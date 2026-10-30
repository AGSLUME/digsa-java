package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Columna;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Fila;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Kpi;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Tabla;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tipo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Supresión de celdas pequeñas (analitica-tableros, invariante «Toda salida general es agregada»; decisión de diseño: parámetro
 * {@code digsa.analitica.tableros.supresion-minima}, desactivado por defecto; lo decide la DIGSA). Con k &gt; 0, en cada fila y KPI cuyo
 * número de pacientes distintos esté entre 1 y k − 1 se ocultan los pacientes, los pacientes-mes y el promedio por paciente («&lt;k»): con
 * un ESM y un diagnóstico sensible, una celda de 1 paciente revelaría su gasto. Las sumas (gasto, unidades, dispensaciones) no se tocan:
 * siguen cuadrando con el insumo (14.5). Se aplica a pantalla, descargas, consulta estructurada y asistente (todos pasan por aquí) y se
 * declara en los metadatos.
 *
 * <p><b>Reforzada</b> (A7.1, AN.31: filtro por una cohorte o un código sensible —VIH, huérfanas—): k es el mayor entre el general y el de
 * las sensibles y una celda oculta pierde también sus cifras (con un paciente, el gasto de la celda es el de esa persona). Además:
 * <ul>
 * <li>el total de pacientes del resultado llega calculado aparte ({@code totalPacientes}, siempre): si es pequeño se oculta todo;</li>
 * <li>en una tabla con una sola celda oculta (contando «Resto», cuyos pacientes no se cuentan y siempre se oculta) se oculta también la
 * visible con menos pacientes (complementaria);</li>
 * <li>las tablas sin pacientes por celda se ocultan enteras, salvo las series por periodo fuera del nivel ESM; el mapa de calor siempre;</li>
 * <li>las columnas del periodo de comparación por fila no se publican (no se cuentan sus pacientes por fila) y la comparación de los KPI
 * solo si el total de pacientes del periodo comparado ({@code comparadoPacientes}) no es pequeño;</li>
 * <li>en las tablas de distribución, el mínimo y el máximo no se publican y, con menos de 2k pacientes, nada.</li>
 * </ul>
 * Se llama primero {@link #kpis} y luego {@link #tablas}.
 */
public final class Supresion {

    /** Columnas que dependen de los pacientes de la fila. */
    static final Set<String> COLUMNAS = Set.of("pacientes", "pacientesMes", "promedioPaciente");
    /** Columnas que son conteos de pacientes (en la reforzada se ocultan también por su propio valor). */
    static final Set<String> CONTEOS = Set.of("pacientes", "pacientesMes", "poliformulados");
    /** Columnas del periodo de comparación por fila (no se publican en la reforzada). */
    static final Set<String> COMPARACION = Set.of("comparado", "variacion", "variacionPct");
    /** KPI de pacientes y el promedio que depende de ellos. */
    static final Set<String> KPIS = Set.of("pacientes", "pacientesAltoCosto", "poliformulados");
    /** KPI que describen a los pacientes de otro KPI (con este oculto, en la reforzada se ocultan también). */
    static final Map<String, List<String>> DEPENDIENTES = Map.of("pacientesAltoCosto", List.of("gastoAltoCosto", "participacionAltoCosto"),
            "poliformulados", List.of("porcentajePoliformulados"));
    /** Mapa de calor ESM × periodo: sus celdas son de nivel ESM en cualquier vista. */
    static final String CALOR = "calor";
    static final String DISTRIBUCION = "distribucion";

    private final int k;
    private final boolean reforzada;
    private final boolean nivelEsm;
    private final Long totalPacientes;
    private final Long comparadoPacientes;
    /** Reforzada con el total de pacientes bajo k: todo el resultado se oculta. */
    private boolean todo;

    public Supresion(int k) {
        this(k, false, false, null, null);
    }

    public Supresion(int k, boolean reforzada, boolean nivelEsm) {
        this(k, reforzada, nivelEsm, null, null);
    }

    /**
     * @param k                  umbral (0 = desactivada)
     * @param reforzada          filtro sensible: las celdas ocultas pierden también sus cifras (AN.24, AN.31)
     * @param nivelEsm           el resultado es de nivel ESM (alcance, vista o filtro de ESM)
     * @param totalPacientes     pacientes distintos del resultado (en la reforzada se calculan siempre); nulo: se toma del KPI «pacientes»
     * @param comparadoPacientes pacientes distintos del periodo de comparación (nulo sin comparación)
     */
    public Supresion(int k, boolean reforzada, boolean nivelEsm, Long totalPacientes, Long comparadoPacientes) {
        this.k = k;
        this.reforzada = reforzada && k > 0;
        this.nivelEsm = nivelEsm;
        this.totalPacientes = totalPacientes;
        this.comparadoPacientes = comparadoPacientes;
        this.todo = this.reforzada && oculta(totalPacientes);
    }

    public int k() {
        return k;
    }

    /** ¿Se ocultó todo el resultado? (reforzada con menos de k pacientes en total) */
    public boolean ocultoTodo() {
        return todo;
    }

    private boolean oculta(Object pacientes) {
        if (k <= 0 || pacientes == null) {
            return false;
        }
        long n = pacientes instanceof Number x ? x.longValue() : -1;
        return n > 0 && n < k;
    }

    private String texto() {
        return "<" + k;
    }

    private Kpi ocultar(Kpi x) {
        return new Kpi(x.clave(), x.etiqueta(), x.tipo(), null, texto(), null, null, null, null, null,
                "Oculto: menos de " + k + " pacientes (supresión de celdas pequeñas)", x.rastreo());
    }

    /** El KPI sin las cifras del periodo de comparación (pocos pacientes en ese periodo). */
    private Kpi sinComparacion(Kpi x) {
        return new Kpi(x.clave(), x.etiqueta(), x.tipo(), x.valor(), x.texto(), null, texto(), null, null, texto(),
                "Comparación oculta: menos de " + k + " pacientes en el periodo de comparación", x.rastreo());
    }

    public List<Kpi> kpis(List<Kpi> kpis) {
        if (k <= 0) {
            return kpis;
        }
        boolean pocos = totalPacientes != null ? oculta(totalPacientes)
                : kpis.stream().anyMatch(x -> x.clave().equals("pacientes") && oculta(x.valor()));
        todo = reforzada && pocos;
        boolean comparacionOculta = reforzada && oculta(comparadoPacientes);
        java.util.Set<String> arrastrados = new java.util.HashSet<>();
        if (reforzada) {
            for (Kpi x : kpis) {
                if (oculta(x.valor()) && DEPENDIENTES.containsKey(x.clave())) {
                    arrastrados.addAll(DEPENDIENTES.get(x.clave()));
                }
            }
        }
        List<Kpi> r = new ArrayList<>();
        for (Kpi x : kpis) {
            boolean ocultar = todo && (x.valor() != null || x.comparado() != null) || KPIS.contains(x.clave()) && oculta(x.valor())
                    || x.clave().equals("promedioPaciente") && pocos || arrastrados.contains(x.clave());
            if (ocultar) {
                r.add(ocultar(x));
            } else if (comparacionOculta && (x.comparado() != null || x.variacion() != null || x.variacionPct() != null)) {
                r.add(sinComparacion(x));
            } else {
                r.add(x);
            }
        }
        return r;
    }

    public List<Tabla> tablas(List<Tabla> tablas) {
        if (k <= 0) {
            return tablas;
        }
        List<Tabla> r = new ArrayList<>();
        for (Tabla t : tablas) {
            int iPac = -1;
            for (int i = 0; i < t.columnas().size(); i++) {
                if (t.columnas().get(i).clave().equals("pacientes")) {
                    iPac = i;
                }
            }
            if (reforzada) {
                r.add(reforzada(t, iPac));
                continue;
            }
            if (iPac < 0) {
                r.add(t);
                continue;
            }
            List<Fila> filas = new ArrayList<>();
            for (Fila f : t.filas()) {
                filas.add(oculta(f.valores().get(iPac)) ? sinCifras(t.columnas(), f, false) : f);
            }
            r.add(new Tabla(t.id(), t.titulo(), t.grafico(), t.valorGrafico(), t.comparado(), t.columnas(), filas, t.nota()));
        }
        return r;
    }

    /** Una tabla con la supresión reforzada (AN.31). */
    private Tabla reforzada(Tabla t, int iPac) {
        if (DISTRIBUCION.equals(t.id())) {
            // Mínimo y máximo son de una persona; con pocos pacientes, también los cuartiles
            boolean nada = todo || totalPacientes == null || totalPacientes < 2L * k;
            List<Fila> filas = new ArrayList<>();
            for (Fila f : t.filas()) {
                boolean extremo = Set.of("min", "max", "Mínimo", "Máximo").contains(f.clave());
                filas.add(nada || extremo ? sinCifras(t.columnas(), f, true) : f);
            }
            return new Tabla(t.id(), t.titulo(), t.grafico(), t.valorGrafico(), t.comparado(), t.columnas(), filas, nota(t.nota(), nada
                    ? "Distribución oculta: menos de " + (2 * k) + " pacientes en una consulta sensible." : "Mínimo y máximo ocultos (son de una persona)."));
        }
        boolean serie = "serie".equals(t.grafico());
        if (todo || iPac < 0 && (CALOR.equals(t.id()) || !serie || nivelEsm)) {
            // Todo pequeño, o una tabla sin pacientes por celda: no se puede saber qué celda es pequeña
            List<Fila> filas = new ArrayList<>();
            for (Fila f : t.filas()) {
                filas.add(sinCifras(t.columnas(), f, true));
            }
            return new Tabla(t.id(), t.titulo(), t.grafico(), t.valorGrafico(), t.comparado(), t.columnas(), filas, nota(t.nota(), todo
                    ? "Cifras ocultas: menos de " + k + " pacientes en una consulta sensible."
                    : "Cifras ocultas: la tabla no lleva pacientes por celda y la consulta es sensible."));
        }
        List<Fila> filas = new ArrayList<>();
        List<Boolean> ocultas = new ArrayList<>();
        for (Fila f : t.filas()) {
            boolean total = "total".equals(f.tipo());
            boolean oc = !total && (iPac >= 0 && oculta(f.valores().get(iPac)) || "resto".equals(f.tipo()));
            Fila x = oc ? sinCifras(t.columnas(), f, true) : sinComparacionNiConteos(t.columnas(), f);
            filas.add(x);
            ocultas.add(oc);
        }
        if (iPac >= 0 && ocultas.stream().filter(Boolean::booleanValue).count() == 1) {
            // Complementaria: la visible con menos pacientes también (con el total, la oculta saldría por diferencia)
            int menor = -1;
            long min = Long.MAX_VALUE;
            for (int i = 0; i < filas.size(); i++) {
                Fila f = filas.get(i);
                if (!ocultas.get(i) && !"total".equals(f.tipo()) && f.valores().get(iPac) instanceof Number n && n.longValue() > 0 && n.longValue() < min) {
                    min = n.longValue();
                    menor = i;
                }
            }
            if (menor >= 0) {
                filas.set(menor, sinCifras(t.columnas(), filas.get(menor), true));
            }
        }
        return new Tabla(t.id(), t.titulo(), t.grafico(), t.valorGrafico(), t.comparado(), t.columnas(), filas, t.nota());
    }

    /** Fila visible de la reforzada: sin las columnas del periodo de comparación y con los conteos pequeños ocultos por su valor. */
    private Fila sinComparacionNiConteos(List<Columna> columnas, Fila f) {
        List<Object> v = new ArrayList<>(f.valores());
        List<String> tx = new ArrayList<>(f.textos());
        boolean cambio = false;
        for (int i = 0; i < columnas.size(); i++) {
            String c = columnas.get(i).clave();
            if (COMPARACION.contains(c) && v.get(i) != null || CONTEOS.contains(c) && oculta(v.get(i))) {
                v.set(i, null);
                tx.set(i, texto());
                cambio = true;
            }
        }
        return cambio ? new Fila(f.clave(), f.rastreo(), java.util.Collections.unmodifiableList(v), List.copyOf(tx), f.tipo()) : f;
    }

    /** La fila con sus pacientes ocultos y, si {@code cifras}, también todas sus cifras (las columnas que no son texto). */
    private Fila sinCifras(List<Columna> columnas, Fila f, boolean cifras) {
        List<Object> v = new ArrayList<>(f.valores());
        List<String> tx = new ArrayList<>(f.textos());
        for (int i = 0; i < columnas.size(); i++) {
            Columna c = columnas.get(i);
            if (COLUMNAS.contains(c.clave()) || cifras && c.tipo() != Tipo.TEXTO && v.get(i) != null) {
                v.set(i, null);
                tx.set(i, texto());
            }
        }
        return new Fila(f.clave(), f.rastreo(), java.util.Collections.unmodifiableList(v), List.copyOf(tx), f.tipo());
    }

    private static String nota(String actual, String extra) {
        return actual == null || actual.isBlank() ? extra : actual + " " + extra;
    }
}
