package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Corte;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Dispensa;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Parametros;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Tipo;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Codigos;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Criterio;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.GastoMovil;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Lista;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.MesPaciente;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Resultado;
import co.com.agsamericas.digsa.reglas.Bitacora;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Recorrido único de los hechos de un corte (ana_hechos_tablero, ordenados por paciente y fecha, en un cursor): por cada paciente arma sus
 * meses y evalúa cada versión de cohorte con las reglas puras (AnaliticaCohortes), acumula la serie AGREGADA por mes y celda (nacional,
 * Fuerza y ESM POR FUERZA de la dispensación: un ESM compartido por dos Fuerzas no mezcla cifras en el alcance de Sanidad), escribe la
 * membresía en un búfer COPY por versión (número de paciente, nunca el seudónimo), cuenta los pacientes distintos del periodo por defecto
 * de cada celda (las tarjetas los leen sin recorrer la membresía) y corre los detectores que miran la secuencia del paciente (duplicidad
 * terapéutica, interacciones, cambio de marca en estrecho margen y uso fuera de indicación).
 *
 * <p>Aislamiento (revisión A4 2026-10-24): si la evaluación de una versión de cohorte o de un tipo de secuencia lanza, esa versión o ese tipo
 * queda marcado con su error y deja de evaluarse; las demás versiones, los demás tipos y el recorrido siguen.
 *
 * <p>Ingresos y salidas: un miembro «ingresa» a una celda si no fue miembro en ella en los meses de gracia previos y la historia ya permite
 * determinarlo (no en los meses de calentamiento del inicio del corte: los meses consecutivos que exige el criterio, y al menos gracia + 1);
 * «sale» cuando su membresía en la celda termina y no vuelve dentro de la gracia (se cuenta en el mes siguiente al último). Memoria: un
 * paciente a la vez más los acumuladores agregados.
 */
final class MotorRiesgo {

    private static final Logger LOG = LoggerFactory.getLogger(MotorRiesgo.class);
    static final int FETCH = 10_000;

    /** Versión de cohorte a calcular. {@code noEvaluado}: motivo (falta una lista) o nulo. */
    record VersionCohorte(int indice, long id, long cohorteId, String codigo, Criterio criterio, List<Codigos> atomos, List<String> cronicos,
                          boolean particion, String noEvaluado, String fuentes, GastoMovil gasto) {
    }

    /** Celda de la serie: versión, mes, celda («N», «F|EJC», «E|EJC|BAS02») y grupo de la partición. */
    record Llave(int version, int mes, String celda, String grupo) {
    }

    /** Llave de una alerta acumulada en el recorrido; en el nivel ESM el código es «Fuerza|ESM» (la Fuerza de la dispensación). */
    record ClaveAlerta(Tipo tipo, String nivel, String codigoNivel, String dimension, String valor, int mes) {
    }

    /** Acumulado de una alerta de secuencia: pacientes distintos, registros distintos, gasto, ESM, Fuerzas y CUM involucrados. */
    static final class Acum {
        final Set<Integer> pacientes = new HashSet<>();
        long registros;
        long gasto;
        final TreeSet<String> esms = new TreeSet<>();
        final TreeSet<String> fuerzas = new TreeSet<>();
        final TreeSet<String> cums = new TreeSet<>();
    }

    private static final class Fila {
        int paciente;
        int dia;
        int mes;
        String fuerza;
        String esm;
        Long punto;
        String modalidad;
        Long medicamento;
        String cum;
        String cie;
        long cantidad;
        long valor;
    }

    /** Lo que el paciente dispensó en un mes (común a todas las cohortes). */
    private static final class MesBase {
        final int mes;
        long gasto;
        final Set<String> principios = new HashSet<>();
        final Map<String, Long> gastoAtc2 = new HashMap<>();
        final Set<Long> meds = new HashSet<>();
        final Set<String> cies = new HashSet<>();
        final Set<String> ops = new HashSet<>();
        /** Celda ⇒ {dispensaciones, unidades, gasto}. */
        final Map<String, long[]> celdas = new LinkedHashMap<>();

        MesBase(int mes) {
            this.mes = mes;
        }
    }

