package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Campo;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Causa;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reglas de la ingesta del insumo del OPL (docs/especificaciones/analitica-ingesta). */
class AnaliticaIngestaTest {

    @Test
    @DisplayName("detecta «;» en el CSV oficial con BOM y lee 1234,50 como 1234.50")
    void detectaPuntoYComa() {
        String linea = "﻿DOC_PACIENTE;FZA;COD_ESM;VR_TOTAL";
        assertThat(AnaliticaIngesta.detectarDelimitador(linea)).isEqualTo(';');
        String primero = AnaliticaIngesta.sinBom(linea).split(";")[0];
        assertThat(primero).isEqualTo("DOC_PACIENTE");
        assertThat(AnaliticaIngesta.numero("1234,50", true)).contains(new BigDecimal("1234.50"));
    }

    @Test
    @DisplayName("detecta «,», tabulador y «|»; las comas entre comillas no cuentan; sin delimitador, «;»")
    void detectaOtrosDelimitadores() {
        assertThat(AnaliticaIngesta.detectarDelimitador("a,b,c")).isEqualTo(',');
        assertThat(AnaliticaIngesta.detectarDelimitador("a\tb\tc")).isEqualTo('\t');
        assertThat(AnaliticaIngesta.detectarDelimitador("a|b|c")).isEqualTo('|');
        assertThat(AnaliticaIngesta.detectarDelimitador("\"x,y,z\";b")).isEqualTo(';');
        assertThat(AnaliticaIngesta.detectarDelimitador("unacolumna")).isEqualTo(';');
        assertThat(AnaliticaIngesta.nombreDelimitador('\t')).isEqualTo("tab");
    }

    @Test
    @DisplayName("normaliza encabezados: mayúsculas, sin tildes ni signos")
    void normalizaEncabezados() {
        assertThat(AnaliticaIngesta.normalizarEncabezado("F. DISP.")).isEqualTo("F DISP");
        assertThat(AnaliticaIngesta.normalizarEncabezado("Fecha de dispensación")).isEqualTo("FECHA DE DISPENSACION");
        assertThat(AnaliticaIngesta.normalizarEncabezado("﻿Año_Nacimiento")).isEqualTo("ANO NACIMIENTO");
        assertThat(AnaliticaIngesta.firmaEncabezado(List.of("Fecha", "CUM"))).isEqualTo(AnaliticaIngesta.firmaEncabezado(List.of("FECHA", "cum")))
                .hasSize(64);
    }

    @Test
    @DisplayName("propone el mapeo completo de obligatorios con encabezados desconocidos por sinónimos")
    void proponeMapeoPorSinonimos() {
        List<String> enc = List.of("DOC_PACIENTE", "FZA", "COD_ESM", "MOD", "FEC_DISP", "DESC_MED", "CUM", "CANT", "VR_TOTAL");
        List<Campo> m = AnaliticaIngesta.proponerMapeo(enc);
        assertThat(m).containsExactly(Campo.PACIENTE, Campo.FUERZA, Campo.ESM, Campo.MODALIDAD, Campo.FECHA, Campo.MEDICAMENTO, Campo.CUM,
                Campo.CANTIDAD, Campo.VALOR);
        assertThat(AnaliticaIngesta.problemasMapeo(m)).isEmpty();
    }

    @Test
    @DisplayName("sinónimos de fecha y personales: «FECHA_DISPENSACION», «Fecha de entrega», «F. DISP.» ⇒ fecha; el nombre se descarta")
    void sinonimosVariados() {
        for (String e : List.of("FECHA_DISPENSACION", "Fecha de entrega", "F. DISP.", "fecha disp paciente")) {
            assertThat(AnaliticaIngesta.campoDe(e)).as(e).contains(Campo.FECHA);
        }
        assertThat(AnaliticaIngesta.campoDe("NOMBRE_PACIENTE")).contains(Campo.DESCARTAR);
        assertThat(AnaliticaIngesta.campoDe("Primer apellido")).contains(Campo.DESCARTAR);
        assertThat(AnaliticaIngesta.campoDe("Teléfono de contacto")).contains(Campo.DESCARTAR);
        assertThat(AnaliticaIngesta.campoDe("FECHA_NAC")).contains(Campo.FECHA_NACIMIENTO);
        assertThat(AnaliticaIngesta.campoDe("NOMBRE MEDICAMENTO")).contains(Campo.MEDICAMENTO);
        assertThat(AnaliticaIngesta.campoDe("VALOR_UNITARIO")).contains(Campo.VALOR_UNITARIO);
        assertThat(AnaliticaIngesta.campoDe("Vr. Unit.")).contains(Campo.VALOR_UNITARIO);
        assertThat(AnaliticaIngesta.campoDe("VALOR_TOTAL")).contains(Campo.VALOR);
        assertThat(AnaliticaIngesta.campoDe("Código CUM del producto")).contains(Campo.CUM);
        assertThat(AnaliticaIngesta.campoDe("Diagnóstico principal")).contains(Campo.CIE10);
        assertThat(AnaliticaIngesta.campoDe("columna rara")).isEmpty();
    }

