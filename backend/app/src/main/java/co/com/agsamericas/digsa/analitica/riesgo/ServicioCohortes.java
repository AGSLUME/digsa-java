package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Columna;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Fila;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.FiltroAplicado;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Kpi;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Metadatos;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Tabla;
import co.com.agsamericas.digsa.analitica.tableros.PropiedadesTableros;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Corte;
import co.com.agsamericas.digsa.analitica.tableros.ServicioConsultaAnalitica;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Criterio;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Dimension;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Granularidad;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tipo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cohortes (analitica-cohortes-alertas; A5 14.6): definición persistente y versionada, tarjetas «Cohortes (conteos agregados)» con el
 * criterio visible, serie mensual, comparación entre periodos y niveles, composición (Top 20) y descargas. Toda salida es AGREGADA: conteos
 * de pacientes distintos, dispensaciones, unidades y gasto; la membresía (número de paciente) solo se cuenta en SQL y nunca sale. El
 * alcance del usuario se aplica en SQL (Sanidad: su Fuerza; ESM: su establecimiento) y la supresión de celdas pequeñas de A3 se aplica a
 * cada conteo de pacientes.
 */
@Service
public class ServicioCohortes {

    /** Metodología de GCF y GRC (RESP L5361–5367): se muestra en la pantalla y va en las descargas. */
    public static final String METODOLOGIA = "GCF (grupo de consumo farmacéutico): cada paciente con dispensaciones en el mes queda en el grupo "
            + "ATC de nivel 2 con mayor gasto suyo en ese mes; empate: el código menor en orden alfabético; sin ATC conocido: «Sin ATC»; sin gasto: «Sin gasto». "
            + "GRC (grupo de riesgo clínico): diagnósticos crónicos distintos (categoría CIE-10 de 3 caracteres de la lista de crónicos) en los 12 meses que "
            + "terminan en el mes: 0 ⇒ GRC1, 1 ⇒ GRC2, 2 o 3 ⇒ GRC3, 4 o más ⇒ GRC4; quien es de alto costo en el mes (gasto en 12 meses móviles ≥ percentil 99 "
            + "del gasto de los pacientes del mes) queda en GRC4. Las particiones suman el total de pacientes del mes. El ATC y el principio activo salen de "
            + "la tabla maestra homologada vigente al corte o, si el dato original no está homologado, del insumo. Variables: gasto (valor total "
            + "dispensado), ATC, CIE-10 del insumo homologado por R-060; parámetros: ventana de 12 meses y percentil 99 (parametrizables en cada versión); "
            + "supuestos: el diagnóstico de la dispensación es el del paciente en ese mes. Versión de la metodología: A4-2026-10-23.";
    static final String SIN_CALCULAR = "Aún sin calcular para este corte: se calcula con el próximo cargue o con «Recalcular».";

    private final JdbcClient jdbc;
    private final ServicioConsultaAnalitica consultas;
    private final RecalculoRiesgo riesgo;
    private final PropiedadesTableros tableros;
    private final PropiedadesRiesgo propiedades;
    private final RegistroBitacora bitacora;
    private final Clock reloj;
    private final TransactionTemplate escritura;

    ServicioCohortes(JdbcClient jdbc, ServicioConsultaAnalitica consultas, RecalculoRiesgo riesgo, PropiedadesTableros tableros, PropiedadesRiesgo propiedades,
                     RegistroBitacora bitacora, Clock reloj, org.springframework.transaction.PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.consultas = consultas;
        this.riesgo = riesgo;
        this.tableros = tableros;
        this.propiedades = propiedades;
        this.bitacora = bitacora;
        this.reloj = reloj;
        this.escritura = new TransactionTemplate(tm);
    }

    // ---------- Supresión ----------

    /** ¿Cohorte sensible (VIH, huérfanas) con su umbral propio activo? */
    private boolean sensible(String codigo) {
        return propiedades.kSensibles() > 0 && propiedades.sensibles().contains(codigo);
    }

    /** Umbral k de una celda: el general; en las cohortes sensibles a nivel ESM, al menos el de las sensibles (duda de supresión). */
    private int k(String codigo, boolean celdaEsm) {
        int k = tableros.supresion();
        return celdaEsm && sensible(codigo) ? Math.max(k, propiedades.kSensibles()) : k;
    }

    // ---------- Nivel y periodo ----------

    /** Nivel de la serie que ve el usuario: nacional, su Fuerza o sus códigos de ESM (los del punto en los cargues del corte). */
    record Nivel(String nivel, List<String> codigos, String texto) {
    }

    Nivel nivel(JdbcClient j, Alcance a, Corte c, String fuerza) {
        return switch (a) {
            case Alcance.Todo t -> fuerza == null ? new Nivel("nacional", List.of(""), "Nacional") : new Nivel("fuerza", List.of(fuerza), "Fuerza " + fuerza);
            case Alcance.Fuerza f -> new Nivel("fuerza", List.of(f.codigo()), "Fuerza " + f.codigo());
            case Alcance.Esm e -> new Nivel("esm", j.sql("""
                    SELECT DISTINCT valor FROM ana_cargue_valores WHERE cargue_id = ANY(:c) AND variable = 'esm' AND punto_id = :p ORDER BY 1""")
                    .param("c", c.cargues().toArray(Long[]::new)).param("p", e.puntoId()).query(String.class).list(), consultas.alcanceTexto(j, a));
            default -> throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Su perfil no tiene un alcance de datos para la analítica farmacéutica.");
        };
    }

    /** Periodo en meses completos: sin fechas, los últimos 12 meses con datos del corte. */
    static LocalDate[] periodo(Corte c, LocalDate desde, LocalDate hasta) {
        LocalDate[] defecto = AnaliticaConsulta.periodoPorDefecto(c.datosDesde(), c.datosHasta());
        LocalDate d = desde != null ? desde : defecto == null ? c.datosDesde() : defecto[0];
        LocalDate h = hasta != null ? hasta : c.datosHasta();
        if (d == null || h == null) {
            return null;
        }
        if (d.isAfter(h)) {
            throw ErrorNegocio.validacion("La fecha «Desde» es posterior a «Hasta».");
        }
        return new LocalDate[] {d.withDayOfMonth(1), h.withDayOfMonth(1)};
    }

    private Corte corte(JdbcClient j, Long corte) {
        return consultas.corte(j, corte).orElseThrow(() -> ErrorNegocio.validacion("Aún no hay cargues aplicados."));
    }

    // ---------- Versiones ----------

