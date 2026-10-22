package co.com.agsamericas.digsa.ejecucion;

import co.com.agsamericas.digsa.agenda.RepositorioVisitas;
import co.com.agsamericas.digsa.agenda.ServicioNotificacion;
import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Checklist;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.EstadosVisita;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Plazos;
import co.com.agsamericas.digsa.reglas.Reparto;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acta de la visita (Anexo 5, prueba 10): la genera y envía el auditor líder cuando todo está calificado y firmado; al salir, la visita
 * se cierra. El coordinador reabre una visita cerrada con motivo; el acta siguiente es de otra ronda y la anterior se conserva.
 */
@Service
public class ServicioActa {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public record Envio(boolean ok, String mensaje, Integer ronda) {
    }

    private final ServicioFormulario formulario;
    private final ServicioArchivos archivos;
    private final ServicioNotificacion notificacion;
    private final ServicioCatalogos catalogos;
    private final RepositorioVisitas visitas;
    private final Correo correo;
    private final RegistroBitacora bitacora;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock reloj;
    private final co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda consulta;
    private final co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa cfg;
    private final ServicioSeguimiento seguimiento;

    public ServicioActa(ServicioFormulario formulario, ServicioArchivos archivos, ServicioNotificacion notificacion, ServicioCatalogos catalogos,
                        RepositorioVisitas visitas, Correo correo, RegistroBitacora bitacora, JdbcClient jdbc, TransactionTemplate tx, Clock reloj,
                        co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda consulta, co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa cfg,
                        ServicioSeguimiento seguimiento) {
        this.seguimiento = seguimiento;
        this.consulta = consulta;
        this.cfg = cfg;
        this.formulario = formulario;
        this.archivos = archivos;
        this.notificacion = notificacion;
        this.catalogos = catalogos;
        this.visitas = visitas;
        this.correo = correo;
        this.bitacora = bitacora;
        this.jdbc = jdbc;
        this.tx = tx;
        this.reloj = reloj;
    }

