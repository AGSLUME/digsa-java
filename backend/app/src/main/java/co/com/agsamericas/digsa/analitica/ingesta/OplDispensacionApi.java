package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.analitica.PropiedadesAnalitica;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Servicio web simulado del OPL (A5 14.3): lotes JSON de dispensación, solo para el perfil OPL nacional (los demás, 403 sin crear
 * nada). Con la sesión de esa cuenta (duda 6: la credencial de servicio sin CSRF queda para cuando la DIGSA lo pida). El cuerpo tiene
 * tope (digsa.analitica.cuerpo-ws-max-mb): más responde 413 sin leerlo entero.
 */
@RestController
public class OplDispensacionApi {

    private final ServicioWebOpl servicio;
    private final PropiedadesAnalitica propiedades;

    OplDispensacionApi(ServicioWebOpl servicio, PropiedadesAnalitica propiedades) {
        this.servicio = servicio;
        this.propiedades = propiedades;
    }

    /** Un lote: {@code {"envio": "...", "numero": 1, "final": false, "registros": [{"paciente": ..., "fuerza": ..., ...}]}}. */
    @PostMapping(value = "/api/analitica/opl/dispensacion", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permisosAnalitica.servicioWeb()")
    @Accion(modulo = "analitica", accion = "opl_dispensacion_lote", operacion = Declarada.INSERCION)
    public Map<String, Object> lote(HttpServletRequest peticion) throws IOException {
        long tope = propiedades.cuerpoWsMaxBytes();
        if (peticion.getContentLengthLong() > tope) {
            throw ServicioWebOpl.demasiadoGrande(tope);
        }
        byte[] cuerpo;
        try (InputStream in = peticion.getInputStream()) {
            cuerpo = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, tope + 1)); // lectura limitada aunque falte Content-Length
        }
        if (cuerpo.length > tope) {
            throw ServicioWebOpl.demasiadoGrande(tope);
        }
        if (cuerpo.length == 0) {
            throw ErrorNegocio.validacion("El lote llegó vacío.");
        }
        return servicio.recibir(cuerpo, UsuarioSesion.actual().orElseThrow());
    }
}
