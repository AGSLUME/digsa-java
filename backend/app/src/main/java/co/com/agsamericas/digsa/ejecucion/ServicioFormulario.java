package co.com.agsamericas.digsa.ejecucion;

import co.com.agsamericas.digsa.agenda.RepositorioVisitas;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Checklist;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.EstadosVisita;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Plazos;
import co.com.agsamericas.digsa.reglas.Reparto;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Formulario de la visita en ejecución (Anexo 5, prueba 12): datos impuestos por la agenda (número, lugar, auditor y equipo),
 * inicio y fin reales, objeto, personal auditado, calificación de cada ítem con su hallazgo, observaciones y firmas.
 */
@Service
public class ServicioFormulario {

    static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** Ítem del formulario de esta visita (de la versión con que se ejecuta y de los componentes elegidos). */
    record ItemVisita(long id, String codigo, String componente, String componenteNombre, String parte, String punto, String criterio,
                      String requisito, String evidencia, String condicion, boolean escalaPropia, boolean obligacion) {
        /** Clave del hallazgo y de sus soportes: «it:<código>» para un ítem, «ob:<código>» para una obligación de la minuta. */
        String clave() {
            return (obligacion ? "ob:" : "it:") + codigo;
        }

        Checklist.Item regla() {
            return new Checklist.Item(codigo, componente, parte, escalaPropia, condicion != null && !condicion.isBlank());
        }
    }

    /**
     * @param enTramite D5: el ítem de habilitación (C00) en «No cumple» con la habilitación en trámite (sigue siendo hallazgo)
     * @param inmediata fase 9: el hallazgo exige intervención inmediata del OPL (factor del riesgo); solo en «No cumple»
     */
    public record Respuesta(String item, String calificacion, String hallazgo, Boolean enTramite, Boolean inmediata) {
    }

    /** Componente de las validaciones de habilitación. */
    static final String HABILITACION = "C00";
    /** Lo que queda pendiente con la habilitación en trámite (decisión D5 del 2026-10-21). */
    public static final String TEXTO_TRAMITE = "Habilitación en trámite: queda pendiente aportar el distintivo de habilitación o las actas de visita del ente habilitador.";

    public record Ejecucion(String inicioReal, String finReal, String objeto, String recomendaciones, String observacionesAuditor,
                            String observacionesEsm, String observacionesOpl) {
    }

    public record Persona(String entidad, String nombre, String cargo, String documento, String correo) {
    }

    public record Firma(String rol, String nombre, String documento, String cargo, String imagen) {
    }

    /** Fila del plan de mejoramiento 5W2H, parte del auditor (Anexo 3 p.11); quién y cómo los diligencia el OPL. */
    public record FilaPlan(String clave, String causas, String que, String porque, String donde, String inicio, String fin, String indicador,
                           String estado, Integer avance, String prorroga) {
    }

    public record Plan(String fecha, List<FilaPlan> filas) {
    }

    static final Set<String> ESTADOS_PLAN = Set.of("Pendiente", "En curso", "Completado", "Retrasado", "Cancelado");
    /** Acciones abiertas: las que exigen fecha fin para enviar el acta y se verifican en el seguimiento. */
    static final Set<String> ESTADOS_ABIERTOS = Set.of("Pendiente", "En curso", "Retrasado");

    private final RepositorioVisitas visitas;
    private final JdbcClient jdbc;
    private final Clock reloj;
    private final ServicioSeguimiento seguimiento;

    public ServicioFormulario(RepositorioVisitas visitas, JdbcClient jdbc, Clock reloj, ServicioSeguimiento seguimiento) {
        this.visitas = visitas;
        this.jdbc = jdbc;
        this.reloj = reloj;
        this.seguimiento = seguimiento;
    }

    // ---------- Acceso ----------

    Acceso acceso(long visitaId, UsuarioSesion quien, Set<Perfil> perfiles, boolean bloquear) {
        RepositorioVisitas.Visita v = (bloquear ? visitas.bloquear(visitaId) : visitas.porId(visitaId))
                .orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
        boolean coordinador = perfiles.contains(Perfil.COORDINADOR) || perfiles.contains(Perfil.ADMINISTRADOR);
        List<RepositorioVisitas.Miembro> equipo = visitas.equipo(visitaId);
        boolean miembro = equipo.stream().anyMatch(m -> m.id() == quien.id());
        if (!miembro && !coordinador) {
            throw ErrorNegocio.noEncontrado("La visita no existe o no está asignada a usted.");
        }
        if (!Set.of("ejecucion", "cerrada").contains(v.estado())) {
            throw ErrorNegocio.validacion("El formulario se abre cuando la visita está en ejecución (estado: "
                    + EstadosVisita.NOMBRES.get(v.estado()) + ").");
        }
        boolean lider = v.auditorId() == quien.id();
        boolean unico = miembro && equipo.size() == 1;
        boolean correccion = coordinador && !miembro && v.reaperturas() > 0;
        Set<String> partes = new HashSet<>(visitas.partesDe(visitaId, quien.id()));
        String motivo = null;
        if (!"ejecucion".equals(v.estado())) {
            motivo = "Visita cerrada: el acta ya se envió.";
        } else if (!miembro && !correccion) {
            motivo = "Consulta: solo los auditores de la visita modifican el formulario; el coordinador lo corrige si la visita se reabre.";
        } else if (actaEnviadaEstaRonda(v)) {
            motivo = "El acta de esta ronda ya se envió o se está enviando.";
        }
        return new Acceso(v, miembro, lider, coordinador, unico || correccion, partes, motivo == null, motivo);
    }

    /** El acta de esta ronda ya salió, o se está enviando (menos de 10 minutos): el formulario no cambia mientras tanto. */
    private boolean actaEnviadaEstaRonda(RepositorioVisitas.Visita v) {
        return jdbc.sql("""
                SELECT count(*) FROM visitas_actas WHERE visita_id = :v AND ronda = :r
                  AND (estado IN ('enviada', 'parcial') OR (estado = 'pendiente' AND generado > :h))""")
                .param("v", v.id()).param("r", v.reaperturas() + 1).param("h", OffsetDateTime.now(reloj).minusMinutes(10))
                .query(Long.class).single() > 0;
    }

