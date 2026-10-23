package co.com.agsamericas.digsa.auditoria;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.CampoTemporal;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.ClaseReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Columna;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Filtro;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoColumna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import co.com.agsamericas.digsa.reportes.JdbcReportes;
import co.com.agsamericas.digsa.reportes.Opcion;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Los logs de auditoría en el catálogo del módulo de reportes (Anexo 5, 2.7 a 2.12): los cuatro formatos, el encabezado de 2.9, la
 * huella y el código. Con los mismos topes de la pantalla de logs: 200.000 filas por archivo y la búsqueda por texto en periodos de
 * hasta 92 días. La pantalla y los endpoints de {@code /api/logs} siguen igual.
 */
@Component
class ProveedorReporteLogs implements ProveedorReporte {

    private static final Logger LOG = LoggerFactory.getLogger(ProveedorReporteLogs.class);
    static final String ID = "logs_auditoria";
    private static final Pattern IDENTIFICADOR = Pattern.compile("^[a-z0-9_.-]{1,50}$");

    static final DefinicionReporte DEFINICION = DefinicionReporte.nuevo(ID, "Logs de auditoría")
            .descripcion("Cada operación registrada en la bitácora: fecha y hora, usuario, operación, tipo, detalle, IP y cambios.")
            .grupo(GrupoReporte.SEGURIDAD).modulo("auditoria").clase(ClaseReporte.OPERATIVO).pantalla(Pantallas.LOGS)
            .fuente("Bitácora de la Suite DIGSA (log_transacciones)").periodo(CampoTemporal.FECHA_HORA, Set.of())
            .maxFilas(ControladorLogs.MAX_EXPORTAR)
            .filtro(Filtro.lista("usuario_id", "Usuario")).filtro(Filtro.lista("operacion", "Operación")).filtro(Filtro.lista("modulo", "Módulo"))
            .filtro(Filtro.texto("tipo", "Tipo")).filtro(Filtro.texto("texto", "Texto"))
            .columna("Fecha y hora", TipoColumna.FECHA_HORA).columna(Columna.personal("Usuario", Set.of(Perfil.ADMINISTRADOR)))
            .columna("Operación", TipoColumna.TEXTO).columna("Módulo", TipoColumna.TEXTO).columna("Tipo", TipoColumna.TEXTO)
            .columna("Detalle", TipoColumna.TEXTO).columna(Columna.personal("IP", Set.of(Perfil.ADMINISTRADOR)))
            .columna("Cambios (antes → después)", TipoColumna.TEXTO).orden("fecha y hora, de la más reciente a la más antigua, y registro").posicion(10)
            .crear();

    private final JdbcReportes jdbc;
    private final RepositorioLogs logs;
    private final ObjectMapper json;

    ProveedorReporteLogs(JdbcReportes jdbc, RepositorioLogs logs, ObjectMapper json) {
        this.jdbc = jdbc;
        this.logs = logs;
        this.json = json;
    }

    @Override
    public DefinicionReporte definicion() {
        return DEFINICION;
    }

    @Override
    public List<Opcion> opciones(String filtro, Alcance alcance) {
        return switch (filtro) {
            case "usuario_id" -> logs.usuarios().stream().map(u -> new Opcion(String.valueOf(u.get("id")), u.get("nombre") + " (" + u.get("usuario") + ")"))
                    .toList();
            case "operacion" -> Arrays.stream(Bitacora.Operacion.values()).map(o -> new Opcion(o.valor(), o.valor())).toList();
            case "modulo" -> logs.modulos().stream().map(m -> new Opcion(m, m)).toList();
            default -> List.of();
        };
    }

