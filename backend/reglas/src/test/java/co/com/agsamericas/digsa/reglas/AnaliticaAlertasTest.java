package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Contexto;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Dispensa;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Estado;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Incremento;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Parametros;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Severidad;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Tipo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Alertas tempranas de la analítica (docs/especificaciones/analitica-cohortes-alertas): detectores, bandas, parámetros y análisis técnico. */
class AnaliticaAlertasTest {

    private static int dia(String fecha) {
        return (int) LocalDate.parse(fecha).toEpochDay();
    }

    private static Dispensa d(String fecha, String atc, String principio, String cum) {
        return new Dispensa(dia(fecha), atc, principio, cum, "BAS02", "EJC");
    }

    @Test
    @DisplayName("incremento de consumo del mockup: unidades de L04 del Ejército de una media de 1000 a 1420 ⇒ 42,00 % sobre el umbral 30,00 % (banda baja)")
    void incrementoConsumoL04() {
        Parametros p = AnaliticaAlertas.porDefecto(Tipo.INCREMENTO_CONSUMO);
        // Milésimas de unidad: marzo a agosto estable alrededor de 1000; septiembre 1420
        long[] serie = {990_000, 1_010_000, 1_000_000, 995_000, 1_005_000, 1_000_000, 1_420_000};
        Optional<Incremento> inc = AnaliticaAlertas.incremento(serie, 6, p, 3);
        assertThat(inc).isPresent();
        assertThat(inc.get().variacionPct()).isEqualByComparingTo("42.00");
        assertThat(inc.get().referencia()).isEqualByComparingTo("1000.00");
        Severidad s = AnaliticaAlertas.severidad(inc.get().variacionPct(), p).orElseThrow();
        assertThat(s).isEqualTo(Severidad.BAJA);
        assertThat(AnaliticaAlertas.conUnidad(AnaliticaAlertas.umbral(s, p), "%")).isEqualTo("30,00 %");
        assertThat(AnaliticaAlertas.conUnidad(inc.get().variacionPct(), "%")).isEqualTo("42,00 %");
        // Sin 6 meses de base no se evalúa; con una base menor a la mínima tampoco
        assertThat(AnaliticaAlertas.incremento(serie, 5, p, 3)).isEmpty();
        assertThat(AnaliticaAlertas.incremento(new long[] {10, 10, 10, 10, 10, 10, 30}, 6, p, 3)).isEmpty();
        // Una serie ruidosa: la z robusta frena el 35 % que está dentro de la variación habitual
        assertThat(AnaliticaAlertas.incremento(new long[] {600_000, 1_400_000, 700_000, 1_300_000, 650_000, 1_350_000, 1_350_000}, 6, p, 3)).isEmpty();
    }

    @Test
    @DisplayName("bandas: 120 % ⇒ alta con umbral 100,00 %; 60 % ⇒ media; 29,99 % ⇒ sin alerta; presentación inconsistente no tiene banda alta")
    void bandas() {
        Parametros p = AnaliticaAlertas.porDefecto(Tipo.INCREMENTO_GASTO);
        assertThat(AnaliticaAlertas.severidad(new BigDecimal("120"), p)).contains(Severidad.ALTA);
        assertThat(AnaliticaAlertas.umbral(Severidad.ALTA, p)).isEqualByComparingTo("100");
        assertThat(AnaliticaAlertas.severidad(new BigDecimal("60"), p)).contains(Severidad.MEDIA);
        assertThat(AnaliticaAlertas.severidad(new BigDecimal("29.99"), p)).isEmpty();
        Parametros pi = AnaliticaAlertas.porDefecto(Tipo.PRESENTACION_INCONSISTENTE);
        assertThat(AnaliticaAlertas.severidad(new BigDecimal("1000000"), pi)).contains(Severidad.MEDIA);
        assertThat(AnaliticaAlertas.severidad(new BigDecimal("1"), pi)).contains(Severidad.BAJA);
    }

