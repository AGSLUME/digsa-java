package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Parametrización en vivo (analitica-homologacion, «Parametrización en vivo con traza y re-ejecución»; TAS P5): activar o desactivar
 * reglas, cambiar su orden y parámetros, y editar los catálogos (sinónimos DCI, sales, abreviaturas, unidades y formas equivalentes) sin
 * tocar código. Cada cambio sube la versión de la regla o crea una versión del catálogo y deja una fila de historial con usuario, fecha,
 * valor anterior y nuevo. Lo ya homologado no cambia hasta «Re-ejecutar homologación» (o el siguiente cargue con datos nuevos).
 */
@Service
class ServicioReglas {

    /** Parámetros admitidos por regla, con su rango (texto). */
    static final Map<String, Map<String, int[]>> PARAMETROS = Map.of(
            AnaliticaHomologacion.R015, Map.of("tolerancia", new int[] {0, 2}),
            AnaliticaHomologacion.R012, Map.of("toleranciaPorcentaje", new int[] {0, 50}));

    /** Nombre legible de cada parámetro (para el historial y la bitácora). */
    static final Map<String, String> NOMBRES = Map.of("tolerancia", "tolerancia de forma farmacéutica", "toleranciaPorcentaje",
            "tolerancia de concentración (%)");

    static final Map<String, String> NOMBRES_CATALOGO = Map.of("sinonimo_dci", "sinónimos DCI", "sal", "sales toleradas", "abreviatura",
            "abreviaturas", "unidad", "unidades", "forma", "formas farmacéuticas equivalentes");

    private static final Set<String> UNIDADES = Set.of("MG", "G", "MCG", "UI", "ML", "%", "MEQ");

    /** Cambio pedido a una regla (lo que no viene no cambia) y la versión de la regla que se editó (409 si otra persona la cambió). */
    record Cambio(Boolean activa, Integer orden, Map<String, String> parametros, Integer version) {
    }

    /** Entrada de un catálogo. */
    record Entrada(String clave, String valor, String extra) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate plantilla;
    private final ObjectMapper json;
    private final RegistroBitacora bitacora;
    private final Clock reloj;
    private final TransactionTemplate tx;

    ServicioReglas(JdbcClient jdbc, JdbcTemplate plantilla, ObjectMapper json, RegistroBitacora bitacora, Clock reloj,
                   PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.plantilla = plantilla;
        this.json = json;
        this.bitacora = bitacora;
        this.reloj = reloj;
        this.tx = new TransactionTemplate(transacciones);
    }

