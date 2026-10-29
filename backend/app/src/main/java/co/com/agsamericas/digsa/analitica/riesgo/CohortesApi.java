package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.analitica.tableros.ExportadorAnalitica;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Pantallas;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Tablero 10 «Cohortes y riesgo» y tablero 11 «Comportamientos atípicos» dentro de la pantalla ANALITICA (analitica-cohortes-alertas):
 * tarjetas con conteos agregados y criterio visible, detalle con serie y comparación, composición, descargas, gestión de cohortes
 * (Administrador y Analista: crear, editar criterios ⇒ versión nueva, activar) y recálculo bajo demanda. Todo en el alcance del usuario y
 * siempre agregado.
 */
@RestController
@PreAuthorize("@permisos.pantalla('ANALITICA')")
public class CohortesApi {

    private static final String EDITA = "@permisos.pantalla('ANALITICA') and @permisosRiesgo.editarCohortes()";

    private final ServicioCohortes cohortes;
    private final ServicioAlertasAnaliticas alertas;
    private final ExportadorAnalitica exportador;
    private final PerfilesOperacion perfiles;
    private final PermisosRiesgo permisos;
    private final RegistroBitacora bitacora;
    private final Clock reloj;

    CohortesApi(ServicioCohortes cohortes, ServicioAlertasAnaliticas alertas, ExportadorAnalitica exportador, PerfilesOperacion perfiles, PermisosRiesgo permisos,
                RegistroBitacora bitacora, Clock reloj) {
        this.cohortes = cohortes;
        this.alertas = alertas;
        this.exportador = exportador;
        this.perfiles = perfiles;
        this.permisos = permisos;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    private static Alcance alcance() {
        return UsuarioSesion.alcanceActual();
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    /** Solo el alcance nacional elige una Fuerza para el nivel; el código va validado. */
    private static String fuerza(String f) {
        if (f == null || f.isBlank()) {
            return null;
        }
        if (!(alcance() instanceof Alcance.Todo)) {
            return null;
        }
        if (!f.strip().matches("^[A-Z]{2,5}$")) {
            throw ErrorNegocio.validacion("Fuerza no válida.");
        }
        return f.strip();
    }

    @GetMapping("/api/analitica/cohortes")
    @Accion(modulo = "analitica", accion = "cohortes_tarjetas", operacion = Declarada.CONSULTA)
    public Map<String, Object> tarjetas(@RequestParam(required = false) Long corte,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                        @RequestParam(required = false) String fuerza) {
        Map<String, Object> r = new LinkedHashMap<>(cohortes.tarjetas(corte, desde, hasta, fuerza(fuerza), alcance()));
        r.put("puedeEditar", permisos.editarCohortes());
        r.put("puedeRecalcular", permisos.recalcularCohortes());
        r.put("puedeVerConsola", permisos.consola());
        bitacora.registrar("analitica_cohorte_consulta", "Tarjetas de cohortes · corte " + r.get("corte") + " · " + r.get("periodo"), quien().id(),
                quien().usuario());
        return r;
    }

    @GetMapping("/api/analitica/cohortes/{id:\\d+}")
    @Accion(modulo = "analitica", accion = "cohorte_detalle", operacion = Declarada.CONSULTA)
    public Map<String, Object> detalle(@PathVariable long id, @RequestParam(required = false) Integer version, @RequestParam(required = false) Long corte,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                       @RequestParam(required = false) String fuerza) {
        Map<String, Object> r = cohortes.detalle(id, version, corte, desde, hasta, fuerza(fuerza), alcance());
        bitacora.registrar("analitica_cohorte_consulta", "Cohorte " + id + " · v" + r.get("version") + " · corte " + r.get("corte"), quien().id(),
                quien().usuario());
        return r;
    }

    @GetMapping("/api/analitica/cohortes/{id:\\d+}/composicion")
    @Accion(modulo = "analitica", accion = "cohorte_composicion", operacion = Declarada.CONSULTA)
    public Map<String, Object> composicion(@PathVariable long id, @RequestParam(required = false) String por, @RequestParam(required = false) Integer version,
                                           @RequestParam(required = false) Long corte,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                           @RequestParam(required = false) String fuerza) {
        Map<String, Object> r = cohortes.composicion(id, por, version, corte, desde, hasta, fuerza(fuerza), alcance());
        bitacora.registrar("analitica_cohorte_consulta", "Composición de la cohorte " + id + " por " + r.get("por") + " · corte " + r.get("corte"), quien().id(),
                quien().usuario());
        return r;
    }

    /** Serie o composición en Excel, CSV o PDF con los metadatos de 14.8, el criterio y la versión (solo cifras agregadas). */
    @GetMapping("/api/analitica/cohortes/{id:\\d+}/descarga")
    @Accion(modulo = "analitica", accion = "cohorte_descarga", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> descarga(@PathVariable long id, @RequestParam String formato, @RequestParam(defaultValue = "serie") String vista,
                                           @RequestParam(required = false) String por, @RequestParam(required = false) Integer version,
                                           @RequestParam(required = false) Long corte,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                           @RequestParam(required = false) String fuerza) {
        if (!List.of("serie", "composicion").contains(vista)) {
            throw ErrorNegocio.validacion("Vista no válida: serie o composicion.");
        }
        ResultadoTablero r = cohortes.paraDescarga(id, vista, por, version, corte, desde, hasta, fuerza(fuerza), alcance());
        UsuarioSesion u = quien();
        ExportadorAnalitica.Descarga d = exportador.exportar(r, formato, u.nombre() + " (" + u.usuario() + ")", perfiles.nombres(), OffsetDateTime.now(reloj));
        bitacora.registrar("analitica_cohorte_descarga", "Cohorte " + id + " · " + vista + " · " + formato + " · corte " + r.metadatos().corteCargueId()
                + " · huella " + r.huella().substring(0, 12), u.id(), u.usuario());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, d.tipo()).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.nombre() + "\"")
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Huella-Datos", r.huella()).body(d.datos());
    }

    @PostMapping("/api/analitica/cohortes")
    @PreAuthorize(EDITA)
    @Accion(modulo = "analitica", accion = "cohorte_crear", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> crear(@RequestBody Map<String, Object> cuerpo) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cohortes.crear(cuerpo, quien()));
    }

