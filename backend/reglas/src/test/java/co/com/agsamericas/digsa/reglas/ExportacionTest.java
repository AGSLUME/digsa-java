package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Exportacion.TablaExportada;
import co.com.agsamericas.digsa.reglas.Exportacion.Verificacion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Portado de tests/exportacion_test.php, con el formato de la DIGSA (15.2): «;», coma decimal y hora de Colombia. */
class ExportacionTest {

    @Test
    @DisplayName("TABLAS: sin repetidas, sin las técnicas, con las de las alertas de la fase 10 y la de las exportaciones")
    void tablas() {
        assertThat(new HashSet<>(Exportacion.TABLAS)).hasSameSizeAs(Exportacion.TABLAS);
        assertThat(Exportacion.TABLAS).doesNotContainAnyElementsOf(Exportacion.TECNICAS)
                .contains("correos_salida", "sms_salida", "alertas_criticas", "alertas_eventos").contains("log_transacciones", "visitas", "exportaciones", "riesgo_incidencias");
        assertThat(Exportacion.TABLAS).doesNotContain("log_transacciones_defecto");
        assertThat(Exportacion.TABLAS).containsAll(Exportacion.EXCLUIDAS.keySet());
    }

    @Test
    @DisplayName("usuarios sin contraseña, enlace ni huella de sesión")
    void usuariosSinSecretos() {
        List<String> cols = Exportacion.columnas("usuarios", List.of("id", "usuario", "clave_hash", "token_hash", "token_vence", "sesion_huella", "correo"));
        assertThat(cols).containsExactly("id", "usuario", "correo");
    }

    @Test
    @DisplayName("OPL sin huellas del enlace ni del código; MFA sin la huella del código; firmas sin la imagen")
    void oplSinHuellas() {
        assertThat(Exportacion.columnas("opl_accesos", List.of("visita_id", "token_hash", "acta_id"))).containsExactly("visita_id", "acta_id");
        assertThat(Exportacion.columnas("opl_codigos", List.of("id", "visita_id", "codigo_hash"))).containsExactly("id", "visita_id");
        assertThat(Exportacion.columnas("mfa_codigos", List.of("id", "codigo_hash", "ip"))).containsExactly("id", "ip");
        assertThat(Exportacion.columnas("visitas_firmas", List.of("visita_id", "rol", "imagen"))).containsExactly("visita_id", "rol");
        assertThat(Exportacion.columnas("opl_planes", List.of("id", "firma", "nombre"))).containsExactly("id", "nombre");
    }

    @Test
    @DisplayName("las demás tablas salen con todas sus columnas, en su orden")
    void demasCompletas() {
        List<String> cols = List.of("id", "numero", "tipo", "punto_id", "fecha");
        assertThat(Exportacion.columnas("visitas", cols)).isEqualTo(cols);
    }

    @Test
    @DisplayName("sin fórmulas y NULL vacío: =SUMA(A1), @x, -x, NULL, normal y 5")
    void sinFormulas() {
        assertThat(Exportacion.fila(Arrays.asList("=SUMA(A1)", "@x", "-x", null, "normal", 5))).isEqualTo("'=SUMA(A1);'@x;'-x;;normal;5\r\n");
        assertThat(Exportacion.celda("+57 300")).isEqualTo("'+57 300");
        assertThat(Exportacion.celda("\tx")).isEqualTo("'\tx");
        assertThat(Exportacion.celda("a;b")).isEqualTo("\"a;b\"");
        assertThat(Exportacion.celda("dijo \"sí\"")).isEqualTo("\"dijo \"\"sí\"\"\"");
        assertThat(Exportacion.celda("línea\nnueva")).isEqualTo("\"línea\nnueva\"");
    }

    @Test
    @DisplayName("números con signo y coma decimal, sin apóstrofo y sin redondear")
    void numeros() {
        assertThat(Exportacion.fila(List.of("05001", new BigDecimal("-75.581775")))).isEqualTo("05001;-75,581775\r\n");
        assertThat(Exportacion.celda(new BigDecimal("1234567.5"))).isEqualTo("1234567,5");
        assertThat(Exportacion.celda(-3)).isEqualTo("-3");
        assertThat(Exportacion.celda(2.75d)).isEqualTo("2,75");
        assertThat(Exportacion.celda(1.5f)).isEqualTo("1,5");
        assertThat(Exportacion.celda(new BigDecimal("1E+3"))).isEqualTo("1000");
    }