    /** Reglas en su orden, catálogos con su versión y el historial reciente. */
    Map<String, Object> reglas() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("reglas", jdbc.sql("""
                SELECT r.id, r.nombre, r.orden, r.activa, r.obligatoria, r.parametros::text, r.version, r.actualizado, u.usuario
                FROM hom_regla r LEFT JOIN usuarios u ON u.id = r.actualizado_por ORDER BY r.orden, r.id""").query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("nombre", rs.getString(2));
                    m.put("orden", rs.getInt(3));
                    m.put("activa", rs.getBoolean(4));
                    m.put("obligatoria", rs.getBoolean(5));
                    m.put("parametros", Instantanea.leer(json, rs.getString(6)));
                    m.put("version", rs.getInt(7));
                    m.put("actualizado", rs.getObject(8, OffsetDateTime.class));
                    m.put("actualizadoPor", rs.getString(9));
                    m.put("admite", PARAMETROS.getOrDefault(rs.getString(1), Map.of()).keySet());
                    return m;
                }).list());
        r.put("catalogos", jdbc.sql("""
                SELECT tipo, max(version), count(*) FILTER (WHERE version = (SELECT max(version) FROM hom_catalogo x WHERE x.tipo = c.tipo))
                FROM hom_catalogo c GROUP BY tipo ORDER BY tipo""").query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tipo", rs.getString(1));
                    m.put("nombre", NOMBRES_CATALOGO.getOrDefault(rs.getString(1), rs.getString(1)));
                    m.put("version", rs.getInt(2));
                    m.put("entradas", rs.getInt(3));
                    return m;
                }).list());
        r.put("historial", historial(50));
        return r;
    }

    List<Map<String, Object>> historial(int limite) {
        return jdbc.sql("""
                SELECT id, objeto, version, antes::text, despues::text, descripcion, responsable_texto, fecha FROM hom_regla_historial
                ORDER BY id DESC LIMIT :l""").param("l", limite).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("objeto", rs.getString(2));
                    m.put("version", rs.getInt(3));
                    m.put("antes", rs.getString(4));
                    m.put("despues", rs.getString(5));
                    m.put("descripcion", rs.getString(6));
                    m.put("responsable", rs.getString(7));
                    m.put("fecha", rs.getObject(8, OffsetDateTime.class));
                    return m;
                }).list();
    }

    /** Cambia una regla: versión + 1 e historial con el antes y el después. */
    Map<String, Object> actualizar(String id, Cambio c, UsuarioSesion quien, String responsable) {
        if (c == null) {
            throw ErrorNegocio.validacion("Indique el cambio de la regla.");
        }
        Object[] r = tx.execute(t -> {
            Map<String, Object> actual = jdbc.sql("SELECT id, orden, activa, obligatoria, parametros::text, version FROM hom_regla WHERE id = :id FOR UPDATE")
                    .param("id", id).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> ErrorNegocio.noEncontrado("La regla " + id + " no existe."));
            if (c.version() != null && c.version() != ((Number) actual.get("version")).intValue()) {
                throw ErrorNegocio.conflicto("Otra persona cambió la regla " + id + " mientras la editaba (ahora está en la versión " + actual.get("version")
                        + "); recargue y vuelva a hacer el cambio.");
            }
            boolean obligatoria = (Boolean) actual.get("obligatoria");
            Map<String, Object> antes = estado(actual);
            boolean activa = c.activa() == null ? (Boolean) actual.get("activa") : c.activa();
            if (obligatoria && !activa) {
                throw ErrorNegocio.validacion("La regla " + id + " es obligatoria y no se puede desactivar.");
            }
            int orden = c.orden() == null ? ((Number) actual.get("orden")).intValue() : c.orden();
            if (orden < 1 || orden > 999) {
                throw ErrorNegocio.validacion("El orden debe estar entre 1 y 999.", "orden");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> previos = (Map<String, Object>) antes.get("parametros");
            Map<String, String> parametros = new TreeMap<>();
            previos.forEach((k, v) -> parametros.put(k, String.valueOf(v)));
            if (c.parametros() != null) {
                Map<String, int[]> admite = PARAMETROS.getOrDefault(id, Map.of());
                for (var e : c.parametros().entrySet()) {
                    int[] rango = admite.get(e.getKey());
                    if (rango == null) {
                        throw ErrorNegocio.validacion("La regla " + id + " no tiene el parámetro «" + e.getKey() + "».", e.getKey());
                    }
                    String v = e.getValue() == null ? "" : e.getValue().strip();
                    int n;
                    try {
                        n = Integer.parseInt(v);
                    } catch (NumberFormatException x) {
                        throw ErrorNegocio.validacion("El parámetro «" + e.getKey() + "» debe ser un número entero entre " + rango[0] + " y " + rango[1] + ".",
                                e.getKey());
                    }
                    if (n < rango[0] || n > rango[1]) {
                        throw ErrorNegocio.validacion("El parámetro «" + e.getKey() + "» debe estar entre " + rango[0] + " y " + rango[1] + ".", e.getKey());
                    }
                    parametros.put(e.getKey(), Integer.toString(n));
                }
            }
            Map<String, Object> despues = new LinkedHashMap<>();
            despues.put("activa", activa);
            despues.put("orden", orden);
            despues.put("parametros", parametros);
            if (antes.equals(despues)) {
                throw ErrorNegocio.validacion("No hay cambios en la regla.");
            }
            int version = ((Number) actual.get("version")).intValue() + 1;
            OffsetDateTime ahora = OffsetDateTime.now(reloj);
            jdbc.sql("""
                    UPDATE hom_regla SET activa = :a, orden = :o, parametros = CAST(:p AS jsonb), version = :v, actualizado = :ahora, actualizado_por = :u
                    WHERE id = :id""")
                    .param("a", activa).param("o", orden).param("p", aJson(parametros)).param("v", version).param("ahora", ahora).param("u", quien.id())
                    .param("id", id).update();
            String descripcion = "Regla " + id + " modificada (" + queCambio(antes, despues) + ")";
            jdbc.sql("""
                    INSERT INTO hom_regla_historial (objeto, version, antes, despues, descripcion, usuario_id, responsable_texto, fecha)
                    VALUES (:o, :v, CAST(:antes AS jsonb), CAST(:despues AS jsonb), :d, :u, :resp, :ahora)""")
                    .param("o", id).param("v", version).param("antes", aJson(antes)).param("despues", aJson(despues))
                    .param("d", ServicioHomologacion.cortar(descripcion, 500)).param("u", quien.id()).param("resp", responsable).param("ahora", ahora)
                    .update();
            return new Object[] {descripcion, aJson(antes), aJson(despues), version};
        });
        registrar(ServicioHomologacion.cortar(r[0] + " · v" + r[3] + " · antes " + r[1] + " · después " + r[2], 500), quien);
        return Map.of("descripcion", r[0], "version", r[3]);
    }

    /** La bitácora va después del commit: si falla, el cambio ya quedó y se registra el error (no un 500 con el cambio aplicado). */
    private void registrar(String descripcion, UsuarioSesion quien) {
        try {
            bitacora.registrar("homologacion_regla_modificada", descripcion, quien.id(), quien.usuario());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ServicioReglas.class).error("[homologacion] El cambio quedó aplicado pero no se pudo registrar en la "
                    + "bitácora: {}", descripcion, e);
        }
    }

    private Map<String, Object> estado(Map<String, Object> fila) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activa", fila.get("activa"));
        m.put("orden", ((Number) fila.get("orden")).intValue());
        Map<String, Object> p = new TreeMap<>();
        Instantanea.leer(json, String.valueOf(fila.get("parametros"))).forEach((k, v) -> p.put(k, String.valueOf(v)));
        m.put("parametros", p);
        return m;
    }

    @SuppressWarnings("unchecked")
    static String queCambio(Map<String, Object> antes, Map<String, Object> despues) {
        List<String> l = new ArrayList<>();
        if (!antes.get("activa").equals(despues.get("activa"))) {
            l.add(Boolean.TRUE.equals(despues.get("activa")) ? "activada" : "desactivada");
        }
        if (!antes.get("orden").equals(despues.get("orden"))) {
            l.add("orden");
        }
        Map<String, Object> pa = (Map<String, Object>) antes.get("parametros");
        Map<String, Object> pd = (Map<String, Object>) despues.get("parametros");
        for (String k : pd.keySet()) {
            if (!String.valueOf(pd.get(k)).equals(String.valueOf(pa.get(k)))) {
                l.add(NOMBRES.getOrDefault(k, k));
            }
        }
        return String.join(", ", l);
    }

    // ---------- Catálogos ----------

    private static void validarTipo(String tipo) {
        if (!AnaliticaHomologacion.TIPOS_CATALOGO.contains(tipo)) {
            throw ErrorNegocio.noEncontrado("El catálogo no existe.");
        }
    }

    /** Entradas de la versión vigente del catálogo. */
    Map<String, Object> catalogo(String tipo) {
        validarTipo(tipo);
        Integer version = jdbc.sql("SELECT max(version) FROM hom_catalogo WHERE tipo = :t").param("t", tipo).query(Integer.class).single();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tipo", tipo);
        r.put("nombre", NOMBRES_CATALOGO.get(tipo));
        r.put("version", version);
        r.put("entradas", entradas(tipo, version));
        return r;
    }

    private List<Map<String, Object>> entradas(String tipo, Integer version) {
        return version == null ? List.of() : jdbc.sql("SELECT clave, valor, extra FROM hom_catalogo WHERE tipo = :t AND version = :v ORDER BY clave")
                .param("t", tipo).param("v", version).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("clave", rs.getString(1));
                    m.put("valor", rs.getString(2));
                    m.put("extra", rs.getString(3));
                    return m;
                }).list();
    }

    /** Reemplaza el catálogo con las entradas dadas (normalizadas): versión nueva completa e historial con el antes y el después. */
    Map<String, Object> editarCatalogo(String tipo, List<Entrada> entradas, Integer versionEditada, UsuarioSesion quien, String responsable) {
        validarTipo(tipo);
        if (entradas == null || entradas.isEmpty()) {
            throw ErrorNegocio.validacion("El catálogo debe tener al menos una entrada.", "entradas");
        }
        Map<String, Entrada> limpias = new TreeMap<>();
        for (Entrada e : entradas) {
            if (e == null || e.clave() == null || e.clave().isBlank()) {
                throw ErrorNegocio.validacion("Cada entrada necesita su texto de entrada.", "entradas");
            }
            String clave = "unidad".equals(tipo) ? e.clave().strip().toUpperCase(java.util.Locale.ROOT) : AnaliticaHomologacion.normalizarTexto(e.clave());
            String valor = "sal".equals(tipo) ? clave : e.valor() == null ? "" : "unidad".equals(tipo) ? e.valor().strip().toUpperCase(java.util.Locale.ROOT)
                    : AnaliticaHomologacion.normalizarTexto(e.valor());
            String extra = "forma".equals(tipo) && e.extra() != null && !e.extra().isBlank() ? AnaliticaHomologacion.normalizarTexto(e.extra()) : null;
            if (clave.isEmpty() || valor.isEmpty() || clave.length() > 120 || valor.length() > 200 || extra != null && extra.length() > 120) {
                throw ErrorNegocio.validacion("Entrada no válida: «" + e.clave().strip() + "» (texto y valor obligatorios; 120 y 200 caracteres).", "entradas");
            }
            if ("unidad".equals(tipo) && !UNIDADES.contains(valor)) {
                throw ErrorNegocio.validacion("La unidad canónica debe ser una de " + String.join(", ", new java.util.TreeSet<>(UNIDADES)) + ".", "entradas");
            }
            if (limpias.put(clave, new Entrada(clave, valor, extra)) != null) {
                throw ErrorNegocio.validacion("«" + clave + "» está repetido.", "entradas");
            }
        }
        Object[] r = tx.execute(t -> {
            ServicioHomologacion.candado(jdbc, ServicioHomologacion.ESPERA_PANTALLA);
            Integer anterior = jdbc.sql("SELECT max(version) FROM hom_catalogo WHERE tipo = :t").param("t", tipo).query(Integer.class).single();
            if (versionEditada != null && !versionEditada.equals(anterior)) {
                throw ErrorNegocio.conflicto("Otra persona cambió el catálogo mientras lo editaba (ahora está en la versión " + anterior
                        + "); recargue y vuelva a hacer el cambio.");
            }
            List<Map<String, Object>> antes = entradas(tipo, anterior);
            List<Map<String, Object>> despues = new ArrayList<>();
            limpias.values().forEach(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("clave", e.clave());
                m.put("valor", e.valor());
                m.put("extra", e.extra());
                despues.add(m);
            });
            if (antes.equals(despues)) {
                throw ErrorNegocio.validacion("El catálogo no cambió.");
            }
            int version = (anterior == null ? 0 : anterior) + 1;
            List<Object[]> lote = new ArrayList<>();
            limpias.values().forEach(e -> lote.add(new Object[] {tipo, version, e.clave(), e.valor(), e.extra()}));
            plantilla.batchUpdate("INSERT INTO hom_catalogo (tipo, version, clave, valor, extra) VALUES (?, ?, ?, ?, ?)", lote);
            String descripcion = ServicioHomologacion.cortar("Catálogo de " + NOMBRES_CATALOGO.get(tipo) + " v" + version + " modificado: "
                    + resumen(antes, despues), 500);
            jdbc.sql("""
                    INSERT INTO hom_regla_historial (objeto, version, antes, despues, descripcion, usuario_id, responsable_texto, fecha)
                    VALUES (:o, :v, CAST(:antes AS jsonb), CAST(:despues AS jsonb), :d, :u, :resp, :ahora)""")
                    .param("o", "catalogo:" + tipo).param("v", version).param("antes", aJson(antes)).param("despues", aJson(despues)).param("d", descripcion)
                    .param("u", quien.id()).param("resp", responsable).param("ahora", OffsetDateTime.now(reloj)).update();
            return new Object[] {descripcion, version};
        });
        registrar((String) r[0], quien);
        return Map.of("descripcion", r[0], "version", r[1]);
    }

    /** «+ACETAMINOFENO ⇒ PARACETAMOL; −DIPIRONA» con lo agregado, cambiado y quitado. */
    static String resumen(List<Map<String, Object>> antes, List<Map<String, Object>> despues) {
        Map<String, String> a = new TreeMap<>();
        antes.forEach(m -> a.put(String.valueOf(m.get("clave")), m.get("valor") + (m.get("extra") == null ? "" : " / " + m.get("extra"))));
        Map<String, String> d = new TreeMap<>();
        despues.forEach(m -> d.put(String.valueOf(m.get("clave")), m.get("valor") + (m.get("extra") == null ? "" : " / " + m.get("extra"))));
        List<String> l = new ArrayList<>();
        d.forEach((k, v) -> {
            if (!a.containsKey(k)) {
                l.add("+" + k + " ⇒ " + v);
            } else if (!a.get(k).equals(v)) {
                l.add(k + ": " + a.get(k) + " ⇒ " + v);
            }
        });
        a.keySet().stream().filter(k -> !d.containsKey(k)).forEach(k -> l.add("−" + k));
        return String.join("; ", l);
    }

    private String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
