package co.com.agsamericas.digsa.reglas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reglas del presupuesto del contrato de medicamentos (docs/especificaciones/presupuesto; Anexo 5 §4.6, Anexo 2 L739-741). Montos en
 * pesos con 2 decimales y siempre BigDecimal; los porcentajes se muestran con 2 decimales y se comparan sin redondear.
 */
public final class Presupuesto {

    /** Umbrales de la notificación formal (Anexo 2 L741). */
    public static final List<Integer> UMBRALES = List.of(25, 50, 75, 100);
    public static final String BASE_FACTURADO = "facturado";
    public static final String BASE_RECONOCIDO = "reconocido";
    public static final BigDecimal TOLERANCIA_EXCESO = new BigDecimal("10.00");
    public static final BigDecimal TOLERANCIA_SUBEJECUCION = new BigDecimal("20.00");
    /** Mayor monto de numeric(18,2). */
    public static final BigDecimal MAXIMO = new BigDecimal("9999999999999999.99");

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final Pattern MILES_PUNTO = Pattern.compile("^\\d{1,3}(\\.\\d{3})+$");
    private static final Pattern MILES_COMA = Pattern.compile("^\\d{1,3}(,\\d{3})+$");
    private static final Pattern DIGITOS = Pattern.compile("^\\d+$");
    private static final Pattern PERIODO_ISO = Pattern.compile("^(\\d{4})-(\\d{1,2})(-01)?$");
    private static final Pattern PERIODO_BARRA = Pattern.compile("^(\\d{1,2})/(\\d{4})$");

    private Presupuesto() {
    }

    // ---------- Montos ----------

    /**
     * Lee un monto escrito a mano o en un CSV: «1.250.000,5», «1250000.50», «$ 1 250 000,50». Con punto y coma, el último es el decimal;
     * con solo comas, una coma es decimal (es-CO) y varias son de miles; con solo puntos, «1.250» y «1.250.000» son miles y un punto con 1
     * o 2 decimales es decimal. Vacío si no es un número, tiene más de 2 decimales o pasa el máximo. El signo «-» se conserva.
     */
    public static Optional<BigDecimal> monto(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String t = texto.replace("$", "").replace(" ", "").replace(" ", "").replace("'", "").strip();
        boolean negativo = t.startsWith("-");
        if (negativo || t.startsWith("+")) {
            t = t.substring(1);
        }
        if (t.isEmpty()) {
            return Optional.empty();
        }
        String entero;
        String decimales;
        int punto = t.lastIndexOf('.');
        int coma = t.lastIndexOf(',');
        if (punto >= 0 && coma >= 0) {
            char dec = punto > coma ? '.' : ',';
            char miles = dec == '.' ? ',' : '.';
            int i = t.lastIndexOf(dec);
            entero = t.substring(0, i);
            // Los miles en grupos de 3: «12.5,00» es un error de digitación, no 125,00
            if (!DIGITOS.matcher(entero).matches() && !entero.matches("^\\d{1,3}(" + Pattern.quote(String.valueOf(miles)) + "\\d{3})+$")) {
                return Optional.empty();
            }
            entero = entero.replace(String.valueOf(miles), "");
            decimales = t.substring(i + 1);
        } else if (coma >= 0) {
            if (t.indexOf(',') != coma && MILES_COMA.matcher(t).matches()) {
                entero = t.replace(",", "");
                decimales = "";
            } else {
                entero = t.substring(0, coma);
                decimales = t.substring(coma + 1);
            }
        } else if (punto >= 0) {
            if (MILES_PUNTO.matcher(t).matches()) {
                entero = t.replace(".", "");
                decimales = "";
            } else {
                entero = t.substring(0, punto);
                decimales = t.substring(punto + 1);
            }
        } else {
            entero = t;
            decimales = "";
        }
        if (entero.isEmpty()) {
            entero = "0";
        }
        if (!DIGITOS.matcher(entero).matches() || (!decimales.isEmpty() && !DIGITOS.matcher(decimales).matches()) || decimales.length() > 2
                || entero.length() > 16) {
            return Optional.empty();
        }
        BigDecimal v = new BigDecimal(entero + (decimales.isEmpty() ? "" : "." + decimales)).setScale(2, RoundingMode.UNNECESSARY);
        return Optional.of(negativo ? v.negate() : v);
    }

    /** Monto con 2 decimales, coma decimal y sin separador de miles (VCO P27): «1250000,50», «-30000000,00». */
    public static String formato(BigDecimal v) {
        return v == null ? "" : v.setScale(2, RoundingMode.HALF_EVEN).toPlainString().replace('.', ',');
    }

