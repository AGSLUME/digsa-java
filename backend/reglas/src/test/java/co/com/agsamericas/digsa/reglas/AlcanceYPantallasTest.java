package co.com.agsamericas.digsa.reglas;

import static co.com.agsamericas.digsa.reglas.Perfil.ADMINISTRADOR;
import static co.com.agsamericas.digsa.reglas.Perfil.ANALISTA;
import static co.com.agsamericas.digsa.reglas.Perfil.AUDITOR;
import static co.com.agsamericas.digsa.reglas.Perfil.CONSULTA;
import static co.com.agsamericas.digsa.reglas.Perfil.COORDINADOR;
import static co.com.agsamericas.digsa.reglas.Perfil.DIGSA;
import static co.com.agsamericas.digsa.reglas.Perfil.ESM;
import static co.com.agsamericas.digsa.reglas.Perfil.OPL_NACIONAL;
import static co.com.agsamericas.digsa.reglas.Perfil.OPL_REGIONAL;
import static co.com.agsamericas.digsa.reglas.Perfil.SANIDAD;
import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Alcance.DatosUsuario;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Portado de tests/alcance_test.php (de, puedeVerPunto, combinaciones) y de las rutas de tests/reglas_test.php. */
class AlcanceYPantallasTest {

    private static final DatosUsuario VACIO = new DatosUsuario(5, null, null, null);

    @Test
    @DisplayName("constantes: consulta, solicitan (el ESM no) y perfiles con alcance")
    void constantes() {
        assertThat(Alcance.PERFILES_CONSULTA).containsExactlyInAnyOrder(DIGSA, SANIDAD, ESM);
        assertThat(Alcance.PERFILES_SOLICITAN).containsExactlyInAnyOrder(DIGSA, SANIDAD);
    }

    @Test
    @DisplayName("de: Administrador, Coordinador y DIGSA ⇒ todo, aunque tengan datos sobrantes")
    void todo() {
        for (Perfil p : List.of(ADMINISTRADOR, COORDINADOR, DIGSA)) {
            assertThat(Alcance.de(List.of(p), VACIO)).as(p.nombre()).isEqualTo(new Alcance.Todo());
        }
        assertThat(Alcance.de(List.of(DIGSA), new DatosUsuario(5, "EJC", 7L, 3L))).isEqualTo(new Alcance.Todo());
    }

    @Test
    @DisplayName("de: perfiles acotados con su dato")
    void acotados() {
        assertThat(Alcance.de(List.of(SANIDAD), new DatosUsuario(5, "EJC", null, null))).isEqualTo(new Alcance.Fuerza("EJC"));
        assertThat(Alcance.de(List.of(SANIDAD), new DatosUsuario(5, "FAC", null, null))).isEqualTo(new Alcance.Fuerza("FAC"));
        assertThat(Alcance.de(List.of(OPL_REGIONAL), new DatosUsuario(5, null, null, 3L))).isEqualTo(new Alcance.Regional(3));
        assertThat(Alcance.de(List.of(ESM), new DatosUsuario(5, null, 7L, null))).isEqualTo(new Alcance.Esm(7));
        assertThat(Alcance.de(List.of(AUDITOR), new DatosUsuario(12, null, null, null))).isEqualTo(new Alcance.Equipo(12));
        assertThat(Alcance.de(List.of(OPL_NACIONAL), VACIO)).isEqualTo(new Alcance.Opl());
    }

    @Test
    @DisplayName("de: falla cerrado (sin perfiles, perfil sin su dato, Consulta retirado)")
    void fallaCerrado() {
        Alcance ninguno = new Alcance.Ninguno();
        assertThat(Alcance.de(List.of(), VACIO)).isEqualTo(ninguno);
        assertThat(Alcance.de(List.of(SANIDAD), VACIO)).isEqualTo(ninguno);
        assertThat(Alcance.de(List.of(SANIDAD), new DatosUsuario(5, "", null, null))).isEqualTo(ninguno);
        assertThat(Alcance.de(List.of(ESM), VACIO)).isEqualTo(ninguno);
        assertThat(Alcance.de(List.of(OPL_REGIONAL), VACIO)).isEqualTo(ninguno);
        assertThat(Alcance.de(List.of(SANIDAD, ESM, OPL_REGIONAL), VACIO)).isEqualTo(ninguno);
        assertThat(Alcance.de(List.of(CONSULTA), new DatosUsuario(5, "EJC", null, null))).isEqualTo(ninguno);
        assertThat(Alcance.de(List.of(ANALISTA), VACIO)).as("Analista: consulta nacional (decisión del 2026-10-22)").isEqualTo(new Alcance.Todo());
        assertThat(Perfil.deNombre("administrador")).as("otra grafía no cuenta").isEmpty();
    }