    @Test
    @DisplayName("duplicidad: apixabán + rivaroxabán (B01AF) en 20 días ⇒ duplicidad; warfarina (B01AA) + apixabán no; a 40 días no")
    void duplicidadAnticoagulantes() {
        List<Dispensa> l = List.of(d("2025-05-01", "B01AF02", "APIXABAN", "1-1"), d("2025-05-21", "B01AF01", "RIVAROXABAN", "2-1"));
        assertThat(AnaliticaAlertas.duplicidades(l, 30, 5, Set.of())).containsExactly(new int[] {0, 1});
        List<Dispensa> w = List.of(d("2025-05-01", "B01AA03", "WARFARINA", "3-1"), d("2025-05-10", "B01AF02", "APIXABAN", "1-1"));
        assertThat(AnaliticaAlertas.duplicidades(w, 30, 5, Set.of())).isEmpty();
        // Con el nivel ATC 3 (B01A) sí sería duplicidad: el nivel es un parámetro
        assertThat(AnaliticaAlertas.duplicidades(w, 30, 4, Set.of())).hasSize(1);
        List<Dispensa> lejos = List.of(d("2025-05-01", "B01AF02", "APIXABAN", "1-1"), d("2025-06-10", "B01AF01", "RIVAROXABAN", "2-1"));
        assertThat(AnaliticaAlertas.duplicidades(lejos, 30, 5, Set.of())).isEmpty();
        // El mismo principio de dos marcas no es duplicidad; una exclusión del grupo la apaga
        List<Dispensa> marca = List.of(d("2025-05-01", "B01AF02", "APIXABAN", "1-1"), d("2025-05-05", "B01AF02", "APIXABAN", "9-1"));
        assertThat(AnaliticaAlertas.duplicidades(marca, 30, 5, Set.of())).isEmpty();
        assertThat(AnaliticaAlertas.duplicidades(l, 30, 5, Set.of("B01AF"))).isEmpty();
    }

    @Test
    @DisplayName("interacción: el par de la tabla con cobertura superpuesta, en cualquier orden; fuera de la ventana no")
    void interacciones() {
        Set<String> pares = Set.of(AnaliticaAlertas.par("OMEPRAZOL", "CLOPIDOGREL"));
        assertThat(AnaliticaAlertas.par("OMEPRAZOL", "CLOPIDOGREL")).isEqualTo("CLOPIDOGREL + OMEPRAZOL");
        List<Dispensa> l = List.of(d("2025-03-01", "A02BC01", "OMEPRAZOL", "1-1"), d("2025-03-15", "B01AC04", "CLOPIDOGREL", "2-1"));
        assertThat(AnaliticaAlertas.interacciones(l, 30, pares)).hasSize(1);
        assertThat(AnaliticaAlertas.interacciones(l, 10, pares)).isEmpty();
    }

    @Test
    @DisplayName("cambio de marca en estrecho margen: CUM distinto entre dispensaciones consecutivas del mismo principio de la lista")
    void cambioDeMarca() {
        List<Dispensa> l = List.of(d("2025-07-01", "N03AF01", "CARBAMAZEPINA", "20010703-1"), d("2025-07-02", "C09CA01", "LOSARTAN", "1-1"),
                d("2025-07-20", "N03AF01", "CARBAMAZEPINA", "20019999-1"), d("2025-08-20", "N03AF01", "CARBAMAZEPINA", "20019999-1"),
                d("2025-08-21", "C09CA01", "LOSARTAN", "2-1"));
        assertThat(AnaliticaAlertas.cambiosDeMarca(l, Set.of("CARBAMAZEPINA"))).containsExactly(2);
        assertThat(AnaliticaAlertas.enLista("CARBAMAZEPINA SODICA", Set.of("CARBAMAZEPINA"))).isTrue();
        assertThat(AnaliticaAlertas.enLista("OXCARBAZEPINA", Set.of("CARBAMAZEPINA"))).isFalse();
    }

