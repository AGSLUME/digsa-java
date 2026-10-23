package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.CodigoSsfm;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Concentracion;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Configuracion;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Estado;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.GrupoCum;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Origen;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Presentacion;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.PrincipioCum;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Referencias;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.ReferenciaCie10;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Regla;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Resultado;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reglas de la homologación (docs/especificaciones/analitica-homologacion): cada regla con su caso, precedencia de estados,
 * normalización de texto, sinónimos, casos del mockup, determinismo e insumo no visto con errores sembrados al azar. Las referencias son
 * de ejemplo, ficticias, con el formato del CUM del INVIMA.
 */
class AnaliticaHomologacionTest {

    private static final LocalDate VENCE = LocalDate.of(2031, 12, 31);
    private static final Configuracion C = Configuracion.porDefecto();

    // ---------- Referencias de ejemplo (ficticias) ----------

    private static Presentacion pres(String cum, String conjunto, String producto, String principio, String cantidad, String unidad, String forma,
                                     String atc, int empaque) {
        return new Presentacion(cum, conjunto, producto, "LABORATORIO FICTICIO S.A.", "INVIMA 2019M-0012345", VENCE, "Vigente", "Activo",
                BigDecimal.valueOf(empaque), "CAJA X " + empaque + " " + forma, "U", atc, null, "ORAL", forma, null,
                List.of(new PrincipioCum(principio, new BigDecimal(cantidad), unidad, "TABLETA")));
    }

    /** Referencias en memoria: presentaciones por CUM y por base del principio, y catálogo SSFM. */
    private static Referencias refs(List<Presentacion> ps, List<CodigoSsfm> ssfm) {
        return new Referencias() {
            @Override
            public boolean hayCum() {
                return !ps.isEmpty();
            }

            @Override
            public List<Presentacion> porCum(String cum) {
                return ps.stream().filter(p -> p.cum().equals(cum)).toList();
            }

            @Override
            public List<Presentacion> porPrincipio(String base) {
                return ps.stream().filter(p -> p.principios().stream()
                        .anyMatch(x -> AnaliticaHomologacion.principioBase(AnaliticaHomologacion.normalizarTexto(x.nombre()), Set.of()).equals(base)))
                        .toList();
            }

            @Override
            public boolean haySsfm() {
                return ssfm != null;
            }

            @Override
            public List<CodigoSsfm> ssfm() {
                return ssfm == null ? List.of() : ssfm;
            }
        };
    }

    private static final List<Presentacion> MOCKUP = List.of(
            pres("19900001-1", "vigentes", "METFORMINA 850 MG", "METFORMINA CLORHIDRATO", "850", "mg", "TABLETA", "A10BA02", 30),
            pres("19900001-2", "vigentes", "METFORMINA 850 MG", "METFORMINA CLORHIDRATO", "850", "mg", "TABLETA", "A10BA02", 60),
            pres("19900002-1", "vigentes", "DOLEX FICTICIO", "PARACETAMOL", "500", "mg", "TABLETA", "N02BE01", 20),
            pres("19950001-1", "vencidos", "ADALIMUMAB FICTICIO", "ADALIMUMAB", "40", "mg", "SOLUCION INYECTABLE", "L04AB04", 2),
            pres("19960001-1", "vigentes", "LOSARTAN FICTICIO", "LOSARTAN POTASICO", "50", "mg", "TABLETA RECUBIERTA", "C09CA01", 30),
            pres("19970001-1", "vigentes", "GLARGINA FICTICIA", "INSULINA GLARGINA", "100", "UI", "SOLUCION INYECTABLE", "A10AE04", 1));
    private static final List<CodigoSsfm> SSFM = List.of(new CodigoSsfm("SSFM-0001", null, "METFORMINA", "850 mg", "TABLETA"),
            new CodigoSsfm("SSFM-0002", "19900002-1", null, null, null), new CodigoSsfm("SSFM-0003", "19960001-1", null, null, null),
            new CodigoSsfm("SSFM-0004", null, "PARACETAMOL", null, null));

    private static Origen origen(long id, String cum, String descripcion) {
        return new Origen(id, cum, descripcion, null, null, null, LocalDate.of(2025, 6, 1));
    }

    private static Resultado homologar(Origen o) {
        return AnaliticaHomologacion.homologar(o, C, refs(MOCKUP, SSFM));
    }

    // ---------- Estados ----------

    @Test
    @DisplayName("precedencia: no válido > duplicado > inconsistente > sin correspondencia > pendiente > homologado")
    void precedencia() {
        assertThat(AnaliticaHomologacion.precedente(List.of(Estado.HOMOLOGADO, Estado.PENDIENTE))).isEqualTo(Estado.PENDIENTE);
        assertThat(AnaliticaHomologacion.precedente(List.of(Estado.PENDIENTE, Estado.SIN_CORRESPONDENCIA))).isEqualTo(Estado.SIN_CORRESPONDENCIA);
        assertThat(AnaliticaHomologacion.precedente(List.of(Estado.SIN_CORRESPONDENCIA, Estado.INCONSISTENTE))).isEqualTo(Estado.INCONSISTENTE);
        assertThat(AnaliticaHomologacion.precedente(List.of(Estado.INCONSISTENTE, Estado.DUPLICADO))).isEqualTo(Estado.DUPLICADO);
        assertThat(AnaliticaHomologacion.precedente(List.of(Estado.DUPLICADO, Estado.NO_VALIDO, Estado.HOMOLOGADO))).isEqualTo(Estado.NO_VALIDO);
        assertThat(AnaliticaHomologacion.precedente(List.of())).isEqualTo(Estado.HOMOLOGADO);
        assertThat(Estado.deCodigo("no_valido")).contains(Estado.NO_VALIDO);
        assertThat(Estado.NO_VALIDO.texto()).isEqualTo("CUM o registro no válido");
    }

    // ---------- Normalización (R-010, R-011) ----------

