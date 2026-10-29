package co.com.agsamericas.digsa.analitica.ingesta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.analitica.PropiedadesAnalitica;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Seudónimo HMAC-SHA256 del paciente y del profesional (A5 4.7.3). */
class SeudonimizadorTest {

    private static Seudonimizador con(String clave, String entorno) {
        ConfiguracionDigsa cfg = new ConfiguracionDigsa("http://localhost/", entorno, null, null, null, null, null, null, null);
        return new Seudonimizador(PropiedadesAnalitica.conClave(clave), cfg);
    }

    @Test
    @DisplayName("estable: «CC 1.023.456» y «CC1023456» dan el mismo seudónimo de 64 hexadecimales, sin el documento")
    void estable() {
        Seudonimizador s = con("clave-de-prueba-no-secreta-para-el-hmac", "local");
        String a = s.paciente(null, "CC 1.023.456");
        assertThat(a).matches("^[0-9a-f]{64}$").isEqualTo(s.paciente(null, "CC1023456")).isEqualTo(s.paciente("CC", "1023456"))
                .doesNotContain("1023456");
        assertThat(s.paciente("TI", "1023456")).as("otro tipo de documento, otra persona").isNotEqualTo(a);
        assertThat(s.profesional("CC1023456")).as("el profesional no cruza con el paciente").isNotEqualTo(a);
        assertThat(s.paciente(null, " ")).isNull();
    }

    @Test
    @DisplayName("la clave cambia el seudónimo; sin clave en local se usa la de desarrollo")
    void clave() {
        String a = con("clave-uno-de-prueba-no-secreta-xxxxxxxx", "local").paciente("CC", "1");
        String b = con("clave-dos-de-prueba-no-secreta-xxxxxxxx", "local").paciente("CC", "1");
        assertThat(a).isNotEqualTo(b);
        assertThat(con(null, "local").paciente("CC", "1")).isEqualTo(con(PropiedadesAnalitica.CLAVE_LOCAL, "local").paciente("CC", "1"));
        assertThat(con(null, "local").disponible()).isTrue();
    }

    @Test
    @DisplayName("«pruebas» sin clave: nunca una clave aleatoria; no hay seudónimo y todo cargue se rechaza con el mensaje de la clave")
    void pruebasSinClave() {
        Seudonimizador s = con(null, "pruebas");
        assertThat(s.disponible()).isFalse();
        assertThatThrownBy(s::exigir).isInstanceOf(ErrorNegocio.class).hasMessage(Seudonimizador.MENSAJE_SIN_CLAVE);
        assertThatThrownBy(() -> s.paciente("CC", "1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIGSA_ANALITICA_CLAVE_SEUDONIMO");
        assertThat(con("clave-de-prueba-no-secreta-xxxxxxxxxxxx", "pruebas").disponible()).isTrue();
    }
}
