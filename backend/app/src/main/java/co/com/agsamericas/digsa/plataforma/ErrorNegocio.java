package co.com.agsamericas.digsa.plataforma;

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