    private final CatalogoClinico cat;
    private final List<VersionCohorte> versiones;
    /** Umbral del gasto móvil por «meses:percentil» ⇒ mes ⇒ centavos. */
    private final Map<String, Map<Integer, Long>> umbrales;
    private final Map<Tipo, Parametros> parametros;
    private final long corte;
    private final int primerMes;
    private final int ultimoMes;
    private final int gracia;
    /** Periodo por defecto de las tarjetas (ordinales de mes). */
    final int periodoDesde;
    final int periodoHasta;
    private final RecalculoRiesgo.Sonda sonda;

    final Map<Llave, long[]> serie = new HashMap<>();
    /** Membresía en líneas COPY, una por versión (la de una versión que falla se descarta sin tocar las demás); por lotes a disco (AN.32). */
    final List<Volcado> miembros = new ArrayList<>();
    /** Pacientes distintos del periodo por defecto: versión ⇒ celda ⇒ pacientes. */
    final Map<Integer, Map<String, long[]>> conteos = new HashMap<>();
    long filasMiembros;
    final Map<ClaveAlerta, Acum> alertas = new HashMap<>();
    /** Versiones (índice) y tipos de secuencia que fallaron, con el error resumido. */
    final Map<Integer, String> fallosVersion = new TreeMap<>();
    final Map<Tipo, String> fallosTipo = new EnumMap<>(Tipo.class);
    /** ESM ⇒ punto (el primero visto): alcance ESM de las alertas. */
    final Map<String, Long> puntoDeEsm = new TreeMap<>();
    long filas;

    private final Set<Tipo> activos;
    private final Map<Long, long[]> mascaraMed = new HashMap<>();
    private final Map<String, long[]> mascaraCie = new HashMap<>();
    private final Map<String, long[]> mascaraOp = new HashMap<>();
    private final Map<String, String[]> cronicoCie = new HashMap<>();
    private final int[] inicioIngresos;

    MotorRiesgo(CatalogoClinico cat, List<VersionCohorte> versiones, Map<String, Map<Integer, Long>> umbrales, Map<Tipo, Parametros> parametros,
                Set<Tipo> evaluables, Corte corte, int gracia, RecalculoRiesgo.Sonda sonda) {
        this.cat = cat;
        this.versiones = versiones;
        this.umbrales = umbrales;
        this.parametros = parametros;
        this.activos = evaluables.isEmpty() ? EnumSet.noneOf(Tipo.class) : EnumSet.copyOf(evaluables);
        this.corte = corte.cargueId();
        this.gracia = gracia;
        this.sonda = sonda;
        if (corte.datosDesde() == null || corte.datosHasta() == null) {
            // Nunca el reloj del sistema: el resultado de un corte no puede depender del día en que se recalcula
            throw new IllegalStateException("El corte " + corte.cargueId() + " no tiene fechas de datos: no se puede evaluar");
        }
        this.primerMes = AnaliticaCohortes.ordinal(corte.datosDesde().getYear(), corte.datosDesde().getMonthValue());
        this.ultimoMes = AnaliticaCohortes.ordinal(corte.datosHasta().getYear(), corte.datosHasta().getMonthValue());
        LocalDate[] p = ServicioCohortes.periodo(corte, null, null);
        this.periodoDesde = AnaliticaCohortes.ordinal(p[0].getYear(), p[0].getMonthValue());
        this.periodoHasta = AnaliticaCohortes.ordinal(p[1].getYear(), p[1].getMonthValue());
        this.inicioIngresos = new int[versiones.size()];
        for (VersionCohorte v : versiones) {
            miembros.add(new Volcado());
            inicioIngresos[v.indice()] = primerMes + Math.max(AnaliticaCohortes.mesesCalentamiento(v.criterio()), gracia + 1);
        }
    }

    /** Cierra la membresía acumulada (borra los archivos temporales de los lotes, AN.32). */
    void liberar() {
        miembros.forEach(Volcado::close);
    }

    static String llaveUmbral(GastoMovil g) {
        return g.meses() + ":" + g.valorPercentil();
    }

    /** Primer mes (ordinal) en que la versión puede contar ingresos: antes es calentamiento (historia insuficiente). */
    int inicioIngresos(int version) {
        return inicioIngresos[version];
    }

