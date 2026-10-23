package co.com.agsamericas.digsa.motorreportes;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import co.com.agsamericas.digsa.reportes.SqlPeriodo;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Motor de reportes (Anexo 5, 2.8, 2.9 y 2.11): catálogo por perfil, filtros de una, varias o todas las opciones, periodos y prueba 13. */
class MotorReportesApiTest extends PruebaIntegracion {

    private record Escenario(Navegador coord, String coordinador, long lider, long ejc, long arc, String lugarEjc, String lugarArc) {
    }

    /** Dos puntos (EJC y ARC) y un coordinador; las visitas se siembran por SQL en fechas pasadas. */
    private Escenario escenario() {
        String lider = crearUsuario("lider", Perfil.AUDITOR);
        String lugarEjc = unico("Punto motor EJC ");
        String lugarArc = unico("Punto motor ARC ");
        long ejc = punto(lugarEjc, "EJC");
        long arc = punto(lugarArc, "ARC");
        String coordinador = crearUsuario("coord", Perfil.COORDINADOR);
        Navegador coord = navegador();
        coord.ingresar(coordinador, CLAVE);
        return new Escenario(coord, coordinador, idDe(lider), ejc, arc, lugarEjc, lugarArc);
    }

    private long punto(String nombre, String fuerza) {
        return jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, creado_por) VALUES (:n, 'ESM', :f, 'Dispensación', '05001', 'prueba')
                RETURNING id""").param("n", nombre).param("f", fuerza).query(Long.class).single();
    }

    private long visita(long punto, long lider, LocalDate fecha, String estado, String... componentes) {
        long id = jdbc.sql("""
                INSERT INTO visitas (numero, tipo, punto_id, fecha, auditor_id, alcance, estado, creado_por)
                VALUES (:n, 'anual', :p, :f, :a, 'Prueba del motor.', :e, :a) RETURNING id""")
                .param("n", "VS-" + fecha.toString().replace("-", "") + "-" + (System.nanoTime() % 100_000_000)).param("p", punto).param("f", fecha).param("a", lider).param("e", estado).query(Long.class).single();
        jdbc.sql("INSERT INTO visitas_auditores (visita_id, usuario_id, funcion, orden) VALUES (:v, :u, 'lider', 0)").param("v", id).param("u", lider).update();
        for (String c : componentes) {
            jdbc.sql("INSERT INTO visitas_componentes (visita_id, componente) VALUES (:v, :c)").param("v", id).param("c", c).update();
        }
        return id;
    }

    private static String numeroDe(JsonNode filas, int columna) {
        List<String> l = new ArrayList<>();
        filas.forEach(f -> l.add(f.get(columna).asText()));
        return String.join(",", l);
    }

    @Test
    @DisplayName("catálogo: grupos con reportes del perfil; la distribución solo con alcance total; el auditor no entra")
    void catalogo() {
        Escenario x = escenario();
        JsonNode cat = x.coord().get("/api/reportes/catalogo").json();
        // Visitas y hallazgos primero (orden del mockup); la coordinación también ve presupuesto
        JsonNode visitas = cat.path("grupos").get(0);
        assertThat(visitas.path("nombre").asText()).isEqualTo("Visitas y hallazgos");
        List<String> ids = new ArrayList<>();
        visitas.path("reportes").forEach(r -> ids.add(r.path("id").asText()));
        assertThat(ids).containsExactly("detalle", "lugar", "proceso", "distribucion", "dashboard_visitas", "dashboard_riesgo");
        JsonNode detalle = visitas.path("reportes").get(0);
        assertThat(detalle.path("clase").asText()).isEqualTo("operativo");
        assertThat(detalle.path("columnas").toString()).contains("Auditor líder", "Inicio real", "Descripción de los hallazgos");
        assertThat(visitas.path("reportes").get(1).path("agrupaciones").toString()).contains("bimestre", "anio").doesNotContain("hora");
        assertThat(cat.path("presets").toString()).contains("mes_anterior", "anio_actual");
        assertThat(cat.path("formatos").toString()).contains("xlsx", "csv", "pdf", "txt");

        String sanidad = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        s.ingresar(sanidad, CLAVE);
        JsonNode catS = s.get("/api/reportes/catalogo").json();
        assertThat(catS.toString()).doesNotContain("\"distribucion\"").contains("\"detalle\"");
        assertThat(catS.path("grupos").get(0).path("reportes").get(0).path("columnas").toString()).doesNotContain("Auditor líder");
        assertThat(s.get("/api/reportes/distribucion/opciones").estado()).isEqualTo(404);
        assertThat(s.get("/api/reportes/distribucion/exportar?formato=csv").estado()).isEqualTo(404);
        assertThat(s.post("/api/reportes/distribucion/enviar", Map.of("para", "comite@example.com")).estado()).isEqualTo(404);
        assertThat(x.coord().get("/api/reportes/distribucion?fuerza=EJC").mensaje()).contains("no aplica");
        assertThat(x.coord().get("/api/reportes").json().path("tipos").path("detalle").asText()).isEqualTo("Detalle de visitas");

        Navegador aud = navegador();
        aud.ingresar(crearUsuario("aud", Perfil.AUDITOR), CLAVE);
        assertThat(aud.get("/api/reportes/catalogo").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("2.11: varias fuerzas a la vez; las opciones salen recortadas al alcance y lo de fuera es 422 sin revelar nombres")
    void filtrosMultivalor() {
        Escenario x = escenario();
        LocalDate dia = LocalDate.of(2026, 3, 10);
        visita(x.ejc(), x.lider(), dia, "programada", "C08");
        visita(x.arc(), x.lider(), dia, "programada", "C08");
        String filtro = "?desde=2026-03-01&hasta=2026-03-31";
        JsonNode ambas = x.coord().get("/api/reportes/detalle" + filtro + "&fuerza=EJC&fuerza=ARC&punto_id=" + x.ejc() + "&punto_id=" + x.arc()).json();
        assertThat(ambas.path("filas").toString()).contains(x.lugarEjc(), x.lugarArc());
        String ejercito = jdbc.sql("SELECT nombre FROM par_fuerzas WHERE codigo = 'EJC'").query(String.class).single();
        String armada = jdbc.sql("SELECT nombre FROM par_fuerzas WHERE codigo = 'ARC'").query(String.class).single();
        // Los valores van ordenados por código: la huella no depende del orden en que se eligieron
        assertThat(ambas.path("filtros").path("Fuerza").asText()).isEqualTo(armada + ", " + ejercito);
        assertThat(ambas.path("establecimiento").asText()).contains(x.lugarEjc()).contains(x.lugarArc());
        // Sin filtro de lugar: una tercera fuerza queda por fuera y todas las filas son de las dos elegidas
        String lugarFac = unico("Punto motor FAC ");
        visita(punto(lugarFac, "FAC"), x.lider(), dia, "programada", "C08");
        JsonNode dos = x.coord().get("/api/reportes/detalle" + filtro + "&fuerza=EJC&fuerza=ARC&estado=programada&estado=cancelada").json();
        assertThat(dos.toString()).contains(x.lugarEjc(), x.lugarArc()).doesNotContain(lugarFac);
        dos.path("filas").forEach(f -> assertThat(f.get(8).asText()).isIn("EJC", "ARC"));
        JsonNode solo = x.coord().get("/api/reportes/detalle" + filtro + "&fuerza=EJC&punto_id=" + x.ejc() + "&punto_id=" + x.arc()).json();
        assertThat(solo.path("filas").toString()).contains(x.lugarEjc()).doesNotContain(x.lugarArc());

        String sanidad = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        s.ingresar(sanidad, CLAVE);
        JsonNode opciones = s.get("/api/reportes/detalle/opciones").json();
        assertThat(opciones.path("fuerza").toString()).contains("ARC").doesNotContain("EJC");
        assertThat(opciones.path("punto_id").toString()).contains(x.lugarArc()).doesNotContain(x.lugarEjc());
        Respuesta fuera = s.get("/api/reportes/detalle" + filtro + "&punto_id=" + x.ejc());
        assertThat(fuera.estado()).isEqualTo(422);
        assertThat(fuera.mensaje()).contains("no existe o está fuera de su alcance").doesNotContain(x.lugarEjc());
        assertThat(s.get("/api/reportes/detalle" + filtro + "&fuerza=EJC").estado()).isEqualTo(422);
        JsonNode propio = s.get("/api/reportes/detalle" + filtro).json();
        assertThat(propio.path("filas").toString()).contains(x.lugarArc()).doesNotContain(x.lugarEjc());
        assertThat(propio.path("establecimiento").asText()).isEqualTo("Todos (dentro de su alcance)");

        assertThat(x.coord().get("/api/reportes/detalle" + filtro + "&fuerza=XYZ").estado()).isEqualTo(422);
        assertThat(x.coord().get("/api/reportes/proceso" + filtro + "&estado=cerrada").mensaje()).contains("no aplica");
        assertThat(x.coord().get("/api/reportes/detalle" + filtro + "&desde=2026-01-01").mensaje()).contains("un solo valor");
    }

    @Test
    @DisplayName("2.11: agrupado por bimestre y por año; la agrupación no admitida es 422 con las que sí admite")
    void agrupacion() {
        Escenario x = escenario();
        visita(x.ejc(), x.lider(), LocalDate.of(2026, 1, 15), "cancelada", "C08");
        visita(x.ejc(), x.lider(), LocalDate.of(2026, 2, 20), "programada", "C08");
        visita(x.ejc(), x.lider(), LocalDate.of(2026, 3, 5), "programada", "C08", "C10");
        String filtro = "?desde=2026-01-01&hasta=2026-06-30&punto_id=" + x.ejc();
        JsonNode b = x.coord().get("/api/reportes/lugar" + filtro + "&agrupacion=bimestre").json();
        assertThat(b.path("columnas").get(0).asText()).isEqualTo("Periodo");
        assertThat(b.path("agrupacion").asText()).isEqualTo("bimestres");
        assertThat(b.path("filtros").path("Agrupado por").asText()).isEqualTo("bimestres");
        // B1: las dos visitas de enero y febrero en C08; B2: la de marzo en C08 y en C10
        assertThat(numeroDe(b.path("filas"), 0)).isEqualTo("2026-B1,2026-B2,2026-B2");
        assertThat(b.path("filas").get(0).get(7).asInt()).isEqualTo(2);
        assertThat(b.path("filas").get(0).get(11).asInt()).as("canceladas").isEqualTo(1);
        assertThat(b.path("filas").get(2).get(6).asText()).startsWith("C10");
        JsonNode anio = x.coord().get("/api/reportes/lugar" + filtro + "&agrupacion=anio&componente=C08").json();
        assertThat(anio.path("filas")).hasSize(1);
        assertThat(anio.path("filas").get(0).get(0).asText()).isEqualTo("2026");
        assertThat(anio.path("filas").get(0).get(7).asInt()).isEqualTo(3);

        Respuesta horas = x.coord().get("/api/reportes/lugar" + filtro + "&agrupacion=hora");
        assertThat(horas.estado()).isEqualTo(422);
        assertThat(horas.mensaje()).contains("Admite:", "bimestres");
        assertThat(x.coord().get("/api/reportes/detalle" + filtro + "&agrupacion=mes").mensaje()).contains("no se agrupa por periodo");
        assertThat(x.coord().get("/api/reportes/lugar?desde=2026-02-01&hasta=2026-01-01").estado()).isEqualTo(422);
        assertThat(x.coord().get("/api/reportes/lugar?desde=2020-01-01&hasta=2026-01-01").mensaje()).contains("3 años");
    }

    @Test
    @DisplayName("la etiqueta SQL del periodo es la misma de la regla pura en los bordes (semana ISO, bimestre, semestre)")
    void etiquetasSqlIgualesALaRegla() {
        List<LocalDate> fechas = List.of(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 12, 31), LocalDate.of(2026, 10, 1));
        for (Agrupacion a : Agrupacion.values()) {
            for (LocalDate d : fechas) {
                String sql = jdbc.sql("SELECT " + SqlPeriodo.etiqueta(a, "x.d") + " FROM (SELECT CAST(:d AS date) AS d) x").param("d", d)
                        .query(String.class).single();
                assertThat(sql).as(a + " " + d).isEqualTo(a.etiqueta(d));
            }
        }
    }

    @Test
    @DisplayName("presets: el periodo se resuelve a fechas en el encabezado; uno inexistente es 422")
    void presets() {
        Escenario x = escenario();
        LocalDate hoy = LocalDate.now(reloj.withZone(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA));
        JsonNode ayer = x.coord().get("/api/reportes/detalle?preset=ayer").json();
        assertThat(ayer.path("periodo").asText()).isEqualTo(hoy.minusDays(1) + " a " + hoy.minusDays(1));
        assertThat(x.coord().get("/api/reportes/detalle?preset=quincena").estado()).isEqualTo(422);
    }

    @Test
    @DisplayName("prueba 13: el detalle trae inicio y fin reales, objeto, componentes, hallazgos del último acta y auditor")
    void detallePrueba13() {
        Escenario x = escenario();
        long v = visita(x.ejc(), x.lider(), LocalDate.of(2026, 4, 2), "cerrada", "C08", "C10");
        jdbc.sql("""
                INSERT INTO visitas_ejecucion (visita_id, inicio_real, fin_real, objeto)
                VALUES (:v, timestamptz '2026-04-02 08:00:00-05', timestamptz '2026-04-02 11:30:00-05', 'Verificar el almacenamiento.')""").param("v", v).update();
        jdbc.sql("INSERT INTO visitas_resumen (visita_id, c, nc, na, pendientes, hallazgos) VALUES (:v, 8, 2, 1, 0, 2)").param("v", v).update();
        long acta1 = acta(v, 1, "enviada");
        hallazgo(v, acta1, "C08-01", "Hallazgo de la ronda 1");
        long acta2 = acta(v, 2, "parcial");
        hallazgo(v, acta2, "C08-02", "Temperatura fuera de rango");
        hallazgo(v, acta2, "C10-01", "Sin registro de inventario");
        // La ronda 3 no le llegó a nadie: sus hallazgos no cuentan
        long acta3 = acta(v, 3, "fallida");
        hallazgo(v, acta3, "C08-09", "Borrador que nadie recibió");
        JsonNode det = x.coord().get("/api/reportes/detalle?desde=2026-04-01&hasta=2026-04-30&punto_id=" + x.ejc()).json();
        JsonNode cols = det.path("columnas");
        JsonNode fila = det.path("filas").get(0);
        Map<String, String> celda = new java.util.HashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            celda.put(cols.get(i).asText(), fila.get(i).asText());
        }
        assertThat(celda.get("Inicio real")).isEqualTo("2026-04-02 08:00:00");
        assertThat(celda.get("Fin real")).isEqualTo("2026-04-02 11:30:00");
        assertThat(celda.get("Objeto")).isEqualTo("Verificar el almacenamiento.");
        assertThat(celda.get("Componentes")).startsWith("C08").contains(" · C10");
        assertThat(celda.get("Descripción de los hallazgos")).isEqualTo("C08-02: Temperatura fuera de rango · C10-01: Sin registro de inventario");
        assertThat(celda.get("Auditor líder")).startsWith("Persona");
        assertThat(celda.get("% cumplimiento")).isEqualTo("80.0");
        assertThat(det.path("fuente").asText()).contains("visitas");
        assertThat(det.path("ms").asLong()).isNotNegative();

        // Reabierta (en ejecución): sin resultados ni hallazgos, como en los consolidados (19.9)
        jdbc.sql("UPDATE visitas SET estado = 'ejecucion' WHERE id = :v").param("v", v).update();
        JsonNode f2 = x.coord().get("/api/reportes/detalle?desde=2026-04-01&hasta=2026-04-30&punto_id=" + x.ejc()).json().path("filas").get(0);
        Map<String, JsonNode> reabierta = new java.util.HashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            reabierta.put(cols.get(i).asText(), f2.get(i));
        }
        assertThat(reabierta.get("Cumple").isNull()).isTrue();
        assertThat(reabierta.get("Descripción de los hallazgos").isNull()).isTrue();
        assertThat(reabierta.get("Objeto").asText()).isEqualTo("Verificar el almacenamiento.");
    }

    @Test
    @DisplayName("prueba 13: el consolidado por lugar suma por componente, más las obligaciones y las visitas sin componentes")
    void consolidadoPorLugarConResultados() {
        Escenario x = escenario();
        long cerrada = visita(x.ejc(), x.lider(), LocalDate.of(2026, 5, 4), "cerrada", "C08", "C10");
        String porComponente = "{\"C08\":{\"c\":2,\"nc\":1,\"na\":0,\"pendientes\":0},\"C10\":{\"c\":3,\"nc\":0,\"na\":0,\"pendientes\":0},"
                + "\"OBL\":{\"c\":1,\"nc\":1,\"na\":0,\"pendientes\":0}}";
        jdbc.sql("INSERT INTO visitas_resumen (visita_id, c, nc, na, pendientes, hallazgos, por_componente) VALUES (:v, 6, 2, 0, 0, 2, CAST(:pc AS jsonb))")
                .param("v", cerrada).param("pc", porComponente).update();
        visita(x.ejc(), x.lider(), LocalDate.of(2026, 5, 6), "cancelada", "C08");
        visita(x.ejc(), x.lider(), LocalDate.of(2026, 5, 7), "programada");
        JsonNode filas = x.coord().get("/api/reportes/lugar?desde=2026-05-01&hasta=2026-05-31&punto_id=" + x.ejc()).json().path("filas");
        assertThat(filas).hasSize(4);
        // Lugar, Sigla, Municipio, Fuerza, Unidad, Componente, Visitas, Cerradas, En ejecución, Por ejecutar, Canceladas, Cumple, No cumple, %, Hallazgos
        JsonNode c08 = filas.get(0);
        assertThat(c08.get(5).asText()).startsWith("C08");
        assertThat(List.of(c08.get(6).asInt(), c08.get(7).asInt(), c08.get(10).asInt(), c08.get(11).asInt(), c08.get(12).asInt(), c08.get(14).asInt()))
                .containsExactly(2, 1, 1, 2, 1, 1);
        assertThat(c08.get(13).asText()).isEqualTo("66.7");
        assertThat(filas.get(1).get(5).asText()).startsWith("C10");
        assertThat(filas.get(1).get(13).asText()).isEqualTo("100.0");
        assertThat(filas.get(2).get(5).asText()).isEqualTo("OBL Obligaciones del contrato");
        assertThat(filas.get(2).get(12).asInt()).isEqualTo(1);
        assertThat(filas.get(3).get(5).asText()).isEqualTo("Sin componente");
        assertThat(filas.get(3).get(9).asInt()).as("por ejecutar").isEqualTo(1);
        // Cumple y No cumple del lugar cuadran con el resumen del acta que muestra el detalle (6 y 2)
        int c = 0;
        int nc = 0;
        for (JsonNode f : filas) {
            c += f.get(11).asInt();
            nc += f.get(12).asInt();
        }
        assertThat(List.of(c, nc)).containsExactly(6, 2);
    }

    @Test
    @DisplayName("alcance ESM: solo su punto en opciones y filas, sin la visita a necesidad pendiente; otro punto es 422; sin distribución")
    void alcanceEsm() {
        Escenario x = escenario();
        long otro = punto(unico("Punto vecino EJC "), "EJC");
        visita(x.ejc(), x.lider(), LocalDate.of(2026, 6, 2), "programada", "C08");
        long necesidad = visita(x.ejc(), x.lider(), LocalDate.of(2026, 6, 3), "programada", "C08");
        jdbc.sql("UPDATE visitas SET tipo = 'necesidad', fecha_solicitud = fecha WHERE id = :v").param("v", necesidad).update();
        visita(otro, x.lider(), LocalDate.of(2026, 6, 2), "programada", "C08");
        String numeroNecesidad = jdbc.sql("SELECT numero FROM visitas WHERE id = :v").param("v", necesidad).query(String.class).single();
        String esm = crearUsuario("esm", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", x.ejc()).param("u", esm).update();
        Navegador e = navegador();
        e.ingresar(esm, CLAVE);
        JsonNode op = e.get("/api/reportes/detalle/opciones").json();
        assertThat(op.path("punto_id")).hasSize(1);
        assertThat(op.path("punto_id").get(0).path("valor").asText()).isEqualTo(String.valueOf(x.ejc()));
        String filtro = "?desde=2026-06-01&hasta=2026-06-30";
        JsonNode det = e.get("/api/reportes/detalle" + filtro).json();
        assertThat(det.path("filas")).hasSize(1);
        assertThat(det.toString()).contains(x.lugarEjc()).doesNotContain(numeroNecesidad);
        assertThat(det.path("establecimiento").asText()).isEqualTo("Todos (dentro de su alcance)");
        JsonNode lugar = e.get("/api/reportes/lugar" + filtro).json();
        assertThat(lugar.path("filas")).hasSize(1);
        assertThat(lugar.path("filas").get(0).get(6).asInt()).as("sin la de necesidad").isEqualTo(1);
        Respuesta ajeno = e.get("/api/reportes/detalle" + filtro + "&punto_id=" + otro);
        assertThat(ajeno.estado()).isEqualTo(422);
        Respuesta inexistente = e.get("/api/reportes/detalle" + filtro + "&punto_id=999999999");
        assertThat(inexistente.mensaje().replace("999999999", "N")).isEqualTo(ajeno.mensaje().replace(String.valueOf(otro), "N"));
        assertThat(e.get("/api/reportes/catalogo").texto()).doesNotContain("\"distribucion\"");
        // La coordinación sí ve la visita a necesidad
        assertThat(x.coord().get("/api/reportes/detalle" + filtro + "&punto_id=" + x.ejc()).texto()).contains(numeroNecesidad);
    }

    private long acta(long visita, int ronda, String estado) {
        long archivo = jdbc.sql("""
                INSERT INTO archivos (visita_id, clave, nombre_original, ruta, tipo, tamano, sha256)
                VALUES (:v, 'acta', 'acta.pdf', :r, 'application/pdf', 10, repeat('a', 64)) RETURNING id""")
                .param("v", visita).param("r", "actas/motor_" + visita + "_" + ronda + ".pdf").query(Long.class).single();
        long usuario = jdbc.sql("SELECT auditor_id FROM visitas WHERE id = :v").param("v", visita).query(Long.class).single();
        return jdbc.sql("INSERT INTO visitas_actas (visita_id, ronda, archivo_id, generado_por, estado) VALUES (:v, :r, :a, :u, :e) RETURNING id")
                .param("v", visita).param("r", ronda).param("a", archivo).param("u", usuario).param("e", estado).query(Long.class).single();
    }

    private void hallazgo(long visita, long acta, String codigo, String texto) {
        jdbc.sql("""
                INSERT INTO visitas_hallazgos (visita_id, acta_id, clave, codigo, origen, texto, hallazgo)
                VALUES (:v, :a, :c, :c, 'item', 'Ítem ' || :c, :h)""").param("v", visita).param("a", acta).param("c", codigo).param("h", texto).update();
    }

    @Test
    @DisplayName("bitácora: el reporte generado queda con todos los valores de cada filtro; el envío acepta filtros en lista")
    void bitacoraYEnvio() {
        Escenario x = escenario();
        visita(x.ejc(), x.lider(), LocalDate.of(2026, 3, 10), "programada", "C08");
        visita(x.arc(), x.lider(), LocalDate.of(2026, 3, 11), "programada", "C08");
        String puntos = "&punto_id=" + x.ejc() + "&punto_id=" + x.arc();
        assertThat(x.coord().get("/api/reportes/lugar?desde=2026-03-01&hasta=2026-03-31&fuerza=EJC&fuerza=ARC" + puntos).estado()).isEqualTo(200);
        String ejercito = jdbc.sql("SELECT nombre FROM par_fuerzas WHERE codigo = 'EJC'").query(String.class).single();
        String armada = jdbc.sql("SELECT nombre FROM par_fuerzas WHERE codigo = 'ARC'").query(String.class).single();
        // Primero filas y tiempo (no se pierden si la fila se corta); luego los códigos de todos los valores de cada filtro
        assertThat(bitacoraDe(idDe(x.coordinador())).get(0)).startsWith("consulta|reporte_generado|")
                .contains(" · 2 filas · ", "Fuerza: ARC,EJC", "Periodo: 2026-03-01 a 2026-03-31");

        Respuesta bajado = x.coord().get("/api/reportes/lugar/exportar?formato=csv&desde=2026-03-01&hasta=2026-03-31&fuerza=EJC&fuerza=ARC" + puntos);
        assertThat(bajado.cabecera("Content-Security-Policy")).isEqualTo("sandbox");
        assertThat(bajado.cabecera("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(bitacoraDe(idDe(x.coordinador())).get(0)).startsWith("consulta|reporte_descargado|").contains("reporte_lugar_", ".csv",
                "Fuerza: ARC,EJC");

        Respuesta env = x.coord().post("/api/reportes/lugar/enviar", Map.of("para", "comite@example.com", "formato", "csv", "desde", "2026-03-01",
                "hasta", "2026-03-31", "filtros", Map.of("fuerza", List.of("EJC", "ARC"), "punto_id", List.of(x.ejc(), x.arc()))));
        assertThat(env.estado()).as(env.texto()).isEqualTo(200);
        String csv = new String(ultimoCorreoA("comite@example.com").adjuntos().get(0).contenido(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains(x.lugarEjc(), x.lugarArc(), "Filtro: Fuerza;" + armada + ", " + ejercito);
        assertThat(x.coord().post("/api/reportes/lugar/enviar", Map.of("para", "comite@example.com", "filtros", "EJC")).estado()).isEqualTo(422);
        assertThat(x.coord().post("/api/reportes/lugar/enviar", Map.of("para", "comite@example.com", "formato", "doc")).estado()).isEqualTo(422);
        assertThat(x.coord().post("/api/reportes/lugar/enviar", Map.of("para", "comite@example.com", "formato", List.of("pdf"))).estado()).isEqualTo(422);
        assertThat(x.coord().post("/api/reportes/lugar/enviar", Map.of("para", "comite@example.com", "fuerza", "EJC", "filtros", Map.of("fuerza",
                List.of("ARC")))).mensaje()).contains("dos veces");
        assertThat(bitacoraDe(idDe(x.coordinador())).stream().filter(f -> f.contains("|reporte_enviado|")).findFirst().orElseThrow())
                .contains("Fuerza: ARC,EJC").doesNotContain("comite@example.com");
    }
}
