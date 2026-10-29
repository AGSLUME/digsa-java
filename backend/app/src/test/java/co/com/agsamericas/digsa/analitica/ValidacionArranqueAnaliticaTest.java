package co.com.agsamericas.digsa.analitica;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sin la clave del seudónimo en producción la aplicación no arranca (aprobación de A0); en los demás entornos arranca con un aviso (los
 * cargues se rechazan: SeudonimizadorTest). El mensaje nombra la variable, no el valor.
 */
class ValidacionArranqueAnaliticaTest {

    private static PropiedadesAnalitica clave(String c) {
        return PropiedadesAnalitica.conClave(c);
    }

    @Test
    @DisplayName("producción sin clave: no arranca y el mensaje nombra DIGSA_ANALITICA_CLAVE_SEUDONIMO")
    void produccionSinClave() {
        assertThat(ValidacionArranqueAnalitica.problemas(true, clave(null))).singleElement().asString()
                .contains("DIGSA_ANALITICA_CLAVE_SEUDONIMO");
        assertThat(ValidacionArranqueAnalitica.problemas(true, clave("  "))).hasSize(1);
    }

    @Test
    @DisplayName("producción con clave corta o la de desarrollo: no arranca, sin mostrar la clave")
    void produccionClaveDebil() {
        assertThat(ValidacionArranqueAnalitica.problemas(true, clave("corta"))).singleElement().asString().doesNotContain("corta");
        assertThat(ValidacionArranqueAnalitica.problemas(true, clave(PropiedadesAnalitica.CLAVE_LOCAL))).hasSize(1);
        assertThat(ValidacionArranqueAnalitica.problemas(true, clave("x".repeat(40)))).isEmpty();
    }

    @Test
    @DisplayName("«pruebas» sin clave: arranca, con el aviso de que los cargues se rechazan (nunca una clave aleatoria)")
    void pruebasSinClave() {
        assertThat(ValidacionArranqueAnalitica.problemas(false, clave(null))).isEmpty();
        assertThat(ValidacionArranqueAnalitica.aviso(false, clave(null))).get().asString()
                .contains("DIGSA_ANALITICA_CLAVE_SEUDONIMO").contains("se rechaza");
        assertThat(ValidacionArranqueAnalitica.aviso(true, clave(null))).get().asString().contains("clave fija de desarrollo");
        assertThat(ValidacionArranqueAnalitica.aviso(false, clave("x".repeat(40)))).isEmpty();
    }
}
