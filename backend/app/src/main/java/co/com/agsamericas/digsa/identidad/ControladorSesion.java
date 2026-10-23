package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Pantallas;
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

    private final ParametrosSeguridad parametros;
    private final RepositorioUsuarios usuarios;

    public ControladorSesion(ParametrosSeguridad parametros, RepositorioUsuarios usuarios) {
        this.parametros = parametros;
        this.usuarios = usuarios;
    }

    @GetMapping
    @Accion(modulo = "sesion", accion = "datos", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> datos() {
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        Set<Pantallas> permitidas = UsuarioSesion.pantallasActuales(); // perfiles directos y roles adicionales (Anexo 5, 1.12)
        List<Map<String, String>> menu = Arrays.stream(Pantallas.values())
                .filter(p -> !p.oculta() && permitidas.contains(p))
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
        // Los asignados (perfiles y roles adicionales) con su nombre visible; los perfiles efectivos solo cuentan para las reglas
        Set<Integer> asignados = UsuarioSesion.asignadosActuales();
        m.put("perfiles", asignados.stream().map(id -> nombres.getOrDefault(id, String.valueOf(id))).sorted().toList());
        m.put("menu", menu);
        // Todas las pantallas permitidas, incluidas las que no van en el menú (p. ej. el formulario de la visita)
        m.put("pantallas", Arrays.stream(Pantallas.values()).filter(permitidas::contains).map(Pantallas::ruta).toList());
        m.put("minutosInactividad", parametros.minutosSesion());
        return m;
    }

    /**
     * Estado de la sesión SIN renovarla: los minutos de inactividad vigentes (el administrador los puede cambiar con la sesión abierta)
     * y los segundos que le quedan. El aviso de inactividad lo usa para no quedarse con un plazo viejo. Si ya venció, el filtro responde
     * 401 «inactividad».
     */
    @GetMapping("/estado")
    @Accion(modulo = "sesion", accion = "estado", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> estado(jakarta.servlet.http.HttpServletRequest req) {
        Object r = req.getAttribute(FiltroSesion.ATR_RESTANTES);
        return Map.of("minutos", parametros.minutosSesion(), "restantes", r instanceof Long l ? l : 0L);
    }

    /** «Seguir conectado»: renueva el último acceso (lo hace el filtro de la sesión) y no deja fila en la bitácora. */
    @GetMapping("/mantener")
    @Accion(modulo = "sesion", accion = "mantener", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> mantener() {
        return Map.of("minutos", parametros.minutosSesion());
    }
}
