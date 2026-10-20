package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.ManejadorErrores;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.SessionManagementFilter;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Seguridad web: sesión en el servidor (Spring Session JDBC) con cookie HttpOnly, SameSite=Strict y Secure con HTTPS; CSRF en toda
 * escritura; cabeceras de seguridad y CSP; respuestas JSON para 401, 403 y 419. La sesión única y la inactividad las impone
 * {@link FiltroSesion}.
 */
@Configuration
@EnableMethodSecurity
public class ConfiguracionSeguridad {

    /** Content-Security-Policy: nada de terceros salvo el captcha de Cloudflare Turnstile. */
    static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://challenges.cloudflare.com",
            "frame-src https://challenges.cloudflare.com",
            "connect-src 'self'",
            "img-src 'self' data: blob:",
            "style-src 'self' 'unsafe-inline'",
            "font-src 'self' data:",
            "worker-src 'self' blob:",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    SecurityFilterChain cadena(HttpSecurity http, FiltroSesion filtroSesion, ObjectMapper json, ConfiguracionDigsa cfg) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(c -> c.sameSite("Strict").secure(cfg.urlSegura()).path("/"));
        http
                .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new ManejadorCsrfSpa()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED).sessionFixation(f -> f.changeSessionId()))
                .securityContext(s -> s.securityContextRepository(repositorioContexto()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/salud").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> responder(res, json, 401, "La sesión expiró. Vuelva a iniciar sesión.",
                                Map.of("motivo", "sin_sesion")))
                        .accessDeniedHandler((req, res, ex) -> {
                            if (ex instanceof CsrfException) {
                                responder(res, json, 419, "La página expiró. Recárguela e intente de nuevo.", Map.of());
                            } else {
                                responder(res, json, 403, "No tiene permiso para esta acción.", Map.of());
                            }
                        }))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .permissionsPolicyHeader(p -> p.policy("geolocation=(), microphone=(), camera=(self)")))
                .addFilterAfter(filtroSesion, SessionManagementFilter.class);
        return http.build();
    }

    @Bean
    SecurityContextRepository repositorioContexto() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder codificador() {
        return new BCryptPasswordEncoder(12);
    }

    /** Cookie de la sesión: HttpOnly, SameSite=Strict, sin vencimiento propio y Secure con HTTPS. */
    @Bean
    CookieSerializer cookieSesion(ConfiguracionDigsa cfg) {
        DefaultCookieSerializer c = new DefaultCookieSerializer();
        c.setCookieName("DIGSA_" + (cfg.entorno() == null ? "app" : cfg.entorno().toUpperCase()));
        c.setUseHttpOnlyCookie(true);
        c.setSameSite("Strict");
        c.setUseSecureCookie(cfg.urlSegura());
        c.setCookieMaxAge(-1);
        c.setCookiePath("/");
        return c;
    }

    static void responder(HttpServletResponse res, ObjectMapper json, int estado, String mensaje, Map<String, Object> extra) throws IOException {
        if (res.isCommitted()) {
            return;
        }
        Map<String, Object> cuerpo = ManejadorErrores.cuerpo(mensaje);
        cuerpo.putAll(extra);
        res.setStatus(estado);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getOutputStream(), cuerpo);
    }
}
