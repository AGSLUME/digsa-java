package co.com.agsamericas.digsa.plataforma;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Interceptor de la bitácora y la SPA: las rutas del frontend (sin punto ni /api) devuelven index.html. */
@Configuration
public class ConfiguracionWeb implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new InterceptorAccion()).addPathPatterns("/api/**");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/{ruta:^(?!api$)[^.]*}").setViewName("forward:/index.html");
        registry.addViewController("/{ruta:^(?!api$)[^.]*}/{sub:[^.]*}").setViewName("forward:/index.html");
    }
}