    @Test
    @DisplayName("formulación inusual: con la cantidad casi constante (MAD 0) el doble no es atípico; diez veces sí; sin base suficiente no se evalúa")
    void formulacionInusual() {
        Parametros p = AnaliticaAlertas.porDefecto(Tipo.FORMULACION_INUSUAL);
        TreeMap<Long, Long> f = new TreeMap<>(Map.of(30_000L, 80L, 60_000L, 20L));
        AnaliticaAlertas.UmbralCantidad u = AnaliticaAlertas.umbralCantidad(f, p).orElseThrow();
        assertThat(u.mediana()).isEqualTo(30_000);
        assertThat(60_000).isLessThanOrEqualTo((int) u.umbral());
        assertThat(300_000L).isGreaterThan(u.umbral());
        assertThat(AnaliticaAlertas.umbralCantidad(new TreeMap<>(Map.of(30_000L, 10L)), p)).isEmpty();
    }

    @Test
    @DisplayName("concentración: 15 de 24 fórmulas de un profesional ⇒ 62,50 % (banda baja: 50 / 70 / 90); con menos de 20 fórmulas no se evalúa")
    void concentracion() {
        Parametros p = AnaliticaAlertas.porDefecto(Tipo.CONCENTRACION);
        BigDecimal[] r = AnaliticaAlertas.concentracion(Map.of("a", 15L, "b", 5L, "c", 4L), 20).orElseThrow();
        assertThat(r[0]).isEqualByComparingTo("62.50");
        assertThat(AnaliticaAlertas.severidad(r[0], p)).contains(Severidad.BAJA);
        assertThat(AnaliticaAlertas.concentracion(Map.of("a", 15L), 20)).isEmpty();
    }

    @Test
    @DisplayName("sin profesional en el insumo, la concentración queda «no evaluado: el insumo no trae profesional»; sin fuente, cada tipo dice por qué")
    void sinProfesionalNoEvaluado() {
        AnaliticaAlertas.Fuentes nada = new AnaliticaAlertas.Fuentes(false, false, false, false, false);
        assertThat(AnaliticaAlertas.noEvaluable(Tipo.CONCENTRACION, nada)).contains("no evaluado: el insumo no trae profesional");
        assertThat(AnaliticaAlertas.noEvaluable(Tipo.INTERACCION, nada)).contains("no evaluado: sin fuente de interacciones cargada");
        assertThat(AnaliticaAlertas.noEvaluable(Tipo.DUPLICIDAD, nada)).isEmpty();
        assertThat(AnaliticaAlertas.noEvaluable(Tipo.CONCENTRACION, new AnaliticaAlertas.Fuentes(false, false, false, false, true))).isEmpty();
    }

    @Test
    @DisplayName("desabastecimiento aproximado: caída de 100 a 40 dispensaciones (60 %) ⇒ baja; sin base no se evalúa")
    void caida() {
        Parametros p = AnaliticaAlertas.porDefecto(Tipo.DESABASTECIMIENTO);
        Incremento c = AnaliticaAlertas.caida(new long[] {100, 100, 100, 40}, 3, p).orElseThrow();
        assertThat(c.variacionPct()).isEqualByComparingTo("60.00");
        assertThat(AnaliticaAlertas.severidad(c.variacionPct(), p)).contains(Severidad.BAJA);
        assertThat(AnaliticaAlertas.caida(new long[] {2, 2, 2, 0}, 3, p)).isEmpty();
    }

