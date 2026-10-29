package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.analitica.PropiedadesAnalitica;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Campo;
import co.com.agsamericas.digsa.reglas.Huellas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Adaptador de servicio web del puerto {@link FuenteDispensacion} (A5 14.3 «simulando el web service del OPL»; RESP L4513–4527): el OPL
 * nacional envía lotes JSON numerados de un envío ({@code envio}, {@code numero}, {@code final}, {@code registros[]} con las claves de los
 * campos del modelo). Cada lote se guarda durable (en el almacén, solo con las claves del modelo, y en ana_ws_lotes con el SHA-256 del
 * cuerpo recibido): NO pasa al staging UNLOGGED, que una caída de la base vaciaría. Cuando están todos los lotes 1..N (N = el lote
 * final), el cargue entra a la cola; el procesador los serializa en el envío (su original, con SHA-256) y los copia al staging.
 *
 * <p>Reglas del envío: un lote con número mayor que el final ya declarado, un segundo final con otro número o un final menor que un lote
 * ya recibido es 422; el mismo lote otra vez con el mismo SHA-256 responde lo mismo (idempotente), con otro contenido es 409. Las claves
 * desconocidas de los registros se ignoran y se avisan (solo sus nombres) en la respuesta y en la bitácora del cargue.
 */
@Service
class ServicioWebOpl {

    private static final Pattern ENVIO = Pattern.compile("^[A-Za-z0-9_.-]{1,80}$");
    static final String FUENTE_TEXTO = AnaliticaIngesta.fuenteTexto("json", ';');
    /** Encabezado del servicio web: las claves de los campos que se guardan, en el orden del modelo. */
    static final List<Campo> CAMPOS = Arrays.stream(Campo.values()).filter(Campo::seGuarda).toList();
    private static final Set<String> CLAVES = CAMPOS.stream().map(Campo::clave).collect(Collectors.toUnmodifiableSet());
    /** Máximo de lotes de un envío (CHECK de ana_ws_lotes y ana_cargues.partes). */
    static final int MAX_LOTES = 10_000;
    private static final int MAX_CLAVES_AVISO = 30;

    private final JdbcClient jdbc;
    private final Almacen almacen;
    private final Seudonimizador seudo;
    private final ProcesadorCargues procesador;
    private final RegistroBitacora bitacora;
    private final PropiedadesAnalitica propiedades;
    private final ObjectMapper json;
    private final ObjectMapper lector;
    private final Clock reloj;
    private final TransactionTemplate tx;

    ServicioWebOpl(JdbcClient jdbc, Almacen almacen, Seudonimizador seudo, ProcesadorCargues procesador, RegistroBitacora bitacora,
                   PropiedadesAnalitica propiedades, ObjectMapper json, Clock reloj, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.almacen = almacen;
        this.seudo = seudo;
        this.procesador = procesador;
        this.bitacora = bitacora;
        this.propiedades = propiedades;
        this.json = json;
        // Valores con decimales exactos (pesos): nunca pasan por double
        this.lector = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.reloj = reloj;
        this.tx = new TransactionTemplate(transacciones);
    }

    /** Lote ya leído. */
    record Lote(String envio, int numero, boolean fin, JsonNode registros) {
    }

    /** Lote ya guardado (para la respuesta idempotente). */
    private record Guardado(String sha256, int registros, boolean fin, List<String> desconocidas) {
    }

