package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reglas de las alertas y la bandeja de salida (spec exportacion-alertas). */
class AlertasTest {

    @Test
    @DisplayName("teléfono: móvil colombiano a E.164 con o sin prefijo y separadores; fijo, corto o de otro país no")
    void telefono() {
        assertThat(Alertas.normalizarTelefono("3000000000")).contains("+573000000000");
        assertThat(Alertas.normalizarTelefono("+57 300 000 0000")).contains("+573000000000");
        assertThat(Alertas.normalizarTelefono("(+57) 300-000-0000")).contains("+573000000000");
        assertThat(Alertas.normalizarTelefono("0057 3000000000")).contains("+573000000000");
        assertThat(Alertas.normalizarTelefono("573000000000")).contains("+573000000000");
        assertThat(Alertas.normalizarTelefono("300.000.0000")).contains("+573000000000");
        assertThat(Alertas.normalizarTelefono("6015550000")).as("fijo de Bogotá").isEmpty();
        assertThat(Alertas.normalizarTelefono("300000000")).as("9 dígitos").isEmpty();
        assertThat(Alertas.normalizarTelefono("+13000000000")).as("otro país").isEmpty();
        assertThat(Alertas.normalizarTelefono("ext 123")).isEmpty();
        assertThat(Alertas.normalizarTelefono(null)).isEmpty();
        assertThat(Alertas.normalizarTelefono("  ")).isEmpty();
    }

    @Test
    @DisplayName("máscara del teléfono: indicativo, primer dígito y los 2 últimos; un número inválido no se muestra")
    void mascara() {
        assertThat(Alertas.enmascararTelefono("300 000 0012")).isEqualTo("+573*******12");
        assertThat(Alertas.enmascararTelefono("+573000000012")).doesNotContain("0000000");
        assertThat(Alertas.enmascararTelefono("123")).isEqualTo("***");
        assertThat(Alertas.enmascararTelefono(null)).isEqualTo("***");
    }

    @Test
    @DisplayName("texto del SMS: tipo y hora sin tildes, «Revise la Suite DIGSA», GSM-7 y máximo 160")
    void textoSms() {
        String t = Alertas.textoSms("Tarea diaria con fallos · OPL", LocalDateTime.of(2026, 10, 22, 14, 5));
        assertThat(t).isEqualTo("Alerta critica DIGSA: Tarea diaria con fallos OPL (22/10 14:05). Revise la Suite DIGSA");
        assertThat(Alertas.esGsm7(t)).isTrue();
        String largo = Alertas.textoSms("Almacén ".repeat(40), LocalDateTime.of(2026, 1, 2, 3, 4));
        assertThat(largo).hasSizeLessThanOrEqualTo(Alertas.MAX_SMS).endsWith("Revise la Suite DIGSA").doesNotContain("é");
        assertThat(Alertas.esGsm7(largo)).isTrue();
        assertThat(Alertas.gsm7("Señal «crítica» € ½")).isEqualTo("Senal critica ");
        assertThat(Alertas.esGsm7("ñandú")).isFalse();
    }

    @Test
    @DisplayName("reintentos de la bandeja: 1, 5, 30 min y 2 h; al quinto intento fallido se agota")
    void reintentos() {
        assertThat(Alertas.esperaReintento(0)).isEqualTo(Duration.ZERO);
        assertThat(Alertas.esperaReintento(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(Alertas.esperaReintento(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(Alertas.esperaReintento(3)).isEqualTo(Duration.ofMinutes(30));
        assertThat(Alertas.esperaReintento(4)).isEqualTo(Duration.ofHours(2));
        assertThat(Alertas.esperaReintento(9)).isEqualTo(Duration.ofHours(2));
        assertThat(Alertas.agotado(4)).isFalse();
        assertThat(Alertas.agotado(5)).isTrue();
    }

    @Test
    @DisplayName("deduplicación: una alerta abierta avisa la primera vez y luego como mucho una vez por hora")
    void deduplicacion() {
        Instant t = Instant.parse("2026-10-22T15:00:00Z");
        assertThat(Alertas.avisarDeNuevo(null, t)).isTrue();
        assertThat(Alertas.avisarDeNuevo(t, t.plusSeconds(59 * 60))).isFalse();
        assertThat(Alertas.avisarDeNuevo(t, t.plusSeconds(3600))).isTrue();
        assertThat(Alertas.bloqueoMasivo(4, 5)).isFalse();
        assertThat(Alertas.bloqueoMasivo(5, 5)).isTrue();
        assertThat(Alertas.bloqueoMasivo(50, 0)).as("umbral 0 = desactivado").isFalse();
    }
}
