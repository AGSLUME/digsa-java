package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.analitica.tableros.PropiedadesTableros;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Columna;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Fila;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.FiltroAplicado;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Metadatos;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Tabla;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Corte;
import co.com.agsamericas.digsa.analitica.tableros.ServicioConsultaAnalitica;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Estado;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Parametros;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Tipo;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Huellas;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consola de alertas tempranas (analitica-cohortes-alertas; A5 14.7): lista con contexto obligatorio (umbral, valor observado, periodo,
 * dimensión y valor, severidad, registros y pacientes agregados) ordenada por prioridad (severidad y gasto), detalle con el análisis
 * técnico (P4) y el historial, validación humana (RESP L5175), parametrización con traza (RESP L5671), ejecuciones con el resumen por tipo
 * («no evaluado: motivo»), referencias cargables y los atípicos agregados del tablero 11. El alcance se aplica en SQL: nacional ve todo;
 * Sanidad, solo las de nivel Fuerza o ESM de su Fuerza (otra: 404); el ESM no entra a la consola (403) y en el tablero 11 solo ve conteos de
 * su establecimiento.
 */
@Service
public class ServicioAlertasAnaliticas {

    static final Set<String> FILTROS = Set.of("tipo", "severidad", "estado", "desde", "hasta", "nivel", "fuerza", "esm", "cohorte", "dimension", "valor", "atc");
    static final int MAX_REFERENCIA = 100_000;

    private final JdbcClient jdbc;
    private final ServicioConsultaAnalitica consultas;
    private final RecalculoRiesgo riesgo;
    private final PropiedadesRiesgo propiedades;
    private final PropiedadesTableros tableros;
    private final RegistroBitacora bitacora;
    private final PerfilesOperacion perfiles;
    private final Clock reloj;
    private final TransactionTemplate escritura;
    private final co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa configuracion;

    ServicioAlertasAnaliticas(JdbcClient jdbc, ServicioConsultaAnalitica consultas, RecalculoRiesgo riesgo, PropiedadesRiesgo propiedades,
                              PropiedadesTableros tableros, RegistroBitacora bitacora, PerfilesOperacion perfiles, Clock reloj, PlatformTransactionManager tm,
                              co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa configuracion) {
        this.configuracion = configuracion;
        this.jdbc = jdbc;
        this.consultas = consultas;
        this.riesgo = riesgo;
        this.propiedades = propiedades;
        this.tableros = tableros;
        this.bitacora = bitacora;
        this.perfiles = perfiles;
        this.reloj = reloj;
        this.escritura = new TransactionTemplate(tm);
    }

    // ---------- Alcance ----------

    /** Predicado del alcance sobre ana_alertas (alias a). El ESM no llega aquí (403 antes). */
    private static String alcance(Alcance a, Map<String, Object> p) {
        return switch (a) {
            case Alcance.Todo t -> "TRUE";
            case Alcance.Fuerza f -> {
                p.put("alcFuerza", f.codigo());
                yield "a.nivel IN ('fuerza', 'esm') AND a.fuerza = :alcFuerza";
            }
            default -> throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Su perfil no tiene acceso a la consola de alertas.");
        };
    }

    // ---------- Lista ----------

    private static final String COLUMNAS = """
            a.id, a.tipo, a.severidad, a.estado, a.nivel, a.fuerza, a.esm, a.dimension, a.dimension_texto, a.valor, a.valor_texto, a.periodo_desde,
            a.periodo_hasta, a.umbral, a.valor_observado, a.valor_referencia, a.unidad, a.registros, a.pacientes, a.gasto, a.generada, a.corte_cargue_id,
            a.cohorte_id, a.parametros_version, a.ultima_evaluacion_cargue_id, a.aviso""";

    /** Alertas con los filtros de la lista blanca, ordenadas por prioridad (severidad y gasto), paginadas. */
    public Map<String, Object> listar(Map<String, String> filtros, int pagina, Alcance a) {
        Map<String, Object> p = new LinkedHashMap<>();
        String where = where(filtros, a, p);
        int tam = propiedades.pagina();
        long total = jdbc.sql("SELECT count(*) FROM ana_alertas a WHERE " + where).params(p).query(Long.class).single();
        int paginas = (int) Math.max(1, (total + tam - 1) / tam);
        int pg = Math.max(1, Math.min(pagina, paginas));
        p.put("lim", tam);
        p.put("off", (pg - 1) * tam);
        List<Map<String, Object>> alertas = jdbc.sql("SELECT " + COLUMNAS + " FROM ana_alertas a WHERE " + where
                + " ORDER BY CASE a.severidad WHEN 'alta' THEN 0 WHEN 'media' THEN 1 ELSE 2 END, a.gasto DESC, a.id DESC LIMIT :lim OFFSET :off").params(p)
                .query((rs, n) -> item(rs)).list();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", total);
        r.put("pagina", pg);
        r.put("paginas", paginas);
        r.put("alertas", alertas);
        r.put("resumen", resumen(where, p));
        return r;
    }

    /** WHERE con el alcance y los filtros (solo los de la lista blanca, con su forma validada). */
    private String where(Map<String, String> filtros, Alcance a, Map<String, Object> p) {
        List<String> w = new ArrayList<>();
        w.add(alcance(a, p));
        w.addAll(clausulas(filtros, p));
        return String.join(" AND ", w);
    }

