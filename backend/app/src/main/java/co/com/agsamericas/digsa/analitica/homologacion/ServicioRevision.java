package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.analitica.SugeridorHomologacion;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.DatoFinal;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Presentacion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * «Revisión» de la homologación (analitica-homologacion, «Revisión humana con sugerencia opcional de la IA»): compara campo a campo el
 * dato del OPL con el del INVIMA; «Aprobar homologación» fija el dato final (el del INVIMA o un CUM elegido) con regla MANUAL y
 * «Devolver al OPL» exige motivo. Cada decisión es una fila inmutable de hom_revision ligada a una versión nueva de la tabla maestra que
 * NO copia filas: la decisión se aplica al leer (capa de decisiones de hom_tabla_maestra_version), así que una consulta con una versión
 * o un corte anterior no la ve y 50 decisiones no multiplican las filas. La decisión se toma sobre la versión que la persona revisó: si
 * otra la cambió entretanto, 409. La sugerencia de la IA solo se muestra y se valida contra los candidatos del CUM: nunca cambia un estado.
 */
@Service
class ServicioRevision {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioRevision.class);
    static final int MAX_MOTIVO = 500;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ServicioHomologacion homologacion;
    private final SugeridorHomologacion sugeridor;
    private final RegistroBitacora bitacora;
    private final Clock reloj;
    private final TransactionTemplate tx;

    ServicioRevision(JdbcClient jdbc, ObjectMapper json, ServicioHomologacion homologacion, SugeridorHomologacion sugeridor, RegistroBitacora bitacora,
                     Clock reloj, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.json = json;
        this.homologacion = homologacion;
        this.sugeridor = sugeridor;
        this.bitacora = bitacora;
        this.reloj = reloj;
        this.tx = new TransactionTemplate(transacciones);
    }

    /** Fila de la versión (completa, con decisiones) para un dato original (404 si no está). */
    private Map<String, Object> fila(int version, long origenId) {
        return jdbc.sql("SELECT * FROM hom_tabla_maestra_version(:v) t WHERE t.origen_id = :o").param("v", version).param("o", origenId)
                .query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El dato no está en la tabla maestra vigente."));
    }

    private int vigente() {
        Integer v = homologacion.vigente();
        if (v == null) {
            throw ErrorNegocio.noEncontrado("Aún no hay tabla maestra: cargue el insumo del OPL o re-ejecute la homologación.");
        }
        return v;
    }

    // ---------- Comparación OPL frente a INVIMA ----------

    /** Campo a campo OPL frente a INVIMA, candidatos del CUM, la versión revisada y si hay sugeridor. */
    Map<String, Object> comparacion(long origenId) {
        int v = vigente();
        Map<String, Object> f = fila(v, origenId);
        Map<String, Object> origen = jdbc.sql("SELECT descripcion, cum_original, fecha_referencia FROM hom_medicamento_origen WHERE id = :o")
                .param("o", origenId).query().singleRow();
        List<Map<String, Object>> campos = new ArrayList<>();
        campos.add(campo("Principio activo", f.get("orig_principio"), f.get("fin_principio_activo")));
        campos.add(campo("Concentración", f.get("orig_concentracion"), f.get("fin_concentracion")));
        campos.add(campo("Forma farmacéutica", f.get("orig_forma"), f.get("fin_forma")));
        String registro = (String) f.get("fin_registro_sanitario");
        String vigencia = null;
        if (f.get("fin_cum") != null) {
            vigencia = jdbc.sql("""
                    SELECT p.fecha_vencimiento::text FROM ref_cum_presentacion p JOIN ref_version v ON v.id = p.version_id AND v.estado = 'publicada'
                    WHERE p.cum = :c ORDER BY p.version_id DESC LIMIT 1""").param("c", f.get("fin_cum")).query(String.class).optional().orElse(null);
        }
        campos.add(campo("Registro sanitario", null, registro == null ? null : registro + (vigencia == null ? "" : " · vence " + vigencia)));
        campos.add(campo("Presentación", f.get("orig_presentacion"), f.get("fin_presentacion")));
        campos.add(campo("ATC", f.get("orig_atc"), f.get("fin_atc")));
        campos.add(campo("CUM", f.get("orig_cum"), f.get("fin_cum")));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("origenId", origenId);
        r.put("version", v);
        r.put("descripcion", origen.get("descripcion"));
        r.put("estado", f.get("estado"));
        r.put("regla", f.get("regla"));
        r.put("causa", f.get("causa"));
        r.put("diferencias", lista(f.get("diferencias")));
        r.put("campos", campos);
        r.put("candidatos", candidatos(lista(f.get("candidatos")), (String) f.get("fin_cum")));
        r.put("exigeCum", "no_valido".equals(f.get("estado")));
        r.put("sugeridor", Map.of("disponible", sugeridor.disponible(), "proveedor", sugeridor.proveedor()));
        return r;
    }

    private static Map<String, Object> campo(String nombre, Object opl, Object invima) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("campo", nombre);
        m.put("opl", opl);
        m.put("invima", invima);
        m.put("distinto", opl != null && invima != null
                && !AnaliticaHomologacion.normalizarTexto(String.valueOf(opl)).equals(AnaliticaHomologacion.normalizarTexto(String.valueOf(invima))));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<String> lista(Object arreglo) {
        if (arreglo instanceof java.sql.Array a) {
            try {
                return new ArrayList<>(List.of((String[]) a.getArray()));
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return arreglo instanceof List<?> l ? (List<String>) l : new ArrayList<>();
    }

    /** Descripción de los CUM candidatos en las versiones publicadas (para elegir al aprobar). */
    private List<Map<String, Object>> candidatos(List<String> cums, String finalCum) {
        List<String> todos = new ArrayList<>(cums);
        if (finalCum != null && !todos.contains(finalCum)) {
            todos.addFirst(finalCum);
        }
        if (todos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT ON (p.cum) p.cum, f.conjunto, p.producto, p.forma_farmaceutica, p.estado_cum, p.registro_sanitario, p.atc,
                       (SELECT string_agg(a.principio_activo || coalesce(' ' || a.cantidad::text, '') || coalesce(' ' || a.unidad_medida, ''), ' + '
                                          ORDER BY a.principio_norm)
                        FROM ref_cum_principio_activo a WHERE a.version_id = p.version_id AND a.cum = p.cum) AS principios
                FROM ref_cum_presentacion p JOIN ref_version v ON v.id = p.version_id AND v.estado = 'publicada'
                JOIN ref_fuente f ON f.id = v.fuente_id
                WHERE p.cum = ANY(:c)
                ORDER BY p.cum, CASE f.conjunto WHEN 'vigentes' THEN 0 WHEN 'renovacion' THEN 1 WHEN 'vencidos' THEN 2 ELSE 3 END""")
                .param("c", todos.toArray(String[]::new)).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cum", rs.getString(1));
                    m.put("conjunto", rs.getString(2));
                    m.put("producto", rs.getString(3));
                    m.put("forma", rs.getString(4));
                    m.put("estadoCum", rs.getString(5));
                    m.put("registro", rs.getString(6));
                    m.put("atc", rs.getString(7));
                    m.put("principios", rs.getString(8));
                    return m;
                }).list();
    }

    // ---------- Sugerencia de la IA ----------

    /**
     * Pide una sugerencia al proveedor y la valida contra los candidatos: ver {@link #sugerir}. No cambia ningún estado ni crea versiones.
     */
    Map<String, Object> sugerencia(long origenId) {
        Map<String, Object> c = comparacion(origenId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) c.get("candidatos");
        return sugerir(sugeridor, String.valueOf(c.get("descripcion")), cands);
    }

    /**
     * Lo que va al proveedor de IA: solo el texto del medicamento y, de cada candidato del CUM, su número y su descripción del INVIMA
     * (producto, principios y forma); nunca seudónimos, ESM, cantidades ni nada del registro de dispensación (RESP L5721, Ley 1581). La
     * respuesta solo vale si su CUM está entre los candidatos (un CUM inventado se descarta). Si el proveedor falla: «Sin sugerencia
     * automática», sin error (se registra solo la clase de la excepción: el mensaje del proveedor podría repetir el texto enviado).
     */
    static Map<String, Object> sugerir(SugeridorHomologacion sugeridor, String descripcion, List<Map<String, Object>> candidatos) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("disponible", sugeridor.disponible());
        r.put("proveedor", sugeridor.proveedor());
        if (!sugeridor.disponible()) {
            r.put("mensaje", "Sin sugerencia automática");
            return r;
        }
        List<SugeridorHomologacion.Candidato> lista = candidatos.stream().map(x -> new SugeridorHomologacion.Candidato((String) x.get("cum"),
                x.get("producto") + " · " + x.get("principios") + " · " + x.get("forma"))).toList();
        Optional<SugeridorHomologacion.Sugerencia> s;
        try {
            s = sugeridor.sugerir(descripcion, lista);
        } catch (RuntimeException e) {
            LOG.warn("[homologacion] El sugeridor {} falló ({})", sugeridor.proveedor(), e.getClass().getSimpleName());
            s = Optional.empty();
        }
        Optional<SugeridorHomologacion.Sugerencia> valida = validarSugerencia(s, lista);
        r.put("mensaje", valida.isPresent() ? "Sugerencia (IA)" : "Sin sugerencia automática");
        r.put("sugerencia", valida.map(x -> Map.of("cum", x.cum(), "justificacion", String.valueOf(x.justificacion()),
                "confianza", x.confianza() == null ? "" : x.confianza().toPlainString())).orElse(null));
        return r;
    }

    /** La sugerencia solo vale si su CUM es uno de los candidatos (un CUM inventado por el modelo se descarta). */
    static Optional<SugeridorHomologacion.Sugerencia> validarSugerencia(Optional<SugeridorHomologacion.Sugerencia> s,
                                                                         List<SugeridorHomologacion.Candidato> candidatos) {
        return s.filter(x -> x.cum() != null && candidatos.stream().anyMatch(c -> c.cum().equals(x.cum())));
    }

    // ---------- Aprobar y devolver ----------

    /** La decisión se toma sobre la versión que se revisó: si ya no es la vigente, 409. */
    private static void mismaVersion(Integer revisada, int vigente) {
        if (revisada != null && revisada != vigente) {
            throw ErrorNegocio.conflicto("La tabla maestra cambió desde que abrió el registro; vuelva a abrirlo y revise de nuevo.");
        }
    }

    /**
     * Aprueba la homologación con el dato del INVIMA de la fila o con el CUM elegido (que debe estar vigente); crea la versión N+1 sin
     * copiar filas. Un registro «CUM o registro no válido» solo se aprueba eligiendo un CUM vigente.
     *
     * @param versionRevisada versión con la que se abrió la comparación (409 si cambió); nula = sin control
     */
    Map<String, Object> aprobar(long origenId, String cumElegido, Integer versionRevisada, UsuarioSesion quien, String responsable) {
        Object[] r = tx.execute(t -> {
            ServicioHomologacion.candado(jdbc, ServicioHomologacion.ESPERA_PANTALLA);
            int anterior = vigente();
            mismaVersion(versionRevisada, anterior);
            Map<String, Object> f = fila(anterior, origenId);
            if ("homologado".equals(f.get("estado"))) {
                throw ErrorNegocio.conflicto("El dato ya está homologado.");
            }
            boolean conCum = cumElegido != null && !cumElegido.isBlank();
            if (!conCum && "no_valido".equals(f.get("estado"))) {
                throw ErrorNegocio.validacion("El registro tiene «CUM o registro no válido»: elija un CUM vigente del INVIMA para aprobarlo.", "cum");
            }
            Map<String, Object> dato;
            Instantanea inst = Instantanea.cargar(jdbc, json);
            if (conCum) {
                String cum = AnaliticaHomologacion.normalizarCum(cumElegido)
                        .orElseThrow(() -> ErrorNegocio.validacion("El CUM elegido no tiene un formato válido.", "cum"));
                Presentacion p = vigenteDe(inst, cum)
                        .orElseThrow(() -> ErrorNegocio.validacion("El CUM elegido no está vigente en las versiones publicadas del INVIMA.", "cum"));
                DatoFinal d = AnaliticaHomologacion.datoDe(p, inst.configuracion());
                dato = ServicioHomologacion.mapaDeDato(d);
                Instantanea.Fuente fuente = inst.cum(p.conjunto()).orElseThrow();
                dato.put("fuenteCodigo", fuente.codigo());
                dato.put("fuenteVersionId", fuente.versionId());
                dato.put("fuenteFechaCorte", fuente.fechaCorte().toString());
            } else {
                if (f.get("fin_cum") == null && f.get("fin_dci") == null) {
                    throw ErrorNegocio.validacion("Elija el CUM del INVIMA para aprobar.", "cum");
                }
                // El CUM del dato final de la fila también debe estar vigente (la misma exigencia que con un CUM elegido)
                if (f.get("fin_cum") != null && vigenteDe(inst, (String) f.get("fin_cum")).isEmpty()) {
                    throw ErrorNegocio.validacion("El CUM del dato final no está vigente en el INVIMA: elija un CUM vigente para aprobar.", "cum");
                }
                dato = ServicioHomologacion.mapaDeDato(new DatoFinal((String) f.get("fin_nombre_comercial"), (String) f.get("fin_dci"),
                        (String) f.get("fin_principio_activo"), (String) f.get("fin_concentracion"), (String) f.get("fin_forma"),
                        (String) f.get("fin_presentacion"), (String) f.get("fin_unidad"), (String) f.get("fin_cum"), (String) f.get("fin_codigo_ssfm"),
                        (String) f.get("fin_atc"), (String) f.get("fin_grupo_farmacologico"), (String) f.get("fin_registro_sanitario")));
                dato.put("fuenteCodigo", f.get("fuente_codigo"));
                dato.put("fuenteVersionId", f.get("fuente_version_id"));
                dato.put("fuenteFechaCorte", f.get("fuente_fecha_corte") == null ? null : f.get("fuente_fecha_corte").toString());
            }
            Object[] x = decidir(anterior, f, "aprobar", dato, null, (String) dato.get("cum"), quien, responsable);
            // La devolución abierta del dato queda resuelta en Revisión (no la corrigió el OPL)
            jdbc.sql("""
                    UPDATE hom_devolucion SET estado = 'resuelta', resuelta_revision_id = :r, actualizado = :ahora
                    WHERE origen_id = :o AND estado IN ('devuelta', 'persiste')""")
                    .param("r", x[2]).param("ahora", OffsetDateTime.now(reloj)).param("o", origenId).update();
            return x;
        });
        registrar("homologacion_aprobada", (String) r[1], quien);
        return Map.of("version", r[0], "descripcion", r[1]);
    }

    /** Presentación vigente (vigentes o en trámite, activa) del CUM en las versiones publicadas. */
    private Optional<Presentacion> vigenteDe(Instantanea inst, String cum) {
        return inst.referencias(jdbc, Set.of(cum), Set.of()).porCum(cum).stream().filter(Presentacion::vigente).findFirst();
    }

    /**
     * Devuelve el dato al OPL con motivo: queda «pendiente de revisión · devuelto al OPL» en la versión N+1 y en el seguimiento. El motivo
     * solo se guarda en la revisión y en el seguimiento (no en la tabla maestra).
     */
    Map<String, Object> devolver(long origenId, String motivo, Integer versionRevisada, UsuarioSesion quien, String responsable) {
        if (motivo == null || motivo.isBlank()) {
            throw ErrorNegocio.validacion("Escriba el motivo de la devolución.", "motivo");
        }
        if (motivo.strip().length() > MAX_MOTIVO) {
            throw ErrorNegocio.validacion("El motivo no puede pasar de " + MAX_MOTIVO + " caracteres.", "motivo");
        }
        String m = motivo.strip();
        Object[] r = tx.execute(t -> {
            ServicioHomologacion.candado(jdbc, ServicioHomologacion.ESPERA_PANTALLA);
            int anterior = vigente();
            mismaVersion(versionRevisada, anterior);
            Map<String, Object> f = fila(anterior, origenId);
            if ("homologado".equals(f.get("estado"))) {
                throw ErrorNegocio.conflicto("El dato ya está homologado: no hay nada que devolver.");
            }
            Object[] x = decidir(anterior, f, "devolver", null, m, null, quien, responsable);
            Map<String, Object> o = jdbc.sql("""
                    SELECT o.ultimo_cargue_id, c.fuente, date_trunc('month', o.fecha_referencia)::date AS periodo
                    FROM hom_medicamento_origen o LEFT JOIN ana_cargues c ON c.id = o.ultimo_cargue_id WHERE o.id = :o""")
                    .param("o", origenId).query().singleRow();
            jdbc.sql("""
                    INSERT INTO hom_devolucion (origen_id, revision_id, cargue_id, fuente, periodo, motivo, fecha, usuario_id)
                    VALUES (:o, :r, :c, :fuente, :periodo, :m, :ahora, :u)""")
                    .param("o", origenId).param("r", x[2]).param("c", o.get("ultimo_cargue_id")).param("fuente", o.get("fuente"))
                    .param("periodo", o.get("periodo")).param("m", m).param("ahora", OffsetDateTime.now(reloj)).param("u", quien.id()).update();
            return x;
        });
        registrar("homologacion_devuelta", (String) r[1], quien);
        return Map.of("version", r[0], "descripcion", r[1]);
    }

    /** La bitácora va después del commit: si falla, la decisión ya quedó y se registra el error (no un 500 con la decisión aplicada). */
    private void registrar(String tipo, String descripcion, UsuarioSesion quien) {
        try {
            bitacora.registrar(tipo, descripcion, quien.id(), quien.usuario());
        } catch (RuntimeException e) {
            LOG.error("[homologacion] La decisión quedó aplicada pero no se pudo registrar en la bitácora: {}", descripcion, e);
        }
    }

    /**
     * Crea la versión N+1 (publicada, sin filas propias: la decisión se aplica al leer) y la decisión inmutable en hom_revision, con el
     * conteo de la versión ajustado. Devuelve {versión, descripción para la bitácora, id de la revisión}.
     */
    private Object[] decidir(int anterior, Map<String, Object> f, String accion, Map<String, Object> dato, String motivo, String cum,
                             UsuarioSesion quien, String responsable) {
        long origenId = ((Number) f.get("origen_id")).longValue();
        Map<String, Object> v = jdbc.sql("SELECT fuentes::text, reglas::text, catalogos::text, conteo::text, origenes FROM hom_version WHERE id = :v")
                .param("v", anterior).query().singleRow();
        String estadoAntes = (String) f.get("estado");
        String estadoDespues = "aprobar".equals(accion) ? "homologado" : "pendiente";
        Map<String, Object> conteo = new TreeMap<>(Instantanea.leer(json, (String) v.get("conteo")));
        conteo.merge(estadoAntes, -1, (a, b) -> ((Number) a).longValue() - 1);
        conteo.merge(estadoDespues, 1, (a, b) -> ((Number) a).longValue() + 1);
        conteo.values().removeIf(x -> ((Number) x).longValue() <= 0);
        int numero = homologacion.siguienteNumero();
        String descripcion = ServicioHomologacion.cortar("Homologación v" + numero + " · dato " + origenId + " ("
                + ServicioHomologacion.cortar(String.valueOf(f.get("orig_descripcion")), 80) + "): " + estadoAntes + " · " + f.get("regla") + " → "
                + estadoDespues + " · MANUAL" + (cum != null ? " · CUM " + cum : "") + ("devolver".equals(accion) ? " · devuelto al OPL" : ""), 500);
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        int nueva = jdbc.sql("""
                INSERT INTO hom_version (numero, creada, motivo, anterior_id, usuario_id, responsable_texto, fuentes, reglas, catalogos, origenes, conteo,
                                         cambios, duracion_ms, detalle)
                VALUES (:n, :ahora, :motivo, :ant, :u, :resp, CAST(:fuentes AS jsonb), CAST(:reglas AS jsonb), CAST(:catalogos AS jsonb), :origenes,
                        CAST(:conteo AS jsonb), 1, 0, :detalle)
                RETURNING id""")
                .param("n", numero).param("ahora", ahora).param("motivo", "aprobar".equals(accion) ? "aprobacion" : "devolucion").param("ant", anterior)
                .param("u", quien.id()).param("resp", responsable).param("fuentes", v.get("fuentes")).param("reglas", v.get("reglas"))
                .param("catalogos", v.get("catalogos")).param("origenes", v.get("origenes")).param("conteo", homologacion.aJson(conteo))
                .param("detalle", descripcion).query(Integer.class).single();
        Map<String, Object> antes = new LinkedHashMap<>();
        antes.put("estado", estadoAntes);
        antes.put("regla", f.get("regla"));
        antes.put("causa", f.get("causa"));
        antes.put("cum", f.get("fin_cum"));
        Map<String, Object> despues = new LinkedHashMap<>();
        despues.put("estado", estadoDespues);
        despues.put("regla", AnaliticaHomologacion.MANUAL);
        despues.put("cum", "aprobar".equals(accion) ? dato.get("cum") : f.get("fin_cum"));
        long revision = jdbc.sql("""
                INSERT INTO hom_revision (origen_id, version_id, accion, cum_elegido, dato, antes, despues, motivo, usuario_id, responsable_texto, fecha)
                VALUES (:o, :v, :a, :cum, CAST(:dato AS jsonb), CAST(:antes AS jsonb), CAST(:despues AS jsonb), :motivo, :u, :resp, :ahora) RETURNING id""")
                .param("o", origenId).param("v", nueva).param("a", accion).param("cum", cum).param("dato", dato == null ? null : homologacion.aJson(dato))
                .param("antes", homologacion.aJson(antes)).param("despues", homologacion.aJson(despues)).param("motivo", motivo).param("u", quien.id())
                .param("resp", responsable).param("ahora", ahora).query(Long.class).single();
        return new Object[] {nueva, descripcion, revision};
    }

    static LocalDate fecha(Object o) {
        return o instanceof Date d ? d.toLocalDate() : null;
    }
}
