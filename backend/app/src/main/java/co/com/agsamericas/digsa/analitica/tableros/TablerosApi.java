package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.ConsultaAnalitica;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * «Analítica farmacéutica» (Pantallas.ANALITICA, grupo Información; analitica-tableros): catálogo de la pantalla, cortes, opciones de
 * filtro en el alcance, los 12 tableros, la consulta estructurada (asistente y consulta guiada), las descargas Excel, CSV y PDF, la
 * trazabilidad 14.9 (pantalla ANALITICA_TRAZABILIDAD) y la bitácora de analítica (Administrador y Coordinador). La consulta viaja en la
 * URL con parámetros repetidos (contrato de reportes 2.11): la misma URL da la misma consulta en la pantalla, la descarga y el rastreo.
 *
 * <p>Toda consulta, descarga y rastreo deja su fila en la bitácora de analítica, también si falla: «rechazada» (validación),
 * «tiempo_agotado» o «error» (falla técnica, sin el mensaje técnico).
 */
@RestController
@PreAuthorize("@permisos.pantalla('ANALITICA')")
public class TablerosApi {

    private static final String TRAZA = "@permisos.pantalla('ANALITICA') and @permisosTableros.traza()";

    private final ServicioConsultaAnalitica servicio;
    private final ServicioTrazaAnalitica traza;
    private final ExportadorAnalitica exportador;
    private final BitacoraAnalitica bitacora;
    private final PerfilesOperacion perfiles;
    private final PermisosTableros permisos;
    private final Clock reloj;

    TablerosApi(ServicioConsultaAnalitica servicio, ServicioTrazaAnalitica traza, ExportadorAnalitica exportador, BitacoraAnalitica bitacora,
                PerfilesOperacion perfiles, PermisosTableros permisos, Clock reloj) {
        this.servicio = servicio;
        this.traza = traza;
        this.exportador = exportador;
        this.bitacora = bitacora;
        this.perfiles = perfiles;
        this.permisos = permisos;
        this.reloj = reloj;
    }

    private static Alcance alcance() {
        return UsuarioSesion.alcanceActual();
    }

