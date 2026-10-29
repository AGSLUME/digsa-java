package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.PasoAplicacion;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.CampoOpcional;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tablas de apoyo de los tableros al aplicar un cargue (analitica-tableros, «Actualización de los tableros con cada cargue»): corre en la
 * transacción que inserta los hechos y marca el cargue «aplicado», así el nuevo corte aparece completo en los tableros apenas se publica,
 * y si falla el cargue queda «fallido» sin filas aquí. Todo es por cargue: un corte anterior lee solo los cargues visibles en él y da lo
 * mismo aunque después lleguen otros (o uno reemplace su periodo).
 * <ol>
 * <li>numera los pacientes nuevos (ana_pacientes);</li>
 * <li>copia los hechos del cargue a la tabla angosta (ana_hechos_tablero), ordenados por fecha para que el BRIN pode el periodo;</li>
 * <li>guarda los valores de filtro del cargue con su Fuerza y ESM (ana_cargue_valores);</li>
 * <li>fija los campos opcionales presentes (ana_cargues.campos_presentes).</li>
 * </ol>
 * Agrupa por hash (work_mem de la aplicación del cargue: 256 MB) y compara el seudónimo por bytes (intercalación «C»).
 */
@Component
class PasoTablas implements PasoAplicacion {

    private static final Logger LOG = LoggerFactory.getLogger(PasoTablas.class);

    private final Precalentador precalentador;

    PasoTablas(Precalentador precalentador) {
        this.precalentador = precalentador;
    }