    /** Cláusulas de los filtros (sin el alcance). */
    private List<String> clausulas(Map<String, String> filtros, Map<String, Object> p) {
        List<String> w = new ArrayList<>();
        for (Map.Entry<String, String> e : filtros.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().strip();
            if (v.isEmpty() || !FILTROS.contains(e.getKey())) {
                continue;
            }
            switch (e.getKey()) {
                case "tipo" -> {
                    Tipo t = Tipo.deCodigo(v).orElseThrow(() -> ErrorNegocio.validacion("Tipo de alerta no válido."));
                    w.add("a.tipo = :tipo");
                    p.put("tipo", t.codigo());
                }
                case "severidad" -> {
                    if (!Set.of("baja", "media", "alta").contains(v)) {
                        throw ErrorNegocio.validacion("Severidad no válida.");
                    }
                    w.add("a.severidad = :severidad");
                    p.put("severidad", v);
                }
                case "estado" -> {
                    Estado es = Estado.deCodigo(v).orElseThrow(() -> ErrorNegocio.validacion("Estado no válido."));
                    w.add("a.estado = :estado");
                    p.put("estado", es.codigo());
                }
                case "desde" -> {
                    w.add("a.periodo_hasta >= :desde");
                    p.put("desde", fecha(v));
                }
                case "hasta" -> {
                    w.add("a.periodo_desde <= :hasta");
                    p.put("hasta", fecha(v));
                }
                case "nivel" -> {
                    if (!AnaliticaAlertas.NIVELES.contains(v)) {
                        throw ErrorNegocio.validacion("Nivel no válido.");
                    }
                    w.add("a.nivel = :nivel");
                    p.put("nivel", v);
                }
                case "fuerza" -> {
                    codigo(v);
                    w.add("(a.fuerza = :fuerza OR a.dimension = 'fuerza' AND a.valor = :fuerza)");
                    p.put("fuerza", v);
                }
                case "esm" -> {
                    codigo(v);
                    w.add("(a.esm = :esm OR a.dimension = 'esm' AND a.valor = :esm)");
                    p.put("esm", v);
                }
                case "cohorte" -> {
                    w.add("a.cohorte_id = :cohorte");
                    p.put("cohorte", entero(v));
                }
                case "dimension" -> {
                    codigo(v);
                    w.add("a.dimension = :dimension");
                    p.put("dimension", v);
                }
                case "atc" -> {
                    String atc = AnaliticaCohortes.normalizarValor(AnaliticaCohortes.TipoCodigo.ATC, v);
                    if (atc == null) {
                        throw ErrorNegocio.validacion("ATC no válido.");
                    }
                    w.add("a.dimension LIKE 'atc%' AND a.valor LIKE :atc");
                    p.put("atc", atc + "%");
                }
                default -> {
                    if (v.length() > 200) {
                        throw ErrorNegocio.validacion("Valor demasiado largo.");
                    }
                    w.add("a.valor = :valor");
                    p.put("valor", v);
                }
            }
        }
        return w;
    }

    private static void codigo(String v) {
        if (!v.matches("^[A-Za-z0-9_.-]{1,40}$")) {
            throw ErrorNegocio.validacion("Código no válido.");
        }
    }

    private static LocalDate fecha(String v) {
        try {
            return LocalDate.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            throw ErrorNegocio.validacion("Fecha no válida (aaaa-mm-dd).");
        }
    }

