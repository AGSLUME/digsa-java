package co.com.agsamericas.digsa.reglas;

import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Codigos;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Criterio;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Cronicos;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.GastoMovil;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Grupo;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Particion;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.PrincipiosConsecutivos;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.PrincipiosMes;
import java.util.SortedSet;

/**
 * Recálculo incremental del motor de cohortes y alertas (A7.4, duda AN.23; docs/especificaciones/analitica-cohortes-alertas «Recálculo
 * incremental»). Reglas puras: cuántos meses mira hacia atrás cada criterio y qué meses hay que recalcular y leer cuando cambian los hechos
 * de algunos meses, para que el resultado sea IDÉNTICO al del recorrido completo.
 *
 * <p>Mapa de dependencias por ventanas (el motor solo evalúa los meses en que el paciente dispensó):
 * <ul>
 * <li>Membresía de un paciente en el mes m: sus dispensaciones de [m − L, m], con L = {@link #mesesAtras(Criterio)} (códigos, crónicos y gasto
 * móvil: ventana − 1; principios consecutivos: meses − 1; principios del mes y GCF: 0). El umbral del gasto móvil por percentil del mes m
 * depende de los hechos de TODOS los pacientes en [m − meses + 1, m]: está dentro de L.</li>
 * <li>Ingreso en m: membresía en [m − gracia − 1, m] (y el primer mes del corte, que fija el calentamiento).</li>
 * <li>Salida en s: membresía en [s − 1, s + gracia] y, al final de los datos, el último mes del corte (censura por la derecha: la salida de un
 * episodio que termina en u solo se cuenta si u + gracia + 1 ≤ último mes).</li>
 * <li>Duplicidad e interacción en el mes m: las dispensaciones de los «días» previos (⌈días / 28⌉ meses atrás). Fuera de indicación: la
 * propia dispensación. Cambio de marca en estrecho margen: la dispensación ANTERIOR del mismo principio, sin límite hacia atrás (recorrido
 * propio de toda la historia, solo de los medicamentos de estrecho margen).</li>
 * <li>Conteos del periodo por defecto: pacientes distintos de varios meses (se recalculan de la membresía final, no se copian).</li>
 * </ul>
 */
public final class AnaliticaIncremental {

    private AnaliticaIncremental() {
    }

    /** Versión del algoritmo incremental: forma parte de la firma de la versión anterior (cambiarla obliga al recorrido completo). */
    public static final String VERSION_MOTOR = "A7.4-1";

    /** Meses hacia atrás (además del propio) que mira el criterio para decidir la membresía o el grupo de un mes. */
    public static int mesesAtras(Criterio c) {
        return switch (c) {
            case Grupo g -> g.criterios().stream().mapToInt(AnaliticaIncremental::mesesAtras).max().orElse(0);
            case Codigos k -> Math.max(0, k.ventanaMeses() - 1);
            case PrincipiosMes p -> 0;
            case PrincipiosConsecutivos p -> Math.max(0, p.meses() - 1);
            case GastoMovil g -> Math.max(0, g.meses() - 1);
            case Cronicos k -> Math.max(0, k.ventanaMeses() - 1);
            case Particion p -> p.gcf() ? 0 : Math.max(Math.max(0, p.ventanaMeses() - 1), p.altoCosto() == null ? 0 : Math.max(0, p.altoCosto().meses() - 1));
        };
    }

    /** Meses calendario hacia atrás que cubre una ventana de {@code dias} días (un mes tiene al menos 28 días). */
    public static int mesesDeDias(int dias) {
        return dias <= 0 ? 0 : (dias + 27) / 28;
    }

    /**
     * Plan del recálculo: meses (ordinales, ambos inclusive) cuyos resultados se recalculan y meses de hechos que hay que leer para ello. Los
     * resultados de los demás meses se copian de la versión anterior del corte. {@code desde > hasta}: nada que recalcular.
     */
    public record Plan(int desde, int hasta, int leerDesde, int leerHasta) {

        public boolean vacio() {
            return desde > hasta;
        }

        public boolean contiene(int mes) {
            return mes >= desde && mes <= hasta;
        }

        /** «2025-11 a 2026-01», «2025-11» o «ninguno» (lo que se registra en la ejecución). */
        public String texto() {
            if (vacio()) {
                return "ninguno";
            }
            String a = AnaliticaCohortes.deOrdinal(desde).toString();
            return desde == hasta ? a : a + " a " + AnaliticaCohortes.deOrdinal(hasta);
        }

        public String textoLectura() {
            return vacio() ? "ninguno" : AnaliticaCohortes.deOrdinal(leerDesde) + " a " + AnaliticaCohortes.deOrdinal(leerHasta);
        }
    }

    /**
     * Meses a recalcular y a leer.
     *
     * @param cambiados       meses con hechos agregados o retirados (cargues nuevos y cargues reemplazados)
     * @param atras           el mayor {@link #mesesAtras(Criterio)} de las versiones evaluadas
     * @param gracia          meses de gracia de la membresía
     * @param mesesSecuencia  {@link #mesesDeDias(int)} del mayor «días» de duplicidad e interacción (0 si no se evalúan)
     * @param ultimoAnterior  último mes del corte anterior
     * @param ultimoNuevo     último mes del corte nuevo
     */
    public static Plan plan(SortedSet<Integer> cambiados, int atras, int gracia, int mesesSecuencia, int ultimoAnterior, int ultimoNuevo) {
        if (atras < 0 || gracia < 0 || mesesSecuencia < 0) {
            throw new IllegalArgumentException("Ventanas negativas");
        }
        int desde = Integer.MAX_VALUE;
        int hasta = Integer.MIN_VALUE;
        if (!cambiados.isEmpty()) {
            int a = cambiados.first();
            int b = cambiados.last();
            // salidas: un cambio en a altera la membresía desde a; la salida de s mira [s − 1, s + gracia] ⇒ desde a − gracia
            desde = a - gracia;
            // membresía hasta b + atras; ingresos hasta b + atras + gracia + 1; salidas hasta b + atras + 1; secuencia hasta b + mesesSecuencia
            hasta = Math.max(b + atras + gracia + 1, b + mesesSecuencia);
        }
        if (ultimoAnterior != ultimoNuevo) {
            // Censura por la derecha: la salida de s se cuenta si s + gracia ≤ último mes
            desde = Math.min(desde, Math.min(ultimoAnterior, ultimoNuevo) - gracia + 1);
            hasta = Math.max(hasta, Math.max(ultimoAnterior, ultimoNuevo) - gracia);
        }
        if (desde == Integer.MAX_VALUE) {
            return new Plan(1, 0, 1, 0);
        }
        // Después del último mes de datos no hay membresía, ingresos ni salidas (la salida más tardía es el último mes)
        hasta = Math.min(hasta, Math.max(ultimoAnterior, ultimoNuevo));
        if (desde > hasta) {
            return new Plan(1, 0, 1, 0);
        }
        int leerDesde = desde - Math.max(gracia + 1 + atras, mesesSecuencia);
        return new Plan(desde, hasta, leerDesde, hasta + gracia);
    }
}
