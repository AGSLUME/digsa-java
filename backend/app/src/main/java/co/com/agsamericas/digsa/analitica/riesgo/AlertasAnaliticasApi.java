package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.analitica.tableros.ExportadorAnalitica;
import co.com.agsamericas.digsa.analitica.tableros.PermisosTableros;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Tipo;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * «Alertas analíticas» (Pantallas.ANALITICA_ALERTAS; A5 14.7): consola con contexto y análisis técnico, validación humana, ejecución bajo
 * demanda, parámetros con traza, referencias cargables y descarga. Solo perfiles autorizados (Administrador, Coordinador, Analista, DIGSA
 * y Sanidad con su Fuerza); el ESM no la abre (403).
 */
@RestController
@PreAuthorize(AlertasAnaliticasApi.CONSOLA)
public class AlertasAnaliticasApi {

    static final String CONSOLA = "@permisos.pantalla('ANALITICA_ALERTAS') and @permisosRiesgo.consola()";

    private final ServicioAlertasAnaliticas servicio;
    private final ExportadorAnalitica exportador;
    private final PerfilesOperacion perfiles;
    private final PermisosRiesgo permisos;
    private final PermisosTableros permisosTableros;
    private final RegistroBitacora bitacora;
    private final Clock reloj;

    AlertasAnaliticasApi(ServicioAlertasAnaliticas servicio, ExportadorAnalitica exportador, PerfilesOperacion perfiles, PermisosRiesgo permisos,
                         PermisosTableros permisosTableros, RegistroBitacora bitacora, Clock reloj) {
        this.servicio = servicio;
        this.exportador = exportador;
        this.perfiles = perfiles;
        this.permisos = permisos;
        this.permisosTableros = permisosTableros;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    /** Lo que la consola necesita para armarse: tipos, niveles y qué puede hacer el usuario. */
    @GetMapping("/api/analitica/alertas/catalogo")
    @Accion(modulo = "analitica", accion = "alertas_catalogo", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> catalogo() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> tipos = new ArrayList<>();
        for (Tipo t : Tipo.values()) {
            tipos.add(Map.of("codigo", t.codigo(), "nombre", t.nombre(), "unidad", t.unidad()));
        }
        m.put("tipos", tipos);
        m.put("niveles", AnaliticaAlertas.NIVELES);
        // Cohortes activas: filtro «cohorte» de la consola (el tablero 10 enlaza con él)
        m.put("cohortes", servicio.cohortesConAlertas());
        m.put("puedeValidar", permisos.validar());
        m.put("puedeParametrizar", permisos.parametrizar());
        m.put("puedeEjecutar", permisos.ejecutar());
        m.put("puedeRastrear", permisosTableros.traza());
        return m;
    }

    @GetMapping("/api/analitica/alertas")
    @Accion(modulo = "analitica", accion = "alertas_lista", operacion = Declarada.CONSULTA)
    public Map<String, Object> listar(@RequestParam Map<String, String> parametros) {
        int pagina = 1;
        if (parametros.get("pagina") != null) {
            try {
                pagina = Math.max(1, Integer.parseInt(parametros.get("pagina").strip()));
            } catch (NumberFormatException e) {
                throw ErrorNegocio.validacion("La página no es válida.");
            }
        }
        Map<String, Object> r = servicio.listar(parametros, pagina, UsuarioSesion.alcanceActual());
        bitacora.registrar("analitica_alerta_consulta", "Consola de alertas: " + r.get("total") + " alertas · página " + r.get("pagina"), quien().id(),
                quien().usuario());
        return r;
    }

    @GetMapping("/api/analitica/alertas/{id:\\d+}")
    @Accion(modulo = "analitica", accion = "alerta_detalle", operacion = Declarada.CONSULTA)
    public Map<String, Object> detalle(@PathVariable long id) {
        Map<String, Object> r = new LinkedHashMap<>(servicio.detalle(id, UsuarioSesion.alcanceActual()));
        r.put("puedeValidar", permisos.validar());
        r.put("puedeRastrear", permisosTableros.traza());
        bitacora.registrar("analitica_alerta_consulta", "Alerta analítica " + id, quien().id(), quien().usuario());
        return r;
    }

    /** Validación humana: {estado, nota}. Validar o descartar exige nota (≥ 10 caracteres). */
    @PostMapping("/api/analitica/alertas/{id:\\d+}/estado")
    @PreAuthorize(CONSOLA + " and @permisosRiesgo.validar()")
    @Accion(modulo = "analitica", accion = "alerta_estado", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> estado(@PathVariable long id, @RequestBody Map<String, Object> cuerpo) {
        return servicio.cambiarEstado(id, cuerpo.get("estado") == null ? null : String.valueOf(cuerpo.get("estado")),
                cuerpo.get("nota") == null ? null : String.valueOf(cuerpo.get("nota")), quien(), UsuarioSesion.alcanceActual());
    }

    /** Ejecuta el motor bajo demanda sobre el corte vigente (202: corre en segundo plano; las ejecuciones dicen cuándo termina). */
    @PostMapping("/api/analitica/alertas/ejecutar")
    @PreAuthorize(CONSOLA + " and @permisosRiesgo.ejecutar()")
    @Accion(modulo = "analitica", accion = "alertas_ejecutar", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> ejecutar() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(servicio.ejecutar(quien()));
    }

    @GetMapping("/api/analitica/alertas/ejecuciones")
    @Accion(modulo = "analitica", accion = "alertas_ejecuciones", operacion = Declarada.SIN_REGISTRO)
    public List<Map<String, Object>> ejecuciones() {
        return servicio.ejecuciones();
    }

    @GetMapping("/api/analitica/alertas/parametros")
    @Accion(modulo = "analitica", accion = "alertas_parametros", operacion = Declarada.SIN_REGISTRO)
    public List<Map<String, Object>> parametros() {
        return servicio.parametros();
    }

    /** Nueva versión de los parámetros de un tipo, con traza (usuario, fecha, antes y después). */
    @PutMapping("/api/analitica/alertas/parametros/{tipo}")
    @PreAuthorize(CONSOLA + " and @permisosRiesgo.parametrizar()")
    @Accion(modulo = "analitica", accion = "alertas_parametro_guardar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> guardarParametros(@PathVariable String tipo, @RequestBody Map<String, Object> cuerpo) {
        return servicio.guardarParametros(tipo, cuerpo, quien());
    }

    @GetMapping("/api/analitica/alertas/parametros/{tipo}/historial")
    @Accion(modulo = "analitica", accion = "alertas_parametro_historial", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> historialParametros(@PathVariable String tipo) {
        return servicio.historialParametros(tipo);
    }

    @GetMapping("/api/analitica/alertas/referencias")
    @Accion(modulo = "analitica", accion = "alertas_referencias", operacion = Declarada.SIN_REGISTRO)
    public List<Map<String, Object>> referencias() {
        return servicio.referencias();
    }

    /** Carga por archivo de una referencia (interacciones, indicaciones o huérfanas) con su fuente; la nueva versión queda vigente. */
    @PostMapping(value = "/api/analitica/alertas/referencias/{lista}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(CONSOLA + " and @permisosRiesgo.parametrizar()")
    @Accion(modulo = "analitica", accion = "alertas_referencia_cargar", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> cargarReferencia(@PathVariable String lista, @RequestPart(name = "archivo", required = false) MultipartFile archivo,
                                                                @RequestParam(required = false) String fuente, @RequestParam(required = false) String fechaFuente,
                                                                @RequestParam(defaultValue = "false") boolean ficticio) throws IOException {
        LocalDate fecha = null;
        if (fechaFuente != null && !fechaFuente.isBlank()) {
            try {
                fecha = LocalDate.parse(fechaFuente.strip());
            } catch (DateTimeParseException e) {
                throw ErrorNegocio.validacion("Fecha de la fuente no válida (aaaa-mm-dd).");
            }
        }
        if (archivo != null && archivo.getSize() > 20L * 1024 * 1024) {
            throw ErrorNegocio.validacion("El archivo de referencia pasa de 20 MB.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.cargarReferencia(lista, archivo == null ? null : archivo.getBytes(), fuente, fecha,
                ficticio, quien()));
    }

    /** Descarga de la consola con los filtros (Excel, CSV o PDF) y los metadatos de 14.8. Sin datos personales. */
    @GetMapping("/api/analitica/alertas/descarga")
    @Accion(modulo = "analitica", accion = "alertas_descarga", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> descarga(@RequestParam Map<String, String> parametros) {
        String formato = parametros.getOrDefault("formato", "");
        ResultadoTablero r = servicio.paraDescarga(parametros, UsuarioSesion.alcanceActual());
        UsuarioSesion u = quien();
        ExportadorAnalitica.Descarga d = exportador.exportar(r, formato, u.nombre() + " (" + u.usuario() + ")", perfiles.nombres(), OffsetDateTime.now(reloj));
        bitacora.registrar("analitica_alerta_descarga", "Consola de alertas · " + formato + " · " + r.tablas().getFirst().filas().size() + " alertas", u.id(),
                u.usuario());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, d.tipo()).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.nombre() + "\"")
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").body(d.datos());
    }
}
