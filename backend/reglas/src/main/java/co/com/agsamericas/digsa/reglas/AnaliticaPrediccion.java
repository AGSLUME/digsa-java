package co.com.agsamericas.digsa.reglas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Proyección mensual de la analítica farmacéutica (docs/especificaciones/analitica-cohortes-alertas, requisitos «Proyección mensual a 6
 * meses, también con poca historia» y «Métodos en Java puro, selección por backtesting e IC 95 %»; A4 §11.1 f, A5 tablero 12). Solo JDK,
 * sin azar ni dependencias: misma serie + misma {@link #VERSION} = mismo resultado (invariante «Resultados reproducibles por corte y
 * versión»). Sin Spring ni JDBC.
 *
 * <p>Decisiones del algoritmo (versión {@value #VERSION}):
 * <ul>
 * <li><b>Meses de entrenamiento</b>: desde el primer mes con dato hasta el último mes completo al corte (el mes del corte entra solo si el
 * corte es su último día; si no, se devuelve aparte como observado parcial). Lo posterior al corte se ignora.</li>
 * <li><b>Huecos</b>: un mes sin dato entre el primero y el último de la serie vale 0 (en la analítica un mes sin dispensaciones es gasto,
 * dispensaciones y pacientes 0, no un dato perdido); se deja nota con cuántos se rellenaron.</li>
 * <li><b>Historia</b>: menos de {@value #HISTORIA_MINIMA} meses completos ⇒ {@code historia_insuficiente}; de 6 a 23 sin estacionalidad;
 * desde {@value #HISTORIA_ESTACIONAL} se evalúa además Holt-Winters aditivo (s = 12).</li>
 * <li><b>Rejilla fija</b>: α, β y γ recorren {@link #REJILLA} (0,1 a 0,9 de a 0,1), en ese orden; se elige la combinación con menor suma de
 * cuadrados del error a un paso y, ante igualdad exacta, la primera de la rejilla.</li>
 * <li><b>Backtesting</b>: entrena sin los últimos 3 meses (1 si hay de 6 a 8), proyecta esos meses y compara. WMAPE = Σ|obs − proy| /
 * Σ|obs| · 100 (si Σ|obs| = 0: 0 cuando no hay error, si no queda sin valor y la selección usa Σ|obs − proy|); MAPE = promedio de
 * |obs − proy| / |obs| · 100 solo en meses con obs ≠ 0 (sin valor si no hay ninguno).</li>
 * <li><b>Selección</b>: el más simple (regresión &lt; Holt &lt; Holt-Winters) cuyo WMAPE no supere el mínimo en más de
 * {@value #TOLERANCIA_EMPATE} puntos porcentuales; se reentrena con toda la serie.</li>
 * <li><b>IC 95 %</b>: proyección ± {@value #Z95} · σ · √h, con σ = raíz del promedio de los cuadrados de los residuos a un paso del modelo
 * reentrenado (regresión: residuos del ajuste); proyección y límites acotados en 0.</li>
 * <li><b>Huella</b>: SHA-256 (hex) de la serie de entrenamiento ya rellenada, una línea {@code aaaa-mm;valor} por mes separadas por
 * {@code \n}, con el valor en {@link BigDecimal#stripTrailingZeros()} y {@link BigDecimal#toPlainString()} (0 se escribe «0»).</li>
 * </ul>
 */
public final class AnaliticaPrediccion {

    private AnaliticaPrediccion() {
    }

    /** Versión del algoritmo: cambia si cambia cualquier decisión de esta clase (rejilla, inicialización, selección o IC). */
    public static final String VERSION = "prediccion-1";
    /** Horizonte por defecto y mínimo (A4 §11.1 f: «horizonte mínimo de proyección de seis (6) meses»). */
    public static final int HORIZONTE_DEFECTO = 6;
    /** Tope del horizonte: más allá de dos años la banda deja de ser útil. */
    public static final int HORIZONTE_MAXIMO = 24;
    /** Meses completos mínimos para proyectar. */
    public static final int HISTORIA_MINIMA = 6;
    /** Meses mínimos para evaluar la estacionalidad (dos ciclos de 12). */
    public static final int HISTORIA_ESTACIONAL = 24;
    /** Por debajo de esta historia el backtesting usa 1 mes en lugar de 3. */
    public static final int HISTORIA_BACKTEST_COMPLETO = 9;
    public static final int BACKTEST = 3;
    public static final int BACKTEST_CORTO = 1;
    public static final int PERIODO = 12;
    /** Diferencia de WMAPE (puntos porcentuales) que se considera empate. */
    public static final double TOLERANCIA_EMPATE = 0.05;
    public static final double Z95 = 1.96;
    /** Rejilla fija de α, β y γ. */
    public static final List<Double> REJILLA = List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9);

    public static final String OK = "ok";
    public static final String HISTORIA_INSUFICIENTE = "historia_insuficiente";
    public static final String NOTA_SIN_ESTACIONAL = "sin componente estacional: menos de 24 meses";
    public static final String NOTA_BACKTEST_CORTO = "backtesting de 1 mes: menos de 9 meses de historia";

    /** Métodos, del más simple al más complejo (el orden decide los empates). */
    public enum Metodo {
        REGRESION("regresion_lineal", "Regresión lineal"),
        HOLT("holt", "Holt con tendencia"),
        HOLT_WINTERS("holt_winters", "Holt-Winters aditivo (estacionalidad 12)");

        private final String codigo;
        private final String nombre;

        Metodo(String codigo, String nombre) {
            this.codigo = codigo;
            this.nombre = nombre;
        }

        public String codigo() {
            return codigo;
        }

        public String nombre() {
            return nombre;
        }

        public static Optional<Metodo> deCodigo(String c) {
            for (Metodo m : values()) {
                if (m.codigo.equals(c)) {
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }
    }

    /** Método evaluado en el backtesting con los parámetros ajustados sin los meses de prueba y su error (null si no es calculable). */
    public record Evaluado(Metodo metodo, Map<String, BigDecimal> parametros, BigDecimal wmape, BigDecimal mape) {
    }

    /** Mes proyectado: h meses después del último mes de entrenamiento, valor e IC 95 %. */
    public record MesProyectado(YearMonth mes, int h, BigDecimal valor, BigDecimal inferior, BigDecimal superior) {
    }

    /** Valor observado de un mes (el parcial del corte). */
    public record Observado(YearMonth mes, BigDecimal valor) {
    }

    /**
     * Resultado del entrenamiento. Con {@code historia_insuficiente} solo traen valor estado, mensaje, historia, parcial, versión y
     * huella; método, errores y σ quedan en null y las listas vacías.
     *
     * @param desde primer mes de entrenamiento (null sin historia)
     * @param hasta último mes completo de entrenamiento (null sin historia)
     * @param parcial mes del corte incompleto con su valor observado hasta el corte (null si el corte cierra el mes)
     * @param sha256 huella de la serie de entrenamiento (ver la representación canónica en la clase)
     */
    public record Resultado(String estado, String mensaje, Metodo metodo, Map<String, BigDecimal> parametros, List<Evaluado> evaluados,
                            YearMonth desde, YearMonth hasta, int mesesHistoria, int mesesBacktest, BigDecimal mape, BigDecimal wmape,
                            BigDecimal sigma, List<String> notas, List<MesProyectado> proyeccion, Observado parcial, String version,
                            String sha256) {

        public boolean ok() {
            return OK.equals(estado);
        }
    }

    // ------------------------------------------------------------------ entrada

    /** Proyecta con el horizonte por defecto (6). */
    public static Resultado proyectar(Map<YearMonth, BigDecimal> serie, LocalDate corte) {
        return proyectar(serie, corte, HORIZONTE_DEFECTO);
    }

    /**
     * Entrena y proyecta la serie mensual al corte.
     *
     * @param serie valor por mes (sin nulos); los meses posteriores al corte se ignoran
     * @param corte fecha de corte: si no es el último día de su mes, ese mes es parcial y no entra al entrenamiento
     * @param horizonte meses a proyectar, entre {@value #HORIZONTE_DEFECTO} y {@value #HORIZONTE_MAXIMO}
     */
    public static Resultado proyectar(Map<YearMonth, BigDecimal> serie, LocalDate corte, int horizonte) {
        if (serie == null || corte == null) {
            throw new IllegalArgumentException("La serie y el corte son obligatorios");
        }
        if (horizonte < HORIZONTE_DEFECTO || horizonte > HORIZONTE_MAXIMO) {
            throw new IllegalArgumentException("El horizonte debe estar entre " + HORIZONTE_DEFECTO + " y " + HORIZONTE_MAXIMO + " meses");
        }
        TreeMap<YearMonth, BigDecimal> ordenada = new TreeMap<>();
        for (Map.Entry<YearMonth, BigDecimal> e : serie.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalArgumentException("La serie no admite meses ni valores vacíos");
            }
            ordenada.put(e.getKey(), e.getValue());
        }
        YearMonth mesCorte = YearMonth.from(corte);
        boolean completo = corte.equals(mesCorte.atEndOfMonth());
        YearMonth hasta = completo ? mesCorte : mesCorte.minusMonths(1);
        Observado parcial = completo ? null : new Observado(mesCorte, dos(ordenada.getOrDefault(mesCorte, BigDecimal.ZERO)));

        // Serie de entrenamiento con los huecos en 0.
        TreeMap<YearMonth, BigDecimal> previa = new TreeMap<>(ordenada.headMap(hasta, true));
        List<YearMonth> meses = new ArrayList<>();
        List<BigDecimal> valores = new ArrayList<>();
        int huecos = 0;
        if (!previa.isEmpty()) {
            for (YearMonth m = previa.firstKey(); !m.isAfter(hasta); m = m.plusMonths(1)) {
                BigDecimal v = previa.get(m);
                if (v == null) {
                    huecos++;
                    v = BigDecimal.ZERO;
                }
                meses.add(m);
                valores.add(v);
            }
        }
        String sha = huella(meses, valores);
        int n = meses.size();
        YearMonth desde = n == 0 ? null : meses.get(0);
        YearMonth finHistoria = n == 0 ? null : hasta;
        if (n < HISTORIA_MINIMA) {
            return new Resultado(HISTORIA_INSUFICIENTE, historiaInsuficiente(n), null, Map.of(), List.of(), desde, finHistoria, n, 0, null,
                    null, null, List.of(), List.of(), parcial, VERSION, sha);
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = valores.get(i).doubleValue();
        }

        List<String> notas = new ArrayList<>();
        int k = n < HISTORIA_BACKTEST_COMPLETO ? BACKTEST_CORTO : BACKTEST;
        if (k == BACKTEST_CORTO) {
            notas.add(NOTA_BACKTEST_CORTO);
        }
        if (n < HISTORIA_ESTACIONAL) {
            notas.add(NOTA_SIN_ESTACIONAL);
        }
        if (huecos > 0) {
            notas.add(huecos + (huecos == 1 ? " mes sin datos se tomó" : " meses sin datos se tomaron") + " como 0");
        }

        // Backtesting.
        List<Metodo> candidatos = new ArrayList<>(List.of(Metodo.REGRESION, Metodo.HOLT));
        if (n >= HISTORIA_ESTACIONAL) {
            candidatos.add(Metodo.HOLT_WINTERS);
        }
        double[] entrenamiento = java.util.Arrays.copyOf(y, n - k);
        double[] prueba = java.util.Arrays.copyOfRange(y, n - k, n);
        List<Evaluado> evaluados = new ArrayList<>();
        Map<Metodo, Double> comparables = new EnumMap<>(Metodo.class);
        Map<Metodo, Double[]> errores = new EnumMap<>(Metodo.class);
        for (Metodo m : candidatos) {
            Modelo mod = ajustar(m, entrenamiento);
            double[] proy = new double[k];
            for (int h = 1; h <= k; h++) {
                proy[h - 1] = mod.pronostico(h);
            }
            Double w = wmape(prueba, proy);
            Double mp = mape(prueba, proy);
            errores.put(m, new Double[] {w, mp});
            comparables.put(m, w != null ? w : sumaAbs(prueba, proy));
            evaluados.add(new Evaluado(m, mod.parametros(), dosONulo(w), dosONulo(mp)));
        }
        Metodo elegido = elegir(comparables);

        // Reentrenamiento con toda la serie e IC.
        Modelo fin = ajustar(elegido, y);
        double sigma = fin.sigma();
        List<MesProyectado> proyeccion = new ArrayList<>();
        for (int h = 1; h <= horizonte; h++) {
            double p = fin.pronostico(h);
            double ancho = Z95 * sigma * Math.sqrt(h);
            proyeccion.add(new MesProyectado(hasta.plusMonths(h), h, dos(Math.max(0, p)), dos(Math.max(0, p - ancho)), dos(Math.max(0, p + ancho))));
        }
        Double[] err = errores.get(elegido);
        return new Resultado(OK, null, elegido, fin.parametros(), List.copyOf(evaluados), desde, hasta, n, k, dosONulo(err[1]), dosONulo(err[0]),
                dos(sigma), List.copyOf(notas), List.copyOf(proyeccion), parcial, VERSION, sha);
    }

    /** «Historia insuficiente: n meses (mínimo 6)». */
    public static String historiaInsuficiente(int n) {
        return "Historia insuficiente: " + n + (n == 1 ? " mes" : " meses") + " (mínimo " + HISTORIA_MINIMA + ")";
    }

    /**
     * Selección: el método más simple cuyo error no supera el mínimo en más de {@link #TOLERANCIA_EMPATE}. Visible en el paquete para
     * probar el empate.
     */
    static Metodo elegir(Map<Metodo, Double> errores) {
        double minimo = Double.POSITIVE_INFINITY;
        for (double e : errores.values()) {
            minimo = Math.min(minimo, e);
        }
        for (Metodo m : Metodo.values()) {
            Double e = errores.get(m);
            if (e != null && e <= minimo + TOLERANCIA_EMPATE) {
                return m;
            }
        }
        throw new IllegalArgumentException("No hay métodos evaluados");
    }

    // ------------------------------------------------------------------ escenarios y error real

    /** Escenario ajustado: base × (1 + p/100), con dos decimales y acotado en 0; p entre −100 y 1000. */
    public static BigDecimal ajustar(BigDecimal base, BigDecimal porcentaje) {
        BigDecimal factor = factor(porcentaje);
        return dos(base.multiply(factor).max(BigDecimal.ZERO));
    }

    /** Escenario ajustado de toda la proyección: valor y límites del IC multiplicados por (1 + p/100). */
    public static List<MesProyectado> ajustar(List<MesProyectado> base, BigDecimal porcentaje) {
        factor(porcentaje);
        List<MesProyectado> r = new ArrayList<>(base.size());
        for (MesProyectado m : base) {
            r.add(new MesProyectado(m.mes(), m.h(), ajustar(m.valor(), porcentaje), ajustar(m.inferior(), porcentaje),
                    ajustar(m.superior(), porcentaje)));
        }
        return Collections.unmodifiableList(r);
    }

    private static BigDecimal factor(BigDecimal p) {
        if (p == null || p.compareTo(BigDecimal.valueOf(-100)) < 0 || p.compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw new IllegalArgumentException("El ajuste debe estar entre -100 % y 1000 %");
        }
        return BigDecimal.ONE.add(p.movePointLeft(2));
    }

    /** Error real del mes ya observado: |obs − proy| / |obs| · 100 con dos decimales; vacío si el observado es 0. */
    public static Optional<BigDecimal> errorReal(BigDecimal proyectado, BigDecimal observado) {
        if (observado.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(observado.subtract(proyectado).abs().multiply(BigDecimal.valueOf(100)).divide(observado.abs(), 2, RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------ errores

    static Double wmape(double[] obs, double[] proy) {
        double num = 0;
        double den = 0;
        for (int i = 0; i < obs.length; i++) {
            num += Math.abs(obs[i] - proy[i]);
            den += Math.abs(obs[i]);
        }
        if (den == 0) {
            return num == 0 ? 0.0 : null;
        }
        return num / den * 100;
    }

    static Double mape(double[] obs, double[] proy) {
        double suma = 0;
        int c = 0;
        for (int i = 0; i < obs.length; i++) {
            if (obs[i] != 0) {
                suma += Math.abs(obs[i] - proy[i]) / Math.abs(obs[i]);
                c++;
            }
        }
        return c == 0 ? null : suma / c * 100;
    }

    private static double sumaAbs(double[] obs, double[] proy) {
        double s = 0;
        for (int i = 0; i < obs.length; i++) {
            s += Math.abs(obs[i] - proy[i]);
        }
        return s;
    }

    // ------------------------------------------------------------------ modelos

    /** Modelo ajustado: pronóstico h meses después del último dato, residuos a un paso y parámetros. */
    private interface Modelo {
        double pronostico(int h);

        double sigma();

        Map<String, BigDecimal> parametros();
    }

    private static Modelo ajustar(Metodo m, double[] y) {
        return switch (m) {
            case REGRESION -> regresion(y);
            case HOLT -> holtRejilla(y);
            case HOLT_WINTERS -> holtWintersRejilla(y);
        };
    }

    private static double rms(double sse, int cuantos) {
        return cuantos == 0 ? 0 : Math.sqrt(sse / cuantos);
    }

    /** Regresión lineal por mínimos cuadrados sobre t = 0..n−1; σ con los residuos del ajuste. */
    private static Modelo regresion(double[] y) {
        int n = y.length;
        double tm = (n - 1) / 2.0;
        double ym = 0;
        for (double v : y) {
            ym += v;
        }
        ym /= n;
        double sxy = 0;
        double sxx = 0;
        for (int t = 0; t < n; t++) {
            sxy += (t - tm) * (y[t] - ym);
            sxx += (t - tm) * (t - tm);
        }
        double b = sxx == 0 ? 0 : sxy / sxx;
        double a = ym - b * tm;
        double sse = 0;
        for (int t = 0; t < n; t++) {
            double e = y[t] - (a + b * t);
            sse += e * e;
        }
        double sigma = rms(sse, n);
        Map<String, BigDecimal> p = new LinkedHashMap<>();
        p.put("intercepto", dos(a));
        p.put("pendiente", dos(b));
        Map<String, BigDecimal> par = Collections.unmodifiableMap(p);
        return new Modelo() {
            public double pronostico(int h) {
                return a + b * (n - 1 + h);
            }

            public double sigma() {
                return sigma;
            }

            public Map<String, BigDecimal> parametros() {
                return par;
            }
        };
    }

    /** Estado final de un suavizamiento con su SSE y número de residuos. */
    private record Suavizado(double nivel, double tendencia, double[] estacional, int ultimo, double sse, int residuos) {
    }

    /**
     * Holt con tendencia: nivel inicial y₀, tendencia inicial y₁ − y₀; los residuos a un paso cuentan desde t = 2 (el de t = 1 es 0 por
     * construcción).
     */
    private static Suavizado holt(double[] y, double a, double b) {
        double l = y[0];
        double tr = y.length > 1 ? y[1] - y[0] : 0;
        double sse = 0;
        int c = 0;
        for (int t = 1; t < y.length; t++) {
            double f = l + tr;
            if (t >= 2) {
                double e = y[t] - f;
                sse += e * e;
                c++;
            }
            double l2 = a * y[t] + (1 - a) * (l + tr);
            tr = b * (l2 - l) + (1 - b) * tr;
            l = l2;
        }
        return new Suavizado(l, tr, null, y.length - 1, sse, c);
    }

    private static Modelo holtRejilla(double[] y) {
        Suavizado mejor = null;
        double ma = 0;
        double mb = 0;
        for (double a : REJILLA) {
            for (double b : REJILLA) {
                Suavizado s = holt(y, a, b);
                if (mejor == null || s.sse() < mejor.sse()) {
                    mejor = s;
                    ma = a;
                    mb = b;
                }
            }
        }
        Map<String, BigDecimal> p = new LinkedHashMap<>();
        p.put("alfa", dos(ma));
        p.put("beta", dos(mb));
        return suavizado(mejor, Collections.unmodifiableMap(p));
    }

    /**
     * Holt-Winters aditivo, s = 12. Inicialización con los primeros 12 meses: tendencia = promedio de (y_j − y_{j−12}) / 12 para j = 12 ..
     * min(23, n−1) (con 24 o más meses es la diferencia de promedios de los dos primeros años / 12); nivel al mes 11 = promedio del primer
     * año + 5,5 · tendencia; estacional_i = y_i − (promedio + (i − 5,5) · tendencia). Recursión y residuos a un paso desde t = 12. Exige
     * al menos 13 meses (el backtesting entrena con n − 3 ≥ 21).
     */
    private static Suavizado holtWinters(double[] y, double a, double b, double g) {
        double media = 0;
        for (int i = 0; i < PERIODO; i++) {
            media += y[i];
        }
        media /= PERIODO;
        double tr = 0;
        int c = 0;
        for (int j = PERIODO; j < Math.min(2 * PERIODO, y.length); j++) {
            tr += (y[j] - y[j - PERIODO]) / PERIODO;
            c++;
        }
        tr /= c;
        double[] s = new double[PERIODO];
        for (int i = 0; i < PERIODO; i++) {
            s[i] = y[i] - (media + (i - 5.5) * tr);
        }
        double l = media + 5.5 * tr;
        double sse = 0;
        int r = 0;
        for (int t = PERIODO; t < y.length; t++) {
            int i = t % PERIODO;
            double e = y[t] - (l + tr + s[i]);
            sse += e * e;
            r++;
            double l2 = a * (y[t] - s[i]) + (1 - a) * (l + tr);
            tr = b * (l2 - l) + (1 - b) * tr;
            s[i] = g * (y[t] - l2) + (1 - g) * s[i];
            l = l2;
        }
        return new Suavizado(l, tr, s, y.length - 1, sse, r);
    }

    private static Modelo holtWintersRejilla(double[] y) {
        if (y.length <= PERIODO) {
            throw new IllegalArgumentException("Holt-Winters exige más de " + PERIODO + " meses");
        }
        Suavizado mejor = null;
        double ma = 0;
        double mb = 0;
        double mg = 0;
        for (double a : REJILLA) {
            for (double b : REJILLA) {
                for (double g : REJILLA) {
                    Suavizado s = holtWinters(y, a, b, g);
                    if (mejor == null || s.sse() < mejor.sse()) {
                        mejor = s;
                        ma = a;
                        mb = b;
                        mg = g;
                    }
                }
            }
        }
        Map<String, BigDecimal> p = new LinkedHashMap<>();
        p.put("alfa", dos(ma));
        p.put("beta", dos(mb));
        p.put("gamma", dos(mg));
        return suavizado(mejor, Collections.unmodifiableMap(p));
    }

    private static Modelo suavizado(Suavizado s, Map<String, BigDecimal> par) {
        double sigma = rms(s.sse(), s.residuos());
        return new Modelo() {
            public double pronostico(int h) {
                double base = s.nivel() + h * s.tendencia();
                return s.estacional() == null ? base : base + s.estacional()[(s.ultimo() + h) % PERIODO];
            }

            public double sigma() {
                return sigma;
            }

            public Map<String, BigDecimal> parametros() {
                return par;
            }
        };
    }

    // ------------------------------------------------------------------ utilidades

    /** Huella de la serie de entrenamiento: «aaaa-mm;valor» por línea (ver la clase). */
    static String huella(List<YearMonth> meses, List<BigDecimal> valores) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < meses.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(meses.get(i)).append(';').append(valores.get(i).stripTrailingZeros().toPlainString());
        }
        return Huellas.sha256(sb.toString());
    }

    private static BigDecimal dos(double v) {
        BigDecimal r = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
        return r.signum() == 0 ? r.abs() : r;
    }

    private static BigDecimal dos(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal dosONulo(Double v) {
        return v == null ? null : dos(v);
    }
}