    /** Tableros, variables, granularidades, vistas del alcance, cobertura del A4 y qué puede hacer el usuario. */
    @GetMapping("/api/analitica/catalogo")
    @Accion(modulo = "analitica", accion = "catalogo", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> catalogo() {
        Map<String, Object> m = new LinkedHashMap<>(servicio.catalogo(alcance()));
        m.put("puedeRastrear", permisos.traza());
        m.put("puedeVerBitacora", permisos.bitacora());
        m.put("puedeVerCargues", UsuarioSesion.pantallasActuales().contains(Pantallas.ANALITICA_CARGUES));
        return m;
    }

    /** Cargues aplicados para elegir el corte; nombre de archivo y conteo nacional solo para el alcance nacional con la pantalla de cargues. */
    @GetMapping("/api/analitica/cortes")
    @Accion(modulo = "analitica", accion = "cortes", operacion = Declarada.SIN_REGISTRO)
    public List<Map<String, Object>> cortes() {
        boolean detalle = alcance() instanceof Alcance.Todo && (UsuarioSesion.pantallasActuales().contains(Pantallas.ANALITICA_CARGUES)
                || UsuarioSesion.pantallasActuales().contains(Pantallas.ANALITICA_TRAZABILIDAD));
        return servicio.cortes(detalle);
    }

    /** Opciones de una variable de filtro en el alcance del usuario (visibles al corte). */
    @GetMapping("/api/analitica/opciones")
    @Accion(modulo = "analitica", accion = "opciones", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> opciones(@RequestParam String variable, @RequestParam(required = false) String texto,
                                        @RequestParam(required = false) Long corte) {
        return servicio.opciones(ParametrosConsulta.variable(variable), texto, corte, alcance());
    }

    /** Un tablero con los filtros de la URL. Cada consulta queda en la bitácora de analítica con su huella. */
    @GetMapping("/api/analitica/tableros/{tablero}")
    @Accion(modulo = "analitica", accion = "tablero_consulta", operacion = Declarada.CONSULTA)
    public ResultadoTablero tablero(@PathVariable String tablero, @RequestParam MultiValueMap<String, String> parametros) {
        return medir("consulta", null, () -> ParametrosConsulta.leer(tablero, parametros), q -> servicio.tablero(q, alcance()));
    }

    /**
     * Consulta estructurada (intención METRICAS: métricas por dimensiones con filtros, periodo y corte), la misma que usará el asistente
     * de IA (A6) y la consulta guiada. El cuerpo es un {@link ConsultaAnalitica} en JSON (enumeraciones por su nombre: GASTO, FUERZA…).
     */
    @PostMapping("/api/analitica/consulta")
    @Accion(modulo = "analitica", accion = "consulta_estructurada", operacion = Declarada.CONSULTA)
    public ResultadoTablero consulta(@RequestBody ConsultaAnalitica q) {
        return medir("consulta", "consulta estructurada", () -> q, x -> servicio.consultar(x, alcance()));
    }

    /** Descarga de lo visualizado (xlsx, csv o pdf) con todos los metadatos; mismo perfil, filtros y corte ⇒ misma huella de datos. */
    @GetMapping("/api/analitica/tableros/{tablero}/descarga")
    @Accion(modulo = "analitica", accion = "tablero_descarga", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> descarga(@PathVariable String tablero, @RequestParam MultiValueMap<String, String> parametros) {
        long t0 = System.nanoTime();
        ConsultaAnalitica q = null;
        String formato = null;
        ResultadoTablero r;
        ExportadorAnalitica.Descarga d;
        try {
            formato = ParametrosConsulta.formato(parametros.getFirst("formato"));
            q = ParametrosConsulta.leer(tablero, parametros);
            // Presupuesto de exportación (≤ 10 min, A5 2.7)
            r = servicio.tableroParaDescarga(q, alcance());
            UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
            d = exportador.exportar(r, formato, u.nombre() + " (" + u.usuario() + ")", perfiles.nombres(), OffsetDateTime.now(reloj));
        } catch (RuntimeException e) {
            registrarFalla("descarga", q, formato, t0, e);
            throw e;
        }
        bitacora.registrar(new BitacoraAnalitica.Entrada("descarga", q, ParametrosConsulta.objeto(q, formato) + " · archivo " + d.sha256().substring(0, 12),
                r.metadatos() == null ? null : r.metadatos().corteCargueId(), formato, r.metadatos() == null ? null : r.metadatos().registros(),
                r.huella(), ms(t0), "ok", r.advertencias(), r.metadatos() == null ? null : r.metadatos().alcance()));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, d.tipo())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.nombre() + "\"").header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Huella-Datos", r.huella()).body(d.datos());
    }

    /**
     * Rastreo de una cifra: desglose y registros de origen seudonimizados (página {@code pagina}). Solo con la pantalla de trazabilidad y un
     * perfil autorizado. La bitácora se escribe ANTES de entregar (si no se puede, 503 sin datos).
     */
    @GetMapping("/api/analitica/traza/{tablero}")
    @PreAuthorize(TRAZA)
    @Accion(modulo = "analitica", accion = "traza", operacion = Declarada.CONSULTA)
    public ServicioTrazaAnalitica.Traza rastrear(@PathVariable String tablero, @RequestParam MultiValueMap<String, String> parametros) {
        long t0 = System.nanoTime();
        ConsultaAnalitica q = null;
        ServicioTrazaAnalitica.Traza t;
        String periodo = parametros.getFirst("periodo");
        try {
            q = ParametrosConsulta.leer(tablero, parametros);
            int pagina = parametros.getFirst("pagina") == null ? 1 : (int) Math.max(1, Math.min(Integer.MAX_VALUE, parseo(parametros.getFirst("pagina"))));
            Long despues = parametros.getFirst("despues") == null ? null : parseo(parametros.getFirst("despues"));
            t = traza.traza(q, periodo, pagina, despues, alcance());
        } catch (RuntimeException e) {
            registrarFalla("traza", q, null, t0, e);
            throw e;
        }
        Object corte = t.desglose().get("corteCargueId");
        bitacora.registrarAntes(new BitacoraAnalitica.Entrada("traza", q, ParametrosConsulta.objeto(q, "rastreo" + (periodo == null ? ""
                : " periodo " + ParametrosConsulta.periodoSeguro(periodo)) + " · página " + t.pagina()), corte instanceof Long l ? l : null, null,
                t.total(), t.huella(), ms(t0), "ok", List.of(), (String) t.desglose().get("alcance")));
        return t;
    }

    /**
     * CSV de los registros de origen de la cifra, en flujo (fetchSize) dentro de la transacción de lectura. Más del máximo responde 422
     * antes del primer byte. La descarga se registra en la bitácora antes del primer byte (falla cerrado); si algo falla a mitad, el
     * archivo termina con la línea «ARCHIVO INCOMPLETO» (sin la línea final «FIN») y la bitácora queda con resultado «error».
     */
    @GetMapping("/api/analitica/traza/{tablero}/registros.csv")
    @PreAuthorize(TRAZA)
    @Accion(modulo = "analitica", accion = "traza_csv", operacion = Declarada.CONSULTA)
    public void rastrearCsv(@PathVariable String tablero, @RequestParam MultiValueMap<String, String> parametros, HttpServletResponse respuesta)
            throws IOException {
        long t0 = System.nanoTime();
        ConsultaAnalitica q = null;
        String periodo = parametros.getFirst("periodo");
        Map<String, Object>[] registrado = new Map[1];
        try {
            q = ParametrosConsulta.leer(tablero, parametros);
            ConsultaAnalitica consulta = q;
            UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
            List<String[]> enc = new ArrayList<>();
            enc.add(new String[] {"Título", "Registros de origen · " + q.tablero().titulo()});
            enc.add(new String[] {"Fecha y hora de generación", co.com.agsamericas.digsa.reglas.AnaliticaConsulta.fechaHora(OffsetDateTime.now(reloj))});
            enc.add(new String[] {"Usuario", u.nombre() + " (" + u.usuario() + ")"});
            enc.add(new String[] {"Perfil", perfiles.nombres()});
            enc.add(new String[] {"Filtros", BitacoraAnalitica.resumenFiltros(q, true) + (periodo == null ? "" : "; periodo="
                    + ParametrosConsulta.periodoSeguro(periodo))});
            traza.csv(q, periodo, alcance(), enc, () -> {
                respuesta.setContentType("text/csv;charset=UTF-8");
                respuesta.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"registros_origen_" + consulta.tablero().codigo() + ".csv\"");
                respuesta.setHeader("X-Content-Type-Options", "nosniff");
                respuesta.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                try {
                    return respuesta.getOutputStream();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, d -> {
                // Antes del primer byte: si no se puede registrar, no sale nada (503)
                registrado[0] = d;
                Object corte = d.get("corteCargueId");
                bitacora.registrarAntes(new BitacoraAnalitica.Entrada("traza_descarga", consulta, ParametrosConsulta.objeto(consulta,
                        "registros de origen csv"), corte instanceof Long l ? l : null, "csv", (Long) d.get("registros"), null, ms(t0), "ok",
                        List.of(), (String) d.get("alcance")));
            });
        } catch (RuntimeException e) {
            if (registrado[0] == null && !respuesta.isCommitted()) {
                respuesta.reset();
            }
            registrarFalla("traza_descarga", q, "csv", t0, e);
            throw e;
        }
    }

    /** Bitácora de analítica (consultas, descargas y rastreos) para Administrador y Coordinador, sin datos personales; paginada por llave. */
    @GetMapping("/api/analitica/bitacora")
    @PreAuthorize("@permisos.pantalla('ANALITICA') and @permisosTableros.bitacora()")
    @Accion(modulo = "analitica", accion = "bitacora", operacion = Declarada.CONSULTA)
    public Map<String, Object> bitacora(@RequestParam(required = false) String usuario, @RequestParam(required = false) String accion,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                        @RequestParam(required = false) String antes) {
        if (accion != null && !Set.of("consulta", "descarga", "traza", "asistente").contains(accion)) {
            throw ErrorNegocio.validacion("La acción no es válida.");
        }
        return bitacora.listar(usuario, accion, desde, hasta, antes);
    }

    // ---------- Apoyo ----------

    private ResultadoTablero medir(String accion, String extra, java.util.function.Supplier<ConsultaAnalitica> leer,
                                   Function<ConsultaAnalitica, ResultadoTablero> f) {
        long t0 = System.nanoTime();
        ConsultaAnalitica q = null;
        ResultadoTablero r;
        try {
            q = leer.get();
            r = f.apply(q);
        } catch (RuntimeException e) {
            registrarFalla(accion, q, null, t0, e);
            throw e;
        }
        bitacora.registrar(new BitacoraAnalitica.Entrada(accion, q, ParametrosConsulta.objeto(q, extra), r.metadatos() == null ? null
                : r.metadatos().corteCargueId(), null, r.metadatos() == null ? null : r.metadatos().registros(), r.huella(), ms(t0), "ok",
                r.advertencias(), r.metadatos() == null ? null : r.metadatos().alcance()));
        return r;
    }

    /**
     * Fila de una falla: «tiempo_agotado», «rechazada» (validación, 4xx) o «error» (falla técnica). Sin el mensaje (puede repetir el valor
     * que escribió el usuario) ni el texto técnico: solo la causa genérica y, si la hay, la variable.
     */
    private void registrarFalla(String accion, ConsultaAnalitica q, String formato, long t0, RuntimeException e) {
        String resultado;
        String advertencia;
        if (e instanceof ErrorNegocio n && Boolean.TRUE.equals(n.datos().get("tiempoAgotado"))) {
            resultado = "tiempo_agotado";
            advertencia = "Tiempo agotado";
        } else if (e instanceof ErrorNegocio n && n.estado().is4xxClientError()) {
            resultado = "rechazada";
            Object v = n.datos().get("variable");
            advertencia = v == null ? "Consulta rechazada por validación" : "Filtro rechazado: " + v + " (valor inexistente o fuera del alcance)";
        } else {
            resultado = "error";
            advertencia = "Error técnico (" + e.getClass().getSimpleName() + ")";
        }
        bitacora.registrar(new BitacoraAnalitica.Entrada(accion, q, ParametrosConsulta.objeto(q, null), null, formato, null, null, ms(t0), resultado,
                List.of(advertencia), null));
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static long parseo(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            throw ErrorNegocio.validacion("La página no es válida.");
        }
    }
}