    @Override
    public void filas(ConsultaReporte c, Consumer<List<Object>> destino) {
        String tipo = identificador(c.valores("tipo").stream().findFirst().orElse(null));
        String texto = c.valores("texto").stream().findFirst().map(t -> Bitacora.cortar(t.strip(), 80)).filter(t -> !t.isEmpty()).orElse(null);
        if (texto != null && ChronoUnit.DAYS.between(c.desde(), c.hasta()) > ControladorLogs.MAX_DIAS_TEXTO) {
            throw ErrorNegocio.validacion("Para buscar por texto, use un periodo de máximo " + ControladorLogs.MAX_DIAS_TEXTO + " días.");
        }
        Map<String, Object> p = new HashMap<>();
        p.put("desde", c.desde().atStartOfDay(Fechas.BOGOTA).toOffsetDateTime());
        p.put("hasta", c.hasta().plusDays(1).atStartOfDay(Fechas.BOGOTA).toOffsetDateTime());
        StringBuilder w = new StringBuilder(" WHERE fecha >= :desde AND fecha < :hasta");
        if (c.filtra("usuario_id")) {
            w.append(" AND usuario_id IN (:usuarios)");
            p.put("usuarios", c.valores("usuario_id").stream().map(Long::valueOf).toList());
        }
        if (c.filtra("operacion")) {
            w.append(" AND operacion IN (:operaciones)");
            p.put("operaciones", c.valores("operacion"));
        }
        if (c.filtra("modulo")) {
            w.append(" AND modulo IN (:modulos)");
            p.put("modulos", c.valores("modulo"));
        }
        if (tipo != null) {
            w.append(" AND tipo = :tipo");
            p.put("tipo", tipo);
        }
        if (texto != null) {
            w.append(" AND (tipo ILIKE :texto ESCAPE '\\' OR descripcion ILIKE :texto ESCAPE '\\' OR usuario ILIKE :texto ESCAPE '\\')");
            p.put("texto", "%" + texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        jdbc.jdbc().sql("SELECT id, fecha, operacion, tipo, modulo, usuario, descripcion, ip, cambios::text AS cambios FROM log_transacciones" + w
                + " ORDER BY fecha DESC, id DESC" + JdbcReportes.limite(c)).params(p).query(rs -> {
                    Map<String, Object> v = new HashMap<>();
                    v.put("Fecha y hora", rs.getObject("fecha", OffsetDateTime.class));
                    v.put("Usuario", texto(rs.getString("usuario")));
                    v.put("Operación", rs.getString("operacion"));
                    v.put("Módulo", texto(rs.getString("modulo")));
                    v.put("Tipo", rs.getString("tipo"));
                    v.put("Detalle", texto(rs.getString("descripcion")));
                    v.put("IP", texto(rs.getString("ip")));
                    v.put("Cambios (antes → después)", cambiosEnTexto(rs.getLong("id"), rs.getString("cambios")));
                    List<Object> fila = new ArrayList<>(c.columnas().size());
                    c.columnas().forEach(col -> fila.add(v.get(col)));
                    destino.accept(fila);
                });
    }

    /** Vacío: sin filtro. Escrito y no válido: 422, como en la pantalla de logs. */
    private static String identificador(String s) {
        String t = s == null ? "" : s.strip().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        if (!IDENTIFICADOR.matcher(t).matches()) {
            throw ErrorNegocio.validacion("El tipo es una palabra sin espacios (p. ej. usuario_editado).");
        }
        return t;
    }

    /** Como en la descarga de la pantalla de logs: «campo: «antes» → «después»; …», o «campo: modificado» si es un dato personal. */
    private String cambiosEnTexto(long id, String crudo) {
        if (crudo == null || crudo.isBlank()) {
            return "";
        }
        List<Map<String, Object>> cs;
        try {
            cs = json.readValue(crudo, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException e) {
            LOG.warn("[logs] Cambios ilegibles en el registro {} de la bitácora: {}", id, e.getOriginalMessage());
            return "(cambios ilegibles)";
        }
        String t = String.join("; ", cs.stream().map(x -> Boolean.TRUE.equals(x.get("oculto")) ? Objects.toString(x.get("campo"), "?") + ": modificado"
                : Objects.toString(x.get("campo"), "?") + ": «" + Objects.toString(x.get("antes"), "") + "» → «" + Objects.toString(x.get("despues"), "") + "»").toList());
        return Bitacora.largoCorte(t, ControladorLogs.MAX_CAMBIOS_CELDA);
    }

    private static String texto(String s) {
        return s == null ? "" : s;
    }
}
