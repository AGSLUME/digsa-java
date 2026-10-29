package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Validación por conjuntos sobre el staging de un cargue (A5 4.7.1 punto 3; RESP L2279): cada registro se rechaza con su línea y TODAS
 * sus causas del catálogo cerrado (reglas/AnaliticaIngesta.Causa). Las de campo van por lotes de líneas (avance visible); los duplicados
 * y «Ya cargado» miran el cargue completo. Nunca se guarda el valor de la celda, solo la línea, la causa y la columna.
 */
@Component
class ValidadorStaging {

    /**
     * Número canónico (lo escribe CopiadorStaging) que cabe en la columna de los hechos sin perder nada al guardarlo: la cantidad con
     * hasta 3 decimales (numeric(14,3): un 0,0004 redondearía a 0 y violaría el CHECK de la carga completa); el valor con 13 dígitos
     * enteros (numeric(16,2): al redondear a 2 decimales un 99999999999999,999 se desbordaría).
     */
    static final String RE_CANTIDAD = "^-?\\d{1,11}(\\.\\d{1,3})?$";
    static final String RE_VALOR = "^-?\\d{1,13}(\\.\\d+)?$";
    static final String RE_FECHA = "^\\d{4}-\\d{2}-\\d{2}$";
    static final String RE_CUM = "^\\d{1,9}-\\d{1,2}$";
    /** Fechas anteriores no se aceptan (también acota las particiones). */
    static final LocalDate FECHA_MINIMA = LocalDate.of(1990, 1, 1);

    /** Resultado: rechazados (registros), conteo por causa y periodo de las fechas válidas. */
    record Resultado(long rechazados, Map<String, Long> motivos) {
    }

    /**
     * Causas de campo: una sola pasada por registro arma la lista de sus causas («causa|columna») y solo los registros con alguna se
     * expanden (unnest). Fuerza y modalidad se cruzan con su catálogo por LEFT JOIN.
     */
    private static final String CAMPOS = """
            INSERT INTO ana_cargue_rechazos (cargue_id, orden, linea, causa, columna)
            SELECT x.cargue_id, x.linea, x.linea_origen, split_part(c, '|', 1), nullif(split_part(c, '|', 2), '')
            FROM (
                SELECT s.cargue_id, s.linea, s.linea_origen,
                       CASE WHEN s.campos_distintos THEN ARRAY['campos_distintos|'] ELSE array_remove(ARRAY[
                           CASE WHEN s.paciente_seudo IS NULL THEN 'obligatorio_vacio|paciente' END,
                           CASE WHEN s.fuerza IS NULL THEN 'obligatorio_vacio|fuerza' END,
                           CASE WHEN s.esm IS NULL THEN 'obligatorio_vacio|esm' END,
                           CASE WHEN s.modalidad IS NULL THEN 'obligatorio_vacio|modalidad' END,
                           CASE WHEN s.fecha IS NULL THEN 'obligatorio_vacio|fecha' END,
                           CASE WHEN s.medicamento IS NULL AND s.cum IS NULL THEN 'obligatorio_vacio|medicamento o CUM' END,
                           CASE WHEN s.cantidad IS NULL THEN 'obligatorio_vacio|cantidad' END,
                           CASE WHEN s.valor IS NULL THEN 'obligatorio_vacio|valor' END,
                           CASE WHEN s.fecha !~ :reFecha THEN 'fecha_invalida|fecha' END,
                           CASE WHEN s.fecha ~ :reFecha THEN CASE WHEN s.fecha::date > :hoy OR s.fecha::date < :minima
                                     OR (CAST(:desde AS date) IS NOT NULL AND s.fecha::date < CAST(:desde AS date))
                                     OR (CAST(:hasta AS date) IS NOT NULL AND s.fecha::date > CAST(:hasta AS date))
                                THEN 'fecha_fuera_periodo|fecha' END END,
                           CASE WHEN s.cantidad !~ :reCantidad THEN 'numero_invalido|cantidad' END,
                           CASE WHEN s.valor !~ :reValor THEN 'numero_invalido|valor' END,
                           CASE WHEN s.valor_unitario !~ :reValor THEN 'numero_invalido|valor unitario' END,
                           CASE WHEN s.cantidad ~ :reCantidad THEN CASE WHEN s.cantidad::numeric <= 0 THEN 'cantidad_no_positiva|cantidad' END END,
                           CASE WHEN s.valor ~ :reValor THEN CASE WHEN s.valor::numeric < 0 THEN 'valor_negativo|valor' END END,
                           CASE WHEN s.cum !~ :reCum THEN 'cum_invalido|cum' END,
                           CASE WHEN char_length(s.medicamento) > :textoMax THEN 'texto_largo|medicamento' END,
                           CASE WHEN char_length(s.cum_original) > :textoMax THEN 'texto_largo|cum' END,
                           CASE WHEN char_length(s.principio_activo) > :textoMax THEN 'texto_largo|principio activo' END,
                           CASE WHEN s.fuerza IS NOT NULL AND f.codigo IS NULL THEN 'fuerza_no_reconocida|fuerza' END,
                           CASE WHEN s.modalidad IS NOT NULL AND m.nombre IS NULL THEN 'modalidad_no_reconocida|modalidad' END
                       ]::text[], NULL) END AS causas
                FROM ana_staging_dispensacion s
                LEFT JOIN par_fuerzas f ON f.codigo = s.fuerza
                LEFT JOIN par_modalidades m ON m.nombre = s.modalidad
                WHERE s.cargue_id = :c AND s.linea BETWEEN :a AND :b
            ) x
            CROSS JOIN LATERAL unnest(x.causas) AS c
            WHERE cardinality(x.causas) > 0""";

