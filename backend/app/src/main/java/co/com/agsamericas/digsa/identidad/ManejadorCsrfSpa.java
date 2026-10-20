package co.com.agsamericas.digsa.identidad;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * CSRF para la SPA (patrón documentado por Spring Security): el token viaja en la cookie XSRF-TOKEN y la SPA lo devuelve en la
 * cabecera X-XSRF-TOKEN; los formularios del servidor (si los hubiera) usan la versión enmascarada contra BREACH.
 */
final class ManejadorCsrfSpa implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plano = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler enmascarado = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
        enmascarado.handle(request, response, token);
        token.get(); // genera la cookie en cada respuesta
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        String cabecera = request.getHeader(token.getHeaderName());
        return (StringUtils.hasText(cabecera) ? plano : enmascarado).resolveCsrfTokenValue(request, token);
    }
}
