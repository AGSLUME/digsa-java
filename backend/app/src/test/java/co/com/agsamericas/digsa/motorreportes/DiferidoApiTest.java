package co.com.agsamericas.digsa.motorreportes;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Descargas del día, generación diferida, limpieza y tiempos (Anexo 5, 2.7). La espera síncrona es 0 (toda descarga sigue en segundo
 * plano y responde 202) y el plazo para ver en pantalla es de 1 ms (toda consulta se corta), para probar esos caminos.
 */
@TestPropertySource(properties = {"digsa.reportes.espera-sincrona-ms=0", "digsa.reportes.plazo-ver-ms=1", "digsa.reportes.limpieza-inicial-ms=3600000"})
class DiferidoApiTest extends PruebaIntegracion {

    @Autowired
    LimpiezaReportes limpieza;

    @Autowired
    Almacen almacen;

    @Autowired
    RegistroGenerados registro;

    private record Escenario(Navegador coord, String coordinador, long punto, String filtro) {
    }

    private Escenario escenario() {
        long lider = idDe(crearUsuario("lider", Perfil.AUDITOR));
        long punto = jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, creado_por) VALUES (:n, 'ESM', 'EJC', 'Dispensación', '05001', 'prueba')
                RETURNING id""").param("n", unico("Punto diferido ")).query(Long.class).single();
        long v = jdbc.sql("""
                INSERT INTO visitas (numero, tipo, punto_id, fecha, auditor_id, alcance, estado, creado_por)
                VALUES (:n, 'anual', :p, DATE '2026-08-03', :a, 'Diferido.', 'programada', :a) RETURNING id""")
                .param("n", "VS-20260803-" + (System.nanoTime() % 100_000_000)).param("p", punto).param("a", lider).query(Long.class).single();
        jdbc.sql("INSERT INTO visitas_componentes (visita_id, componente) VALUES (:v, 'C08')").param("v", v).update();
        String coordinador = crearUsuario("coord", Perfil.COORDINADOR);
        Navegador coord = navegador();
        coord.ingresar(coordinador, CLAVE);
        return new Escenario(coord, coordinador, punto, "desde=2026-08-01&hasta=2026-08-31&punto_id=" + punto);
    }

    private String estado(String codigo) {
        return jdbc.sql("SELECT estado FROM reportes_generados WHERE codigo = :c").param("c", codigo).query(String.class).single();
    }

    private long idPorCodigo(String codigo) {
        return jdbc.sql("SELECT id FROM reportes_generados WHERE codigo = :c").param("c", codigo).query(Long.class).single();
    }

    /** Una solicitud del coordinador en «pendiente» o «generando» desde {@code desde}. */
    private String enEstado(Escenario x, String estado, OffsetDateTime desde) {
        String codigo = CodigoVerificacion.nuevo(LocalDate.now(reloj.withZone(Fechas.BOGOTA)));
        jdbc.sql("""
                INSERT INTO reportes_generados (codigo, reporte, version, titulo, usuario_id, usuario, alcance, parametros, columnas, formato, estado, creado, iniciado, vence)
                VALUES (:c, 'detalle', 1, 'Detalle de visitas', :u, 'Persona', 'todo', '{}', '[]', 'csv', :e, :d, :i, :v)""").param("c", codigo)
                .param("u", idDe(x.coordinador())).param("e", estado).param("d", desde).param("i", "generando".equals(estado) ? desde : null)
                .param("v", ServicioGeneracion.finDelDia(desde)).update();
        return codigo;
    }

    @Test
    @DisplayName("2.7: la descarga sigue en segundo plano (202), aparece en «Descargas del día» y se vuelve a bajar sin regenerarla; otro usuario no")
    void diferidoYDescargasDelDia() {
        Escenario x = escenario();
        Respuesta r = x.coord().get("/api/reportes/detalle/exportar?formato=csv&" + x.filtro());
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        String codigo = r.json().path("codigo").asText();
        assertThat(r.json().path("mensaje").asText()).contains("Descargas del día");
        esperar(() -> "lista".equals(estado(codigo)), "que el reporte diferido quede listo");

        JsonNode descargas = x.coord().get("/api/reportes/descargas").json();
        JsonNode d = null;
        for (JsonNode n : descargas) {
            if (codigo.equals(n.path("codigo").asText())) {
                d = n;
            }
        }
        assertThat(d).isNotNull();
        assertThat(d.path("estado").asText()).isEqualTo("lista");
        assertThat(d.path("disponibleHasta").asText()).isEqualTo("23:59");
        assertThat(d.path("filas").asInt()).isEqualTo(1);
        assertThat(d.path("formato").asText()).isEqualTo("csv");

        Respuesta bajado = x.coord().get("/api/reportes/descargas/" + codigo);
        assertThat(bajado.estado()).isEqualTo(200);
        assertThat(bajado.cabecera("X-Codigo-Verificacion")).isEqualTo(codigo);
        assertThat(bajado.cabecera("Content-Disposition")).contains("reporte_detalle_");
        Respuesta otraVez = x.coord().get("/api/reportes/descargas/" + codigo);
        assertThat(otraVez.cuerpo()).as("el mismo archivo, sin regenerar").isEqualTo(bajado.cuerpo());

        Navegador otro = navegador();
        otro.ingresar(crearUsuario("coord", Perfil.COORDINADOR), CLAVE);
        assertThat(otro.get("/api/reportes/descargas/" + codigo).estado()).isEqualTo(404);
        assertThat(otro.get("/api/reportes/descargas").texto()).doesNotContain(codigo);
        assertThat(bitacoraDe(idDe(x.coordinador()))).anyMatch(f -> f.contains("|reporte_solicitado|") && f.contains(codigo))
                .anyMatch(f -> f.contains("|reporte_descargado|") && f.contains("descargas del día"));
    }

    @Test
    @DisplayName("al terminar el día el archivo se borra y la fila queda «vencida»; el código se sigue verificando; las interrumpidas quedan fallidas")
    void limpieza() {
        Escenario x = escenario();
        Respuesta r = x.coord().get("/api/reportes/detalle/exportar?formato=txt&" + x.filtro());
        String codigo = r.json().path("codigo").asText();
        esperar(() -> "lista".equals(estado(codigo)), "que el reporte diferido quede listo");
        String ruta = jdbc.sql("SELECT ruta FROM reportes_generados WHERE codigo = :c").param("c", codigo).query(String.class).single();
        // Una generación que se quedó «generando» más allá del tope técnico (el servidor se cayó), una «pendiente» más vieja que la cola
        // entera y una «generando» reciente, que sigue viva
        OffsetDateTime ya = OffsetDateTime.now(reloj);
        Duration tope = Duration.ofMillis(3_600_000);
        String huerfano = enEstado(x, "generando", ya.minus(tope).minusMinutes(10));
        String perdida = enEstado(x, "pendiente", ya.minus(limpieza.pendienteMaxima(tope)).minusMinutes(1));
        String viva = enEstado(x, "generando", ya.minusMinutes(30));
        LimpiezaReportes.Vuelta hoy = limpieza.limpiar();
        assertThat(estado(codigo)).as("hoy sigue disponible").isEqualTo("lista");
        assertThat(estado(huerfano)).isEqualTo("fallida");
        assertThat(estado(perdida)).isEqualTo("fallida");
        assertThat(estado(viva)).isEqualTo("generando");
        assertThat(hoy.huerfanas()).isGreaterThanOrEqualTo(2);
        registro.interrumpidas(java.util.List.of(idPorCodigo(viva)), ya); // la deja en paz para las demás pruebas

        // Pasada la medianoche de Bogotá
        LocalDateTime ahora = LocalDateTime.now(reloj.withZone(Fechas.BOGOTA));
        reloj.adelantar(Duration.between(ahora, ahora.toLocalDate().plusDays(1).atTime(0, 5)));
        LimpiezaReportes.Vuelta manana = limpieza.limpiar();
        assertThat(manana.candado()).isTrue();
        assertThat(estado(codigo)).isEqualTo("vencida");
        assertThat(jdbc.sql("SELECT archivo_borrado IS NOT NULL FROM reportes_generados WHERE codigo = :c").param("c", codigo).query(Boolean.class).single())
                .isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> almacen.leer(ruta).close()).isInstanceOf(java.io.IOException.class);
        // Al día siguiente la sesión de ayer venció por inactividad: vuelve a ingresar
        Navegador manana2 = navegador();
        manana2.ingresar(x.coordinador(), CLAVE);
        Respuesta lista = manana2.get("/api/reportes/descargas");
        assertThat(lista.estado()).isEqualTo(200);
        assertThat(lista.texto()).doesNotContain(codigo);
        assertThat(manana2.get("/api/reportes/descargas/" + codigo).estado()).isEqualTo(404);
        JsonNode v = manana2.get("/api/reportes/verificar/" + codigo).json();
        assertThat(v.path("resultado").asText()).isEqualTo("integro");
        assertThat(v.path("reporte").path("estado").asText()).isEqualTo("vencida");
    }

    @Test
    @DisplayName("tope por usuario: con dos generándose, la tercera es 429")
    void topePorUsuario() {
        Escenario x = escenario();
        for (int i = 0; i < 2; i++) {
            jdbc.sql("""
                    INSERT INTO reportes_generados (codigo, reporte, version, titulo, usuario_id, usuario, alcance, parametros, columnas, formato, estado, vence)
                    VALUES (:c, 'detalle', 1, 'Detalle de visitas', :u, 'Persona', 'todo', '{}', '[]', 'csv', 'pendiente', now() + interval '1 hour')""")
                    .param("c", CodigoVerificacion.nuevo(LocalDate.now(reloj.withZone(Fechas.BOGOTA)))).param("u", idDe(x.coordinador())).update();
        }
        Respuesta r = x.coord().get("/api/reportes/detalle/exportar?formato=csv&" + x.filtro());
        assertThat(r.estado()).isEqualTo(429);
        assertThat(r.mensaje()).contains("generándose");
    }

    @Test
    @DisplayName("cupo: una solicitud que la cola rechaza o que se interrumpe al apagar queda «fallida» y libera el cupo del usuario")
    void fallidaDesdePendiente() {
        Escenario x = escenario();
        OffsetDateTime ya = OffsetDateTime.now(reloj);
        String rechazada = enEstado(x, "pendiente", ya);
        String enCola = enEstado(x, "pendiente", ya);
        String enCurso = enEstado(x, "generando", ya);
        registro.fallida(idPorCodigo(rechazada), "El servidor tiene muchos reportes en cola. Intente en unos minutos.", ya);
        assertThat(estado(rechazada)).isEqualTo("fallida");
        assertThat(registro.interrumpidas(java.util.List.of(idPorCodigo(enCola), idPorCodigo(enCurso), idPorCodigo(rechazada)), ya)).isEqualTo(2);
        assertThat(estado(enCola)).isEqualTo("fallida");
        assertThat(estado(enCurso)).isEqualTo("fallida");
        assertThat(registro.enCurso(idDe(x.coordinador()))).isZero();
        // Las fallidas se ven en «Descargas del día» con su mensaje
        JsonNode descargas = x.coord().get("/api/reportes/descargas").json();
        assertThat(descargas.findValuesAsText("codigo")).contains(rechazada, enCola, enCurso);
        assertThat(descargas.findValuesAsText("error")).anyMatch(e -> e.contains("muchos reportes en cola"));
    }

    @Test
    @DisplayName("«Descargas del día»: lo pedido ayer que sigue en curso o terminó hoy aparece; lo de ayer ya vencido, no")
    void descargasQuePasanLaMedianoche() {
        Escenario x = escenario();
        OffsetDateTime ayer = ServicioGeneracion.inicioDelDia(OffsetDateTime.now(reloj)).minusMinutes(10);
        String enCurso = enEstado(x, "generando", ayer);
        String vieja = enEstado(x, "pendiente", ayer);
        registro.fallida(idPorCodigo(vieja), "No se pudo generar el archivo.", ayer.plusMinutes(1)); // terminó ayer
        JsonNode descargas = x.coord().get("/api/reportes/descargas").json();
        assertThat(descargas.findValuesAsText("codigo")).contains(enCurso).doesNotContain(vieja);
        registro.interrumpidas(java.util.List.of(idPorCodigo(enCurso)), OffsetDateTime.now(reloj));
    }

    @Test
    @DisplayName("2.7: un reporte que pasa su plazo NO se corta (el usuario lo recibe completo) y dispara la alerta «reporte lento»")
    void lentoAvisaSinCortar() {
        Escenario x = escenario();
        Respuesta r = x.coord().get("/api/reportes/lugar?" + x.filtro());
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(r.json().path("filas")).hasSize(1);
        String detalle = jdbc.sql("SELECT detalle FROM alertas_eventos WHERE tipo = 'reporte_lento' AND clave LIKE 'reporte_lento:lugar:%' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        assertThat(detalle).contains("Consolidado", "superó", "2.7").doesNotContain(x.coordinador());
    }

    @Test
    @DisplayName("tiempos: la generación guarda sus milisegundos y la alerta se dispara desde el 80 % del plazo")
    void tiempos() {
        Escenario x = escenario();
        String codigo = x.coord().get("/api/reportes/lugar/exportar?formato=xlsx&" + x.filtro()).json().path("codigo").asText();
        esperar(() -> "lista".equals(estado(codigo)), "que el reporte diferido quede listo");
        Map<String, Object> t = jdbc.sql("SELECT ms_consulta, ms_archivo, ms_total FROM reportes_generados WHERE codigo = :c").param("c", codigo).query()
                .singleRow();
        assertThat(((Number) t.get("ms_total")).intValue()).isEqualTo(((Number) t.get("ms_consulta")).intValue() + ((Number) t.get("ms_archivo")).intValue());
        assertThat(MedicionReportes.lento(79, 100, false)).isFalse();
        assertThat(MedicionReportes.lento(80, 100, false)).isTrue();
        assertThat(MedicionReportes.lento(1, 100, true)).isTrue();
    }
}