    /** Recibe un lote (cuerpo JSON tal como llegó, ya acotado en tamaño por la API). */
    Map<String, Object> recibir(byte[] cuerpo, UsuarioSesion quien) {
        seudo.exigir();
        Lote lote = leer(cuerpo);
        String sha = Huellas.sha256(cuerpo);
        List<String> desconocidas = clavesDesconocidas(lote.registros());
        byte[] minimo = minimizar(lote);
        boolean[] cerrado = {false};
        Map<String, Object> r = tx.execute(s -> {
            long cargueId = cargueDelEnvio(lote.envio(), quien);
            Guardado previo = jdbc.sql("""
                    SELECT sha256, registros, final, claves_desconocidas::text FROM ana_ws_lotes WHERE envio = :e AND numero = :n""")
                    .param("e", lote.envio()).param("n", lote.numero())
                    .query((rs, n) -> new Guardado(rs.getString(1).strip(), rs.getInt(2), rs.getBoolean(3), deJson(rs.getString(4))))
                    .optional().orElse(null);
            if (previo != null) {
                if (!previo.sha256().equals(sha)) {
                    throw ErrorNegocio.conflicto("El lote " + lote.numero() + " del envío " + lote.envio() + " ya se recibió con otro contenido.");
                }
                return respuesta(cargueId, lote.envio(), lote.numero(), previo); // el mismo lote otra vez: misma respuesta
            }
            Map<String, Object> c = jdbc.sql("SELECT estado, partes FROM ana_cargues WHERE id = :c").param("c", cargueId).query().singleRow();
            if (!"subiendo".equals(c.get("estado"))) {
                throw ErrorNegocio.conflicto("El envío " + lote.envio() + " ya se cerró; use otro identificador de envío.");
            }
            Integer fin = c.get("partes") == null ? null : ((Number) c.get("partes")).intValue();
            if (fin != null && lote.numero() > fin) {
                throw ErrorNegocio.validacion("El lote " + lote.numero() + " es posterior al lote final del envío (" + fin + ").");
            }
            if (fin != null && lote.fin() && lote.numero() != fin) {
                throw ErrorNegocio.validacion("El envío ya declaró como final el lote " + fin + ".");
            }
            if (lote.fin()) {
                int mayor = jdbc.sql("SELECT coalesce(max(numero), 0) FROM ana_ws_lotes WHERE cargue_id = :c").param("c", cargueId).query(Integer.class)
                        .single();
                if (mayor > lote.numero()) {
                    throw ErrorNegocio.validacion("Ya se recibió el lote " + mayor + ", posterior al final declarado (" + lote.numero() + ").");
                }
            }
            OffsetDateTime ahora = OffsetDateTime.now(reloj);
            String ruta = guardarLote(cargueId, lote.numero(), minimo);
            jdbc.sql("""
                    INSERT INTO ana_ws_lotes (cargue_id, envio, numero, registros, final, sha256, almacen_ruta, conservar_hasta, claves_desconocidas, recibido)
                    VALUES (:c, :e, :n, :r, :f, :sha, :ruta, :vence, CAST(:desc AS jsonb), :ahora)""")
                    .param("c", cargueId).param("e", lote.envio()).param("n", lote.numero()).param("r", lote.registros().size())
                    .param("f", lote.fin()).param("sha", sha).param("ruta", ruta).param("vence", ahora.plusDays(propiedades.diasConservar()))
                    .param("desc", aJson(desconocidas)).param("ahora", ahora).update();
            String aviso = desconocidas.isEmpty() ? null : aviso(lote.numero(), desconocidas);
            jdbc.sql("""
                    UPDATE ana_cargues SET actividad = :ahora, partes = CASE WHEN :fin THEN CAST(:n AS int) ELSE partes END,
                           avisos = CASE WHEN CAST(:aviso AS text) IS NULL OR jsonb_array_length(avisos) >= 50 THEN avisos
                                         ELSE avisos || jsonb_build_array(CAST(:aviso AS text)) END
                    WHERE id = :c""")
                    .param("ahora", ahora).param("fin", lote.fin()).param("n", lote.numero()).param("aviso", aviso).param("c", cargueId).update();
            if (lote.numero() == 1 || lote.fin() || aviso != null) {
                bitacora.registrar("analitica_ws_lote", ProcesadorCargues.cortar("Cargue " + cargueId + " · envío " + lote.envio() + " · lote "
                        + lote.numero() + (lote.fin() ? " (final)" : "") + " · " + lote.registros().size() + " registros"
                        + (aviso == null ? "" : " · " + aviso), 500), quien.id(), quien.usuario());
            }
            if (completo(cargueId)) {
                cerrar(cargueId, ahora);
                cerrado[0] = true;
            }
            return respuesta(cargueId, lote.envio(), lote.numero(), new Guardado(sha, lote.registros().size(), lote.fin(), desconocidas));
        });
        if (cerrado[0]) {
            procesador.despertar();
        }
        return r;
    }