    /** Duplicado exacto: misma huella de todas las columnas mapeadas; se procesa la primera y se rechazan las demás. */
    private static final String DUPLICADOS = """
            INSERT INTO ana_cargue_rechazos (cargue_id, orden, linea, causa, columna, referencia)
            SELECT x.cargue_id, x.linea, x.linea_origen, 'duplicado_exacto', NULL, x.ph
            FROM (SELECT s.cargue_id, s.linea, s.linea_origen,
                         row_number() OVER w AS nh, first_value(s.linea_origen) OVER w AS ph
                  FROM ana_staging_dispensacion s
                  WHERE s.cargue_id = :c AND NOT s.campos_distintos
                  WINDOW w AS (PARTITION BY s.huella ORDER BY s.linea)) x
            WHERE x.nh > 1""";

    /** Con id de dispensación: duplicado por llave natural (misma llave, distinto contenido; el exacto ya quedó arriba). */
    private static final String DUPLICADOS_LLAVE = """
            INSERT INTO ana_cargue_rechazos (cargue_id, orden, linea, causa, columna, referencia)
            SELECT x.cargue_id, x.linea, x.linea_origen, 'duplicado_llave', NULL, x.pl
            FROM (SELECT s.cargue_id, s.linea, s.linea_origen,
                         row_number() OVER w AS nl, first_value(s.linea_origen) OVER w AS pl
                  FROM ana_staging_dispensacion s
                  WHERE s.cargue_id = :c AND NOT s.campos_distintos
                  WINDOW w AS (PARTITION BY s.llave ORDER BY s.linea)) x
            WHERE x.nl > 1
              AND NOT EXISTS (SELECT 1 FROM ana_cargue_rechazos r WHERE r.cargue_id = x.cargue_id AND r.orden = x.linea
                              AND r.causa = 'duplicado_exacto')""";