    @Test
    @DisplayName("un campo se propone una sola vez; las columnas de descarte pueden repetirse")
    void unaVezPorCampo() {
        List<Campo> m = AnaliticaIngesta.proponerMapeo(List.of("CUM", "CODIGO CUM", "NOMBRES", "APELLIDOS"));
        assertThat(m).containsExactly(Campo.CUM, Campo.IGNORAR, Campo.DESCARTAR, Campo.DESCARTAR);
    }

    @Test
    @DisplayName("sin la columna de cantidad: «Falta asignar la columna de cantidad»; medicamento o CUM basta uno")
    void faltaObligatorio() {
        List<Campo> m = List.of(Campo.PACIENTE, Campo.FUERZA, Campo.ESM, Campo.MODALIDAD, Campo.FECHA, Campo.CUM, Campo.VALOR);
        assertThat(AnaliticaIngesta.problemasMapeo(m)).containsExactly("Falta asignar la columna de cantidad");
        List<Campo> sinMed = List.of(Campo.PACIENTE, Campo.FUERZA, Campo.ESM, Campo.MODALIDAD, Campo.FECHA, Campo.CANTIDAD, Campo.VALOR);
        assertThat(AnaliticaIngesta.problemasMapeo(sinMed)).containsExactly("Falta asignar la columna de medicamento o CUM");
        List<Campo> doble = List.of(Campo.PACIENTE, Campo.FUERZA, Campo.ESM, Campo.MODALIDAD, Campo.FECHA, Campo.CUM, Campo.CANTIDAD,
                Campo.VALOR, Campo.VALOR);
        assertThat(AnaliticaIngesta.problemasMapeo(doble)).containsExactly("La columna de valor está asignada dos veces");
    }

    @Test
    @DisplayName("encabezados repetidos tras normalizar")
    void encabezadosRepetidos() {
        assertThat(AnaliticaIngesta.repetidos(List.of("CUM", "Fecha", "cum", "FECHA", "fecha"))).containsExactly("cum", "FECHA");
        assertThat(AnaliticaIngesta.repetidos(List.of("A", "", "", "B"))).isEmpty();
        assertThat(AnaliticaIngesta.posicionesRepetidas(List.of("CUM", "Fecha", "cum", "FECHA", "fecha"))).containsExactly(3, 4, 5);
        assertThat(AnaliticaIngesta.motivoRepetidos(List.of(3, 7))).isEqualTo("Encabezados repetidos (columnas 3, 7)");
    }

    @Test
    @DisplayName("primera fila sin ningún encabezado reconocible: se guardan «Columna 1..n», nunca sus valores")
    void encabezadoNoReconocido() {
        assertThat(AnaliticaIngesta.encabezadoReconocido(List.of("CC 1.023.456", "ANA FICTICIA", "EJC", "14/03/2025"))).isFalse();
        assertThat(AnaliticaIngesta.encabezadoReconocido(List.of("DOC_PACIENTE", "OTRA"))).isTrue();
        assertThat(AnaliticaIngesta.encabezadosGenericos(3)).containsExactly("Columna 1", "Columna 2", "Columna 3");
    }

