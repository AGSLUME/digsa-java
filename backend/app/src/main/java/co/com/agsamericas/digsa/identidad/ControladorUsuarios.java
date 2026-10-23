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

    record ClaveInicial(String clave) {
    }

    record NombrePerfil(String nombre, String descripcion) {
    }

    record Seguridad(@jakarta.validation.constraints.NotNull(message = "Escriba los minutos de inactividad.")
                     @jakarta.validation.constraints.Min(value = 1, message = "Los minutos de inactividad deben ser un número entero de 1 a 480.")
                     @jakarta.validation.constraints.Max(value = 480, message = "Los minutos de inactividad deben ser un número entero de 1 a 480.")
                     Integer minutosSesion) {
    }

    private final ServicioUsuarios servicio;
    private final ServicioRoles roles;
    private final java.time.Clock reloj;
    private final ParametrosSeguridad parametros;

    public ControladorUsuarios(ServicioUsuarios servicio, java.time.Clock reloj, ParametrosSeguridad parametros, ServicioRoles roles) {
        this.roles = roles;
        this.servicio = servicio;
        this.reloj = reloj;
        this.parametros = parametros;
    }

    @GetMapping
    @Accion(modulo = "usuarios", accion = "listar", operacion = Declarada.CONSULTA)
    public Map<String, Object> listar() {
        int minutos = parametros.minutosSesion();
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
            // Abierta = la que bloquea un ingreso nuevo (decisión 7.1); una huella con la actividad vencida ya no cuenta
            m.put("sesionAbierta", co.com.agsamericas.digsa.reglas.Sesion.bloqueaNuevoIngreso(u.sesionHuella(), u.sesionUltimo(), reloj.instant(), minutos));
            m.put("claveInicial", u.claveInicial());
            m.put("perfiles", u.asignados()); // perfiles base y roles adicionales, por id
            m.put("alcanceFuerza", u.alcanceFuerza());
            m.put("alcancePunto", u.alcancePunto());
            m.put("alcanceRegional", u.alcanceRegional());
            return m;
        }).toList();
        Map<String, Object> alcance = servicio.opcionesAlcance();
        return Map.of("usuarios", filas, "perfiles", servicio.perfilesDisponibles(), "puntos", alcance.get("puntos"), "regionales", alcance.get("regionales"),
                "fuerzas", List.of(Map.of("codigo", "EJC", "nombre", "DISAN EJC (Ejército)"), Map.of("codigo", "ARC", "nombre", "DISAN ARC (Armada)"),
                        Map.of("codigo", "FAC", "nombre", "JEFSA FAC (Fuerza Aeroespacial)")));
    }

    @PostMapping
    @Accion(modulo = "usuarios", accion = "crear", operacion = Declarada.INSERCION)
    public ResponseEntity<ServicioUsuarios.Guardado> crear(@RequestBody ServicioUsuarios.Datos d) {
        ServicioUsuarios.Datos sinId = new ServicioUsuarios.Datos(null, d.nombre(), d.documento(), d.telefono(), d.usuario(), d.correo(),
                d.perfiles(), d.alcanceFuerza(), d.alcancePunto(), d.alcanceRegional(), d.claveInicial());
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.guardar(sinId, UsuarioSesion.actual().orElseThrow()));
    }

    @PutMapping("/{id}")
    @Accion(modulo = "usuarios", accion = "editar", operacion = Declarada.ACTUALIZACION)
    public ServicioUsuarios.Guardado editar(@PathVariable long id, @RequestBody ServicioUsuarios.Datos d) {
        ServicioUsuarios.Datos conId = new ServicioUsuarios.Datos(id, d.nombre(), d.documento(), d.telefono(), d.usuario(), d.correo(),
                d.perfiles(), d.alcanceFuerza(), d.alcancePunto(), d.alcanceRegional(), null);
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

    @PostMapping("/{id}/clave-inicial")
    @Accion(modulo = "usuarios", accion = "clave_inicial", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> claveInicial(@PathVariable long id, @RequestBody ClaveInicial c) {
        servicio.asignarClaveInicial(id, c.clave(), UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Contraseña inicial asignada. Entréguela al usuario por un canal seguro: deberá cambiarla al ingresar.");
    }

    @PostMapping("/{id}/cerrar-sesion")
    @Accion(modulo = "usuarios", accion = "cerrar_sesion", operacion = Declarada.SALIDA)
    public Map<String, Object> cerrarSesion(@PathVariable long id) {
        boolean cerro = servicio.cerrarSesion(id, UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", cerro ? "Sesión cerrada." : "El usuario no tenía una sesión abierta.");
    }

    @PostMapping("/{id}/desbloquear")
    @Accion(modulo = "usuarios", accion = "desbloquear", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> desbloquear(@PathVariable long id) {
        boolean estaba = servicio.desbloquear(id, UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", estaba ? "Usuario desbloqueado." : "El usuario no estaba bloqueado.");
    }

    /** Parámetros de seguridad: minutos de inactividad (Anexo 5, 3.2: TIME OUT «parametrizable y ajustable»). */
    @GetMapping("/seguridad")
    @Accion(modulo = "usuarios", accion = "seguridad", operacion = Declarada.CONSULTA)
    public Map<String, Object> seguridad() {
        return Map.of("minutosSesion", parametros.minutosSesion(), "minutosMaximo", co.com.agsamericas.digsa.reglas.Sesion.MINUTOS_MAXIMO);
    }

    @PutMapping("/seguridad")
    @Accion(modulo = "usuarios", accion = "seguridad_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarSeguridad(@RequestBody @jakarta.validation.Valid Seguridad s) {
        parametros.cambiarMinutosSesion(s.minutosSesion(), UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Tiempo de inactividad actualizado: " + s.minutosSesion() + " minutos. Aplica desde la siguiente acción de cada usuario.");
    }

    /** Roles adicionales parametrizables (Anexo 5, 1.12): perfiles base con sus pantallas elegibles y los roles creados. */
    @GetMapping("/roles")
    @Accion(modulo = "usuarios", accion = "roles", operacion = Declarada.CONSULTA)
    public Map<String, Object> roles() {
        return roles.catalogo();
    }

    @PostMapping("/roles")
    @Accion(modulo = "usuarios", accion = "rol_crear", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> crearRol(@RequestBody ServicioRoles.Datos d) {
        int id = roles.crear(d, UsuarioSesion.actual().orElseThrow());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "mensaje", "Rol creado. Asígnelo en el formulario de cada usuario."));
    }

    @PutMapping("/roles/{id}")
    @Accion(modulo = "usuarios", accion = "rol_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarRol(@PathVariable int id, @RequestBody ServicioRoles.Datos d) {
        roles.editar(id, d, UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Rol actualizado. Aplica desde la siguiente acción de sus usuarios.");
    }

    @PutMapping("/roles/{id}/estado")
    @Accion(modulo = "usuarios", accion = "rol_estado", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> estadoRol(@PathVariable int id, @RequestBody Estado e) {
        long n = roles.cambiarEstado(id, e.activo(), UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", (e.activo() ? "Rol activado" : "Rol inactivado") + " (" + n + " usuarios activos lo tienen).");
    }

    @PutMapping("/perfiles/{id}")
    @Accion(modulo = "usuarios", accion = "perfil_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> renombrarPerfil(@PathVariable int id, @RequestBody NombrePerfil n) {
        servicio.renombrarPerfil(id, n.nombre(), n.descripcion(), UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Perfil actualizado.");
    }
}
