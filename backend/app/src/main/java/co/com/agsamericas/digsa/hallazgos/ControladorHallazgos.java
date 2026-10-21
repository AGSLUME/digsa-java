package co.com.agsamericas.digsa.hallazgos;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** «Prestadores con hallazgos»: Auditor (su equipo), Coordinador y DIGSA (todo), Sanidad (su fuerza) y ESM (su establecimiento). */
@RestController
public class ControladorHallazgos {

    private final ServicioHallazgos hallazgos;

    public ControladorHallazgos(ServicioHallazgos hallazgos) {
        this.hallazgos = hallazgos;
    }

    @GetMapping("/api/hallazgos")
    @PreAuthorize("@permisos.pantalla('HALLAZGOS')")
    @Accion(modulo = "hallazgos", accion = "lista", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> lista() {
        return hallazgos.lista(UsuarioSesion.alcanceActual(), UsuarioSesion.actual().orElseThrow(), UsuarioSesion.perfilesActuales());
    }
}
