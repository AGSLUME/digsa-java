package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.plataforma.BandejaSalida;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Presupuesto;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Notificación formal y electrónica (Anexo 2 L741): un correo con consecutivo anual (PRE-2027-0001) al buzón de la Sanidad de la fuerza
 * (par_fuerzas.correos: DISAN EJC, DISAN ARC o JEFSA FAC) y al de la DIGSA (parámetro correo_digsa). Sin SMS (decisión del usuario: los
 * anexos dicen «formal y electrónica»). Se encola en la misma transacción del cambio que la origina: si esa transacción se revierte, no
 * sale nada. Sin datos personales.
 */
@Component
class Notificaciones {

    private static final Logger LOG = LoggerFactory.getLogger(Notificaciones.class);

    /** Lo que se notifica. nivel: esm, fuerza o nacional; fuerza vacía en el nivel nacional (va a todas las sanidades). */
    record Aviso(String tipo, String nivel, Lineas.Vigencia vigencia, Long lineaId, String fuerza, LocalDate corte, String asunto, List<String[]> datos) {
    }

    record Resultado(long id, String consecutivo, String estado, List<String> para) {
    }

    private final JdbcClient jdbc;
    private final BandejaSalida bandeja;
    private final RegistroBitacora bitacora;
    private final ConfiguracionDigsa cfg;
    private final Clock reloj;

    Notificaciones(JdbcClient jdbc, BandejaSalida bandeja, RegistroBitacora bitacora, ConfiguracionDigsa cfg, Clock reloj) {
        this.jdbc = jdbc;
        this.bandeja = bandeja;
        this.bitacora = bitacora;
        this.cfg = cfg;
        this.reloj = reloj;
    }