    private static void exigir(boolean ok, String mensaje) {
        if (!ok) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, mensaje);
        }
    }

    // ---------- Ítems de la visita ----------

    List<ItemVisita> items(RepositorioVisitas.Visita v) {
        String version = v.checklistVersion() != null ? v.checklistVersion()
                : visitas.versionVigente().orElseThrow(() -> ErrorNegocio.validacion("No hay una versión vigente del checklist."));
        List<String> elegidos = visitas.componentesDe(v.id());
        boolean sis = visitas.reparto(v.id()).containsKey(Reparto.PARTE_SISTEMAS);
        List<ItemVisita> lista = jdbc.sql("""
                SELECT i.id, i.codigo, c.codigo AS componente, c.nombre AS componente_nombre, i.punto, i.criterio, i.requisito, i.evidencia,
                       i.condicion, (c.escala IS NOT NULL) AS escala_propia
                FROM chk_items i JOIN chk_componentes c ON c.id = i.componente_id
                WHERE c.version = :v AND c.codigo = ANY(:comps) AND :unidad = ANY(c.unidades)
                ORDER BY c.orden, c.codigo, i.orden, i.id""")
                .param("v", version).param("comps", elegidos.toArray(String[]::new)).param("unidad", v.unidad())
                .query((rs, n) -> new ItemVisita(rs.getLong("id"), rs.getString("codigo"), rs.getString("componente"),
                        rs.getString("componente_nombre"), Reparto.parteDeItem(rs.getString("codigo"), rs.getString("componente"), sis),
                        rs.getString("punto"), rs.getString("criterio"), rs.getString("requisito"), rs.getString("evidencia"),
                        rs.getString("condicion"), rs.getBoolean("escala_propia"), false))
                .list();
        List<ItemVisita> todos = new ArrayList<>(lista);
        if (visitas.reparto(v.id()).containsKey(Reparto.OBLIGACIONES) || "ejecucion".equals(v.estado()) || "cerrada".equals(v.estado())) {
            todos.addAll(obligaciones(v));
        }
        // En el seguimiento solo se recalifican los hallazgos por verificar (decisiones 17, 6.5 y 6.11): el resumen, los faltantes, los
        // hallazgos y el acta cuentan solo eso; lo demás se ve como quedó en la visita de origen
        if (v.esSeguimiento()) {
            Set<String> verificar = seguimiento.porVerificar(v);
            return todos.stream().filter(i -> verificar.contains(i.clave())).toList();
        }
        return todos;
    }

    /**
     * Obligaciones de la minuta del prestador visitado (farmacia alterna si la unidad es FA; si no, el OPL) como parte OBL: las activas
     * y las ya calificadas en esta visita (aunque luego se hayan inactivado).
     */
    List<ItemVisita> obligaciones(RepositorioVisitas.Visita v) {
        String prestador = "FA".equals(v.unidad()) ? "fa" : "opl";
        return jdbc.sql("""
                SELECT o.id, o.codigo, o.texto, o.fuente FROM obligaciones_contrato o
                WHERE o.prestador = :p AND (o.activo OR EXISTS (SELECT 1 FROM visitas_obligaciones vo WHERE vo.visita_id = :v AND vo.obligacion_id = o.id))
                ORDER BY o.orden, o.codigo""").param("p", prestador).param("v", v.id())
                .query((rs, n) -> new ItemVisita(rs.getLong("id"), rs.getString("codigo"), Reparto.OBLIGACIONES, "Obligaciones de la minuta",
                        Reparto.OBLIGACIONES, rs.getString("texto"), rs.getString("texto"), null, rs.getString("fuente"), null, false, true))
                .list();
    }

    /** Ítems marcados con la habilitación en trámite. */
    Set<String> enTramite(long visitaId) {
        return new HashSet<>(jdbc.sql("SELECT i.codigo FROM visitas_respuestas r JOIN chk_items i ON i.id = r.item_id WHERE r.visita_id = :v AND r.en_tramite")
                .param("v", visitaId).query(String.class).list());
    }

    /** Ítems y obligaciones marcados de intervención inmediata (los códigos son únicos en la visita). */
    Set<String> inmediatas(long visitaId) {
        return new HashSet<>(jdbc.sql("""
                SELECT i.codigo FROM visitas_respuestas r JOIN chk_items i ON i.id = r.item_id WHERE r.visita_id = :v AND r.inmediata
                UNION SELECT o.codigo FROM visitas_obligaciones r JOIN obligaciones_contrato o ON o.id = r.obligacion_id WHERE r.visita_id = :v AND r.inmediata""")
                .param("v", visitaId).query(String.class).list());
    }

    /** Ronda vigente del acta (cada reapertura abre otra, con firmas nuevas: decisión D12). */
    static int ronda(RepositorioVisitas.Visita v) {
        return v.reaperturas() + 1;
    }

    Map<String, Checklist.Respuesta> respuestas(long visitaId) {
        Map<String, Checklist.Respuesta> r = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT i.codigo, r.calificacion, r.hallazgo FROM visitas_respuestas r JOIN chk_items i ON i.id = r.item_id WHERE r.visita_id = :v""")
                .param("v", visitaId).query((rs, n) -> r.put(rs.getString(1), new Checklist.Respuesta(rs.getString(2), rs.getString(3)))).list();
        jdbc.sql("""
                SELECT o.codigo, vo.calificacion, vo.hallazgo FROM visitas_obligaciones vo JOIN obligaciones_contrato o ON o.id = vo.obligacion_id
                WHERE vo.visita_id = :v""")
                .param("v", visitaId).query((rs, n) -> r.put(rs.getString(1), new Checklist.Respuesta(rs.getString(2), rs.getString(3)))).list();
        return r;
    }

    // ---------- Lectura del formulario ----------

    @Transactional(readOnly = true)
    public Map<String, Object> formulario(long visitaId, UsuarioSesion quien, Set<Perfil> perfiles) {
        Acceso a = acceso(visitaId, quien, perfiles, false);
        RepositorioVisitas.Visita v = a.visita();
        List<RepositorioVisitas.Miembro> equipo = visitas.equipo(visitaId);
        Map<Long, String> nombres = equipo.stream().collect(Collectors.toMap(RepositorioVisitas.Miembro::id, RepositorioVisitas.Miembro::nombre));
        Map<String, Long> reparto = visitas.reparto(visitaId);
        Map<String, Object> m = new LinkedHashMap<>();

        // Datos impuestos por la agenda: no se editan en el formulario
        Map<String, Object> lugar = jdbc.sql("""
                SELECT p.nombre, p.sigla, p.direccion, mu.nombre AS municipio, d.nombre AS departamento FROM puntos p
                LEFT JOIN par_municipios mu ON mu.codigo = p.municipio LEFT JOIN par_departamentos d ON d.codigo = mu.departamento WHERE p.id = :id""")
                .param("id", v.puntoId()).query().singleRow();
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("id", v.id());
        datos.put("numero", v.numero());
        datos.put("tipo", visitas.tipo(v.tipo()).map(RepositorioVisitas.Tipo::nombre).orElse(v.tipo()));
        datos.put("lugar", lugar.get("nombre"));
        datos.put("sigla", lugar.get("sigla"));
        datos.put("direccion", lugar.get("direccion"));
        datos.put("municipio", lugar.get("municipio"));
        datos.put("departamento", lugar.get("departamento"));
        datos.put("unidad", v.unidad());
        datos.put("fuerza", v.fuerza());
        datos.put("modalidad", v.modalidad());
        datos.put("fechaProgramada", v.fecha().toString());
        datos.put("fechaLarga", Plazos.fechaLarga(v.fecha()));
        datos.put("hora", v.hora() == null ? null : v.hora().toString());
        datos.put("alcance", v.alcance());
        datos.put("estado", v.estado());
        datos.put("estadoNombre", EstadosVisita.NOMBRES.get(v.estado()));
        datos.put("checklistVersion", v.checklistVersion());
        datos.put("ronda", v.reaperturas() + 1);
        datos.put("lider", nombres.get(v.auditorId()));
        datos.put("equipo", equipo.stream().map(x -> Map.of("id", x.id(), "nombre", x.nombre(), "rol", Reparto.ROLES.getOrDefault(x.rol(), "Otro"),
                "lider", x.id() == v.auditorId())).toList());
        m.put("visita", datos);
        m.put("seguimiento", seguimiento.datos(v));

        Map<String, Object> permisos = new LinkedHashMap<>();
        permisos.put("escribible", a.escribible());
        permisos.put("motivo", a.motivoSoloLectura());
        permisos.put("lider", a.lider());
        permisos.put("todo", a.todo());
        permisos.put("correccion", a.correccion());
        permisos.put("escribeVisita", a.escribeVisita());
        permisos.put("misPartes", new TreeSet<>(a.misPartes()));
        permisos.put("enviaActa", a.escribible() && (a.lider() || (a.todo() && !a.correccion())));
        m.put("permisos", permisos);

        m.put("ejecucion", ejecucion(visitaId));
        m.put("personal", jdbc.sql("SELECT entidad, nombre, cargo, documento, correo FROM visitas_personal WHERE visita_id = :v ORDER BY orden, id")
                .param("v", visitaId).query().listOfRows());
        m.put("firmas", jdbc.sql("SELECT rol, nombre, documento, cargo, firmado FROM visitas_firmas WHERE visita_id = :v AND ronda = :r ORDER BY rol")
                .param("v", visitaId).param("r", ronda(v)).query().listOfRows());

        // Componentes con sus ítems; cada ítem dice de quién es la parte y si este usuario lo puede calificar
        List<ItemVisita> items = items(v);
        Map<String, Checklist.Respuesta> resp = respuestas(visitaId);
        Map<String, List<Map<String, Object>>> archivos = archivosPorClave(visitaId);
        Set<String> tramite = enTramite(visitaId);
        Set<String> inmediatas = inmediatas(visitaId);
        Map<String, Map<String, Object>> anteriores = seguimiento.anteriores(v);
        Map<String, List<Map<String, Object>>> archivosAnteriores = seguimiento.archivosAnteriores(v,
                items.stream().map(ItemVisita::clave).collect(Collectors.toSet()));
        Map<String, Map<String, Object>> comps = new LinkedHashMap<>();
        for (ItemVisita i : items) {
            Map<String, Object> c = comps.computeIfAbsent(i.componente(), k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("codigo", i.componente());
                x.put("nombre", i.componenteNombre());
                x.put("escalaPropia", i.escalaPropia());
                x.put("items", new ArrayList<Map<String, Object>>());
                return x;
            });
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("id", i.id());
            it.put("codigo", i.codigo());
            it.put("parte", i.parte());
            it.put("responsable", nombres.get(reparto.get(i.parte())));
            it.put("editable", a.escribeParte(i.parte()));
            it.put("punto", i.punto());
            it.put("criterio", i.criterio());
            it.put("requisito", i.requisito());
            it.put("evidencia", i.evidencia());
            it.put("condicion", i.condicion());
            it.put("admiteNoAplica", i.regla().admiteNoAplica());
            Checklist.Respuesta r = resp.get(i.codigo());
            it.put("calificacion", r == null ? null : r.calificacion());
            it.put("hallazgo", r == null ? null : r.hallazgo());
            it.put("clave", i.clave());
            it.put("obligacion", i.obligacion());
            it.put("admiteTramite", !i.obligacion() && HABILITACION.equals(i.componente()));
            it.put("enTramite", tramite.contains(i.codigo()));
            it.put("inmediata", inmediatas.contains(i.codigo()));
            it.put("archivos", archivos.getOrDefault(i.clave(), List.of()));
            if (v.esSeguimiento()) {
                it.put("anterior", anteriores.get(i.clave()));
                it.put("archivosAnteriores", archivosAnteriores.getOrDefault(i.clave(), List.of()));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lista = (List<Map<String, Object>>) c.get("items");
            lista.add(it);
        }
        m.put("componentes", comps.values());
        m.put("archivosVisita", archivos.getOrDefault("visita", List.of()));
        m.put("plan", plan(v, items, resp));
        List<Checklist.Item> reglas = items.stream().map(ItemVisita::regla).toList();
        Checklist.Resumen s = Checklist.resumen(reglas, resp);
        m.put("resumen", Map.of("c", s.c(), "nc", s.nc(), "na", s.na(), "pendientes", s.pendientes(), "hallazgos", s.hallazgos(),
                "cumplimiento", String.valueOf(Checklist.cumplimiento(s.c(), s.nc()))));
        m.put("faltantes", faltantesParaEnviar(v, reglas, resp));
        m.put("actas", jdbc.sql("""
                SELECT a.ronda, a.estado, a.generado, a.archivo_id AS "archivoId", cardinality(a.destinatarios) AS destinatarios, a.error
                FROM visitas_actas a WHERE a.visita_id = :v ORDER BY a.ronda""").param("v", visitaId).query().listOfRows());
        return m;
    }

    Map<String, Object> ejecucion(long visitaId) {
        return jdbc.sql("""
                SELECT inicio_real, fin_real, objeto, recomendaciones, observaciones_auditor, observaciones_esm, observaciones_opl
                FROM visitas_ejecucion WHERE visita_id = :v""").param("v", visitaId).query((rs, n) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("inicioReal", local(rs.getObject("inicio_real", OffsetDateTime.class)));
            e.put("finReal", local(rs.getObject("fin_real", OffsetDateTime.class)));
            e.put("objeto", rs.getString("objeto"));
            e.put("recomendaciones", rs.getString("recomendaciones"));
            e.put("observacionesAuditor", rs.getString("observaciones_auditor"));
            e.put("observacionesEsm", rs.getString("observaciones_esm"));
            e.put("observacionesOpl", rs.getString("observaciones_opl"));
            return e;
        }).optional().orElseGet(LinkedHashMap::new);
    }

    static String local(OffsetDateTime t) {
        return t == null ? null : FECHA_HORA.format(t.atZoneSameInstant(Fechas.BOGOTA));
    }

    Map<String, List<Map<String, Object>>> archivosPorClave(long visitaId) {
        Map<String, List<Map<String, Object>>> r = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT a.id, a.clave, a.nombre_original, a.tipo, a.tamano, a.sha256, u.nombre AS subido_por FROM archivos a
                LEFT JOIN usuarios u ON u.id = a.subido_por WHERE a.visita_id = :v AND a.eliminado IS NULL AND a.clave <> 'acta' ORDER BY a.id""")
                .param("v", visitaId).query((rs, n) -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("id", rs.getLong("id"));
                    x.put("nombre", rs.getString("nombre_original"));
                    x.put("tipo", rs.getString("tipo"));
                    x.put("tamano", rs.getLong("tamano"));
                    x.put("sha256", rs.getString("sha256"));
                    x.put("subidoPor", rs.getString("subido_por"));
                    r.computeIfAbsent(rs.getString("clave"), k -> new ArrayList<>()).add(x);
                    return null;
                }).list();
        return r;
    }

    /** Lo que impide enviar el acta (Anexo 5, prueba 10: fechas de inicio y fin, hallazgos, firmas). */
    List<String> faltantesParaEnviar(RepositorioVisitas.Visita v, List<Checklist.Item> items, Map<String, Checklist.Respuesta> resp) {
        List<String> f = new ArrayList<>(Checklist.faltantes(items, resp));
        Map<String, Object> e = ejecucion(v.id());
        if (e.get("inicioReal") == null || e.get("finReal") == null) {
            f.add("Registre la fecha y hora reales de inicio y fin de la visita.");
        }
        if (e.get("objeto") == null || String.valueOf(e.get("objeto")).isBlank()) {
            f.add("Registre el objeto de la visita.");
        }
        long personal = jdbc.sql("SELECT count(*) FROM visitas_personal WHERE visita_id = :v").param("v", v.id()).query(Long.class).single();
        if (personal == 0) {
            f.add("Registre al menos una persona del personal auditado.");
        }
        Set<String> firmas = new HashSet<>(jdbc.sql("SELECT rol FROM visitas_firmas WHERE visita_id = :v AND ronda = :r").param("v", v.id())
                .param("r", ronda(v)).query(String.class).list());
        if (!firmas.contains("auditor")) {
            f.add("Falta la firma del auditor líder.");
        }
        if (!firmas.contains("esm") && !firmas.contains("opl")) {
            f.add("Falta la firma de al menos una persona que atiende la visita (ESM u OPL).");
        }
        // Con hallazgos: el plan 5W2H con su fecha, el qué y la fecha fin de cada acción abierta, y sus dos firmas
        List<Map<String, Object>> hallazgos = hallazgos(v, items(v), resp);
        if (!hallazgos.isEmpty()) {
            Map<String, Map<String, Object>> filas = filasPlan(v.id());
            Object fecha = jdbc.sql("SELECT plan_fecha FROM visitas_ejecucion WHERE visita_id = :v").param("v", v.id()).query().listOfRows().stream()
                    .findFirst().map(x -> x.get("plan_fecha")).orElse(null);
            if (fecha == null) {
                f.add("Registre la fecha del plan de mejoramiento (5W2H).");
            }
            List<String> sinQue = new ArrayList<>();
            List<String> sinFin = new ArrayList<>();
            for (Map<String, Object> h : hallazgos) {
                Map<String, Object> fila = filas.get((String) h.get("clave"));
                String estado = fila == null ? "Pendiente" : (String) fila.get("estado");
                if (fila == null || fila.get("que") == null || String.valueOf(fila.get("que")).isBlank()) {
                    sinQue.add((String) h.get("codigo"));
                }
                if (ESTADOS_ABIERTOS.contains(estado) && (fila == null || fila.get("fin") == null)) {
                    sinFin.add((String) h.get("codigo"));
                }
            }
            if (!sinQue.isEmpty()) {
                f.add("Diligencie el qué del plan de los hallazgos: " + String.join(", ", sinQue) + ".");
            }
            if (!sinFin.isEmpty()) {
                f.add("Registre la fecha fin de las acciones abiertas: " + String.join(", ", sinFin) + ".");
            }
            if (!firmas.contains("w5_auditor")) {
                f.add("Falta la firma del auditor en el plan de mejoramiento.");
            }
            if (!firmas.contains("w5_auditado")) {
                f.add("Falta la firma de «recibí los hallazgos» del auditado en el plan de mejoramiento.");
            }
            // Prórroga en el seguimiento: la fecha fin de una acción abierta pasa a ser posterior a la anterior (decisión 6.4)
            Set<String> clavesHallazgo = hallazgos.stream().map(h -> (String) h.get("clave")).collect(Collectors.toSet());
            List<String> sinJustificar = seguimiento.prorrogas(v, filas, clavesHallazgo).stream()
                    .filter(x -> !co.com.agsamericas.digsa.reglas.Seguimiento.justificacionValida(x.justificacion())).map(ServicioSeguimiento.Prorroga::codigo).toList();
            if (!sinJustificar.isEmpty()) {
                f.add("Justifique la prórroga (mínimo " + co.com.agsamericas.digsa.reglas.Seguimiento.MIN_JUSTIFICACION + " caracteres) de: "
                        + String.join(", ", sinJustificar) + ".");
            }
        }
        return f;
    }

    // ---------- Escrituras ----------

    /** Califica ítems; cada integrante solo los de sus partes. Una respuesta sin calificación ni hallazgo se borra. */
    @Transactional
    public Map<String, Object> guardarRespuestas(long visitaId, List<Respuesta> entrada, UsuarioSesion quien, Set<Perfil> perfiles) {
        List<Respuesta> lista = entrada == null ? null : entrada.stream().filter(java.util.Objects::nonNull).toList();
        Acceso a = acceso(visitaId, quien, perfiles, true);
        exigir(a.escribible(), a.motivoSoloLectura() == null ? "No puede modificar esta visita." : a.motivoSoloLectura());
        if (lista == null || lista.isEmpty()) {
            throw ErrorNegocio.validacion("No hay respuestas para guardar.");
        }
        if (lista.size() > 400) {
            throw ErrorNegocio.validacion("Máximo 400 respuestas por guardado.");
        }
        Map<String, ItemVisita> porCodigo = items(a.visita()).stream().collect(Collectors.toMap(ItemVisita::codigo, i -> i));
        Map<String, Checklist.Respuesta> antes = respuestas(visitaId);
        Set<String> tramiteAntes = enTramite(visitaId);
        Set<String> inmediataAntes = inmediatas(visitaId);
        List<String> errores = new ArrayList<>();
        List<String> cambiados = new ArrayList<>();
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        for (Respuesta r : lista) {
            ItemVisita i = r.item() == null ? null : porCodigo.get(r.item());
            if (i == null) {
                errores.add((r.item() == null ? "(sin código)" : r.item()) + ": no es un ítem de esta visita");
                continue;
            }
            if (!a.escribeParte(i.parte())) {
                errores.add(i.codigo() + ": la parte " + i.parte() + " es de otro auditor");
                continue;
            }
            String cal = r.calificacion() == null || r.calificacion().isBlank() ? null : r.calificacion().strip().toUpperCase();
            String hallazgo = r.hallazgo() == null || r.hallazgo().isBlank() ? null : r.hallazgo().strip();
            Checklist.problema(i.regla(), new Checklist.Respuesta(cal, hallazgo)).ifPresent(errores::add);
            if (Boolean.TRUE.equals(r.enTramite()) && (i.obligacion() || !HABILITACION.equals(i.componente()) || !"NC".equals(cal))) {
                errores.add(i.codigo() + ": «habilitación en trámite» se marca solo en una validación de habilitación calificada «No cumple»");
            }
            if (Boolean.TRUE.equals(r.inmediata()) && !"NC".equals(cal)) {
                errores.add(i.codigo() + ": «intervención inmediata» se marca solo en un ítem calificado «No cumple»");
            }
        }
        if (!errores.isEmpty()) {
            throw ErrorNegocio.validacion("No se guardó: " + String.join("; ", errores.subList(0, Math.min(10, errores.size())))
                    + (errores.size() > 10 ? " y " + (errores.size() - 10) + " más" : "") + ".");
        }
        for (Respuesta r : lista) {
            ItemVisita i = porCodigo.get(r.item());
            String cal = r.calificacion() == null || r.calificacion().isBlank() ? null : r.calificacion().strip().toUpperCase();
            String hallazgo = r.hallazgo() == null || r.hallazgo().isBlank() ? null : r.hallazgo().strip();
            Checklist.Respuesta previa = antes.get(i.codigo());
            // Sin el campo se conserva la marca que tenía (un cliente que solo edita el hallazgo no la borra); solo vale en «No cumple»
            boolean tramite = (r.enTramite() == null ? tramiteAntes.contains(i.codigo()) : r.enTramite()) && "NC".equals(cal);
            boolean inmediata = (r.inmediata() == null ? inmediataAntes.contains(i.codigo()) : r.inmediata()) && "NC".equals(cal);
            boolean igual = (previa == null ? cal == null && hallazgo == null
                    : java.util.Objects.equals(previa.calificacion(), cal) && java.util.Objects.equals(previa.hallazgo(), hallazgo))
                    && tramite == tramiteAntes.contains(i.codigo()) && inmediata == inmediataAntes.contains(i.codigo());
            if (igual) {
                continue;
            }
            cambiados.add(i.codigo());
            if (i.obligacion()) {
                if (cal == null && hallazgo == null) {
                    jdbc.sql("DELETE FROM visitas_obligaciones WHERE visita_id = :v AND obligacion_id = :o").param("v", visitaId).param("o", i.id()).update();
                } else {
                    jdbc.sql("""
                            INSERT INTO visitas_obligaciones (visita_id, obligacion_id, calificacion, hallazgo, inmediata, usuario_id, actualizado)
                            VALUES (:v, :o, :c, :h, :inm, :u, :ahora)
                            ON CONFLICT (visita_id, obligacion_id) DO UPDATE SET calificacion = EXCLUDED.calificacion, hallazgo = EXCLUDED.hallazgo,
                              inmediata = EXCLUDED.inmediata, usuario_id = EXCLUDED.usuario_id, actualizado = EXCLUDED.actualizado""")
                            .param("v", visitaId).param("o", i.id()).param("c", cal).param("h", hallazgo).param("inm", inmediata).param("u", quien.id())
                            .param("ahora", ahora).update();
                }
            } else if (cal == null && hallazgo == null) {
                jdbc.sql("DELETE FROM visitas_respuestas WHERE visita_id = :v AND item_id = :i").param("v", visitaId).param("i", i.id()).update();
            } else {
                jdbc.sql("""
                        INSERT INTO visitas_respuestas (visita_id, item_id, parte, calificacion, hallazgo, en_tramite, inmediata, usuario_id, actualizado)
                        VALUES (:v, :i, :p, :c, :h, :t, :inm, :u, :ahora)
                        ON CONFLICT (visita_id, item_id) DO UPDATE SET calificacion = EXCLUDED.calificacion, hallazgo = EXCLUDED.hallazgo,
                          en_tramite = EXCLUDED.en_tramite, inmediata = EXCLUDED.inmediata, usuario_id = EXCLUDED.usuario_id, actualizado = EXCLUDED.actualizado""")
                        .param("v", visitaId).param("i", i.id()).param("p", i.parte()).param("c", cal).param("h", hallazgo).param("t", tramite).param("inm", inmediata)
                        .param("u", quien.id()).param("ahora", ahora).update();
            }
        }
        if (a.correccion() && !cambiados.isEmpty()) {
            visitas.historial(visitaId, quien.id(), quien.usuario(), "Corrección", "Ítems: " + String.join(", ", cambiados));
        }
        return Map.of("mensaje", cambiados.isEmpty() ? "Sin cambios." : "Guardado (" + cambiados.size() + " ítems).", "cambiados", cambiados);
    }

    /** Datos de la ejecución: los escribe el líder (Anexo 5, prueba 12: fecha y hora de inicio y fin, objeto). */
    @Transactional
    public void guardarEjecucion(long visitaId, Ejecucion e, UsuarioSesion quien, Set<Perfil> perfiles) {
        Acceso a = acceso(visitaId, quien, perfiles, true);
        exigir(a.escribeVisita(), a.escribible() ? "Los datos de la visita los registra el auditor líder." : a.motivoSoloLectura());
        OffsetDateTime inicio = fechaHora(e.inicioReal(), "inicio");
        OffsetDateTime fin = fechaHora(e.finReal(), "fin");
        if (inicio != null && fin != null && fin.isBefore(inicio)) {
            throw ErrorNegocio.validacion("La fecha y hora de fin no puede ser anterior a la de inicio.");
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        if (inicio != null && inicio.isAfter(ahora.plusMinutes(5)) || fin != null && fin.isAfter(ahora.plusMinutes(5))) {
            throw ErrorNegocio.validacion("El inicio y el fin reales no pueden ser posteriores a este momento.");
        }
        String objeto = texto(e.objeto(), 2000, "El objeto");
        Map<String, Object> antes = ejecucion(visitaId);
        jdbc.sql("""
                INSERT INTO visitas_ejecucion (visita_id, inicio_real, fin_real, objeto, recomendaciones, observaciones_auditor, observaciones_esm,
                  observaciones_opl, actualizado, actualizado_por)
                VALUES (:v, :i, :f, :o, :r, :oa, :oe, :oo, :ahora, :u)
                ON CONFLICT (visita_id) DO UPDATE SET inicio_real = EXCLUDED.inicio_real, fin_real = EXCLUDED.fin_real, objeto = EXCLUDED.objeto,
                  recomendaciones = EXCLUDED.recomendaciones, observaciones_auditor = EXCLUDED.observaciones_auditor,
                  observaciones_esm = EXCLUDED.observaciones_esm, observaciones_opl = EXCLUDED.observaciones_opl,
                  actualizado = EXCLUDED.actualizado, actualizado_por = EXCLUDED.actualizado_por""")
                .param("v", visitaId).param("i", inicio).param("f", fin).param("o", objeto)
                .param("r", texto(e.recomendaciones(), 4000, "Las recomendaciones")).param("oa", texto(e.observacionesAuditor(), 4000, "Las observaciones"))
                .param("oe", texto(e.observacionesEsm(), 4000, "Las observaciones del ESM"))
                .param("oo", texto(e.observacionesOpl(), 4000, "Las observaciones del OPL")).param("ahora", ahora).param("u", quien.id()).update();
        if (a.correccion()) {
            Map<String, Object> despues = ejecucion(visitaId);
            List<String> campos = despues.keySet().stream().filter(k -> !java.util.Objects.equals(antes.get(k), despues.get(k))).toList();
            if (!campos.isEmpty()) {
                visitas.historial(visitaId, quien.id(), quien.usuario(), "Corrección", "Datos de la visita: " + String.join(", ", campos));
            }
        }
    }

    static OffsetDateTime fechaHora(String texto, String cual) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(texto.strip().length() > 16 ? texto.strip().substring(0, 16) : texto.strip(), FECHA_HORA)
                    .atZone(Fechas.BOGOTA).toOffsetDateTime();
        } catch (DateTimeParseException ex) {
            throw ErrorNegocio.validacion("La fecha y hora de " + cual + " no es válida (AAAA-MM-DD HH:MM).");
        }
    }

    static String texto(String s, int max, String campo) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        if (t.length() > max) {
            throw ErrorNegocio.validacion(campo + " tiene máximo " + max + " caracteres.");
        }
        return t;
    }

    /** Personal auditado: lo registra el líder; la lista reemplaza a la anterior. */
    @Transactional
    public void guardarPersonal(long visitaId, List<Persona> personas, UsuarioSesion quien, Set<Perfil> perfiles) {
        Acceso a = acceso(visitaId, quien, perfiles, true);
        exigir(a.escribeVisita(), a.escribible() ? "El personal auditado lo registra el auditor líder." : a.motivoSoloLectura());
        List<Persona> lista = personas == null ? List.of() : personas.stream().filter(java.util.Objects::nonNull).toList();
        if (lista.size() > 30) {
            throw ErrorNegocio.validacion("Máximo 30 personas.");
        }
        List<Persona> validas = new ArrayList<>();
        for (Persona p : lista) {
            String nombre = texto(p.nombre(), 150, "El nombre");
            if (nombre == null || nombre.length() < 3) {
                throw ErrorNegocio.validacion("Escriba el nombre completo de cada persona (mínimo 3 caracteres).");
            }
            String entidad = p.entidad() == null ? "ESM" : p.entidad().strip().toUpperCase();
            if (!Set.of("ESM", "OPL", "FA", "OTRO").contains(entidad)) {
                throw ErrorNegocio.validacion("La entidad debe ser ESM, OPL, FA u otro.");
            }
            String correo = p.correo() == null || p.correo().isBlank() ? null : Correos.normalizar(p.correo().strip());
            if (correo != null && !Correos.valido(correo)) {
                throw ErrorNegocio.validacion("El correo de " + nombre + " no es válido.");
            }
            validas.add(new Persona("OTRO".equals(entidad) ? "otro" : entidad, nombre, texto(p.cargo(), 120, "El cargo"), texto(p.documento(), 30, "El documento"),
                    correo));
        }
        jdbc.sql("DELETE FROM visitas_personal WHERE visita_id = :v").param("v", visitaId).update();
        int orden = 0;
        for (Persona p : validas) {
            jdbc.sql("INSERT INTO visitas_personal (visita_id, entidad, nombre, cargo, documento, correo, orden) VALUES (:v, :e, :n, :c, :d, :co, :o)")
                    .param("v", visitaId).param("e", p.entidad()).param("n", p.nombre()).param("c", p.cargo()).param("d", p.documento())
                    .param("co", p.correo()).param("o", orden++).update();
        }
        if (a.correccion()) {
            visitas.historial(visitaId, quien.id(), quien.usuario(), "Corrección", "Personal auditado (" + validas.size() + " personas)");
        }
    }

    /** Firma del acta dibujada en la pantalla (PNG en base64). El líder registra la suya y la de quienes atienden. */
    @Transactional
    public void firmar(long visitaId, Firma f, UsuarioSesion quien, Set<Perfil> perfiles) {
        Acceso a = acceso(visitaId, quien, perfiles, true);
        exigir(a.escribeVisita() && !a.correccion(), a.correccion() ? "El coordinador no cambia las firmas del acta."
                : a.escribible() ? "Las firmas las registra el auditor líder." : a.motivoSoloLectura());
        String rol = f.rol() == null ? "" : f.rol().strip().toLowerCase();
        if (!Set.of("auditor", "esm", "opl", "w5_auditor", "w5_auditado").contains(rol)) {
            throw ErrorNegocio.validacion("La firma debe ser del auditor, del ESM, del OPL o del plan de mejoramiento (auditor y auditado).");
        }
        String nombre = texto(f.nombre(), 150, "El nombre");
        if (nombre == null || nombre.length() < 3) {
            throw ErrorNegocio.validacion("Escriba el nombre de quien firma.");
        }
        byte[] png = png(f.imagen());
        jdbc.sql("""
                INSERT INTO visitas_firmas (visita_id, ronda, rol, nombre, documento, cargo, imagen, firmado) VALUES (:v, :ronda, :r, :n, :d, :c, :i, :ahora)
                ON CONFLICT (visita_id, ronda, rol) DO UPDATE SET nombre = EXCLUDED.nombre, documento = EXCLUDED.documento, cargo = EXCLUDED.cargo,
                  imagen = EXCLUDED.imagen, firmado = EXCLUDED.firmado""")
                .param("v", visitaId).param("ronda", ronda(a.visita())).param("r", rol).param("n", nombre).param("d", texto(f.documento(), 30, "El documento"))
                .param("c", texto(f.cargo(), 120, "El cargo")).param("i", png).param("ahora", OffsetDateTime.now(reloj)).update();
        visitas.historial(visitaId, quien.id(), quien.usuario(), "Firma registrada", "Firma de " + ("auditor".equals(rol) ? "el auditor líder" : "el " + rol.toUpperCase()));
    }

    // ---------- Hallazgos y plan 5W2H (parte del auditor) ----------

    /**
     * Hallazgos de la visita: los ítems de los componentes y las obligaciones de la minuta calificados «No cumple»
     * (docs/especificaciones/flujo-opl: «Hallazgos de la visita al cerrarla»).
     */
    List<Map<String, Object>> hallazgos(RepositorioVisitas.Visita v, List<ItemVisita> items, Map<String, Checklist.Respuesta> resp) {
        Set<String> tramite = enTramite(v.id());
        Set<String> inmediatas = inmediatas(v.id());
        List<Map<String, Object>> r = new ArrayList<>();
        for (ItemVisita i : items) {
            Checklist.Respuesta x = resp.get(i.codigo());
            if (x != null && "NC".equals(x.calificacion())) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("clave", i.clave());
                h.put("codigo", i.codigo());
                h.put("origen", i.obligacion() ? "obligacion" : "item");
                h.put("texto", i.punto());
                h.put("hallazgo", x.hallazgo());
                h.put("enTramite", tramite.contains(i.codigo()));
                h.put("inmediata", inmediatas.contains(i.codigo()));
                r.add(h);
            }
        }
        return r;
    }

    Map<String, Map<String, Object>> filasPlan(long visitaId) {
        Map<String, Map<String, Object>> r = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT clave, causas, que, porque, donde, inicio, fin, indicador, estado, avance, quien, como, prorroga_justificacion
                FROM visitas_plan WHERE visita_id = :v ORDER BY clave""")
                .param("v", visitaId).query().listOfRows().forEach(f -> {
                    Map<String, Object> m = new LinkedHashMap<>(f);
                    m.put("inicio", f.get("inicio") == null ? null : f.get("inicio").toString());
                    m.put("fin", f.get("fin") == null ? null : f.get("fin").toString());
                    r.put((String) f.get("clave"), m);
                });
        return r;
    }

    /** El plan para la pantalla: cada hallazgo con su fila (vacía si aún no se diligencia), la fecha y el texto de ayuda del indicador. */
    Map<String, Object> plan(RepositorioVisitas.Visita v, List<ItemVisita> items, Map<String, Checklist.Respuesta> resp) {
        Map<String, Map<String, Object>> filas = filasPlan(v.id());
        Map<String, Map<String, Object>> anteriores = seguimiento.anteriores(v);
        Map<String, Map<String, Object>> deOpl = seguimiento.quienComoOpl(v);
        List<Map<String, Object>> lista = new ArrayList<>();
        // En el seguimiento el plan parte del anterior: se ven también los hallazgos por verificar que aún no se recalifican
        List<Map<String, Object>> base = hallazgos(v, items, resp);
        if (v.esSeguimiento()) {
            base = new ArrayList<>(base);
            for (ItemVisita i : items) {
                Checklist.Respuesta r = resp.get(i.codigo());
                if (r == null || r.calificacion() == null) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("clave", i.clave());
                    h.put("codigo", i.codigo());
                    h.put("origen", i.obligacion() ? "obligacion" : "item");
                    h.put("texto", i.punto());
                    h.put("hallazgo", null);
                    h.put("sinCalificar", true);
                    base.add(h);
                }
            }
        }
        for (Map<String, Object> h : base) {
            Map<String, Object> x = new LinkedHashMap<>(h);
            Map<String, Object> fila = new LinkedHashMap<>(filas.getOrDefault((String) h.get("clave"), Map.of("estado", "Pendiente", "avance", 0)));
            Map<String, Object> opl = deOpl.get((String) h.get("clave"));
            if (opl != null) {
                // Quién y cómo del OPL en vivo (responde después del cierre de la visita de origen)
                fila.putIfAbsent("quien", null);
                fila.putIfAbsent("como", null);
                if (fila.get("quien") == null) {
                    fila.put("quien", opl.get("quien"));
                }
                if (fila.get("como") == null) {
                    fila.put("como", opl.get("como"));
                }
            }
            x.put("fila", fila);
            Map<String, Object> a = anteriores.get((String) h.get("clave"));
            x.put("finAnterior", a == null ? null : a.get("fin"));
            lista.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fecha", jdbc.sql("SELECT plan_fecha FROM visitas_ejecucion WHERE visita_id = :v").param("v", v.id()).query().listOfRows().stream().findFirst()
                .map(x -> x.get("plan_fecha") == null ? null : x.get("plan_fecha").toString()).orElse(null));
        m.put("hallazgos", lista);
        m.put("estados", List.of("Pendiente", "En curso", "Completado", "Retrasado", "Cancelado"));
        m.put("ayudaIndicador", "Cómo se mide el cumplimiento de la acción: fórmula o evidencia verificable (Anexo 3 p.11 v).");
        return m;
    }

    /** Parte del auditor del plan 5W2H: la registra el líder (o quien escribe todo). Todo o nada. */
    @Transactional
    public Map<String, Object> guardarPlan(long visitaId, Plan p, UsuarioSesion quien, Set<Perfil> perfiles) {
        Acceso a = acceso(visitaId, quien, perfiles, true);
        exigir(a.escribeVisita(), a.escribible() ? "El plan de mejoramiento lo diligencia el auditor líder." : a.motivoSoloLectura());
        Set<String> claves = new java.util.HashSet<>();
        hallazgos(a.visita(), items(a.visita()), respuestas(visitaId)).forEach(h -> claves.add((String) h.get("clave")));
        if (a.visita().esSeguimiento()) {
            items(a.visita()).forEach(i -> claves.add(i.clave())); // el plan del seguimiento se diligencia también antes de recalificar
        }
        java.time.LocalDate fecha = p.fecha() == null || p.fecha().isBlank() ? null
                : Fechas.valida(p.fecha()).orElseThrow(() -> ErrorNegocio.validacion("La fecha del plan no es válida."));
        List<FilaPlan> filas = p.filas() == null ? List.of() : p.filas().stream().filter(java.util.Objects::nonNull).toList();
        List<String> errores = new ArrayList<>();
        for (FilaPlan f : filas) {
            if (f.clave() == null || !claves.contains(f.clave())) {
                errores.add((f.clave() == null ? "(sin clave)" : f.clave()) + ": no es un hallazgo de esta visita");
                continue;
            }
            String estado = f.estado() == null || f.estado().isBlank() ? "Pendiente" : f.estado();
            if (!ESTADOS_PLAN.contains(estado)) {
                errores.add(f.clave() + ": estado no válido");
            }
            if (f.avance() != null && (f.avance() < 0 || f.avance() > 100)) {
                errores.add(f.clave() + ": el avance va de 0 a 100");
            }
            java.time.LocalDate ini = fechaOpcional(f.inicio(), f.clave() + ": inicio", errores);
            java.time.LocalDate fin = fechaOpcional(f.fin(), f.clave() + ": fin", errores);
            if (ini != null && fin != null && fin.isBefore(ini)) {
                errores.add(f.clave() + ": la fecha fin no puede ser anterior al inicio");
            }
            largo(errores, f.causas(), 2000, f.clave() + ": causas");
            largo(errores, f.que(), 2000, f.clave() + ": qué");
            largo(errores, f.porque(), 2000, f.clave() + ": por qué");
            largo(errores, f.donde(), 500, f.clave() + ": dónde");
            largo(errores, f.indicador(), 1000, f.clave() + ": indicador");
            largo(errores, f.prorroga(), 2000, f.clave() + ": justificación de la prórroga");
        }
        if (!errores.isEmpty()) {
            throw ErrorNegocio.validacion("No se guardó el plan: " + String.join("; ", errores.subList(0, Math.min(10, errores.size()))) + ".");
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        jdbc.sql("""
                INSERT INTO visitas_ejecucion (visita_id, plan_fecha, actualizado, actualizado_por) VALUES (:v, :f, :ahora, :u)
                ON CONFLICT (visita_id) DO UPDATE SET plan_fecha = EXCLUDED.plan_fecha, actualizado = EXCLUDED.actualizado,
                  actualizado_por = EXCLUDED.actualizado_por""").param("v", visitaId).param("f", fecha).param("ahora", ahora).param("u", quien.id()).update();
        for (FilaPlan f : filas) {
            jdbc.sql("""
                    INSERT INTO visitas_plan (visita_id, clave, causas, que, porque, donde, inicio, fin, indicador, estado, avance, prorroga_justificacion,
                      usuario_id, actualizado)
                    VALUES (:v, :c, :causas, :que, :porque, :donde, :ini, :fin, :ind, :estado, :avance, :prorroga, :u, :ahora)
                    ON CONFLICT (visita_id, clave) DO UPDATE SET causas = EXCLUDED.causas, que = EXCLUDED.que, porque = EXCLUDED.porque,
                      donde = EXCLUDED.donde, inicio = EXCLUDED.inicio, fin = EXCLUDED.fin, indicador = EXCLUDED.indicador, estado = EXCLUDED.estado,
                      avance = EXCLUDED.avance, prorroga_justificacion = EXCLUDED.prorroga_justificacion, usuario_id = EXCLUDED.usuario_id,
                      actualizado = EXCLUDED.actualizado""")
                    .param("v", visitaId).param("c", f.clave()).param("causas", texto(f.causas(), 2000, "Causas")).param("que", texto(f.que(), 2000, "Qué"))
                    .param("porque", texto(f.porque(), 2000, "Por qué")).param("donde", texto(f.donde(), 500, "Dónde"))
                    .param("ini", f.inicio() == null || f.inicio().isBlank() ? null : Fechas.valida(f.inicio()).orElse(null))
                    .param("fin", f.fin() == null || f.fin().isBlank() ? null : Fechas.valida(f.fin()).orElse(null))
                    .param("ind", texto(f.indicador(), 1000, "Indicador")).param("estado", f.estado() == null || f.estado().isBlank() ? "Pendiente" : f.estado())
                    .param("avance", f.avance() == null ? 0 : f.avance()).param("prorroga", texto(f.prorroga(), 2000, "La justificación de la prórroga"))
                    .param("u", quien.id()).param("ahora", ahora).update();
        }
        if (a.correccion()) {
            visitas.historial(visitaId, quien.id(), quien.usuario(), "Corrección", "Plan de mejoramiento (" + filas.size() + " filas)");
        }
        return Map.of("mensaje", "Plan de mejoramiento guardado.");
    }

    private static java.time.LocalDate fechaOpcional(String s, String campo, List<String> errores) {
        if (s == null || s.isBlank()) {
            return null;
        }
        java.util.Optional<java.time.LocalDate> d = Fechas.valida(s);
        if (d.isEmpty()) {
            errores.add(campo + " no es una fecha válida");
        }
        return d.orElse(null);
    }

    private static void largo(List<String> errores, String s, int max, String campo) {
        if (s != null && s.strip().length() > max) {
            errores.add(campo + " tiene máximo " + max + " caracteres");
        }
    }

    public static byte[] png(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw ErrorNegocio.validacion("Dibuje la firma.");
        }
        String datos = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        byte[] b;
        try {
            b = java.util.Base64.getDecoder().decode(datos.strip());
        } catch (IllegalArgumentException e) {
            throw ErrorNegocio.validacion("La firma no es una imagen válida.");
        }
        boolean esPng = b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        if (!esPng || b.length < 100 || b.length > 300_000) {
            throw ErrorNegocio.validacion("La firma debe ser una imagen PNG de máximo 300 KB.");
        }
        // Se decodifica de verdad (con tope de tamaño antes de decodificar) y se guarda recodificada: una firma corrupta no llega
        // al acta legal y un PNG que declara millones de píxeles no agota la memoria al generar el PDF
        int[] dim = co.com.agsamericas.digsa.plataforma.ValidadorArchivos.dimensiones(b);
        if (dim == null || dim[0] < 20 || dim[1] < 10 || dim[0] > 2000 || dim[1] > 1000) {
            throw ErrorNegocio.validacion("La firma no es una imagen válida (máximo 2000 × 1000 píxeles).");
        }
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
            if (img == null) {
                throw ErrorNegocio.validacion("La firma no es una imagen válida.");
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException | RuntimeException e) {
            if (e instanceof ErrorNegocio en) {
                throw en;
            }
            throw ErrorNegocio.validacion("La firma no es una imagen válida.");
        }
    }
}