    private Lote leer(byte[] cuerpo) {
        JsonNode raiz;
        try {
            raiz = lector.readTree(cuerpo);
        } catch (IOException e) {
            throw ErrorNegocio.validacion("El lote no es un JSON válido.");
        }
        if (raiz == null || !raiz.isObject()) {
            throw ErrorNegocio.validacion("El lote debe ser un objeto JSON con envio, numero, final y registros.");
        }
        String envio = raiz.path("envio").asText("");
        if (!ENVIO.matcher(envio).matches()) {
            throw ErrorNegocio.validacion("Identificador de envío no válido (letras, números, punto, guion y guion bajo; máximo 80).");
        }
        JsonNode numero = raiz.path("numero");
        if (!numero.canConvertToInt() || numero.asInt() < 1 || numero.asInt() > MAX_LOTES) {
            throw ErrorNegocio.validacion("Número de lote no válido (1 a " + MAX_LOTES + ").");
        }
        JsonNode registros = raiz.path("registros");
        if (!registros.isArray()) {
            throw ErrorNegocio.validacion("El lote no trae la lista de registros.");
        }
        if (registros.size() > propiedades.registrosPorLoteWs()) {
            throw ErrorNegocio.validacion("El lote supera " + propiedades.registrosPorLoteWs() + " registros.");
        }
        for (JsonNode r : registros) {
            if (!r.isObject()) {
                throw ErrorNegocio.validacion("Cada registro del lote debe ser un objeto JSON.");
            }
        }
        return new Lote(envio, numero.asInt(), raiz.path("final").asBoolean(false), registros);
    }

    /** Nombres de las claves de los registros que no son del modelo (ordenados; nunca sus valores). */
    static List<String> clavesDesconocidas(JsonNode registros) {
        Set<String> d = new TreeSet<>();
        for (JsonNode r : registros) {
            for (Iterator<String> it = r.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                if (!CLAVES.contains(k) && d.size() < MAX_CLAVES_AVISO) {
                    d.add(ProcesadorCargues.cortar(k, 60));
                }
            }
        }
        return List.copyOf(d);
    }

    private static String aviso(int numero, List<String> desconocidas) {
        return "Lote " + numero + ": claves desconocidas ignoradas: " + String.join(", ", desconocidas);
    }