    Resultado notificar(Aviso a, Long usuarioId, String usuario) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        int anio = ahora.atZoneSameInstant(Fechas.BOGOTA).getYear();
        int numero = jdbc.sql("""
                INSERT INTO presupuesto_consecutivos (anio, ultimo) VALUES (:a, 1)
                ON CONFLICT (anio) DO UPDATE SET ultimo = presupuesto_consecutivos.ultimo + 1 RETURNING ultimo""")
                .param("a", anio).query(Integer.class).single();
        String prefijo = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'presupuesto_consecutivo_prefijo'").query(String.class).optional().orElse("PRE");
        String consecutivo = Presupuesto.consecutivo(prefijo, anio, numero);
        Destinatarios d = destinatarios(a.fuerza(), a.nivel());
        String estado = d.estado();
        String asunto = consecutivo + " · " + a.asunto();
        String html = html(consecutivo, a, ahora);
        String texto = texto(consecutivo, a, ahora);
        Long correoId = d.todos().isEmpty() ? null : bandeja.encolar("presupuesto:" + a.tipo(), consecutivo,
                new Correo.Mensaje(d.todos(), List.of(), asunto, html, List.of()));
        long id = jdbc.sql("""
                INSERT INTO presupuesto_notificaciones (consecutivo, tipo, nivel, vigencia_id, linea_id, fuerza, corte, asunto, texto, para, correo_id, estado, creado)
                VALUES (:c, :t, :n, :v, :l, :f, :corte, :asunto, :texto, :para, :correo, :estado, :ahora) RETURNING id""")
                .param("c", consecutivo).param("t", a.tipo()).param("n", a.nivel()).param("v", a.vigencia().id()).param("l", a.lineaId())
                .param("f", a.fuerza() == null || a.fuerza().isBlank() ? null : a.fuerza()).param("corte", a.corte()).param("asunto", cortar(asunto, 300))
                .param("texto", cortar(texto, 4000)).param("para", d.todos().toArray(String[]::new)).param("correo", correoId).param("estado", estado)
                .param("ahora", ahora).query(Long.class).single();
        if (!"encolada".equals(estado)) {
            LOG.warn("[presupuesto] Notificación {} {} (fuerza {}): revise par_fuerzas.correos y el parámetro correo_digsa", consecutivo, estado,
                    a.fuerza() == null ? "todas" : a.fuerza());
            bitacora.registrar("presupuesto_notificacion_fallida", "Notificación " + consecutivo + ": " + estado.replace('_', ' '), usuarioId, usuario);
        }
        return new Resultado(id, consecutivo, estado, d.todos());
    }

    /**
     * Vuelve a encolar una notificación que no salió completa: le faltaba un buzón (fuerza o DIGSA) o su correo agotó los intentos en la
     * bandeja de salida.
     */
    @org.springframework.transaction.annotation.Transactional
    Resultado reenviar(long id, Long usuarioId, String usuario) {
        Map<String, Object> n = jdbc.sql("""
                SELECT n.consecutivo, n.tipo, n.nivel, n.fuerza, n.asunto, n.texto, n.estado, c.estado AS envio
                FROM presupuesto_notificaciones n LEFT JOIN correos_salida c ON c.id = n.correo_id WHERE n.id = :id FOR UPDATE OF n""")
                .param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> co.com.agsamericas.digsa.plataforma.ErrorNegocio
                        .noEncontrado("La notificación no existe."));
        if ("encolada".equals(n.get("estado")) && !"fallido".equals(n.get("envio"))) {
            throw co.com.agsamericas.digsa.plataforma.ErrorNegocio.validacion("La notificación ya salió completa.");
        }
        Destinatarios d = destinatarios((String) n.get("fuerza"), (String) n.get("nivel"));
        if (!"encolada".equals(d.estado())) {
            throw co.com.agsamericas.digsa.plataforma.ErrorNegocio.validacion(
                    "Aún falta el buzón de la fuerza o el de la DIGSA (parámetro correo_digsa): regístrelo en Catálogos antes de reenviar.");
        }
        String consecutivo = (String) n.get("consecutivo");
        long correo = bandeja.encolar("presupuesto:" + n.get("tipo"), consecutivo, new Correo.Mensaje(d.todos(), List.of(), (String) n.get("asunto"),
                PlantillaCorreo.marco("Notificación presupuestal " + consecutivo, PlantillaCorreo.texto((String) n.get("texto"))), List.of()));
        jdbc.sql("UPDATE presupuesto_notificaciones SET correo_id = :c, estado = 'encolada', para = :p WHERE id = :id")
                .param("c", correo).param("p", d.todos().toArray(String[]::new)).param("id", id).update();
        bitacora.registrar("presupuesto_notificacion_reenviada", "Notificación " + consecutivo + " reenviada", usuarioId, usuario);
        return new Resultado(id, consecutivo, "encolada", d.todos());
    }

    record Destinatarios(List<String> todos, boolean faltaFuerza, boolean faltaDigsa) {
        String estado() {
            return todos.isEmpty() ? "sin_destinatarios" : faltaFuerza ? "sin_buzon_fuerza" : faltaDigsa ? "sin_buzon_digsa" : "encolada";
        }
    }

    /** Buzones de la Sanidad de la fuerza (o de todas en el nivel nacional) y de la DIGSA, válidos y sin repetir. */
    Destinatarios destinatarios(String fuerza, String nivel) {
        Set<String> todos = new LinkedHashSet<>();
        boolean faltaFuerza = false;
        // Sin fuerza en un aviso de ESM o de fuerza: falla cerrado (no se reparte a todas las sanidades)
        List<Map<String, Object>> fuerzas = !"nacional".equals(nivel) && (fuerza == null || fuerza.isBlank()) ? List.of()
                : "nacional".equals(nivel)
                ? jdbc.sql("SELECT codigo, correos FROM par_fuerzas WHERE activo AND codigo <> 'NA' ORDER BY orden").query().listOfRows()
                : jdbc.sql("SELECT codigo, correos FROM par_fuerzas WHERE codigo = :f").param("f", fuerza).query().listOfRows();
        for (Map<String, Object> f : fuerzas) {
            List<String> c = validos(arreglo(f.get("correos")));
            if (c.isEmpty()) {
                faltaFuerza = true;
            }
            todos.addAll(c);
        }
        if (fuerzas.isEmpty()) {
            faltaFuerza = true;
        }
        String digsa = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'correo_digsa'").query(String.class).optional().orElse("");
        List<String> buzonDigsa = validos(List.of(digsa.split("[,;\\s]+")));
        todos.addAll(buzonDigsa);
        if (todos.size() > 50) {
            LOG.warn("[presupuesto] La notificación tiene {} buzones: solo salen los primeros 50", todos.size());
        }
        return new Destinatarios(new ArrayList<>(todos).subList(0, Math.min(todos.size(), 50)), faltaFuerza, buzonDigsa.isEmpty());
    }

    private static List<String> validos(List<String> correos) {
        return correos.stream().map(Correos::normalizar).filter(Correos::valido).distinct().toList();
    }

    private static List<String> arreglo(Object v) {
        try {
            if (v instanceof java.sql.Array a) {
                Object[] x = (Object[]) a.getArray();
                List<String> r = new ArrayList<>();
                for (Object o : x) {
                    if (o != null) {
                        r.add(o.toString());
                    }
                }
                return r;
            }
            if (v instanceof String[] s) {
                return List.of(s);
            }
        } catch (java.sql.SQLException e) {
            LOG.error("[presupuesto] No se pudieron leer los correos de la fuerza", e);
        }
        return List.of();
    }

    private String html(String consecutivo, Aviso a, OffsetDateTime ahora) {
        StringBuilder filas = new StringBuilder();
        for (String[] d : encabezado(consecutivo, a, ahora)) {
            filas.append("<tr><th style=\"text-align:left;padding:2px 8px\">").append(PlantillaCorreo.e(d[0])).append("</th><td style=\"padding:2px 8px\">")
                    .append(PlantillaCorreo.e(d[1])).append("</td></tr>");
        }
        String cuerpo = "<p>La interventoría informa a la DIGSA y a la Dirección de Sanidad o Jefatura de Salud correspondiente, en cumplimiento del Anexo 2 "
                + "(auditoría de facturación), la siguiente novedad de la ejecución presupuestal del contrato de medicamentos:</p>"
                + "<table style=\"border-collapse:collapse\">" + filas + "</table>"
                + "<p>Consulte el detalle en la Suite DIGSA: <a href=\"" + PlantillaCorreo.e(cfg.enlace("presupuesto")) + "\">" + PlantillaCorreo.e(cfg.enlace("presupuesto"))
                + "</a></p><p>Generado por la interventoría · Suite DIGSA.</p>";
        return PlantillaCorreo.marco("Notificación presupuestal " + consecutivo, cuerpo);
    }

    private String texto(String consecutivo, Aviso a, OffsetDateTime ahora) {
        StringBuilder t = new StringBuilder();
        for (String[] d : encabezado(consecutivo, a, ahora)) {
            t.append(d[0]).append(": ").append(d[1]).append('\n');
        }
        return t.append("Generado por la interventoría · Suite DIGSA.").toString();
    }

    private static List<String[]> encabezado(String consecutivo, Aviso a, OffsetDateTime ahora) {
        List<String[]> r = new ArrayList<>();
        r.add(new String[] {"Consecutivo", consecutivo});
        r.add(new String[] {"Fecha", ahora.atZoneSameInstant(Fechas.BOGOTA).toLocalDateTime().withNano(0).toString().replace('T', ' ')});
        r.add(new String[] {"Vigencia", String.valueOf(a.vigencia().anio())});
        r.addAll(a.datos());
        return r;
    }

    /**
     * Notificaciones en el alcance, de la más reciente a la más antigua: DIGSA y la interventoría todas; Sanidad las de su fuerza y las
     * nacionales (también le llegan); ESM las de sus líneas. Los destinatarios no se muestran (son buzones; basta el estado).
     */
    Map<String, Object> lista(Ambito a, Long vigencia, String tipo, Integer pagina) {
        Map<String, Object> p = new java.util.HashMap<>(a.parametros());
        String condicion = a.parametros().containsKey("ambito_fuerza") ? "(l.fuerza = :ambito_fuerza OR n.fuerza = :ambito_fuerza OR n.nivel = 'nacional')"
                : a.parametros().containsKey("ambito_punto") ? "l.punto_id = :ambito_punto" : a.condicion();
        StringBuilder w = new StringBuilder(" WHERE " + condicion);
        if (vigencia != null) {
            w.append(" AND n.vigencia_id = :f_vigencia");
            p.put("f_vigencia", vigencia);
        }
        if (tipo != null && !tipo.isBlank()) {
            if (!java.util.Set.of("umbral", "desviacion").contains(tipo.strip())) {
                throw co.com.agsamericas.digsa.plataforma.ErrorNegocio.validacion("El tipo debe ser «umbral» o «desviacion».");
            }
            w.append(" AND n.tipo = :f_tipo");
            p.put("f_tipo", tipo.strip());
        }
        int pag = pagina == null || pagina < 1 ? 1 : Math.min(pagina, 100_000);
        String desde = " FROM presupuesto_notificaciones n LEFT JOIN presupuesto_lineas l ON l.id = n.linea_id LEFT JOIN correos_salida c ON c.id = n.correo_id" + w;
        long total = jdbc.sql("SELECT count(*)" + desde).params(p).query(Long.class).single();
        p.put("limite", 50);
        p.put("desplazamiento", (pag - 1) * 50);
        List<Map<String, Object>> items = jdbc.sql("SELECT n.id, n.consecutivo, n.tipo, n.nivel, n.fuerza, n.corte, n.asunto, n.texto, n.estado, n.creado, c.estado AS envio"
                + desde + " ORDER BY n.creado DESC, n.id DESC LIMIT :limite OFFSET :desplazamiento").params(p).query().listOfRows().stream().map(r -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("id", r.get("id"));
                    x.put("consecutivo", r.get("consecutivo"));
                    x.put("tipo", r.get("tipo"));
                    x.put("nivel", r.get("nivel"));
                    x.put("fuerza", r.get("fuerza"));
                    x.put("corte", r.get("corte") == null ? null : r.get("corte").toString().substring(0, 7));
                    x.put("asunto", r.get("asunto"));
                    x.put("texto", r.get("texto"));
                    // El envío real de la bandeja: una notificación «encolada» cuyo correo agotó los intentos se muestra «fallida» y se puede reenviar
                    x.put("estado", "encolada".equals(r.get("estado")) && "fallido".equals(r.get("envio")) ? "fallida" : r.get("estado"));
                    x.put("envio", r.get("envio"));
                    x.put("fecha", Lineas.fechaHora(r.get("creado")));
                    return x;
                }).toList();
        return Map.of("total", total, "pagina", pag, "porPagina", 50, "items", items);
    }

    private static String cortar(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