    @Test
    @DisplayName("privacidad del mapeo: una columna personal solo va a descartar, no se usa o un campo seudonimizado; la vista previa la enmascara")
    void privacidadDelMapeo() {
        List<String> enc = List.of("NOMBRE_PACIENTE", "DOC_PACIENTE", "FZA", "OTRA");
        assertThat(AnaliticaIngesta.problemasPrivacidad(enc, List.of(Campo.ESPECIALIDAD, Campo.PACIENTE, Campo.FUERZA, Campo.IGNORAR)))
                .singleElement().asString().contains("«NOMBRE_PACIENTE» tiene datos personales");
        assertThat(AnaliticaIngesta.problemasPrivacidad(enc, List.of(Campo.DESCARTAR, Campo.ID_DISPENSACION, Campo.FUERZA, Campo.IGNORAR)))
                .singleElement().asString().contains("«DOC_PACIENTE»");
        assertThat(AnaliticaIngesta.problemasPrivacidad(enc, List.of(Campo.DESCARTAR, Campo.PACIENTE, Campo.FUERZA, Campo.IGNORAR))).isEmpty();
        assertThat(AnaliticaIngesta.visibleEnVistaPrevia("FZA", Campo.FUERZA)).isTrue();
        assertThat(AnaliticaIngesta.visibleEnVistaPrevia("OTRA", Campo.IGNORAR)).as("sin uso: enmascarada").isFalse();
        assertThat(AnaliticaIngesta.visibleEnVistaPrevia("NOMBRE_PACIENTE", Campo.ESPECIALIDAD)).isFalse();
        assertThat(AnaliticaIngesta.visibleEnVistaPrevia("DOC_PACIENTE", Campo.PACIENTE)).as("se muestra el seudónimo, no el valor").isFalse();
    }

    @Test
    @DisplayName("subida por partes: ceil(tamaño / parte) partes, todas del tamaño máximo salvo la última")
    void partesDeLaSubida() {
        assertThat(AnaliticaIngesta.partesEsperadas(100, 50)).isEqualTo(2);
        assertThat(AnaliticaIngesta.partesEsperadas(101, 50)).isEqualTo(3);
        assertThat(AnaliticaIngesta.partesEsperadas(1, 50)).isEqualTo(1);
        assertThat(AnaliticaIngesta.tamanoParte(1, 3, 101, 50)).isEqualTo(50);
        assertThat(AnaliticaIngesta.tamanoParte(3, 3, 101, 50)).isEqualTo(1);
    }

    @Test
    @DisplayName("números: coma decimal con «;», punto decimal con «,», miles, $ y basura")
    void numeros() {
        assertThat(AnaliticaIngesta.numero("1.234,50", true)).contains(new BigDecimal("1234.50"));
        assertThat(AnaliticaIngesta.numero("1.234", true)).contains(new BigDecimal("1234"));
        assertThat(AnaliticaIngesta.numero("12.5", true)).contains(new BigDecimal("12.5"));
        assertThat(AnaliticaIngesta.numero("$ 5000", true)).contains(new BigDecimal("5000"));
        assertThat(AnaliticaIngesta.numero("-2", true)).contains(new BigDecimal("-2"));
        assertThat(AnaliticaIngesta.numero("1234.50", false)).contains(new BigDecimal("1234.50"));
        assertThat(AnaliticaIngesta.numero("1,234.50", false)).contains(new BigDecimal("1234.50"));
        assertThat(AnaliticaIngesta.numero("12a,50", true)).isEmpty();
        assertThat(AnaliticaIngesta.numero("", true)).isEmpty();
        assertThat(AnaliticaIngesta.numero(null, true)).isEmpty();
        assertThat(AnaliticaIngesta.numeroCanonico("1234,50", true)).isEqualTo("1234.50");
        assertThat(AnaliticaIngesta.numeroCanonico("12a,50", true)).as("se deja el original para que la validación lo rechace").isEqualTo("12a,50");
    }