    @Test
    @DisplayName("R-010: mayúsculas, sin tildes, número y unidad separados, barras sin espacio y abreviaturas expandidas")
    void normalizacionDeTexto() {
        assertThat(AnaliticaHomologacion.normalizarTexto("  Metformina 850mg tab x30 ")).isEqualTo("METFORMINA 850 MG TAB X 30");
        assertThat(AnaliticaHomologacion.normalizarTexto("Insulina glargina 100 UI / ml")).isEqualTo("INSULINA GLARGINA 100 UI/ML");
        assertThat(AnaliticaHomologacion.normalizarTexto("Tamsulosina 0,4 mg cápsula")).isEqualTo("TAMSULOSINA 0,4 MG CAPSULA");
        assertThat(AnaliticaHomologacion.normalizarTexto("Ácido acetilsalicílico, 100 mg.")).isEqualTo("ACIDO ACETILSALICILICO 100 MG");
        assertThat(AnaliticaHomologacion.normalizarTexto(null)).isEmpty();
        String n = AnaliticaHomologacion.normalizarTexto("ACETAMINOFEN 500 tab");
        assertThat(AnaliticaHomologacion.expandir(n, C.abreviaturas(), C.unidades())).isEqualTo("ACETAMINOFEN 500 TABLETA");
        assertThat(AnaliticaHomologacion.expandir("AMOXICILINA 250 MILIGRAMOS/5 ML SUSP", C.abreviaturas(), C.unidades()))
                .isEqualTo("AMOXICILINA 250 MG/5 ML SUSPENSION");
    }

    @Test
    @DisplayName("R-011/R-012: concentración con unidad canónica (g y mcg a mg), combinaciones, «/ml» y número sin unidad; la del CUM")
    void concentracion() {
        Concentracion a = AnaliticaHomologacion.concentracion("METFORMINA 850 MG TABLETA X 30").orElseThrow();
        assertThat(a.valores()).containsExactly(new BigDecimal("850"));
        assertThat(a.unidad()).isEqualTo("MG");
        Concentracion g = AnaliticaHomologacion.concentracion("CEFTRIAXONA 1 G VIAL").orElseThrow();
        assertThat(AnaliticaHomologacion.mismaConcentracion(g, AnaliticaHomologacion.concentracion("1000 MG").orElseThrow(), BigDecimal.ZERO)).isTrue();
        Concentracion combo = AnaliticaHomologacion.concentracion("SALMETEROL/FLUTICASONA 50/250 MCG INHALADOR").orElseThrow();
        assertThat(combo.valores()).hasSize(2);
        Concentracion cum = AnaliticaHomologacion.concentracionCum(List.of(new PrincipioCum("FLUTICASONA PROPIONATO", new BigDecimal("250"), "mcg", null),
                new PrincipioCum("SALMETEROL XINAFOATO", new BigDecimal("50"), "mcg", null)), C.unidades()).orElseThrow();
        assertThat(AnaliticaHomologacion.mismaConcentracion(combo, cum, BigDecimal.ZERO)).isTrue();
        Concentracion ui = AnaliticaHomologacion.concentracion("INSULINA GLARGINA 100 UI/ML PLUMA").orElseThrow();
        assertThat(ui.unidad()).isEqualTo("UI");
        assertThat(ui.por()).isEqualTo("ML");
        Concentracion suelto = AnaliticaHomologacion.concentracion("ACETAMINOFEN 500 TABLETA").orElseThrow();
        assertThat(suelto.unidad()).isNull();
        assertThat(AnaliticaHomologacion.mismaConcentracion(suelto, AnaliticaHomologacion.concentracion("500 MG").orElseThrow(), BigDecimal.ZERO)).isTrue();
        assertThat(AnaliticaHomologacion.concentracion("LORATADINA TABLETA X 10")).as("la cantidad del empaque no es la concentración").isEmpty();
        assertThat(AnaliticaHomologacion.concentracion("TAMSULOSINA 0,4 MG CAPSULA").orElseThrow().valores()).containsExactly(new BigDecimal("0.4"));
        // Tolerancia en porcentaje
        Concentracion x = AnaliticaHomologacion.concentracion("100 MG").orElseThrow();
        Concentracion y = AnaliticaHomologacion.concentracion("104 MG").orElseThrow();
        assertThat(AnaliticaHomologacion.mismaConcentracion(x, y, BigDecimal.ZERO)).isFalse();
        assertThat(AnaliticaHomologacion.mismaConcentracion(x, y, new BigDecimal("5"))).isTrue();
    }

    @Test
    @DisplayName("R-015 y R-016: forma del texto (la variante más larga), equivalencias, tolerancia por familia; empaque «X30»")
    void formaYPresentacion() {
        assertThat(AnaliticaHomologacion.formaDeTexto("LOSARTAN 50 MG TABLETA RECUBIERTA", C.formas())).contains("TABLETA RECUBIERTA");
        assertThat(AnaliticaHomologacion.formaDeTexto("ADALIMUMAB 40 MG JERINGA PRELLENADA", C.formas())).contains("JERINGA PRELLENADA");
        var capsula = AnaliticaHomologacion.forma("CAPSULA", C.formas());
        var recubierta = AnaliticaHomologacion.forma("Tableta cubierta con película", C.formas());
        assertThat(recubierta.canonica()).isEqualTo("TABLETA RECUBIERTA");
        assertThat(AnaliticaHomologacion.formaCompatible(capsula, recubierta, 0)).isFalse();
        assertThat(AnaliticaHomologacion.formaCompatible(capsula, recubierta, 1)).as("misma familia: sólida oral").isTrue();
        assertThat(AnaliticaHomologacion.formaCompatible(capsula, AnaliticaHomologacion.forma("JARABE", C.formas()), 1)).isFalse();
        assertThat(AnaliticaHomologacion.formaCompatible(capsula, AnaliticaHomologacion.forma("JARABE", C.formas()), 2)).isTrue();
        assertThat(AnaliticaHomologacion.presentacion("METFORMINA 850 MG TABLETA X 30")).contains(30);
        assertThat(AnaliticaHomologacion.presentacion("METFORMINA 850 MG")).isEmpty();
        assertThat(AnaliticaHomologacion.principioDeDescripcion("INSULINA GLARGINA 100 UI/ML PLUMA", C.formas())).isEqualTo("INSULINA GLARGINA");
        assertThat(AnaliticaHomologacion.principioDeDescripcion("AMOXICILINA SUSPENSION 250 MG/5 ML", C.formas())).isEqualTo("AMOXICILINA");
    }

