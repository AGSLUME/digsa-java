package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Supresión de celdas pequeñas de cohortes y alertas (revisión A4 2026-10-24; A5 14.6 «nunca individualizada», Ley 1581 art. 5). Con k &gt; 0,
 * una celda cuyo conteo de pacientes está entre 1 y k − 1 oculta sus pacientes Y todo lo que la describe (gasto, unidades, dispensaciones,
 * registros, ingresos, salidas): con un paciente, el gasto de la celda es el de esa persona. Ingresos y salidas se ocultan también por su
 * propio valor. En una partición o un desglose cuyos grupos suman un total visible, si queda un solo grupo oculto se oculta también el
 * siguiente más pequeño (supresión complementaria: no se recupera por diferencia).
 *
 * <p>k: el general de los tableros ({@code digsa.analitica.tableros.supresion-minima}) o, para las cohortes sensibles (VIH, huérfanas) en
 * celdas de nivel ESM, el mayor entre ese y {@code digsa.analitica.riesgo.supresion-sensibles}.
 */
final class SupresionRiesgo {

    private SupresionRiesgo() {
    }

    /** ¿Se oculta un conteo de pacientes? (0 &lt; n &lt; k). */
    static boolean oculta(Long n, int k) {
        return k > 0 && n != null && n > 0 && n < k;
    }

    static String texto(int k) {
        return "<" + k;
    }

    /** Pone pacientes y su texto en la fila (oculto: nulo y «<k»). Devuelve si quedó oculta. */
    static boolean pacientes(Map<String, Object> fila, Long n, int k) {
        boolean o = oculta(n, k);
        fila.put("pacientes", o ? null : n);
        fila.put("pacientesTexto", o ? texto(k) : n == null ? "—" : AnaliticaConsulta.entero(n));
        return o;
    }

    /** Oculta en la fila las cifras que describen la celda (gasto, unidades, dispensaciones, registros, ingresos, salidas). */
    static void ocultarCifras(Map<String, Object> fila, int k) {
        for (String c : List.of("gasto", "unidades", "dispensaciones", "registros", "ingresos", "salidas", "participacion")) {
            if (fila.containsKey(c)) {
                fila.put(c, fila.get(c) instanceof String ? texto(k) : null);
            }
        }
        for (String c : List.of("gastoTexto", "unidadesTexto", "dispensacionesTexto", "registrosTexto")) {
            if (fila.containsKey(c)) {
                fila.put(c, texto(k));
            }
        }
    }

    /**
     * Supresión complementaria sobre filas con «pacientes» (Long, ya puesto o nulo si se ocultó) y un valor de orden: si exactamente una fila
     * quedó oculta, oculta también la visible con menos pacientes. Las filas ocultas pierden también sus cifras.
     *
     * @param conteos los pacientes reales de cada fila (mismo orden que las filas)
     */
    static void complementaria(List<Map<String, Object>> filas, List<Long> conteos, int k) {
        if (k <= 0) {
            return;
        }
        List<Integer> ocultas = new ArrayList<>();
        for (int i = 0; i < filas.size(); i++) {
            if (oculta(conteos.get(i), k)) {
                ocultas.add(i);
            }
        }
        if (ocultas.size() == 1) {
            filas.stream().filter(f -> f.get("pacientes") instanceof Long x && x > 0).min(Comparator.comparing(f -> (Long) f.get("pacientes")))
                    .ifPresent(f -> {
                        f.put("pacientes", null);
                        f.put("pacientesTexto", texto(k));
                        ocultarCifras(f, k);
                        f.put("nota", "Oculta para que la celda pequeña no se deduzca por diferencia");
                    });
        }
    }

    /** Moneda o «<k». */
    static String moneda(BigDecimal v, boolean oculto, int k) {
        return oculto ? texto(k) : AnaliticaConsulta.moneda(v);
    }
}
