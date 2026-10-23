package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.analitica.PasoAplicacion;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Carga los registros válidos del staging en ana_dispensaciones (particionada por mes) dentro de la transacción de la aplicación del
 * cargue (todo o nada, A5 L723–731): crea antes las particiones que falten, resuelve Fuerza → DISAN y ESM → punto y regional, guarda
 * las llaves naturales en ana_llaves, llena la dimensión de tiempo, marca los cargues reemplazados y corre los pasos de aplicación
 * (agregados de A3). El que llama marca el cargue «aplicado» en la misma transacción.
 */
@Component
class AplicadorHechos {

    /** Hechos insertados: cuántos, cuántos sin ESM en puntos y el periodo procesado. */
    record Carga(long procesados, long esmSinCorrespondencia, LocalDate desde, LocalDate hasta) {
    }

    /** Solo para pruebas: se llama tras insertar los hechos, dentro de la transacción (la de falla lanza aquí). */
    volatile Consumer<Long> trasInsertar = id -> { };

    private static final String INSERTAR = """
            WITH puntos_sigla AS (
                SELECT DISTINCT ON (upper(sigla)) upper(sigla) AS sigla, id, regional_id FROM puntos WHERE sigla IS NOT NULL ORDER BY upper(sigla), id
            ), ins AS (
                INSERT INTO ana_dispensaciones (cargue_id, linea_origen, paciente_seudo, anio_nacimiento, grupo_edad, fuerza, disan, regional_id,
                       punto_id, esm_codigo, modalidad, fecha, cum, cum_original, medicamento_original, principio_activo_original, atc_original,
                       cie10, cantidad, valor_total, valor_unitario, codigo_ssfm, profesional_seudo, especialidad, tipo_formula, numero_formula,
                       id_dispensacion, item, llave)
                SELECT s.cargue_id, s.linea_origen, s.paciente_seudo,
                       CASE WHEN s.anio_nacimiento ~ '^\\d{4}$' AND s.anio_nacimiento::int BETWEEN 1900 AND 2100 THEN s.anio_nacimiento::smallint END,
                       s.grupo_edad, s.fuerza, f.sanidad, p.regional_id, p.id, s.esm, s.modalidad, s.fecha::date, s.cum, s.cum_original,
                       s.medicamento, s.principio_activo, s.atc, s.cie10, round(s.cantidad::numeric, 3), round(s.valor::numeric, 2),
                       round(s.valor_unitario::numeric, 2), s.codigo_ssfm, s.profesional_seudo, s.especialidad, s.tipo_formula, s.numero_formula,
                       s.id_dispensacion, s.item, s.llave
                FROM ana_staging_dispensacion s
                JOIN par_fuerzas f ON f.codigo = s.fuerza
                LEFT JOIN puntos_sigla p ON p.sigla = s.esm
                WHERE s.cargue_id = :c
                  AND NOT EXISTS (SELECT 1 FROM ana_cargue_rechazos r WHERE r.cargue_id = :c AND r.orden = s.linea)
                RETURNING punto_id, fecha, llave
            ), llaves AS (
                INSERT INTO ana_llaves (llave, cargue_id) SELECT DISTINCT llave, CAST(:c AS bigint) FROM ins ON CONFLICT DO NOTHING
            )
            SELECT count(*) AS procesados, count(*) FILTER (WHERE punto_id IS NULL) AS sin_esm, min(fecha) AS desde, max(fecha) AS hasta
            FROM ins""";

    private final List<PasoAplicacion> pasos;

    AplicadorHechos(List<PasoAplicacion> pasos) {
        this.pasos = List.copyOf(pasos);
    }

    /** Meses (primer día) con fechas válidas aún no rechazadas: solo esos reciben partición (nunca los huecos entre fechas sueltas). */
    static List<LocalDate> mesesValidos(JdbcClient jdbc, long cargueId) {
        return jdbc.sql("""
                SELECT DISTINCT date_trunc('month', s.fecha::date)::date FROM ana_staging_dispensacion s
                WHERE s.cargue_id = :c AND s.fecha ~ '^\\d{4}-\\d{2}-\\d{2}$'
                  AND NOT EXISTS (SELECT 1 FROM ana_cargue_rechazos r WHERE r.cargue_id = :c AND r.orden = s.linea)
                ORDER BY 1""")
                .param("c", cargueId).query((rs, n) -> fecha(rs.getDate(1))).list();
    }

    /** Crea las particiones mensuales que falten, mes a mes (transacción propia, antes de la carga). */
    static void asegurarParticiones(JdbcClient jdbc, List<LocalDate> meses) {
        for (LocalDate mes : meses) {
            jdbc.sql("SELECT ana_asegurar_particiones(:d, :d)").param("d", mes).query(Integer.class).single();
        }
    }

    /**
     * Inserta los hechos y corre los pasos de aplicación. Debe correr en la transacción que luego marca el cargue «aplicado».
     *
     * @param reemplaza cargues que este reemplaza: dejan de verse desde {@code ahora}
     */
    Carga cargar(JdbcClient jdbc, long cargueId, List<Long> reemplaza, java.time.OffsetDateTime ahora, Runnable finHechos) {
        jdbc.sql("SET LOCAL work_mem = '256MB'").update();
        Carga c = jdbc.sql(INSERTAR).param("c", cargueId)
                .query((rs, n) -> new Carga(rs.getLong(1), rs.getLong(2), fecha(rs.getDate(3)), fecha(rs.getDate(4)))).single();
        trasInsertar.accept(cargueId);
        if (c.desde() != null) {
            jdbc.sql("""
                    INSERT INTO ana_dim_tiempo (fecha, semana, mes, bimestre, trimestre, semestre, anio)
                    SELECT d::date, to_char(d, 'IYYY-"W"IW'), to_char(d, 'YYYY-MM'),
                           to_char(d, 'YYYY') || '-B' || ((extract(month FROM d)::int + 1) / 2),
                           to_char(d, 'YYYY') || '-T' || extract(quarter FROM d)::int,
                           to_char(d, 'YYYY') || '-S' || CASE WHEN extract(month FROM d) <= 6 THEN 1 ELSE 2 END,
                           extract(year FROM d)::smallint
                    FROM generate_series(CAST(:d AS date), CAST(:h AS date), interval '1 day') AS d
                    ON CONFLICT (fecha) DO NOTHING""").param("d", c.desde()).param("h", c.hasta()).update();
        }
        if (!reemplaza.isEmpty()) {
            jdbc.sql("""
                    UPDATE ana_cargues SET reemplazado_por = :c, reemplazado_en = :ahora
                    WHERE id = ANY(:ids) AND estado = 'aplicado' AND reemplazado_por IS NULL""")
                    .param("c", cargueId).param("ahora", ahora).param("ids", reemplaza.toArray(Long[]::new)).update();
        }
        finHechos.run();
        if (c.desde() != null) {
            for (PasoAplicacion p : pasos) {
                p.alAplicar(jdbc, cargueId, c.desde(), c.hasta());
            }
        }
        return c;
    }

    private static LocalDate fecha(Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
