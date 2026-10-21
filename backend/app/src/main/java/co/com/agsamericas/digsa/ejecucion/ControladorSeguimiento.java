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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Seguimiento al plan: soportes de la visita anterior (solo lectura), prórrogas por decidir y la tarea del recordatorio. */
@RestController
public class ControladorSeguimiento {

    private final ServicioProrrogas prorrogas;
    private final ServicioArchivos archivos;
    private final ServicioFormulario formulario;
    private final ServicioSeguimiento seguimiento;
    private final TareaRecordatorioSeguimiento recordatorio;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public ControladorSeguimiento(ServicioProrrogas prorrogas, ServicioArchivos archivos, ServicioFormulario formulario, ServicioSeguimiento seguimiento,
                                  TareaRecordatorioSeguimiento recordatorio, org.springframework.transaction.support.TransactionTemplate tx) {
        this.tx = tx;
        this.prorrogas = prorrogas;
        this.archivos = archivos;
        this.formulario = formulario;
        this.seguimiento = seguimiento;
        this.recordatorio = recordatorio;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    @GetMapping("/api/prorrogas")
    @PreAuthorize("@permisos.pantalla('PRORROGAS')")
    @Accion(modulo = "seguimiento", accion = "prorrogas", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> listar(@RequestParam(required = false) String estado) {
        return prorrogas.listar(estado, UsuarioSesion.perfilesActuales(), quien());
    }

    @PostMapping("/api/prorrogas/{id}/decision")
    @PreAuthorize("@permisos.pantalla('PRORROGAS')")
    @Accion(modulo = "seguimiento", accion = "prorroga_decidir", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> decidir(@PathVariable long id, @RequestBody ServicioProrrogas.Decision d) {
        return Map.of("mensaje", prorrogas.decidir(id, d, quien(), UsuarioSesion.perfilesActuales()));
    }

    /**
     * Soporte de la visita de origen, solo de lo que se verifica en este seguimiento y para quien tiene acceso al seguimiento
     * (decisión 6.6); cualquier otro archivo o usuario recibe 404.
     */
    @GetMapping("/api/visitas/{id}/anteriores/{archivoId}")
    @PreAuthorize("@permisos.pantalla('VISITA')")
    @Accion(modulo = "visita", accion = "archivo_anterior", operacion = Declarada.CONSULTA)
    public ResponseEntity<InputStreamResource> anterior(@PathVariable long id, @PathVariable long archivoId) {
        Acceso a;
        try {
            a = formulario.acceso(id, quien(), UsuarioSesion.perfilesActuales(), false);
        } catch (ErrorNegocio e) {
            throw ErrorNegocio.noEncontrado("No existe el archivo.");
        }
        ServicioArchivos.Archivo f = archivos.porId(archivoId).filter(x -> a.visita().esSeguimiento() && x.visitaId() == a.visita().origenId()
                && seguimiento.porVerificar(a.visita()).contains(x.clave())).orElseThrow(() -> ErrorNegocio.noEncontrado("No existe el archivo."));
        InputStream in;
        try {
            in = archivos.abrir(f);
        } catch (IOException e) {
            throw new ErrorNegocio(HttpStatus.NOT_FOUND, "El archivo no está en el almacén.");
        }
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.parseMediaType(f.tipo())).contentLength(f.tamano());
        ServicioArchivos.encabezados(f).forEach((k, v) -> {
            if (!v.isEmpty()) {
                b.header(k, v);
            }
        });
        return b.body(new InputStreamResource(in));
    }

    record Programar(String fecha) {
    }

    /** El coordinador programa el seguimiento que no se creó solo (situación «sin programar»). */
    @PostMapping("/api/visitas/{id}/seguimiento")
    @PreAuthorize("@permisos.pantalla('AGENDA')")
    @Accion(modulo = "seguimiento", accion = "programar", operacion = Declarada.INSERCION)
    public Map<String, Object> programar(@PathVariable long id, @RequestBody Programar p) {
        return seguimiento.programarManual(id, p.fecha(), quien(), tx);
    }

    /** Corre a mano el recordatorio a los auditores de los seguimientos próximos (administrador). */
    @PostMapping("/api/seguimiento/recordatorios")
    @PreAuthorize("hasAuthority('PERFIL_ADMINISTRADOR')")
    @Accion(modulo = "seguimiento", accion = "recordatorios", operacion = Declarada.ACTUALIZACION)
    public TareaRecordatorioSeguimiento.Resumen recordatorios() {
        return recordatorio.correr();
    }
}