    @Test
    @DisplayName("parámetros: umbrales crecientes, niveles válidos, extras conocidos y plantilla obligatoria; el JSON va y vuelve igual")
    void parametros() {
        Map<String, Object> m = new LinkedHashMap<>(AnaliticaAlertas.aMapa(AnaliticaAlertas.porDefecto(Tipo.INCREMENTO_GASTO)));
        Parametros p = AnaliticaAlertas.leer(Tipo.INCREMENTO_GASTO, m);
        assertThat(AnaliticaAlertas.aMapa(p)).isEqualTo(AnaliticaAlertas.aMapa(AnaliticaAlertas.porDefecto(Tipo.INCREMENTO_GASTO)));
        m.put("baja", "20");
        assertThat(AnaliticaAlertas.leer(Tipo.INCREMENTO_GASTO, m).baja()).isEqualByComparingTo("20");
        m.put("media", "10");
        assertThatThrownBy(() -> AnaliticaAlertas.leer(Tipo.INCREMENTO_GASTO, m)).hasMessageContaining("crecientes");
        Map<String, Object> n = new LinkedHashMap<>(Map.of("niveles", List.of("planeta")));
        assertThatThrownBy(() -> AnaliticaAlertas.leer(Tipo.INCREMENTO_GASTO, n)).hasMessageContaining("Nivel no válido");
        Map<String, Object> e = new LinkedHashMap<>(Map.of("extras", Map.of("inventado", 1)));
        assertThatThrownBy(() -> AnaliticaAlertas.leer(Tipo.INCREMENTO_GASTO, e)).hasMessageContaining("desconocido");
        Map<String, Object> t = new LinkedHashMap<>(Map.of("plantilla", " "));
        assertThatThrownBy(() -> AnaliticaAlertas.leer(Tipo.INCREMENTO_GASTO, t)).hasMessageContaining("plantilla");
        Map<String, Object> sinAlta = new LinkedHashMap<>();
        sinAlta.put("alta", null);
        assertThat(AnaliticaAlertas.leer(Tipo.DUPLICIDAD, sinAlta).alta()).isNull();
    }

    @Test
    @DisplayName("validación humana: nueva → en revisión → validada o descartada; descartada se reabre; validar y descartar exigen nota")
    void estados() {
        assertThat(Estado.NUEVA.puedePasarA(Estado.EN_REVISION)).isTrue();
        assertThat(Estado.EN_REVISION.puedePasarA(Estado.VALIDADA)).isTrue();
        assertThat(Estado.DESCARTADA.puedePasarA(Estado.EN_REVISION)).isTrue();
        assertThat(Estado.VALIDADA.puedePasarA(Estado.DESCARTADA)).isFalse();
        assertThat(Estado.DESCARTADA.exigeNota()).isTrue();
        assertThat(Estado.EN_REVISION.exigeNota()).isFalse();
        assertThat(AnaliticaAlertas.NOTA_CORTA).isEqualTo("Escriba el motivo (mínimo 10 caracteres)");
    }

