package co.com.agsamericas.digsa.analitica.prediccion;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.analitica.prediccion.ServicioPrediccion.Clave;
import co.com.agsamericas.digsa.analitica.tableros.ExportadorAnalitica;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tablero 12 «Predictivo» dentro de la pantalla ANALITICA (analitica-cohortes-alertas, A5): proyección con IC 95 %, metadatos, escenarios,
 * proyectado vs observado, bitácora de entrenamientos, Top 20 por gasto de una dimensión, descarga con metadatos y «Reentrenar»
 * (Administrador, Coordinador y Analista). Todo en el alcance del usuario y siempre agregado. Sin IA.
 *
 * <p>Parámetros de la serie: {@code metrica} (gasto, dispensaciones, pacientes), {@code nivel} (nacional, fuerza, esm), {@code valor}
 * (Fuerza o ESM), {@code dimension} (atc, principio, cie10) y {@code valorDimension}; además {@code corte} (cargue), {@code horizonte} (6 a
 * 24) y {@code ajuste} (% del escenario ajustado; no se guarda).
 */
@RestController
@PreAuthorize("@permisos.pantalla('ANALITICA')")
public class PrediccionApi {

    private static final String REENTRENA = "@permisos.pantalla('ANALITICA') and @permisosPrediccion.reentrenar()";

    private final ServicioPrediccion servicio;
    private final ExportadorAnalitica exportador;
    private final PerfilesOperacion perfiles;
    private final PermisosPrediccion permisos;
    private final RegistroBitacora bitacora;
    private final Clock reloj;

    PrediccionApi(ServicioPrediccion servicio, ExportadorAnalitica exportador, PerfilesOperacion perfiles, PermisosPrediccion permisos,
                  RegistroBitacora bitacora, Clock reloj) {
        this.servicio = servicio;
        this.exportador = exportador;
        this.perfiles = perfiles;
        this.permisos = permisos;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    private static Alcance alcance() {
        return UsuarioSesion.alcanceActual();
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    private Clave clave(Map<String, String> p, Long corte) {
        return servicio.clave(p.get("metrica"), p.get("nivel"), p.get("valor"), p.get("dimension"), p.get("valorDimension"), corte, alcance());
    }

    private static Long entero(Map<String, ?> p, String k) {
        Object v = p.get(k);
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(v.toString().strip());
        } catch (NumberFormatException e) {
            throw ErrorNegocio.validacion("El valor de «" + k + "» no es un número.", k);
        }
    }

    private static Integer horizonte(Map<String, ?> p) {
        Long h = entero(p, "horizonte");
        return h == null ? null : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, h));
    }

    /** Serie observada, proyección con IC 95 %, metadatos, escenarios y proyectado vs observado. */
    @GetMapping("/api/analitica/prediccion")
    @Accion(modulo = "analitica", accion = "prediccion_consulta", operacion = Declarada.CONSULTA)
    public Map<String, Object> consultar(@RequestParam Map<String, String> parametros) {
        Long corte = entero(parametros, "corte");
        Clave k = clave(parametros, corte);
        Map<String, Object> r = new LinkedHashMap<>(servicio.consultar(k, corte, horizonte(parametros), parametros.get("ajuste"), alcance()));
        r.put("puedeReentrenar", permisos.reentrenar());
        Object c = r.get("corte") instanceof Map<?, ?> m ? m.get("cargueId") : null;
        bitacora.registrar("analitica_consulta", "Tablero predictivo · " + k.texto() + (c == null ? "" : " · corte " + c), quien().id(), quien().usuario());
        return r;
    }

    /** Reentrena la serie sobre el corte vigente (o el indicado): queda en la bitácora de entrenamientos con el usuario. */
    @PostMapping("/api/analitica/prediccion/reentrenar")
    @PreAuthorize(REENTRENA)
    @Accion(modulo = "analitica", accion = "prediccion_reentrenar", operacion = Declarada.INSERCION)
    public ResponseEntity<ServicioPrediccion.Entrenado> reentrenar(@RequestBody(required = false) Map<String, Object> cuerpo) {
        Map<String, Object> c = cuerpo == null ? Map.of() : cuerpo;
        Map<String, String> p = new LinkedHashMap<>();
        c.forEach((k, v) -> p.put(k, v == null ? null : String.valueOf(v)));
        Long corte = entero(c, "corte");
        Clave k = clave(p, corte);
        UsuarioSesion u = quien();
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.reentrenar(k, corte, horizonte(c), alcance(), u.id(), u.usuario()));
    }

    /** Bitácora de entrenamientos de la serie, paginada. */
    @GetMapping("/api/analitica/prediccion/entrenamientos")
    @Accion(modulo = "analitica", accion = "prediccion_entrenamientos", operacion = Declarada.CONSULTA)
    public Map<String, Object> entrenamientos(@RequestParam Map<String, String> parametros) {
        Long corte = entero(parametros, "corte");
        Long pagina = entero(parametros, "pagina");
        if (pagina != null && pagina < 1) {
            throw ErrorNegocio.validacion("La página no es válida.", "pagina");
        }
        return servicio.entrenamientos(clave(parametros, corte), pagina == null ? 1 : (int) Math.min(pagina, 100_000), alcance());
    }

    /** Top 20 por gasto de una dimensión (ATC del nivel elegido, principio activo o CIE-10) en el nivel de la serie. */
    @GetMapping("/api/analitica/prediccion/top")
    @Accion(modulo = "analitica", accion = "prediccion_top", operacion = Declarada.SIN_REGISTRO)
    public List<Map<String, String>> top(@RequestParam Map<String, String> parametros) {
        Long corte = entero(parametros, "corte");
        Map<String, String> sinDimension = new LinkedHashMap<>(parametros);
        sinDimension.remove("dimension");
        sinDimension.remove("valorDimension");
        Long nivelAtc = entero(parametros, "nivelAtc");
        return servicio.top(clave(sinDimension, corte), parametros.get("dimension"), nivelAtc == null ? null : (int) Math.min(nivelAtc, 99), corte,
                alcance());
    }

    /** Descarga Excel, CSV o PDF de la proyección con sus metadatos (14.8). Sin datos personales. */
    @GetMapping("/api/analitica/prediccion/descarga")
    @Accion(modulo = "analitica", accion = "prediccion_descarga", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> descarga(@RequestParam Map<String, String> parametros) {
        String formato = parametros.getOrDefault("formato", "");
        if (!List.of("xlsx", "csv", "pdf").contains(formato)) {
            throw ErrorNegocio.validacion("Formato no válido: use xlsx, csv o pdf.", "formato");
        }
        Long corte = entero(parametros, "corte");
        Clave k = clave(parametros, corte);
        ResultadoTablero r = servicio.paraDescarga(k, corte, horizonte(parametros), parametros.get("ajuste"), alcance());
        UsuarioSesion u = quien();
        ExportadorAnalitica.Descarga d = exportador.exportar(r, formato, u.nombre() + " (" + u.usuario() + ")", perfiles.nombres(), OffsetDateTime.now(reloj));
        bitacora.registrar("analitica_descarga", "Tablero predictivo · " + k.texto() + " · corte " + r.metadatos().corteCargueId() + " · " + formato, u.id(),
                u.usuario());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, d.tipo()).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.nombre() + "\"")
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").body(d.datos());
    }
}
