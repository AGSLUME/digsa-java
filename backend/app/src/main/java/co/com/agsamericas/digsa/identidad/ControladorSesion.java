package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Datos de la sesión para la SPA: quién es, sus perfiles, el menú y los minutos de inactividad; y el latido "Seguir conectado". */
@RestController
@RequestMapping("/api/sesion")
public class ControladorSesion {

    private final ConfiguracionDigsa cfg;
    private final RepositorioUsuarios usuarios;

    public ControladorSesion(ConfiguracionDigsa cfg, RepositorioUsuarios usuarios) {
        this.cfg = cfg;
        this.usuarios = usuarios;
    }

    @GetMapping
    @Accion(modulo = "sesion", accion = "datos", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> datos() {
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        Set<Perfil> perfiles = UsuarioSesion.perfilesActuales();
        List<Map<String, String>> menu = Arrays.stream(Pantallas.values())
                .filter(p -> !p.oculta() && p.puedeEntrar(perfiles, true))
                .map(p -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("ruta", p.ruta());
                    m.put("titulo", p.titulo());
                    m.put("grupo", p.grupo() == null ? "" : p.grupo());
                    return m;
                }).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("usuario", u.usuario());
        m.put("nombre", u.nombre());
        Map<Integer, String> nombres = usuarios.nombresPerfiles(); // configurables por el administrador
        m.put("perfiles", perfiles.stream().map(p -> nombres.getOrDefault(p.id(), p.nombre())).sorted().toList());
        m.put("menu", menu);
        m.put("minutosInactividad", cfg.minutosSesion());
        return m;
    }

    /** «Seguir conectado»: renueva el último acceso (lo hace el filtro de la sesión) y no deja fila en la bitácora. */
    @GetMapping("/mantener")
    @Accion(modulo = "sesion", accion = "mantener", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> mantener() {
        return Map.of("minutos", cfg.minutosSesion());
    }
}
