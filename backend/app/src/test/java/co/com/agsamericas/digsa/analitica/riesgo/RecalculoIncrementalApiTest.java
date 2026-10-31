package co.com.agsamericas.digsa.analitica.riesgo;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.soporte.Navegador;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.TestPropertySource;

/**
 * Recálculo incremental de cohortes y alertas (A7.4, duda AN.23). Contexto propio (agrega cargues al insumo base de 20 000 filas de 2025):
 * después de cada cargue (mes nuevo, agregar a un mes existente, «Reemplazar el periodo») el recálculo automático debe ser incremental, con
 * los meses esperados, y su resultado IDÉNTICO, tabla por tabla y fila por fila, al de un recorrido completo del mismo corte («Ejecutar»).
 */
@TestPropertySource(properties = {"digsa.analitica.riesgo.incremental=true"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecalculoIncrementalApiTest extends PruebaRiesgo {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Mediciones de la prueba (van a .local/notas/mediciones.md, sección A7.4). */
    private static final List<String> MEDICIONES = new ArrayList<>();

    /**
     * Insumo derivado del sintético: las filas válidas del mes {@code origen} con la fecha corrida {@code meses} meses (mismos pacientes:
     * la continuidad de los ingresos y salidas se pone a prueba).
     */
    private static byte[] derivar(Path csv, YearMonth origen, int meses) throws IOException {
        List<String> lineas = Files.readAllLines(csv, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(lineas.getFirst()).append("\r\n");
        int n = 0;
        for (String l : lineas.subList(1, lineas.size())) {
            String[] c = l.split(";", -1);
            if (c.length < 8) {
                continue;
            }
            LocalDate f;
            try {
                f = LocalDate.parse(c[7], DMY);
            } catch (DateTimeParseException e) {
                continue;
            }
            if (!YearMonth.from(f).equals(origen)) {
                continue;
            }
            c[7] = f.plusMonths(meses).format(DMY);
            sb.append(String.join(";", c)).append("\r\n");
            n++;
        }
        assertThat(n).as("filas del mes " + origen).isGreaterThan(500);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Todo lo que el motor deja para un corte, como filas de texto ordenadas por tabla. */
    private Map<String, List<String>> foto(long corte) {
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("ana_cohortes_serie", filas("""
                SELECT concat_ws('|', version_id, mes, nivel, fuerza, codigo, grupo, pacientes, ingresos, salidas, dispensaciones, unidades_milesimas,
                       gasto_centavos) FROM ana_cohortes_serie WHERE corte_cargue_id = :c ORDER BY 1""", corte));
        f.put("ana_cohortes_miembros", filas("""
                SELECT concat_ws('|', version_id, mes, paciente_id, fuerzas::text, esms::text, fuerzas_esms::text, grupo) FROM ana_cohortes_miembros
                WHERE corte_cargue_id = :c ORDER BY 1""", corte));
        f.put("ana_cohortes_conteos", filas("""
                SELECT concat_ws('|', version_id, desde, hasta, nivel, fuerza, codigo, pacientes) FROM ana_cohortes_conteos WHERE corte_cargue_id = :c
                ORDER BY 1""", corte));
        f.put("ana_cohortes_resultados", filas("""
                SELECT concat_ws('|', version_id, estado, coalesce(motivo, '-'), umbrales::text, fuentes, miembros) FROM ana_cohortes_resultados
                WHERE corte_cargue_id = :c ORDER BY 1""", corte));
        // Todas las alertas (no solo las reevaluadas en el corte), con todas sus columnas (unidad, dimension_texto, aviso, evento…) menos las
        // fechas de generación y actualización
        f.put("ana_alertas", filas("""
                SELECT (to_jsonb(a) - 'generada' - 'actualizada')::text FROM ana_alertas a WHERE :c > 0 ORDER BY a.id""", corte));
        f.put("ana_riesgo_inc_cortes", filas("""
                SELECT concat_ws('|', firma, cargues::text, catalogo::text, primer_mes, ultimo_mes) FROM ana_riesgo_inc_cortes WHERE corte_cargue_id = :c""",
                corte));
        f.put("ana_riesgo_inc_secuencia", filas("""
                SELECT concat_ws('|', tipo, nivel, codigo_nivel, dimension, valor, mes, pacientes, registros, gasto_centavos, esms::text, fuerzas::text,
                       cums::text) FROM ana_riesgo_inc_secuencia WHERE corte_cargue_id = :c ORDER BY 1""", corte));
        f.put("ana_riesgo_inc_puntos", filas("""
                SELECT concat_ws('|', mes, esm, puntos::text) FROM ana_riesgo_inc_puntos WHERE corte_cargue_id = :c ORDER BY 1""", corte));
        return f;
    }

    private List<String> filas(String sql, long corte) {
        return jdbc.sql(sql).param("c", corte).query(String.class).list();
    }

    /** Igualdad tabla por tabla y fila por fila; el mensaje muestra la primera diferencia (no las decenas de miles de filas). */
    private static void iguales(String paso, Map<String, List<String>> incremental, Map<String, List<String>> completo) {
        for (String t : completo.keySet()) {
            List<String> a = incremental.get(t);
            List<String> b = completo.get(t);
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                assertThat(a.get(i)).as(paso + " · " + t + " fila " + i + " (incremental frente a completo)").isEqualTo(b.get(i));
            }
            assertThat(a.size()).as(paso + " · " + t + ": filas del incremental frente al completo").isEqualTo(b.size());
        }
        assertThat(completo.get("ana_cohortes_serie")).as(paso + ": hay serie").isNotEmpty();
        assertThat(completo.get("ana_cohortes_miembros")).as(paso + ": hay membresía").isNotEmpty();
        assertThat(completo.get("ana_riesgo_inc_cortes")).as(paso + ": quedó la versión utilizable").hasSize(1);
    }

    /** Tipos de secuencia que se evalúan en la prueba de equivalencia (con interacciones e indicaciones cargadas). */
    private static final List<String> SECUENCIA = List.of("duplicidad_terapeutica", "interaccion", "marca_estrecho_margen", "fuera_indicacion");

    /**
     * Hay datos que comparar: acumulados de cada tipo de secuencia evaluable, y miembros de GRC y de alto costo dentro de los meses
     * recalculados y fuera de ellos (copiados de la versión anterior).
     */
    private void hayQueComparar(String paso, long corte, String meses) {
        for (String t : SECUENCIA) {
            assertThat(jdbc.sql("SELECT count(*) FROM ana_riesgo_inc_secuencia WHERE corte_cargue_id = :c AND tipo = :t").param("c", corte).param("t", t)
                    .query(Long.class).single()).as(paso + ": acumulados de " + t).isPositive();
        }
        String[] m = meses.split(" a ");
        LocalDate desde = YearMonth.parse(m[0]).atDay(1);
        LocalDate hasta = YearMonth.parse(m[m.length - 1]).atDay(1);
        for (String cohorte : List.of("grc", "alto_costo")) {
            for (boolean dentro : new boolean[] {true, false}) {
                long n = jdbc.sql("""
                        SELECT count(*) FROM ana_cohortes_miembros x JOIN ana_cohortes_versiones v ON v.id = x.version_id
                        JOIN ana_cohortes c ON c.id = v.cohorte_id WHERE x.corte_cargue_id = :corte AND c.codigo = :cod
                        AND (x.mes BETWEEN :d AND :h) = :dentro""").param("corte", corte).param("cod", cohorte).param("d", desde).param("h", hasta)
                        .param("dentro", dentro).query(Long.class).single();
                assertThat(n).as(paso + ": miembros de " + cohorte + (dentro ? " en" : " fuera de") + " los meses recalculados").isPositive();
            }
        }
    }

    /** La última ejecución de cohortes del corte: modo, meses, motivo, filas, duración y etapas. */
    private Map<String, Object> ejecucion(long corte) {
        return jdbc.sql("""
                SELECT id, origen, estado, modo, meses_recalculados, motivo_modo, filas, duracion_ms, etapas_ms::text AS etapas
                FROM ana_cohortes_ejecuciones WHERE corte_cargue_id = :c ORDER BY id DESC LIMIT 1""").param("c", corte).query().listOfRows().getFirst();
    }

    private String porTipo(long ejecucionAlertas) {
        return jdbc.sql("SELECT por_tipo::text FROM ana_alertas_ejecuciones WHERE id = :id").param("id", ejecucionAlertas).query(String.class).single();
    }

    private long[] resumenAlertas(long ejecucionAlertas) {
        return jdbc.sql("SELECT nuevas, actualizadas FROM ana_alertas_ejecuciones WHERE id = :id").param("id", ejecucionAlertas)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
    }

    /**
     * Aplica un cargue, espera su recálculo automático (debe ser incremental con los meses esperados), toma la foto, corre el recorrido
     * completo del mismo corte («Ejecutar») y compara.
     */
    private long pasoEquivalente(Navegador n, String paso, String archivo, byte[] datos, String modo, String desde, String hasta, String mesesEsperados) {
        JsonNode fin = cargarInsumo(n, archivo, datos, modo, desde, hasta);
        assertThat(fin.path("estado").asText()).as(paso + ": " + fin).isEqualTo("aplicado");
        long corte = fin.path("id").asLong();
        long alertasInc = esperarRecalculo(corte);
        Map<String, Object> inc = ejecucion(corte);
        assertThat(inc.get("origen")).isEqualTo("cargue");
        assertThat(inc.get("estado")).as(paso + ": " + inc).isEqualTo("ok");
        assertThat(inc.get("modo")).as(paso + ": " + inc).isEqualTo("incremental");
        assertThat(inc.get("meses_recalculados")).as(paso + ": meses recalculados").isEqualTo(mesesEsperados);
        assertThat((String) inc.get("motivo_modo")).startsWith("desde el corte ");
        assertThat((String) inc.get("etapas")).contains("version_anterior").contains("copia_membresia").contains("conteos").contains("recorrido");
        Map<String, List<String>> fotoInc = foto(corte);
        hayQueComparar(paso, corte, mesesEsperados);
        String porTipoInc = porTipo(alertasInc);
        long[] resInc = resumenAlertas(alertasInc);

        long alertasCompleto = ejecutar(n);
        Map<String, Object> comp = ejecucion(corte);
        assertThat(comp.get("modo")).as(paso).isEqualTo("completo");
        assertThat(comp.get("meses_recalculados")).isNull();
        assertThat((String) comp.get("motivo_modo")).contains("bajo demanda");
        Map<String, List<String>> fotoComp = foto(corte);
        iguales(paso, fotoInc, fotoComp);
        // Las alertas: mismas candidatas por tipo; el completo no crea ninguna nueva y reevalúa todas las del incremental
        assertThat(porTipo(alertasCompleto)).as(paso + ": alertas por tipo").isEqualTo(porTipoInc);
        long[] resComp = resumenAlertas(alertasCompleto);
        assertThat(resComp[0]).as(paso + ": alertas nuevas del completo").isZero();
        assertThat(resComp[1]).as(paso + ": alertas reevaluadas del completo").isEqualTo(resInc[0] + resInc[1]);
        MEDICIONES.add(paso + ": incremental " + inc.get("duracion_ms") + " ms (filas leídas " + inc.get("filas") + ", meses " + mesesEsperados + ", etapas "
                + inc.get("etapas") + ") · completo " + comp.get("duracion_ms") + " ms (filas " + comp.get("filas") + ", etapas " + comp.get("etapas")
                + ") · membresía " + fotoComp.get("ana_cohortes_miembros").size() + " filas, serie " + fotoComp.get("ana_cohortes_serie").size()
                + ", alertas " + fotoComp.get("ana_alertas").size() + ", secuencia " + fotoComp.get("ana_riesgo_inc_secuencia").size());
        System.out.println("[A7.4] " + MEDICIONES.getLast());
        return corte;
    }

    @Test
    @Order(1)
    @DisplayName("mes nuevo, agregar a un mes existente y «Reemplazar el periodo»: el recálculo es incremental con los meses esperados e idéntico al completo")
    void equivalencia() throws IOException {
        Base b = base();
        Map<String, Object> primero = ejecucion(b.cargue());
        assertThat(primero.get("modo")).isEqualTo("completo");
        assertThat((String) primero.get("motivo_modo")).contains("primer corte");
        Navegador n = nuevo("anainc", co.com.agsamericas.digsa.reglas.Perfil.ANALISTA);
        Path csv = b.esperado().csv();
        // Interacciones, indicaciones y vitales de ejemplo: los cuatro tipos de secuencia se evalúan (y se comparan); «Ejecutar» deja la
        // versión utilizable del corte base con la firma de esas listas
        List<String> par = co.com.agsamericas.digsa.analitica.GeneradorSintetico.INTERACCION_EJEMPLO;
        Navegador.Respuesta ref = cargarReferencia(n, "interacciones", "principio1;principio2;nota\r\n" + par.get(0) + ";" + par.get(1) + ";Ejemplo ficticio\r\n");
        assertThat(ref.estado()).as(ref.texto()).isEqualTo(201);
        cargarIndicacionesYVitales(n);
        ejecutar(n);
        assertThat(ejecucion(b.cargue()).get("modo")).isEqualTo("completo");

        // 1. Mes nuevo (2026-01, los pacientes de diciembre): la censura de salidas cambia ⇒ desde 2025-11
        pasoEquivalente(n, "mes nuevo 2026-01", "insumo_2026_01.csv", derivar(csv, YearMonth.of(2025, 12), 1), "agregar", null, null, "2025-11 a 2026-01");
        // 2. Agregar a un mes existente (2025-06, los de mayo): ventana de 12 meses ⇒ desde 2025-04 hasta el último mes
        pasoEquivalente(n, "agregar a 2025-06", "insumo_2025_06_extra.csv", derivar(csv, YearMonth.of(2025, 5), 1), "agregar", null, null,
                "2025-04 a 2026-01");
        // 3. Reemplazar 2026-01 (sale el cargue del paso 1, entran los de noviembre corridos dos meses)
        long corte = pasoEquivalente(n, "reemplazar 2026-01", "insumo_2026_01_reemplazo.csv", derivar(csv, YearMonth.of(2025, 11), 2), "reemplazar",
                "2026-01-01", "2026-01-31", "2025-11 a 2026-01");
        assertThat(jdbc.sql("SELECT count(*) FROM ana_cargues WHERE reemplazado_por = :c").param("c", corte).query(Long.class).single()).isEqualTo(1);

        // El registro de la ejecución (API de ejecuciones de cohortes) y la bitácora dicen el modo y los meses, sin datos personales
        JsonNode ej = ok(n.get("/api/analitica/cohortes/ejecuciones"));
        JsonNode inc = null;
        for (JsonNode x : ej) {
            if (x.path("corte").asLong() == corte && "incremental".equals(x.path("modo").asText())) {
                inc = x;
                break;
            }
        }
        assertThat(inc).as(ej.toString()).isNotNull();
        assertThat(inc.path("mesesRecalculados").asText()).isEqualTo("2025-11 a 2026-01");
        assertThat(inc.path("etapasMs").has("recorrido")).isTrue();
        sinIndividualizacion(ej.toString(), b);
        List<String> bit = jdbc.sql("SELECT coalesce(descripcion, '') FROM log_transacciones WHERE tipo = 'analitica_cohorte_recalculo' AND descripcion LIKE :d")
                .param("d", "%corte " + corte + " ·%").query(String.class).list();
        assertThat(bit).anyMatch(d -> d.contains("incremental desde el corte") && d.contains("meses recalculados 2025-11 a 2026-01"));
        assertThat(bit).anyMatch(d -> d.contains("completo (recálculo bajo demanda"));
        bit.forEach(d -> sinIndividualizacion(d, b));
    }

    @Test
    @Order(2)
    @DisplayName("respaldo a completo: cambió un parámetro de alertas, o la versión anterior no está (incompleta o purgada); después vuelve el incremental")
    void respaldoACompleto() throws IOException {
        Base b = base();
        Navegador n = nuevo("anaincr", co.com.agsamericas.digsa.reglas.Perfil.ANALISTA);
        esperarLibre();
        Path csv = b.esperado().csv();
        // Parámetros nuevos de duplicidad ⇒ otra firma
        Navegador.Respuesta p = n.put("/api/analitica/alertas/parametros/duplicidad_terapeutica", Map.of("alta", "25"));
        assertThat(p.estado()).as(p.texto()).isEqualTo(200);
        JsonNode fin = cargarInsumo(n, "insumo_2026_02.csv", derivar(csv, YearMonth.of(2025, 10), 4), "agregar", null, null);
        long corte = fin.path("id").asLong();
        esperarRecalculo(corte);
        Map<String, Object> e = ejecucion(corte);
        assertThat(e.get("modo")).as(e.toString()).isEqualTo("completo");
        assertThat((String) e.get("motivo_modo")).contains("cambió la configuración");
        assertThat(e.get("estado")).isEqualTo("ok");

        // Sin versión anterior utilizable (borrada: como un recálculo anterior incompleto) ⇒ completo
        jdbc.sql("DELETE FROM ana_riesgo_inc_cortes").update();
        fin = cargarInsumo(n, "insumo_2026_03.csv", derivar(csv, YearMonth.of(2025, 9), 6), "agregar", null, null);
        corte = fin.path("id").asLong();
        esperarRecalculo(corte);
        e = ejecucion(corte);
        assertThat(e.get("modo")).as(e.toString()).isEqualTo("completo");
        assertThat((String) e.get("motivo_modo")).contains("no hay versión anterior utilizable");

        // La versión anterior sin la membresía de alguna cohorte (purgada) ⇒ completo
        jdbc.sql("UPDATE ana_cohortes_resultados SET miembros = false WHERE corte_cargue_id = :c AND version_id = (SELECT min(version_id) FROM "
                + "ana_cohortes_resultados WHERE corte_cargue_id = :c)").param("c", corte).update();
        fin = cargarInsumo(n, "insumo_2026_04.csv", derivar(csv, YearMonth.of(2025, 8), 8), "agregar", null, null);
        long corte4 = fin.path("id").asLong();
        esperarRecalculo(corte4);
        e = ejecucion(corte4);
        assertThat(e.get("modo")).as(e.toString()).isEqualTo("completo");
        assertThat((String) e.get("motivo_modo")).contains("no conserva la membresía");

        // Con la versión utilizable del completo, el siguiente cargue vuelve a ser incremental
        fin = cargarInsumo(n, "insumo_2026_05.csv", derivar(csv, YearMonth.of(2025, 7), 10), "agregar", null, null);
        long corte5 = fin.path("id").asLong();
        esperarRecalculo(corte5);
        e = ejecucion(corte5);
        assertThat(e.get("modo")).as(e.toString()).isEqualTo("incremental");
        assertThat(e.get("meses_recalculados")).isEqualTo("2026-03 a 2026-05");
        // Solo quedan las versiones utilizables de los dos últimos cortes
        assertThat(jdbc.sql("SELECT array_agg(corte_cargue_id ORDER BY corte_cargue_id)::text FROM ana_riesgo_inc_cortes").query(String.class).single())
                .isEqualTo("{" + corte4 + "," + corte5 + "}");

        // Una corrida parcial del corte («Recalcular» una cohorte: versiones puntuales) reescribe su serie y su membresía e invalida su versión
        // utilizable; el cargue siguiente ya no parte de él: parte de la versión anterior que sigue siendo válida (corte4) y da lo mismo que
        // el completo
        esperarLibre();
        long renal = jdbc.sql("SELECT id FROM ana_cohortes WHERE codigo = 'renal'").query(Long.class).single();
        Navegador.Respuesta r = n.post("/api/analitica/cohortes/" + renal + "/recalcular", null);
        assertThat(r.estado()).as(r.texto()).isBetween(200, 202);
        esperarLibre();
        assertThat(ejecucion(corte5).get("motivo_modo")).isEqualTo("recálculo de versiones puntuales");
        assertThat(jdbc.sql("SELECT count(*) FROM ana_riesgo_inc_cortes WHERE corte_cargue_id = :c").param("c", corte5).query(Long.class).single())
                .as("la corrida parcial invalida la versión utilizable del corte").isZero();
        long corte6 = pasoEquivalente(n, "tras una corrida parcial", "insumo_2026_06.csv", derivar(csv, YearMonth.of(2025, 4), 14), "agregar", null, null,
                "2026-03 a 2026-06");
        assertThat(jdbc.sql("SELECT motivo_modo FROM ana_cohortes_ejecuciones WHERE corte_cargue_id = :c AND origen = 'cargue'").param("c", corte6)
                .query(String.class).single()).isEqualTo("desde el corte " + corte4);

        // Cambió una lista de referencia (huérfanas, nueva versión) ⇒ otra firma ⇒ completo
        Navegador.Respuesta h = cargarReferencia(n, "huerfanas", co.com.agsamericas.digsa.analitica.GeneradorSintetico.HUERFANAS_EJEMPLO
                + ";Ejemplo ficticio\r\nE76;Otro ejemplo ficticio\r\n");
        assertThat(h.estado()).as(h.texto()).isEqualTo(201);
        fin = cargarInsumo(n, "insumo_2026_07.csv", derivar(csv, YearMonth.of(2025, 3), 16), "agregar", null, null);
        long corte7 = fin.path("id").asLong();
        esperarRecalculo(corte7);
        e = ejecucion(corte7);
        assertThat(e.get("modo")).as(e.toString()).isEqualTo("completo");
        assertThat((String) e.get("motivo_modo")).contains("cambió la configuración");

        // Un ESM que llegó con dos puntos en la historia (simulado en la versión utilizable) ⇒ el punto depende del orden ⇒ completo
        int n7 = jdbc.sql("""
                UPDATE ana_riesgo_inc_puntos SET puntos = puntos || CAST(987654321 AS bigint) WHERE corte_cargue_id = :c
                AND (mes, esm) = (SELECT mes, esm FROM ana_riesgo_inc_puntos WHERE corte_cargue_id = :c ORDER BY mes, esm LIMIT 1)""").param("c", corte7)
                .update();
        assertThat(n7).isEqualTo(1);
        fin = cargarInsumo(n, "insumo_2026_08.csv", derivar(csv, YearMonth.of(2025, 2), 18), "agregar", null, null);
        long corte8 = fin.path("id").asLong();
        esperarRecalculo(corte8);
        e = ejecucion(corte8);
        assertThat(e.get("modo")).as(e.toString()).isEqualTo("completo");
        assertThat((String) e.get("motivo_modo")).contains("más de un punto");
        MEDICIONES.forEach(m -> System.out.println("[A7.4] " + m));
    }
}
