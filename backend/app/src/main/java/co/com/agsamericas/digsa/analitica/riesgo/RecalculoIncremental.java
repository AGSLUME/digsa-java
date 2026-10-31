package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Corte;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Tipo;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes;
import co.com.agsamericas.digsa.reglas.AnaliticaIncremental;
import co.com.agsamericas.digsa.reglas.AnaliticaIncremental.Plan;
import co.com.agsamericas.digsa.reglas.Huellas;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Recálculo incremental del motor de cohortes y alertas (A7.4, duda AN.23). Decide si el corte se puede calcular a partir de la versión
 * anterior (misma firma, mismo primer mes, misma clasificación clínica de los cargues que siguen, membresía conservada), arma el plan de
 * meses (reglas/AnaliticaIncremental), trae de la versión anterior lo de los meses que no se recalculan (serie, membresía, alertas de
 * secuencia y puntos de cada ESM), recalcula los conteos del periodo por defecto de la membresía final y deja la versión utilizable del
 * corte para el siguiente.
 *
 * <p>Lo que sigue con recorrido completo aunque el modo sea incremental: los detectores de alertas que agregan los hechos en SQL
 * (incrementos, formulación inusual con su umbral por CUM sobre toda la historia, presentación inconsistente con la concentración mayoritaria
 * del CUM sobre toda la historia, desabastecimiento, concentración y el conteo de pacientes de las candidatas) y el cambio de marca en
 * estrecho margen (la dispensación anterior del mismo principio no tiene límite hacia atrás: pasada propia de toda la historia, solo de esos
 * medicamentos).
 */
final class RecalculoIncremental {

    private RecalculoIncremental() {
    }

    /** Fecha neutra para «nada que recalcular» (todo se copia). */
    private static final LocalDate NINGUNO = LocalDate.of(1, 1, 1);

    /** Modo de la corrida: incremental (con la versión anterior y el plan) o completo (con su motivo). */
    record Decision(boolean incremental, String motivo, Long anterior, Plan plan, int ultimoAnterior) {

        static Decision completo(String motivo) {
            return new Decision(false, motivo, null, null, 0);
        }

        LocalDate desde() {
            return plan.vacio() ? NINGUNO : AnaliticaCohortes.deOrdinal(plan.desde()).atDay(1);
        }

        LocalDate hasta() {
            return plan.vacio() ? NINGUNO : AnaliticaCohortes.deOrdinal(plan.hasta()).atDay(1);
        }
    }

    // ---------- Firma y huella del catálogo ----------

    /** Clases cuyo código fija el resultado del recorrido y de lo que se copia (con sus anidadas y anónimas). */
    private static final List<Class<?>> DEL_MOTOR = List.of(AnaliticaCohortes.class, AnaliticaAlertas.class, AnaliticaIncremental.class, MotorRiesgo.class,
            RecalculoIncremental.class, RecalculoRiesgo.class, CatalogoClinico.class, DetectoresAlertas.class);

    /**
     * Huella del código del motor (SHA-256 de los .class de {@link #DEL_MOTOR}): cambia con cualquier cambio del motor sin depender de que
     * alguien suba {@link AnaliticaIncremental#VERSION_MOTOR}; con una huella distinta el primer recálculo del código nuevo es completo.
     */
    static final String CODIGO = huellaCodigo();