    /** Recorre los hechos del corte en un cursor (dentro de la transacción de lectura de quien llama). */
    void recorrer(JdbcTemplate t, List<Long> cargues) {
        List<Fila> paciente = new ArrayList<>();
        int[] actual = {Integer.MIN_VALUE};
        t.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT x.paciente_id, x.fecha, x.fuerza, x.esm_codigo, x.punto_id, x.modalidad, x.medicamento_id, x.cum, x.cie10,
                           x.cantidad_milesimas, x.valor_centavos
                    FROM ana_hechos_tablero x WHERE x.cargue_id = ANY(?)
                    ORDER BY x.paciente_id, x.fecha, x.medicamento_id, x.cantidad_milesimas, x.valor_centavos, x.esm_codigo, x.cum""");
            ps.setFetchSize(FETCH);
            ps.setArray(1, con.createArrayOf("bigint", cargues.toArray()));
            return ps;
        }, (ResultSet rs) -> {
            Fila f = leer(rs);
            filas++;
            if (f.paciente != actual[0] && !paciente.isEmpty()) {
                paciente(actual[0], paciente);
                paciente.clear();
            }
            actual[0] = f.paciente;
            paciente.add(f);
        });
        if (!paciente.isEmpty()) {
            paciente(actual[0], paciente);
        }
    }

    private Fila leer(ResultSet rs) throws SQLException {
        Fila f = new Fila();
        f.paciente = rs.getInt(1);
        LocalDate d = rs.getDate(2).toLocalDate();
        f.dia = (int) d.toEpochDay();
        f.mes = AnaliticaCohortes.ordinal(d.getYear(), d.getMonthValue());
        f.fuerza = rs.getString(3);
        f.esm = rs.getString(4);
        long p = rs.getLong(5);
        f.punto = rs.wasNull() ? null : p;
        f.modalidad = rs.getString(6);
        long m = rs.getLong(7);
        f.medicamento = rs.wasNull() ? null : m;
        f.cum = rs.getString(8);
        f.cie = cat.cie(rs.getString(9));
        f.cantidad = rs.getLong(10);
        f.valor = rs.getLong(11);
        if (f.punto != null && !puntoDeEsm.containsKey(f.esm)) {
            puntoDeEsm.put(f.esm, f.punto);
        }
        return f;
    }

    /** Celda ESM: la Fuerza de la dispensación y el ESM. */
    static String celdaEsm(String fuerza, String esm) {
        return "E|" + fuerza + "|" + esm;
    }

    // ---------- Un paciente ----------

    private void paciente(int pid, List<Fila> filas) {
        List<MesBase> meses = new ArrayList<>();
        MesBase mb = null;
        for (Fila f : filas) {
            if (mb == null || mb.mes != f.mes) {
                mb = new MesBase(f.mes);
                meses.add(mb);
            }
            mb.gasto += f.valor;
            CatalogoClinico.Med m = cat.med(f.medicamento);
            mb.principios.add(m == null ? "MED:" + f.medicamento : m.llavePrincipio());
            String atc2 = m == null ? null : m.atcNivel(3);
            if (atc2 != null) {
                mb.gastoAtc2.merge(atc2, f.valor, Long::sum);
            }
            if (f.medicamento != null) {
                mb.meds.add(f.medicamento);
            }
            if (f.cie != null) {
                mb.cies.add(f.cie);
            }
            mb.ops.add("F|" + f.fuerza);
            mb.ops.add("E|" + f.esm);
            mb.ops.add("M|" + f.modalidad);
            sumar(mb.celdas, "N", f);
            sumar(mb.celdas, "F|" + f.fuerza, f);
            sumar(mb.celdas, celdaEsm(f.fuerza, f.esm), f);
        }
        for (VersionCohorte v : versiones) {
            if (v.noEvaluado() != null || fallosVersion.containsKey(v.indice())) {
                continue;
            }
            try {
                sonda.version(v.codigo());
                cohorte(pid, v, meses);
            } catch (RuntimeException e) {
                // Una versión mal parametrizada (o un dato que la rompe) no tumba a las demás ni al recorrido: queda «no evaluada: error»
                fallosVersion.put(v.indice(), Bitacora.cortar("no evaluada: error (" + e.getClass().getSimpleName() + ")", 300));
                LOG.error("[analitica] La evaluación de la cohorte {} (versión {}) falló; las demás siguen", v.codigo(), v.id(), e);
            }
        }
        secuencia(pid, filas);
    }

    private static void sumar(Map<String, long[]> celdas, String c, Fila f) {
        long[] s = celdas.computeIfAbsent(c, x -> new long[3]);
        s[0]++;
        s[1] += f.cantidad;
        s[2] += f.valor;
    }

    private void cohorte(int pid, VersionCohorte v, List<MesBase> meses) {
        List<MesPaciente> mps = new ArrayList<>(meses.size());
        for (MesBase mb : meses) {
            long mask = 0;
            for (Long id : mb.meds) {
                mask |= mascaraMed(id)[v.indice()];
            }
            for (String c : mb.cies) {
                mask |= mascaraCie(c)[v.indice()];
            }
            for (String o : mb.ops) {
                mask |= mascaraOp(o)[v.indice()];
            }
            Set<String> cron = Set.of();
            if (!v.cronicos().isEmpty() && !mb.cies.isEmpty()) {
                cron = new HashSet<>();
                for (String c : mb.cies) {
                    String k = cronico(c)[v.indice()];
                    if (k != null) {
                        cron.add(k);
                    }
                }
            }
            mps.add(new MesPaciente(mb.mes, mask, mb.principios.size(), mb.gasto, cron, mb.gastoAtc2));
        }
        Map<Integer, Long> u = v.gasto() == null ? Map.of() : umbrales.getOrDefault(llaveUmbral(v.gasto()), Map.of());
        Resultado r = AnaliticaCohortes.evaluar(v.criterio(), mps, mes -> u.getOrDefault(mes, -1L));
        if (v.particion()) {
            for (int i = 0; i < meses.size(); i++) {
                MesBase mb = meses.get(i);
                // A7.1: la membresía de la partición (grupo del paciente en el mes) para filtrar los tableros y la predicción por GCF o GRC
                miembroParticion(v, pid, mb, r.grupo()[i]);
                for (Map.Entry<String, long[]> c : mb.celdas.entrySet()) {
                    long[] s = serie.computeIfAbsent(new Llave(v.indice(), mb.mes, c.getKey(), r.grupo()[i]), x -> new long[6]);
                    s[0]++;
                    s[3] += c.getValue()[0];
                    s[4] += c.getValue()[1];
                    s[5] += c.getValue()[2];
                }
            }
            return;
        }
        // Último mes de membresía por celda (episodio abierto) y celdas con membresía en el periodo por defecto
        Map<String, Integer> ultimo = new HashMap<>();
        Set<String> enPeriodo = new HashSet<>();
        int inicio = inicioIngresos[v.indice()];
        for (int i = 0; i < meses.size(); i++) {
            if (!r.miembro()[i]) {
                continue;
            }
            MesBase mb = meses.get(i);
            for (Map.Entry<String, long[]> c : mb.celdas.entrySet()) {
                long[] s = serie.computeIfAbsent(new Llave(v.indice(), mb.mes, c.getKey(), ""), x -> new long[6]);
                s[0]++;
                Integer u0 = ultimo.get(c.getKey());
                boolean nuevoEpisodio = u0 == null || mb.mes - u0 > gracia + 1;
                if (u0 != null && nuevoEpisodio) {
                    // El episodio anterior terminó (no volvió dentro de la gracia): salida en el mes siguiente a su último mes
                    serie.computeIfAbsent(new Llave(v.indice(), u0 + 1, c.getKey(), ""), x -> new long[6])[2]++;
                }
                if (nuevoEpisodio && mb.mes >= inicio) {
                    s[1]++;
                }
                s[3] += c.getValue()[0];
                s[4] += c.getValue()[1];
                s[5] += c.getValue()[2];
                ultimo.put(c.getKey(), mb.mes);
                if (mb.mes >= periodoDesde && mb.mes <= periodoHasta) {
                    enPeriodo.add(c.getKey());
                }
            }
            miembro(v, pid, mb);
        }
        // Episodios que terminan dentro de los datos (sin volver en la gracia); los que podrían seguir quedan abiertos (censura por la derecha)
        for (Map.Entry<String, Integer> e : ultimo.entrySet()) {
            if (e.getValue() + gracia + 1 <= ultimoMes) {
                serie.computeIfAbsent(new Llave(v.indice(), e.getValue() + 1, e.getKey(), ""), x -> new long[6])[2]++;
            }
        }
        if (!enPeriodo.isEmpty()) {
            Map<String, long[]> c = conteos.computeIfAbsent(v.indice(), x -> new HashMap<>());
            for (String celda : enPeriodo) {
                c.computeIfAbsent(celda, x -> new long[1])[0]++;
            }
        }
    }

    /** Línea COPY de la membresía (texto, tabulador): versión, corte, mes, paciente, Fuerzas, ESM y pares Fuerza|ESM. */
    private void miembro(VersionCohorte v, int pid, MesBase mb) {
        YearMonth ym = AnaliticaCohortes.deOrdinal(mb.mes);
        List<String> fu = new ArrayList<>();
        Set<String> es = new TreeSet<>();
        List<String> pares = new ArrayList<>();
        for (String c : mb.celdas.keySet()) {
            if (c.startsWith("F|")) {
                fu.add(c.substring(2));
            } else if (c.startsWith("E|")) {
                String par = c.substring(2);
                pares.add(par);
                es.add(par.substring(par.indexOf('|') + 1));
            }
        }
        Volcado d = miembros.get(v.indice());
        d.linea().append(v.id()).append('\t').append(corte).append('\t').append(ym.atDay(1)).append('\t').append(pid).append('\t')
                .append(arreglo(fu)).append('\t').append(arreglo(new ArrayList<>(es))).append('\t').append(arreglo(pares)).append("\t\n");
        d.fin();
        filasMiembros++;
    }

    /**
     * Línea COPY de la membresía de una partición (A7.1): versión, corte, mes, paciente y su grupo. Fuerzas y ESM van vacíos: el filtro por
     * GCF o GRC solo cruza (mes, paciente) con los hechos, que ya llevan el alcance; las tarjetas de las particiones leen la serie.
     */
    private void miembroParticion(VersionCohorte v, int pid, MesBase mb, String grupo) {
        if (grupo == null || grupo.isEmpty()) {
            return;
        }
        Volcado d = miembros.get(v.indice());
        d.linea().append(v.id()).append('\t').append(corte).append('\t').append(AnaliticaCohortes.deOrdinal(mb.mes).atDay(1)).append('\t')
                .append(pid).append("\t{}\t{}\t{}\t").append(grupo.replace("\t", " ").replace("\\", "")).append('\n');
        d.fin();
        filasMiembros++;
    }

    /** Arreglo de texto para COPY: los códigos de Fuerza y ESM no llevan comas, llaves, comillas ni espacios (se citan por si acaso). */
    private static String arreglo(List<String> l) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(l.get(i).replace("\\", "").replace("\"", "").replace("\t", " ")).append('"');
        }
        return sb.append('}').toString();
    }

    // ---------- Máscaras de los átomos (por medicamento, diagnóstico y variable de la operación) ----------

    private long[] mascaraMed(Long id) {
        return mascaraMed.computeIfAbsent(id, k -> {
            long[] r = new long[versiones.size()];
            CatalogoClinico.Med m = cat.med(k);
            for (VersionCohorte v : versiones) {
                for (int a = 0; a < v.atomos().size(); a++) {
                    if (m != null && cumpleMed(v.atomos().get(a), m)) {
                        r[v.indice()] |= 1L << a;
                    }
                }
            }
            return r;
        });
    }

    private boolean cumpleMed(Codigos k, CatalogoClinico.Med m) {
        return switch (k.tipo()) {
            case ATC -> AnaliticaCohortes.conPrefijo(m.atc(), k.valores());
            case PRINCIPIO -> AnaliticaAlertas.enLista(m.principio(), Set.copyOf(k.valores()));
            case MEDICAMENTO -> m.cum() != null && k.valores().contains(m.cum());
            case LISTA -> k.valores().stream().anyMatch(l -> switch (Lista.de(l)) {
                case ESTRECHO_MARGEN -> AnaliticaAlertas.enLista(m.principio(), cat.estrecho);
                case CONTROL_ESPECIAL -> AnaliticaAlertas.enLista(m.principio(), cat.control);
                case HUERFANAS -> false;
            });
            default -> false;
        };
    }

    private long[] mascaraCie(String cie) {
        return mascaraCie.computeIfAbsent(cie, k -> {
            long[] r = new long[versiones.size()];
            for (VersionCohorte v : versiones) {
                for (int a = 0; a < v.atomos().size(); a++) {
                    Codigos at = v.atomos().get(a);
                    boolean si = at.tipo() == AnaliticaCohortes.TipoCodigo.CIE10 && AnaliticaCohortes.conPrefijo(k, at.valores())
                            || at.tipo() == AnaliticaCohortes.TipoCodigo.LISTA && at.valores().contains(Lista.HUERFANAS.codigo())
                                    && AnaliticaCohortes.conPrefijo(k, cat.huerfanas);
                    if (si) {
                        r[v.indice()] |= 1L << a;
                    }
                }
            }
            return r;
        });
    }

    private long[] mascaraOp(String op) {
        return mascaraOp.computeIfAbsent(op, k -> {
            long[] r = new long[versiones.size()];
            String valor = k.substring(2);
            AnaliticaCohortes.TipoCodigo t = switch (k.charAt(0)) {
                case 'F' -> AnaliticaCohortes.TipoCodigo.FUERZA;
                case 'E' -> AnaliticaCohortes.TipoCodigo.ESM;
                default -> AnaliticaCohortes.TipoCodigo.MODALIDAD;
            };
            for (VersionCohorte v : versiones) {
                for (int a = 0; a < v.atomos().size(); a++) {
                    Codigos at = v.atomos().get(a);
                    if (at.tipo() == t && at.valores().stream().anyMatch(x -> x.equalsIgnoreCase(valor))) {
                        r[v.indice()] |= 1L << a;
                    }
                }
            }
            return r;
        });
    }

    private String[] cronico(String cie) {
        return cronicoCie.computeIfAbsent(cie, k -> {
            String[] r = new String[versiones.size()];
            for (VersionCohorte v : versiones) {
                r[v.indice()] = AnaliticaCohortes.categoriaCronica(k, v.cronicos());
            }
            return r;
        });
    }

    // ---------- Alertas de la secuencia del paciente ----------

    private boolean activo(Tipo t) {
        return activos.contains(t);
    }

    /** Corre un tipo de secuencia aislado: si lanza, queda en error y se apaga para el resto del recorrido. */
    private void aislado(Tipo t, Runnable r) {
        if (!activo(t)) {
            return;
        }
        try {
            r.run();
        } catch (RuntimeException e) {
            activos.remove(t);
            fallosTipo.put(t, "error: " + e.getClass().getSimpleName());
            LOG.error("[analitica] El detector de secuencia {} falló; los demás siguen", t.codigo(), e);
        }
    }

    private void secuencia(int pid, List<Fila> filas) {
        if (!activo(Tipo.DUPLICIDAD) && !activo(Tipo.INTERACCION) && !activo(Tipo.MARCA_ESTRECHO_MARGEN) && !activo(Tipo.FUERA_INDICACION)) {
            return;
        }
        List<Dispensa> d = new ArrayList<>(filas.size());
        for (Fila f : filas) {
            CatalogoClinico.Med m = cat.med(f.medicamento);
            d.add(new Dispensa(f.dia, m == null ? null : m.atc(), m == null ? null : m.principio(), f.cum, f.esm, f.fuerza));
        }
        Map<ClaveAlerta, Set<Integer>> propias = new LinkedHashMap<>();
        aislado(Tipo.DUPLICIDAD, () -> {
            sonda.secuencia(Tipo.DUPLICIDAD);
            Parametros p = parametros.get(Tipo.DUPLICIDAD);
            int nivelAtc = p.extra("nivelAtc", "4").intValue();
            int largo = switch (nivelAtc) {
                case 1 -> 1;
                case 2 -> 3;
                case 3 -> 4;
                default -> 5;
            };
            for (int[] par : AnaliticaAlertas.duplicidades(d, p.extra("dias", "30").intValue(), largo, Set.copyOf(p.exclusiones()))) {
                String grupo = d.get(par[1]).atc().substring(0, largo);
                marcar(propias, p, Tipo.DUPLICIDAD, "atc" + nivelAtc, grupo, filas.get(par[1]), par);
            }
        });
        aislado(Tipo.INTERACCION, () -> {
            sonda.secuencia(Tipo.INTERACCION);
            Parametros p = parametros.get(Tipo.INTERACCION);
            for (int[] par : AnaliticaAlertas.interacciones(d, p.extra("dias", "30").intValue(), cat.interacciones)) {
                String valor = AnaliticaAlertas.par(d.get(par[0]).principio(), d.get(par[1]).principio());
                if (!p.exclusiones().contains(valor)) {
                    marcar(propias, p, Tipo.INTERACCION, "par", valor, filas.get(par[1]), par);
                }
            }
        });
        aislado(Tipo.MARCA_ESTRECHO_MARGEN, () -> {
            sonda.secuencia(Tipo.MARCA_ESTRECHO_MARGEN);
            Parametros p = parametros.get(Tipo.MARCA_ESTRECHO_MARGEN);
            Set<String> lista = new HashSet<>();
            for (Dispensa x : d) {
                if (AnaliticaAlertas.enLista(x.principio(), cat.estrecho) && !p.exclusiones().contains(x.principio())) {
                    lista.add(x.principio());
                }
            }
            if (!lista.isEmpty()) {
                for (int j : AnaliticaAlertas.cambiosDeMarca(d, lista)) {
                    int i = j - 1;
                    while (i >= 0 && !d.get(j).principio().equals(d.get(i).principio())) {
                        i--;
                    }
                    marcar(propias, p, Tipo.MARCA_ESTRECHO_MARGEN, "principio", d.get(j).principio(), filas.get(j), i >= 0 ? new int[] {i, j} : new int[] {j});
                }
            }
        });
        aislado(Tipo.FUERA_INDICACION, () -> {
            sonda.secuencia(Tipo.FUERA_INDICACION);
            Parametros p = parametros.get(Tipo.FUERA_INDICACION);
            for (int j = 0; j < filas.size(); j++) {
                Fila f = filas.get(j);
                String atc = d.get(j).atc();
                if (AnaliticaAlertas.fueraDeIndicacion(atc, f.cie, cat.indicaciones, p.exclusiones())) {
                    marcar(propias, p, Tipo.FUERA_INDICACION, "atc5", atc, f, new int[] {j});
                }
            }
        });
        for (Map.Entry<ClaveAlerta, Set<Integer>> e : propias.entrySet()) {
            if (!activo(e.getKey().tipo())) {
                continue;
            }
            Acum a = alertas.computeIfAbsent(e.getKey(), x -> new Acum());
            a.pacientes.add(pid);
            for (int idx : e.getValue()) {
                Fila f = filas.get(idx);
                a.registros++;
                a.gasto += f.valor;
                a.esms.add(f.esm);
                a.fuerzas.add(f.fuerza);
                if (f.cum != null) {
                    a.cums.add(f.cum);
                }
            }
        }
    }

    /** Anota las filas del evento en cada nivel configurado (nacional, la Fuerza o el ESM —con su Fuerza— de la dispensación que lo dispara). */
    private void marcar(Map<ClaveAlerta, Set<Integer>> propias, Parametros p, Tipo t, String dimension, String valor, Fila f, int[] filasEvento) {
        for (String nivel : p.niveles()) {
            String codigo = switch (nivel) {
                case "fuerza" -> f.fuerza;
                case "esm" -> f.fuerza + "|" + f.esm;
                default -> "";
            };
            Set<Integer> s = propias.computeIfAbsent(new ClaveAlerta(t, nivel, codigo, dimension, valor, f.mes), x -> new TreeSet<>());
            for (int i : filasEvento) {
                s.add(i);
            }
        }
    }
}
