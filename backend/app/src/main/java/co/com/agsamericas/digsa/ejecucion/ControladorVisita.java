package co.com.agsamericas.digsa.ejecucion;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Formulario de la visita en ejecución, evidencias, firmas, acta y reapertura: equipo de la visita y coordinador. */
@RestController
@PreAuthorize("@permisos.pantalla('VISITA')")
public class ControladorVisita {

    record Respuestas(List<ServicioFormulario.Respuesta> respuestas) {
    }

    record Personal(List<ServicioFormulario.Persona> personas) {
    }

    record Reapertura(String motivo, String descripcion) {
    }

    private final ServicioFormulario formulario;
    private final ServicioArchivos archivos;
    private final ServicioActa acta;

    public ControladorVisita(ServicioFormulario formulario, ServicioArchivos archivos, ServicioActa acta) {
        this.formulario = formulario;
        this.archivos = archivos;
        this.acta = acta;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    @GetMapping("/api/visitas/{id}/formulario")
    @Accion(modulo = "visita", accion = "formulario", operacion = Declarada.CONSULTA)
    public Map<String, Object> formulario(@PathVariable long id) {
        return formulario.formulario(id, quien(), UsuarioSesion.perfilesActuales());
    }

    @PutMapping("/api/visitas/{id}/respuestas")
    @Accion(modulo = "visita", accion = "respuestas", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> respuestas(@PathVariable long id, @RequestBody Respuestas r) {
        return formulario.guardarRespuestas(id, r.respuestas(), quien(), UsuarioSesion.perfilesActuales());
    }

    @PutMapping("/api/visitas/{id}/ejecucion")
    @Accion(modulo = "visita", accion = "ejecucion", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> ejecucion(@PathVariable long id, @RequestBody ServicioFormulario.Ejecucion e) {
        formulario.guardarEjecucion(id, e, quien(), UsuarioSesion.perfilesActuales());
        return Map.of("mensaje", "Datos de la visita guardados.");
    }

    @PutMapping("/api/visitas/{id}/personal")
    @Accion(modulo = "visita", accion = "personal", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> personal(@PathVariable long id, @RequestBody Personal p) {
        formulario.guardarPersonal(id, p.personas(), quien(), UsuarioSesion.perfilesActuales());
        return Map.of("mensaje", "Personal auditado guardado.");
    }

    @PostMapping("/api/visitas/{id}/firmas")
    @Accion(modulo = "visita", accion = "firmar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> firmar(@PathVariable long id, @RequestBody ServicioFormulario.Firma f) {
        formulario.firmar(id, f, quien(), UsuarioSesion.perfilesActuales());
        return Map.of("mensaje", "Firma registrada.");
    }

    /** Varios archivos en una sola petición (campo «archivos»), todos o ninguno. */
    @PostMapping(value = "/api/visitas/{id}/archivos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Accion(modulo = "visita", accion = "archivos_subir", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> subir(@PathVariable long id, @RequestParam String clave,
                                                     @RequestPart("archivos") List<MultipartFile> lista) {
        List<ServicioArchivos.Archivo> r = archivos.subir(id, clave, lista, quien(), UsuarioSesion.perfilesActuales());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("mensaje", r.size() + " archivo(s) cargado(s).",
                "archivos", r.stream().map(a -> Map.of("id", a.id(), "nombre", a.nombre(), "tipo", a.tipo(), "tamano", a.tamano(), "sha256", a.sha256())).toList()));
    }

    @DeleteMapping("/api/archivos/{archivoId}")
    @Accion(modulo = "visita", accion = "archivo_quitar", operacion = Declarada.ELIMINACION)
    public Map<String, Object> quitar(@PathVariable long archivoId) {
        archivos.quitar(archivoId, quien(), UsuarioSesion.perfilesActuales());
        return Map.of("mensaje", "Soporte quitado.");
    }

    @GetMapping("/api/archivos/{archivoId}")
    @Accion(modulo = "visita", accion = "archivo_ver", operacion = Declarada.CONSULTA)
    public ResponseEntity<InputStreamResource> descargar(@PathVariable long archivoId) {
        ServicioArchivos.Archivo a = archivos.paraDescargar(archivoId, quien(), UsuarioSesion.perfilesActuales());
        InputStream in;
        try {
            in = archivos.abrir(a);
        } catch (IOException e) {
            throw new ErrorNegocio(HttpStatus.NOT_FOUND, "El archivo no está en el almacén.");
        }
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.parseMediaType(a.tipo())).contentLength(a.tamano());
        ServicioArchivos.encabezados(a).forEach((k, v) -> {
            if (!v.isEmpty()) {
                b.header(k, v);
            }
        });
        return b.body(new InputStreamResource(in));
    }

    @PostMapping("/api/visitas/{id}/acta")
    @Accion(modulo = "visita", accion = "acta_enviar", operacion = Declarada.ACTUALIZACION)
    public ServicioActa.Envio enviarActa(@PathVariable long id) {
        return acta.enviar(id, quien(), UsuarioSesion.perfilesActuales());
    }

    /** Anexo 5, prueba 10: reabrir desde la interfaz (coordinador o administrador). */
    @PostMapping("/api/visitas/{id}/reabrir")
    @PreAuthorize("@permisos.pantalla('AGENDA')")
    @Accion(modulo = "visita", accion = "reabrir", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> reabrir(@PathVariable long id, @RequestBody Reapertura r) {
        acta.reabrir(id, r.motivo(), r.descripcion(), quien());
        return Map.of("mensaje", "Visita reabierta: queda en ejecución para corregirla.");
    }
}
