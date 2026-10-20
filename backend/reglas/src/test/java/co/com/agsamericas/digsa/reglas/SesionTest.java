package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Portado de tests/sesion_test.php (Anexo 5, 3.2). */
class SesionTest {

    private static final Instant AHORA = Instant.ofEpochSecond(1_800_000_000L);

    @Test
    @DisplayName("MINUTOS_DEFECTO es 10")
    void defecto() {
        assertThat(Sesion.MINUTOS_DEFECTO).isEqualTo(10);
    }

    static Stream<Arguments> casosMinutos() {
        return Stream.of(
                Arguments.of(10, 10), Arguments.of(15, 15), Arguments.of(1, 1), Arguments.of(480, 480),
                Arguments.of("15", 15), Arguments.of(" 15 ", 15), Arguments.of("480", 480),
                Arguments.of("", 10), Arguments.of("   ", 10), Arguments.of(null, 10), Arguments.of("abc", 10),
                Arguments.of("15 min", 10), Arguments.of("10.5", 10), Arguments.of(10.5, 10),
                Arguments.of(0, 10), Arguments.of("0", 10), Arguments.of(-5, 10), Arguments.of("-5", 10),
                Arguments.of(481, 480), Arguments.of("1000", 480), Arguments.of("99999999999999999999", 480));
    }

    @ParameterizedTest(name = "minutos({0}) => {1}")
    @MethodSource("casosMinutos")
    void minutos(Object valor, int esperado) {
        assertThat(Sesion.minutos(valor)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("inactividad: al cumplirse el plazo ya está vencida")
    void inactividad() {
        assertThat(Sesion.vencida(null, AHORA, 10)).isFalse();
        assertThat(Sesion.vencida(AHORA.minusSeconds(599), AHORA, 10)).isFalse();
        assertThat(Sesion.vencida(AHORA.minusSeconds(600), AHORA, 10)).isTrue();
        assertThat(Sesion.vencida(AHORA.minusSeconds(601), AHORA, 10)).isTrue();
        assertThat(Sesion.vencida(AHORA, AHORA, 10)).isFalse();
        assertThat(Sesion.vencida(AHORA.minusSeconds(899), AHORA, 15)).isFalse();
        assertThat(Sesion.vencida(AHORA.minusSeconds(900), AHORA, 15)).isTrue();
        assertThat(Sesion.vencida(AHORA.minusSeconds(61), AHORA, 1)).isTrue();
    }

    @Test
    @DisplayName("bloqueo del segundo ingreso (decisión 7.1): con sesión activa sí; sin huella, o vencida por inactividad, no")
    void bloqueoSegundoIngreso() {
        String h = Sesion.huella("abc");
        assertThat(Sesion.bloqueaNuevoIngreso(h, AHORA.minusSeconds(599), AHORA, 10)).isTrue();
        assertThat(Sesion.bloqueaNuevoIngreso(h, AHORA.minusSeconds(600), AHORA, 10)).isFalse();
        assertThat(Sesion.bloqueaNuevoIngreso(null, AHORA, AHORA, 10)).as("salió con «Salir» o la cerró el administrador").isFalse();
        assertThat(Sesion.bloqueaNuevoIngreso("", AHORA, AHORA, 10)).isFalse();
        assertThat(Sesion.bloqueaNuevoIngreso(h, null, AHORA, 10)).isFalse();
    }

    @Test
    @DisplayName("huella: SHA-256 en hexadecimal, igual para el mismo id y distinta para otro")
    void huella() {
        String h = Sesion.huella("abc123");
        assertThat(h).matches("^[0-9a-f]{64}$").isEqualTo(Huellas.sha256("abc123"));
        assertThat(Sesion.huella("abc123")).isEqualTo(h);
        assertThat(Sesion.huella("abc124")).isNotEqualTo(h);
    }

    @Test
    @DisplayName("sesión única: solo la registrada es la vigente; sin huella ninguna; el id en claro no sirve como huella")
    void sesionUnica() {
        String h = Sesion.huella("abc123");
        assertThat(Sesion.esLaVigente(h, "abc123")).isTrue();
        assertThat(Sesion.esLaVigente(h, "otra")).isFalse();
        assertThat(Sesion.esLaVigente(null, "abc123")).isFalse();
        assertThat(Sesion.esLaVigente("", "abc123")).isFalse();
        assertThat(Sesion.esLaVigente("abc123", "abc123")).isFalse();
    }
}
