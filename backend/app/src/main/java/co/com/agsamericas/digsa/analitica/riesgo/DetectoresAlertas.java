package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Corte;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Incremento;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Parametros;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Severidad;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Tipo;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.UmbralCantidad;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Detectores de alertas sobre conteos agregados del corte (incrementos de gasto y consumo, ingresos a alto costo y polimedicados,
 * formulación inusual, presentación inconsistente, desabastecimiento aproximado y concentración de prescripciones) y conversión de lo que
 * acumuló el recorrido de pacientes en alertas candidatas con su contexto.
 *
 * <p>Aislamiento (revisión A4 2026-10-24): cada detector, la base por medicamento y el conteo de pacientes corren en un punto de guardado de
 * la transacción de lectura (PROPAGATION_NESTED). En PostgreSQL una sentencia que falla deja abortada la transacción entera; con el punto de
 * guardado se revierte solo lo del detector, su tipo queda «error» y los demás tipos (y las cohortes, ya recorridas) siguen.
 */
final class DetectoresAlertas {

    private static final Logger LOG = LoggerFactory.getLogger(DetectoresAlertas.class);

    /** Alerta candidata con todo su contexto (sin datos personales). {@code meds}: medicamentos para contar pacientes (nulo = no hace falta). */
    static final class Candidata {
        Tipo tipo;
        String nivel;
        String fuerza;
        String esm;
        Long punto;
        String dimension;
        String dimensionTexto;
        String valor;
        String valorTexto;
        Long cohorteId;
        /** Mes de la alerta (ordinal) y primer mes del periodo (igual salvo en desabastecimiento: incluye la base). */
        int mes;
        int mesDesde;
        Severidad severidad;
        BigDecimal umbral;
        BigDecimal observado;
        BigDecimal referencia;
        long registros;
        long pacientes = -1;
        BigDecimal gasto;
        /** Frase propia del tipo; los conteos de pacientes van marcados (AnaliticaAlertas.marca) y se escriben al mostrarlos. */
        String detalle;
        String fuente;
        Map<String, Object> seleccion;
        List<Long> meds;

        String nivelTexto() {
            return switch (nivel) {
                case "fuerza" -> "Fuerza " + fuerza;
                case "esm" -> "ESM " + esm + " (" + fuerza + ")";
                default -> "Nacional";
            };
        }

        /** Código del nivel en la llave: la Fuerza, o «Fuerza|ESM» (un ESM compartido tiene una alerta por Fuerza). */
        String codigoNivel() {
            return switch (nivel) {
                case "fuerza" -> fuerza;
                case "esm" -> fuerza + "|" + esm;
                default -> "";
            };
        }

        String periodoTexto() {
            YearMonth ym = AnaliticaCohortes.deOrdinal(mes);
            return AnaliticaConsulta.etiquetaPeriodo(ym.atDay(1), AnaliticaConsulta.Granularidad.MES);
        }
    }

    private final JdbcClient j;
    private final TransactionTemplate anidada;
    private final Corte corte;
    private final CatalogoClinico cat;
    private final Map<Tipo, Parametros> parametros;
    private final MotorRiesgo motor;
    private final ObjectMapper json;
    private final RecalculoRiesgo.Sonda sonda;
    private final Long[] cargues;
    private final int primerMes;
    private final int ultimoMes;
    final List<Candidata> candidatas = new ArrayList<>();
    final Map<String, Map<String, Object>> porTipo = new LinkedHashMap<>();
    /** Por (mes, Fuerza, medicamento): {gasto, unidades, dispensaciones}. Base de incrementos y presentación. */
    private final Map<String, long[]> porMed = new HashMap<>();
    /** CUM ⇒ principio activo (para el texto del valor). */
    private final Map<String, String> principioDeCum = new HashMap<>();

