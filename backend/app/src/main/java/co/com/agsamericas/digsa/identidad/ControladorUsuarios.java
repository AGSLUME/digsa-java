package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Usuarios: solo el Administrador. */
@RestController
@RequestMapping("/api/usuarios")
@PreAuthorize("@permisos.pantalla('USUARIOS')")
public class ControladorUsuarios {

    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    record Estado(boolean activo) {
    }

    private final ServicioUsuarios servicio;
    private final java.time.Clock reloj;

    public ControladorUsuarios(ServicioUsuarios servicio, java.time.Clock reloj) {
        this.servicio = servicio;
        this.reloj = reloj;
    }

    @GetMapping
    @Accion(modulo = "usuarios", accion = "listar", operacion = Declarada.CONSULTA)
    public Map<String, Object> listar() {
        List<Map<String, Object>> filas = servicio.listar().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.id());
            m.put("nombre", u.nombre());
            m.put("documento", u.documento());
            m.put("telefono", u.telefono());
            m.put("usuario", u.usuario());
            m.put("correo", u.correo());
            m.put("activo", u.activo());
            m.put("ultimoIngreso", u.ultimoIngreso() == null ? null
                    : FECHA_HORA.format(u.ultimoIngreso().atZone(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA)));
            m.put("bloqueado", u.bloqueado(reloj.instant()));
            m.put("perfiles", u.perfiles().stream().map(Perfil::id).sorted().toList());
            m.put("alcanceFuerza", u.alcanceFuerza());
            m.put("alcancePunto", u.alcancePunto());
            m.put("alcanceRegional", u.alcanceRegional());
            return m;
        }).toList();
        return Map.of("usuarios", filas, "perfiles", servicio.perfilesDisponibles(),
                "fuerzas", List.of(Map.of("codigo", "EJC", "nombre", "DISAN EJC (Ejército)"), Map.of("codigo", "ARC", "nombre", "DISAN ARC (Armada)"),
                        Map.of("codigo", "FAC", "nombre", "JEFSA FAC (Fuerza Aeroespacial)")));
    }

    @PostMapping
    @Accion(modulo = "usuarios", accion = "crear", operacion = Declarada.INSERCION)
    public ResponseEntity<ServicioUsuarios.Guardado> crear(@RequestBody ServicioUsuarios.Datos d) {
        ServicioUsuarios.Datos sinId = new ServicioUsuarios.Datos(null, d.nombre(), d.documento(), d.telefono(), d.usuario(), d.correo(),
                d.perfiles(), d.alcanceFuerza(), d.alcancePunto(), d.alcanceRegional());
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.guardar(sinId, UsuarioSesion.actual().orElseThrow()));
    }

    @PutMapping("/{id}")
    @Accion(modulo = "usuarios", accion = "editar", operacion = Declarada.ACTUALIZACION)
    public ServicioUsuarios.Guardado editar(@PathVariable long id, @RequestBody ServicioUsuarios.Datos d) {
        ServicioUsuarios.Datos conId = new ServicioUsuarios.Datos(id, d.nombre(), d.documento(), d.telefono(), d.usuario(), d.correo(),
                d.perfiles(), d.alcanceFuerza(), d.alcancePunto(), d.alcanceRegional());
        return servicio.guardar(conId, UsuarioSesion.actual().orElseThrow());
    }

    @PutMapping("/{id}/estado")
    @Accion(modulo = "usuarios", accion = "estado", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> estado(@PathVariable long id, @RequestBody Estado e) {
        servicio.cambiarEstado(id, e.activo(), UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", e.activo() ? "Usuario activado." : "Usuario inactivado.");
    }

    @PostMapping("/{id}/restablecer")
    @Accion(modulo = "usuarios", accion = "restablecer", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> restablecer(@PathVariable long id) {
        ServicioClaves.EnvioEnlace e = servicio.restablecer(id, UsuarioSesion.actual().orElseThrow());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mensaje", "Se envió al correo del usuario el enlace para definir una contraseña nueva.");
        if (e.enlaceLocal() != null) {
            m.put("enlaceLocal", e.enlaceLocal());
        }
        return m;
    }
}