    /**
     * «Ya cargado»: la llave natural está en un cargue aplicado vigente (no en los que este reemplaza). Se busca en ana_llaves (una
     * búsqueda por fila en una tabla no particionada), no en los hechos: su costo no crece con los meses de historia.
     */
    private static final String YA_CARGADO = """
            INSERT INTO ana_cargue_rechazos (cargue_id, orden, linea, causa, columna)
            SELECT s.cargue_id, s.linea, s.linea_origen, 'ya_cargado', NULL
            FROM ana_staging_dispensacion s
            WHERE s.cargue_id = :c AND NOT s.campos_distintos
              AND EXISTS (SELECT 1 FROM ana_llaves l JOIN ana_cargues k ON k.id = l.cargue_id
                          WHERE l.llave = s.llave AND k.estado = 'aplicado' AND k.reemplazado_por IS NULL AND k.id <> ALL(:reemplaza))""";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    ValidadorStaging(JdbcClient jdbc, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transacciones);
    }

    /**
     * Valida el staging del cargue y deja sus rechazos (borra los de un intento anterior).
     *
     * @param hoy        las fechas posteriores se rechazan
     * @param desde      inicio del periodo declarado (o null)
     * @param hasta      fin del periodo declarado (o null)
     * @param porId      el insumo trae id de dispensación (llave natural propia)
     * @param reemplaza  cargues cuyo periodo reemplaza este (no cuentan para «Ya cargado»)
     * @param lote       registros por lote
     * @param avance     recibe el porcentaje de la validación (0 a 100)
     */
    Resultado validar(long cargueId, LocalDate hoy, LocalDate desde, LocalDate hasta, boolean porId, List<Long> reemplaza, int lote,
                      IntConsumer avance) {
        jdbc.sql("DELETE FROM ana_cargue_rechazos WHERE cargue_id = :c").param("c", cargueId).update();
        Map<String, Object> rango = jdbc.sql("SELECT min(linea) AS a, max(linea) AS b FROM ana_staging_dispensacion WHERE cargue_id = :c")
                .param("c", cargueId).query().singleRow();
        if (rango.get("a") != null) {
            long a = ((Number) rango.get("a")).longValue();
            long b = ((Number) rango.get("b")).longValue();
            long total = Math.max(1, b - a + 1);
            for (long desdeLinea = a; desdeLinea <= b; desdeLinea += lote) {
                long hastaLinea = Math.min(b, desdeLinea + lote - 1);
                jdbc.sql(CAMPOS).param("c", cargueId).param("a", desdeLinea).param("b", hastaLinea)
                        .param("reFecha", RE_FECHA).param("reCantidad", RE_CANTIDAD).param("reValor", RE_VALOR).param("reCum", RE_CUM)
                        .param("textoMax", AnaliticaIngesta.TEXTO_MAX)
                        .param("hoy", hoy).param("minima", FECHA_MINIMA).param("desde", desde).param("hasta", hasta).update();
                avance.accept((int) Math.min(80, (hastaLinea - a + 1) * 80 / total));
            }
            tx.executeWithoutResult(t -> {
                jdbc.sql("SET LOCAL work_mem = '256MB'").update(); // las ventanas ordenan el cargue completo
                jdbc.sql(DUPLICADOS).param("c", cargueId).update();
                if (porId) {
                    jdbc.sql(DUPLICADOS_LLAVE).param("c", cargueId).update();
                }
            });
            avance.accept(90);
            jdbc.sql(YA_CARGADO).param("c", cargueId).param("reemplaza", reemplaza.toArray(Long[]::new)).update();
            avance.accept(100);
        }
        Map<String, Long> motivos = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT causa, count(DISTINCT orden) AS n FROM ana_cargue_rechazos WHERE cargue_id = :c GROUP BY causa ORDER BY count(DISTINCT orden) DESC, causa""")
                .param("c", cargueId).query((rs, n) -> motivos.put(rs.getString(1), rs.getLong(2))).list();
        long rechazados = jdbc.sql("SELECT count(DISTINCT orden) FROM ana_cargue_rechazos WHERE cargue_id = :c").param("c", cargueId)
                .query(Long.class).single();
        return new Resultado(rechazados, motivos);
    }
}