    @Test
    @DisplayName("análisis técnico: causa con cifras y fuente, valoración con pacientes y gasto, recomendación de la plantilla; aviso ≤ 500 sin datos personales")
    void analisisTecnico() {
        Contexto c = new Contexto(Tipo.INCREMENTO_CONSUMO, "Fuerza EJC", "ATC nivel 2", "L04", "2025-09", new BigDecimal("42.00"), new BigDecimal("1000.00"),
                new BigDecimal("30"), Severidad.BAJA, 140, 37, new BigDecimal("1234567.89"), "El aumento es por volumen.", "Lista v2");
        assertThat(AnaliticaAlertas.causa(c)).contains("42,00 %").contains("umbral de la banda baja 30,00 %").contains("por volumen")
                .contains("Fuente de referencia: Lista v2");
        assertThat(AnaliticaAlertas.mostrar(AnaliticaAlertas.valoracion(c), 0)).contains("37 pacientes").contains("$ 1234567,89");
        assertThat(AnaliticaAlertas.recomendacion("Revise {dimension} {valor} en {nivel} ({periodo})", c)).isEqualTo("Revise atc nivel 2 L04 en Fuerza EJC (2025-09)");
        String aviso = AnaliticaAlertas.detalleAviso(15, c, 0);
        assertThat(aviso).hasSizeLessThanOrEqualTo(500).contains("Alerta analítica 15").contains("37 pacientes").doesNotContainPattern("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("supresión k = 5: una alerta de 2 pacientes no publica «2» en causa, valoración ni aviso (sale «<5»); con k = 0 se ve el número")
    void supresionEnLosTextos() {
        Contexto c = new Contexto(Tipo.MARCA_ESTRECHO_MARGEN, "ESM BAS02", "Principio activo", "CARBAMAZEPINA", "2025-07", new BigDecimal("2"), null,
                new BigDecimal("1"), Severidad.BAJA, 4, 2, new BigDecimal("1000.00"), AnaliticaAlertas.marca(2) + " pacientes cambiaron de CUM.", null);
        String causa = AnaliticaAlertas.causa(c);
        String valoracion = AnaliticaAlertas.valoracion(c);
        for (String s : List.of(AnaliticaAlertas.mostrar(causa, 5), AnaliticaAlertas.mostrar(valoracion, 5), AnaliticaAlertas.detalleAviso(9, c, 5))) {
            assertThat(s).contains("<5 pacientes").doesNotContain(" 2 pacientes").doesNotContain("⟦");
        }
        assertThat(AnaliticaAlertas.mostrar(causa, 0)).contains("valor observado 2 pacientes").contains("2 pacientes cambiaron");
        assertThat(AnaliticaAlertas.mostrar(causa, 2)).contains("valor observado 2 pacientes");
        assertThat(AnaliticaAlertas.mostrar("referencia " + AnaliticaAlertas.marca(new BigDecimal("1.50")), 0)).isEqualTo("referencia 1,50");
        assertThat(AnaliticaAlertas.oculto(BigDecimal.ZERO, 5)).isFalse();
        assertThat(AnaliticaAlertas.oculto(BigDecimal.ONE, 5)).isTrue();
    }

    @Test
    @DisplayName("interacción: con tres principios el mismo día y dos pares de la tabla (A+C y B+C) salen los dos pares, no solo el primero")
    void interaccionesTodosLosPares() {
        Set<String> pares = Set.of(AnaliticaAlertas.par("AAA", "CCC"), AnaliticaAlertas.par("BBB", "CCC"));
        List<Dispensa> l = List.of(d("2025-03-01", "A01", "AAA", "1-1"), d("2025-03-01", "B01", "BBB", "2-1"), d("2025-03-01", "C01", "CCC", "3-1"));
        List<int[]> r = AnaliticaAlertas.interacciones(l, 30, pares);
        assertThat(r).hasSize(2);
        assertThat(r.stream().map(x -> AnaliticaAlertas.par(l.get(x[0]).principio(), l.get(x[1]).principio())).toList())
                .containsExactlyInAnyOrder("AAA + CCC", "BBB + CCC");
    }

    @Test
    @DisplayName("fuera de indicación: ATC cubierto por la referencia con un CIE-10 no admitido; prefijos, CIE-10 nulo, ATC no cubierto y exclusiones")
    void fueraDeIndicacion() {
        Map<String, List<String>> ind = Map.of("M04AC", List.of("M10"), "M04", List.of("M1A"));
        assertThat(AnaliticaAlertas.fueraDeIndicacion("M04AC01", "K297", ind, Set.of())).isTrue();
        assertThat(AnaliticaAlertas.fueraDeIndicacion("M04AC01", "M109", ind, Set.of())).isFalse();
        assertThat(AnaliticaAlertas.fueraDeIndicacion("M04AC01", "M1A0", ind, Set.of())).as("admitido por el prefijo más corto").isFalse();
        assertThat(AnaliticaAlertas.fueraDeIndicacion("M04AC01", null, ind, Set.of())).isFalse();
        assertThat(AnaliticaAlertas.fueraDeIndicacion(null, "K297", ind, Set.of())).isFalse();
        assertThat(AnaliticaAlertas.fueraDeIndicacion("C09CA01", "K297", ind, Set.of())).as("ATC sin referencia").isFalse();
        assertThat(AnaliticaAlertas.fueraDeIndicacion("M04AC01", "K297", ind, Set.of("M04AC01"))).isFalse();
    }

    @Test
    @DisplayName("niveles por tipo: un nivel que el detector no evalúa se rechaza con el motivo; los umbrales y extras tienen tope")
    void nivelesYTopes() {
        assertThatThrownBy(() -> AnaliticaAlertas.leer(Tipo.INCREMENTO_GASTO, Map.of("niveles", List.of("esm")))).hasMessageContaining("no se evalúa")
                .hasMessageContaining("nacional, fuerza");
        assertThatThrownBy(() -> AnaliticaAlertas.leer(Tipo.FORMULACION_INUSUAL, Map.of("niveles", List.of("fuerza")))).hasMessageContaining("no se evalúa");
        assertThatThrownBy(() -> AnaliticaAlertas.leer(Tipo.CONCENTRACION, Map.of("niveles", List.of("nacional")))).hasMessageContaining("no se evalúa");
        assertThat(AnaliticaAlertas.leer(Tipo.DUPLICIDAD, Map.of("niveles", List.of("esm", "fuerza"))).niveles()).containsExactly("fuerza", "esm");
        assertThatThrownBy(() -> AnaliticaAlertas.leer(Tipo.DUPLICIDAD, Map.of("alta", "1e20"))).hasMessageContaining("va hasta");
        assertThatThrownBy(() -> AnaliticaAlertas.leer(Tipo.INCREMENTO_GASTO, Map.of("extras", Map.of("baseMinima", "99999999999999")))).hasMessageContaining("va hasta");
        for (Tipo t : Tipo.values()) {
            assertThat(t.nivelesAdmitidos()).as(t.codigo()).containsAll(AnaliticaAlertas.porDefecto(t).niveles());
        }
    }

    @Test
    @DisplayName("ingresos atípicos: 8 ingresos frente a una media de 0,33 sí; 30 frente a 26 no (variación de azar); z = 0 apaga la condición")
    void ingresosAtipicos() {
        Parametros p = AnaliticaAlertas.porDefecto(Tipo.ALTO_COSTO);
        assertThat(p.ventana()).isEqualTo(3);
        assertThat(AnaliticaAlertas.ingresoAtipico(8, new BigDecimal("0.33"), p)).isTrue();
        assertThat(AnaliticaAlertas.ingresoAtipico(30, new BigDecimal("26"), p)).isFalse();
        assertThat(AnaliticaAlertas.ingresoAtipico(60, new BigDecimal("26"), p)).isTrue();
        Parametros sinZ = AnaliticaAlertas.leer(Tipo.ALTO_COSTO, Map.of("extras", Map.of("z", "0")));
        assertThat(AnaliticaAlertas.ingresoAtipico(30, new BigDecimal("26"), sinZ)).isTrue();
    }

    @Test
    @DisplayName("llave natural: igual para la misma alerta, distinta en otro periodo o nivel; firma de concentración de la descripción")
    void llaveYFirma() {
        String a = AnaliticaAlertas.llave(Tipo.DUPLICIDAD, "nacional", "", "atc4", "B01AF", "2025-05");
        assertThat(a).isEqualTo(AnaliticaAlertas.llave(Tipo.DUPLICIDAD, "nacional", "", "atc4", "B01AF", "2025-05")).hasSize(64);
        assertThat(a).isNotEqualTo(AnaliticaAlertas.llave(Tipo.DUPLICIDAD, "nacional", "", "atc4", "B01AF", "2025-06"));
        assertThat(a).isNotEqualTo(AnaliticaAlertas.llave(Tipo.DUPLICIDAD, "fuerza", "EJC", "atc4", "B01AF", "2025-05"));
        assertThat(AnaliticaAlertas.firmaConcentracion("CARBAMAZEPINA 200 MG TABLETA")).isEqualTo("200 MG");
        assertThat(AnaliticaAlertas.firmaConcentracion("SALMETEROL/FLUTICASONA 50/250 MCG INHALADOR")).isEqualTo("250 MCG");
        assertThat(AnaliticaAlertas.firmaConcentracion("TAMSULOSINA 0,4 MG CAPSULA")).isEqualTo("0.4 MG");
        assertThat(AnaliticaAlertas.firmaConcentracion("PAÑAL")).isEmpty();
    }
}
