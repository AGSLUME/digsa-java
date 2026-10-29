package co.com.agsamericas.digsa.analitica;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Permisos de operación de la analítica con roles adicionales (PerfilesOperacion; revisión A3 2026-10-23): la base de los roles sale de
 * identidad con sus mismas reglas (solo roles válidos y vigentes). Una mezcla no válida (perfil directo + rol, que identidad anula) no
 * da la base del rol: falla cerrado.
 */
class PerfilesOperacionApiTest extends PruebaIntegracion {

    private Navegador como(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private int crearRol(Navegador adm, int base, List<String> pantallas) {
        Map<String, Object> m = new HashMap<>();
        m.put("nombre", unico("Rol analítica "));
        m.put("descripcion", "Rol de prueba");
        m.put("baseId", base);
        m.put("pantallas", pantallas);
        Navegador.Respuesta r = adm.post("/api/usuarios/roles", m);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        return r.json().path("id").asInt();
    }

    /** Asigna el rol directamente en la base (como una reactivación que deja una mezcla que la API no permitiría crear). */
    private void asignar(String usuario, int rol) {
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p)").param("u", idDe(usuario)).param("p", rol).update();
    }

    @Test
    @DisplayName("perfil directo Coordinador + rol de base Analista (mezcla no válida): 403 al cargar insumos y al operar la homologación; el rol solo sí opera")
    void mezclaNoDaLaBaseDelRol() {
        Navegador adm = como(crearUsuario("admanop", Perfil.ADMINISTRADOR));
        int analista = crearRol(adm, Perfil.ANALISTA.id(), List.of("ANALITICA_CARGUES", "HOMOLOGACION"));

        String mezcla = crearUsuario("mezclaop", Perfil.COORDINADOR);
        asignar(mezcla, analista);
        Navegador m = como(mezcla);
        assertThat(m.post("/api/analitica/cargues", Map.of()).estado()).isEqualTo(403);
        assertThat(m.put("/api/homologacion/reglas/999999", Map.of()).estado()).isEqualTo(403);

        String solo = crearUsuario("rolop");
        asignar(solo, analista);
        Navegador s = como(solo);
        assertThat(s.post("/api/analitica/cargues", Map.of()).estado()).as("con el rol válido pasa la autorización").isNotEqualTo(403);
        assertThat(s.put("/api/homologacion/reglas/999999", Map.of()).estado()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("perfil directo ESM + rol de base Coordinador: sin la bitácora nacional de analítica; el rol solo sí la ve")
    void esmConRolCoordinadorSinBitacora() {
        Navegador adm = como(crearUsuario("admanob", Perfil.ADMINISTRADOR));
        int coordinador = crearRol(adm, Perfil.COORDINADOR.id(), List.of("ANALITICA"));

        String mezcla = crearUsuario("esmrolop", Perfil.ESM);
        asignar(mezcla, coordinador);
        assertThat(como(mezcla).get("/api/analitica/bitacora").estado()).isEqualTo(403);

        String solo = crearUsuario("coordrolop");
        asignar(solo, coordinador);
        Navegador s = como(solo);
        assertThat(s.get("/api/analitica/bitacora").estado()).isEqualTo(200);
        assertThat(s.get("/api/analitica/catalogo").json().path("puedeVerBitacora").asBoolean()).isTrue();
    }
}