    @Test
    @DisplayName("R-004 y R-013: sinónimo DCI (ACETAMINOFEN ⇒ PARACETAMOL), sales toleradas y combinaciones como conjunto")
    void sinonimosYSales() {
        assertThat(AnaliticaHomologacion.dcis("ACETAMINOFEN", C)).containsExactly("PARACETAMOL");
        assertThat(AnaliticaHomologacion.dcis("LOSARTAN POTASICO", C)).containsExactly("LOSARTAN");
        assertThat(AnaliticaHomologacion.dcis("CLORHIDRATO DE METFORMINA", C)).containsExactly("METFORMINA");
        assertThat(AnaliticaHomologacion.dcis("SALMETEROL Y FLUTICASONA", C)).containsExactly("FLUTICASONA", "SALMETEROL");
        assertThat(AnaliticaHomologacion.dcis("SALMETEROL/FLUTICASONA", C)).containsExactly("FLUTICASONA", "SALMETEROL");
        Configuracion sinR004 = C.con(new Regla(AnaliticaHomologacion.R004, 70, false, Map.of()));
        assertThat(AnaliticaHomologacion.dcis("ACETAMINOFEN", sinR004)).containsExactly("ACETAMINOFEN");
        assertThat(AnaliticaHomologacion.clavesBusqueda("PARACETAMOL", C)).contains("PARACETAMOL", "ACETAMINOFEN");
        assertThat(AnaliticaHomologacion.principioBase("LOSARTAN POTASICO", Set.of())).isEqualTo("LOSARTAN");
    }

    // ---------- CUM, registro, fechas, precios, ATC y CIE-10 ----------

    @Test
    @DisplayName("R-002: «19911481-01», «19911481/1» y «19911481.1» ⇒ 19911481-1; «ABC-1» y «123456789-1» no cumplen el formato")
    void cumFormatoNormalizado() {
        assertThat(AnaliticaHomologacion.normalizarCum("19911481-01")).contains("19911481-1");
        assertThat(AnaliticaHomologacion.normalizarCum(" 19911481 / 1 ")).contains("19911481-1");
        assertThat(AnaliticaHomologacion.normalizarCum("19911481.1")).contains("19911481-1");
        assertThat(AnaliticaHomologacion.normalizarCum("ABC-1")).isEmpty();
        assertThat(AnaliticaHomologacion.normalizarCum("123456789-1")).isEmpty();
        assertThat(AnaliticaHomologacion.normalizarCum("19911481-1234")).isEmpty();
        assertThat(AnaliticaHomologacion.normalizarCum("")).isEmpty();
        assertThat(AnaliticaHomologacion.cumDe("19911481", "1.0")).contains("19911481-1");
    }

    @Test
    @DisplayName("R-003: formatos del registro sanitario del INVIMA")
    void registroSanitario() {
        assertThat(AnaliticaHomologacion.registroValido("INVIMA 2023M-0013598-R2")).isTrue();
        assertThat(AnaliticaHomologacion.registroValido("INVIMA M-012345")).isTrue();
        assertThat(AnaliticaHomologacion.registroValido("2009M-0009560-R1")).isTrue();
        assertThat(AnaliticaHomologacion.registroValido("SIN REGISTRO")).isFalse();
        assertThat(AnaliticaHomologacion.registroValido(null)).isFalse();
    }

    @Test
    @DisplayName("fechas del INVIMA MM/DD/YYYY: «08/23/2012» ⇒ 2012-08-23; un texto no convertible queda vacío")
    void fechaMesDiaAnio() {
        assertThat(AnaliticaHomologacion.fechaInvima("08/23/2012")).contains(LocalDate.of(2012, 8, 23));
        assertThat(AnaliticaHomologacion.fechaInvima("2012-08-23T00:00:00.000")).contains(LocalDate.of(2012, 8, 23));
        assertThat(AnaliticaHomologacion.fechaInvima("23/08/2012")).as("no es MM/DD").isEmpty();
        assertThat(AnaliticaHomologacion.fechaInvima("02/30/2020")).isEmpty();
        assertThat(AnaliticaHomologacion.fechaInvima("sin fecha")).isEmpty();
    }

    @Test
    @DisplayName("precio en texto: «1234,50» y «1234.5» ⇒ 1234,50; un texto no numérico se rechaza")
    void precioTexto() {
        assertThat(AnaliticaHomologacion.precio("1234,50")).contains(new BigDecimal("1234.50"));
        assertThat(AnaliticaHomologacion.precio("1234.5")).contains(new BigDecimal("1234.50"));
        assertThat(AnaliticaHomologacion.precio("$ 1.234,50")).contains(new BigDecimal("1234.50"));
        assertThat(AnaliticaHomologacion.precio("doce mil")).isEmpty();
    }

    @Test
    @DisplayName("ATC: A10BA02 ⇒ A, A10, A10B, A10BA, A10BA02; un código con otro patrón es ATC no válido; grupo = nivel 3")
    void nivelesAtc() {
        assertThat(AnaliticaHomologacion.nivelesAtc("A10BA02")).contains(List.of("A", "A10", "A10B", "A10BA", "A10BA02"));
        assertThat(AnaliticaHomologacion.nivelesAtc("A10B")).isEmpty();
        assertThat(AnaliticaHomologacion.nivelesAtc("1A0BA02")).isEmpty();
        assertThat(AnaliticaHomologacion.grupoFarmacologico("C09CA01")).isEqualTo("C09C");
    }