    DetectoresAlertas(JdbcClient j, TransactionTemplate anidada, Corte corte, CatalogoClinico cat, Map<Tipo, Parametros> parametros, MotorRiesgo motor,
                      ObjectMapper json, RecalculoRiesgo.Sonda sonda) {
        this.j = j;
        this.anidada = anidada;
        this.corte = corte;
        this.cat = cat;
        this.parametros = parametros;
        this.motor = motor;
        this.json = json;
        this.sonda = sonda;
        this.cargues = corte.cargues().toArray(Long[]::new);
        this.primerMes = AnaliticaCohortes.ordinal(corte.datosDesde().getYear(), corte.datosDesde().getMonthValue());
        this.ultimoMes = AnaliticaCohortes.ordinal(corte.datosHasta().getYear(), corte.datosHasta().getMonthValue());
        for (CatalogoClinico.Med m : cat.meds.values()) {
            if (m.cum() != null && m.principio() != null) {
                principioDeCum.merge(m.cum(), m.principio(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
        }
    }

    private static final String MES = "(extract(year FROM x.fecha)::int * 12 + extract(month FROM x.fecha)::int - 1)";
    private static final Set<Tipo> DE_POR_MED = EnumSet.of(Tipo.INCREMENTO_GASTO, Tipo.INCREMENTO_CONSUMO, Tipo.PRESENTACION_INCONSISTENTE);

    /** Motivo de «no evaluado» de un tipo (falta su fuente o su campo), o nulo si se puede evaluar. */
    static String noEvaluable(Tipo t, CatalogoClinico cat) {
        return AnaliticaAlertas.noEvaluable(t, new AnaliticaAlertas.Fuentes(!cat.interacciones.isEmpty(), !cat.indicaciones.isEmpty(), !cat.estrecho.isEmpty(),
                !cat.vitales.isEmpty(), cat.profesional)).orElse(null);
    }

    /** Corre un bloque en un punto de guardado: si lanza, se revierte solo lo suyo y la transacción de lectura sigue sana. */
    private void enPuntoDeGuardado(Runnable r) {
        anidada.executeWithoutResult(s -> r.run());
    }

    private static Map<String, Object> error(RuntimeException e, String que) {
        return Map.of("estado", "error", "motivo", "error: " + que + " (" + e.getClass().getSimpleName() + ")");
    }

    /** Corre todos los detectores agregados y convierte lo acumulado del recorrido. {@code cohortes}: código ⇒ (id, índice de versión). */
    void detectar(Set<Tipo> evaluables, Map<String, long[]> cohortes) {
        RuntimeException sinBase = null;
        if (evaluables.stream().anyMatch(DE_POR_MED::contains)) {
            try {
                enPuntoDeGuardado(this::cargarPorMed);
            } catch (RuntimeException e) {
                sinBase = e;
                porMed.clear();
                LOG.error("[analitica] No se pudo leer la base por medicamento (incrementos y presentación quedan en error)", e);
            }
        }
        for (Tipo t : Tipo.values()) {
            Parametros p = parametros.get(t);
            if (p == null) {
                porTipo.put(t.codigo(), Map.of("estado", "no_evaluado", "motivo", "no evaluado: sin parámetros sembrados"));
                continue;
            }
            if (!p.activo()) {
                porTipo.put(t.codigo(), Map.of("estado", "inactivo"));
                continue;
            }
            String motivo = noEvaluable(t, cat);
            if (motivo != null) {
                porTipo.put(t.codigo(), Map.of("estado", "no_evaluado", "motivo", motivo));
                continue;
            }
            if (sinBase != null && DE_POR_MED.contains(t)) {
                porTipo.put(t.codigo(), error(sinBase, "no se pudo leer la base por medicamento"));
                continue;
            }
            if (motor.fallosTipo.containsKey(t)) {
                porTipo.put(t.codigo(), Map.of("estado", "error", "motivo", motor.fallosTipo.get(t)));
                continue;
            }
            int antes = candidatas.size();
            try {
                int[] excluidas = {0};
                enPuntoDeGuardado(() -> {
                    sonda.detector(t, j);
                    switch (t) {
                        case INCREMENTO_GASTO, INCREMENTO_CONSUMO -> incrementos(t, p);
                        case ALTO_COSTO, POLIMEDICADOS -> ingresos(t, p, cohortes.get(t == Tipo.ALTO_COSTO ? "alto_costo" : "polimedicados"));
                        case DUPLICIDAD, INTERACCION, MARCA_ESTRECHO_MARGEN, FUERA_INDICACION -> secuencia(t, p);
                        case FORMULACION_INUSUAL -> formulacion(p);
                        case PRESENTACION_INCONSISTENTE -> presentacion(p);
                        case DESABASTECIMIENTO -> desabastecimiento(p);
                        case CONCENTRACION -> concentracion(p);
                    }
                    excluidas[0] = excluir(p, antes);
                });
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("estado", "evaluado");
                r.put("alertas", candidatas.size() - antes);
                if (excluidas[0] > 0) {
                    r.put("excluidas", excluidas[0]);
                }
                porTipo.put(t.codigo(), r);
            } catch (RuntimeException e) {
                while (candidatas.size() > antes) {
                    candidatas.removeLast();
                }
                porTipo.put(t.codigo(), error(e, "el detector falló"));
                LOG.error("[analitica] El detector {} falló; los demás siguen", t.codigo(), e);
            }
        }
        try {
            enPuntoDeGuardado(this::contarPacientes);
        } catch (RuntimeException e) {
            // Sin conteo de pacientes la alerta no tiene su contexto completo: los tipos afectados quedan en error y sus candidatas no se escriben
            LOG.error("[analitica] No se pudieron contar los pacientes de las alertas", e);
            Set<Tipo> afectados = EnumSet.noneOf(Tipo.class);
            candidatas.removeIf(c -> {
                if (c.pacientes < 0) {
                    afectados.add(c.tipo);
                    return true;
                }
                return false;
            });
            afectados.forEach(t -> porTipo.put(t.codigo(), error(e, "no se pudieron contar los pacientes")));
        }
    }

    /**
     * Exclusiones parametrizables: en las dimensiones ATC, por prefijo; en las demás, el valor exacto (sin tildes ni mayúsculas). Devuelve
     * cuántas candidatas excluyó (va en el resumen por tipo).
     */
    private int excluir(Parametros p, int desde) {
        if (p.exclusiones().isEmpty()) {
            return 0;
        }
        List<String> ex = p.exclusiones().stream().map(AnaliticaHomologacion::normalizarTexto).toList();
        int antes = candidatas.size();
        List<Candidata> nuevas = new ArrayList<>(candidatas.subList(desde, candidatas.size()));
        while (candidatas.size() > desde) {
            candidatas.removeLast();
        }
        for (Candidata c : nuevas) {
            String v = AnaliticaHomologacion.normalizarTexto(c.valor);
            boolean atc = c.dimension.startsWith("atc");
            boolean fuera = ex.stream().anyMatch(x -> atc ? v.startsWith(x) : v.equals(x));
            if (!fuera) {
                candidatas.add(c);
            }
        }
        return antes - candidatas.size();
    }

    // ---------- Incrementos de gasto y consumo ----------

    private void cargarPorMed() {
        j.sql("SELECT " + MES + " AS mes, x.fuerza, x.medicamento_id, sum(x.valor_centavos), sum(x.cantidad_milesimas), count(*) FROM ana_hechos_tablero x"
                + " WHERE x.cargue_id = ANY(:c) GROUP BY 1, 2, 3").param("c", cargues).query((rs, n) -> {
                    int mes = rs.getInt(1);
                    String fuerza = rs.getString(2);
                    long med = rs.getLong(3);
                    boolean sinMed = rs.wasNull();
                    porMed.put(mes + "|" + fuerza + "|" + (sinMed ? "" : med), new long[] {rs.getLong(4), rs.getLong(5), rs.getLong(6)});
                    return null;
                }).list();
    }

    /** Serie de una celda (nivel, código, dimensión, valor): gasto, unidades y dispensaciones por mes, y los medicamentos que la forman. */
    private static final class SerieDim {
        final String nivel;
        final String codigo;
        final String dimension;
        final String valor;
        final long[] gasto;
        final long[] unidades;
        final long[] dispensaciones;
        final TreeSet<Long> meds = new TreeSet<>();

        SerieDim(String nivel, String codigo, String dimension, String valor, int meses) {
            this.nivel = nivel;
            this.codigo = codigo;
            this.dimension = dimension;
            this.valor = valor;
            this.gasto = new long[meses];
            this.unidades = new long[meses];
            this.dispensaciones = new long[meses];
        }
    }

    private void incrementos(Tipo t, Parametros p) {
        int meses = ultimoMes - primerMes + 1;
        Map<String, SerieDim> series = new TreeMap<>();
        for (Map.Entry<String, long[]> e : porMed.entrySet()) {
            String[] k = e.getKey().split("\\|", -1);
            int i = Integer.parseInt(k[0]) - primerMes;
            if (i < 0 || i >= meses) {
                continue;
            }
            String fuerza = k[1];
            Long medId = k[2].isEmpty() ? null : Long.valueOf(k[2]);
            CatalogoClinico.Med m = cat.med(medId);
            List<String[]> dims = new ArrayList<>();
            if (m != null && m.atc() != null) {
                for (int largo : new int[] {1, 3, 4}) {
                    String a = m.atcNivel(largo);
                    if (a != null) {
                        dims.add(new String[] {"atc" + (largo == 1 ? 1 : largo == 3 ? 2 : 3), a});
                    }
                }
            }
            if (m != null && m.principio() != null) {
                dims.add(new String[] {"principio", m.principio()});
            }
            for (String[] d : dims) {
                if (p.niveles().contains("nacional")) {
                    sumarSerie(series, "nacional", "", d[0], d[1], i, e.getValue(), medId, meses);
                }
                if (p.niveles().contains("fuerza")) {
                    sumarSerie(series, "fuerza", fuerza, d[0], d[1], i, e.getValue(), medId, meses);
                }
            }
            if (p.niveles().contains("nacional")) {
                // La dimensión Fuerza a nivel nacional: sin medicamentos (los pacientes se cuentan por la Fuerza)
                sumarSerie(series, "nacional", "", "fuerza", fuerza, i, e.getValue(), null, meses);
            }
        }
        if (p.niveles().contains("nacional")) {
            // La dimensión ESM a nivel nacional (los niveles admitidos de este tipo son nacional y Fuerza)
            j.sql("SELECT " + MES + " AS mes, x.esm_codigo, sum(x.valor_centavos), sum(x.cantidad_milesimas), count(*) FROM ana_hechos_tablero x"
                    + " WHERE x.cargue_id = ANY(:c) GROUP BY 1, 2").param("c", cargues).query((rs, n) -> {
                        int i = rs.getInt(1) - primerMes;
                        if (i >= 0 && i < meses) {
                            sumarSerie(series, "nacional", "", "esm", rs.getString(2), i, new long[] {rs.getLong(3), rs.getLong(4), rs.getLong(5)}, null, meses);
                        }
                        return null;
                    }).list();
        }
        boolean gasto = t == Tipo.INCREMENTO_GASTO;
        for (SerieDim s : series.values()) {
            long[] valores = gasto ? s.gasto : s.unidades;
            for (int i = 0; i < meses; i++) {
                if (!AnaliticaAlertas.conVolumen(s.dispensaciones, i, p)) {
                    continue;
                }
                Optional<Incremento> inc = AnaliticaAlertas.incremento(valores, i, p, gasto ? 2 : 3);
                if (inc.isEmpty()) {
                    continue;
                }
                Optional<Severidad> sev = AnaliticaAlertas.severidad(inc.get().variacionPct(), p);
                if (sev.isEmpty() || s.dispensaciones[i] == 0) {
                    continue;
                }
                Candidata c = base(t, s.nivel, s.codigo, s.dimension, s.valor, primerMes + i);
                c.severidad = sev.get();
                c.umbral = AnaliticaAlertas.umbral(sev.get(), p);
                c.observado = inc.get().variacionPct();
                c.referencia = inc.get().referencia();
                c.registros = s.dispensaciones[i];
                c.gasto = BigDecimal.valueOf(s.gasto[i], 2);
                c.detalle = detalleIncremento(s, i, p, inc.get(), gasto);
                // Fuerza y ESM como dimensión se cuentan por su código, no por la lista de medicamentos (consulta por igualdad)
                c.meds = s.meds.isEmpty() || "fuerza".equals(s.dimension) || "esm".equals(s.dimension) ? null : List.copyOf(s.meds);
                c.seleccion = seleccion(c, s.meds);
                candidatas.add(c);
            }
        }
    }

    private static void sumarSerie(Map<String, SerieDim> series, String nivel, String codigo, String dim, String valor, int i, long[] v, Long med, int meses) {
        SerieDim s = series.computeIfAbsent(nivel + "|" + codigo + "|" + dim + "|" + valor, x -> new SerieDim(nivel, codigo, dim, valor, meses));
        s.gasto[i] += v[0];
        s.unidades[i] += v[1];
        s.dispensaciones[i] += v[2];
        if (med != null) {
            s.meds.add(med);
        }
    }

    private static String detalleIncremento(SerieDim s, int i, Parametros p, Incremento inc, boolean gasto) {
        int v = p.ventana();
        long g0 = 0;
        long u0 = 0;
        for (int k = i - v; k < i; k++) {
            g0 += s.gasto[k];
            u0 += s.unidades[k];
        }
        String causa = "";
        if (u0 > 0 && s.unidades[i] > 0 && g0 > 0) {
            BigDecimal vu0 = BigDecimal.valueOf(g0).divide(BigDecimal.valueOf(u0), 8, RoundingMode.HALF_UP);
            BigDecimal vu1 = BigDecimal.valueOf(s.gasto[i]).divide(BigDecimal.valueOf(s.unidades[i]), 8, RoundingMode.HALF_UP);
            BigDecimal var = vu1.subtract(vu0).multiply(BigDecimal.valueOf(100)).divide(vu0, 2, RoundingMode.HALF_UP);
            causa = " El valor unitario promedio varió " + AnaliticaConsulta.porcentaje(var) + ": el aumento es "
                    + (var.abs().compareTo(BigDecimal.TEN) < 0 ? "por volumen." : "por precio y volumen.");
        }
        return (gasto ? "Gasto" : "Unidades") + " del mes " + AnaliticaConsulta.decimal(inc.observado()) + " frente a una media de "
                + AnaliticaConsulta.decimal(inc.referencia()) + " en los " + v + " meses previos (variación " + AnaliticaConsulta.porcentaje(inc.variacionPct())
                + "; z robusta " + AnaliticaConsulta.decimal(inc.zRobusta()) + " sobre " + p.extra("mesesBase", "6").intValue() + " meses)." + causa;
    }

    // ---------- Ingresos a alto costo y polimedicados (de la serie de la cohorte) ----------

    private void ingresos(Tipo t, Parametros p, long[] cohorte) {
        if (cohorte == null) {
            throw new IllegalStateException("La cohorte de " + t.codigo() + " no está activa o no se evaluó");
        }
        int v = (int) cohorte[1];
        if (motor.fallosVersion.containsKey(v)) {
            throw new IllegalStateException("La cohorte de " + t.codigo() + " no se evaluó: " + motor.fallosVersion.get(v));
        }
        // Solo meses con historia suficiente: después del calentamiento de la cohorte y con la ventana de referencia también fuera de él
        int desde = motor.inicioIngresos(v) + p.ventana();
        Map<String, long[]> previos = new HashMap<>();
        List<Map.Entry<MotorRiesgo.Llave, long[]>> celdas = new ArrayList<>();
        for (Map.Entry<MotorRiesgo.Llave, long[]> e : motor.serie.entrySet()) {
            if (e.getKey().version() == v) {
                celdas.add(e);
                previos.put(e.getKey().celda() + "|" + e.getKey().mes(), e.getValue());
            }
        }
        celdas.sort(java.util.Comparator.comparing((Map.Entry<MotorRiesgo.Llave, long[]> e) -> e.getKey().celda()).thenComparing(e -> e.getKey().mes()));
        for (Map.Entry<MotorRiesgo.Llave, long[]> e : celdas) {
            String celda = e.getKey().celda();
            String nivel = celda.startsWith("F|") ? "fuerza" : celda.startsWith("E|") ? "esm" : "nacional";
            if (!p.niveles().contains(nivel) || e.getKey().mes() < desde) {
                continue;
            }
            long ingresos = e.getValue()[1];
            Optional<Severidad> sev = AnaliticaAlertas.severidad(BigDecimal.valueOf(ingresos), p);
            if (sev.isEmpty() || e.getValue()[3] == 0) {
                continue;
            }
            long suma = 0;
            for (int k = 1; k <= p.ventana(); k++) {
                long[] prev = previos.get(celda + "|" + (e.getKey().mes() - k));
                suma += prev == null ? 0 : prev[1];
            }
            BigDecimal referencia = BigDecimal.valueOf(suma).divide(BigDecimal.valueOf(p.ventana()), 2, RoundingMode.HALF_UP);
            if (!AnaliticaAlertas.ingresoAtipico(ingresos, referencia, p)) {
                continue;
            }
            String codigo = "nacional".equals(nivel) ? "" : celda.substring(2);
            Candidata c = base(t, nivel, codigo, nivel, "x", e.getKey().mes());
            c.valor = "esm".equals(nivel) ? c.esm : "fuerza".equals(nivel) ? c.fuerza : "Nacional";
            c.valorTexto = c.valor;
            c.cohorteId = cohorte[0];
            c.severidad = sev.get();
            c.umbral = AnaliticaAlertas.umbral(sev.get(), p);
            c.observado = BigDecimal.valueOf(ingresos);
            c.referencia = referencia;
            c.registros = e.getValue()[3];
            c.pacientes = ingresos;
            c.gasto = BigDecimal.valueOf(e.getValue()[5], 2);
            c.detalle = AnaliticaAlertas.marca(ingresos) + " pacientes ingresaron a la cohorte en el mes (" + AnaliticaAlertas.marca(e.getValue()[0])
                    + " miembros en el nivel; " + AnaliticaAlertas.marca(e.getValue()[2]) + " salidas; ingreso = sin membresía en los meses de gracia previos)."
                    + " El gasto y los registros son los de los miembros del mes en el nivel.";
            c.seleccion = seleccion(c, null);
            candidatas.add(c);
        }
    }

    // ---------- Secuencia del paciente (acumulada en el recorrido) ----------

    private void secuencia(Tipo t, Parametros p) {
        // En un orden estable: dos ejecuciones sobre el mismo corte generan igual
        List<MotorRiesgo.ClaveAlerta> claves = motor.alertas.keySet().stream().filter(k -> k.tipo() == t)
                .sorted(java.util.Comparator.comparing(MotorRiesgo.ClaveAlerta::nivel).thenComparing(MotorRiesgo.ClaveAlerta::codigoNivel)
                        .thenComparing(MotorRiesgo.ClaveAlerta::dimension).thenComparing(MotorRiesgo.ClaveAlerta::valor)
                        .thenComparingInt(MotorRiesgo.ClaveAlerta::mes)).toList();
        for (MotorRiesgo.ClaveAlerta k : claves) {
            MotorRiesgo.Acum a = motor.alertas.get(k);
            Optional<Severidad> sev = AnaliticaAlertas.severidad(BigDecimal.valueOf(a.pacientes.size()), p);
            if (sev.isEmpty()) {
                continue;
            }
            Candidata c = base(t, k.nivel(), k.codigoNivel(), k.dimension(), k.valor(), k.mes());
            c.severidad = sev.get();
            c.umbral = AnaliticaAlertas.umbral(sev.get(), p);
            c.observado = BigDecimal.valueOf(a.pacientes.size());
            c.registros = a.registros;
            c.pacientes = a.pacientes.size();
            c.gasto = BigDecimal.valueOf(a.gasto, 2);
            String n = AnaliticaAlertas.marca(a.pacientes.size());
            c.detalle = switch (t) {
                case DUPLICIDAD -> n + " pacientes recibieron dos principios activos distintos del grupo " + k.valor() + " con cobertura superpuesta ("
                        + p.extra("dias", "30").intValue() + " días; el insumo no trae días de tratamiento), en " + a.esms.size() + " ESM.";
                case INTERACCION -> n + " pacientes recibieron " + k.valor() + " con cobertura superpuesta (" + p.extra("dias", "30").intValue()
                        + " días), en " + a.esms.size() + " ESM.";
                case MARCA_ESTRECHO_MARGEN -> n + " pacientes cambiaron de CUM (marca o titular) entre dispensaciones consecutivas de "
                        + k.valor() + ", principio de estrecho margen terapéutico.";
                default -> a.registros + " dispensaciones de " + k.valor() + " (" + n + " pacientes) con un diagnóstico que no está entre los admitidos "
                        + "para ese ATC.";
            };
            c.fuente = switch (t) {
                case INTERACCION -> cat.fuenteInteracciones;
                case MARCA_ESTRECHO_MARGEN -> cat.fuenteEstrecho;
                case FUERA_INDICACION -> cat.fuenteIndicaciones;
                default -> null;
            };
            Map<String, Object> sel = seleccion(c, null);
            if (!a.cums.isEmpty() && a.cums.size() <= AnaliticaConsulta.MAX_VALORES) {
                sel.put("cum", List.copyOf(a.cums));
            }
            if ("nacional".equals(k.nivel()) && a.esms.size() <= AnaliticaConsulta.MAX_VALORES) {
                sel.put("esm", List.copyOf(a.esms));
            }
            c.seleccion = sel;
            candidatas.add(c);
        }
    }

    // ---------- Formulación inusual ----------

    private void formulacion(Parametros p) {
        Map<String, TreeMap<Long, Long>> frecuencias = new TreeMap<>();
        j.sql("SELECT x.cum, x.cantidad_milesimas, count(*) FROM ana_hechos_tablero x WHERE x.cargue_id = ANY(:c) AND x.cum IS NOT NULL GROUP BY 1, 2")
                .param("c", cargues).query((rs, n) -> frecuencias.computeIfAbsent(rs.getString(1), x -> new TreeMap<>()).put(rs.getLong(2), rs.getLong(3)))
                .list();
        Map<String, UmbralCantidad> umbrales = new TreeMap<>();
        frecuencias.forEach((cum, f) -> AnaliticaAlertas.umbralCantidad(f, p).ifPresent(u -> umbrales.put(cum, u)));
        if (umbrales.isEmpty()) {
            return;
        }
        List<Map<String, Object>> sel = new ArrayList<>();
        umbrales.forEach((cum, u) -> sel.add(Map.of("cum", cum, "umbral", u.umbral())));
        j.sql("SELECT x.cum, " + MES + " AS mes, count(*), count(DISTINCT x.paciente_id), sum(x.valor_centavos), max(x.cantidad_milesimas)"
                + " FROM ana_hechos_tablero x JOIN jsonb_to_recordset(CAST(:sel AS jsonb)) AS u(cum text, umbral bigint)"
                + " ON x.cum = u.cum COLLATE \"C\" AND x.cantidad_milesimas > u.umbral WHERE x.cargue_id = ANY(:c) GROUP BY 1, 2 ORDER BY 1, 2")
                .param("sel", aJson(sel)).param("c", cargues).query((rs, n) -> {
                    String cum = rs.getString(1);
                    long registros = rs.getLong(3);
                    Optional<Severidad> sev = AnaliticaAlertas.severidad(BigDecimal.valueOf(registros), p);
                    if (sev.isEmpty()) {
                        return null;
                    }
                    UmbralCantidad u = umbrales.get(cum);
                    Candidata c = base(Tipo.FORMULACION_INUSUAL, "nacional", "", "cum", cum, rs.getInt(2));
                    c.valorTexto = cum + principioDeCum(cum);
                    c.severidad = sev.get();
                    c.umbral = AnaliticaAlertas.umbral(sev.get(), p);
                    c.observado = BigDecimal.valueOf(registros);
                    c.referencia = BigDecimal.valueOf(u.mediana(), 3).setScale(2, RoundingMode.HALF_UP);
                    c.registros = registros;
                    c.pacientes = rs.getLong(4);
                    c.gasto = BigDecimal.valueOf(rs.getLong(5), 2);
                    c.detalle = registros + " dispensaciones con más de " + AnaliticaConsulta.unidades(BigDecimal.valueOf(u.umbral(), 3))
                            + " unidades (mediana del CUM " + AnaliticaConsulta.unidades(BigDecimal.valueOf(u.mediana(), 3)) + "; umbral = mediana + "
                            + p.extra("k", "5").stripTrailingZeros().toPlainString() + " · escala robusta, sobre " + u.registros() + " registros); máximo "
                            + AnaliticaConsulta.unidades(BigDecimal.valueOf(rs.getLong(6), 3)) + ".";
                    c.seleccion = seleccion(c, null);
                    c.seleccion.put("cum", List.of(cum));
                    candidatas.add(c);
                    return null;
                }).list();
    }

    private String principioDeCum(String cum) {
        String p = principioDeCum.get(cum);
        return p == null ? "" : " · " + p;
    }

    // ---------- Presentación inconsistente ----------

    private void presentacion(Parametros p) {
        // Registros por medicamento (dato original) en el corte
        Map<Long, Long> registros = new HashMap<>();
        porMed.forEach((k, v) -> {
            String[] x = k.split("\\|", -1);
            if (!x[2].isEmpty()) {
                registros.merge(Long.valueOf(x[2]), v[2], Long::sum);
            }
        });
        // Por CUM, la concentración con más registros es la de referencia; los datos originales con otra son inconsistentes. Más los que la
        // tabla maestra marca «inconsistente» (diferencia en concentración, forma o presentación, R-012/R-015).
        Map<String, Map<String, Long>> firmas = new TreeMap<>();
        for (CatalogoClinico.Med m : cat.meds.values()) {
            if (m.cum() != null && !m.firma().isEmpty() && registros.containsKey(m.id())) {
                firmas.computeIfAbsent(m.cum(), x -> new TreeMap<>()).merge(m.firma(), registros.get(m.id()), Long::sum);
            }
        }
        Map<Long, String> inconsistentes = new TreeMap<>();
        for (CatalogoClinico.Med m : cat.meds.values()) {
            if (!registros.containsKey(m.id())) {
                continue;
            }
            Map<String, Long> f = m.cum() == null ? null : firmas.get(m.cum());
            if (f != null && f.size() > 1) {
                String mayor = f.entrySet().stream().max(java.util.Comparator.comparing((Map.Entry<String, Long> e) -> e.getValue())
                        .thenComparing(Map.Entry::getKey, java.util.Comparator.reverseOrder())).orElseThrow().getKey();
                if (!m.firma().equals(mayor) && !m.firma().isEmpty()) {
                    inconsistentes.put(m.id(), "el CUM " + m.cum() + " llega con concentración " + m.firma() + " frente a " + mayor + " en la mayoría de sus registros");
                    continue;
                }
            }
            if (m.inconsistenteTm()) {
                inconsistentes.put(m.id(), "la tabla maestra marca el dato original como inconsistente con la referencia (concentración, forma o presentación)");
            }
        }
        if (inconsistentes.isEmpty()) {
            return;
        }
        // Por (CUM, mes): registros, gasto y medicamentos inconsistentes
        Map<String, long[]> celdas = new TreeMap<>();
        Map<String, TreeSet<Long>> medsCelda = new HashMap<>();
        porMed.forEach((k, v) -> {
            String[] x = k.split("\\|", -1);
            if (x[2].isEmpty() || !inconsistentes.containsKey(Long.valueOf(x[2]))) {
                return;
            }
            CatalogoClinico.Med m = cat.med(Long.valueOf(x[2]));
            String clave = (m.cum() == null ? "ORIGEN-" + m.id() : m.cum()) + "|" + String.format("%08d", Integer.parseInt(x[0]));
            long[] s = celdas.computeIfAbsent(clave, z -> new long[2]);
            s[0] += v[2];
            s[1] += v[0];
            medsCelda.computeIfAbsent(clave, z -> new TreeSet<>()).add(m.id());
        });
        for (Map.Entry<String, long[]> e : celdas.entrySet()) {
            String[] k = e.getKey().split("\\|");
            Optional<Severidad> sev = AnaliticaAlertas.severidad(BigDecimal.valueOf(e.getValue()[0]), p);
            if (sev.isEmpty()) {
                continue;
            }
            Candidata c = base(Tipo.PRESENTACION_INCONSISTENTE, "nacional", "", "cum", k[0], Integer.parseInt(k[1]));
            c.valorTexto = k[0] + principioDeCum(k[0]);
            c.severidad = sev.get();
            c.umbral = AnaliticaAlertas.umbral(sev.get(), p);
            c.observado = BigDecimal.valueOf(e.getValue()[0]);
            c.registros = e.getValue()[0];
            c.gasto = BigDecimal.valueOf(e.getValue()[1], 2);
            c.meds = List.copyOf(medsCelda.get(e.getKey()));
            c.detalle = "Registros con presentación inconsistente: " + String.join("; ", c.meds.stream().map(inconsistentes::get).distinct().toList()) + ".";
            c.seleccion = seleccion(c, null);
            if (!k[0].startsWith("ORIGEN-")) {
                c.seleccion.put("cum", List.of(k[0]));
            }
            candidatas.add(c);
        }
    }

    // ---------- Desabastecimiento (aproximación por caída de la dispensación de vitales) ----------

    private void desabastecimiento(Parametros p) {
        List<Long> vit = cat.meds.values().stream().filter(m -> AnaliticaAlertas.enLista(m.principio(), cat.vitales)).map(CatalogoClinico.Med::id).toList();
        if (vit.isEmpty()) {
            return;
        }
        int meses = ultimoMes - primerMes + 1;
        Map<String, long[][]> series = new TreeMap<>();
        Map<String, TreeSet<Long>> medsSerie = new HashMap<>();
        // Por (Fuerza, ESM, principio): un ESM compartido tiene una serie por Fuerza (alcance de Sanidad)
        j.sql("SELECT " + MES + " AS mes, x.fuerza, x.esm_codigo, x.medicamento_id, count(*), sum(x.valor_centavos) FROM ana_hechos_tablero x"
                + " WHERE x.cargue_id = ANY(:c) AND x.medicamento_id = ANY(:m) GROUP BY 1, 2, 3, 4").param("c", cargues).param("m", vit.toArray(Long[]::new))
                .query((rs, n) -> {
                    int i = rs.getInt(1) - primerMes;
                    CatalogoClinico.Med m = cat.med(rs.getLong(4));
                    if (i >= 0 && i < meses && m != null) {
                        String k = rs.getString(2) + "|" + rs.getString(3) + "|" + m.principio();
                        long[][] s = series.computeIfAbsent(k, x -> new long[2][meses]);
                        s[0][i] += rs.getLong(5);
                        s[1][i] += rs.getLong(6);
                        medsSerie.computeIfAbsent(k, x -> new TreeSet<>()).add(m.id());
                    }
                    return null;
                }).list();
        for (Map.Entry<String, long[][]> e : series.entrySet()) {
            String[] k = e.getKey().split("\\|", 3);
            for (int i = 0; i < meses; i++) {
                Optional<Incremento> caida = AnaliticaAlertas.caida(e.getValue()[0], i, p);
                if (caida.isEmpty()) {
                    continue;
                }
                Optional<Severidad> sev = AnaliticaAlertas.severidad(caida.get().variacionPct(), p);
                if (sev.isEmpty()) {
                    continue;
                }
                Candidata c = base(Tipo.DESABASTECIMIENTO, "esm", k[0] + "|" + k[1], "principio", k[2], primerMes + i);
                c.severidad = sev.get();
                c.umbral = AnaliticaAlertas.umbral(sev.get(), p);
                c.observado = caida.get().variacionPct();
                c.referencia = caida.get().referencia();
                // Una caída al 100 % no tiene registros del mes: la alerta se sustenta en los del periodo de referencia
                long reg = 0;
                long gasto = 0;
                for (int q = Math.max(0, i - p.ventana()); q <= i; q++) {
                    reg += e.getValue()[0][q];
                    gasto += e.getValue()[1][q];
                }
                c.registros = reg;
                c.gasto = BigDecimal.valueOf(gasto, 2);
                c.meds = List.copyOf(medsSerie.get(e.getKey()));
                c.detalle = "Aproximación: la dispensación de " + k[2] + " (medicamento vital no disponible del INVIMA) cayó " + AnaliticaConsulta.porcentaje(
                        caida.get().variacionPct()) + " frente a la media de los " + p.ventana() + " meses previos. Sin inventarios ni pendientes del OPL "
                        + "(duda 7): confírmelo con el OPL.";
                c.fuente = cat.fuenteVitales;
                c.mesDesde = primerMes + Math.max(0, i - p.ventana());
                c.seleccion = seleccion(c, null);
                c.seleccion.put("desde", AnaliticaCohortes.deOrdinal(c.mesDesde).atDay(1).toString());
                candidatas.add(c);
            }
        }
    }

    // ---------- Concentración de prescripciones ----------

    /**
     * Por (ESM con su Fuerza, mes, principio activo): un profesional que concentra las fórmulas de UN medicamento de control especial o alto
     * costo. La celda por principio hace la participación relativa a ese medicamento en el ESM y el mes, no a todo el grupo: así no se diluye
     * cuando crece el volumen del ESM (falso negativo a 2 M filas en la revisión A4).
     */
    private void concentracion(Parametros p) {
        List<String> atc = p.lista("atcAltoCosto");
        Map<Long, String> meds = new HashMap<>();
        cat.meds.values().stream().filter(m -> m.principio() != null && (AnaliticaCohortes.conPrefijo(m.atc(), atc) || AnaliticaAlertas.enLista(m.principio(),
                cat.control))).forEach(m -> meds.put(m.id(), m.principio()));
        if (meds.isEmpty()) {
            return;
        }
        Map<String, Map<String, Long>> celdas = new TreeMap<>();
        Map<String, long[]> gastos = new HashMap<>();
        Map<String, TreeSet<Long>> medsCelda = new HashMap<>();
        // El profesional (seudónimo) solo se cuenta: nunca sale de la base ni de este método
        j.sql("SELECT " + MES + " AS mes, x.fuerza, x.esm_codigo, x.medicamento_id, x.profesional_seudo, count(*), round(sum(x.valor_total) * 100)::bigint FROM ana_dispensaciones x"
                + " WHERE x.cargue_id = ANY(:c) AND x.medicamento_id = ANY(:m) AND x.profesional_seudo IS NOT NULL GROUP BY 1, 2, 3, 4, 5")
                .param("c", cargues).param("m", meds.keySet().toArray(Long[]::new)).query((rs, n) -> {
                    String k = rs.getString(2) + "|" + rs.getString(3) + "|" + meds.get(rs.getLong(4)) + "|" + String.format("%08d", rs.getInt(1));
                    celdas.computeIfAbsent(k, x -> new HashMap<>()).merge(rs.getString(5), rs.getLong(6), Long::sum);
                    gastos.computeIfAbsent(k, x -> new long[1])[0] += rs.getLong(7);
                    medsCelda.computeIfAbsent(k, x -> new TreeSet<>()).add(rs.getLong(4));
                    return null;
                }).list();
        for (Map.Entry<String, Map<String, Long>> e : celdas.entrySet()) {
            Optional<BigDecimal[]> r = AnaliticaAlertas.concentracion(e.getValue(), p.extra("minFormulas", "20").longValue());
            if (r.isEmpty()) {
                continue;
            }
            Optional<Severidad> sev = AnaliticaAlertas.severidad(r.get()[0], p);
            if (sev.isEmpty()) {
                continue;
            }
            String[] k = e.getKey().split("\\|");
            Candidata c = base(Tipo.CONCENTRACION, "esm", k[0] + "|" + k[1], "principio", k[2], Integer.parseInt(k[3]));
            c.severidad = sev.get();
            c.umbral = AnaliticaAlertas.umbral(sev.get(), p);
            c.observado = r.get()[0];
            c.registros = r.get()[1].longValue();
            c.meds = List.copyOf(medsCelda.get(e.getKey()));
            c.gasto = BigDecimal.valueOf(gastos.get(e.getKey())[0], 2);
            c.detalle = "Un profesional concentra " + r.get()[2].longValue() + " de " + r.get()[1].longValue() + " dispensaciones de " + k[2]
                    + " (control especial o alto costo: ATC " + String.join(", ", atc) + ") del ESM en el mes, entre " + e.getValue().size()
                    + " profesionales (el profesional no se identifica: va seudonimizado en el insumo).";
            c.seleccion = seleccion(c, medsCelda.get(e.getKey()));
            candidatas.add(c);
        }
    }

    // ---------- Apoyo ----------

    /** Candidata base; en el nivel ESM el código es «Fuerza|ESM». */
    private Candidata base(Tipo t, String nivel, String codigo, String dimension, String valor, int mes) {
        Candidata c = new Candidata();
        c.tipo = t;
        c.nivel = nivel;
        c.dimension = dimension;
        c.valor = valor;
        c.valorTexto = valor;
        c.dimensionTexto = textoDimension(dimension);
        c.mes = mes;
        c.mesDesde = mes;
        switch (nivel) {
            case "fuerza" -> c.fuerza = codigo;
            case "esm" -> {
                int i = codigo.indexOf('|');
                c.fuerza = codigo.substring(0, i);
                c.esm = codigo.substring(i + 1);
                c.punto = motor.puntoDeEsm.get(c.esm);
            }
            default -> {
            }
        }
        if ("esm".equals(dimension) && c.esm == null && "nacional".equals(nivel)) {
            c.punto = motor.puntoDeEsm.get(valor);
        }
        return c;
    }

    static String textoDimension(String d) {
        return switch (d) {
            case "atc1" -> "ATC nivel 1";
            case "atc2" -> "ATC nivel 2";
            case "atc3" -> "ATC nivel 3";
            case "atc4" -> "Grupo ATC nivel 4";
            case "atc5" -> "ATC";
            case "principio" -> "Principio activo";
            case "fuerza" -> "Fuerza";
            case "esm" -> "ESM";
            case "par" -> "Par de principios activos";
            case "cum" -> "CUM";
            default -> "Nacional";
        };
    }

    /**
     * Consulta de la trazabilidad (14.9) que contiene los registros de origen de la alerta: tablero general, periodo del mes, el nivel y la
     * dimensión como filtros (ATC y principio por los CUM que los forman, hasta 50).
     */
    private Map<String, Object> seleccion(Candidata c, Set<Long> meds) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("t", "general");
        s.put("corte", corte.cargueId());
        YearMonth ym = AnaliticaCohortes.deOrdinal(c.mes);
        s.put("desde", ym.atDay(1).toString());
        s.put("hasta", ym.atEndOfMonth().toString());
        if (c.fuerza != null && !"nacional".equals(c.nivel)) {
            s.put("fuerza", List.of(c.fuerza));
        }
        if (c.esm != null) {
            s.put("esm", List.of(c.esm));
        }
        switch (c.dimension) {
            case "fuerza" -> s.put("fuerza", List.of(c.valor));
            case "esm" -> s.put("esm", List.of(c.valor));
            default -> {
                if (meds != null) {
                    TreeSet<String> cums = new TreeSet<>();
                    meds.forEach(id -> {
                        CatalogoClinico.Med m = cat.med(id);
                        if (m != null && m.cum() != null) {
                            cums.add(m.cum());
                        }
                    });
                    if (!cums.isEmpty() && cums.size() <= AnaliticaConsulta.MAX_VALORES) {
                        s.put("cum", List.copyOf(cums));
                    } else if (c.dimension.startsWith("atc")) {
                        s.put("atc", List.of(c.valor));
                    }
                }
            }
        }
        return s;
    }

    /**
     * Pacientes distintos de las candidatas que no los traen, en una sola sentencia con uniones por IGUALDAD (mes y medicamento, ESM o
     * Fuerza): una pasada por los hechos con hash join en lugar de recorrer el mes completo por cada candidata (revisión A4: el costo crecía
     * con candidatas × filas del mes).
     */
    private void contarPacientes() {
        List<Map<String, Object>> sel = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < candidatas.size(); i++) {
            Candidata c = candidatas.get(i);
            if (c.pacientes >= 0) {
                continue;
            }
            String fuerza = c.fuerza != null && !"nacional".equals(c.nivel) ? c.fuerza : "fuerza".equals(c.dimension) ? c.valor : null;
            String esm = c.esm != null ? c.esm : "esm".equals(c.dimension) ? c.valor : null;
            for (int mes = c.mesDesde; mes <= c.mes; mes++) {
                min = Math.min(min, mes);
                max = Math.max(max, mes);
                List<Long> meds = c.meds == null ? java.util.Collections.singletonList(null) : c.meds;
                for (Long med : meds) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("idx", i);
                    m.put("mes", AnaliticaCohortes.deOrdinal(mes).atDay(1).toString());
                    m.put("fuerza", fuerza);
                    m.put("esm", esm);
                    m.put("med", med);
                    sel.add(m);
                }
            }
        }
        if (sel.isEmpty()) {
            return;
        }
        String x = " JOIN ana_hechos_tablero x ON date_trunc('month', x.fecha)::date = s.mes AND x.cargue_id = ANY(:c) AND x.fecha BETWEEN :d AND :h";
        String filtros = " AND (s.fuerza IS NULL OR x.fuerza = (s.fuerza COLLATE \"C\")) AND (s.esm IS NULL OR x.esm_codigo = (s.esm COLLATE \"C\"))";
        j.sql("WITH s AS MATERIALIZED (SELECT * FROM jsonb_to_recordset(CAST(:sel AS jsonb)) AS s(idx int, mes date, fuerza text, esm text, med bigint))"
                + " SELECT idx, count(DISTINCT p) FROM ("
                + " SELECT s.idx, x.paciente_id AS p FROM s" + x + " AND x.medicamento_id = s.med WHERE s.med IS NOT NULL" + filtros
                + " UNION ALL SELECT s.idx, x.paciente_id FROM s" + x + " AND x.esm_codigo = (s.esm COLLATE \"C\") WHERE s.med IS NULL AND s.esm IS NOT NULL"
                + filtros
                + " UNION ALL SELECT s.idx, x.paciente_id FROM s" + x + " AND x.fuerza = (s.fuerza COLLATE \"C\") WHERE s.med IS NULL AND s.esm IS NULL"
                + " AND s.fuerza IS NOT NULL"
                + " UNION ALL SELECT s.idx, x.paciente_id FROM s" + x + " WHERE s.med IS NULL AND s.esm IS NULL AND s.fuerza IS NULL"
                + ") u GROUP BY idx").param("sel", aJson(sel)).param("c", cargues).param("d", AnaliticaCohortes.deOrdinal(min).atDay(1))
                .param("h", AnaliticaCohortes.deOrdinal(max).atEndOfMonth()).query((rs, n) -> candidatas.get(rs.getInt(1)).pacientes = rs.getLong(2)).list();
        for (Candidata c : candidatas) {
            if (c.pacientes < 0) {
                c.pacientes = 0;
            }
        }
    }

    private String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
