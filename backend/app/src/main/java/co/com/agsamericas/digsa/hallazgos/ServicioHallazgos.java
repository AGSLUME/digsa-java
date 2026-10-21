package co.com.agsamericas.digsa.hallazgos;

import co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda;
import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Seguimiento;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * «Prestadores con hallazgos» (decisiones 6.8 y 36; docs/especificaciones/seguimiento): por prestador, la última visita cerrada con
 * hallazgos de cada cadena de seguimientos, con su plan, la respuesta del OPL, la siguiente visita y la situación. El ESM no ve las
 * objeciones del OPL, el motivo del líder ni los soportes (decisión 12.2): el hallazgo objetado se le muestra como vigente.
 */
@Service
public class ServicioHallazgos {

    private final JdbcClient jdbc;
    private final ServicioCatalogos catalogos;

    public ServicioHallazgos(JdbcClient jdbc, ServicioCatalogos catalogos) {
        this.jdbc = jdbc;
        this.catalogos = catalogos;
    }

    int maximo() {
        try {
            int n = Integer.parseInt(catalogos.textoParametro("max_seguimientos", "3").strip());
            return n >= 1 ? n : 3;
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> lista(Alcance alcance, UsuarioSesion quien, java.util.Set<co.com.agsamericas.digsa.reglas.Perfil> perfiles) {
        if (alcance instanceof Alcance.Opl || alcance instanceof Alcance.Regional || alcance instanceof Alcance.Ninguno) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Esta pantalla no es del perfil OPL.");
        }
        boolean esm = alcance instanceof Alcance.Esm;
        // Solo el auditor y la coordinación abren el checklist (la DIGSA, con alcance de todo el país, no)
        boolean abreChecklist = perfiles.contains(co.com.agsamericas.digsa.reglas.Perfil.AUDITOR) || perfiles.contains(co.com.agsamericas.digsa.reglas.Perfil.COORDINADOR)
                || perfiles.contains(co.com.agsamericas.digsa.reglas.Perfil.ADMINISTRADOR);
        ServicioConsultaAgenda.Condicion c = ServicioConsultaAgenda.condicion(alcance);
        // Fin de cada cadena: visita cerrada con hallazgos en su acta vigente y sin un seguimiento cerrado después
        List<Map<String, Object>> visitas = jdbc.sql("""
                SELECT v.id, v.numero, v.fecha, v.estado, v.seguimiento_n, v.origen_id, o.numero AS origen_numero, v.auditor_id, u.nombre AS auditor,
                       v.punto_id, p.nombre AS punto, p.unidad, p.fuerza, a.id AS acta_id,
                       pl.estado AS plan_estado, pl.firmado AS plan_firmado, ac.vence_objeciones, ac.vence_plan,
                       sg.id AS sig_id, sg.numero AS sig_numero, sg.fecha AS sig_fecha, sg.estado AS sig_estado
                FROM visitas v JOIN puntos p ON p.id = v.punto_id JOIN usuarios u ON u.id = v.auditor_id
                LEFT JOIN visitas o ON o.id = v.origen_id
                JOIN LATERAL (SELECT id FROM visitas_actas WHERE visita_id = v.id AND estado IN ('enviada', 'parcial') ORDER BY ronda DESC LIMIT 1) a ON true
                LEFT JOIN opl_planes pl ON pl.acta_id = a.id
                LEFT JOIN opl_accesos ac ON ac.visita_id = v.id AND ac.acta_id = a.id
                LEFT JOIN visitas sg ON sg.origen_id = v.id AND sg.estado IN ('programada', 'notificada', 'reprogramada', 'ejecucion')
                WHERE v.estado = 'cerrada' AND EXISTS (SELECT 1 FROM visitas_hallazgos h WHERE h.acta_id = a.id)
                  AND NOT EXISTS (SELECT 1 FROM visitas s WHERE s.origen_id = v.id AND s.estado = 'cerrada')
                  AND (""" + c.sql() + ") ORDER BY p.nombre, v.fecha DESC, v.id DESC LIMIT 1000")
                .params(c.params()).query().listOfRows();
        Map<Long, Map<String, Object>> porPunto = new LinkedHashMap<>();
        int max = maximo();
        Map<Long, List<Map<String, Object>>> todos = hallazgos(visitas.stream().map(v -> ((Number) v.get("acta_id")).longValue()).toList(), esm);
        for (Map<String, Object> v : visitas) {
            long visitaId = ((Number) v.get("id")).longValue();
            long actaId = ((Number) v.get("acta_id")).longValue();
            List<Map<String, Object>> hs = todos.getOrDefault(actaId, List.of());
            long abiertas = hs.stream().filter(h -> !"levantado".equals(h.get("estadoOpl")) && !Seguimiento.ESTADOS_CERRADOS.contains(h.get("estadoPlan"))).count();
            Map<String, Object> sig = v.get("sig_id") == null ? null
                    : Map.of("id", v.get("sig_id"), "numero", v.get("sig_numero"), "fecha", v.get("sig_fecha"), "estado", v.get("sig_estado"));
            int n = v.get("seguimiento_n") == null ? 0 : ((Number) v.get("seguimiento_n")).intValue();
            String situacion = sig != null ? "programada" : abiertas == 0 ? "plan_cerrado" : n + 1 > max ? "escalar" : "sin_programar";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("visitaId", visitaId);
            m.put("numero", v.get("numero"));
            m.put("fecha", String.valueOf(v.get("fecha")));
            m.put("auditor", v.get("auditor"));
            m.put("esLider", ((Number) v.get("auditor_id")).longValue() == quien.id());
            m.put("seguimientoN", n == 0 ? null : n);
            m.put("origenNumero", v.get("origen_numero"));
            m.put("hallazgos", hs);
            m.put("abiertas", abiertas);
            m.put("planEstado", v.get("plan_estado"));
            m.put("planFirmado", v.get("plan_firmado") != null);
            m.put("venceObjeciones", v.get("vence_objeciones") == null ? null : String.valueOf(v.get("vence_objeciones")));
            m.put("vencePlan", v.get("vence_plan") == null ? null : String.valueOf(v.get("vence_plan")));
            m.put("siguiente", sig == null ? null : Map.of("id", sig.get("id"), "numero", sig.get("numero"), "fecha", String.valueOf(sig.get("fecha")), "estado",
                    sig.get("estado")));
            m.put("situacion", situacion);
            m.put("abreChecklist", abreChecklist);
            Map<String, Object> punto = porPunto.computeIfAbsent(((Number) v.get("punto_id")).longValue(), k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("puntoId", k);
                x.put("punto", v.get("punto"));
                x.put("unidad", v.get("unidad"));
                x.put("fuerza", v.get("fuerza"));
                x.put("visitas", new ArrayList<Map<String, Object>>());
                return x;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lista = (List<Map<String, Object>>) punto.get("visitas");
            lista.add(m);
        }
        return new ArrayList<>(porPunto.values());
    }

    /** Hallazgos de todas las actas en una sola consulta, por acta. */
    private Map<Long, List<Map<String, Object>>> hallazgos(List<Long> actas, boolean esm) {
        Map<Long, List<Map<String, Object>>> r = new LinkedHashMap<>();
        if (actas.isEmpty()) {
            return r;
        }
        jdbc.sql("""
                SELECT h.acta_id, h.clave, h.codigo, h.texto, h.hallazgo, coalesce(pl.fin, h.fin) AS fin, coalesce(pl.estado, h.estado_plan) AS estado_plan,
                       r.decision, r.automatica, r.resolucion, r.justificacion, r.resolucion_motivo,
                       (SELECT count(*) FROM opl_soportes s WHERE s.visita_id = h.visita_id AND s.clave = h.clave) AS soportes
                FROM visitas_hallazgos h LEFT JOIN visitas_plan pl ON pl.visita_id = h.visita_id AND pl.clave = h.clave
                LEFT JOIN opl_respuestas r ON r.visita_id = h.visita_id AND r.clave = h.clave
                WHERE h.acta_id = ANY(:a) ORDER BY h.acta_id, h.id""").param("a", actas.toArray(Long[]::new)).query().listOfRows().forEach(f -> {
            String estadoOpl = estadoOpl((String) f.get("decision"), Boolean.TRUE.equals(f.get("automatica")), (String) f.get("resolucion"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clave", f.get("clave"));
            m.put("codigo", f.get("codigo"));
            m.put("texto", f.get("texto"));
            m.put("hallazgo", f.get("hallazgo"));
            m.put("fin", f.get("fin") == null ? null : String.valueOf(f.get("fin")));
            m.put("estadoPlan", f.get("estado_plan"));
            if (esm) {
                // Decisión 12.2: sin objeción, motivo ni soportes; el objetado o ratificado se ve como vigente
                m.put("estadoOpl", "levantado".equals(estadoOpl) ? "levantado" : "vigente");
            } else {
                m.put("estadoOpl", estadoOpl);
                m.put("justificacion", f.get("justificacion"));
                m.put("resolucionMotivo", f.get("resolucion_motivo"));
                m.put("soportes", ((Number) f.get("soportes")).intValue());
            }
            r.computeIfAbsent(((Number) f.get("acta_id")).longValue(), k -> new ArrayList<>()).add(m);
        });
        return r;
    }

    static String estadoOpl(String decision, boolean automatica, String resolucion) {
        if (decision == null) {
            return "sin_respuesta";
        }
        if ("acepta".equals(decision)) {
            return automatica ? "aceptado_auto" : "aceptado";
        }
        return resolucion == null ? "objetado" : "ratifica".equals(resolucion) ? "ratificado" : "levantado";
    }
}