    @Test
    @DisplayName("R-060 CIE-10: «e11.9» y «E119» ⇒ E119; «E11» pendiente (grupo); «Z999X» o inexistente sin correspondencia")
    void cie10() {
        ReferenciaCie10 r = new ReferenciaCie10() {
            @Override
            public boolean hayReferencia() {
                return true;
            }

            @Override
            public boolean existe(String c) {
                return Set.of("E119", "I10X").contains(c);
            }

            @Override
            public boolean existeGrupo(String g) {
                return Set.of("E11", "I10").contains(g);
            }
        };
        assertThat(AnaliticaHomologacion.homologarCie10("e11.9", r).codigo()).isEqualTo("E119");
        assertThat(AnaliticaHomologacion.homologarCie10("E119", r).estado()).isEqualTo(Estado.HOMOLOGADO);
        assertThat(AnaliticaHomologacion.homologarCie10("E11", r).estado()).isEqualTo(Estado.PENDIENTE);
        assertThat(AnaliticaHomologacion.homologarCie10("Z999X", r).estado()).isEqualTo(Estado.SIN_CORRESPONDENCIA);
        assertThat(AnaliticaHomologacion.homologarCie10("E118", r).estado()).isEqualTo(Estado.SIN_CORRESPONDENCIA);
        assertThat(AnaliticaHomologacion.homologarCie10("E119", r).regla()).isEqualTo("R-060");
    }

    // ---------- CUM del INVIMA ----------

    private static Map<String, String> fila(String principio, String rol, String vence) {
        Map<String, String> f = new HashMap<>();
        f.put("expediente", "19911481");
        f.put("consecutivocum", "1");
        f.put("producto", "PRODUCTO FICTICIO");
        f.put("registrosanitario", "INVIMA 2015M-0016285");
        f.put("fechavencimiento", vence);
        f.put("estadocum", "Activo");
        f.put("atc", "c09dx01");
        f.put("principioactivo", principio);
        f.put("cantidad", "50");
        f.put("unidadmedida", "mg");
        f.put("nombrerol", rol);
        f.put("tiporol", "FABRICANTE");
        f.put("formafarmaceutica", "TABLETA");
        return f;
    }

    @Test
    @DisplayName("CUM combinado: 19911481-1 con 4 filas (2 principios × 2 roles) ⇒ 1 presentación, 2 principios y 2 roles; fechas MM/DD/YYYY")
    void cumCombinado() {
        List<Map<String, String>> filas = List.of(fila("VALSARTAN", "LAB A", "08/23/2030"), fila("SACUBITRIL", "LAB A", "08/23/2030"),
                fila("VALSARTAN", "LAB B", "08/23/2030"), fila("SACUBITRIL", "LAB B", "08/23/2030"));
        GrupoCum g = AnaliticaHomologacion.agruparCum(filas, Set.of()).orElseThrow();
        assertThat(g.presentacion().cum()).isEqualTo("19911481-1");
        assertThat(g.presentacion().fechaVencimiento()).isEqualTo(LocalDate.of(2030, 8, 23));
        assertThat(g.presentacion().atc()).isEqualTo("C09DX01");
        assertThat(g.principios()).hasSize(2);
        assertThat(g.roles()).hasSize(2);
        assertThat(g.fechasInvalidas()).isZero();
        GrupoCum mal = AnaliticaHomologacion.agruparCum(List.of(fila("VALSARTAN", "LAB A", "fecha rara")), Set.of()).orElseThrow();
        assertThat(mal.fechasInvalidas()).isEqualTo(1);
        assertThat(mal.presentacion().fechaVencimiento()).isNull();
        assertThat(AnaliticaHomologacion.faltantes(List.of("Expediente", "producto"), AnaliticaHomologacion.COLUMNAS_CUM_OBLIGATORIAS))
                .contains("consecutivocum").doesNotContain("expediente");
        assertThat(AnaliticaHomologacion.columna("principio_activo1")).isEqualTo("principioactivo1");
    }

    // ---------- Homologación ----------

    @Test
    @DisplayName("casos del mockup: R-012, R-004, R-021 sin dato final, R-015 inconsistente (origen cápsula) y R-030 pendiente")
    void casosMockup() {
        Resultado metformina = homologar(origen(1, null, "METFORMINA 850MG TAB X30"));
        assertThat(metformina.estado()).isEqualTo(Estado.HOMOLOGADO);
        assertThat(metformina.regla()).isEqualTo("R-012");
        assertThat(metformina.dato().dci()).isEqualTo("Metformina");
        assertThat(metformina.dato().concentracion()).isEqualTo("850 mg");
        assertThat(metformina.dato().forma()).isEqualTo("tableta");
        assertThat(metformina.dato().presentacion()).isEqualTo("caja x 30");
        assertThat(metformina.dato().atc()).isEqualTo("A10BA02");
        assertThat(metformina.dato().cum()).as("una sola presentación con x 30").isEqualTo("19900001-1");
        assertThat(metformina.dato().codigoSsfm()).isEqualTo("SSFM-0001");

        Resultado paracetamol = homologar(origen(2, null, "ACETAMINOFEN 500 TAB"));
        assertThat(paracetamol.estado()).isEqualTo(Estado.HOMOLOGADO);
        assertThat(paracetamol.regla()).isEqualTo("R-004");
        assertThat(paracetamol.dato().dci()).isEqualTo("Paracetamol");
        assertThat(paracetamol.dato().atc()).isEqualTo("N02BE01");
        assertThat(paracetamol.pasos()).anyMatch(p -> p.regla().equals("R-004") && p.antes().equals("ACETAMINOFEN") && p.despues().equals("PARACETAMOL"));

        Resultado adalimumab = homologar(origen(3, "19950001-1", "ADALIMUMAB 40MG JER"));
        assertThat(adalimumab.estado()).isEqualTo(Estado.NO_VALIDO);
        assertThat(adalimumab.regla()).isEqualTo("R-021");
        assertThat(adalimumab.dato()).as("sin dato final").isNull();

        Resultado losartan = homologar(origen(4, "19960001-1", "LOSARTAN 50 MG CAP"));
        assertThat(losartan.estado()).isEqualTo(Estado.INCONSISTENTE);
        assertThat(losartan.regla()).isEqualTo("R-015");
        assertThat(losartan.diferencias()).containsExactly("diferencia en forma farmacéutica (origen capsula)");

        Resultado insulina = homologar(origen(5, null, "INSULINA GLARGINA 100UI"));
        assertThat(insulina.estado()).isEqualTo(Estado.PENDIENTE);
        assertThat(insulina.regla()).isEqualTo("R-030");
        assertThat(insulina.dato()).as("dato propuesto para la revisión").isNotNull();
    }

