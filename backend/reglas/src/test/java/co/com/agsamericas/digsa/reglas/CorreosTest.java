package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Portado del enmascarado de tests/opl_test.php y de la validación de correos de los maestros. */
class CorreosTest {

    @Test
    @DisplayName("enmascarar: primera y última letra; con 2 o menos, solo la primera")
    void enmascarar() {
        assertThat(Correos.enmascarar("ana.perez@agsamericas.com.co")).isEqualTo("a*******z@agsamericas.com.co");
        assertThat(Correos.enmascarar("ab@x.co")).isEqualTo("a*@x.co");
        assertThat(Correos.enmascarar("a@x.co")).isEqualTo("a@x.co");
        assertThat(Correos.enmascarar("ñandú@x.co")).isEqualTo("ñ***ú@x.co");
    }

    @Test
    @DisplayName("validar y normalizar")
    void validar() {
        assertThat(Correos.valido("auditor1@example.com")).isTrue();
        assertThat(Correos.valido("sin-arroba")).isFalse();
        assertThat(Correos.valido("a@b")).isFalse();
        assertThat(Correos.valido(null)).isFalse();
        assertThat(Correos.normalizar("  Ana@Example.COM ")).isEqualTo("ana@example.com");
    }
}