    /** Porcentaje con 2 decimales para mostrar: ejecutado ÷ asignado × 100. Vacío si no hay asignado. */
    public static Optional<BigDecimal> porcentaje(BigDecimal ejecutado, BigDecimal asignado) {
        if (asignado == null || asignado.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(ejecutado.multiply(CIEN).divide(asignado, 2, RoundingMode.HALF_EVEN));
    }

    // ---------- Umbrales ----------

    /**
     * Umbrales alcanzados (ejecutado ≥ asignado × umbral ÷ 100, sin redondear). Con asignado 0 y algo ejecutado, todos: no hay presupuesto
     * para lo que se consumió.
     */
    public static SortedSet<Integer> umbralesAlcanzados(BigDecimal ejecutado, BigDecimal asignado) {
        SortedSet<Integer> r = new TreeSet<>();
        if (ejecutado == null || ejecutado.signum() <= 0) {
            return r;
        }
        for (int u : UMBRALES) {
            if (asignado.signum() <= 0 || ejecutado.multiply(CIEN).compareTo(asignado.multiply(BigDecimal.valueOf(u))) >= 0) {
                r.add(u);
            }
        }
        return r;
    }

    /** Los alcanzados que aún no estaban registrados para la línea. */
    public static SortedSet<Integer> nuevosUmbrales(SortedSet<Integer> alcanzados, java.util.Collection<Integer> registrados) {
        SortedSet<Integer> r = new TreeSet<>(alcanzados);
        r.removeAll(registrados);
        return r;
    }

    /** Solo el más alto de los recién cruzados lleva aviso propio (los más bajos quedan registrados sin aviso). */
    public static Optional<Integer> umbralANotificar(SortedSet<Integer> nuevos) {
        return nuevos.isEmpty() ? Optional.empty() : Optional.of(nuevos.last());
    }

    /** Semáforo del tablero por % de ejecución: verde < 75, ámbar de 75 a 99,99, rojo desde 100 (y sin asignado con algo ejecutado). */
    public static String semaforo(BigDecimal ejecutado, BigDecimal asignado) {
        SortedSet<Integer> u = umbralesAlcanzados(ejecutado, asignado);
        return u.contains(100) ? "rojo" : u.contains(75) ? "ambar" : "verde";
    }

    /** Base del parámetro presupuesto_base_umbral: «facturado» o «reconocido»; cualquier otro valor no es válido. */
    public static Optional<String> baseValida(String parametro) {
        String p = parametro == null ? "" : parametro.strip().toLowerCase(java.util.Locale.ROOT);
        return BASE_FACTURADO.equals(p) || BASE_RECONOCIDO.equals(p) ? Optional.of(p) : Optional.empty();
    }

    /** Base para calcular: la del parámetro o, si no es válida, facturado (decisión del usuario 2026-10-22); quien lee avisa. */
    public static String base(String parametro) {
        return baseValida(parametro).orElse(BASE_FACTURADO);
    }

    // ---------- Vigencia, periodos y proyección ----------

    /** Meses de la vigencia, del de inicio al de fin, inclusive. */
    public static List<YearMonth> meses(LocalDate inicio, LocalDate fin) {
        List<YearMonth> r = new ArrayList<>();
        for (YearMonth m = YearMonth.from(inicio); !m.isAfter(YearMonth.from(fin)); m = m.plusMonths(1)) {
            r.add(m);
        }
        return r;
    }

    /** Periodo mensual: «2027-03», «2027-3», «2027-03-01» o «03/2027». */
    public static Optional<YearMonth> periodo(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String t = texto.strip();
        java.util.regex.Matcher m = PERIODO_ISO.matcher(t);
        int anio;
        int mes;
        if (m.matches()) {
            anio = Integer.parseInt(m.group(1));
            mes = Integer.parseInt(m.group(2));
        } else {
            m = PERIODO_BARRA.matcher(t);
            if (!m.matches()) {
                return Optional.empty();
            }
            mes = Integer.parseInt(m.group(1));
            anio = Integer.parseInt(m.group(2));
        }
        if (mes < 1 || mes > 12 || anio < 2020 || anio > 2100) {
            return Optional.empty();
        }
        return Optional.of(YearMonth.of(anio, mes));
    }

    /** «2027-03». */
    public static String periodoTexto(YearMonth m) {
        return m.toString();
    }

    /**
     * Proyección lineal: el asignado repartido en los meses, cada mes truncado a 2 decimales y el residuo de centavos en el último mes, para
     * que la suma sea exactamente el asignado y ningún mes quede negativo (0,10 en 12 meses: 0,00 × 11 y 0,10).
     */
    public static List<BigDecimal> proyeccionLineal(BigDecimal asignado, int meses) {
        if (meses <= 0) {
            return List.of();
        }
        BigDecimal mensual = asignado.divide(BigDecimal.valueOf(meses), 2, RoundingMode.DOWN);
        List<BigDecimal> r = new ArrayList<>(meses);
        for (int i = 0; i < meses - 1; i++) {
            r.add(mensual);
        }
        r.add(asignado.subtract(mensual.multiply(BigDecimal.valueOf(meses - 1L))).setScale(2, RoundingMode.HALF_EVEN));
        return r;
    }

    /** Proyectado acumulado hasta el corte, inclusive: suma de los primeros meses de la vigencia hasta el corte. */
    public static BigDecimal acumuladoLineal(BigDecimal asignado, LocalDate inicio, LocalDate fin, YearMonth corte) {
        List<YearMonth> m = meses(inicio, fin);
        List<BigDecimal> p = proyeccionLineal(asignado, m.size());
        BigDecimal suma = BigDecimal.ZERO.setScale(2);
        for (int i = 0; i < m.size() && !m.get(i).isAfter(corte); i++) {
            suma = suma.add(p.get(i));
        }
        return suma;
    }

    // ---------- Desviación frente a la proyección ----------

    public enum Sentido {
        EXCESO("exceso", "exceso"), SUBEJECUCION("subejecucion", "subejecución"), NINGUNO("ninguno", "dentro de la tolerancia");

        private final String codigo;
        private final String texto;

        Sentido(String codigo, String texto) {
            this.codigo = codigo;
            this.texto = texto;
        }

        public String codigo() {
            return codigo;
        }

        public String texto() {
            return texto;
        }
    }

    /** Desviación % = (ejecutado − proyectado) ÷ proyectado × 100, con 2 decimales. Vacía si lo proyectado es 0. */
    public static Optional<BigDecimal> desviacion(BigDecimal ejecutado, BigDecimal proyectado) {
        if (proyectado == null || proyectado.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(ejecutado.subtract(proyectado).multiply(CIEN).divide(proyectado, 2, RoundingMode.HALF_EVEN));
    }

    /**
     * Exceso si la desviación pasa la tolerancia de exceso; subejecución si queda por debajo de menos la de subejecución (comparación
     * exacta, sin redondear: con +10,004 % la pantalla muestra 10,00 % y ya es exceso). Proyectado 0 o vacío con algo ejecutado es exceso.
     */
    public static Sentido sentido(BigDecimal ejecutado, BigDecimal proyectado, BigDecimal tolExceso, BigDecimal tolSub) {
        if (ejecutado == null) {
            ejecutado = BigDecimal.ZERO;
        }
        if (proyectado == null || proyectado.signum() <= 0) {
            return ejecutado.signum() > 0 ? Sentido.EXCESO : Sentido.NINGUNO;
        }
        BigDecimal dif = ejecutado.subtract(proyectado).multiply(CIEN);
        if (dif.compareTo(proyectado.multiply(tolExceso)) > 0) {
            return Sentido.EXCESO;
        }
        if (dif.compareTo(proyectado.multiply(tolSub).negate()) < 0) {
            return Sentido.SUBEJECUCION;
        }
        return Sentido.NINGUNO;
    }

    /** Tolerancia del parámetro («10,00», «10.5», «15», «5 %»): entre 0 y 1000 con hasta 2 decimales. */
    public static Optional<BigDecimal> toleranciaValida(String parametro) {
        return monto(parametro == null ? null : parametro.replace("%", "")).filter(v -> v.signum() >= 0 && v.compareTo(BigDecimal.valueOf(1000)) <= 0);
    }

    /** Tolerancia para calcular: la del parámetro o, si no es válida, el valor por defecto; quien lee avisa. */
    public static BigDecimal tolerancia(String parametro, BigDecimal defecto) {
        return toleranciaValida(parametro).orElse(defecto);
    }

    // ---------- Notificación ----------

    /** Consecutivo de la notificación formal: «PRE-2027-0007». */
    public static String consecutivo(String prefijo, int anio, int numero) {
        String p = prefijo == null || !prefijo.strip().matches("^[A-Za-z]{2,10}$") ? "PRE" : prefijo.strip().toUpperCase(java.util.Locale.ROOT);
        return p + "-" + anio + "-" + String.format(java.util.Locale.ROOT, "%04d", numero);
    }

    /** Clave de deduplicación de la alerta de umbral: una vez por vigencia, línea y umbral. */
    public static String claveUmbral(int anio, long lineaId, int umbral) {
        return "pu:" + anio + ":" + lineaId + ":" + umbral;
    }

    /** Clave de deduplicación de la alerta de desviación: una vez por corte, nivel, referencia y sentido. */
    public static String claveDesviacion(int anio, YearMonth corte, String nivel, String referencia, Sentido s) {
        return "pd:" + anio + ":" + corte + ":" + nivel + ":" + referencia + ":" + s.codigo();
    }
}
