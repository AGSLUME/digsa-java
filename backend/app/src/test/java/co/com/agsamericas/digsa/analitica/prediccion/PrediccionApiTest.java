package co.com.agsamericas.digsa.analitica.prediccion;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.reglas.AnaliticaPrediccion;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Tablero 12 «Predictivo» (analitica-cohortes-alertas, A5): contexto propio (base embebida propia) con el entrenamiento automático
 * encendido. Insumo sintético pequeño con cifras conocidas (sin datos reales): BAS02 (EJC) y DMBOG (ARC) con una dispensación por mes cada
 * uno el último día del mes; gasto nacional del mes i (1 = enero de 2025) = 1500 + 150 · i. Cargue 1: enero a marzo de 2025 (historia
 * insuficiente); cargue 2: abril a diciembre (un año completo). Las pruebas fijan el corte de cada caso: el orden entre ellas no importa.
 */
@TestPropertySource(properties = {"digsa.analitica.prediccion.entrenar-al-aplicar=true"})
class PrediccionApiTest extends PruebaIntegracion {

    static final int PARTE = 1024 * 1024;
    static final List<String> TERMINALES = List.of("aplicado", "rechazado", "fallido", "duplicado", "vencido", "descartado");

    /** Cargues base (uno por contexto). */
    record Base(long c1, long c2) {
    }

    private static final Map<Object, Base> BASES = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private static int documento = 0;