    @Test
    @DisplayName("fechas en hora de Colombia de 24 h, booleanos true/false, arreglos como JSON")
    void fechasYOtros() {
        assertThat(Exportacion.celda(OffsetDateTime.of(2026, 10, 22, 3, 5, 9, 0, ZoneOffset.UTC))).isEqualTo("2026-10-21 22:05:09");
        assertThat(Exportacion.celda(LocalDate.of(2026, 1, 2))).isEqualTo("2026-01-02");
        assertThat(Exportacion.celda(LocalTime.of(14, 30))).isEqualTo("14:30:00");
        assertThat(Exportacion.celda(true)).isEqualTo("true");
        assertThat(Exportacion.celda(false)).isEqualTo("false");
        assertThat(Exportacion.celda(List.of("a@example.com", "b\"c"))).isEqualTo("\"[\"\"a@example.com\"\",\"\"b\\\"\"c\"\"]\"");
        assertThat(Exportacion.celda(List.of())).isEqualTo("[]");
    }

    @Test
    @DisplayName("nombre del soporte: <id>_<original saneado>, sin rutas ni caracteres raros")
    void nombreSoporte() {
        assertThat(Exportacion.nombreSoporte(7, "Foto Bodega #1.JPG")).isEqualTo("7_Foto_Bodega_1.JPG");
        assertThat(Exportacion.nombreSoporte(8, "../../etc/passwd")).isEqualTo("8_passwd");
        assertThat(Exportacion.nombreSoporte(9, "C:\\docs\\Acta revisión.pdf")).isEqualTo("9_Acta_revision.pdf");
        assertThat(Exportacion.nombreSoporte(10, "..")).isEqualTo("10_archivo");
        assertThat(Exportacion.nombreSoporte(11, null)).isEqualTo("11_archivo");
        assertThat(Exportacion.nombreSoporte(12, "a..b.pdf")).isEqualTo("12_a.b.pdf");
        assertThat(Exportacion.carpetaVisita("VS-20261022-0001", 3)).isEqualTo("VS-20261022-0001");
        assertThat(Exportacion.carpetaVisita(null, 3)).isEqualTo("visita_3");
        assertThat(Exportacion.seguro("it:HB-01", "x")).isEqualTo("it_HB-01");
    }

    @Test
    @DisplayName("verificación de la huella: coincide, distinta, no encontrado y sin huella")
    void verificacion() {
        String h = "a".repeat(64);
        assertThat(Exportacion.verificar(h, h)).isEqualTo(Verificacion.COINCIDE);
        assertThat(Exportacion.verificar(h.toUpperCase(), h)).isEqualTo(Verificacion.COINCIDE);
        assertThat(Exportacion.verificar(h, "b".repeat(64))).isEqualTo(Verificacion.DISTINTA);
        assertThat(Exportacion.verificar(h, null)).isEqualTo(Verificacion.NO_ENCONTRADO);
        assertThat(Exportacion.verificar(null, h)).isEqualTo(Verificacion.SIN_HUELLA);
        assertThat(Verificacion.DISTINTA.texto()).isEqualTo("huella distinta");
    }

    @Test
    @DisplayName("nombre del ZIP y detalle de la bitácora")
    void nombreYResumen() {
        assertThat(Exportacion.nombreZip(OffsetDateTime.of(2026, 10, 22, 3, 5, 0, 0, ZoneOffset.UTC))).isEqualTo("digsa_exportacion_20261021_2205.zip");
        assertThat(Exportacion.resumen(58, 1234, 7)).isEqualTo("58 tablas · 1234 filas · 7 soportes");
    }

    @Test
    @DisplayName("LEEME: quién, columnas excluidas, soportes, tablas y filas con su huella")
    void leeme() {
        String l = Exportacion.leeme(new Exportacion.Leeme("Admin (admin)", OffsetDateTime.of(2026, 10, 22, 15, 0, 0, 0, ZoneOffset.ofHours(-5)),
                List.of(new TablaExportada("usuarios", 3, "1".repeat(64)), new TablaExportada("visitas", 10, "2".repeat(64))), 4, 1, 2));
        assertThat(l).startsWith("Suite DIGSA · Exportación completa de los datos\r\n").contains("Admin (admin)").contains("2026-10-22 15:00:00")
                .contains("UTF-8 con BOM, separado por punto y coma, coma decimal").contains("usuarios: clave_hash, sesion_huella, token_hash, token_vence")
                .contains("opl_accesos: token_hash").contains("soportes/indice.csv").contains("acta_ronda_<n>.pdf").contains("Tablas y filas")
                .contains("visitas 10 · " + "2".repeat(64)).contains("usuarios 3").contains("Total: 2 tablas, 13 filas")
                .contains("Soportes: 4 (huella distinta: 1; no encontrados: 2)").contains("Ley 1581").doesNotContain("Aún no existen");
        assertThat(l.replace("\r\n", "")).doesNotContain("\n");
    }
}