    @Test
    @DisplayName("de: varios perfiles (el más amplio gana; orden fuerza, regional, esm, equipo)")
    void varios() {
        assertThat(Alcance.de(List.of(AUDITOR, COORDINADOR), VACIO)).isEqualTo(new Alcance.Todo());
        assertThat(Alcance.de(List.of(ESM, OPL_NACIONAL), new DatosUsuario(5, null, 7L, null))).isEqualTo(new Alcance.Opl());
        assertThat(Alcance.de(List.of(SANIDAD, DIGSA), VACIO)).isEqualTo(new Alcance.Todo());
        DatosUsuario todos = new DatosUsuario(9, "FAC", 7L, 3L);
        assertThat(Alcance.de(List.of(AUDITOR, ESM, OPL_REGIONAL, SANIDAD), todos)).isEqualTo(new Alcance.Fuerza("FAC"));
        assertThat(Alcance.de(List.of(AUDITOR, ESM, OPL_REGIONAL), todos)).isEqualTo(new Alcance.Regional(3));
        assertThat(Alcance.de(List.of(AUDITOR, ESM), todos)).isEqualTo(new Alcance.Esm(7));
        assertThat(Alcance.de(List.of(SANIDAD, AUDITOR), new DatosUsuario(9, null, null, null))).isEqualTo(new Alcance.Equipo(9));
        assertThat(Alcance.de(List.of(SANIDAD, ESM), new DatosUsuario(5, null, 7L, null))).isEqualTo(new Alcance.Esm(7));
        assertThat(Alcance.de(List.of(CONSULTA, AUDITOR), new DatosUsuario(9, null, null, null))).isEqualTo(new Alcance.Equipo(9));
        assertThat(Alcance.de(List.of(OPL_NACIONAL, DIGSA), VACIO)).isEqualTo(new Alcance.Todo());
    }

    @Test
    @DisplayName("puedeVerPunto")
    void puedeVerPunto() {
        Alcance.Punto punto = new Alcance.Punto(7, "EJC", 3L);
        Alcance.Punto otro = new Alcance.Punto(8, "ARC", 4L);
        assertThat(Alcance.puedeVerPunto(new Alcance.Todo(), punto)).isTrue();
        assertThat(Alcance.puedeVerPunto(new Alcance.Opl(), punto)).isTrue();
        assertThat(Alcance.puedeVerPunto(new Alcance.Ninguno(), punto)).isFalse();
        assertThat(Alcance.puedeVerPunto(new Alcance.Fuerza("EJC"), punto)).isTrue();
        assertThat(Alcance.puedeVerPunto(new Alcance.Fuerza("EJC"), otro)).isFalse();
        assertThat(Alcance.puedeVerPunto(new Alcance.Fuerza("EJC"), new Alcance.Punto(9, null, 3L))).isFalse();
        assertThat(Alcance.puedeVerPunto(new Alcance.Regional(3), punto)).isTrue();
        assertThat(Alcance.puedeVerPunto(new Alcance.Regional(3), otro)).isFalse();
        assertThat(Alcance.puedeVerPunto(new Alcance.Regional(3), new Alcance.Punto(9, "EJC", null))).isFalse();
        assertThat(Alcance.puedeVerPunto(new Alcance.Esm(7), punto)).isTrue();
        assertThat(Alcance.puedeVerPunto(new Alcance.Esm(7), otro)).isFalse();
        assertThat(Alcance.puedeVerPunto(new Alcance.Equipo(7), punto)).as("el auditor ve visitas, no el maestro").isFalse();
    }

    @Test
    @DisplayName("combinaciones de perfiles: un solo perfil con alcance; el OPL no se mezcla con la interventoría")
    void combinaciones() {
        assertThat(Alcance.combinacionInvalida(List.of(ESM, OPL_NACIONAL))).isPresent();
        assertThat(Alcance.combinacionInvalida(List.of(DIGSA, SANIDAD))).get().asString().contains("un solo perfil");
        assertThat(Alcance.combinacionInvalida(List.of(OPL_REGIONAL, AUDITOR))).get().asString().contains("no se combinan");
        assertThat(Alcance.combinacionInvalida(List.of(OPL_NACIONAL, COORDINADOR))).isPresent();
        assertThat(Alcance.combinacionInvalida(List.of(OPL_NACIONAL, ANALISTA))).isPresent();
        assertThat(Alcance.combinacionInvalida(List.of(AUDITOR, COORDINADOR))).isEmpty();
        assertThat(Alcance.combinacionInvalida(List.of(ADMINISTRADOR, DIGSA))).isEmpty();
        assertThat(Alcance.combinacionInvalida(List.of(SANIDAD))).isEmpty();
        assertThat(Alcance.combinacionInvalida(List.of(ANALISTA, ESM))).as("el Analista ya ve todo el país: no se mezcla con otro alcance").isPresent();
        assertThat(Alcance.combinacionInvalida(List.of(ANALISTA, AUDITOR))).isEmpty();
    }