    @Test
    @DisplayName("CUM exacto: «19911481-01» ⇒ R-002 normaliza, R-001 homologa con DCI, concentración, forma, ATC y registro del INVIMA")
    void cumExacto() {
        Presentacion p = pres("19911481-1", "vigentes", "LOSARTAN FICTICIO 50", "LOSARTAN POTASICO", "50", "mg", "TABLETA", "C09CA01", 30);
        Origen o = new Origen(9, "19911481-01", "LOSARTAN 50 MG TABLETA", "LOSARTAN", "C09CA01", null, LocalDate.of(2025, 3, 1));
        Resultado r = AnaliticaHomologacion.homologar(o, C, refs(List.of(p), null));
        assertThat(r.estado()).isEqualTo(Estado.HOMOLOGADO);
        assertThat(r.regla()).isEqualTo("R-001");
        assertThat(r.pasos()).anyMatch(x -> x.regla().equals("R-002") && x.despues().equals("19911481-1"));
        assertThat(r.dato().cum()).isEqualTo("19911481-1");
        assertThat(r.dato().dci()).isEqualTo("Losartan");
        assertThat(r.dato().principioActivo()).isEqualTo("LOSARTAN POTASICO");
        assertThat(r.dato().concentracion()).isEqualTo("50 mg");
        assertThat(r.dato().registroSanitario()).isEqualTo("INVIMA 2019M-0012345");
        assertThat(r.dato().grupoFarmacologico()).isEqualTo("C09C");
        assertThat(r.fuente().conjunto()).isEqualTo("vigentes");
    }

    @Test
    @DisplayName("CUM con formato imposible («ABC-1», «123456789-1») ⇒ no válido por R-002 «formato de CUM no válido»")
    void cumFormato() {
        for (String cum : List.of("ABC-1", "123456789-1")) {
            Resultado r = homologar(origen(10, cum, "LOSARTAN 50 MG"));
            assertThat(r.estado()).isEqualTo(Estado.NO_VALIDO);
            assertThat(r.regla()).isEqualTo("R-002");
            assertThat(r.causa()).isEqualTo("formato de CUM no válido");
        }
    }

    @Test
    @DisplayName("R-003: dispensación del 2025-03-01 con registro vencido el 2024-12-31 ⇒ no válido aunque el CUM exista")
    void registroVencido() {
        Presentacion p = new Presentacion("19911481-1", "vigentes", "X", "T", "INVIMA 2015M-0016285", LocalDate.of(2024, 12, 31), "Vigente", "Activo",
                BigDecimal.TEN, null, null, "C09CA01", null, null, "TABLETA", null, List.of(new PrincipioCum("LOSARTAN", BigDecimal.TEN, "mg", null)));
        Origen o = new Origen(11, "19911481-1", "LOSARTAN 10 MG", "LOSARTAN", "C09CA01", null, LocalDate.of(2025, 3, 1));
        Resultado r = AnaliticaHomologacion.homologar(o, C, refs(List.of(p), null));
        assertThat(r.estado()).isEqualTo(Estado.NO_VALIDO);
        assertThat(r.regla()).isEqualTo("R-003");
        assertThat(r.causa()).contains("vencido el 2024-12-31");
        // En trámite de renovación se tolera
        Presentacion renov = new Presentacion("19911481-1", "renovacion", "X", "T", "INVIMA 2015M-0016285", LocalDate.of(2024, 12, 31),
                "En tramite renov", "Activo", BigDecimal.TEN, null, null, "C09CA01", null, null, "TABLETA", null, p.principios());
        assertThat(AnaliticaHomologacion.homologar(o, C, refs(List.of(renov), null)).estado()).isEqualTo(Estado.HOMOLOGADO);
    }

    @Test
    @DisplayName("R-021: CUM solo en vencidos u otros estados, o inactivo en vigentes ⇒ CUM o registro no válido")
    void cumVencidoOInactivo() {
        Presentacion otros = pres("19800001-1", "otros", "X", "IBUPROFENO", "400", "mg", "TABLETA", "M01AE01", 20);
        Presentacion inactivo = new Presentacion("19800002-1", "vigentes", "X", "T", "INVIMA 2015M-0016285", VENCE, "Vigente", "Inactivo",
                BigDecimal.TEN, null, null, "M01AE01", null, null, "TABLETA", null, List.of(new PrincipioCum("IBUPROFENO", new BigDecimal("400"), "mg", null)));
        Referencias r = refs(List.of(otros, inactivo), null);
        assertThat(AnaliticaHomologacion.homologar(origen(12, "19800001-1", "IBUPROFENO 400 MG"), C, r).regla()).isEqualTo("R-021");
        Resultado in = AnaliticaHomologacion.homologar(origen(13, "19800002-1", "IBUPROFENO 400 MG"), C, r);
        assertThat(in.estado()).isEqualTo(Estado.NO_VALIDO);
        assertThat(in.causa()).isEqualTo("CUM inactivo en el INVIMA");
        // Si el mismo CUM está en vigentes y en otros estados, prevalece vigentes
        Presentacion vigente = pres("19800001-1", "vigentes", "X", "IBUPROFENO", "400", "mg", "TABLETA", "M01AE01", 20);
        assertThat(AnaliticaHomologacion.homologar(origen(14, "19800001-1", "IBUPROFENO 400 MG"), C, refs(List.of(otros, vigente), null)).estado())
                .isEqualTo(Estado.HOMOLOGADO);
    }