    @PutMapping("/api/analitica/cohortes/{id:\\d+}")
    @PreAuthorize(EDITA)
    @Accion(modulo = "analitica", accion = "cohorte_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editar(@PathVariable long id, @RequestBody Map<String, Object> cuerpo) {
        return cohortes.editar(id, cuerpo, quien());
    }

    @PostMapping("/api/analitica/cohortes/{id:\\d+}/recalcular")
    @PreAuthorize("@permisos.pantalla('ANALITICA') and @permisosRiesgo.recalcularCohortes()")
    @Accion(modulo = "analitica", accion = "cohorte_recalcular", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> recalcular(@PathVariable long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(cohortes.recalcular(id, quien()));
    }

    @GetMapping("/api/analitica/cohortes/ejecuciones")
    @Accion(modulo = "analitica", accion = "cohortes_ejecuciones", operacion = Declarada.SIN_REGISTRO)
    public List<Map<String, Object>> ejecuciones() {
        return cohortes.ejecuciones();
    }

    /** Tablero 11: atípicos agregados en el alcance (el ESM ve solo conteos de su establecimiento, sin lista de alertas). */
    @GetMapping("/api/analitica/atipicos")
    @Accion(modulo = "analitica", accion = "atipicos", operacion = Declarada.CONSULTA)
    public Map<String, Object> atipicos(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                        @RequestParam(required = false) String fuerza, @RequestParam(required = false) String esm,
                                        @RequestParam(required = false) String atc, @RequestParam(required = false) Long corte) {
        Map<String, Object> r = new LinkedHashMap<>(alertas.atipicos(desde, hasta, fuerza, esm, atc, corte, alcance()));
        r.put("puedeVerConsola", permisos.consola());
        bitacora.registrar("analitica_alerta_consulta", "Atípicos (tablero 11): " + r.get("total") + " alertas en el alcance", quien().id(), quien().usuario());
        return r;
    }
}