    Navegador como(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    Navegador nuevo(String prefijo, Perfil... perfiles) {
        return como(crearUsuario(prefijo, perfiles));
    }

    static JsonNode ok(Navegador.Respuesta r) {
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        return r.json();
    }

    // ---------- Insumo sintético ----------

    /** Una dispensación el último día del mes: «;» con coma decimal, como el insumo del OPL (paciente ficticio). */
    static synchronized String fila(String fuerza, String esm, YearMonth mes, BigDecimal valor) {
        documento++;
        LocalDate f = mes.atEndOfMonth();
        String v = valor.setScale(2).toPlainString().replace('.', ',');
        return String.join(";", "CC", Long.toString(70_000_000L + documento), "PACIENTE SINTETICO " + documento, "01/01/1980", fuerza, esm, "Dispensación",
                String.format("%02d/%02d/%04d", f.getDayOfMonth(), f.getMonthValue(), f.getYear()), "LOSARTAN 50 MG TABLETA", "20010037-2", "LOSARTAN",
                "C09CA01", "I10X", "1", v, v, "RM1", "Crónica", "P" + documento);
    }

    static byte[] csv(List<String> filas) {
        return ("﻿" + GeneradorSintetico.ENCABEZADO + "\r\n" + String.join("\r\n", filas) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Gasto de BAS02 y DMBOG en el mes i (1 = enero de 2025): suman 1500 + 150 · i. */
    static List<String> filasBase(int desde, int hasta) {
        List<String> l = new ArrayList<>();
        for (int i = desde; i <= hasta; i++) {
            YearMonth m = YearMonth.of(2025, 1).plusMonths(i - 1);
            l.add(fila("EJC", "BAS02", m, BigDecimal.valueOf(1000 + 100L * i)));
            l.add(fila("ARC", "DMBOG", m, BigDecimal.valueOf(500 + 50L * i)));
        }
        return l;
    }

    Base base() {
        Base b = BASES.get(jdbc);
        if (b != null) {
            return b;
        }
        synchronized (PrediccionApiTest.class) {
            if (BASES.get(jdbc) == null) {
                Navegador n = nuevo("anapred", Perfil.ANALISTA);
                long c1 = cargar(n, "pred_2025_t1.csv", filasBase(1, 3));
                // 3 métricas × (nacional + EJC + ARC)
                esperarAutomaticos(c1, 9);
                long c2 = cargar(n, "pred_2025_t2_t4.csv", filasBase(4, 12));
                esperarAutomaticos(c2, 9);
                BASES.put(jdbc, new Base(c1, c2));
            }
            return BASES.get(jdbc);
        }
    }

    long cargar(Navegador n, String nombre, List<String> filas) {
        return cargar(n, nombre, filas, Map.of());
    }

    long cargar(Navegador n, String nombre, List<String> filas, Map<String, Object> periodo) {
        JsonNode fin = cargarInsumo(n, nombre, csv(filas), periodo);
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
        return fin.path("id").asLong();
    }

    void esperarAutomaticos(long corte, int cuantos) {
        long limite = System.nanoTime() + 120_000_000_000L;
        while (jdbc.sql("SELECT count(*) FROM ana_pred_entrenamientos WHERE corte_cargue_id = :c AND origen = 'automatico'").param("c", corte)
                .query(Long.class).single() < cuantos) {
            if (System.nanoTime() > limite) {
                throw new AssertionError("No terminaron los entrenamientos automáticos del corte " + corte);
            }
            dormir(100);
        }
    }

    static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    /** Sube el insumo por partes, confirma la propuesta de mapeo y espera el fin (como la pantalla «Cargues del OPL»). */
    static JsonNode cargarInsumo(Navegador n, String nombre, byte[] datos, Map<String, Object> periodo) {
        int partes = (datos.length + PARTE - 1) / PARTE;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("nombre", nombre);
        s.put("tamano", datos.length);
        s.put("sha256", Huellas.sha256(datos));
        s.put("partes", partes);
        Navegador.Respuesta c = n.post("/api/analitica/cargues", s);
        assertThat(c.estado()).as(c.texto()).isEqualTo(201);
        long id = c.json().path("id").asLong();
        for (int p = 0; p < partes; p++) {
            byte[] parte = Arrays.copyOfRange(datos, p * PARTE, Math.min(datos.length, (p + 1) * PARTE));
            assertThat(n.putBinario("/api/analitica/cargues/" + id + "/partes/" + (p + 1), parte).estado()).isEqualTo(200);
        }
        assertThat(n.post("/api/analitica/cargues/" + id + "/cerrar", null).estado()).isEqualTo(200);
        List<String> columnas = new ArrayList<>();
        n.get("/api/analitica/cargues/" + id + "/mapeo").json().path("propuesta").forEach(x -> columnas.add(x.asText()));
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("columnas", columnas);
        conf.put("modo", "agregar");
        conf.put("guardarPlantilla", false);
        conf.putAll(periodo);
        Navegador.Respuesta r = n.post("/api/analitica/cargues/" + id + "/mapeo", conf);
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        long limite = System.nanoTime() + 180_000_000_000L;
        while (true) {
            JsonNode e = n.get("/api/analitica/cargues/" + id).json();
            if (TERMINALES.contains(e.path("estado").asText())) {
                return e;
            }
            if (System.nanoTime() > limite) {
                throw new AssertionError("El cargue " + id + " no terminó: " + e);
            }
            dormir(200);
        }
    }

    static String q(String parametros) {
        return "/api/analitica/prediccion?" + parametros;
    }

    // ---------- Pruebas ----------

    @Test
    @DisplayName("prueba con un año: 12 meses completos de 2025 ⇒ 6 meses proyectados (2026-01 a 2026-06), backtesting de 3, nota de estacionalidad, tendencia exacta")
    void unAnioSinEstacionalidad() {
        Base b = base();
        Navegador n = nuevo("anapred1", Perfil.ANALISTA);
        JsonNode r = ok(n.get(q("metrica=gasto&nivel=nacional&corte=" + b.c2())));
        assertThat(r.path("corte").path("fechaCorte").asText()).isEqualTo("2025-12-31");
        JsonNode obs = r.path("observado");
        assertThat(obs).hasSize(12);
        for (int i = 1; i <= 12; i++) {
            assertThat(obs.get(i - 1).path("mes").asText()).isEqualTo(YearMonth.of(2025, i).toString());
            assertThat(obs.get(i - 1).path("valor").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(1500 + 150L * i));
            assertThat(obs.get(i - 1).path("parcial").asBoolean()).isFalse();
        }
        JsonNode m = r.path("modelo");
        assertThat(m.path("estado").asText()).isEqualTo("ok");
        assertThat(m.path("registrado").asBoolean()).as("el automático del cargue que completó diciembre").isTrue();
        assertThat(m.path("origen").asText()).isEqualTo("automatico");
        assertThat(m.path("metodo").asText()).isIn("holt", "regresion_lineal");
        assertThat(m.path("historiaMeses").asInt()).isEqualTo(12);
        assertThat(m.path("historiaDesde").asText()).isEqualTo("2025-01");
        assertThat(m.path("historiaHasta").asText()).isEqualTo("2025-12");
        assertThat(m.path("backtestMeses").asInt()).isEqualTo(3);
        assertThat(m.path("notas").toString()).contains(AnaliticaPrediccion.NOTA_SIN_ESTACIONAL);
        assertThat(m.path("evaluados")).hasSize(2);
        assertThat(m.path("wmape").decimalValue()).isLessThan(new BigDecimal("0.5"));
        assertThat(m.path("fechaEntrenamiento").isNull()).isFalse();
        assertThat(m.path("version").asText()).isEqualTo(AnaliticaPrediccion.VERSION);
        JsonNode p = r.path("proyeccion");
        assertThat(p).hasSize(6);
        for (int h = 1; h <= 6; h++) {
            assertThat(p.get(h - 1).path("mes").asText()).isEqualTo(YearMonth.of(2026, h).toString());
            assertThat(p.get(h - 1).path("h").asInt()).isEqualTo(h);
            assertThat(p.get(h - 1).path("inferior").decimalValue()).isLessThanOrEqualTo(p.get(h - 1).path("valor").decimalValue());
            assertThat(p.get(h - 1).path("superior").decimalValue()).isGreaterThanOrEqualTo(p.get(h - 1).path("valor").decimalValue());
        }
        assertThat(p.get(0).path("valor").decimalValue()).isBetween(new BigDecimal("3449"), new BigDecimal("3451"));
        // Escenarios: base, inferior, superior y ajustado por el % que manda el cliente (no se guarda)
        long antes = jdbc.sql("SELECT count(*) FROM ana_pred_entrenamientos").query(Long.class).single();
        JsonNode a = ok(n.get(q("metrica=gasto&nivel=nacional&ajuste=10&corte=" + b.c2())));
        assertThat(a.path("proyeccion").get(0).path("ajustado").decimalValue())
                .isEqualByComparingTo(AnaliticaPrediccion.ajustar(p.get(0).path("valor").decimalValue(), BigDecimal.TEN));
        assertThat(a.path("escenarios").path("base").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(a.path("escenarios").path("ajustado").decimalValue()).isGreaterThan(a.path("escenarios").path("base").decimalValue());
        assertThat(jdbc.sql("SELECT count(*) FROM ana_pred_entrenamientos").query(Long.class).single()).isEqualTo(antes);
        Navegador.Respuesta mal = n.get(q("metrica=gasto&nivel=nacional&ajuste=-150&corte=" + b.c2()));
        assertThat(mal.estado()).isEqualTo(422);
        assertThat(mal.json().path("campo").asText()).isEqualTo("ajuste");
        assertThat(n.get(q("metrica=gasto&horizonte=3&corte=" + b.c2())).estado()).isEqualTo(422);
        assertThat(n.get(q("metrica=unidades&corte=" + b.c2())).estado()).isEqualTo(422);
        // Horizonte parametrizable: 9 meses al vuelo (sin entrenamiento guardado con ese horizonte)
        JsonNode nueve = ok(n.get(q("metrica=gasto&horizonte=9&corte=" + b.c2())));
        assertThat(nueve.path("proyeccion")).hasSize(9);
        assertThat(nueve.path("modelo").path("registrado").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("historia insuficiente: con 3 meses (corte del cargue 1) no proyecta, lo dice y el entrenamiento automático quedó como historia_insuficiente")
    void historiaInsuficiente() {
        Base b = base();
        Navegador n = nuevo("anapred2", Perfil.ANALISTA);
        JsonNode r = ok(n.get(q("metrica=gasto&nivel=nacional&corte=" + b.c1())));
        JsonNode m = r.path("modelo");
        assertThat(m.path("estado").asText()).isEqualTo("historia_insuficiente");
        assertThat(m.path("mensaje").asText()).isEqualTo("Historia insuficiente: 3 meses (mínimo 6)");
        assertThat(r.path("proyeccion")).isEmpty();
        assertThat(r.path("observado")).hasSize(3);
        assertThat(jdbc.sql("""
                SELECT estado || '|' || mensaje FROM ana_pred_entrenamientos
                WHERE corte_cargue_id = :c AND metrica = 'gasto' AND nivel = 'nacional' AND origen = 'automatico'""").param("c", b.c1()).query(String.class)
                .single()).isEqualTo("historia_insuficiente|Historia insuficiente: 3 meses (mínimo 6)");
        assertThat(jdbc.sql("SELECT count(*) FROM ana_pred_proyecciones p JOIN ana_pred_entrenamientos e ON e.id = p.entrenamiento_id WHERE e.corte_cargue_id = :c")
                .param("c", b.c1()).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("reentrenamiento manual: el ANALISTA reentrena el gasto nacional; queda con su usuario en la tabla, en la lista y en la bitácora con la serie y el método")
    void entrenarRegistra() {
        Base b = base();
        String analista = crearUsuario("anapred3", Perfil.ANALISTA);
        Navegador n = como(analista);
        Navegador.Respuesta r = n.post("/api/analitica/prediccion/reentrenar", Map.of("metrica", "gasto", "nivel", "nacional", "corte", b.c2(), "horizonte", 12));
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        long id = r.json().path("id").asLong();
        assertThat(r.json().path("estado").asText()).isEqualTo("ok");
        Map<String, Object> fila = jdbc.sql("SELECT origen, usuario_id, corte_cargue_id, metodo, sha256 FROM ana_pred_entrenamientos WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(fila.get("origen")).isEqualTo("manual");
        assertThat(((Number) fila.get("usuario_id")).longValue()).isEqualTo(idDe(analista));
        assertThat(((Number) fila.get("corte_cargue_id")).longValue()).isEqualTo(b.c2());
        assertThat(jdbc.sql("SELECT count(*) FROM ana_pred_proyecciones WHERE entrenamiento_id = :id").param("id", id).query(Long.class).single()).isEqualTo(12);
        String metodo = AnaliticaPrediccion.Metodo.deCodigo((String) fila.get("metodo")).orElseThrow().nombre();
        assertThat(bitacoraDe(idDe(analista)).stream().anyMatch(l -> l.contains("analitica_prediccion_entrenamiento") && l.contains("Gasto · Nacional")
                && l.contains(metodo) && l.contains("manual"))).as(bitacoraDe(idDe(analista)).toString()).isTrue();
        // La pantalla muestra ahora ese entrenamiento (el último del corte) y la bitácora del tablero lo lista con el usuario
        JsonNode c = ok(n.get(q("metrica=gasto&nivel=nacional&horizonte=12&corte=" + b.c2())));
        assertThat(c.path("modelo").path("entrenamiento").asLong()).isEqualTo(id);
        assertThat(c.path("modelo").path("usuario").asText()).isEqualTo(analista);
        assertThat(c.path("puedeReentrenar").asBoolean()).isTrue();
        // Mismo corte ⇒ misma huella que el automático
        String autom = jdbc.sql("""
                SELECT sha256 FROM ana_pred_entrenamientos WHERE corte_cargue_id = :c AND metrica = 'gasto' AND nivel = 'nacional' AND origen = 'automatico'""")
                .param("c", b.c2()).query(String.class).single();
        assertThat(fila.get("sha256")).isEqualTo(autom);
        JsonNode l = ok(n.get("/api/analitica/prediccion/entrenamientos?metrica=gasto&nivel=nacional"));
        assertThat(l.path("total").asLong()).isGreaterThanOrEqualTo(3);
        JsonNode primero = l.path("entrenamientos").get(0);
        assertThat(primero.path("id").asLong()).isEqualTo(id);
        assertThat(primero.path("usuario").asText()).isEqualTo(analista);
        assertThat(primero.path("origen").asText()).isEqualTo("manual");
        assertThat(primero.path("version").asText()).isEqualTo(AnaliticaPrediccion.VERSION);
        // Sanidad y ESM no reentrenan; un horizonte fuera de rango es 422 con su campo
        Navegador s = nuevo("sanpred", Perfil.SANIDAD);
        assertThat(s.post("/api/analitica/prediccion/reentrenar", Map.of("metrica", "gasto")).estado()).isEqualTo(403);
        Navegador.Respuesta h = n.post("/api/analitica/prediccion/reentrenar", Map.of("metrica", "gasto", "horizonte", 30));
        assertThat(h.estado()).isEqualTo(422);
        assertThat(h.json().path("campo").asText()).isEqualTo("horizonte");
        // Un ESM inexistente no se entrena ni se guarda, tampoco con alcance nacional (revisión A5)
        long antes = jdbc.sql("SELECT count(*) FROM ana_pred_entrenamientos").query(Long.class).single();
        Navegador.Respuesta zzz = n.post("/api/analitica/prediccion/reentrenar", Map.of("metrica", "gasto", "nivel", "esm", "valor", "ZZZ9"));
        assertThat(zzz.estado()).isEqualTo(422);
        assertThat(zzz.json().path("campo").asText()).isEqualTo("valor");
        assertThat(zzz.mensaje()).isEqualTo("ESM «ZZZ9» no existe o está fuera de su alcance.");
        assertThat(n.post("/api/analitica/prediccion/reentrenar", Map.of("metrica", "gasto", "nivel", "fuerza", "valor", "POL")).estado()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM ana_pred_entrenamientos").query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    @DisplayName("llega el mes proyectado: el entrenamiento de agosto proyectó septiembre en 7,90 y el cargue trae septiembre completo con 8,10 ⇒ error real 2,47 %")
    void observadoVsProyectado() {
        Base b = base();
        Navegador n = nuevo("anapred4", Perfil.ANALISTA);
        // Entrenamiento anterior (corte del cargue 2) que proyectó septiembre de 2026 para el gasto del ESM DMEFA
        long previo = jdbc.sql("""
                INSERT INTO ana_pred_entrenamientos (metrica, nivel, nivel_valor, corte_cargue_id, fecha_corte, origen, horizonte, metodo, historia_desde,
                    historia_hasta, historia_meses, backtest_meses, version_algoritmo, sha256, estado, duracion_ms, creado)
                VALUES ('gasto', 'esm', 'DMEFA', :c, '2026-08-31', 'automatico', 6, 'holt', '2026-01-01', '2026-08-01', 8, 1, :v, :sha, 'ok', 5,
                    '2026-09-05T10:00:00-05:00') RETURNING id""").param("c", b.c2()).param("v", AnaliticaPrediccion.VERSION).param("sha", "0".repeat(64))
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO ana_pred_proyecciones (entrenamiento_id, mes, h, valor, inferior, superior) VALUES
                    (:e, '2026-09-01', 1, 7.90, 7.50, 8.30), (:e, '2026-10-01', 2, 8.00, 7.40, 8.60)""").param("e", previo).update();
        long c3 = cargar(n, "pred_dmefa_2026_09.csv", List.of(fila("FAC", "DMEFA", YearMonth.of(2026, 9), new BigDecimal("8.10"))));
        // El cargue completa septiembre de 2026: entrena 3 métricas × (nacional + EJC + ARC + FAC) en segundo plano
        esperarAutomaticos(c3, 12);
        JsonNode r = ok(n.get(q("metrica=gasto&nivel=esm&valor=DMEFA&corte=" + c3)));
        JsonNode comp = r.path("comparacion");
        assertThat(comp).hasSize(1);
        JsonNode sep = comp.get(0);
        assertThat(sep.path("mes").asText()).isEqualTo("2026-09");
        assertThat(sep.path("proyectado").decimalValue()).isEqualByComparingTo("7.90");
        assertThat(sep.path("observado").decimalValue()).isEqualByComparingTo("8.10");
        assertThat(sep.path("error").decimalValue()).isEqualByComparingTo("2.47");
        assertThat(sep.path("dentroIc").asBoolean()).isTrue();
        assertThat(sep.path("entrenamiento").asLong()).isEqualTo(previo);
        assertThat(sep.path("fechaEntrenamiento").asText()).startsWith("2026-09-05");
        // Desde el corte anterior (cargue 2) el mes aún no está observado: no hay comparación
        JsonNode antes = ok(n.get(q("metrica=gasto&nivel=nacional&corte=" + b.c2())));
        assertThat(antes.path("comparacion")).isEmpty();
        // La descarga lleva la tabla «Proyectado vs observado» con el error
        Navegador.Respuesta d = n.get("/api/analitica/prediccion/descarga?formato=csv&metrica=gasto&nivel=esm&valor=DMEFA&corte=" + c3);
        assertThat(d.estado()).as(d.texto()).isEqualTo(200);
        assertThat(d.texto()).contains("Proyectado vs observado").contains("2,47");
    }

    @Test
    @DisplayName("alcance: Sanidad EJC proyecta su Fuerza (sin vista nacional; ARC es 422); el ESM solo su establecimiento; sin la pantalla, 403")
    void alcancePorPerfil() {
        Base b = base();
        String sanidad = crearUsuario("sanpred2", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = como(sanidad);
        JsonNode r = ok(s.get(q("metrica=gasto&corte=" + b.c2())));
        assertThat(r.path("serie").path("nivel").asText()).isEqualTo("fuerza");
        assertThat(r.path("serie").path("valor").asText()).isEqualTo("EJC");
        assertThat(r.path("niveles").toString()).doesNotContain("nacional");
        // EJC = BAS02: 1000 + 100 · i
        assertThat(r.path("observado").get(11).path("valor").decimalValue()).isEqualByComparingTo("2200");
        assertThat(r.path("puedeReentrenar").asBoolean()).isFalse();
        Navegador.Respuesta otra = s.get(q("metrica=gasto&nivel=fuerza&valor=ARC&corte=" + b.c2()));
        assertThat(otra.estado()).isEqualTo(422);
        assertThat(otra.mensaje()).isEqualTo("Fuerza «ARC» no existe o está fuera de su alcance.");
        assertThat(s.get(q("metrica=gasto&nivel=nacional&corte=" + b.c2())).estado()).isEqualTo(422);
        assertThat(s.get(q("metrica=gasto&nivel=esm&valor=DMBOG&corte=" + b.c2())).estado()).isEqualTo(422);
        assertThat(ok(s.get(q("metrica=gasto&nivel=esm&valor=BAS02&corte=" + b.c2()))).path("observado")).hasSize(12);
        assertThat(s.get("/api/analitica/prediccion/entrenamientos?metrica=gasto&nivel=fuerza&valor=ARC").estado()).isEqualTo(422);

        long punto = jdbc.sql("SELECT id FROM puntos WHERE upper(sigla) = 'DMBOG' ORDER BY id LIMIT 1").query(Long.class).single();
        String esm = crearUsuario("esmpred", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", punto).param("u", esm).update();
        Navegador e = como(esm);
        JsonNode re = ok(e.get(q("metrica=dispensaciones&corte=" + b.c2())));
        assertThat(re.path("serie").path("nivel").asText()).isEqualTo("esm");
        assertThat(re.path("serie").path("valor").asText()).isEqualTo("DMBOG");
        assertThat(re.path("niveles").toString()).isEqualTo("[\"esm\"]");
        assertThat(e.get(q("metrica=gasto&nivel=esm&valor=BAS02&corte=" + b.c2())).estado()).isEqualTo(422);
        assertThat(e.get(q("metrica=gasto&nivel=fuerza&corte=" + b.c2())).estado()).isEqualTo(422);
        assertThat(e.post("/api/analitica/prediccion/reentrenar", Map.of("metrica", "gasto")).estado()).isEqualTo(403);

        for (Perfil sin : List.of(Perfil.AUDITOR, Perfil.OPL_NACIONAL)) {
            Navegador x = nuevo("sinpred", sin);
            assertThat(x.get(q("metrica=gasto")).estado()).as(sin.name()).isEqualTo(403);
            assertThat(x.get("/api/analitica/prediccion/entrenamientos").estado()).as(sin.name()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("reproducible: mismo corte ⇒ misma proyección y misma huella, al vuelo o guardada, aunque después lleguen cargues; la huella es la de la serie esperada")
    void reproducibilidad() {
        Base b = base();
        Navegador n = nuevo("anapred5", Perfil.COORDINADOR);
        String ruta = q("metrica=dispensaciones&nivel=esm&valor=BAS02&corte=" + b.c2());
        JsonNode r1 = ok(n.get(ruta));
        JsonNode r2 = ok(n.get(ruta));
        assertThat(r1.path("modelo").path("registrado").asBoolean()).as("ESM sin entrenamiento guardado: al vuelo").isFalse();
        assertThat(r1.path("proyeccion")).isEqualTo(r2.path("proyeccion"));
        assertThat(r1.path("modelo").path("sha256").asText()).isEqualTo(r2.path("modelo").path("sha256").asText());
        // La serie que entrenó es la del insumo: una dispensación por mes de BAS02 en 2025
        TreeMap<YearMonth, BigDecimal> esperada = new TreeMap<>();
        for (int i = 1; i <= 12; i++) {
            esperada.put(YearMonth.of(2025, i), BigDecimal.ONE);
        }
        AnaliticaPrediccion.Resultado e = AnaliticaPrediccion.proyectar(esperada, LocalDate.of(2025, 12, 31));
        assertThat(r1.path("modelo").path("sha256").asText()).isEqualTo(e.sha256());
        assertThat(r1.path("proyeccion").get(5).path("valor").decimalValue()).isEqualByComparingTo(e.proyeccion().get(5).valor());
        // El guardado del corte da la misma proyección que se calcula hoy para ese corte
        JsonNode g = ok(n.get(q("metrica=gasto&nivel=fuerza&valor=ARC&corte=" + b.c2())));
        assertThat(g.path("modelo").path("registrado").asBoolean()).isTrue();
        assertThat(g.path("advertencias").toString()).doesNotContain("huella distinta");
        assertThat(ok(n.get(q("metrica=gasto&nivel=fuerza&valor=ARC&corte=" + b.c2()))).path("proyeccion")).isEqualTo(g.path("proyeccion"));
    }

    @Test
    @DisplayName("sin individualización: respuestas, bitácora de entrenamientos, Top 20 y descargas solo con cifras agregadas (sin seudónimos, documentos ni nombres)")
    void sinIndividualizacion() {
        Base b = base();
        Navegador n = nuevo("anapred6", Perfil.ANALISTA);
        List<String> salidas = new ArrayList<>();
        salidas.add(ok(n.get(q("metrica=pacientes&nivel=nacional&corte=" + b.c2()))).toString());
        salidas.add(ok(n.get(q("metrica=gasto&nivel=esm&valor=DMBOG&corte=" + b.c2()))).toString());
        // Dimensiones: el valor se resuelve contra la tabla maestra homologada del corte (aquí nada está homologado: 422, nunca una serie en 0)
        Navegador.Respuesta atc = n.get(q("metrica=gasto&dimension=atc&valorDimension=C09&corte=" + b.c2()));
        assertThat(atc.estado()).isEqualTo(422);
        assertThat(atc.mensaje()).isEqualTo("ATC «C09» no existe o está fuera de su alcance.");
        // A7.1: gcf, grc y cohorte ya son dimensiones; una desconocida sigue respondiendo 422 en «dimension»
        assertThat(n.get(q("metrica=gasto&dimension=cups&valorDimension=X&corte=" + b.c2())).json().path("campo").asText()).isEqualTo("dimension");
        salidas.add(ok(n.get("/api/analitica/prediccion/entrenamientos?metrica=pacientes&nivel=nacional")).toString());
        JsonNode top = ok(n.get("/api/analitica/prediccion/top?dimension=principio&nivel=nacional&corte=" + b.c2()));
        salidas.add(top.toString());
        for (String f : List.of("csv", "xlsx", "pdf")) {
            Navegador.Respuesta d = n.get("/api/analitica/prediccion/descarga?formato=" + f + "&metrica=pacientes&nivel=nacional&corte=" + b.c2());
            assertThat(d.estado()).as(f + ": " + d.texto()).isEqualTo(200);
            if (f.equals("csv")) {
                salidas.add(d.texto());
                assertThat(d.texto()).contains("Metadatos del modelo").contains(AnaliticaPrediccion.VERSION);
            }
        }
        List<String> seudonimos = jdbc.sql("SELECT DISTINCT trim(paciente_seudo) FROM ana_dispensaciones").query(String.class).list();
        assertThat(seudonimos).isNotEmpty();
        for (String s : salidas) {
            for (String x : seudonimos) {
                assertThat(s).doesNotContain(x);
            }
            assertThat(s).doesNotContain("PACIENTE SINTETICO").doesNotContain("70000001").doesNotContain("RM1;");
        }
        // Pacientes distintos por mes: dos (uno por ESM), agregados
        JsonNode p = ok(n.get(q("metrica=pacientes&nivel=nacional&corte=" + b.c2())));
        assertThat(p.path("observado").get(0).path("valor").decimalValue()).isEqualByComparingTo("2");
        assertThat(p.path("escenarios").isNull()).as("los pacientes no se suman entre meses").isTrue();
    }

    @Autowired
    EntrenamientoAutomatico automatico;

    /** Espera a que el entrenamiento automático termine: el evento del cargue se encola un instante después de quedar «aplicado». */
    void esperarQuieto() throws Exception {
        long n1;
        long n2;
        do {
            n1 = jdbc.sql("SELECT count(*) FROM ana_pred_entrenamientos").query(Long.class).single();
            dormir(1000);
            automatico.esperarCola();
            n2 = jdbc.sql("SELECT count(*) FROM ana_pred_entrenamientos").query(Long.class).single();
        } while (n1 != n2);
    }

    @Test
    @DisplayName("alcance de lo guardado: el ANALISTA reentrena «gasto · ESM BAS02»; Sanidad EJC no ve ese entrenamiento (ni modelo, ni bitácora, ni comparación, ni descarga)")
    void entrenamientoGuardadoSoloEnSuAlcance() {
        Base b = base();
        String analista = crearUsuario("anapred7", Perfil.ANALISTA);
        Navegador n = como(analista);
        Navegador.Respuesta r = n.post("/api/analitica/prediccion/reentrenar", Map.of("metrica", "gasto", "nivel", "esm", "valor", "BAS02", "corte", b.c2()));
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        long id = r.json().path("id").asLong();
        assertThat(jdbc.sql("SELECT alcance FROM ana_pred_entrenamientos WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("nacional");
        // Entrenamiento anterior nacional del mismo ESM que proyectó diciembre de 2025
        long previo = jdbc.sql("""
                INSERT INTO ana_pred_entrenamientos (metrica, nivel, nivel_valor, alcance, corte_cargue_id, fecha_corte, origen, horizonte, metodo,
                    historia_desde, historia_hasta, historia_meses, backtest_meses, version_algoritmo, sha256, estado, duracion_ms, creado)
                VALUES ('gasto', 'esm', 'BAS02', 'nacional', :c, '2025-11-30', 'automatico', 6, 'holt', '2025-01-01', '2025-11-01', 11, 3, :v, :sha,
                    'ok', 5, '2025-01-01T00:00:00-05:00') RETURNING id""").param("c", b.c1()).param("v", AnaliticaPrediccion.VERSION)
                .param("sha", "1".repeat(64)).query(Long.class).single();
        jdbc.sql("INSERT INTO ana_pred_proyecciones (entrenamiento_id, mes, h, valor, inferior, superior) VALUES (:e, '2025-12-01', 1, 2100, 2000, 2200)")
                .param("e", previo).update();
        String ruta = q("metrica=gasto&nivel=esm&valor=BAS02&corte=" + b.c2());
        JsonNode suyo = ok(n.get(ruta));
        assertThat(suyo.path("modelo").path("entrenamiento").asLong()).isEqualTo(id);
        assertThat(suyo.path("comparacion")).hasSize(1);
        assertThat(suyo.path("comparacion").get(0).path("entrenamiento").asLong()).isEqualTo(previo);

        String sanidad = crearUsuario("sanpred3", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = como(sanidad);
        JsonNode otro = ok(s.get(ruta));
        assertThat(otro.path("modelo").path("registrado").asBoolean()).as("al vuelo en su alcance").isFalse();
        assertThat(otro.path("modelo").path("entrenamiento").isNull()).isTrue();
        assertThat(otro.path("comparacion")).isEmpty();
        JsonNode lista = ok(s.get("/api/analitica/prediccion/entrenamientos?metrica=gasto&nivel=esm&valor=BAS02"));
        assertThat(lista.path("total").asLong()).isZero();
        Navegador.Respuesta d = s.get("/api/analitica/prediccion/descarga?formato=csv&metrica=gasto&nivel=esm&valor=BAS02&corte=" + b.c2());
        assertThat(d.estado()).as(d.texto()).isEqualTo(200);
        assertThat(d.texto()).contains("Calculada al consultar").doesNotContain("Reentrenar").doesNotContain("Proyectado vs observado");
        // La vista de su propia Fuerza sí usa los automáticos nacionales: la serie es idéntica (misma huella)
        JsonNode fuerza = ok(s.get(q("metrica=gasto&nivel=fuerza&corte=" + b.c2())));
        assertThat(fuerza.path("modelo").path("registrado").asBoolean()).isTrue();
        assertThat(fuerza.path("modelo").path("origen").asText()).isEqualTo("automatico");
        assertThat(ok(s.get("/api/analitica/prediccion/entrenamientos?metrica=gasto&nivel=fuerza")).path("total").asLong()).isGreaterThanOrEqualTo(2);
        // Lo guardado con otra huella no se usa, aunque sea más nuevo
        jdbc.sql("""
                INSERT INTO ana_pred_entrenamientos (metrica, nivel, nivel_valor, alcance, corte_cargue_id, fecha_corte, origen, horizonte, metodo,
                    historia_desde, historia_hasta, historia_meses, backtest_meses, version_algoritmo, sha256, estado, duracion_ms)
                VALUES ('gasto', 'esm', 'BAS02', 'nacional', :c, '2025-12-31', 'automatico', 6, 'holt', '2025-01-01', '2025-12-01', 12, 3, :v, :sha,
                    'ok', 5)""").param("c", b.c2()).param("v", AnaliticaPrediccion.VERSION).param("sha", "2".repeat(64)).update();
        assertThat(ok(n.get(ruta)).path("modelo").path("entrenamiento").asLong()).isEqualTo(id);
    }

    @Test
    @DisplayName("fecha de corte: un mes declarado completo cuyo último día no tuvo dispensaciones no es parcial; el arranque no duplica entrenamientos")
    void mesDeclaradoCompletoYArranque() throws Exception {
        base();
        Navegador n = nuevo("anapred8", Perfil.ANALISTA);
        String fila = fila("FAC", "CACOM01", YearMonth.of(2026, 3), new BigDecimal("500.00")).replace("31/03/2026", "15/03/2026");
        long c4 = cargar(n, "pred_cacom01_2026_03.csv", List.of(fila), Map.of("periodoDesde", "2026-03-01", "periodoHasta", "2026-03-31"));
        esperarQuieto();
        assertThat(jdbc.sql("SELECT periodo_hasta::text || '|' || declarado_hasta::text FROM ana_cargues WHERE id = :id").param("id", c4)
                .query(String.class).single()).isEqualTo("2026-03-15|2026-03-31");
        JsonNode r = ok(n.get(q("metrica=gasto&nivel=esm&valor=CACOM01&corte=" + c4)));
        assertThat(r.path("corte").path("fechaCorte").asText()).isGreaterThanOrEqualTo("2026-03-31");
        assertThat(r.path("observado").get(0).path("mes").asText()).isEqualTo("2026-03");
        assertThat(r.path("observado").get(0).path("parcial").asBoolean()).as("marzo declarado completo").isFalse();
        // Regla: el declarado completa el mes, acotado al día del corte; sin declarado, la última fecha con datos
        assertThat(ServicioPrediccion.fechaCorte(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 2)))
                .isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(ServicioPrediccion.fechaCorte(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 25)))
                .isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(ServicioPrediccion.fechaCorte(LocalDate.of(2026, 8, 30), null, LocalDate.of(2026, 9, 2))).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(ServicioPrediccion.fechaCorte(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 10, 2)))
                .isEqualTo(LocalDate.of(2026, 9, 30));
        // Al arrancar se revisa el corte vigente; lo ya entrenado no se repite
        long antes = jdbc.sql("SELECT count(*) FROM ana_pred_entrenamientos").query(Long.class).single();
        automatico.alArrancar();
        automatico.esperarCola();
        assertThat(jdbc.sql("SELECT count(*) FROM ana_pred_entrenamientos").query(Long.class).single()).isEqualTo(antes);
        // Errores relativos enormes (observados cercanos a 0) se acotan al tipo de la base
        assertThat(ServicioPrediccion.acotar(new BigDecimal("1e20"), ServicioPrediccion.MAX_ERROR)).isEqualByComparingTo(ServicioPrediccion.MAX_ERROR);
        assertThat(ServicioPrediccion.acotar(new BigDecimal("12.34"), ServicioPrediccion.MAX_ERROR)).isEqualByComparingTo("12.34");
    }
}