    private static boolean entra(Pantallas p, Perfil... perfiles) {
        return p.puedeEntrar(Set.of(perfiles), true);
    }

    @Test
    @DisplayName("pantallas por perfil (rutas de tests/reglas_test.php)")
    void pantallas() {
        assertThat(entra(Pantallas.MIS_VISITAS, AUDITOR)).isTrue();
        for (Pantallas p : List.of(Pantallas.AGENDA, Pantallas.MAESTROS, Pantallas.DASHBOARD, Pantallas.USUARIOS, Pantallas.CALENDARIO,
                Pantallas.SOLICITUDES, Pantallas.RIESGO)) {
            assertThat(entra(p, AUDITOR)).as("auditor no entra a " + p.ruta()).isFalse();
        }
        assertThat(entra(Pantallas.AGENDA, COORDINADOR) && entra(Pantallas.MAESTROS, COORDINADOR) && entra(Pantallas.DASHBOARD, COORDINADOR)
                && entra(Pantallas.SOLICITUDES, COORDINADOR) && entra(Pantallas.PROPUESTA, COORDINADOR) && entra(Pantallas.CATALOGOS, COORDINADOR))
                .isTrue();
        assertThat(entra(Pantallas.USUARIOS, COORDINADOR)).isFalse();
        assertThat(entra(Pantallas.DASHBOARD, CONSULTA) || entra(Pantallas.AGENDA, CONSULTA) || entra(Pantallas.CALENDARIO, CONSULTA)).isFalse();
        for (Perfil p : List.of(DIGSA, SANIDAD)) {
            assertThat(entra(Pantallas.CALENDARIO, p) && entra(Pantallas.DASHBOARD, p) && entra(Pantallas.REPORTES, p) && entra(Pantallas.HALLAZGOS, p)
                    && entra(Pantallas.SOLICITUDES, p) && entra(Pantallas.RIESGO, p)).as(p.nombre()).isTrue();
            assertThat(entra(Pantallas.AGENDA, p) || entra(Pantallas.MAESTROS, p) || entra(Pantallas.MIS_VISITAS, p) || entra(Pantallas.PROPUESTA, p))
                    .as(p.nombre()).isFalse();
        }
        for (Perfil p : List.of(OPL_NACIONAL, OPL_REGIONAL)) {
            assertThat(entra(Pantallas.OPL, p)).isTrue();
            for (Pantallas x : List.of(Pantallas.CALENDARIO, Pantallas.DASHBOARD, Pantallas.REPORTES, Pantallas.SOLICITUDES, Pantallas.AGENDA,
                    Pantallas.MAESTROS, Pantallas.MIS_VISITAS)) {
                assertThat(entra(x, p)).as(p.nombre() + " no entra a " + x.ruta()).isFalse();
            }
        }
        assertThat(entra(Pantallas.CALENDARIO, ESM) && entra(Pantallas.DASHBOARD, ESM) && entra(Pantallas.REPORTES, ESM) && entra(Pantallas.HALLAZGOS, ESM))
                .isTrue();
        assertThat(entra(Pantallas.SOLICITUDES, ESM)).as("el ESM no solicita visitas (decisión 35)").isFalse();
        assertThat(entra(Pantallas.USUARIOS, ADMINISTRADOR) && entra(Pantallas.AGENDA, ADMINISTRADOR) && entra(Pantallas.MIS_VISITAS, ADMINISTRADOR)
                && entra(Pantallas.LOGS, ADMINISTRADOR)).isTrue();
        assertThat(entra(Pantallas.INICIO, ANALISTA)).isTrue();
        for (Pantallas x : List.of(Pantallas.CALENDARIO, Pantallas.DASHBOARD, Pantallas.RIESGO, Pantallas.REPORTES, Pantallas.HALLAZGOS)) {
            assertThat(entra(x, ANALISTA)).as("Analista entra a " + x.ruta()).isTrue();
        }
        for (Pantallas x : List.of(Pantallas.AGENDA, Pantallas.SOLICITUDES, Pantallas.MIS_VISITAS, Pantallas.USUARIOS, Pantallas.LOGS, Pantallas.OPL)) {
            assertThat(entra(x, ANALISTA)).as("Analista no entra a " + x.ruta()).isFalse();
        }
        assertThat(Pantallas.INICIO.puedeEntrar(Set.of(), false)).as("sin sesión: nada").isFalse();
    }
}
