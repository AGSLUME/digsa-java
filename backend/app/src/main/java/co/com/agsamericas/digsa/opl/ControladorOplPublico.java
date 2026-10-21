package co.com.agsamericas.digsa.opl;

import co.com.agsamericas.digsa.ejecucion.ServicioArchivos;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Enlace del acta (QR) sin usuario: abrir, pedir y verificar el código, y responder. Los POST exigen CSRF como el resto de la API. Los
 * eventos los escribe el servicio con la IP y el correo enmascarado (por eso las acciones no dejan su propia fila).
 */
@RestController
@RequestMapping("/api/opl-publico")
public class ControladorOplPublico {

    record PedirCodigo(int indice) {
    }

    record Verificar(String codigo) {
    }

    record Plan(List<ServicioOpl.FilaOpl> filas) {
    }

    private final ServicioOplPublico publico;
    private final ServicioOpl opl;

    public ControladorOplPublico(ServicioOplPublico publico, ServicioOpl opl) {
        this.publico = publico;
        this.opl = opl;
    }

    @GetMapping("/enlace")
    @Accion(modulo = "opl", accion = "enlace", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> enlace(@RequestParam("t") String token, HttpServletRequest req) {
        return publico.abrir(token, req);
    }

    @PostMapping("/codigo")
    @Accion(modulo = "opl", accion = "codigo", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> codigo(@RequestBody PedirCodigo p, HttpServletRequest req) {
        return Map.of("mensaje", publico.enviarCodigo(p.indice(), req));
    }

    @PostMapping("/verificar")
    @Accion(modulo = "opl", accion = "verificar", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> verificar(@RequestBody Verificar v, HttpServletRequest req) {
        return Map.of("mensaje", publico.verificar(v.codigo(), req));
    }

    @GetMapping("/detalle")
    @Accion(modulo = "opl", accion = "detalle", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> detalle(HttpServletRequest req) {
        return opl.detalle(publico.verificadoDe(req).acceso());
    }

    @PostMapping("/respuestas")
    @Accion(modulo = "opl", accion = "responder", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> responder(@RequestBody ServicioOpl.Respuesta r, HttpServletRequest req) {
        ServicioOplPublico.Verificado v = publico.verificadoDe(req);
        return Map.of("mensaje", opl.responder(v.acceso(), r, v.actor()));
    }

    @PostMapping(value = "/soportes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Accion(modulo = "opl", accion = "soportes", operacion = Declarada.SIN_REGISTRO)
    public ResponseEntity<Map<String, Object>> soportes(@RequestParam String clave, @RequestPart("archivos") List<MultipartFile> archivos, HttpServletRequest req) {
        ServicioOplPublico.Verificado v = publico.verificadoDe(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(opl.subirSoportes(v.acceso(), clave, archivos, v.actor()));
    }

    @PutMapping("/plan")
    @Accion(modulo = "opl", accion = "plan", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> plan(@RequestBody Plan p, HttpServletRequest req) {
        ServicioOplPublico.Verificado v = publico.verificadoDe(req);
        return opl.guardarPlan(v.acceso(), p.filas(), v.actor());
    }

    @PostMapping("/firma")
    @Accion(modulo = "opl", accion = "firma", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> firma(@RequestBody ServicioOpl.FirmaPlan f, HttpServletRequest req) {
        ServicioOplPublico.Verificado v = publico.verificadoDe(req);
        return Map.of("mensaje", opl.firmar(v.acceso(), f, v.actor()));
    }

    @GetMapping("/acta")
    @Accion(modulo = "opl", accion = "acta", operacion = Declarada.SIN_REGISTRO)
    public ResponseEntity<InputStreamResource> acta(HttpServletRequest req) {
        ServicioOpl.Acceso a = publico.verificadoDe(req).acceso();
        return descarga(opl, opl.acta(a));
    }

    @GetMapping("/soportes/{id}")
    @Accion(modulo = "opl", accion = "soporte", operacion = Declarada.SIN_REGISTRO)
    public ResponseEntity<InputStreamResource> soporte(@PathVariable long id, HttpServletRequest req) {
        ServicioOpl.Acceso a = publico.verificadoDe(req).acceso();
        return descarga(opl, opl.soporte(a, id));
    }

    static ResponseEntity<InputStreamResource> descarga(ServicioOpl opl, ServicioArchivos.Archivo f) {
        java.io.InputStream in;
        try {
            in = opl.abrir(f);
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
}