    @Test
    @DisplayName("R-012 y R-013: diferencia en concentración o en principio activo ⇒ inconsistente con la diferencia anotada")
    void diferencias() {
        Resultado c = homologar(origen(15, "19960001-1", "LOSARTAN 100 MG TABLETA RECUBIERTA"));
        assertThat(c.estado()).isEqualTo(Estado.INCONSISTENTE);
        assertThat(c.regla()).isEqualTo("R-012");
        assertThat(c.diferencias()).containsExactly("diferencia en concentración");
        Resultado p = homologar(new Origen(16, "19960001-1", "VALSARTAN 50 MG", "VALSARTAN", null, null, null));
        assertThat(p.estado()).isEqualTo(Estado.INCONSISTENTE);
        assertThat(p.regla()).isEqualTo("R-013");
        Resultado sal = homologar(new Origen(17, "19960001-1", "LOSARTAN 50 MG TABLETA RECUBIERTA", "LOSARTAN", null, null, null));
        assertThat(sal.estado()).as("«losartán» ≈ «losartán potásico»").isEqualTo(Estado.HOMOLOGADO);
    }

    @Test
    @DisplayName("R-015 con tolerancia 1 (cápsula ≈ tableta recubierta): Losartán pasa de inconsistente a homologado")
    void toleranciaForma() {
        Origen o = origen(4, "19960001-1", "LOSARTAN 50 MG CAP");
        assertThat(homologar(o).estado()).isEqualTo(Estado.INCONSISTENTE);
        Configuracion tolerante = C.con(new Regla("R-015", 100, true, Map.of("tolerancia", "1")));
        Resultado r = AnaliticaHomologacion.homologar(o, tolerante, refs(MOCKUP, SSFM));
        assertThat(r.estado()).isEqualTo(Estado.HOMOLOGADO);
        Configuracion sinR015 = C.con(new Regla("R-015", 100, false, Map.of("tolerancia", "0")));
        assertThat(AnaliticaHomologacion.homologar(o, sinR015, refs(MOCKUP, SSFM)).estado()).isEqualTo(Estado.HOMOLOGADO);
    }

    @Test
    @DisplayName("R-004 desactivada: ACETAMINOFEN ya no se encuentra (sin correspondencia)")
    void sinonimoDesactivado() {
        Configuracion sinR004 = C.con(new Regla("R-004", 70, false, Map.of()));
        Resultado r = AnaliticaHomologacion.homologar(origen(2, null, "ACETAMINOFEN 500 TAB"), sinR004, refs(MOCKUP, SSFM));
        assertThat(r.estado()).isEqualTo(Estado.SIN_CORRESPONDENCIA);
        assertThat(r.regla()).isEqualTo("R-050");
    }

    @Test
    @DisplayName("R-041: CUM válido sin principio activo ni ATC ⇒ homologado «R-041 · completado desde CUM INVIMA»")
    void incompletoCorregido() {
        Resultado r = homologar(origen(20, "19960001-1", null));
        assertThat(r.estado()).isEqualTo(Estado.HOMOLOGADO);
        assertThat(r.regla()).isEqualTo("R-041");
        assertThat(r.causa()).isEqualTo("completado desde CUM INVIMA");
        assertThat(r.dato().principioActivo()).isEqualTo("LOSARTAN POTASICO");
        assertThat(r.dato().atc()).isEqualTo("C09CA01");
        assertThat(r.pasos()).anyMatch(p -> p.regla().equals("R-041") && p.despues().contains("principio activo y ATC"));
    }

    @Test
    @DisplayName("R-050: CUM que no existe, o principio sin ningún CUM vigente ⇒ sin correspondencia; sin CUM publicado también")
    void sinCorrespondencia() {
        assertThat(homologar(origen(21, "19999999-9", "LOSARTAN 50 MG")).estado()).isEqualTo(Estado.SIN_CORRESPONDENCIA);
        assertThat(homologar(origen(22, null, "MEDICAMENTO INVENTADO 10 MG")).regla()).isEqualTo("R-050");
        assertThat(AnaliticaHomologacion.homologar(origen(23, "19960001-1", "LOSARTAN"), C, refs(List.of(), null)).causa())
                .isEqualTo("sin versión publicada del CUM del INVIMA");
    }

    @Test
    @DisplayName("R-030: sin catálogo SSFM publicado no aplica; con catálogo, un código del insumo que no está queda pendiente")
    void codigoSsfm() {
        Origen o = origen(5, null, "INSULINA GLARGINA 100UI");
        assertThat(AnaliticaHomologacion.homologar(o, C, refs(MOCKUP, null)).estado()).isEqualTo(Estado.HOMOLOGADO);
        Origen conCodigo = new Origen(6, "19960001-1", "LOSARTAN 50 MG TABLETA RECUBIERTA", null, null, "SSFM-9999", null);
        assertThat(homologar(conCodigo).estado()).isEqualTo(Estado.PENDIENTE);
        Origen bueno = new Origen(7, "19960001-1", "LOSARTAN 50 MG TABLETA RECUBIERTA", null, null, "ssfm-0003", null);
        assertThat(homologar(bueno).dato().codigoSsfm()).isEqualTo("SSFM-0003");
    }

