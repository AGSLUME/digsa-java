package co.com.agsamericas.digsa.motorreportes;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * El tope técnico (consulta colgada) sí corta, con mensaje claro, y dispara la alerta. Aquí vale 1 ms para provocarlo: una generación
 * que termina pasado el tope se trata igual que una cortada, así que no depende de cuánto tarde la consulta.
 */
@TestPropertySource(properties = {"digsa.reportes.tope-tecnico-ms=1", "digsa.reportes.tope-tecnico-ver-ms=1", "digsa.reportes.limpieza-inicial-ms=3600000"})
class TopeTecnicoApiTest extends PruebaIntegracion {

    @Test
    @DisplayName("en pantalla: una consulta que llega al tope técnico se corta con 503 y un mensaje para acotarla, y queda la alerta «reporte lento»")
    void corte() {
        Navegador coord = navegador();
        coord.ingresar(crearUsuario("coord", Perfil.COORDINADOR), CLAVE);
        Respuesta r = coord.get("/api/reportes/lugar?desde=2026-01-01&hasta=2026-12-31");
        assertThat(r.estado()).isEqualTo(503);
        assertThat(r.mensaje()).contains("tope técnico", "acote el periodo");
        String detalle = jdbc.sql("SELECT detalle FROM alertas_eventos WHERE tipo = 'reporte_lento' AND clave LIKE 'reporte_lento:lugar:%' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        assertThat(detalle).contains("se cortó en el tope técnico");
    }

    @Test
    @DisplayName("al exportar: la generación cortada queda «fallida» con su mensaje, sin archivo, en la bitácora como «reporte_fallido» y libera el cupo")
    void corteAlExportar() {
        String usuario = crearUsuario("coord", Perfil.COORDINADOR);
        Navegador coord = navegador();
        coord.ingresar(usuario, CLAVE);
        Respuesta r = coord.get("/api/reportes/lugar/exportar?formato=csv&desde=2026-01-01&hasta=2026-12-31");
        assertThat(r.estado()).as(r.texto()).isEqualTo(503);
        assertThat(r.mensaje()).contains("tope técnico", "para generarse");
        var fila = jdbc.sql("SELECT estado, ruta, error, terminado IS NOT NULL AS terminado FROM reportes_generados WHERE usuario_id = :u AND formato = 'csv'")
                .param("u", idDe(usuario)).query().singleRow();
        assertThat(fila.get("estado")).isEqualTo("fallida");
        assertThat(fila.get("ruta")).isNull();
        assertThat((String) fila.get("error")).contains("tope técnico");
        assertThat(fila.get("terminado")).isEqualTo(true);
        assertThat(bitacoraDe(idDe(usuario))).anyMatch(f -> f.contains("|reporte_fallido|") && f.contains("no se generó"));
        assertThat(jdbc.sql("SELECT count(*) FROM reportes_generados WHERE usuario_id = :u AND estado IN ('pendiente', 'generando')").param("u", idDe(usuario))
                .query(Integer.class).single()).isZero();
    }
}
