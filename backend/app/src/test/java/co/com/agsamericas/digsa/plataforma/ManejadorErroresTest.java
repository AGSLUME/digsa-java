package co.com.agsamericas.digsa.plataforma;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

/** Cuerpo del 422 de validación (decisión UI-6): el mensaje de siempre y, si el error es de un campo, el nombre del campo. */
class ManejadorErroresTest {

    record Peticion(String telefono, String nombre) {}

    @SuppressWarnings("unused")
    void recibir(Peticion p) {}

    private static MethodArgumentNotValidException excepcion(BeanPropertyBindingResult r) throws NoSuchMethodException {
        MethodParameter parametro = new MethodParameter(ManejadorErroresTest.class.getDeclaredMethod("recibir", Peticion.class), 0);
        return new MethodArgumentNotValidException(parametro, r);
    }

    @Test
    @DisplayName("error de campo: 422 con el mensaje sin cambios y el campo")
    void conCampo() throws Exception {
        var r = new BeanPropertyBindingResult(new Peticion("12", "Ana"), "peticion");
        r.rejectValue("telefono", "Pattern", "El teléfono debe tener 10 dígitos.");
        r.rejectValue("nombre", "NotBlank", "Falta el nombre.");

        var respuesta = new ManejadorErrores().validacion(excepcion(r));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(422);
        assertThat(respuesta.getBody()).containsOnlyKeys("mensaje", "campo").containsEntry("mensaje", "El teléfono debe tener 10 dígitos.").containsEntry("campo", "telefono");
    }

    @Test
    @DisplayName("error de campo sin mensaje: el texto de siempre, con el campo")
    void sinMensaje() throws Exception {
        var r = new BeanPropertyBindingResult(new Peticion(null, "Ana"), "peticion");
        r.rejectValue("telefono", "NotNull");

        var respuesta = new ManejadorErrores().validacion(excepcion(r));

        assertThat(respuesta.getBody()).containsOnlyKeys("mensaje", "campo").containsEntry("mensaje", "Hay un dato no válido: telefono.").containsEntry("campo", "telefono");
    }

    @Test
    @DisplayName("error que no es de un campo: el mensaje general y sin «campo» (compatible con los clientes de antes)")
    void sinCampo() throws Exception {
        var r = new BeanPropertyBindingResult(new Peticion("1", "Ana"), "peticion");
        r.reject("Rango", "El rango no es válido.");

        var respuesta = new ManejadorErrores().validacion(excepcion(r));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(422);
        assertThat(respuesta.getBody()).containsOnlyKeys("mensaje").containsEntry("mensaje", "Hay datos no válidos.");
    }
}
