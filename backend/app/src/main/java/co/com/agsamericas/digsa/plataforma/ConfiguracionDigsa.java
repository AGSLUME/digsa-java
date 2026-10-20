package co.com.agsamericas.digsa.plataforma;

import co.com.agsamericas.digsa.reglas.Claves;
import co.com.agsamericas.digsa.reglas.Sesion;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración propia de la aplicación (prefijo {@code digsa}). Los secretos llegan por variables de entorno o Key Vault, nunca
 * por el repositorio.
 *
 * @param url            URL pública con la barra final: va en los enlaces de los correos
 * @param entorno        local | pruebas | produccion; el captcha sin clave y el correo a archivo solo se admiten en local
 * @param sesionMinutos  inactividad (Anexo 5, 3.2): 10 por defecto, de 1 a 480
 * @param claveVigenciaDias días de vigencia de la contraseña (30 por defecto)
 */
@ConfigurationProperties(prefix = "digsa")
public record ConfiguracionDigsa(String url, String entorno, String sesionMinutos, Integer claveVigenciaDias, Correo correo,
                                 Captcha captcha, Reportes reportes, Archivos archivos) {

    public record Correo(String modo, String remitente, String nombreRemitente, String carpeta) {
    }

    public record Captcha(String claveSitio, String claveSecreta) {
    }

    /** @param dominiosPermitidos dominios a los que se pueden enviar reportes, separados por coma; vacío = cualquiera (solo local) */
    public record Reportes(String correoDefecto, String dominiosPermitidos) {
    }

    /** Dominios permitidos para enviar reportes (la bitácora no sale a cualquier correo externo). */
    public java.util.List<String> dominiosReportes() {
        String d = reportes == null ? null : reportes.dominiosPermitidos();
        if (d == null || d.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(d.split(",")).map(String::strip).map(String::toLowerCase).filter(s -> !s.isEmpty()).toList();
    }

    public record Archivos(String carpeta) {
    }

    public boolean esLocal() {
        return "local".equalsIgnoreCase(entorno);
    }

    public int minutosSesion() {
        return Sesion.minutos(sesionMinutos);
    }

    public int diasVigenciaClave() {
        return claveVigenciaDias == null || claveVigenciaDias < 1 ? Claves.VIGENCIA_DIAS_DEFECTO : claveVigenciaDias;
    }

    public boolean urlSegura() {
        return url != null && url.startsWith("https://");
    }

    public String enlace(String ruta) {
        String base = url == null ? "" : url;
        return (base.endsWith("/") ? base : base + "/") + ruta;
    }
}