    /** Versión que se muestra para el corte: la de mayor número con resultados en ese corte; si ninguna tiene, la vigente. */
    private Map<String, Object> version(JdbcClient j, long cohorteId, Integer version, long corte) {
        List<Map<String, Object>> v = j.sql("""
                SELECT v.id, v.version, v.criterio_texto, v.criterios::text AS criterios, v.vigente, r.estado, r.motivo, r.umbrales::text AS umbrales,
                       r.fuentes, r.calculado, r.miembros
                FROM ana_cohortes_versiones v LEFT JOIN ana_cohortes_resultados r ON r.version_id = v.id AND r.corte_cargue_id = :c
                WHERE v.cohorte_id = :id AND (CAST(:ver AS integer) IS NULL OR v.version = CAST(:ver AS integer))
                ORDER BY (r.estado IS NOT NULL) DESC, v.version DESC LIMIT 1""").param("c", corte).param("id", cohorteId).param("ver", version)
                .query().listOfRows();
        if (v.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.NOT_FOUND, "La versión de la cohorte no existe.");
        }
        return v.getFirst();
    }

    private Map<String, Object> cohorte(JdbcClient j, long id) {
        return j.sql("SELECT id, codigo, nombre, descripcion, predefinida, activa, orden FROM ana_cohortes WHERE id = :id").param("id", id).query().listOfRows()
                .stream().findFirst().orElseThrow(() -> new ErrorNegocio(HttpStatus.NOT_FOUND, "La cohorte no existe."));
    }

    // ---------- Tarjetas ----------

    /** Tarjetas «Cohortes (conteos agregados)»: una por cohorte activa con conteo, criterio, versión, periodo, corte y registros. */
    public Map<String, Object> tarjetas(Long corteId, LocalDate desde, LocalDate hasta, String fuerza, Alcance alcance) {
        return consultas.leer(j -> {
            Map<String, Object> r = new LinkedHashMap<>();
            Optional<Corte> oc = consultas.corte(j, corteId);
            r.put("metodologia", METODOLOGIA);
            r.put("supresion", consultas.textoSupresion());
            if (oc.isEmpty()) {
                r.put("mensaje", "Aún no hay cargues aplicados");
                r.put("cohortes", List.of());
                return r;
            }
            Corte c = oc.get();
            LocalDate[] p = periodo(c, desde, hasta);
            Nivel n = nivel(j, alcance, c, fuerza);
            r.put("corte", c.cargueId());
            r.put("corteTexto", "Corte " + AnaliticaConsulta.fechaHora(c.aplicadoEn()) + " (cargue " + c.cargueId() + ")");
            r.put("periodo", AnaliticaConsulta.fecha(p[0]) + " – " + AnaliticaConsulta.fecha(YearMonth.from(p[1]).atEndOfMonth()));
            r.put("desde", p[0]);
            r.put("hasta", YearMonth.from(p[1]).atEndOfMonth());
            r.put("nivel", n.texto());
            List<Map<String, Object>> tarjetas = new ArrayList<>();
            for (Map<String, Object> co : j.sql("SELECT id, codigo, nombre, descripcion, predefinida, activa FROM ana_cohortes WHERE activa ORDER BY orden, id")
                    .query().listOfRows()) {
                long id = ((Number) co.get("id")).longValue();
                Map<String, Object> v = version(j, id, null, c.cargueId());
                tarjetas.add(tarjeta(j, co, v, c, p, n));
            }
            r.put("cohortes", tarjetas);
            return r;
        });
    }

    private Map<String, Object> tarjeta(JdbcClient j, Map<String, Object> co, Map<String, Object> v, Corte c, LocalDate[] p, Nivel n) {
        long versionId = ((Number) v.get("id")).longValue();
        Criterio crit = AnaliticaCohortes.leer(riesgo.deJson((String) v.get("criterios")));
        boolean particion = crit instanceof AnaliticaCohortes.Particion;
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", co.get("id"));
        t.put("codigo", co.get("codigo"));
        t.put("nombre", co.get("nombre"));
        t.put("descripcion", co.get("descripcion"));
        t.put("predefinida", co.get("predefinida"));
        t.put("delMockup", AnaliticaCohortes.DEL_MOCKUP.contains((String) co.get("codigo")));
        t.put("version", v.get("version"));
        t.put("criterio", v.get("criterio_texto") + " · v" + v.get("version"));
        t.put("particion", particion);
        String estado = (String) v.get("estado");
        t.put("estado", estado == null ? "sin_calcular" : estado);
        t.put("motivo", estado == null ? sinCalcular(j, versionId, c.cargueId()) : v.get("motivo"));
        if (estado == null && !SIN_CALCULAR.equals(t.get("motivo"))) {
            t.put("estado", "error_calculo");
        }
        t.put("fuentes", v.get("fuentes"));
        if (!Boolean.TRUE.equals(v.get("vigente"))) {
            t.put("aviso", "Se muestra la versión " + v.get("version") + ": la vigente aún no tiene resultados en este corte.");
        }
        if (!"evaluada".equals(estado)) {
            return t;
        }
        String codigo = (String) co.get("codigo");
        int k = k(codigo, "esm".equals(n.nivel()));
        Map<String, String> umbrales = v.get("umbrales") == null ? Map.of() : riesgo.deJson((String) v.get("umbrales")).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
        String ultimo = umbrales.get(YearMonth.from(p[1]).toString());
        if (ultimo != null) {
            t.put("umbral", "Umbral del gasto en 12 meses en " + AnaliticaConsulta.etiquetaPeriodo(p[1], Granularidad.MES) + ": "
                    + AnaliticaConsulta.moneda(new BigDecimal(ultimo)));
        } else if (umbrales.containsKey("absoluto")) {
            t.put("umbral", "Umbral del gasto: " + AnaliticaConsulta.moneda(new BigDecimal(umbrales.get("absoluto"))));
        }
        Long[] sumas = sumas(j, versionId, c.cargueId(), p, n);
        if (particion) {
            t.put("registros", sumas[0]);
            t.put("gasto", AnaliticaConsulta.moneda(BigDecimal.valueOf(sumas[1], 2)));
            List<Map<String, Object>> g = grupos(j, versionId, c.cargueId(), p[1], n, k);
            t.put("grupos", g);
            boolean ocultos = g.stream().anyMatch(x -> x.get("pacientes") == null);
            t.put("nota", "Partición del mes " + AnaliticaConsulta.etiquetaPeriodo(p[1], Granularidad.MES) + ": cada paciente con dispensaciones en el mes está en "
                    + "un solo grupo" + (ocultos ? " (grupos con menos de " + k + " pacientes ocultos, y el siguiente menor para que no se deduzcan)."
                            : "; la suma de los grupos es el total de pacientes del mes."));
            return t;
        }
        Long pacientes = pacientes(j, versionId, c.cargueId(), p[0], p[1], n);
        if (pacientes == null) {
            // Corte anterior al último calculado: la membresía detallada ya es la del último corte
            pacientes = maximoMensual(j, versionId, c.cargueId(), p, n);
            t.put("nota", "Corte antiguo: máximo de pacientes de un mes del periodo (la membresía detallada se conserva para los últimos cortes de la versión).");
        }
        boolean oculto = SupresionRiesgo.pacientes(t, pacientes, k);
        // Con la celda oculta tampoco salen sus registros ni su gasto (con un paciente, serían los de esa persona)
        t.put("registros", oculto ? null : sumas[0]);
        t.put("gasto", SupresionRiesgo.moneda(BigDecimal.valueOf(sumas[1], 2), oculto, k));
        return t;
    }

    /** Motivo de una versión sin resultados en el corte: aún sin calcular o, si la última ejecución del corte falló, la falla con su fecha. */
    private String sinCalcular(JdbcClient j, long versionId, long corte) {
        return j.sql("""
                SELECT e.inicio, e.estado, e.error FROM ana_cohortes_ejecuciones e WHERE e.corte_cargue_id = :c AND e.fin IS NOT NULL
                ORDER BY e.id DESC LIMIT 1""").param("c", corte).query((rs, n) -> "error".equals(rs.getString(2)) || "interrumpida".equals(rs.getString(2))
                        ? "El cálculo falló el " + AnaliticaConsulta.fechaHora(rs.getTimestamp(1).toInstant().atOffset(java.time.ZoneOffset.UTC)) + ": "
                                + rs.getString(3) + ". Use «Recalcular»."
                        : SIN_CALCULAR).optional().orElse(SIN_CALCULAR);
    }

    private static String filtroNivel(Nivel n) {
        return switch (n.nivel()) {
            case "fuerza" -> " AND CAST(:cod AS text[]) && fuerzas";
            case "esm" -> " AND CAST(:cod AS text[]) && esms";
            default -> "";
        };
    }

    /** ¿Se conserva la membresía de la versión en ese corte? (los últimos cortes de cada versión) */
    private static boolean conMiembros(JdbcClient j, long version, long corte) {
        return Boolean.TRUE.equals(j.sql("SELECT miembros FROM ana_cohortes_resultados WHERE version_id = :v AND corte_cargue_id = :c").param("v", version)
                .param("c", corte).query(Boolean.class).optional().orElse(false));
    }

    /** Pacientes distintos miembros en el periodo y el nivel (de la membresía del corte); nulo si ese corte ya no conserva la membresía. */
    private Long pacientes(JdbcClient j, long version, long corte, LocalDate d, LocalDate h, Nivel n) {
        Long precalculado = precalculado(j, version, corte, d, h, n);
        if (precalculado != null) {
            return precalculado;
        }
        if (!conMiembros(j, version, corte)) {
            return null;
        }
        return j.sql("SELECT count(DISTINCT paciente_id) FROM ana_cohortes_miembros WHERE version_id = :v AND corte_cargue_id = :c AND mes BETWEEN :d AND :h"
                + filtroNivel(n)).param("v", version).param("c", corte).param("d", d).param("h", h).param("cod", n.codigos().toArray(String[]::new)).query(Long.class).single();
    }

    /**
     * Pacientes distintos del periodo por defecto contados en el recálculo (ana_cohortes_conteos), si el periodo pedido es ese: nacional,
     * una Fuerza o un ESM con un solo código y una sola Fuerza (con varios no se pueden sumar distintos: va a la membresía). Nulo si no aplica.
     */
    private static Long precalculado(JdbcClient j, long version, long corte, LocalDate d, LocalDate h, Nivel n) {
        if (n.codigos().size() != 1) {
            return null;
        }
        List<Map<String, Object>> f = j.sql("""
                SELECT nivel, fuerza, codigo, pacientes FROM ana_cohortes_conteos WHERE version_id = :v AND corte_cargue_id = :c AND desde = :d AND hasta = :h
                AND ((nivel = 'nacional') OR (nivel = :n AND codigo = :cod))""").param("v", version).param("c", corte).param("d", d).param("h", h)
                .param("n", n.nivel()).param("cod", n.codigos().getFirst()).query().listOfRows();
        if (f.stream().noneMatch(x -> "nacional".equals(x.get("nivel")))) {
            return null; // el corte no tiene conteos precalculados de este periodo
        }
        List<Map<String, Object>> propias = f.stream().filter(x -> n.nivel().equals(x.get("nivel"))).toList();
        if (propias.size() > 1) {
            return null; // ESM compartido por varias Fuerzas: los distintos no se suman
        }
        return propias.isEmpty() ? 0L : ((Number) propias.getFirst().get("pacientes")).longValue();
    }

    private Long maximoMensual(JdbcClient j, long version, long corte, LocalDate[] p, Nivel n) {
        return j.sql("""
                SELECT coalesce(max(s), 0) FROM (SELECT mes, sum(pacientes) AS s FROM ana_cohortes_serie
                WHERE version_id = :v AND corte_cargue_id = :c AND mes BETWEEN :d AND :h AND nivel = :n AND codigo = ANY(:cod) AND grupo = '' GROUP BY mes) x""")
                .param("v", version).param("c", corte).param("d", p[0]).param("h", p[1]).param("n", n.nivel()).param("cod", n.codigos().toArray(String[]::new))
                .query(Long.class).single();
    }

    /** {dispensaciones, gasto en centavos} de los miembros en el periodo y el nivel (sumas: no hay pacientes que contar dos veces). */
    private Long[] sumas(JdbcClient j, long version, long corte, LocalDate[] p, Nivel n) {
        return j.sql("""
                SELECT coalesce(sum(dispensaciones), 0), coalesce(sum(gasto_centavos), 0) FROM ana_cohortes_serie
                WHERE version_id = :v AND corte_cargue_id = :c AND mes BETWEEN :d AND :h AND nivel = :n AND codigo = ANY(:cod)""")
                .param("v", version).param("c", corte).param("d", p[0]).param("h", p[1]).param("n", n.nivel()).param("cod", n.codigos().toArray(String[]::new))
                .query((rs, x) -> new Long[] {rs.getLong(1), rs.getLong(2)}).single();
    }

    /** Grupos de la partición en el mes, con la supresión de la celda y la complementaria (los grupos suman el total del mes). */
    private List<Map<String, Object>> grupos(JdbcClient j, long version, long corte, LocalDate mes, Nivel n, int k) {
        List<Map<String, Object>> r = new ArrayList<>();
        List<Long> reales = new ArrayList<>();
        j.sql("""
                SELECT grupo, sum(pacientes), sum(dispensaciones), sum(gasto_centavos) FROM ana_cohortes_serie
                WHERE version_id = :v AND corte_cargue_id = :c AND mes = :m AND nivel = :n AND codigo = ANY(:cod) GROUP BY grupo ORDER BY sum(pacientes) DESC, grupo""")
                .param("v", version).param("c", corte).param("m", mes).param("n", n.nivel()).param("cod", n.codigos().toArray(String[]::new))
                .query((rs, x) -> {
                    Map<String, Object> g = new LinkedHashMap<>();
                    long pac = rs.getLong(2);
                    g.put("grupo", rs.getString(1));
                    g.put("etiqueta", AnaliticaCohortes.etiquetaGrupo(rs.getString(1)));
                    boolean oculto = SupresionRiesgo.pacientes(g, pac, k);
                    g.put("dispensaciones", oculto ? null : rs.getLong(3));
                    g.put("gasto", SupresionRiesgo.moneda(BigDecimal.valueOf(rs.getLong(4), 2), oculto, k));
                    r.add(g);
                    reales.add(pac);
                    return null;
                }).list();
        SupresionRiesgo.complementaria(r, reales, k);
        return r;
    }

    // ---------- Detalle, serie y comparación ----------

    /** Definición, versiones y la serie mensual con la comparación entre periodos (P5) y entre niveles. */
    public Map<String, Object> detalle(long id, Integer version, Long corteId, LocalDate desde, LocalDate hasta, String fuerza, Alcance alcance) {
        return consultas.leer(j -> {
            Map<String, Object> co = cohorte(j, id);
            Map<String, Object> r = new LinkedHashMap<>(co);
            r.put("versiones", j.sql("""
                    SELECT v.version, v.criterio_texto, v.criterios, v.vigente, v.creada, coalesce(u.usuario, 'Sistema') AS creada_por
                    FROM ana_cohortes_versiones v LEFT JOIN usuarios u ON u.id = v.creada_por WHERE v.cohorte_id = :id ORDER BY v.version DESC""")
                    .param("id", id).query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("version", rs.getInt(1));
                        m.put("criterio", rs.getString(2));
                        m.put("criterios", riesgo.deJson(rs.getString(3)));
                        m.put("vigente", rs.getBoolean(4));
                        m.put("creada", AnaliticaConsulta.fechaHora(rs.getTimestamp(5).toInstant().atOffset(java.time.ZoneOffset.UTC)));
                        m.put("creadaPor", rs.getString(6));
                        return m;
                    }).list());
            r.put("metodologia", METODOLOGIA);
            Optional<Corte> oc = consultas.corte(j, corteId);
            if (oc.isEmpty()) {
                return r;
            }
            Corte c = oc.get();
            Map<String, Object> v = version(j, id, version, c.cargueId());
            long versionId = ((Number) v.get("id")).longValue();
            LocalDate[] p = periodo(c, desde, hasta);
            Nivel n = nivel(j, alcance, c, fuerza);
            String codigo = (String) co.get("codigo");
            int k = k(codigo, "esm".equals(n.nivel()));
            // Cohorte sensible vista por un ESM: sin ingresos ni salidas mensuales (un ingreso de 1 es un diagnóstico nuevo identificable)
            boolean sinMovimientos = "esm".equals(n.nivel()) && sensible(codigo);
            r.put("version", v.get("version"));
            r.put("criterio", v.get("criterio_texto") + " · v" + v.get("version"));
            r.put("estado", v.get("estado") == null ? "sin_calcular" : v.get("estado"));
            r.put("motivo", v.get("estado") == null ? sinCalcular(j, versionId, c.cargueId()) : v.get("motivo"));
            if (sinMovimientos) {
                r.put("avisoSupresion", "Cohorte sensible en el nivel ESM: ingresos, salidas y la composición por diagnóstico o medicamento no se muestran.");
            }
            r.put("fuentes", v.get("fuentes"));
            r.put("corte", c.cargueId());
            r.put("corteTexto", "Corte " + AnaliticaConsulta.fechaHora(c.aplicadoEn()) + " (cargue " + c.cargueId() + ")");
            r.put("periodo", AnaliticaConsulta.fecha(p[0]) + " – " + AnaliticaConsulta.fecha(YearMonth.from(p[1]).atEndOfMonth()));
            r.put("nivel", n.texto());
            if (!"evaluada".equals(v.get("estado"))) {
                return r;
            }
            Criterio crit = AnaliticaCohortes.leer(riesgo.deJson((String) v.get("criterios")));
            r.put("serie", serie(j, versionId, c.cargueId(), p, n, k, sinMovimientos));
            if (crit instanceof AnaliticaCohortes.Particion) {
                r.put("grupos", grupos(j, versionId, c.cargueId(), p[1], n, k));
                return r;
            }
            // Comparación con el periodo anterior de igual duración (meses)
            int meses = (int) java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(p[0]), YearMonth.from(p[1])) + 1;
            LocalDate[] previo = {p[0].minusMonths(meses), p[0].minusMonths(1)};
            Map<String, Object> comp = new LinkedHashMap<>();
            Long pa = pacientes(j, versionId, c.cargueId(), p[0], p[1], n);
            Long pb = pacientes(j, versionId, c.cargueId(), previo[0], previo[1], n);
            Long[] sa = sumas(j, versionId, c.cargueId(), p, n);
            Long[] sb = sumas(j, versionId, c.cargueId(), previo, n);
            comp.put("periodoAnterior", AnaliticaConsulta.fecha(previo[0]) + " – " + AnaliticaConsulta.fecha(YearMonth.from(previo[1]).atEndOfMonth()));
            boolean ocultoA = SupresionRiesgo.oculta(pa, k);
            boolean ocultoB = SupresionRiesgo.oculta(pb, k);
            comp.put("filas", List.of(filaComparacion("Pacientes distintos", pa, pb, false, ocultoA, ocultoB, k),
                    filaComparacion("Dispensaciones", sa[0], sb[0], false, ocultoA, ocultoB, k), filaComparacion("Gasto", sa[1], sb[1], true, ocultoA, ocultoB, k)));
            r.put("comparacion", comp);
            r.put("niveles", niveles(j, versionId, codigo, c, p, n));
            return r;
        });
    }

    /** Fila de la comparación; si los pacientes de un periodo se ocultan, también sus dispensaciones y su gasto (y la variación). */
    private static Map<String, Object> filaComparacion(String que, Long a, Long b, boolean moneda, boolean ocultoA, boolean ocultoB, int k) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("indicador", que);
        BigDecimal va = a == null ? null : moneda ? BigDecimal.valueOf(a, 2) : BigDecimal.valueOf(a);
        BigDecimal vb = b == null ? null : moneda ? BigDecimal.valueOf(b, 2) : BigDecimal.valueOf(b);
        f.put("actual", ocultoA ? SupresionRiesgo.texto(k) : va == null ? "—" : moneda ? AnaliticaConsulta.moneda(va) : AnaliticaConsulta.entero(va.longValue()));
        f.put("anterior", ocultoB ? SupresionRiesgo.texto(k) : vb == null ? "—" : moneda ? AnaliticaConsulta.moneda(vb) : AnaliticaConsulta.entero(vb.longValue()));
        if (va != null && vb != null && !ocultoA && !ocultoB) {
            AnaliticaConsulta.Variacion v = AnaliticaConsulta.variacion(va, vb);
            f.put("variacion", moneda ? AnaliticaConsulta.moneda(v.absoluta()) : AnaliticaConsulta.decimal(v.absoluta()));
            f.put("variacionPct", AnaliticaConsulta.porcentaje(v.porcentaje()));
        } else {
            f.put("variacion", null);
            f.put("variacionPct", null);
        }
        return f;
    }

    /**
     * Serie mensual del nivel: una celda oculta (0 &lt; pacientes &lt; k) oculta también sus dispensaciones, unidades, gasto, ingresos y salidas;
     * ingresos y salidas se ocultan además por su propio valor; en las particiones, la supresión complementaria es por mes.
     */
    private List<Map<String, Object>> serie(JdbcClient j, long version, long corte, LocalDate[] p, Nivel n, int k, boolean sinMovimientos) {
        List<Map<String, Object>> filas = new ArrayList<>();
        Map<String, List<Map<String, Object>>> porMes = new LinkedHashMap<>();
        Map<String, List<Long>> realesPorMes = new LinkedHashMap<>();
        j.sql("""
                SELECT mes, grupo, sum(pacientes), sum(ingresos), sum(salidas), sum(dispensaciones), sum(unidades_milesimas), sum(gasto_centavos)
                FROM ana_cohortes_serie WHERE version_id = :v AND corte_cargue_id = :c AND mes BETWEEN :d AND :h AND nivel = :n AND codigo = ANY(:cod)
                GROUP BY mes, grupo ORDER BY mes, grupo""").param("v", version).param("c", corte).param("d", p[0]).param("h", p[1]).param("n", n.nivel())
                .param("cod", n.codigos().toArray(String[]::new)).query((rs, x) -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    LocalDate mes = rs.getDate(1).toLocalDate();
                    long pac = rs.getLong(3);
                    f.put("mes", AnaliticaConsulta.etiquetaPeriodo(mes, Granularidad.MES));
                    f.put("grupo", rs.getString(2));
                    boolean oculto = SupresionRiesgo.pacientes(f, pac, k);
                    long ing = rs.getLong(4);
                    long sal = rs.getLong(5);
                    f.put("ingresos", oculto || sinMovimientos || SupresionRiesgo.oculta(ing, k) ? null : ing);
                    f.put("salidas", oculto || sinMovimientos || SupresionRiesgo.oculta(sal, k) ? null : sal);
                    f.put("dispensaciones", oculto ? null : rs.getLong(6));
                    f.put("unidades", oculto ? null : BigDecimal.valueOf(rs.getLong(7), 3));
                    f.put("gasto", oculto ? null : BigDecimal.valueOf(rs.getLong(8), 2));
                    f.put("gastoTexto", SupresionRiesgo.moneda(BigDecimal.valueOf(rs.getLong(8), 2), oculto, k));
                    filas.add(f);
                    if (!rs.getString(2).isEmpty()) {
                        porMes.computeIfAbsent((String) f.get("mes"), z -> new ArrayList<>()).add(f);
                        realesPorMes.computeIfAbsent((String) f.get("mes"), z -> new ArrayList<>()).add(pac);
                    }
                    return null;
                }).list();
        porMes.forEach((mes, l) -> SupresionRiesgo.complementaria(l, realesPorMes.get(mes), k));
        return filas;
    }

    /**
     * Comparación entre niveles: por Fuerza (alcance nacional) o por ESM de la Fuerza (alcance de una Fuerza: solo las celdas de esa Fuerza,
     * un ESM compartido no mezcla cifras de otra), con pacientes distintos de la membresía y supresión complementaria (los niveles suman el
     * total visible del nivel superior).
     */
    private List<Map<String, Object>> niveles(JdbcClient j, long version, String codigoCohorte, Corte c, LocalDate[] p, Nivel n) {
        String nivel = n.nivel().equals("nacional") ? "fuerza" : n.nivel().equals("fuerza") ? "esm" : null;
        if (nivel == null) {
            return List.of();
        }
        int k = k(codigoCohorte, "esm".equals(nivel));
        String fuerza = "esm".equals(nivel) ? n.codigos().getFirst() : "";
        Map<String, Long[]> sumas = new TreeMap<>();
        j.sql("""
                SELECT codigo, sum(dispensaciones), sum(gasto_centavos) FROM ana_cohortes_serie WHERE version_id = :v AND corte_cargue_id = :c
                AND mes BETWEEN :d AND :h AND nivel = :n AND fuerza = :f AND grupo = '' GROUP BY codigo""").param("v", version).param("c", c.cargueId())
                .param("d", p[0]).param("h", p[1]).param("n", nivel).param("f", fuerza)
                .query((rs, x) -> sumas.put(rs.getString(1), new Long[] {rs.getLong(2), rs.getLong(3), null})).list();
        if (conMiembros(j, version, c.cargueId())) {
            String sql = "esm".equals(nivel)
                    ? "SELECT substr(u, length(:f) + 2), count(DISTINCT paciente_id) FROM ana_cohortes_miembros, unnest(fuerzas_esms) u WHERE version_id = :v"
                            + " AND corte_cargue_id = :c AND mes BETWEEN :d AND :h AND u LIKE :pre GROUP BY 1"
                    : "SELECT u, count(DISTINCT paciente_id) FROM ana_cohortes_miembros, unnest(fuerzas) u WHERE version_id = :v AND corte_cargue_id = :c"
                            + " AND mes BETWEEN :d AND :h GROUP BY u";
            Map<String, Object> pm = new LinkedHashMap<>(Map.of("v", version, "c", c.cargueId(), "d", p[0], "h", p[1]));
            if ("esm".equals(nivel)) {
                pm.put("f", fuerza);
                pm.put("pre", fuerza + "|%");
            }
            j.sql(sql).params(pm).query((rs, x) -> {
                        Long[] s = sumas.get(rs.getString(1));
                        if (s != null) {
                            s[2] = rs.getLong(2);
                        }
                        return null;
                    }).list();
        }
        List<Map<String, Object>> r = new ArrayList<>();
        List<Long> reales = new ArrayList<>();
        sumas.forEach((cod, s) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nivel", nivel);
            m.put("codigo", cod);
            boolean oculto = SupresionRiesgo.pacientes(m, s[2], k);
            m.put("dispensaciones", oculto ? null : s[0]);
            m.put("gasto", SupresionRiesgo.moneda(BigDecimal.valueOf(s[1], 2), oculto, k));
            r.add(m);
            reales.add(s[2]);
        });
        SupresionRiesgo.complementaria(r, reales, k);
        return r;
    }

    // ---------- Composición ----------

    /** Dimensiones de la composición (P5: nivel ATC, principio activo, medicamento normalizado, CIE-10, Fuerza y ESM). */
    static Dimension dimension(String por) {
        return switch (por == null ? "atc3" : por) {
            case "atc1" -> Dimension.ATC1;
            case "atc2" -> Dimension.ATC2;
            case "atc3" -> Dimension.ATC3;
            case "atc4" -> Dimension.ATC4;
            case "atc5" -> Dimension.ATC5;
            case "principio" -> Dimension.PRINCIPIO_ACTIVO;
            case "medicamento" -> Dimension.MEDICAMENTO;
            case "cie10" -> Dimension.CIE10;
            case "fuerza" -> Dimension.FUERZA;
            case "esm" -> Dimension.ESM;
            default -> throw ErrorNegocio.validacion("Composición no válida: use atc1–atc5, principio, medicamento, cie10, fuerza o esm.");
        };
    }

    /**
     * Composición del gasto y de los pacientes de la cohorte por una dimensión (Top 20 por gasto + «Resto»): dispensaciones de los miembros en
     * los meses en que lo son. ATC, principio y medicamento por la tabla maestra (como los tableros); CIE-10 homologado. Solo con el último
     * corte calculado de la versión (es el que conserva la membresía).
     */
    public Map<String, Object> composicion(long id, String por, Integer version, Long corteId, LocalDate desde, LocalDate hasta, String fuerza, Alcance alcance) {
        Dimension d = dimension(por);
        return consultas.leer(j -> {
            Map<String, Object> co = cohorte(j, id);
            Corte c = corte(j, corteId);
            Map<String, Object> v = version(j, id, version, c.cargueId());
            long versionId = ((Number) v.get("id")).longValue();
            if (!"evaluada".equals(v.get("estado"))) {
                throw ErrorNegocio.validacion("La cohorte no está evaluada en este corte: " + (v.get("estado") == null ? SIN_CALCULAR : v.get("motivo")));
            }
            if (AnaliticaCohortes.leer(riesgo.deJson((String) v.get("criterios"))) instanceof AnaliticaCohortes.Particion) {
                // A7.1: la partición guarda ahora su membresía (todos los pacientes con su grupo): su «composición» sería la de todos; por
                // grupo se consulta en los tableros con el filtro GCF o GRC
                throw ErrorNegocio.validacion("GCF y GRC son particiones: consulte la composición de un grupo en los tableros con el filtro GCF o GRC.");
            }
            if (!conMiembros(j, versionId, c.cargueId())) {
                throw ErrorNegocio.validacion("La composición se calcula con los últimos cortes de la versión; este corte ya solo conserva la serie mensual.");
            }
            LocalDate[] p = periodo(c, desde, hasta);
            Nivel n = nivel(j, alcance, c, fuerza);
            String codigo = (String) co.get("codigo");
            if ("esm".equals(n.nivel()) && sensible(codigo) && java.util.Set.of(Dimension.CIE10, Dimension.MEDICAMENTO, Dimension.PRINCIPIO_ACTIVO,
                    Dimension.ATC4, Dimension.ATC5).contains(d)) {
                throw ErrorNegocio.validacion("La cohorte «" + co.get("nombre") + "» es sensible: en el nivel ESM su composición solo se muestra por ATC "
                        + "de nivel 1 a 3, Fuerza o ESM (supresión de celdas pequeñas).");
            }
            int k = k(codigo, "esm".equals(n.nivel()) || d == Dimension.ESM);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("v", versionId);
            params.put("corte", c.cargueId());
            params.put("d", p[0]);
            params.put("h", p[1]);
            params.put("fd", p[0]);
            params.put("fh", YearMonth.from(p[1]).atEndOfMonth());
            params.put("cargues", c.cargues().toArray(Long[]::new));
            params.put("hv", c.homVersion());
            params.put("cod", n.codigos().toArray(String[]::new));
            String alc = switch (n.nivel()) {
                case "fuerza" -> " AND x.fuerza = ANY(CAST(:cod AS text[]))";
                case "esm" -> " AND x.esm_codigo = ANY(CAST(:cod AS text[]))";
                default -> "";
            };
            List<String> ctes = new ArrayList<>();
            if (d.deTablaMaestra()) {
                ctes.add(SemanticaSql.CTE_TM);
            }
            if (d == Dimension.CIE10) {
                ctes.add(SemanticaSql.CTE_TC);
            }
            ctes.add("m AS MATERIALIZED (SELECT mes, paciente_id FROM ana_cohortes_miembros WHERE version_id = :v AND corte_cargue_id = :corte AND mes BETWEEN :d AND :h"
                    + filtroNivel(n) + ")");
            String sql = "WITH " + String.join(", ", ctes) + " SELECT k, count(*) AS pacientes, sum(g) AS gasto, sum(u) AS unidades, sum(n) AS dispensaciones"
                    + " FROM (SELECT " + SemanticaSql.expresion(d, Granularidad.MES) + " AS k, x.paciente_id, sum(x.valor_centavos) AS g,"
                    + " sum(x.cantidad_milesimas) AS u, count(*) AS n FROM ana_hechos_tablero x"
                    + " JOIN m ON m.paciente_id = x.paciente_id AND m.mes = date_trunc('month', x.fecha)::date"
                    + (d.deTablaMaestra() ? " LEFT JOIN tm ON tm.origen_id = x.medicamento_id" : "")
                    + (d == Dimension.CIE10 ? " LEFT JOIN tc ON tc.codigo_original = x.cie10" : "")
                    + " WHERE x.cargue_id = ANY(CAST(:cargues AS bigint[])) AND x.fecha BETWEEN :fd AND :fh" + alc + " GROUP BY 1, 2) s GROUP BY k";
            List<Object[]> filas = new ArrayList<>(j.sql(sql).params(params).query((rs, x) -> new Object[] {rs.getString(1), rs.getLong(2),
                rs.getLong(3), rs.getLong(4), rs.getLong(5)}).list());
            filas.sort(Comparator.comparing((Object[] f) -> (Long) f[2]).reversed().thenComparing(f -> (String) f[0]));
            long total = filas.stream().mapToLong(f -> (Long) f[2]).sum();
            List<Map<String, Object>> out = new ArrayList<>();
            List<Long> reales = new ArrayList<>();
            long restoG = 0;
            long restoN = 0;
            int restoFilas = 0;
            for (int i = 0; i < filas.size(); i++) {
                Object[] f = filas.get(i);
                if (i >= AnaliticaConsulta.TOP) {
                    restoG += (Long) f[2];
                    restoN += (Long) f[4];
                    restoFilas++;
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                long pac = (Long) f[1];
                m.put("clave", f[0]);
                boolean oculto = SupresionRiesgo.pacientes(m, pac, k);
                // Celda oculta: tampoco su gasto ni sus dispensaciones (con un paciente serían los de esa persona)
                m.put("dispensaciones", oculto ? null : f[4]);
                m.put("gasto", oculto ? null : BigDecimal.valueOf((Long) f[2], 2));
                m.put("gastoTexto", SupresionRiesgo.moneda(BigDecimal.valueOf((Long) f[2], 2), oculto, k));
                m.put("participacion", oculto ? SupresionRiesgo.texto(k) : AnaliticaConsulta.porcentaje(AnaliticaConsulta.participacion(
                        BigDecimal.valueOf((Long) f[2]), BigDecimal.valueOf(total))));
                out.add(m);
                reales.add(pac);
            }
            // El gasto de las filas suma el total: una sola fila oculta se deduciría por diferencia
            SupresionRiesgo.complementaria(out, reales, k);
            if (restoFilas > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("clave", AnaliticaConsulta.etiquetaResto(restoFilas));
                m.put("pacientes", null);
                m.put("pacientesTexto", "—");
                m.put("dispensaciones", restoN);
                m.put("gasto", BigDecimal.valueOf(restoG, 2));
                m.put("gastoTexto", AnaliticaConsulta.moneda(BigDecimal.valueOf(restoG, 2)));
                m.put("participacion", AnaliticaConsulta.porcentaje(AnaliticaConsulta.participacion(BigDecimal.valueOf(restoG), BigDecimal.valueOf(total))));
                out.add(m);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("cohorte", co.get("nombre"));
            r.put("version", v.get("version"));
            r.put("criterio", v.get("criterio_texto") + " · v" + v.get("version"));
            r.put("por", d.codigo());
            r.put("porTexto", d.etiqueta());
            r.put("periodo", AnaliticaConsulta.fecha(p[0]) + " – " + AnaliticaConsulta.fecha(YearMonth.from(p[1]).atEndOfMonth()));
            r.put("corte", c.cargueId());
            r.put("gastoTotal", BigDecimal.valueOf(total, 2));
            r.put("gastoTotalTexto", AnaliticaConsulta.moneda(BigDecimal.valueOf(total, 2)));
            r.put("filas", out);
            r.put("nota", "Un paciente puede estar en varias filas (pacientes distintos por fila, no se suman). Los no homologados van a «Sin homologar».");
            return r;
        });
    }

    // ---------- Descarga (P5: «Exportación de la información») ----------

    /** Serie o composición como resultado de tablero (mismos metadatos de 14.8 que los tableros, más el criterio y la versión). */
    public ResultadoTablero paraDescarga(long id, String vista, String por, Integer version, Long corteId, LocalDate desde, LocalDate hasta, String fuerza,
                                         Alcance alcance) {
        Map<String, Object> det = "composicion".equals(vista) ? composicion(id, por, version, corteId, desde, hasta, fuerza, alcance)
                : detalle(id, version, corteId, desde, hasta, fuerza, alcance);
        if (det.get("corte") == null) {
            throw ErrorNegocio.validacion("Aún no hay cargues aplicados: no hay nada que descargar.");
        }
        return consultas.leer(j -> {
            Corte c = corte(j, ((Number) det.get("corte")).longValue());
            Map<String, Object> co = cohorte(j, id);
            String criterio = String.valueOf(det.get("criterio"));
            List<Tabla> tablas = new ArrayList<>();
            List<Kpi> kpis = new ArrayList<>();
            if ("composicion".equals(vista)) {
                List<Columna> col = List.of(new Columna("clave", String.valueOf(det.get("porTexto")), Tipo.TEXTO), new Columna("pacientes", "Pacientes distintos",
                        Tipo.ENTERO), new Columna("dispensaciones", "Dispensaciones", Tipo.ENTERO), new Columna("gasto", "Gasto", Tipo.MONEDA));
                List<Fila> fs = new ArrayList<>();
                for (Object o : (List<?>) det.get("filas")) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    fs.add(ResultadoTablero.fila(String.valueOf(m.get("clave")), null, col, java.util.Arrays.asList(m.get("clave"), m.get("pacientes"),
                            m.get("dispensaciones"), m.get("gasto")), "dato"));
                }
                tablas.add(new Tabla("composicion", "Composición por " + det.get("porTexto") + " (Top 20)", "barras", "gasto", null, col, fs, String.valueOf(det.get("nota"))));
            } else {
                List<Columna> col = List.of(new Columna("mes", "Mes", Tipo.TEXTO), new Columna("grupo", "Grupo", Tipo.TEXTO), new Columna("pacientes",
                        "Pacientes distintos", Tipo.ENTERO), new Columna("ingresos", "Ingresos", Tipo.ENTERO), new Columna("salidas", "Salidas", Tipo.ENTERO),
                        new Columna("dispensaciones", "Dispensaciones", Tipo.ENTERO), new Columna("unidades", "Unidades", Tipo.UNIDADES), new Columna("gasto", "Gasto",
                                Tipo.MONEDA));
                List<Fila> fs = new ArrayList<>();
                Object serie = det.get("serie");
                if (serie instanceof List<?> l) {
                    for (Object o : l) {
                        Map<?, ?> m = (Map<?, ?>) o;
                        fs.add(ResultadoTablero.fila(m.get("mes") + "|" + m.get("grupo"), null, col, java.util.Arrays.asList(m.get("mes"), m.get("grupo"),
                                m.get("pacientes"), m.get("ingresos"), m.get("salidas"), m.get("dispensaciones"), m.get("unidades"), m.get("gasto")), "dato"));
                    }
                }
                tablas.add(new Tabla("serie", "Serie mensual de la cohorte", "ninguno", null, null, col, fs, "Pacientes distintos por mes y nivel: no se suman entre meses."));
            }
            long registros = tablas.getFirst().filas().stream().mapToLong(f -> f.valores().get("composicion".equals(vista) ? 2 : 5) instanceof Number x
                    ? x.longValue() : 0).sum();
            String fuente = "Cohorte «" + co.get("nombre") + "» · criterio: " + criterio + " · tabla maestra " + (c.homNumero() == null ? "(sin versión)"
                    : "v" + c.homNumero()) + " · cargues " + String.join(", ", c.cargues().stream().map(String::valueOf).toList());
            Metadatos m = new Metadatos(c.cargueId(), c.aplicadoEn(), "Corte " + AnaliticaConsulta.fechaHora(c.aplicadoEn()) + " (cargue " + c.cargueId() + ")",
                    c.homNumero(), c.cargues(), fuente, null, null, String.valueOf(det.get("periodo")), "Mes", "Sin comparación", null,
                    String.valueOf(det.getOrDefault("nivel", consultas.alcanceTexto(j, alcance))), consultas.alcanceTexto(j, alcance),
                    List.of(new FiltroAplicado("cohorte", "Cohorte", List.of((String) co.get("codigo")), List.of(co.get("nombre") + " · v" + det.get("version")))),
                    registros, c.datosHasta(), consultas.textoSupresion());
            String titulo = "Cohorte " + co.get("nombre") + " · " + ("composicion".equals(vista) ? "composición" : "serie");
            List<String> obs = List.of("Criterio: " + criterio, METODOLOGIA);
            return new ResultadoTablero("cohortes", titulo, "nacional", List.of("nacional"), true, null, kpis, tablas, List.of(), obs, List.of(), m,
                    huella(c, criterio, tablas));
        });
    }

    /** Huella SHA-256 del contenido (corte, criterio y cifras): misma cohorte, versión, periodo y corte ⇒ misma huella. */
    private static String huella(Corte c, String criterio, List<Tabla> tablas) {
        List<String> l = new ArrayList<>();
        l.add("corte|" + c.cargueId() + "|" + c.homVersion() + "|" + c.cargues());
        l.add("criterio|" + criterio);
        for (Tabla t : tablas) {
            for (Fila f : t.filas()) {
                l.add("fila|" + t.id() + "|" + f.clave() + "|" + String.join("|", f.valores().stream().map(AnaliticaConsulta::canonico).toList()));
            }
        }
        return AnaliticaConsulta.huella(l);
    }

    // ---------- Definición y versiones ----------

    /** Crea una cohorte (versión 1) y la calcula sobre el corte vigente sin esperar otro cargue. */
    public Map<String, Object> crear(Map<String, Object> cuerpo, UsuarioSesion u) {
        String codigo = texto(cuerpo.get("codigo"));
        if (codigo == null || !AnaliticaCohortes.CODIGO.matcher(codigo).matches()) {
            throw ErrorNegocio.validacion("El código va en minúsculas y guion bajo (3 a 40 caracteres).", "codigo");
        }
        String nombre = nombre(cuerpo.get("nombre"));
        String descripcion = descripcion(cuerpo.get("descripcion"));
        Criterio c = criterio(cuerpo.get("criterios"));
        long[] ids = escritura.execute(s -> {
            if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM ana_cohortes WHERE codigo = :c)").param("c", codigo).query(Boolean.class).single()) {
                throw ErrorNegocio.conflicto("Ya existe una cohorte con el código «" + codigo + "».");
            }
            long id = jdbc.sql("""
                    INSERT INTO ana_cohortes (codigo, nombre, descripcion, predefinida, creada_por) VALUES (:c, :n, :d, false, :u) RETURNING id""")
                    .param("c", codigo).param("n", nombre).param("d", descripcion).param("u", u.id()).query(Long.class).single();
            long v = nuevaVersion(id, 1, c, u);
            bitacora.registrar("analitica_cohorte_version", "Cohorte «" + codigo + "» v1 creada: " + Bitacora_.cortar(AnaliticaCohortes.texto(c), 300), u.id(),
                    u.usuario());
            return new long[] {id, v};
        });
        recalcularVersion(ids[1], u);
        return Map.of("id", ids[0], "version", 1);
    }

    /** Edita nombre, descripción, estado y criterios; cambiar los criterios crea la versión siguiente (la anterior queda con sus resultados). */
    public Map<String, Object> editar(long id, Map<String, Object> cuerpo, UsuarioSesion u) {
        long[] nueva = escritura.execute(s -> {
            Map<String, Object> co = jdbc.sql("SELECT id, codigo, nombre, descripcion, predefinida, activa FROM ana_cohortes WHERE id = :id FOR UPDATE")
                    .param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> new ErrorNegocio(HttpStatus.NOT_FOUND, "La cohorte no existe."));
            String nombre = cuerpo.containsKey("nombre") ? nombre(cuerpo.get("nombre")) : (String) co.get("nombre");
            String descripcion = cuerpo.containsKey("descripcion") ? descripcion(cuerpo.get("descripcion")) : (String) co.get("descripcion");
            boolean activa = cuerpo.containsKey("activa") ? Boolean.TRUE.equals(cuerpo.get("activa")) : (Boolean) co.get("activa");
            jdbc.sql("UPDATE ana_cohortes SET nombre = :n, descripcion = :d, activa = :a WHERE id = :id").param("n", nombre).param("d", descripcion)
                    .param("a", activa).param("id", id).update();
            if (!cuerpo.containsKey("criterios")) {
                return new long[] {-1, -1};
            }
            Criterio c = criterio(cuerpo.get("criterios"));
            Map<String, Object> vigente = jdbc.sql("SELECT id, version, criterios::text AS criterios, criterio_texto FROM ana_cohortes_versiones "
                    + "WHERE cohorte_id = :id AND vigente FOR UPDATE").param("id", id).query().listOfRows().getFirst();
            if (riesgo.deJson((String) vigente.get("criterios")).equals(AnaliticaCohortes.aMapa(c))) {
                return new long[] {-1, -1};
            }
            int numero = ((Number) vigente.get("version")).intValue() + 1;
            jdbc.sql("UPDATE ana_cohortes_versiones SET vigente = false WHERE id = :v").param("v", vigente.get("id")).update();
            long v = nuevaVersion(id, numero, c, u);
            bitacora.registrarCambios("analitica_cohorte_version", "Cohorte «" + co.get("codigo") + "» v" + numero,
                    Map.of("criterio", vigente.get("criterio_texto")), Map.of("criterio", AnaliticaCohortes.texto(c)), java.util.Set.of(), u.id(), u.usuario());
            return new long[] {v, numero};
        });
        if (nueva[0] > 0) {
            recalcularVersion(nueva[0], u);
            return Map.of("id", id, "version", nueva[1]);
        }
        return Map.of("id", id);
    }

    private long nuevaVersion(long cohorte, int numero, Criterio c, UsuarioSesion u) {
        return jdbc.sql("""
                INSERT INTO ana_cohortes_versiones (cohorte_id, version, criterios, criterio_texto, vigente, creada, creada_por)
                VALUES (:c, :n, CAST(:crit AS jsonb), :t, true, :a, :u) RETURNING id""").param("c", cohorte).param("n", numero)
                .param("crit", riesgo.aJson(AnaliticaCohortes.aMapa(c))).param("t", Bitacora_.cortar(AnaliticaCohortes.texto(c), 2000))
                .param("a", OffsetDateTime.now(reloj)).param("u", u.id()).query(Long.class).single();
    }

    private void recalcularVersion(long version, UsuarioSesion u) {
        riesgo.corteVigente().ifPresent(c -> riesgo.programar(c, "version", u.id(), List.of(version), false));
    }

    /** Recalcula bajo demanda la versión vigente de la cohorte sobre el corte vigente. */
    public Map<String, Object> recalcular(long id, UsuarioSesion u) {
        long v = jdbc.sql("SELECT id FROM ana_cohortes_versiones WHERE cohorte_id = :id AND vigente").param("id", id).query(Long.class).optional()
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.NOT_FOUND, "La cohorte no existe."));
        long corte = riesgo.corteVigente().orElseThrow(() -> ErrorNegocio.validacion("Aún no hay cargues aplicados."));
        RecalculoRiesgo.Programada p = riesgo.programar(corte, "usuario", u.id(), List.of(v), false);
        return Map.of("ejecucion", p.ejecucionCohortes(), "corte", corte);
    }

    /** Recálculos recientes (ejecuciones) con su duración. */
    public List<Map<String, Object>> ejecuciones() {
        return jdbc.sql("""
                SELECT e.id, e.origen, e.corte_cargue_id, e.inicio, e.fin, e.estado, e.versiones, e.filas, e.duracion_ms, e.etapas_ms::text AS etapas, e.error
                FROM ana_cohortes_ejecuciones e ORDER BY e.id DESC LIMIT 50""").query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("origen", rs.getString("origen"));
                    m.put("corte", rs.getLong("corte_cargue_id"));
                    m.put("inicio", AnaliticaConsulta.fechaHora(rs.getTimestamp("inicio").toInstant().atOffset(java.time.ZoneOffset.UTC)));
                    m.put("estado", rs.getString("estado"));
                    m.put("versiones", rs.getInt("versiones"));
                    m.put("filas", rs.getObject("filas"));
                    m.put("duracionMs", rs.getObject("duracion_ms"));
                    m.put("etapasMs", riesgo.deJson(rs.getString("etapas")));
                    m.put("error", rs.getString("error"));
                    return m;
                }).list();
    }

    private Criterio criterio(Object o) {
        try {
            return AnaliticaCohortes.leer(o);
        } catch (IllegalArgumentException e) {
            throw ErrorNegocio.validacion(e.getMessage(), "criterios");
        }
    }

    private static String texto(Object o) {
        return o == null ? null : String.valueOf(o).strip();
    }

    private static String nombre(Object o) {
        String n = texto(o);
        if (n == null || n.length() < 3 || n.length() > 120) {
            throw ErrorNegocio.validacion("El nombre lleva de 3 a 120 caracteres.", "nombre");
        }
        return n;
    }

    private static String descripcion(Object o) {
        String d = texto(o);
        if (d != null && d.length() > 500) {
            throw ErrorNegocio.validacion("La descripción lleva máximo 500 caracteres.", "descripcion");
        }
        return d == null || d.isEmpty() ? null : d;
    }

    /** Alias corto de la utilidad de recorte de la bitácora. */
    private static final class Bitacora_ {
        static String cortar(String s, int n) {
            return co.com.agsamericas.digsa.reglas.Bitacora.cortar(s, n);
        }
    }
}