    /** El lote solo con las claves del modelo (minimización: un «nombre» o «telefono» que mande el OPL no se guarda). */
    private byte[] minimizar(Lote lote) {
        ObjectNode m = json.createObjectNode();
        m.put("envio", lote.envio());
        m.put("numero", lote.numero());
        m.put("final", lote.fin());
        ArrayNode regs = m.putArray("registros");
        for (JsonNode r : lote.registros()) {
            ObjectNode x = regs.addObject();
            for (Campo k : CAMPOS) {
                JsonNode v = r.get(k.clave());
                if (v != null) {
                    x.set(k.clave(), v);
                }
            }
        }
        try {
            return json.writeValueAsBytes(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Celdas de un registro en el orden de {@link #CAMPOS} (texto; los números sin notación científica). */
    static List<String> celdas(JsonNode r) {
        List<String> celdas = new ArrayList<>(CAMPOS.size());
        for (Campo k : CAMPOS) {
            JsonNode v = r.path(k.clave());
            celdas.add(v.isMissingNode() || v.isNull() ? "" : v.isNumber() ? v.decimalValue().toPlainString() : v.asText(""));
        }
        return celdas;
    }

    /** Cargue del envío (lo crea con el primer lote); queda bloqueado hasta el fin de la transacción: los lotes de un envío van en fila. */
    private long cargueDelEnvio(String envio, UsuarioSesion quien) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        int nuevo = jdbc.sql("""
                INSERT INTO ana_cargues (fuente, fuente_texto, envio, formato, estado, etapa, usuario_id, responsable_texto, creado, actividad,
                                         conservar_hasta)
                VALUES ('ws_opl', :f, :e, 'json', 'subiendo', 'subida', :u, :resp, :ahora, :ahora, :vence)
                ON CONFLICT (envio) WHERE envio IS NOT NULL DO NOTHING""")
                .param("f", FUENTE_TEXTO).param("e", envio).param("u", quien.id())
                .param("resp", ProcesadorCargues.cortar(AnaliticaIngesta.responsable(quien.usuario(), UsuarioSesion.perfilesActuales(), true), 120))
                .param("ahora", ahora).param("vence", ahora.plusDays(propiedades.diasConservar())).update();
        Map<String, Object> c = jdbc.sql("SELECT id, usuario_id FROM ana_cargues WHERE envio = :e FOR UPDATE").param("e", envio).query().singleRow();
        long id = ((Number) c.get("id")).longValue();
        if (c.get("usuario_id") == null || ((Number) c.get("usuario_id")).longValue() != quien.id()) {
            throw ErrorNegocio.conflicto("El envío " + envio + " pertenece a otra cuenta; use otro identificador de envío.");
        }
        if (nuevo == 1) {
            bitacora.registrar("analitica_cargue_creado", "Cargue " + id + " · " + FUENTE_TEXTO + " · envío " + envio, quien.id(), quien.usuario());
        }
        return id;
    }

    private static Map<String, Object> respuesta(long cargueId, String envio, int numero, Guardado g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cargue", cargueId);
        m.put("envio", envio);
        m.put("numero", numero);
        m.put("registros", g.registros());
        m.put("final", g.fin());
        m.put("sha256", g.sha256());
        m.put("clavesDesconocidas", g.desconocidas());
        if (!g.desconocidas().isEmpty()) {
            m.put("aviso", "Claves desconocidas ignoradas: " + String.join(", ", g.desconocidas()));
        }
        return m;
    }

    /** El lote mínimo en el almacén (se descarta si la transacción revierte). */
    private String guardarLote(long cargueId, int numero, byte[] cuerpo) {
        String ruta = "analitica/ws/" + cargueId + "/lote_" + numero + "_" + Huellas.secreto(6) + ".json";
        try {
            almacen.guardar(ruta, cuerpo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int estado) {
                    if (estado != STATUS_COMMITTED) {
                        almacen.descartar(ruta);
                    }
                }
            });
        }
        return ruta;
    }

    /** ¿Llegaron todos los lotes 1..N? N es el número del lote final; los números son únicos, así que basta count = N y max = N. */
    private boolean completo(long cargueId) {
        Map<String, Object> m = jdbc.sql("""
                SELECT c.partes, count(l.numero) AS lotes, max(l.numero) AS mayor
                FROM ana_cargues c LEFT JOIN ana_ws_lotes l ON l.cargue_id = c.id WHERE c.id = :c GROUP BY c.partes""")
                .param("c", cargueId).query().singleRow();
        if (m.get("partes") == null || m.get("mayor") == null) {
            return false;
        }
        long partes = ((Number) m.get("partes")).longValue();
        return partes == ((Number) m.get("lotes")).longValue() && partes == ((Number) m.get("mayor")).longValue();
    }

    /** Envío completo: el cargue pasa a la cola con el mapeo del servicio web (sin tocar el almacén en esta transacción). */
    private void cerrar(long cargueId, OffsetDateTime ahora) {
        String mapeo = aJson(CAMPOS.stream().map(Campo::clave).toList());
        jdbc.sql("""
                UPDATE ana_cargues SET estado = 'pendiente', etapa = NULL, recibido = :ahora, actividad = :ahora, encabezados = CAST(:m AS jsonb),
                       mapeo = CAST(:m AS jsonb), nombre_archivo = 'envio_' || envio || '.json'
                WHERE id = :c AND estado = 'subiendo'""")
                .param("ahora", ahora).param("m", mapeo).param("c", cargueId).update();
    }

    private String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> deJson(String texto) {
        try {
            return texto == null ? List.of() : json.readValue(texto, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Para pruebas y documentación: cuerpo de un lote. */
    static byte[] cuerpo(ObjectMapper json, String envio, int numero, boolean fin, List<Map<String, Object>> registros) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("envio", envio);
        m.put("numero", numero);
        m.put("final", fin);
        m.put("registros", registros);
        try {
            return json.writeValueAsString(m).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 413 para un cuerpo que pasa del tope. */
    static ErrorNegocio demasiadoGrande(long tope) {
        return new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "El lote supera el tamaño máximo (" + tope / (1024 * 1024) + " MB); envíe lotes más "
                + "pequeños.");
    }
}
