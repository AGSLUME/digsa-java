package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Perfil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * Módulo de presupuesto (Anexo 5 §4.6). Consultan, cada uno en su alcance, DIGSA, Coordinador, Administrador, Analista, Sanidad y ESM
 * (pantalla PRESUPUESTO); registran solo la interventoría: Coordinador y Administrador (duda P.8). Auditor y OPL: 403.
 */
@RestController
public class ControladorPresupuesto {

    static final String REGISTRAN = "hasAnyAuthority('PERFIL_COORDINADOR', 'PERFIL_ADMINISTRADOR')";
    static final String CONSULTAN = "@permisos.pantalla('PRESUPUESTO')";

    record Valor(String valor) {
    }

    record Centralizador(Long centralizadorId) {
    }

    private final ServicioPresupuesto presupuesto;
    private final ServicioNovedades novedades;
    private final ServicioTablero tablero;
    private final ObjectMapper json;

    public ControladorPresupuesto(ServicioPresupuesto presupuesto, ServicioNovedades novedades, ServicioTablero tablero, ObjectMapper json) {
        this.presupuesto = presupuesto;
        this.novedades = novedades;
        this.tablero = tablero;
        this.json = json;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    static Ambito ambito() {
        return Ambito.de(UsuarioSesion.alcanceActual(), UsuarioSesion.perfilesActuales());
    }

    static boolean puedeRegistrar() {
        Set<Perfil> p = UsuarioSesion.perfilesActuales();
        return p.contains(Perfil.COORDINADOR) || p.contains(Perfil.ADMINISTRADOR);
    }

    // ---------- Consulta ----------

    @GetMapping("/api/presupuesto/opciones")
    @PreAuthorize(CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "opciones", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> opciones() {
        return presupuesto.opciones(ambito(), puedeRegistrar());
    }

    @GetMapping("/api/presupuesto/tablero")
    @PreAuthorize(CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "tablero", operacion = Declarada.CONSULTA)
    public Map<String, Object> tablero(@RequestParam(required = false) Long vigencia, @RequestParam(required = false) String corte,
                                       @RequestParam(required = false) String fuerza, @RequestParam(required = false) Long punto,
                                       @RequestParam(required = false) String modalidad) {
        return tablero.tablero(new ServicioTablero.Filtros(vigencia, corte, fuerza, punto, modalidad), ambito());
    }

    @GetMapping("/api/presupuesto/lineas")
    @PreAuthorize(CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "lineas", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> lineas(@RequestParam(required = false) Long vigencia, @RequestParam(required = false) String fuerza,
                                            @RequestParam(required = false) Long punto, @RequestParam(required = false) String modalidad) {
        return presupuesto.lineas(new ServicioPresupuesto.Filtros(vigencia, null, fuerza, punto, modalidad, null, null, null), ambito());
    }

    @GetMapping("/api/presupuesto/movimientos")
    @PreAuthorize(CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "movimientos", operacion = Declarada.CONSULTA)
    public Map<String, Object> movimientos(@RequestParam(required = false) Long vigencia, @RequestParam(required = false) String tipo,
                                           @RequestParam(required = false) String fuerza, @RequestParam(required = false) Long punto,
                                           @RequestParam(required = false) String modalidad, @RequestParam(required = false) String desde,
                                           @RequestParam(required = false) String hasta, @RequestParam(required = false) Integer pagina) {
        return presupuesto.movimientos(new ServicioPresupuesto.Filtros(vigencia, tipo, fuerza, punto, modalidad, desde, hasta, pagina), ambito());
    }

    @GetMapping("/api/presupuesto/soportes/{id}")
    @PreAuthorize(CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "soporte", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> soporte(@PathVariable long id) {
        ServicioPresupuesto.ArchivoSoporte a = presupuesto.soporte(id, ambito());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(a.nombre(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CONTENT_TYPE, a.tipo()).header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(a.contenido());
    }

    // ---------- Registro (interventoría) ----------

    @PostMapping("/api/presupuesto/novedades")
    @PreAuthorize(REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "novedad", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> novedad(@RequestParam("datos") String datos, @RequestPart(name = "archivo", required = false) MultipartFile archivo) {
        ServicioNovedades.Novedad n;
        try {
            n = json.readValue(datos, ServicioNovedades.Novedad.class);
        } catch (JsonProcessingException e) {
            throw ErrorNegocio.validacion("Los datos de la novedad no son válidos.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(novedades.registrar(n, archivo, quien()));
    }

    @PostMapping("/api/presupuesto/asignacion/simular")
    @PreAuthorize(REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "asignacion_simular", operacion = Declarada.CONSULTA)
    public Map<String, Object> simularAsignacion(@RequestPart("archivo") MultipartFile archivo) {
        return novedades.simularAsignacion(archivo, quien());
    }

    @PostMapping("/api/presupuesto/asignacion/aplicar")
    @PreAuthorize(REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "asignacion_aplicar", operacion = Declarada.INSERCION)
    public Map<String, Object> aplicarAsignacion(@RequestPart("archivo") MultipartFile archivo, @RequestParam("soporte") String soporte,
                                                 @RequestParam(name = "fuente", required = false) String fuente) {
        return novedades.aplicarAsignacion(archivo, soporte, fuente, quien());
    }

    @PostMapping("/api/presupuesto/vigencias")
    @PreAuthorize(REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "vigencia_crear", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> crearVigencia(@RequestBody ServicioPresupuesto.VigenciaEntrada v) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", presupuesto.crearVigencia(v, quien()), "mensaje", "Vigencia creada."));
    }

    @PutMapping("/api/presupuesto/vigencias/{id}")
    @PreAuthorize(REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "vigencia_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarVigencia(@PathVariable long id, @RequestBody ServicioPresupuesto.VigenciaEntrada v) {
        presupuesto.editarVigencia(id, v, quien());
        return Map.of("mensaje", "Vigencia actualizada.");
    }

    @PostMapping("/api/presupuesto/modalidades")
    @PreAuthorize(REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "modalidad_crear", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> crearModalidad(@RequestBody ServicioPresupuesto.ModalidadEntrada m) {
        presupuesto.crearModalidad(m, quien());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("mensaje", "Modalidad creada."));
    }

    @PutMapping("/api/presupuesto/modalidades/{codigo}")
    @PreAuthorize(REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "modalidad_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarModalidad(@PathVariable String codigo, @RequestBody ServicioPresupuesto.ModalidadEntrada m) {
        presupuesto.editarModalidad(codigo, m, quien());
        return Map.of("mensaje", "Modalidad actualizada.");
    }

    @PutMapping("/api/presupuesto/puntos/{id}/centralizador")
    @PreAuthorize(REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "centralizador", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> centralizador(@PathVariable long id, @RequestBody Centralizador c) {
        presupuesto.centralizador(id, c == null ? null : c.centralizadorId(), quien());
        return Map.of("mensaje", "Centralizador actualizado.");
    }

    @PutMapping("/api/presupuesto/parametros/{clave}")
    @PreAuthorize(REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "parametro", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> parametro(@PathVariable String clave, @RequestBody Valor v) {
        presupuesto.editarParametro(clave, v == null ? null : v.valor(), quien());
        return Map.of("mensaje", "Parámetro actualizado.");
    }
}
