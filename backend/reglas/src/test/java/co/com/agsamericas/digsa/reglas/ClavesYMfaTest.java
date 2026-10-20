package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Portado de tests/reglas_test.php (contraseñas y enlaces) más el segundo factor por correo (nuevo). */
class ClavesYMfaTest {

    private static final Instant AHORA = Instant.parse("2026-10-20T15:00:00Z");

    @Test
    @DisplayName("contraseña válida")
    void valida() {
        assertThat(Claves.problema("Digsa.Prueba.2026")).isEmpty();
    }

    @Test
    @DisplayName("con ñ o tildes se rechaza (regla que se evalúa primero)")
    void sinNiTildes() {
        assertThat(Claves.problema("Contraseña.2026X")).get().asString().contains("ñ");
        assertThat(Claves.problema("Clave.Válida.2026")).get().asString().contains("tildes");
        assertThat(Claves.problema("Ñ")).contains("La contraseña no puede tener ñ ni tildes.");
        assertThat(Claves.problema("Pingüino.2026Xy")).get().asString().contains("tildes");
    }

    @Test
    @DisplayName("11 caracteres, sin especial, sin mayúsculas o sin número se rechaza")
    void reglas() {
        assertThat(Claves.problema("Abc.1234567")).contains("La contraseña debe tener mínimo 12 caracteres.");
        assertThat(Claves.problema("Abcdefgh12345")).get().asString().contains("especial");
        assertThat(Claves.problema("abcdefgh.12345")).get().asString().contains("mayúsculas");
        assertThat(Claves.problema("Abcdefgh.xyzw")).get().asString().contains("número");
        assertThat(Claves.problema(null)).isPresent();
    }

    @Test
    @DisplayName("más de 64 caracteres se rechaza (BCrypt usa 72 bytes)")
    void maximo() {
        assertThat(Claves.problema("Aa1." + "x".repeat(61))).get().asString().contains("máximo");
        assertThat(Claves.problema("Aa1." + "x".repeat(60))).isEmpty();
    }

    @Test
    @DisplayName("enlace vigente, vencido, con otro token y sin token guardado")
    void enlaces() {
        String t = "a".repeat(64);
        String huella = Huellas.sha256(t);
        assertThat(Claves.enlaceVigente(huella, AHORA.plusSeconds(60), t, AHORA)).isTrue();
        assertThat(Claves.enlaceVigente(huella, AHORA, t, AHORA)).as("vence en el mismo segundo: aún sirve").isTrue();
        assertThat(Claves.enlaceVigente(huella, AHORA.minusSeconds(1), t, AHORA)).isFalse();
        assertThat(Claves.enlaceVigente(huella, AHORA.plusSeconds(60), "b".repeat(64), AHORA)).isFalse();
        assertThat(Claves.enlaceVigente(null, null, t, AHORA)).isFalse();
        assertThat(Claves.enlaceVigente(huella, AHORA.plusSeconds(60), "A".repeat(64), AHORA)).as("solo hexadecimal en minúscula").isFalse();
    }

    @Test
    @DisplayName("secretos: 64 caracteres hexadecimales y distintos; código de 6 dígitos")
    void secretos() {
        String a = Huellas.secreto(32);
        assertThat(a).matches("^[0-9a-f]{64}$").isNotEqualTo(Huellas.secreto(32));
        for (int i = 0; i < 200; i++) {
            assertThat(Huellas.codigoSeisDigitos()).matches("^\\d{6}$");
        }
    }

    @Test
    @DisplayName("MFA: el código vale si coincide, es de 6 dígitos y está vigente con intentos")
    void mfa() {
        String huella = Huellas.sha256("012345");
        assertThat(Mfa.codigoValido("012345", huella)).isTrue();
        assertThat(Mfa.codigoValido("12345", huella)).isFalse();
        assertThat(Mfa.codigoValido("012346", huella)).isFalse();
        assertThat(Mfa.codigoValido(" 012345", huella)).isFalse();
        Instant vence = Mfa.vence(AHORA);
        assertThat(vence).isEqualTo(AHORA.plusSeconds(15 * 60));
        assertThat(Mfa.usable(vence, 0, false, AHORA)).isTrue();
        assertThat(Mfa.usable(vence, 0, false, vence)).isTrue();
        assertThat(Mfa.usable(vence, 0, false, vence.plusSeconds(1))).isFalse();
        assertThat(Mfa.usable(vence, 5, false, AHORA)).as("quinto intento fallido agota el código").isFalse();
        assertThat(Mfa.usable(vence, 0, true, AHORA)).isFalse();
        assertThat(Mfa.puedeEnviarOtro(4, Mfa.CODIGOS_POR_HORA)).isTrue();
        assertThat(Mfa.puedeEnviarOtro(5, Mfa.CODIGOS_POR_HORA)).isFalse();
    }
}
