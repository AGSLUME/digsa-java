package co.com.agsamericas.digsa.plataforma;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Error que el usuario puede corregir o entender: se responde con su estado HTTP y el mensaje tal cual. */
public class ErrorNegocio extends RuntimeException {

    private final HttpStatus estado;
    private final Map<String, Object> datos;

    public ErrorNegocio(HttpStatus estado, String mensaje) {
        this(estado, mensaje, Map.of());
    }

    public ErrorNegocio(HttpStatus estado, String mensaje, Map<String, Object> datos) {
        super(mensaje);
        this.estado = estado;
        this.datos = datos;
    }

    public static ErrorNegocio validacion(String mensaje) {
        return new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, mensaje);
    }

    /**
     * 422 de un campo: además del mensaje lleva {@code campo} (nombre del campo en el cuerpo de la petición) para que la interfaz lo
     * marque (Anexo 5 num. 2.4; decisiones UI-6 y UI-21). El mensaje y el código no cambian.
     */
    public static ErrorNegocio validacion(String mensaje, String campo) {
        return new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, mensaje, Map.of("campo", campo));
    }

    /** El mismo error con el campo al que se refiere (para uno que viene de una regla compartida, p. ej. la de los días hábiles). */
    public ErrorNegocio conCampo(String campo) {
        Map<String, Object> d = new LinkedHashMap<>(datos);
        d.put("campo", campo);
        return new ErrorNegocio(estado, getMessage(), d);
    }

    public static ErrorNegocio noEncontrado(String mensaje) {
        return new ErrorNegocio(HttpStatus.NOT_FOUND, mensaje);
    }

    public static ErrorNegocio conflicto(String mensaje) {
        return new ErrorNegocio(HttpStatus.CONFLICT, mensaje);
    }

    public HttpStatus estado() {
        return estado;
    }

    public Map<String, Object> datos() {
        return datos;
    }
}