    @Override
    public void alAplicar(JdbcClient jdbc, long cargueId, LocalDate desde, LocalDate hasta) {
        long t0 = System.nanoTime();
        // Agrupar por hash: el planificador sobrestima los grupos y elegiría ordenar (2–3 veces más lento); se restablece al final
        jdbc.sql("SET LOCAL enable_sort = off").update();
        // La fecha acota las particiones mensuales que se leen (poda); el BRIN de cargue_id ubica las filas del cargue dentro de ellas
        int nuevos = jdbc.sql("""
                INSERT INTO ana_pacientes (seudo)
                SELECT x.paciente_seudo COLLATE "C" FROM ana_dispensaciones x
                WHERE x.cargue_id = :c AND x.fecha BETWEEN :desde AND :hasta
                GROUP BY x.paciente_seudo COLLATE "C"
                ON CONFLICT (seudo) DO NOTHING""").param("c", cargueId).param("desde", desde).param("hasta", hasta).update();
        long t1 = System.nanoTime();
        int filas = jdbc.sql("""
                INSERT INTO ana_hechos_tablero (cargue_id, fecha, paciente_id, fuerza, disan, regional_id, punto_id, esm_codigo, modalidad,
                                                medicamento_id, cum, cie10, tipo_formula, especialidad, numero_formula, cantidad_milesimas, valor_centavos)
                SELECT x.cargue_id, x.fecha, p.id, x.fuerza, x.disan, x.regional_id, x.punto_id, x.esm_codigo, x.modalidad, x.medicamento_id, x.cum,
                       x.cie10, x.tipo_formula, x.especialidad, x.numero_formula, (x.cantidad * 1000)::bigint, (x.valor_total * 100)::bigint
                FROM ana_dispensaciones x JOIN ana_pacientes p ON p.seudo = x.paciente_seudo COLLATE "C"
                WHERE x.cargue_id = :c AND x.fecha BETWEEN :desde AND :hasta
                ORDER BY x.fecha""").param("c", cargueId).param("desde", desde).param("hasta", hasta).update();
        long t2 = System.nanoTime();
        // Dos recorridos livianos (por hash, pocas combinaciones cada uno) en vez de una combinación de 9 columnas: territorio y producto
        jdbc.sql("""
                WITH c AS MATERIALIZED (
                    SELECT fuerza, punto_id, disan, regional_id, esm_codigo, modalidad FROM ana_hechos_tablero
                    WHERE cargue_id = :c AND fecha BETWEEN :desde AND :hasta GROUP BY 1, 2, 3, 4, 5, 6)
                INSERT INTO ana_cargue_valores (cargue_id, variable, valor, fuerza, punto_id)
                SELECT DISTINCT CAST(:c AS bigint), v.variable, v.valor, c.fuerza, c.punto_id
                FROM c CROSS JOIN LATERAL (VALUES ('fuerza', c.fuerza), ('disan', c.disan), ('regional', c.regional_id::text), ('esm', c.esm_codigo),
                                                  ('modalidad', c.modalidad)) AS v (variable, valor)
                WHERE v.valor IS NOT NULL""").param("c", cargueId).param("desde", desde).param("hasta", hasta).update();
        jdbc.sql("""
                WITH c AS MATERIALIZED (
                    SELECT fuerza, punto_id, medicamento_id, cum, cie10 FROM ana_hechos_tablero
                    WHERE cargue_id = :c AND fecha BETWEEN :desde AND :hasta GROUP BY 1, 2, 3, 4, 5)
                INSERT INTO ana_cargue_valores (cargue_id, variable, valor, fuerza, punto_id)
                SELECT DISTINCT CAST(:c AS bigint), v.variable, v.valor, c.fuerza, c.punto_id
                FROM c CROSS JOIN LATERAL (VALUES ('cum', c.cum), ('medicamento', c.medicamento_id::text), ('cie10', c.cie10)) AS v (variable, valor)
                WHERE v.valor IS NOT NULL""").param("c", cargueId).param("desde", desde).param("hasta", hasta).update();
        long t3 = System.nanoTime();
        jdbc.sql("SET LOCAL enable_sort = on").update();
        jdbc.sql("UPDATE ana_cargues SET campos_presentes = CAST(:campos AS text[]) WHERE id = :c")
                .param("campos", presentes(jdbc, cargueId, desde, hasta).toArray(String[]::new)).param("c", cargueId).update();
        // Cuando la aplicación se confirme (nunca si se revierte), los tableros por defecto del nuevo corte se calculan en segundo plano
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    precalentador.programar(cargueId);
                }
            });
        }
        LOG.info("[analitica] Tablas de los tableros del cargue {}: {} pacientes nuevos ({} ms), {} hechos ({} ms), valores de filtro ({} ms), "
                + "campos ({} ms)", cargueId, nuevos, ms(t0, t1), filas, ms(t1, t2), ms(t2, t3), ms(t3, System.nanoTime()));
    }

    /** Campos opcionales presentes: los del mapeo confirmado (archivo) o los que traen los hechos (servicio web). HOMIL aún no viene. */
    private static List<String> presentes(JdbcClient jdbc, long cargueId, LocalDate desde, LocalDate hasta) {
        List<String> r = new ArrayList<>();
        String fuente = jdbc.sql("SELECT fuente FROM ana_cargues WHERE id = :c").param("c", cargueId).query(String.class).single();
        for (CampoOpcional campo : CampoOpcional.values()) {
            if (campo == CampoOpcional.HOMIL) {
                continue;
            }
            boolean hay;
            if ("archivo".equals(fuente)) {
                hay = Boolean.TRUE.equals(jdbc.sql("SELECT coalesce(mapeo @> jsonb_build_array(CAST(:clave AS text)), false) FROM ana_cargues WHERE id = :c")
                        .param("clave", campo.clave()).param("c", cargueId).query(Boolean.class).single());
            } else {
                String col = switch (campo) {
                    case PROFESIONAL -> "profesional_seudo";
                    case ESPECIALIDAD -> "especialidad";
                    case TIPO_FORMULA -> "tipo_formula";
                    default -> "numero_formula";
                };
                hay = Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM ana_dispensaciones x WHERE x.cargue_id = :c AND x.fecha BETWEEN :d AND :h"
                        + " AND x." + col + " IS NOT NULL)").param("c", cargueId).param("d", desde).param("h", hasta).query(Boolean.class).single());
            }
            if (hay) {
                r.add(campo.clave());
            }
        }
        return r;
    }

    private static long ms(long a, long b) {
        return (b - a) / 1_000_000;
    }
}