    @Test
    @DisplayName("fechas: dd/mm/aaaa, aaaa-mm-dd, con hora, serial de Excel; 31/02/2025 no es fecha")
    void fechas() {
        LocalDate d = LocalDate.of(2025, 3, 14);
        assertThat(AnaliticaIngesta.fecha("14/03/2025")).contains(d);
        assertThat(AnaliticaIngesta.fecha("14-3-2025")).contains(d);
        assertThat(AnaliticaIngesta.fecha("2025-03-14")).contains(d);
        assertThat(AnaliticaIngesta.fecha("2025/03/14")).contains(d);
        assertThat(AnaliticaIngesta.fecha("20250314")).contains(d);
        assertThat(AnaliticaIngesta.fecha("2025-03-14 10:35:00")).contains(d);
        assertThat(AnaliticaIngesta.fecha("2025-03-14T10:35")).contains(d);
        assertThat(AnaliticaIngesta.fecha("45730")).as("serial de Excel").contains(d);
        assertThat(AnaliticaIngesta.fecha("31/02/2025")).isEmpty();
        assertThat(AnaliticaIngesta.fecha("2025-13-01")).isEmpty();
        assertThat(AnaliticaIngesta.fecha("ayer")).isEmpty();
        assertThat(AnaliticaIngesta.fechaCanonica("14/03/2025")).isEqualTo("2025-03-14");
        assertThat(AnaliticaIngesta.fechaCanonica("31/02/2025")).isEqualTo("31/02/2025");
        assertThat(AnaliticaIngesta.anioNacimiento("14/03/1980")).contains(1980);
    }

    @Test
    @DisplayName("CUM: se normaliza y se valida con ^\\d{1,9}-\\d{1,2}$")
    void cum() {
        assertThat(AnaliticaIngesta.normalizarCum(" 19901234 - 1 ")).isEqualTo("19901234-1");
        assertThat(AnaliticaIngesta.cumValido("19901234-1")).isTrue();
        assertThat(AnaliticaIngesta.cumValido("19901234-01")).isTrue();
        assertThat(AnaliticaIngesta.cumValido("ABC-12")).isFalse();
        assertThat(AnaliticaIngesta.cumValido("1234567890-1")).isFalse();
        assertThat(AnaliticaIngesta.cumValido("19901234")).isFalse();
        assertThat(AnaliticaIngesta.normalizarCum("  ")).isNull();
        assertThat(AnaliticaIngesta.normalizarCodigo(" j45.9 ", true)).isEqualTo("J459");
    }

    @Test
    @DisplayName("Fuerza y modalidad por sinónimos: «Ejército», «EJERCITO», «EJC» ⇒ EJC; «DISPENSACION» ⇒ Dispensación")
    void sinonimosDeValores() {
        assertThat(List.of("Ejército", "EJERCITO", "EJC", "ejercito nacional").stream().map(AnaliticaIngesta::fuerza)).containsOnly("EJC");
        assertThat(AnaliticaIngesta.fuerza("Armada")).isEqualTo("ARC");
        assertThat(AnaliticaIngesta.fuerza("Fuerza Aeroespacial Colombiana")).isEqualTo("FAC");
        assertThat(AnaliticaIngesta.fuerza("Policía")).isEqualTo("POLICIA");
        assertThat(AnaliticaIngesta.modalidad("DISPENSACION")).isEqualTo("Dispensación");
        assertThat(AnaliticaIngesta.modalidad("suministro")).isEqualTo("Suministro");
        assertThat(AnaliticaIngesta.modalidad("Domicilio")).isEqualTo("Domicilio");
    }

    @Test
    @DisplayName("documento del paciente: «CC 1.023.456» y «CC1023456» dan lo mismo; sin número, vacío")
    void documento() {
        assertThat(AnaliticaIngesta.documento(null, "CC 1.023.456")).isEqualTo("CC|1023456");
        assertThat(AnaliticaIngesta.documento(null, "CC1023456")).isEqualTo("CC|1023456");
        assertThat(AnaliticaIngesta.documento("cc", "1023456")).isEqualTo("CC|1023456");
        assertThat(AnaliticaIngesta.documento("C.C.", "1.023.456")).isEqualTo("CC|1023456");
        assertThat(AnaliticaIngesta.documento(null, "1023456")).isEqualTo("|1023456");
        assertThat(AnaliticaIngesta.documento("CC", " ")).isEmpty();
    }

