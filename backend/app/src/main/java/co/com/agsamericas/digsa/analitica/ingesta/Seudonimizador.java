package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.analitica.PropiedadesAnalitica;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Seudónimo del paciente y del profesional (A5 4.7.3; RESP L2209): HMAC-SHA256 con DIGSA_ANALITICA_CLAVE_SEUDONIMO sobre el documento
 * normalizado («TIPO|NUMERO»), en el flujo de lectura y antes de escribir en staging. El mismo documento da el mismo seudónimo en todos
 * los cargues mientras no cambie la clave (ver la rotación en {@link PropiedadesAnalitica}). La clave y el documento nunca se registran.
 *
 * <p>Nunca una clave aleatoria: partiría a cada paciente en dos seudónimos al reiniciar, sin ningún error visible. Sin la variable, en
 * local se usa la clave fija de desarrollo; en cualquier otro entorno el seudonimizador queda «sin clave» y todo cargue se rechaza con
 * {@link #MENSAJE_SIN_CLAVE} (producción ni siquiera arranca: ValidacionArranqueAnalitica).
 */
@Component
class Seudonimizador {

    static final String MENSAJE_SIN_CLAVE = "Falta configurar la clave del seudónimo de los pacientes (DIGSA_ANALITICA_CLAVE_SEUDONIMO); "
            + "los cargues del insumo están deshabilitados hasta que TI la configure.";
    private static final HexFormat HEX = HexFormat.of();
    private final SecretKeySpec clave;
    private final ThreadLocal<Mac> macs;

    Seudonimizador(PropiedadesAnalitica propiedades, ConfiguracionDigsa cfg) {
        String c = propiedades.tieneClave() ? propiedades.claveSeudonimo() : cfg.esLocal() ? PropiedadesAnalitica.CLAVE_LOCAL : null;
        this.clave = c == null ? null : new SecretKeySpec(c.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.macs = ThreadLocal.withInitial(this::nuevoMac);
    }

    /** ¿Hay clave? Sin ella no se acepta ningún cargue. */
    boolean disponible() {
        return clave != null;
    }

    /** 422 con el mensaje de la clave faltante si no hay clave (antes de crear un cargue o recibir un lote). */
    void exigir() {
        if (!disponible()) {
            throw ErrorNegocio.validacion(MENSAJE_SIN_CLAVE);
        }
    }

    private Mac nuevoMac() {
        if (clave == null) {
            throw new IllegalStateException(MENSAJE_SIN_CLAVE);
        }
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(clave);
            return m;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo iniciar HMAC-SHA256", e);
        }
    }

    /** Seudónimo de 64 hexadecimales del documento (tipo y número como vienen), o null si no hay número. */
    String paciente(String tipo, String numero) {
        String doc = AnaliticaIngesta.documento(tipo, numero);
        return doc.isEmpty() ? null : hmac("paciente|" + doc);
    }

    /** Seudónimo del profesional (otro dominio que el del paciente: el mismo documento no cruza entre los dos). */
    String profesional(String documento) {
        String doc = AnaliticaIngesta.documento(null, documento);
        return doc.isEmpty() ? null : hmac("profesional|" + doc);
    }

    private String hmac(String texto) {
        return HEX.formatHex(macs.get().doFinal(texto.getBytes(StandardCharsets.UTF_8)));
    }
}