    @Test
    @DisplayName("R-040: el mismo dato original con dos CUM distintos ⇒ ambos «duplicado»; un no válido conserva su estado")
    void duplicado() {
        List<Origen> os = List.of(origen(30, "19900002-1", "ACETAMINOFEN 500 MG TABLETA"), origen(31, "19900001-1", "ACETAMINOFEN 500 MG TABLETA"),
                origen(32, "19960001-1", "LOSARTAN 50 MG TABLETA RECUBIERTA"), origen(33, "ABC", "LOSARTAN 50 MG TABLETA RECUBIERTA"));
        List<Resultado> rs = new ArrayList<>();
        for (Origen o : os) {
            rs.add(homologar(o));
        }
        List<Resultado> d = AnaliticaHomologacion.marcarDuplicados(os, rs, C);
        assertThat(d.get(0).estado()).isEqualTo(Estado.DUPLICADO);
        assertThat(d.get(0).regla()).isEqualTo("R-040");
        assertThat(d.get(1).estado()).isEqualTo(Estado.DUPLICADO);
        assertThat(d.get(1).causa()).contains("19900001-1").contains("19900002-1");
        assertThat(d.get(2).estado()).as("un solo CUM válido").isEqualTo(Estado.HOMOLOGADO);
        assertThat(d.get(3).estado()).isEqualTo(Estado.NO_VALIDO);
        Configuracion sinR040 = C.con(new Regla("R-040", 140, false, Map.of()));
        assertThat(AnaliticaHomologacion.marcarDuplicados(os, rs, sinR040).get(0).estado()).isNotEqualTo(Estado.DUPLICADO);
    }

    @Test
    @DisplayName("llave del dato original: CUM normalizado + descripción normalizada («19911481-01 losartán» = «19911481-1 LOSARTAN»)")
    void llaveOrigen() {
        assertThat(AnaliticaHomologacion.llaveOrigen("19911481-01", "losartán 50mg")).isEqualTo(AnaliticaHomologacion.llaveOrigen("19911481-1", "LOSARTAN 50 MG"));
        assertThat(AnaliticaHomologacion.llaveOrigen(null, "X")).isNotEqualTo(AnaliticaHomologacion.llaveOrigen("1-1", "X"));
        assertThat(AnaliticaHomologacion.llaveOrigen("19911481-1", "A")).hasSize(64);
    }