    /** Genera el acta, la envía y cierra la visita. Si el correo no sale, la visita sigue en ejecución y se puede reintentar. */
    public Envio enviar(long visitaId, UsuarioSesion quien, Set<Perfil> perfiles) {
        record Preparada(long actaId, int ronda, String numero, List<String> para, Set<String> obligatorios, String asunto, byte[] pdf, String nombre,
                         EnlaceOpl enlace, ServicioSeguimiento.Programacion programacion, List<ServicioSeguimiento.Prorroga> prorrogas) {
        }
        Preparada p = tx.execute(t -> {
            Acceso a = formulario.acceso(visitaId, quien, perfiles, true);
            if (!a.escribible()) {
                throw new ErrorNegocio(HttpStatus.FORBIDDEN, a.motivoSoloLectura());
            }
            if (!a.lider() && !(a.todo() && !a.correccion())) {
                throw new ErrorNegocio(HttpStatus.FORBIDDEN, "El acta la genera y envía el auditor líder.");
            }
            RepositorioVisitas.Visita v = a.visita();
            int ronda = v.reaperturas() + 1;
            OffsetDateTime hace10 = OffsetDateTime.now(reloj).minus(Duration.ofMinutes(10));
            long enEnvio = jdbc.sql("SELECT count(*) FROM visitas_actas WHERE visita_id = :v AND ronda = :r AND estado = 'pendiente' AND generado > :h")
                    .param("v", visitaId).param("r", ronda).param("h", hace10).query(Long.class).single();
            if (enEnvio > 0) {
                throw new ErrorNegocio(HttpStatus.LOCKED, "El acta se está enviando: espere a que termine.");
            }
            List<ServicioFormulario.ItemVisita> items = formulario.items(v);
            Map<String, Checklist.Respuesta> resp = formulario.respuestas(visitaId);
            List<Checklist.Item> reglas = items.stream().map(ServicioFormulario.ItemVisita::regla).toList();
            List<String> faltan = formulario.faltantesParaEnviar(v, reglas, resp);
            if (!faltan.isEmpty()) {
                throw ErrorNegocio.validacion("Antes de enviar el acta: " + String.join(" ", faltan));
            }
            ServicioNotificacion.Calculada dest = notificacion.calcular(v, null);
            if (!dest.faltan().isEmpty()) {
                throw ErrorNegocio.validacion("Faltan los correos de " + String.join(", ", dest.faltan()) + " para enviar el acta.");
            }
            Set<String> obligatorios = new HashSet<>();
            Set<String> para = new LinkedHashSet<>();
            for (ServicioNotificacion.Destinatario d : dest.destinatarios()) {
                para.add(d.correo());
                if (d.obligatorio()) {
                    obligatorios.add(d.correo().toLowerCase(Locale.ROOT));
                }
            }
            // La DIGSA recibe todas las actas (decisiones 9 y 24); el equipo y el personal auditado con correo, también
            for (String c : catalogos.textoParametro("correo_digsa", "").split("[,;\\s]+")) {
                if (Correos.valido(Correos.normalizar(c))) {
                    para.add(Correos.normalizar(c));
                }
            }
            visitas.equipo(visitaId).forEach(m -> para.add(m.correo()));
            jdbc.sql("SELECT correo FROM visitas_personal WHERE visita_id = :v AND correo IS NOT NULL").param("v", visitaId).query(String.class).list()
                    .forEach(para::add);
            List<String> lista = para.stream().filter(x -> x != null && !x.isBlank()).distinct().limit(50).toList();

            Checklist.Resumen s = Checklist.resumen(reglas, resp);
            guardarResumen(visitaId, s);
            // Con hallazgos: enlace de respuesta del OPL con sus plazos (decisiones 25 y 8; 11.5 sin firma tardía)
            List<Map<String, Object>> hallazgos = formulario.hallazgos(v, items, resp);
            EnlaceOpl enlace = hallazgos.isEmpty() ? null : EnlaceOpl.nuevo(consulta.plazos(), OffsetDateTime.now(reloj), cfg::enlace);
            // Seguimiento (decisión 30): se decide antes del acta, que lo dice; se crea al cerrar. Prórrogas del seguimiento (12.1)
            Map<String, Map<String, Object>> filasPlan = formulario.filasPlan(v.id());
            ServicioSeguimiento.Programacion programacion = seguimiento.programacion(v, filasPlan, hallazgos);
            seguimiento.fijarQuienComo(v);
            List<ServicioSeguimiento.Prorroga> prorrogas = seguimiento.prorrogas(v, filasPlan,
                    hallazgos.stream().map(h -> (String) h.get("clave")).collect(java.util.stream.Collectors.toSet()));
            byte[] pdf = GeneradorActa.generar(contenido(v, ronda, items, resp, s, enlace, programacion, prorrogas));
            String nombre = "Acta_" + v.numero() + "_ronda" + ronda + ".pdf";
            ServicioArchivos.Archivo archivo = archivos.guardarGenerado(visitaId, "acta", nombre, pdf, quien);
            Long existente = jdbc.sql("SELECT id FROM visitas_actas WHERE visita_id = :v AND ronda = :r").param("v", visitaId).param("r", ronda)
                    .query(Long.class).optional().orElse(null);
            long actaId;
            OffsetDateTime ahora = OffsetDateTime.now(reloj);
            if (existente == null) {
                actaId = jdbc.sql("""
                        INSERT INTO visitas_actas (visita_id, ronda, archivo_id, destinatarios, generado_por, generado)
                        VALUES (:v, :r, :a, :d, :u, :ahora) RETURNING id""")
                        .param("v", visitaId).param("r", ronda).param("a", archivo.id()).param("d", lista.toArray(String[]::new))
                        .param("u", quien.id()).param("ahora", ahora).query(Long.class).single();
            } else {
                // Reintento después de un envío fallido: el acta se genera de nuevo con los datos actuales
                actaId = existente;
                jdbc.sql("""
                        UPDATE visitas_actas SET archivo_id = :a, destinatarios = :d, estado = 'pendiente', error = NULL, generado_por = :u,
                          generado = :ahora, resultado = NULL WHERE id = :id""")
                        .param("a", archivo.id()).param("d", lista.toArray(String[]::new)).param("u", quien.id()).param("ahora", ahora).param("id", actaId)
                        .update();
            }
            guardarHallazgosYEnlace(v, actaId, hallazgos, enlace);
            Map<String, Object> segDatos = seguimiento.datos(v);
            String asunto = (v.esSeguimiento() ? "Acta de seguimiento a plan de mejoramiento " : "Acta de visita ") + v.numero() + " · " + v.puntoNombre()
                    + (segDatos == null ? "" : " · " + segDatos.get("ordinal") + " de la visita " + segDatos.get("origenNumero"))
                    + (ronda > 1 ? " · ronda " + ronda : "");
            return new Preparada(actaId, ronda, v.numero(), lista, obligatorios, asunto, pdf, nombre, enlace, programacion, prorrogas);
        });

        Correo.Resultado r = correo.enviar(new Correo.Mensaje(p.para(), List.of(), p.asunto(),
                PlantillaCorreo.marco(p.asunto().startsWith("Acta de seguimiento") ? "Acta de seguimiento a plan de mejoramiento" : "Acta de visita",
                        "<p>Adjuntamos el acta de la visita <strong>" + PlantillaCorreo.e(p.numero())
                        + "</strong>" + (p.ronda() > 1 ? " (ronda " + p.ronda() + ", visita reabierta)" : "") + ".</p>"
                        + (p.enlace() == null ? "<p>La visita no tiene hallazgos.</p>"
                                : "<p style=\"border:2px solid #961414;padding:10px;color:#961414\"><strong>" + PlantillaCorreo.e(p.enlace().textoPlazos())
                                        + "</strong></p><p>Responda los hallazgos y firme el plan en: <a href=\"" + PlantillaCorreo.e(p.enlace().url()) + "\">"
                                        + PlantillaCorreo.e(p.enlace().url()) + "</a></p>")
                        + "<p style=\"color:#6b7280;font-size:11px\">Huella SHA-256 del acta: "
                        + co.com.agsamericas.digsa.reglas.Huellas.sha256(p.pdf()) + "</p>"),
                List.of(new Correo.Adjunto(p.nombre(), "application/pdf", p.pdf()))));
        List<String> rechazados = r.ok() ? r.rechazados() : p.para();
        long obligatoriosRechazados = rechazados.stream().filter(x -> p.obligatorios().contains(x.toLowerCase(Locale.ROOT))).count();
        boolean completa = r.ok() && obligatoriosRechazados == 0;
        String estado = !r.ok() ? "fallida" : rechazados.isEmpty() ? "enviada" : completa ? "parcial" : "fallida";
        String error = !r.ok() ? ServicioNotificacionTexto.corto(Bitacora.sinCorreos(r.error() == null ? "error desconocido" : r.error()), 500)
                : rechazados.isEmpty() ? null : "Rechazados " + rechazados.size() + " de " + p.para().size() + " destinatarios";
        ServicioSeguimiento.Resultado[] seg = new ServicioSeguimiento.Resultado[1];
        boolean cerrada = Boolean.TRUE.equals(tx.execute(t -> {
            visitas.bloquear(visitaId); // mismo orden de bloqueo que el resto: primero la visita
            jdbc.sql("UPDATE visitas_actas SET estado = :e, error = :err, resultado = :ahora WHERE id = :id").param("e", estado).param("err", error)
                    .param("ahora", OffsetDateTime.now(reloj)).param("id", p.actaId()).update();
            if (!completa) {
                visitas.historial(visitaId, quien.id(), quien.usuario(), "Envío del acta fallido", "Ronda " + p.ronda() + ". " + error);
                return false;
            }
            boolean ok = visitas.cambiarEstado(visitaId, Set.of("ejecucion"), "cerrada");
            visitas.historial(visitaId, quien.id(), quien.usuario(), ok ? "Cerrada" : "Acta enviada",
                    "Acta de la ronda " + p.ronda() + " enviada a " + p.para().size() + " destinatarios" + (rechazados.isEmpty() ? "" : "; rechazados: " + rechazados.size()));
            if (ok) {
                // En la misma transacción del cierre: la visita de seguimiento o el historial del máximo
                RepositorioVisitas.Visita cerradaV = visitas.porId(visitaId).orElseThrow();
                seguimiento.registrarProrrogas(cerradaV, p.actaId(), p.prorrogas()); // solo cuando el acta salió (decisión 12.1)
                seg[0] = seguimiento.alCerrar(cerradaV, p.programacion(), quien);
            }
            return ok;
        }));
        if (completa && cerrada) {
            bitacora.registrar("visita_cerrada", "Visita " + p.numero() + ": acta de la ronda " + p.ronda() + " enviada y visita cerrada", quien.id(),
                    quien.usuario());
            List<String> avisos = seg[0] == null ? List.of() : seguimiento.despuesDelCierre(visitas.porId(visitaId).orElseThrow(), p.actaId(), seg[0], quien);
            return new Envio(true, "Acta enviada a " + p.para().size() + " destinatarios y visita cerrada."
                    + (rechazados.isEmpty() ? "" : " " + rechazados.size() + " destinatarios no obligatorios la rechazaron.")
                    + (avisos.isEmpty() ? "" : " " + String.join(" ", avisos)), p.ronda());
        }
        if (completa) {
            // El acta salió, pero la visita cambió de estado mientras se enviaba (p. ej. la cancelaron): no se marca como cerrada
            bitacora.registrar("visita_notificacion_fallida", "Visita " + p.numero() + ": el acta salió pero la visita no se cerró (cambió de estado)",
                    quien.id(), quien.usuario());
            return new Envio(false, "El acta salió, pero la visita cambió de estado mientras se enviaba y no se cerró. Revise el historial de la visita.",
                    p.ronda());
        }
        bitacora.registrar("visita_notificacion_fallida", "Visita " + p.numero() + ": el acta de la ronda " + p.ronda() + " no salió", quien.id(), quien.usuario());
        return new Envio(false, "El acta se generó pero no llegó a los destinatarios obligatorios (" + error + "). La visita sigue en ejecución: "
                + "intente enviarla de nuevo cuando el correo funcione.", p.ronda());
    }