    private static String huellaCodigo() {
        java.io.ByteArrayOutputStream todo = new java.io.ByteArrayOutputStream();
        java.util.ArrayDeque<Class<?>> cola = new java.util.ArrayDeque<>(DEL_MOTOR);
        Set<String> vistas = new HashSet<>();
        try {
            while (!cola.isEmpty()) {
                Class<?> c = cola.poll();
                if (!vistas.add(c.getName())) {
                    continue;
                }
                todo.write(clase(c.getName(), c));
                cola.addAll(List.of(c.getDeclaredClasses()));
                // Clases anónimas (Outer$1, Outer$2…): no salen en getDeclaredClasses
                for (int i = 1; ; i++) {
                    byte[] b = clase(c.getName() + "$" + i, c);
                    if (b.length == 0) {
                        break;
                    }
                    todo.write(b);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo leer el código del motor", e);
        }
        return Huellas.sha256(todo.toByteArray());
    }

    private static byte[] clase(String nombre, Class<?> de) throws java.io.IOException {
        try (java.io.InputStream in = de.getClassLoader().getResourceAsStream(nombre.replace('.', '/') + ".class")) {
            return in == null ? new byte[0] : in.readAllBytes();
        }
    }

    /**
     * Firma de lo que fija el resultado además de los hechos: versión y huella del código del motor, gracia, si corren las alertas,
     * versiones de cohortes (inmutables: el id basta) con su estado, tipos evaluables con la versión de sus parámetros (inmutable) y las
     * listas de referencia.
     */
    static String firma(int gracia, boolean alertas, List<MotorRiesgo.VersionCohorte> versiones, Set<Tipo> evaluables, Map<Tipo, Integer> versionParametros,
                        Map<Tipo, AnaliticaAlertas.Parametros> parametros, CatalogoClinico cat) {
        StringBuilder sb = new StringBuilder(AnaliticaIncremental.VERSION_MOTOR).append('\n').append("codigo=").append(CODIGO).append('\n')
                .append("gracia=").append(gracia).append('\n')
                .append("alertas=").append(alertas).append('\n');
        for (MotorRiesgo.VersionCohorte v : versiones) {
            sb.append("v=").append(v.id()).append('|').append(v.codigo()).append('|').append(v.noEvaluado()).append('\n');
        }
        for (Tipo t : Tipo.values()) {
            AnaliticaAlertas.Parametros p = parametros.get(t);
            sb.append("t=").append(t.codigo()).append('|').append(versionParametros.get(t)).append('|').append(p != null && p.activo()).append('|')
                    .append(evaluables.contains(t)).append('\n');
        }
        sb.append("estrecho=").append(new TreeSet<>(cat.estrecho)).append('\n').append("control=").append(new TreeSet<>(cat.control)).append('\n')
                .append("interacciones=").append(new TreeSet<>(cat.interacciones)).append('\n').append("huerfanas=").append(new TreeSet<>(cat.huerfanas))
                .append('\n');
        Map<String, List<String>> ind = new TreeMap<>();
        cat.indicaciones.forEach((k, v) -> ind.put(k, new ArrayList<>(new TreeSet<>(v))));
        sb.append("indicaciones=").append(ind).append('\n');
        return Huellas.sha256(sb.toString());
    }

    /**
     * Huella, por cargue, de la clasificación clínica que el motor usa de sus datos originales: ATC, principio y CUM de cada medicamento y el
     * CIE-10 clínico de cada diagnóstico. Si la de un cargue que sigue en el corte cambió (otra tabla maestra o CIE-10), el incremental no
     * sirve: la versión anterior se calculó con otra clasificación.
     */
    static List<String> huellas(JdbcClient j, CatalogoClinico cat, List<Long> cargues) {
        Map<Long, StringBuilder> por = new LinkedHashMap<>();
        cargues.forEach(c -> por.put(c, new StringBuilder()));
        j.sql("""
                SELECT DISTINCT cargue_id, variable, valor FROM ana_cargue_valores WHERE cargue_id = ANY(:c) AND variable IN ('medicamento', 'cie10')
                ORDER BY cargue_id, variable, valor""").param("c", cargues.toArray(Long[]::new)).query((rs, n) -> {
                    StringBuilder sb = por.get(rs.getLong(1));
                    String valor = rs.getString(3);
                    if ("medicamento".equals(rs.getString(2))) {
                        CatalogoClinico.Med m = cat.med(Long.valueOf(valor));
                        sb.append("m:").append(valor).append('|').append(m == null ? "-" : m.atc() + "|" + m.principio() + "|" + m.cum()).append('\n');
                    } else {
                        sb.append("c:").append(valor).append('|').append(cat.cie(valor)).append('\n');
                    }
                    return null;
                }).list();
        return por.values().stream().map(sb -> Huellas.sha256(sb.toString())).toList();
    }

    // ---------- Decisión ----------

    /**
     * ¿Se puede calcular el corte desde la versión anterior? La versión anterior es la del corte más reciente que la dejó (otro corte); si no
     * sirve, el motivo va al registro de la ejecución.
     */
    static Decision decidir(JdbcClient j, Corte corte, String firma, List<String> huellas, List<MotorRiesgo.VersionCohorte> versiones, int gracia,
                            int mesesSecuencia) {
        List<Map<String, Object>> previa = j.sql("""
                SELECT corte_cargue_id, firma, cargues, catalogo, primer_mes, ultimo_mes FROM ana_riesgo_inc_cortes WHERE corte_cargue_id <> :c
                ORDER BY corte_cargue_id DESC LIMIT 1""").param("c", corte.cargueId()).query().listOfRows();
        if (previa.isEmpty()) {
            return Decision.completo("primer corte: no hay versión anterior utilizable");
        }
        Map<String, Object> p = previa.getFirst();
        long anterior = ((Number) p.get("corte_cargue_id")).longValue();
        if (!firma.equals(((String) p.get("firma")).strip())) {
            return Decision.completo("cambió la configuración desde el corte " + anterior + " (versiones de cohortes, parámetros de alertas, listas de "
                    + "referencia o versión del motor)");
        }
        int primer = AnaliticaCohortes.ordinal(corte.datosDesde().getYear(), corte.datosDesde().getMonthValue());
        LocalDate pm = ((java.sql.Date) p.get("primer_mes")).toLocalDate();
        if (AnaliticaCohortes.ordinal(pm.getYear(), pm.getMonthValue()) != primer) {
            return Decision.completo("cambió el primer mes de los datos desde el corte " + anterior);
        }
        List<Long> antes = arreglo(p.get("cargues"));
        List<String> catalogo = textos(p.get("catalogo"));
        Map<Long, String> huellaAntes = new HashMap<>();
        for (int i = 0; i < antes.size(); i++) {
            huellaAntes.put(antes.get(i), catalogo.get(i));
        }
        Set<Long> cambiaron = new TreeSet<>();
        for (int i = 0; i < corte.cargues().size(); i++) {
            Long c = corte.cargues().get(i);
            if (!huellaAntes.containsKey(c)) {
                cambiaron.add(c);
            } else if (!huellaAntes.get(c).equals(huellas.get(i))) {
                return Decision.completo("cambió la clasificación clínica (tabla maestra o CIE-10) de los datos del corte " + anterior);
            }
        }
        for (Long c : antes) {
            if (!corte.cargues().contains(c)) {
                cambiaron.add(c);
            }
        }
        // La versión anterior debe conservar la membresía de todas las versiones evaluadas
        Long[] ids = versiones.stream().filter(v -> v.noEvaluado() == null).map(MotorRiesgo.VersionCohorte::id).toArray(Long[]::new);
        long conMiembros = j.sql("""
                SELECT count(*) FROM ana_cohortes_resultados WHERE corte_cargue_id = :p AND version_id = ANY(:v) AND estado = 'evaluada' AND miembros""")
                .param("p", anterior).param("v", ids).query(Long.class).single();
        if (conMiembros != ids.length) {
            return Decision.completo("la versión anterior (corte " + anterior + ") no conserva la membresía de todas las cohortes");
        }
        TreeSet<Integer> meses = new TreeSet<>(cambiaron.isEmpty() ? List.of() : j.sql("""
                SELECT DISTINCT (extract(year FROM x.fecha)::int * 12 + extract(month FROM x.fecha)::int - 1) FROM ana_hechos_tablero x
                WHERE x.cargue_id = ANY(:c)""").param("c", cambiaron.toArray(Long[]::new)).query(Integer.class).list());
        int atras = versiones.stream().filter(v -> v.noEvaluado() == null).mapToInt(v -> AnaliticaIncremental.mesesAtras(v.criterio())).max().orElse(0);
        LocalDate um = ((java.sql.Date) p.get("ultimo_mes")).toLocalDate();
        int ultimoAntes = AnaliticaCohortes.ordinal(um.getYear(), um.getMonthValue());
        int ultimo = AnaliticaCohortes.ordinal(corte.datosHasta().getYear(), corte.datosHasta().getMonthValue());
        Plan plan = AnaliticaIncremental.plan(meses, atras, gracia, mesesSecuencia, ultimoAntes, ultimo);
        return new Decision(true, "desde el corte " + anterior, anterior, plan, ultimoAntes);
    }

    // ---------- Lo que se trae de la versión anterior ----------

    /** Serie de los meses que no se recalculan, alertas de secuencia (menos estrecho margen) y puntos de cada ESM, al motor. */
    static void cargarAnterior(JdbcClient j, Decision d, MotorRiesgo motor, List<MotorRiesgo.VersionCohorte> versiones, boolean alertas) {
        Map<Long, Integer> indice = new HashMap<>();
        versiones.stream().filter(v -> v.noEvaluado() == null).forEach(v -> indice.put(v.id(), v.indice()));
        j.sql("""
                SELECT version_id, mes, nivel, fuerza, codigo, grupo, pacientes, ingresos, salidas, dispensaciones, unidades_milesimas, gasto_centavos
                FROM ana_cohortes_serie WHERE corte_cargue_id = :p AND version_id = ANY(:v) AND (mes < :d OR mes > :h)""").param("p", d.anterior())
                .param("v", indice.keySet().toArray(Long[]::new)).param("d", d.desde()).param("h", d.hasta()).query((rs, n) -> {
                    LocalDate mes = rs.getDate(2).toLocalDate();
                    String celda = switch (rs.getString(3)) {
                        case "nacional" -> "N";
                        case "fuerza" -> "F|" + rs.getString(5);
                        default -> MotorRiesgo.celdaEsm(rs.getString(4), rs.getString(5));
                    };
                    motor.serie.put(new MotorRiesgo.Llave(indice.get(rs.getLong(1)), AnaliticaCohortes.ordinal(mes.getYear(), mes.getMonthValue()), celda,
                            rs.getString(6)), new long[] {rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11), rs.getLong(12)});
                    return null;
                }).list();
        if (alertas) {
            j.sql("""
                    SELECT tipo, nivel, codigo_nivel, dimension, valor, mes, pacientes, registros, gasto_centavos, esms, fuerzas, cums
                    FROM ana_riesgo_inc_secuencia WHERE corte_cargue_id = :p AND tipo <> :marca AND (mes < :d OR mes > :h)""").param("p", d.anterior())
                    .param("marca", Tipo.MARCA_ESTRECHO_MARGEN.codigo()).param("d", d.desde()).param("h", d.hasta()).query((rs, n) -> {
                        Tipo t = Tipo.deCodigo(rs.getString(1)).orElseThrow();
                        LocalDate mes = rs.getDate(6).toLocalDate();
                        MotorRiesgo.Acum a = new MotorRiesgo.Acum();
                        a.pacientesPrevios = rs.getLong(7);
                        a.registros = rs.getLong(8);
                        a.gasto = rs.getLong(9);
                        a.esms.addAll(textos(rs.getArray(10)));
                        a.fuerzas.addAll(textos(rs.getArray(11)));
                        a.cums.addAll(textos(rs.getArray(12)));
                        motor.alertas.put(new MotorRiesgo.ClaveAlerta(t, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                AnaliticaCohortes.ordinal(mes.getYear(), mes.getMonthValue())), a);
                        return null;
                    }).list();
        }
        j.sql("SELECT mes, esm, puntos FROM ana_riesgo_inc_puntos WHERE corte_cargue_id = :p AND (mes < :d OR mes > :h)").param("p", d.anterior())
                .param("d", d.desde()).param("h", d.hasta()).query((rs, n) -> {
                    LocalDate mes = rs.getDate(1).toLocalDate();
                    motor.puntos.computeIfAbsent(AnaliticaCohortes.ordinal(mes.getYear(), mes.getMonthValue()), x -> new TreeMap<>())
                            .computeIfAbsent(rs.getString(2), x -> new TreeSet<>()).addAll(arreglo(rs.getArray(3)));
                    return null;
                }).list();
    }

    /**
     * El punto de cada ESM (ubica las alertas de nivel ESM) a partir de los puntos por mes. El recorrido completo toma el primero que ve;
     * si un ESM llegó con más de un punto en la historia el orden importa y el incremental no lo garantiza: devuelve falso (recorrido completo).
     */
    static boolean rehacerPuntos(MotorRiesgo motor) {
        Map<String, Set<Long>> todos = new TreeMap<>();
        motor.puntos.values().forEach(m -> m.forEach((esm, p) -> todos.computeIfAbsent(esm, x -> new HashSet<>()).addAll(p)));
        motor.puntoDeEsm.clear();
        for (Map.Entry<String, Set<Long>> e : todos.entrySet()) {
            if (e.getValue().size() > 1) {
                return false;
            }
            motor.puntoDeEsm.put(e.getKey(), e.getValue().iterator().next());
        }
        return true;
    }

    /** Umbral del gasto móvil guardado con la versión anterior (mes ⇒ centavos), de la primera versión con esa llave. */
    static Map<Integer, Long> umbralesAnteriores(JdbcClient j, long anterior, long versionId, RecalculoRiesgo riesgo) {
        Map<Integer, Long> r = new TreeMap<>();
        j.sql("SELECT umbrales::text FROM ana_cohortes_resultados WHERE corte_cargue_id = :p AND version_id = :v").param("p", anterior).param("v", versionId)
                .query(String.class).optional().ifPresent(s -> riesgo.deJson(s).forEach((k, v) -> {
                    YearMonth ym = YearMonth.parse(k);
                    r.put(AnaliticaCohortes.ordinal(ym.getYear(), ym.getMonthValue()), new BigDecimal(String.valueOf(v)).movePointRight(2).longValueExact());
                }));
        return r;
    }

    /** Copia la membresía de los meses que no se recalculan (en la transacción de escritura, antes de la purga de cortes viejos). */
    static long copiarMembresia(JdbcClient j, Decision d, long corte, Long[] versiones) {
        if (versiones.length == 0) {
            return 0;
        }
        return j.sql("""
                INSERT INTO ana_cohortes_miembros (version_id, corte_cargue_id, mes, paciente_id, fuerzas, esms, fuerzas_esms, grupo)
                SELECT version_id, :c, mes, paciente_id, fuerzas, esms, fuerzas_esms, grupo FROM ana_cohortes_miembros
                WHERE corte_cargue_id = :p AND version_id = ANY(:v) AND (mes < :d OR mes > :h)""").param("c", corte).param("p", d.anterior())
                .param("v", versiones).param("d", d.desde()).param("h", d.hasta()).update();
    }

    /**
     * Pacientes distintos del periodo por defecto por celda (nacional siempre, aunque sea 0; Fuerza; ESM con su Fuerza) de la membresía final:
     * lo mismo que cuenta el recorrido completo (cada paciente cuenta una vez en cada celda en que fue miembro algún mes del periodo).
     */
    static void conteos(JdbcClient j, long corte, Long[] versiones, LocalDate desde, LocalDate hasta) {
        if (versiones.length == 0) {
            return;
        }
        j.sql("""
                INSERT INTO ana_cohortes_conteos (version_id, corte_cargue_id, desde, hasta, nivel, fuerza, codigo, pacientes)
                SELECT v.id, :c, :d, :h, 'nacional', '', '', (SELECT count(DISTINCT m.paciente_id) FROM ana_cohortes_miembros m
                       WHERE m.version_id = v.id AND m.corte_cargue_id = :c AND m.mes BETWEEN :d AND :h)
                FROM unnest(CAST(:v AS bigint[])) AS v(id)
                UNION ALL
                SELECT m.version_id, :c, :d, :h, 'fuerza', '', f, count(DISTINCT m.paciente_id)
                FROM ana_cohortes_miembros m CROSS JOIN LATERAL unnest(m.fuerzas) AS f
                WHERE m.version_id = ANY(:v) AND m.corte_cargue_id = :c AND m.mes BETWEEN :d AND :h GROUP BY m.version_id, f
                UNION ALL
                SELECT m.version_id, :c, :d, :h, 'esm', split_part(fe, '|', 1), substr(fe, strpos(fe, '|') + 1), count(DISTINCT m.paciente_id)
                FROM ana_cohortes_miembros m CROSS JOIN LATERAL unnest(m.fuerzas_esms) AS fe
                WHERE m.version_id = ANY(:v) AND m.corte_cargue_id = :c AND m.mes BETWEEN :d AND :h GROUP BY m.version_id, fe""")
                .param("c", corte).param("v", versiones).param("d", desde).param("h", hasta).update();
    }

    // ---------- Versión utilizable del corte ----------

    /**
     * Deja la versión utilizable del corte (solo tras un recálculo completo de todas las versiones y tipos, sin fallas) y purga las de los
     * cortes anteriores al penúltimo.
     */
    static void guardar(JdbcClient j, DataSource ds, Corte corte, long ejecucion, String firma, List<String> huellas, MotorRiesgo motor, OffsetDateTime ahora) {
        j.sql("DELETE FROM ana_riesgo_inc_cortes WHERE corte_cargue_id = :c").param("c", corte.cargueId()).update();
        j.sql("""
                INSERT INTO ana_riesgo_inc_cortes (corte_cargue_id, ejecucion_id, firma, cargues, catalogo, primer_mes, ultimo_mes, creado)
                VALUES (:c, :e, :f, :cargues, :cat, :pm, :um, :a)""").param("c", corte.cargueId()).param("e", ejecucion).param("f", firma)
                .param("cargues", corte.cargues().toArray(Long[]::new)).param("cat", huellas.toArray(String[]::new))
                .param("pm", corte.datosDesde().withDayOfMonth(1)).param("um", corte.datosHasta().withDayOfMonth(1)).param("a", ahora).update();
        JdbcTemplate t = new JdbcTemplate(ds);
        List<Map.Entry<MotorRiesgo.ClaveAlerta, MotorRiesgo.Acum>> sec = new ArrayList<>(motor.alertas.entrySet());
        t.batchUpdate("""
                INSERT INTO ana_riesgo_inc_secuencia (corte_cargue_id, tipo, nivel, codigo_nivel, dimension, valor, mes, pacientes, registros, gasto_centavos,
                                                      esms, fuerzas, cums) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                MotorRiesgo.ClaveAlerta k = sec.get(i).getKey();
                MotorRiesgo.Acum a = sec.get(i).getValue();
                ps.setLong(1, corte.cargueId());
                ps.setString(2, k.tipo().codigo());
                ps.setString(3, k.nivel());
                ps.setString(4, k.codigoNivel());
                ps.setString(5, k.dimension());
                ps.setString(6, k.valor());
                ps.setObject(7, AnaliticaCohortes.deOrdinal(k.mes()).atDay(1));
                ps.setLong(8, a.numPacientes());
                ps.setLong(9, a.registros);
                ps.setLong(10, a.gasto);
                ps.setArray(11, ps.getConnection().createArrayOf("text", a.esms.toArray()));
                ps.setArray(12, ps.getConnection().createArrayOf("text", a.fuerzas.toArray()));
                ps.setArray(13, ps.getConnection().createArrayOf("text", a.cums.toArray()));
            }

            @Override
            public int getBatchSize() {
                return sec.size();
            }
        });
        List<Object[]> pun = new ArrayList<>();
        motor.puntos.forEach((mes, m) -> m.forEach((esm, p) -> pun.add(new Object[] {mes, esm, p.toArray(Long[]::new)})));
        t.batchUpdate("INSERT INTO ana_riesgo_inc_puntos (corte_cargue_id, mes, esm, puntos) VALUES (?, ?, ?, ?)", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Object[] x = pun.get(i);
                ps.setLong(1, corte.cargueId());
                ps.setObject(2, AnaliticaCohortes.deOrdinal((Integer) x[0]).atDay(1));
                ps.setString(3, (String) x[1]);
                ps.setArray(4, ps.getConnection().createArrayOf("bigint", (Long[]) x[2]));
            }

            @Override
            public int getBatchSize() {
                return pun.size();
            }
        });
        j.sql("""
                DELETE FROM ana_riesgo_inc_cortes WHERE corte_cargue_id NOT IN (
                    SELECT corte_cargue_id FROM ana_riesgo_inc_cortes ORDER BY corte_cargue_id DESC LIMIT 2)""").update();
    }

    // ---------- Apoyo ----------

    private static List<Long> arreglo(Object o) {
        try {
            Object a = o instanceof Array x ? x.getArray() : o;
            List<Long> r = new ArrayList<>();
            for (Object v : (Object[]) a) {
                r.add(((Number) v).longValue());
            }
            return r;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> textos(Object o) {
        try {
            Object a = o instanceof Array x ? x.getArray() : o;
            List<String> r = new ArrayList<>();
            for (Object v : (Object[]) a) {
                r.add((String) v);
            }
            return r;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Medicamentos (datos originales) cuyo principio está en la lista de estrecho margen: los de la pasada propia de toda la historia. */
    static Long[] medsEstrecho(CatalogoClinico cat) {
        Collection<CatalogoClinico.Med> meds = cat.meds.values();
        return meds.stream().filter(m -> AnaliticaAlertas.enLista(m.principio(), cat.estrecho)).map(CatalogoClinico.Med::id).sorted().toArray(Long[]::new);
    }
}