    @Test
    @DisplayName("porcentajes de calidad con dos decimales que suman exactamente 100,00 (mayor residuo); sin registros, ceros")
    void porcentajesSumanCien() {
        Map<Estado, Long> c = new EnumMap<>(Estado.class);
        c.put(Estado.HOMOLOGADO, 1L);
        c.put(Estado.INCONSISTENTE, 1L);
        c.put(Estado.PENDIENTE, 1L);
        Map<Estado, BigDecimal> p = AnaliticaHomologacion.porcentajes(c);
        assertThat(p.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(p.get(Estado.HOMOLOGADO)).isEqualByComparingTo("33.34");
        Random azar = new Random(7);
        for (int i = 0; i < 200; i++) {
            Map<Estado, Long> x = new EnumMap<>(Estado.class);
            for (Estado e : Estado.values()) {
                x.put(e, (long) azar.nextInt(100_000));
            }
            assertThat(AnaliticaHomologacion.porcentajes(x).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        }
        assertThat(AnaliticaHomologacion.porcentajes(Map.of()).values()).allMatch(v -> v.signum() == 0);
    }

    @Test
    @DisplayName("determinista: mismo dato y mismas versiones dan el mismo estado, regla y dato final, en cualquier orden")
    void determinista() {
        List<Origen> os = new ArrayList<>(List.of(origen(1, null, "METFORMINA 850MG TAB X30"), origen(2, null, "ACETAMINOFEN 500 TAB"),
                origen(3, "19950001-1", "ADALIMUMAB 40MG JER"), origen(4, "19960001-1", "LOSARTAN 50 MG CAP"), origen(5, null, "INSULINA GLARGINA 100UI")));
        Map<Long, Resultado> primera = new LinkedHashMap<>();
        for (Origen o : os) {
            primera.put(o.id(), homologar(o));
        }
        Collections.shuffle(os, new Random(99));
        List<Presentacion> barajadas = new ArrayList<>(MOCKUP);
        Collections.shuffle(barajadas, new Random(5));
        for (Origen o : os) {
            Resultado r = AnaliticaHomologacion.homologar(o, C, refs(barajadas, SSFM));
            assertThat(r).isEqualTo(primera.get(o.id()));
        }
    }

    @Test
    @DisplayName("atributos del dato original: el valor más frecuente, con empate el menor lexicográfico, sin depender del orden de las filas")
    void masFrecuenteDeterminista() {
        Map<String, Long> a = new LinkedHashMap<>();
        a.put("LOSARTAN POTASICO", 2L);
        a.put("LOSARTAN", 2L);
        a.put("  ", 9L);
        Map<String, Long> b = new LinkedHashMap<>();
        b.put("LOSARTAN", 2L);
        b.put("LOSARTAN POTASICO", 2L);
        assertThat(AnaliticaHomologacion.masFrecuente(a)).isEqualTo("LOSARTAN").isEqualTo(AnaliticaHomologacion.masFrecuente(b));
        b.put("LOSARTAN POTASICO", 3L);
        assertThat(AnaliticaHomologacion.masFrecuente(b)).isEqualTo("LOSARTAN POTASICO");
        assertThat(AnaliticaHomologacion.masFrecuente(Map.of())).isNull();
    }

    @Test
    @DisplayName("capa de decisiones: aprobar deja homologado; devolver deja pendiente salvo que las reglas lo homologuen; sin decisión, las reglas")
    void estadoFinalConDecision() {
        assertThat(AnaliticaHomologacion.estadoFinal(Estado.NO_VALIDO, "aprobar")).isEqualTo(Estado.HOMOLOGADO);
        assertThat(AnaliticaHomologacion.estadoFinal(Estado.INCONSISTENTE, "devolver")).isEqualTo(Estado.PENDIENTE);
        assertThat(AnaliticaHomologacion.estadoFinal(Estado.HOMOLOGADO, "devolver")).isEqualTo(Estado.HOMOLOGADO);
        assertThat(AnaliticaHomologacion.estadoFinal(Estado.DUPLICADO, null)).isEqualTo(Estado.DUPLICADO);
    }

    @Test
    @DisplayName("R-030: el índice SSFM busca por código, por CUM y por DCI + forma + concentración igual que el recorrido del catálogo")
    void indiceSsfm() {
        List<CodigoSsfm> cat = List.of(new CodigoSsfm("SSFM-1", "19911481-1", null, null, null), new CodigoSsfm("SSFM-2", null, "Metformina",
                "850 mg", "Tableta"), new CodigoSsfm("SSFM-3", null, "METFORMINA", null, null));
        AnaliticaHomologacion.IndiceSsfm i = AnaliticaHomologacion.IndiceSsfm.de(cat, C.formas());
        assertThat(i.porCodigo(" ssfm-2 ")).contains("SSFM-2");
        assertThat(i.porCum("19911481-1")).contains("SSFM-1");
        assertThat(i.porDci("METFORMINA", "TABLETA", "850 MG")).contains("SSFM-2");
        assertThat(i.porDci("METFORMINA", "JARABE", "850 MG")).contains("SSFM-3");
        assertThat(i.porDci("LOSARTAN", null, null)).isEmpty();
    }

    /**
     * Invariante «las reglas detectan sin conocer el insumo» (RESP L3943): referencias y medicamentos inventados al azar (semilla distinta
     * en cada corrida, en el mensaje) con errores sembrados; cada error cae en su categoría.
     */
    @Test
    @DisplayName("insumo no visto: errores sembrados al azar (CUM mal escrito, inexistente, vencido, concentración y forma) caen en su estado")
    void insumoNoVisto() {
        long semilla = System.nanoTime();
        Random azar = new Random(semilla);
        String[] formas = {"TABLETA", "CAPSULA", "JARABE", "CREMA"};
        List<Presentacion> ps = new ArrayList<>();
        int n = 60;
        String[] nombres = new String[n];
        int[] mg = new int[n];
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 7 + azar.nextInt(5); k++) {
                sb.append((char) ('A' + azar.nextInt(26)));
            }
            nombres[i] = sb.toString();
            mg[i] = 5 * (1 + azar.nextInt(200));
            ps.add(pres((30000000 + i) + "-" + (1 + azar.nextInt(9)), i % 7 == 0 ? "vencidos" : "vigentes", "P" + i, nombres[i], Integer.toString(mg[i]),
                    "mg", formas[azar.nextInt(formas.length)], "A01AA0" + (i % 10), 10));
        }
        Referencias r = refs(ps, null);
        Map<String, Integer> aciertos = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Presentacion p = ps.get(i);
            int tipo = azar.nextInt(6);
            Origen o;
            Estado esperado;
            switch (tipo) {
                case 0 -> { // CUM con ceros a la izquierda y espacios
                    String[] c = p.cum().split("-");
                    o = new Origen(i, " " + c[0] + " - 0" + c[1] + " ", nombres[i] + " " + mg[i] + " MG " + p.forma(), null, null, null, null);
                    esperado = "vencidos".equals(p.conjunto()) ? Estado.NO_VALIDO : Estado.HOMOLOGADO;
                }
                case 1 -> { // CUM con letras
                    o = new Origen(i, "X" + p.cum(), nombres[i], null, null, null, null);
                    esperado = Estado.NO_VALIDO;
                }
                case 2 -> { // CUM inexistente
                    o = new Origen(i, "4" + p.cum().substring(1), nombres[i] + " " + mg[i] + " MG", null, null, null, null);
                    esperado = Estado.SIN_CORRESPONDENCIA;
                }
                case 3 -> { // concentración distinta
                    o = new Origen(i, p.cum(), nombres[i] + " " + (mg[i] + 1) + " MG", null, null, null, null);
                    esperado = "vencidos".equals(p.conjunto()) ? Estado.NO_VALIDO : Estado.INCONSISTENTE;
                }
                case 4 -> { // forma distinta de otra familia
                    String otra = "CREMA".equals(p.forma()) ? "JARABE" : "CREMA";
                    o = new Origen(i, p.cum(), nombres[i] + " " + mg[i] + " MG " + otra, null, null, null, null);
                    esperado = "vencidos".equals(p.conjunto()) ? Estado.NO_VALIDO : Estado.INCONSISTENTE;
                }
                default -> { // sin CUM, por descripción en minúsculas con tildes
                    o = new Origen(i, null, nombres[i].toLowerCase() + " " + mg[i] + "mg", null, null, null, null);
                    esperado = "vencidos".equals(p.conjunto()) ? Estado.SIN_CORRESPONDENCIA : Estado.HOMOLOGADO;
                }
            }
            Resultado res = AnaliticaHomologacion.homologar(o, C, r);
            assertThat(res.estado()).as("semilla " + semilla + ", caso " + tipo + ": " + o).isEqualTo(esperado);
            aciertos.merge(Integer.toString(tipo), 1, Integer::sum);
        }
        assertThat(aciertos.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(n);
    }

    @Test
    @DisplayName("reglas iniciales: ids únicos del mockup, órdenes crecientes y obligatorias R-001, R-002 y R-050")
    void reglasIniciales() {
        assertThat(AnaliticaHomologacion.REGLAS.stream().map(AnaliticaHomologacion.DefinicionRegla::id).distinct().count())
                .isEqualTo(AnaliticaHomologacion.REGLAS.size());
        assertThat(AnaliticaHomologacion.REGLAS.stream().map(AnaliticaHomologacion.DefinicionRegla::id))
                .contains("R-004", "R-012", "R-015", "R-021", "R-030", "R-040");
        assertThat(AnaliticaHomologacion.REGLAS.stream().filter(AnaliticaHomologacion.DefinicionRegla::obligatoria).map(AnaliticaHomologacion.DefinicionRegla::id))
                .containsExactlyInAnyOrder("R-001", "R-002", "R-050");
        assertThat(C.parametro("R-015", "tolerancia", "x")).isEqualTo("0");
    }
}