    private void guardarResumen(long visitaId, Checklist.Resumen s) {
        StringBuilder json = new StringBuilder("{");
        s.porComponente().forEach((k, v) -> json.append(json.length() > 1 ? "," : "").append('"').append(k).append("\":{\"c\":").append(v[0])
                .append(",\"nc\":").append(v[1]).append(",\"na\":").append(v[2]).append(",\"pendientes\":").append(v[3]).append('}'));
        json.append('}');
        jdbc.sql("""
                INSERT INTO visitas_resumen (visita_id, c, nc, na, pendientes, hallazgos, por_componente, calculado)
                VALUES (:v, :c, :nc, :na, :p, :h, CAST(:j AS jsonb), :ahora)
                ON CONFLICT (visita_id) DO UPDATE SET c = EXCLUDED.c, nc = EXCLUDED.nc, na = EXCLUDED.na, pendientes = EXCLUDED.pendientes,
                  hallazgos = EXCLUDED.hallazgos, por_componente = EXCLUDED.por_componente, calculado = EXCLUDED.calculado""")
                .param("v", visitaId).param("c", s.c()).param("nc", s.nc()).param("na", s.na()).param("p", s.pendientes()).param("h", s.hallazgos())
                .param("j", json.toString()).param("ahora", OffsetDateTime.now(reloj)).update();
    }

