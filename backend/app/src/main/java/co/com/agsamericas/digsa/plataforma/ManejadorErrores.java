package co.com.agsamericas.digsa.plataforma;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Respuesta JSON única para los errores: {"mensaje": "..."} con el estado HTTP que corresponde. Nunca el detalle interno. */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorErrores.class);

    public static Map<String, Object> cuerpo(String mensaje) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mensaje", mensaje);
        return m;
    }

    @ExceptionHandler(ErrorNegocio.class)
    ResponseEntity<Map<String, Object>> negocio(ErrorNegocio e) {
        Map<String, Object> m = cuerpo(e.getMessage());
        m.putAll(e.datos());
        return ResponseEntity.status(e.estado()).body(m);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validacion(MethodArgumentNotValidException e) {
        String mensaje = e.getBindingResult().getFieldErrors().stream().findFirst()
                .map(f -> f.getDefaultMessage() == null ? "Hay un dato no válido: " + f.getField() + "." : f.getDefaultMessage())
                .orElse("Hay datos no válidos.");
        return ResponseEntity.unprocessableEntity().body(cuerpo(mensaje));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> peticionMal(Exception e) {
        return ResponseEntity.badRequest().body(cuerpo("La petición no tiene el formato esperado."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> denegado(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(cuerpo("No tiene permiso para esta acción."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> muyGrande(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(cuerpo("Los archivos superan el tamaño máximo permitido por petición."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> metodo(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(cuerpo("Método no permitido."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noExiste(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(cuerpo("No existe."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> interno(Exception e) {
        LOG.error("Error no controlado", e);
        return ResponseEntity.internalServerError().body(cuerpo("Ocurrió un error en el servidor. Intente de nuevo o avise al administrador."));
    }
}
