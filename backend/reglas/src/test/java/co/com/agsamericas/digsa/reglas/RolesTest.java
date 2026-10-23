package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Roles adicionales parametrizables (Anexo 5, 1.12): perfil base + subconjunto de sus pantallas; fallan cerrado. */
class RolesTest {

    private static final Roles.Adicional GESTOR_RIESGO = new Roles.Adicional(100, Perfil.COORDINADOR, Set.of(Pantallas.RIESGO, Pantallas.DASHBOARD));

    @Test
    @DisplayName("elegibles: las pantallas del menú de su base; sin inicio ni ocultas; Administrador, Auditor y Consulta no son base")
    void elegibles() {
        assertThat(Roles.elegibles(Perfil.COORDINADOR)).contains(Pantallas.AGENDA, Pantallas.RIESGO)
                .doesNotContain(Pantallas.INICIO, Pantallas.VISITA, Pantallas.USUARIOS, Pantallas.LOGS, Pantallas.EXPORTACION, Pantallas.OPL);
        assertThat(Roles.elegibles(Perfil.OPL_REGIONAL)).containsExactly(Pantallas.OPL);
        assertThat(Roles.elegibles(Perfil.ANALISTA)).contains(Pantallas.CALENDARIO, Pantallas.REPORTES).doesNotContain(Pantallas.SOLICITUDES);
        assertThat(Roles.elegibles(Perfil.ADMINISTRADOR)).isEmpty();
        assertThat(Roles.elegibles(Perfil.AUDITOR)).isEmpty();
        assertThat(Roles.elegibles(Perfil.CONSULTA)).isEmpty();
    }

    @Test
    @DisplayName("problema: base válida y al menos una pantalla, todas del perfil base")
    void problema() {
        assertThat(Roles.problema(Perfil.COORDINADOR, Set.of(Pantallas.RIESGO))).isEmpty();
        assertThat(Roles.problema(Perfil.ADMINISTRADOR, Set.of(Pantallas.USUARIOS))).get().asString().contains("Administrador");
        assertThat(Roles.problema(Perfil.AUDITOR, Set.of(Pantallas.MIS_VISITAS))).isPresent();
        assertThat(Roles.problema(null, Set.of(Pantallas.RIESGO))).isPresent();
        assertThat(Roles.problema(Perfil.DIGSA, Set.of())).get().asString().contains("al menos una pantalla");
        assertThat(Roles.problema(Perfil.DIGSA, Set.of(Pantallas.AGENDA))).get().asString().contains("Agenda de visitas");
        assertThat(Roles.problema(Perfil.COORDINADOR, Set.of(Pantallas.VISITA))).as("pantalla oculta").isPresent();
    }

    @Test
    @DisplayName("permitidas: el rol suma solo sus pantallas, nunca todas las de su base")
    void permitidas() {
        assertThat(Roles.permitidas(Set.of(), List.of(GESTOR_RIESGO))).containsExactlyInAnyOrder(Pantallas.INICIO, Pantallas.RIESGO, Pantallas.DASHBOARD);
        assertThat(Roles.permitidas(Set.of(Perfil.ADMINISTRADOR), List.of())).contains(Pantallas.values());
        assertThat(Roles.permitidas(Set.of(), List.of())).containsExactly(Pantallas.INICIO);
    }

    @Test
    @DisplayName("falla cerrado: pantalla que ya no es de la base, rol sin pantallas válidas o con base no permitida no dan pantallas ni alcance")
    void fallaCerrado() {
        Roles.Adicional raro = new Roles.Adicional(101, Perfil.DIGSA, Set.of(Pantallas.CALENDARIO, Pantallas.AGENDA));
        assertThat(Roles.permitidas(Set.of(), List.of(raro))).contains(Pantallas.CALENDARIO).doesNotContain(Pantallas.AGENDA);
        Roles.Adicional vacio = new Roles.Adicional(102, Perfil.COORDINADOR, Set.of(Pantallas.VISITA));
        assertThat(Roles.vigente(vacio)).isFalse();
        assertThat(Roles.deAlcance(Set.of(), List.of(vacio))).as("sin pantallas válidas no da el alcance de su base").isEmpty();
        Roles.Adicional admin = new Roles.Adicional(103, Perfil.ADMINISTRADOR, Set.of(Pantallas.USUARIOS));
        assertThat(Roles.permitidas(Set.of(), List.of(admin))).containsExactly(Pantallas.INICIO);
        assertThat(Roles.deAlcance(Set.of(), List.of(admin))).isEmpty();
    }

    @Test
    @DisplayName("deAlcance: directos más la base de cada rol vigente (el alcance de los datos sale de la base)")
    void deAlcance() {
        assertThat(Roles.deAlcance(Set.of(), List.of(GESTOR_RIESGO))).containsExactly(Perfil.COORDINADOR);
        Roles.Adicional esm = new Roles.Adicional(104, Perfil.ESM, Set.of(Pantallas.CALENDARIO));
        assertThat(Alcance.de(Roles.deAlcance(Set.of(), List.of(esm)), new Alcance.DatosUsuario(5, null, 7L, null))).isEqualTo(new Alcance.Esm(7));
    }

    @Test
    @DisplayName("asignación: con roles, ningún perfil directo y una sola base (el alcance no se amplía por mezcla)")
    void asignacion() {
        assertThat(Roles.asignacionInvalida(Set.of(Perfil.AUDITOR), List.of())).isEmpty();
        assertThat(Roles.asignacionInvalida(Set.of(), List.of(GESTOR_RIESGO))).isEmpty();
        assertThat(Roles.asignacionInvalida(Set.of(Perfil.AUDITOR), List.of(GESTOR_RIESGO))).get().asString().contains("no tiene además perfiles");
        Roles.Adicional digsa = new Roles.Adicional(105, Perfil.DIGSA, Set.of(Pantallas.CALENDARIO));
        assertThat(Roles.asignacionInvalida(Set.of(), List.of(GESTOR_RIESGO, digsa))).get().asString().contains("mismo perfil base");
        Roles.Adicional otroCoord = new Roles.Adicional(106, Perfil.COORDINADOR, Set.of(Pantallas.REPORTES));
        assertThat(Roles.asignacionInvalida(Set.of(), List.of(GESTOR_RIESGO, otroCoord))).isEmpty();
    }
}