    /**
     * Hallazgos de esta acta (reemplazan los de un intento anterior de la misma ronda), enlace del OPL (uno por visita: se reemplaza con
     * el acta nueva; sin hallazgos se quita) y el plan del OPL de esta acta en «pendiente».
     */
    private void guardarHallazgosYEnlace(RepositorioVisitas.Visita v, long actaId, List<Map<String, Object>> hallazgos, EnlaceOpl enlace) {
        jdbc.sql("DELETE FROM visitas_hallazgos WHERE acta_id = :a").param("a", actaId).update();
        Map<String, Map<String, Object>> filas = formulario.filasPlan(v.id());
        for (Map<String, Object> h : hallazgos) {
            Map<String, Object> fila = filas.get((String) h.get("clave"));
            jdbc.sql("""
                    INSERT INTO visitas_hallazgos (visita_id, acta_id, clave, codigo, origen, texto, hallazgo, fin, estado_plan, en_tramite)
                    VALUES (:v, :a, :c, :cod, :o, :t, :h, :fin, :e, :tram)""")
                    .param("tram", Boolean.TRUE.equals(h.get("enTramite")))
                    .param("v", v.id()).param("a", actaId).param("c", h.get("clave")).param("cod", h.get("codigo")).param("o", h.get("origen"))
                    .param("t", h.get("texto")).param("h", h.get("hallazgo"))
                    .param("fin", fila == null || fila.get("fin") == null ? null : java.time.LocalDate.parse((String) fila.get("fin")))
                    .param("e", fila == null ? "Pendiente" : fila.get("estado")).update();
        }
        if (enlace == null) {
            jdbc.sql("DELETE FROM opl_accesos WHERE visita_id = :v").param("v", v.id()).update();
            return;
        }
        jdbc.sql("""
                INSERT INTO opl_accesos (visita_id, token_hash, acta_id, enviado, vence_objeciones, vence_plan) VALUES (:v, :t, :a, :e, :vo, :vp)
                ON CONFLICT (visita_id) DO UPDATE SET token_hash = EXCLUDED.token_hash, acta_id = EXCLUDED.acta_id, enviado = EXCLUDED.enviado,
                  vence_objeciones = EXCLUDED.vence_objeciones, vence_plan = EXCLUDED.vence_plan""")
                .param("v", v.id()).param("t", enlace.huella()).param("a", actaId).param("e", enlace.enviado()).param("vo", enlace.venceObjeciones())
                .param("vp", enlace.vencePlan()).update();
        jdbc.sql("INSERT INTO opl_planes (visita_id, acta_id) VALUES (:v, :a) ON CONFLICT (acta_id) DO NOTHING").param("v", v.id()).param("a", actaId)
                .update();
    }

