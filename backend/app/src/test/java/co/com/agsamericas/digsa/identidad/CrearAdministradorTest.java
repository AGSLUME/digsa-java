package co.com.agsamericas.digsa.identidad;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Primer administrador por línea de comandos (portado de tests/e2e_local.php:161 y la especificación identidad-sesion). */
@TestPropertySource(properties = {
        "digsa.crear-admin.correo=Primer.Admin@Example.com",
        "digsa.crear-admin.nombre=Primer Administrador",
        "digsa.crear-admin.usuario=primer.admin",
        "digsa.crear-admin.salir=false"})
class CrearAdministradorTest extends PruebaIntegracion {

    @Autowired
    CrearAdministrador comando;

    @Test
    @DisplayName("crea el administrador activo con contraseña aleatoria sin definir, envía el enlace y deja admin_creado a nombre de cli")
    void creaElAdministrador() {
        assertThat(comando.codigo()).isZero();
        Usuario u = usuarios.porUsuario("primer.admin").orElseThrow();
        assertThat(u.correo()).isEqualTo("primer.admin@example.com");
        assertThat(u.activo()).isTrue();
        assertThat(u.claveDefinida()).isFalse();
        assertThat(u.perfiles()).contains(Perfil.ADMINISTRADOR);
        assertThat(ultimoCorreoA("primer.admin@example.com").html()).contains("definir-clave?tkn=");
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'admin_creado' AND usuario = 'cli'").query(Long.class).single())
                .isPositive();
    }

    @Test
    @DisplayName("con un correo ya registrado agrega el perfil Administrador sin quitar los otros y lo deja activo")
    void correoExistente() {
        String u = crearUsuario("yaexiste", Perfil.AUDITOR);
        jdbc.sql("UPDATE usuarios SET activo = false WHERE usuario = :u").param("u", u).update();
        assertThat(comando.ejecutar(u + "@example.com", "Ya Existe", "")).isZero();
        assertThat(comando.ejecutar("no-es-correo", "Nombre Valido", "")).as("datos no válidos").isEqualTo(1);
        assertThat(comando.ejecutar("ok@example.com", "AB", "")).as("nombre corto").isEqualTo(1);
        Usuario despues = usuarios.porUsuario(u).orElseThrow();
        assertThat(despues.perfiles()).containsAll(List.of(Perfil.AUDITOR, Perfil.ADMINISTRADOR));
        assertThat(despues.activo()).isTrue();
    }

    @Test
    @DisplayName("usuario por defecto a partir del correo: solo a-z0-9._-, mínimo 3 y sufijo si ya existe")
    void usuarioPorDefecto() {
        assertThat(comando.usuarioLibre("Ana@x.co")).isEqualTo("ana");
        assertThat(comando.usuarioLibre("a@x.co")).isEqualTo("a00");
        assertThat(comando.usuarioLibre("primer.admin@x.co")).isEqualTo("primer.admin2");
    }
}