    private static long entero(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw ErrorNegocio.validacion("Número no válido.");
        }
    }

    /** Conteos por tipo y severidad de la consulta y el resultado por tipo de la última ejecución terminada. */
    private Map<String, Object> resumen(String where, Map<String, Object> p) {
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Map<String, Long>> conteos = new LinkedHashMap<>();
        for (Tipo t : Tipo.values()) {
            conteos.put(t.codigo(), new LinkedHashMap<>(Map.of("baja", 0L, "media", 0L, "alta", 0L)));
        }
        jdbc.sql("SELECT a.tipo, a.severidad, count(*) FROM ana_alertas a WHERE " + where + " GROUP BY 1, 2").params(p)
                .query((rs, n) -> conteos.get(rs.getString(1)).put(rs.getString(2), rs.getLong(3))).list();
        // La última ejecución terminada, sea cual sea su estado: si falló, el resumen lo dice (no muestra como actual la anterior que salió bien)
        Map<String, Object> ultima = jdbc.sql("""
                SELECT id, estado, corte_cargue_id, por_tipo::text AS por_tipo, error, fin FROM ana_alertas_ejecuciones WHERE fin IS NOT NULL
                ORDER BY id DESC LIMIT 1""").query().listOfRows().stream().findFirst().orElse(Map.of());
        boolean fallo = "error".equals(ultima.get("estado")) || "interrumpida".equals(ultima.get("estado"));
        Map<String, Object> porTipo = ultima.get("por_tipo") == null ? Map.of() : riesgo.deJson((String) ultima.get("por_tipo"));
        Long vigente = riesgo.corteVigente().orElse(null);
        List<Map<String, Object>> tipos = new ArrayList<>();
        for (Tipo t : Tipo.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tipo", t.codigo());
            m.put("nombre", t.nombre());
            m.put("nivelesAdmitidos", t.nivelesAdmitidos());
            m.putAll(conteos.get(t.codigo()));
            Object x = porTipo.get(t.codigo());
            if (fallo) {
                m.put("evaluacion", "error");
                m.put("motivo", "La última ejecución del motor (" + ultima.get("id") + ") terminó «" + ultima.get("estado") + "»: " + ultima.get("error"));
            } else if (x instanceof Map<?, ?> e) {
                m.put("evaluacion", e.get("estado"));
                m.put("motivo", e.get("motivo"));
            } else {
                m.put("evaluacion", "sin_ejecutar");
            }
            tipos.add(m);
        }
        r.put("tipos", tipos);
        if (!ultima.isEmpty() && vigente != null && !vigente.equals(((Number) ultima.get("corte_cargue_id")).longValue())) {
            r.put("aviso", "El corte vigente (cargue " + vigente + ") aún no tiene alertas evaluadas: el resumen es del cargue " + ultima.get("corte_cargue_id") + ".");
        }
        return r;
    }

    private Map<String, Object> item(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        Tipo t = Tipo.deCodigo(rs.getString("tipo")).orElseThrow();
        m.put("id", rs.getLong("id"));
        m.put("tipo", t.codigo());
        m.put("tipoNombre", t.nombre());
        m.put("severidad", rs.getString("severidad"));
        m.put("estado", rs.getString("estado"));
        m.put("nivel", rs.getString("nivel"));
        m.put("nivelTexto", switch (rs.getString("nivel")) {
            case "fuerza" -> "Fuerza " + rs.getString("fuerza");
            case "esm" -> "ESM " + rs.getString("esm") + " (" + rs.getString("fuerza") + ")";
            default -> "Nacional";
        });
        m.put("fuerza", rs.getString("fuerza"));
        m.put("esm", rs.getString("esm"));
        m.put("dimension", rs.getString("dimension"));
        m.put("dimensionTexto", rs.getString("dimension_texto"));
        m.put("valor", rs.getString("valor"));
        m.put("valorTexto", rs.getString("valor_texto"));
        LocalDate d = rs.getDate("periodo_desde").toLocalDate();
        LocalDate h = rs.getDate("periodo_hasta").toLocalDate();
        m.put("desde", d);
        m.put("hasta", h);
        m.put("periodo", java.time.YearMonth.from(d).equals(java.time.YearMonth.from(h)) ? AnaliticaConsulta.etiquetaPeriodo(d, AnaliticaConsulta.Granularidad.MES)
                : AnaliticaConsulta.fecha(d) + " – " + AnaliticaConsulta.fecha(h));
        String unidad = rs.getString("unidad");
        BigDecimal umbral = rs.getBigDecimal("umbral");
        BigDecimal obs = rs.getBigDecimal("valor_observado");
        m.put("unidad", unidad);
        m.put("umbral", umbral);
        m.put("umbralTexto", AnaliticaAlertas.conUnidad(umbral, unidad));
        // Supresión de celdas pequeñas (revisión A4): en los tipos cuya unidad es «pacientes» el observado y la referencia SON conteos de
        // pacientes; se ocultan igual que la columna de pacientes
        int k = tableros.supresion();
        boolean dePacientes = "pacientes".equals(unidad);
        boolean obsOculto = dePacientes && AnaliticaAlertas.oculto(obs, k);
        m.put("observado", obsOculto ? null : obs);
        m.put("observadoTexto", obsOculto ? "<" + k + " pacientes" : AnaliticaAlertas.conUnidad(obs, unidad));
        BigDecimal ref = rs.getBigDecimal("valor_referencia");
        m.put("referenciaTexto", ref == null ? "—" : dePacientes && AnaliticaAlertas.oculto(ref, k) ? "<" + k : AnaliticaConsulta.decimal(ref));
        long pac = rs.getLong("pacientes");
        boolean oculto = k > 0 && pac > 0 && pac < k;
        m.put("pacientes", oculto ? null : pac);
        m.put("pacientesTexto", oculto ? "<" + k : AnaliticaConsulta.entero(pac));
        // Con la celda oculta, sus registros y su gasto tampoco salen (serían los de pocas personas)
        m.put("registros", oculto ? null : rs.getLong("registros"));
        m.put("registrosTexto", oculto ? "<" + k + " pacientes" : AnaliticaConsulta.entero(rs.getLong("registros")));
        m.put("gasto", oculto ? null : rs.getBigDecimal("gasto"));
        m.put("gastoTexto", oculto ? "Oculto (celda pequeña)" : AnaliticaConsulta.moneda(rs.getBigDecimal("gasto")));
        m.put("generada", AnaliticaConsulta.fechaHora(rs.getTimestamp("generada").toInstant().atOffset(ZoneOffset.UTC)));
        m.put("corte", rs.getLong("corte_cargue_id"));
        m.put("corteCifras", rs.getLong("ultima_evaluacion_cargue_id"));
        m.put("cohorteId", rs.getObject("cohorte_id"));
        m.put("parametrosVersion", rs.getInt("parametros_version"));
        m.put("aviso", rs.getString("aviso"));
        return m;
    }

    // ---------- Detalle ----------

    /** Alerta con su análisis técnico, la selección de sus registros de origen (14.9) y el historial de validación. 404 fuera del alcance. */
    public Map<String, Object> detalle(long id, Alcance a) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        List<Map<String, Object>> l = jdbc.sql("SELECT " + COLUMNAS + ", a.causa, a.valoracion, a.recomendacion, a.fuente_referencia, a.seleccion::text AS seleccion,"
                + " a.ultima_evaluacion_cargue_id, a.actualizada, a.evento_alerta_id, c.nombre AS cohorte FROM ana_alertas a LEFT JOIN ana_cohortes c ON c.id = a.cohorte_id"
                + " WHERE a.id = :id AND " + alcance(a, p)).params(p).query((rs, n) -> {
                    Map<String, Object> m = item(rs);
                    // Los conteos de pacientes de los textos se escriben aquí, con la supresión vigente (nunca el número de una celda oculta)
                    m.put("causa", AnaliticaAlertas.mostrar(rs.getString("causa"), tableros.supresion()));
                    m.put("valoracion", AnaliticaAlertas.mostrar(rs.getString("valoracion"), tableros.supresion()));
                    m.put("recomendacion", rs.getString("recomendacion"));
                    m.put("fuenteReferencia", rs.getString("fuente_referencia"));
                    m.put("seleccion", riesgo.deJson(rs.getString("seleccion")));
                    m.put("ultimaEvaluacion", rs.getLong("ultima_evaluacion_cargue_id"));
                    m.put("actualizada", AnaliticaConsulta.fechaHora(rs.getTimestamp("actualizada").toInstant().atOffset(ZoneOffset.UTC)));
                    m.put("avisoCorreo", rs.getObject("evento_alerta_id") != null);
                    m.put("cohorte", rs.getString("cohorte"));
                    return m;
                }).list();
        if (l.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.NOT_FOUND, "La alerta no existe o está fuera de su alcance.");
        }
        Map<String, Object> r = l.getFirst();
        r.put("historial", jdbc.sql("""
                SELECT de, a, nota, responsable_texto, fecha FROM ana_alertas_historial WHERE alerta_id = :id ORDER BY id""").param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("de", rs.getString(1));
                    h.put("a", rs.getString(2));
                    h.put("nota", rs.getString(3));
                    h.put("responsable", rs.getString(4));
                    h.put("fecha", AnaliticaConsulta.fechaHora(rs.getTimestamp(5).toInstant().atOffset(ZoneOffset.UTC)));
                    return h;
                }).list());
        return r;
    }

    // ---------- Validación humana ----------

    /** nueva → en_revision → validada | descartada (y descartada → en_revision). Validar o descartar exige nota de 10 caracteres o más. */
    public Map<String, Object> cambiarEstado(long id, String estado, String nota, UsuarioSesion u, Alcance a) {
        Estado nuevo = Estado.deCodigo(estado == null ? "" : estado.strip()).orElseThrow(() -> ErrorNegocio.validacion("Estado no válido."));
        String n = nota == null ? "" : nota.strip();
        if (nuevo.exigeNota() && n.length() < AnaliticaAlertas.NOTA_MINIMA) {
            throw ErrorNegocio.validacion(AnaliticaAlertas.NOTA_CORTA);
        }
        if (n.length() > 1000) {
            throw ErrorNegocio.validacion("La nota lleva máximo 1000 caracteres.");
        }
        return escritura.execute(s -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", id);
            String actual = jdbc.sql("SELECT a.estado FROM ana_alertas a WHERE a.id = :id AND " + alcance(a, p) + " FOR UPDATE").params(p).query(String.class)
                    .optional().orElseThrow(() -> new ErrorNegocio(HttpStatus.NOT_FOUND, "La alerta no existe o está fuera de su alcance."));
            Estado de = Estado.deCodigo(actual).orElseThrow();
            if (!de.puedePasarA(nuevo)) {
                throw ErrorNegocio.conflicto("La alerta está «" + de.codigo() + "» y no puede pasar a «" + nuevo.codigo() + "».");
            }
            jdbc.sql("UPDATE ana_alertas SET estado = :e WHERE id = :id").param("e", nuevo.codigo()).param("id", id).update();
            jdbc.sql("""
                    INSERT INTO ana_alertas_historial (alerta_id, de, a, nota, usuario_id, responsable_texto, fecha) VALUES (:id, :de, :a, :n, :u, :r, :f)""")
                    .param("id", id).param("de", de.codigo()).param("a", nuevo.codigo()).param("n", n.isEmpty() ? null : n).param("u", u.id())
                    .param("r", Bitacora.cortar(u.nombre() + " (" + perfiles.nombres() + ")", 200)).param("f", OffsetDateTime.now(reloj)).update();
            bitacora.registrar("analitica_alerta_estado", "Alerta analítica " + id + ": " + de.codigo() + " → " + nuevo.codigo(), u.id(), u.usuario());
            return Map.<String, Object>of("id", id, "estado", nuevo.codigo());
        });
    }

    /** Cohortes activas (id y nombre) para el filtro «cohorte» de la consola. */
    public List<Map<String, Object>> cohortesConAlertas() {
        return jdbc.sql("SELECT id, codigo, nombre FROM ana_cohortes WHERE activa ORDER BY orden, id").query((rs, n) -> Map.<String, Object>of("id",
                rs.getLong(1), "codigo", rs.getString(2), "nombre", rs.getString(3))).list();
    }

    // ---------- Parámetros ----------

    public List<Map<String, Object>> parametros() {
        return jdbc.sql("""
                SELECT p.tipo, p.version, p.parametros::text, p.actualizado, coalesce(u.usuario, 'Sistema') FROM ana_alertas_parametros p
                LEFT JOIN usuarios u ON u.id = p.actualizado_por""").query((rs, n) -> {
                    Tipo t = Tipo.deCodigo(rs.getString(1)).orElseThrow();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tipo", t.codigo());
                    m.put("nombre", t.nombre());
                    m.put("unidad", t.unidad());
                    m.put("nivelesAdmitidos", t.nivelesAdmitidos());
                    m.put("version", rs.getInt(2));
                    m.put("parametros", riesgo.deJson(rs.getString(3)));
                    m.put("actualizado", AnaliticaConsulta.fechaHora(rs.getTimestamp(4).toInstant().atOffset(ZoneOffset.UTC)));
                    m.put("actualizadoPor", rs.getString(5));
                    m.put("orden", t.ordinal());
                    return m;
                }).list().stream().sorted(java.util.Comparator.comparing(m -> (Integer) m.get("orden"))).toList();
    }

    /** Guarda una versión nueva de los parámetros del tipo con su traza (antes y después). Lo generado conserva su versión. */
    public Map<String, Object> guardarParametros(String tipo, Map<String, Object> cuerpo, UsuarioSesion u) {
        Tipo t = Tipo.deCodigo(tipo).orElseThrow(() -> ErrorNegocio.noEncontrado("Tipo de alerta no válido."));
        Parametros nuevo;
        try {
            nuevo = AnaliticaAlertas.leer(t, cuerpo);
        } catch (IllegalArgumentException e) {
            throw ErrorNegocio.validacion(e.getMessage());
        }
        Map<String, Object> despues = AnaliticaAlertas.aMapa(nuevo);
        return escritura.execute(s -> {
            Map<String, Object> fila = jdbc.sql("SELECT version, parametros::text AS p FROM ana_alertas_parametros WHERE tipo = :t FOR UPDATE").param("t", t.codigo())
                    .query().listOfRows().getFirst();
            Map<String, Object> antes = riesgo.deJson((String) fila.get("p"));
            if (antes.equals(riesgo.deJson(riesgo.aJson(despues)))) {
                return Map.<String, Object>of("tipo", t.codigo(), "version", fila.get("version"), "cambios", false);
            }
            int version = ((Number) fila.get("version")).intValue() + 1;
            OffsetDateTime ahora = OffsetDateTime.now(reloj);
            jdbc.sql("UPDATE ana_alertas_parametros SET version = :v, parametros = CAST(:p AS jsonb), actualizado = :a, actualizado_por = :u WHERE tipo = :t")
                    .param("v", version).param("p", riesgo.aJson(despues)).param("a", ahora).param("u", u.id()).param("t", t.codigo()).update();
            jdbc.sql("""
                    INSERT INTO ana_alertas_parametros_historial (tipo, version, antes, despues, usuario_id, responsable_texto, fecha)
                    VALUES (:t, :v, CAST(:antes AS jsonb), CAST(:despues AS jsonb), :u, :r, :a)""").param("t", t.codigo()).param("v", version)
                    .param("antes", fila.get("p")).param("despues", riesgo.aJson(despues)).param("u", u.id())
                    .param("r", Bitacora.cortar(u.nombre() + " (" + perfiles.nombres() + ")", 200)).param("a", ahora).update();
            bitacora.registrarCambios("analitica_alerta_parametro", "Parámetros de «" + t.nombre() + "» v" + version, plano(antes), plano(despues), Set.of(),
                    u.id(), u.usuario());
            return Map.<String, Object>of("tipo", t.codigo(), "version", version, "cambios", true);
        });
    }

    /** Parámetros en texto plano por clave (para el resumen antes → después de la bitácora). */
    private static Map<String, Object> plano(Map<String, Object> p) {
        Map<String, Object> r = new LinkedHashMap<>();
        p.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) {
                m.forEach((k2, v2) -> r.put(k + "." + k2, String.valueOf(v2)));
            } else {
                r.put(k, v == null ? null : String.valueOf(v));
            }
        });
        return r;
    }

    public List<Map<String, Object>> historialParametros(String tipo) {
        Tipo t = Tipo.deCodigo(tipo).orElseThrow(() -> ErrorNegocio.noEncontrado("Tipo de alerta no válido."));
        return jdbc.sql("""
                SELECT version, antes::text, despues::text, responsable_texto, fecha FROM ana_alertas_parametros_historial WHERE tipo = :t ORDER BY version DESC""")
                .param("t", t.codigo()).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version", rs.getInt(1));
                    m.put("antes", rs.getString(2) == null ? null : riesgo.deJson(rs.getString(2)));
                    m.put("despues", riesgo.deJson(rs.getString(3)));
                    m.put("responsable", rs.getString(4));
                    m.put("fecha", AnaliticaConsulta.fechaHora(rs.getTimestamp(5).toInstant().atOffset(ZoneOffset.UTC)));
                    return m;
                }).list();
    }

    // ---------- Ejecuciones ----------

    /** Ejecuta el motor bajo demanda sobre el corte vigente (cohortes y alertas, en segundo plano). */
    public Map<String, Object> ejecutar(UsuarioSesion u) {
        long corte = riesgo.corteVigente().orElseThrow(() -> ErrorNegocio.validacion("Aún no hay cargues aplicados."));
        RecalculoRiesgo.Programada p = riesgo.programar(corte, "usuario", u.id(), null, true);
        return Map.of("ejecucion", p.ejecucionAlertas(), "ejecucionCohortes", p.ejecucionCohortes(), "corte", corte);
    }

    public List<Map<String, Object>> ejecuciones() {
        return jdbc.sql("""
                SELECT e.id, e.origen, e.corte_cargue_id, e.inicio, e.fin, e.estado, e.nuevas, e.actualizadas, e.avisos, e.por_tipo::text, e.duracion_ms, e.error,
                       coalesce(u.usuario, 'Sistema') FROM ana_alertas_ejecuciones e LEFT JOIN usuarios u ON u.id = e.usuario_id ORDER BY e.id DESC LIMIT 50""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("origen", rs.getString(2));
                    m.put("corte", rs.getLong(3));
                    m.put("inicio", AnaliticaConsulta.fechaHora(rs.getTimestamp(4).toInstant().atOffset(ZoneOffset.UTC)));
                    m.put("fin", rs.getTimestamp(5) == null ? null : AnaliticaConsulta.fechaHora(rs.getTimestamp(5).toInstant().atOffset(ZoneOffset.UTC)));
                    m.put("estado", rs.getString(6));
                    m.put("nuevas", rs.getInt(7));
                    m.put("actualizadas", rs.getInt(8));
                    m.put("avisos", rs.getInt(9));
                    m.put("porTipo", riesgo.deJson(rs.getString(10)));
                    m.put("duracionMs", rs.getObject(11));
                    m.put("error", rs.getString(12));
                    m.put("usuario", rs.getString(13));
                    return m;
                }).list();
    }

    // ---------- Referencias cargables ----------

    public List<Map<String, Object>> referencias() {
        return jdbc.sql("""
                SELECT v.id, v.lista, v.version, v.fuente, v.fecha_fuente, v.filas, v.ficticio, v.vigente, v.cargada, coalesce(u.usuario, 'Sistema'), v.sha256
                FROM ana_ref_versiones v LEFT JOIN usuarios u ON u.id = v.cargada_por ORDER BY v.lista, v.version DESC""").query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt(1));
                    m.put("lista", rs.getString(2));
                    m.put("version", rs.getInt(3));
                    m.put("fuente", rs.getString(4));
                    m.put("fechaFuente", rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate());
                    m.put("filas", rs.getInt(6));
                    m.put("ficticio", rs.getBoolean(7));
                    m.put("vigente", rs.getBoolean(8));
                    m.put("cargada", AnaliticaConsulta.fechaHora(rs.getTimestamp(9).toInstant().atOffset(ZoneOffset.UTC)));
                    m.put("cargadaPor", rs.getString(10));
                    m.put("sha256", rs.getString(11));
                    return m;
                }).list();
    }

    /**
     * Carga una versión de una referencia (CSV UTF-8 con «;» o «,»: interacciones = principio1;principio2;nota, indicaciones = atc;cie10;nota,
     * huerfanas = cie10;nota). La primera fila puede ser encabezado. Una fila inválida rechaza el archivo con su línea (nada a medias).
     */
    public Map<String, Object> cargarReferencia(String lista, byte[] archivo, String fuente, LocalDate fechaFuente, boolean ficticio, UsuarioSesion u) {
        if (!Set.of("interacciones", "indicaciones", "huerfanas").contains(lista)) {
            throw ErrorNegocio.noEncontrado("Lista no válida (interacciones, indicaciones o huerfanas).");
        }
        String f = fuente == null ? "" : fuente.strip();
        if (f.length() < 3 || f.length() > 200) {
            throw ErrorNegocio.validacion("Escriba la fuente con su edición (3 a 200 caracteres).");
        }
        if (archivo == null || archivo.length == 0) {
            throw ErrorNegocio.validacion("El archivo está vacío.");
        }
        if (ficticio && configuracion.esProduccion()) {
            throw ErrorNegocio.validacion("Las referencias de ejemplo ficticias no se cargan en producción.");
        }
        List<String[]> filas = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(archivo), StandardCharsets.UTF_8))) {
            String linea;
            int n = 0;
            while ((linea = r.readLine()) != null) {
                n++;
                String l = n == 1 && linea.startsWith("﻿") ? linea.substring(1) : linea;
                if (l.isBlank()) {
                    continue;
                }
                String[] c = l.split(l.contains(";") ? ";" : ",", -1);
                String[] fila = validarFila(lista, c, n);
                if (fila == null) {
                    if (n == 1) {
                        continue; // encabezado
                    }
                    throw ErrorNegocio.validacion("Línea " + n + ": " + (lista.equals("interacciones") ? "dos principios activos" : lista.equals("indicaciones")
                            ? "un ATC y un CIE-10 válidos" : "un CIE-10 válido") + ".");
                }
                filas.add(fila);
                if (filas.size() > MAX_REFERENCIA) {
                    throw ErrorNegocio.validacion("Máximo " + MAX_REFERENCIA + " filas.");
                }
            }
        } catch (IOException e) {
            throw ErrorNegocio.validacion("No se pudo leer el archivo como texto UTF-8.");
        }
        if (filas.isEmpty()) {
            throw ErrorNegocio.validacion("El archivo no trae filas válidas.");
        }
        String sha = Huellas.sha256(archivo);
        Map<String, Object> r = escritura.execute(s -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('ana_ref_' || :l))").param("l", lista).query().listOfRows();
            int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM ana_ref_versiones WHERE lista = :l").param("l", lista).query(Integer.class).single();
            jdbc.sql("UPDATE ana_ref_versiones SET vigente = false WHERE lista = :l AND vigente").param("l", lista).update();
            int id = jdbc.sql("""
                    INSERT INTO ana_ref_versiones (lista, version, fuente, fecha_fuente, sha256, filas, ficticio, vigente, cargada, cargada_por)
                    VALUES (:l, :v, :f, :ff, :sha, :n, :fic, true, :a, :u) RETURNING id""").param("l", lista).param("v", version).param("f", f)
                    .param("ff", fechaFuente).param("sha", sha).param("n", filas.size()).param("fic", ficticio).param("a", OffsetDateTime.now(reloj))
                    .param("u", u.id()).query(Integer.class).single();
            List<Object[]> lote = new ArrayList<>();
            for (int i = 0; i < filas.size(); i++) {
                lote.add(new Object[] {id, i + 1, filas.get(i)[0], filas.get(i)[1], filas.get(i)[2]});
            }
            for (Object[] x : lote) {
                jdbc.sql("INSERT INTO ana_ref_filas (version_id, linea, codigo1, codigo2, nota) VALUES (:v, :l, :c1, :c2, :n)").param("v", x[0]).param("l", x[1])
                        .param("c1", x[2]).param("c2", x[3]).param("n", x[4]).update();
            }
            bitacora.registrar("analitica_alerta_referencia", "Referencia «" + lista + "» v" + version + " cargada: " + filas.size() + " filas · " + Bitacora.cortar(f, 120)
                    + (ficticio ? " · datos de ejemplo ficticios" : "") + " · SHA-256 " + sha.substring(0, 12), u.id(), u.usuario());
            return Map.<String, Object>of("lista", lista, "version", version, "filas", filas.size());
        });
        return r;
    }

    /** Fila normalizada {codigo1, codigo2, nota} o nula si no es válida. */
    static String[] validarFila(String lista, String[] c, int linea) {
        String nota = null;
        switch (lista) {
            case "interacciones" -> {
                if (c.length < 2) {
                    return null;
                }
                String a = AnaliticaHomologacion.normalizarTexto(c[0]);
                String b = AnaliticaHomologacion.normalizarTexto(c[1]);
                if (a.isEmpty() || b.isEmpty() || a.equals(b) || a.length() > 200 || b.length() > 200 || !a.matches("^[A-Z0-9 ,./()+-]+$")) {
                    return null;
                }
                nota = c.length > 2 ? Bitacora.cortar(c[2].strip(), 300) : null;
                return new String[] {a, b, nota == null || nota.isEmpty() ? null : nota};
            }
            case "indicaciones" -> {
                if (c.length < 2) {
                    return null;
                }
                String atc = AnaliticaCohortes.normalizarValor(AnaliticaCohortes.TipoCodigo.ATC, c[0]);
                String cie = AnaliticaCohortes.normalizarValor(AnaliticaCohortes.TipoCodigo.CIE10, c[1]);
                if (atc == null || cie == null) {
                    return null;
                }
                nota = c.length > 2 ? Bitacora.cortar(c[2].strip(), 300) : null;
                return new String[] {atc, cie, nota == null || nota.isEmpty() ? null : nota};
            }
            default -> {
                String cie = AnaliticaCohortes.normalizarValor(AnaliticaCohortes.TipoCodigo.CIE10, c[0]);
                if (cie == null) {
                    return null;
                }
                nota = c.length > 1 ? Bitacora.cortar(c[1].strip(), 300) : null;
                return new String[] {cie, null, nota == null || nota.isEmpty() ? null : nota};
            }
        }
    }

    // ---------- Atípicos (tablero 11) ----------

    /**
     * «Comportamientos atípicos» del tablero 11: conteos por tipo y severidad en el alcance y el periodo (y, si se pide, el ATC), más las
     * diez alertas de mayor prioridad (no para el ESM: él ve solo los conteos de su establecimiento). Siempre agregado.
     */
    public Map<String, Object> atipicos(LocalDate desde, LocalDate hasta, String fuerza, String esm, String atc, Long corte, Alcance a) {
        Map<String, Object> p = new LinkedHashMap<>();
        List<String> w = new ArrayList<>();
        // Corte elegido (reproducibilidad 14.8): las alertas nacidas hasta ese corte; sin corte, el vigente
        Long c = corte != null ? corte : riesgo.corteVigente().orElse(null);
        if (c != null) {
            w.add("a.corte_cargue_id <= :corteAt");
            p.put("corteAt", c);
        }
        boolean esEsm = a instanceof Alcance.Esm;
        switch (a) {
            case Alcance.Todo t -> w.add("TRUE");
            case Alcance.Fuerza f -> {
                p.put("alcFuerza", f.codigo());
                w.add("a.nivel IN ('fuerza', 'esm') AND a.fuerza = :alcFuerza");
            }
            case Alcance.Esm e -> {
                p.put("alcPunto", e.puntoId());
                w.add("a.nivel = 'esm' AND a.punto_id = :alcPunto");
            }
            default -> throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Su perfil no tiene un alcance de datos para la analítica farmacéutica.");
        }
        Map<String, String> f = new LinkedHashMap<>();
        if (desde != null) {
            f.put("desde", desde.toString());
        }
        if (hasta != null) {
            f.put("hasta", hasta.toString());
        }
        if (fuerza != null && !fuerza.isBlank()) {
            f.put("fuerza", fuerza);
        }
        if (esm != null && !esm.isBlank()) {
            f.put("esm", esm);
        }
        if (atc != null && !atc.isBlank()) {
            f.put("atc", atc);
        }
        w.addAll(clausulas(f, p));
        String where = String.join(" AND ", w);
        Map<String, Object> r = new LinkedHashMap<>(resumen(where, p));
        long total = jdbc.sql("SELECT count(*) FROM ana_alertas a WHERE " + where).params(p).query(Long.class).single();
        r.put("total", total);
        if (!esEsm) {
            r.put("alertas", jdbc.sql("SELECT " + COLUMNAS + " FROM ana_alertas a WHERE " + where
                    + " ORDER BY CASE a.severidad WHEN 'alta' THEN 0 WHEN 'media' THEN 1 ELSE 2 END, a.gasto DESC, a.id DESC LIMIT 10").params(p)
                    .query((rs, n) -> item(rs)).list());
        }
        r.put("soloConteos", esEsm);
        r.put("corte", c);
        r.put("corteTexto", c == null ? null : "Alertas nacidas hasta el corte del cargue " + c + " (cifras de su última evaluación)");
        return r;
    }

    // ---------- Descarga de la consola ----------

    public ResultadoTablero paraDescarga(Map<String, String> filtros, Alcance a) {
        Map<String, Object> p = new LinkedHashMap<>();
        String where = where(filtros, a, p);
        List<Map<String, Object>> alertas = jdbc.sql("SELECT " + COLUMNAS + " FROM ana_alertas a WHERE " + where
                + " ORDER BY CASE a.severidad WHEN 'alta' THEN 0 WHEN 'media' THEN 1 ELSE 2 END, a.gasto DESC, a.id DESC LIMIT 5000").params(p)
                .query((rs, n) -> item(rs)).list();
        return consultas.leer(j -> {
            Corte c = consultas.corte(j, null).orElseThrow(() -> ErrorNegocio.validacion("Aún no hay cargues aplicados: no hay nada que descargar."));
            List<Columna> col = List.of(new Columna("id", "N.º", AnaliticaConsulta.Tipo.ENTERO), new Columna("severidad", "Severidad", AnaliticaConsulta.Tipo.TEXTO),
                    new Columna("tipo", "Tipo", AnaliticaConsulta.Tipo.TEXTO), new Columna("nivel", "Nivel", AnaliticaConsulta.Tipo.TEXTO),
                    new Columna("dimension", "Dimensión", AnaliticaConsulta.Tipo.TEXTO), new Columna("valor", "Valor", AnaliticaConsulta.Tipo.TEXTO),
                    new Columna("periodo", "Periodo", AnaliticaConsulta.Tipo.TEXTO), new Columna("umbral", "Umbral", AnaliticaConsulta.Tipo.TEXTO),
                    new Columna("observado", "Valor observado", AnaliticaConsulta.Tipo.TEXTO), new Columna("registros", "Registros", AnaliticaConsulta.Tipo.ENTERO),
                    new Columna("pacientes", "Pacientes", AnaliticaConsulta.Tipo.ENTERO), new Columna("gasto", "Gasto", AnaliticaConsulta.Tipo.MONEDA),
                    new Columna("estado", "Estado", AnaliticaConsulta.Tipo.TEXTO));
            List<Fila> fs = new ArrayList<>();
            long registros = 0;
            for (Map<String, Object> x : alertas) {
                registros += x.get("registros") instanceof Long r ? r : 0;
                fs.add(ResultadoTablero.fila(String.valueOf(x.get("id")), null, col, java.util.Arrays.asList(x.get("id"), x.get("severidad"), x.get("tipoNombre"),
                        x.get("nivelTexto"), x.get("dimensionTexto"), x.get("valorTexto"), x.get("periodo"), x.get("umbralTexto"), x.get("observadoTexto"),
                        x.get("registros"), x.get("pacientes"), x.get("gasto"), x.get("estado")), "dato"));
            }
            Tabla t = new Tabla("alertas", "Alertas tempranas", "ninguno", null, null, col, fs, "Ordenadas por prioridad (severidad y gasto). Sin datos personales.");
            List<FiltroAplicado> fa = new ArrayList<>();
            filtros.forEach((k, v) -> {
                if (FILTROS.contains(k) && v != null && !v.isBlank()) {
                    fa.add(new FiltroAplicado(k, k, List.of(v), List.of(v)));
                }
            });
            Metadatos m = new Metadatos(c.cargueId(), c.aplicadoEn(), "Corte " + AnaliticaConsulta.fechaHora(c.aplicadoEn()) + " (cargue " + c.cargueId() + ")",
                    c.homNumero(), c.cargues(), "Motor de alertas tempranas (reglas/AnaliticaAlertas) · insumo de dispensación del OPL", null, null,
                    "Periodos de cada alerta", "Mes", "Sin comparación", null, consultas.alcanceTexto(j, a), consultas.alcanceTexto(j, a), fa, registros,
                    c.datosHasta(), consultas.textoSupresion());
            List<String> lineas = new ArrayList<>();
            fs.forEach(f -> lineas.add(f.clave() + "|" + String.join("|", f.textos())));
            return new ResultadoTablero("alertas", "Alertas tempranas", "nacional", List.of("nacional"), true, null, List.of(), List.of(t), List.of(), List.of(),
                    List.of(), m, AnaliticaConsulta.huella(lineas));
        });
    }
}