    private GeneradorActa.Contenido contenido(RepositorioVisitas.Visita v, int ronda, List<ServicioFormulario.ItemVisita> items,
                                              Map<String, Checklist.Respuesta> resp, Checklist.Resumen s, EnlaceOpl enlace,
                                              ServicioSeguimiento.Programacion programacion, List<ServicioSeguimiento.Prorroga> prorrogas) {
        Map<String, Object> e = formulario.ejecucion(v.id());
        Map<String, Object> lugar = jdbc.sql("""
                SELECT p.nombre, p.sigla, p.direccion, mu.nombre AS municipio, d.nombre AS departamento FROM puntos p
                LEFT JOIN par_municipios mu ON mu.codigo = p.municipio LEFT JOIN par_departamentos d ON d.codigo = mu.departamento WHERE p.id = :id""")
                .param("id", v.puntoId()).query().singleRow();
        Map<String, String> datos = new LinkedHashMap<>();
        datos.put("Número", v.numero());
        datos.put("Establecimiento", (lugar.get("sigla") == null ? "" : lugar.get("sigla") + " · ") + lugar.get("nombre"));
        datos.put("Unidad y fuerza", v.unidad() + " · " + v.fuerza() + (v.modalidad() == null ? "" : " · " + v.modalidad()));
        datos.put("Municipio", nulo(lugar.get("municipio")) + ", " + nulo(lugar.get("departamento")));
        datos.put("Dirección", nulo(lugar.get("direccion")));
        datos.put("Tipo de visita", visitas.tipo(v.tipo()).map(RepositorioVisitas.Tipo::nombre).orElse(v.tipo()));
        datos.put("Fecha programada", Plazos.fechaLarga(v.fecha()));
        datos.put("Inicio real", String.valueOf(e.get("inicioReal")).replace('T', ' '));
        datos.put("Fin real", String.valueOf(e.get("finReal")).replace('T', ' '));
        datos.put("Objeto", nulo(e.get("objeto")));
        datos.put("Alcance", v.alcance());
        datos.put("Checklist", nulo(v.checklistVersion()));
        List<String[]> equipo = visitas.equipo(v.id()).stream()
                .map(m -> new String[] {m.nombre(), Reparto.ROLES.getOrDefault(m.rol(), "Otro"), m.id() == v.auditorId() ? "Líder" : "Apoyo"}).toList();
        List<String[]> personal = jdbc.sql("SELECT entidad, nombre, cargo, documento FROM visitas_personal WHERE visita_id = :v ORDER BY orden, id")
                .param("v", v.id()).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), nulo(rs.getString(3)), nulo(rs.getString(4))}).list();
        Map<String, GeneradorActa.Componente> comps = new LinkedHashMap<>();
        Set<String> tramite = formulario.enTramite(v.id());
        for (ServicioFormulario.ItemVisita i : items) {
            Checklist.Respuesta r = resp.get(i.codigo());
            String hallazgo = r == null || r.hallazgo() == null ? "" : r.hallazgo();
            if (tramite.contains(i.codigo())) {
                hallazgo = (hallazgo.isEmpty() ? "" : hallazgo + " ") + ServicioFormulario.TEXTO_TRAMITE;
            }
            comps.computeIfAbsent(i.componente(), k -> new GeneradorActa.Componente(i.componente(), i.componenteNombre(), new ArrayList<>()))
                    .items().add(new String[] {i.codigo(), i.punto(), r == null ? "" : Checklist.NOMBRES.getOrDefault(r.calificacion(), ""), hallazgo});
        }
        Map<String, String> resumen = new LinkedHashMap<>();
        resumen.put("Cumple", String.valueOf(s.c()));
        resumen.put("No cumple", String.valueOf(s.nc()));
        resumen.put("No aplica", String.valueOf(s.na()));
        Double pct = Checklist.cumplimiento(s.c(), s.nc());
        resumen.put("Cumplimiento", pct == null ? "—" : pct + " %");
        List<String[]> soportes = jdbc.sql("""
                SELECT clave, nombre_original, tipo, sha256 FROM archivos WHERE visita_id = :v AND eliminado IS NULL AND clave <> 'acta' ORDER BY id""")
                .param("v", v.id()).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}).list();
        List<GeneradorActa.FirmaActa> firmas = jdbc.sql("""
                SELECT rol, nombre, documento, cargo, imagen FROM visitas_firmas WHERE visita_id = :v AND ronda = :r
                ORDER BY CASE rol WHEN 'auditor' THEN 0 WHEN 'esm' THEN 1 ELSE 2 END""").param("v", v.id()).param("r", ronda)
                .query((rs, n) -> new GeneradorActa.FirmaActa(switch (rs.getString(1)) {
                    case "auditor" -> "Auditor líder";
                    case "w5_auditor" -> "Plan de mejoramiento: auditor";
                    case "w5_auditado" -> "Plan de mejoramiento: recibí los hallazgos";
                    case "esm" -> "Por el establecimiento (ESM)";
                    default -> "Por el operador logístico (OPL)";
                }, rs.getString(2), rs.getString(3), rs.getString(4), rs.getBytes(5))).list();
        return new GeneradorActa.Contenido(v.numero(), ronda, datos, equipo, personal, new ArrayList<>(comps.values()), resumen,
                (String) e.get("recomendaciones"), (String) e.get("observacionesAuditor"), (String) e.get("observacionesEsm"),
                (String) e.get("observacionesOpl"), soportes, firmas, HORA.format(OffsetDateTime.now(reloj).atZoneSameInstant(Fechas.BOGOTA)),
                planActa(v, items, resp), formulario.plan(v, items, resp).get("fecha") == null ? null : String.valueOf(formulario.plan(v, items, resp).get("fecha")),
                enlace == null ? null : enlace.textoPlazos(), enlace == null ? null : enlace.url(), enlace == null ? null : enlace.qr(),
                ServicioSeguimiento.textoActa(programacion, seguimiento.maximo()), seguimiento.datos(v) == null ? null : seguimiento.datos(v).get("ordinal") + " visita de seguimiento al plan de mejoramiento de la visita "
                        + seguimiento.datos(v).get("origenNumero") + ".",
                prorrogas.stream().map(x -> new String[] {x.codigo(), x.finAnterior().toString(), x.finNuevo().toString(),
                        x.justificacion() == null ? "" : x.justificacion().strip(), "Solicitada"}).toList());
    }

    private List<String[]> planActa(RepositorioVisitas.Visita v, List<ServicioFormulario.ItemVisita> items, Map<String, Checklist.Respuesta> resp) {
        Map<String, Map<String, Object>> filas = formulario.filasPlan(v.id());
        List<String[]> r = new ArrayList<>();
        for (Map<String, Object> h : formulario.hallazgos(v, items, resp)) {
            Map<String, Object> f = filas.getOrDefault((String) h.get("clave"), Map.of());
            r.add(new String[] {(String) h.get("codigo"), str(f.get("que")), str(f.get("porque")), str(f.get("donde")), str(f.get("inicio")),
                    str(f.get("fin")), str(f.get("indicador")), f.get("estado") == null ? "Pendiente" : String.valueOf(f.get("estado"))});
        }
        return r;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nulo(Object o) {
        return o == null ? "sin registrar" : String.valueOf(o);
    }

    // ---------- Reabrir (Anexo 5, prueba 10) ----------

    /** El coordinador (o el administrador) reabre una visita cerrada con motivo y descripción; vuelve a ejecución. */
    public void reabrir(long visitaId, String motivo, String descripcion, UsuarioSesion quien) {
        String m = motivo == null ? "" : motivo.strip();
        if (!EstadosVisita.MOTIVOS_REAPERTURA.containsKey(m)) {
            throw ErrorNegocio.validacion("Elija el motivo: " + String.join(" o ", EstadosVisita.MOTIVOS_REAPERTURA.values()) + ".");
        }
        String d = descripcion == null ? "" : descripcion.strip();
        if (d.length() < 10 || d.length() > 800) {
            throw ErrorNegocio.validacion("Describa qué se va a corregir (de 10 a 800 caracteres).");
        }
        String numero = tx.execute(t -> {
            RepositorioVisitas.Visita v = visitas.bloquear(visitaId).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
            int n = jdbc.sql("UPDATE visitas SET estado = 'ejecucion', reaperturas = reaperturas + 1, actualizado = :ahora WHERE id = :id AND estado = 'cerrada'")
                    .param("ahora", OffsetDateTime.now(reloj)).param("id", visitaId).update();
            if (n != 1) {
                throw ErrorNegocio.validacion("Solo se reabre una visita cerrada.");
            }
            visitas.historial(visitaId, quien.id(), quien.usuario(), "Reabierta",
                    EstadosVisita.MOTIVOS_REAPERTURA.get(m) + ". " + d + " (ronda " + (v.reaperturas() + 2) + ")");
            return v.numero();
        });
        bitacora.registrar("visita_reabierta", "Visita " + numero + " reabierta: " + EstadosVisita.MOTIVOS_REAPERTURA.get(m), quien.id(), quien.usuario());
    }

    /** Recorte de texto para los errores guardados. */
    static final class ServicioNotificacionTexto {
        private ServicioNotificacionTexto() {
        }

        static String corto(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max - 1) + "…";
        }
    }
}
