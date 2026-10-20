package co.com.agsamericas.digsa.plataforma;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Deja en la petición la {@link Accion} del endpoint y sus variables de ruta, para que la bitácora sepa qué se hizo. */
public class InterceptorAccion implements HandlerInterceptor {

    public static final String ATR_ACCION = "digsa.bitacora.accion";
    public static final String ATR_VARIABLES = "digsa.bitacora.variables";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod m) {
            Accion a = m.getMethodAnnotation(Accion.class);
            if (a != null) {
                request.setAttribute(ATR_ACCION, a);
            }
            if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> vars) {
                request.setAttribute(ATR_VARIABLES, vars);
            }
        }
        return true;
    }
}