    @Test
    @DisplayName("llave natural: con id de dispensación, (fuente, id, ítem); sin id, la fila completa; la fuente las separa")
    void llaveNatural() {
        String a = AnaliticaIngesta.llaveNatural("archivo", "D-1", "1", List.of("x", "y"));
        String b = AnaliticaIngesta.llaveNatural("archivo", "D-1", "1", List.of("otra", "fila"));
        assertThat(a).isEqualTo(b).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(AnaliticaIngesta.llaveNatural("archivo", "D-1", "2", List.of("x"))).isNotEqualTo(a);
        assertThat(AnaliticaIngesta.llaveNatural("ws_opl", "D-1", "1", List.of("x"))).isNotEqualTo(a);
        String f1 = AnaliticaIngesta.llaveNatural("archivo", null, null, List.of("x", "y"));
        assertThat(AnaliticaIngesta.llaveNatural("archivo", " ", null, List.of("x", "y"))).isEqualTo(f1);
        assertThat(AnaliticaIngesta.llaveNatural("archivo", null, null, List.of("x", "z"))).isNotEqualTo(f1);
        assertThat(AnaliticaIngesta.huella(java.util.Arrays.asList("a", null))).isNotEqualTo(AnaliticaIngesta.huella(List.of("a", "")));
        assertThat(AnaliticaIngesta.llavePorId(List.of(Campo.CUM, Campo.ID_DISPENSACION))).isTrue();
        assertThat(AnaliticaIngesta.llavePorId(List.of(Campo.CUM))).isFalse();
    }

    @Test
    @DisplayName("umbral de filas mal formadas: más del 1 % rechaza el archivo; el 1 % exacto no")
    void umbral() {
        BigDecimal u = AnaliticaIngesta.UMBRAL_MAL_FORMADAS;
        assertThat(AnaliticaIngesta.excedeUmbral(60_000, 2_000_000, u)).isTrue();
        assertThat(AnaliticaIngesta.excedeUmbral(20_000, 2_000_000, u)).isFalse();
        assertThat(AnaliticaIngesta.excedeUmbral(20_001, 2_000_000, u)).isTrue();
        assertThat(AnaliticaIngesta.excedeUmbral(0, 10, u)).isFalse();
        assertThat(AnaliticaIngesta.excedeUmbral(1, 0, u)).isFalse();
        assertThat(AnaliticaIngesta.motivoCamposDistintos(60_000, "18"))
                .isEqualTo("Número de campos distinto del encabezado (60000; primera línea 18)");
    }

    @Test
    @DisplayName("motivo agrupado de mayor a menor y texto del CSV de rechazos")
    void motivo() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("fecha_invalida", 71L);
        m.put("cum_invalido", 241L);
        m.put("valor_negativo", 0L);
        assertThat(AnaliticaIngesta.motivo(m)).isEqualTo("CUM con formato inválido (241), Fecha inválida (71)");
        assertThat(AnaliticaIngesta.motivo(Map.of())).isEmpty();
        assertThat(AnaliticaIngesta.textoCausa("duplicado_exacto", "50")).isEqualTo("Duplicado exacto de la línea 50");
        assertThat(AnaliticaIngesta.textoCausa("fecha_invalida", null)).isEqualTo("Fecha inválida");
        assertThat(Causa.deCodigo("cantidad_no_positiva")).contains(Causa.CANTIDAD_NO_POSITIVA);
        assertThat(Causa.deCodigo("nada")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("responsable, fuente legible, formatos aceptados y perfiles que cargan")
    void bitacora() {
        assertThat(AnaliticaIngesta.responsable("jgarcia", Set.of(Perfil.ANALISTA), false)).isEqualTo("jgarcia · analista");
        assertThat(AnaliticaIngesta.responsable("opl1", Set.of(Perfil.OPL_NACIONAL), true)).isEqualTo("Proceso automático · API OPL (opl1)");
        assertThat(AnaliticaIngesta.fuenteTexto("csv", ';')).isEqualTo("Archivo CSV (;) UTF-8");
        assertThat(AnaliticaIngesta.fuenteTexto("xlsx", ';')).isEqualTo("Archivo xlsx");
        assertThat(AnaliticaIngesta.fuenteTexto("json", ';')).isEqualTo("API OPL · dispensación");
        assertThat(AnaliticaIngesta.formatoDe("Insumo 2025.CSV")).contains("csv");
        assertThat(AnaliticaIngesta.formatoDe("insumo.csv.gz")).contains("gz");
        assertThat(AnaliticaIngesta.formatoDe("insumo.xls")).isEmpty();
        assertThat(AnaliticaIngesta.PERFILES_CARGA).containsExactlyInAnyOrder(Perfil.ADMINISTRADOR, Perfil.ANALISTA);
    }
}
