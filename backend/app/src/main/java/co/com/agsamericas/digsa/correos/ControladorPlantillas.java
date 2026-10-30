package co.com.agsamericas.digsa.correos;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.CatalogoPlantillas;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Módulo Correos, fase C2: catálogo de plantillas. Se ven todas; las editables cambian su asunto y su texto (las variables protegidas
 * no se pueden quitar), tienen vista previa con datos ficticios y «Restaurar original». Las que llevan un código o un enlace de un solo
 * uso (MFA, contraseña, código del OPL) se ven pero no se editan. Editar y restaurar piden motivo y quedan en la bitácora de
 * auditoría; cada versión queda guardada.
 */
@RestController
@RequestMapping("/api/correos/plantillas")
@PreAuthorize("@permisos.pantalla('CORREOS')")
public class ControladorPlantillas {

    static final String NO_EDITABLE = "Lleva un código o un enlace de un solo uso: se ve aquí pero no se edita.";

    record Edicion(String asunto, String cuerpo, String motivo) {
    }

    record Restauracion(String motivo) {
    }

    private final CatalogoPlantillas catalogo;
    private final RegistroBitacora bitacora;

    public ControladorPlantillas(CatalogoPlantillas catalogo, RegistroBitacora bitacora) {
        this.catalogo = catalogo;
        this.bitacora = bitacora;
    }

    @GetMapping
    @Accion(modulo = "correos", accion = "plantillas", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> lista() {
        Map<String, CatalogoPlantillas.Version> editadas = catalogo.editadas();
        return catalogo.todas().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clave", p.clave());
            m.put("nombre", p.nombre());
            m.put("modulo", p.modulo());
            m.put("editable", p.editable());
            CatalogoPlantillas.Version v = editadas.get(p.clave());
            m.put("editada", v != null);
            m.put("editadaPor", v == null ? null : v.usuario());
            m.put("editadaEl", v == null ? null : v.creado());
            return m;
        }).toList();
    }

    @GetMapping("/{clave}")
    @Accion(modulo = "correos", accion = "ver_plantilla", operacion = Declarada.CONSULTA)
    public Map<String, Object> ver(@PathVariable String clave) {
        Plantilla fabrica = plantilla(clave);
        Plantilla vigente = catalogo.vigente(fabrica);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clave", fabrica.clave());
        m.put("nombre", fabrica.nombre());
        m.put("modulo", fabrica.modulo());
        m.put("formato", fabrica.formato().name().toLowerCase(java.util.Locale.ROOT));
        m.put("editable", fabrica.editable());
        m.put("motivoNoEditable", fabrica.editable() ? null : NO_EDITABLE);
        m.put("titulo", fabrica.titulo());
        m.put("asunto", vigente.asunto());
        m.put("cuerpo", vigente.cuerpo());
        m.put("asuntoFabrica", fabrica.asunto());
        m.put("cuerpoFabrica", fabrica.cuerpo());
        m.put("editada", vigente != fabrica);
        m.put("variables", fabrica.variables().stream().map(v -> Map.of("nombre", v.nombre(), "descripcion", v.descripcion(), "ejemplo", v.ejemplo(),
                "obligatoria", v.obligatoria(), "bloque", v.html())).toList());
        m.put("historial", fabrica.editable() ? catalogo.historial(clave, 20).stream().map(h -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", h.id());
            x.put("accion", h.restaurada() ? "Restaurada al original" : "Editada");
            x.put("motivo", h.motivo());
            x.put("usuario", h.usuario());
            x.put("creado", h.creado());
            return x;
        }).toList() : List.of());
        return m;
    }

    /** Vista previa con datos ficticios de un texto (sin guardar). Sin asunto ni cuerpo, la de la versión vigente. */
    @PostMapping("/{clave}/vista-previa")
    @Accion(modulo = "correos", accion = "vista_previa_plantilla", operacion = Declarada.CONSULTA)
    public Map<String, Object> vistaPrevia(@PathVariable String clave, @RequestBody(required = false) Edicion e) {
        Plantilla fabrica = plantilla(clave);
        Plantilla p = catalogo.vigente(fabrica);
        List<String> errores = List.of();
        if (e != null && e.asunto() != null && e.cuerpo() != null) {
            errores = fabrica.validar(e.asunto(), e.cuerpo());
            if (errores.isEmpty()) {
                p = fabrica.con(e.asunto(), e.cuerpo());
            }
        }
        Plantilla.Renderizada r = p.ejemplo();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asunto", r.asunto());
        m.put("html", r.html());
        m.put("errores", errores);
        return m;
    }

    @PutMapping("/{clave}")
    @Accion(modulo = "correos", accion = "editar_plantilla", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editar(@PathVariable String clave, @RequestBody Edicion e) {
        Plantilla p = editable(clave);
        String motivo = motivo(e == null ? null : e.motivo());
        String asunto = e.asunto() == null ? null : e.asunto().strip();
        String cuerpo = e.cuerpo() == null ? null : e.cuerpo().strip();
        List<String> errores = p.validar(asunto, cuerpo);
        if (!errores.isEmpty()) {
            throw ErrorNegocio.validacion(String.join(" ", errores), errores.get(0).contains("asunto") ? "asunto" : "cuerpo");
        }
        if (asunto.equals(catalogo.vigente(p).asunto()) && cuerpo.equals(catalogo.vigente(p).cuerpo())) {
            throw ErrorNegocio.validacion("No hay cambios que guardar.", "cuerpo");
        }
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        long id = catalogo.guardar(p, asunto, cuerpo, motivo, u.id());
        bitacora.registrar("correo_plantilla_editada", "Plantilla " + p.clave() + " (" + p.nombre() + ") editada, versión " + id + ". Motivo: " + motivo,
                u.id(), u.usuario());
        return Map.of("id", id, "mensaje", "Plantilla guardada: los próximos correos «" + p.nombre() + "» salen con este texto.");
    }

    @PostMapping("/{clave}/restaurar")
    @Accion(modulo = "correos", accion = "restaurar_plantilla", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> restaurar(@PathVariable String clave, @RequestBody Restauracion r) {
        Plantilla p = editable(clave);
        String motivo = motivo(r == null ? null : r.motivo());
        if (catalogo.vigente(p) == p) {
            throw ErrorNegocio.conflicto("La plantilla ya tiene el texto original.");
        }
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        long id = catalogo.restaurar(p, motivo, u.id());
        bitacora.registrar("correo_plantilla_restaurada", "Plantilla " + p.clave() + " (" + p.nombre() + ") restaurada al original, versión " + id
                + ". Motivo: " + motivo, u.id(), u.usuario());
        return Map.of("id", id, "mensaje", "Plantilla restaurada: los próximos correos «" + p.nombre() + "» salen con el texto original.");
    }

    private Plantilla plantilla(String clave) {
        return catalogo.porClave(clave).orElseThrow(() -> ErrorNegocio.noEncontrado("La plantilla no existe."));
    }

    private Plantilla editable(String clave) {
        Plantilla p = plantilla(clave);
        if (!p.editable()) {
            throw ErrorNegocio.conflicto("La plantilla «" + p.nombre() + "» no se edita. " + NO_EDITABLE);
        }
        return p;
    }

    private static String motivo(String m) {
        String motivo = m == null ? "" : m.strip();
        int largo = motivo.codePointCount(0, motivo.length());
        if (largo < ControladorCorreos.MOTIVO_MIN || largo > ControladorCorreos.MOTIVO_MAX) {
            throw ErrorNegocio.validacion("Escriba el motivo (entre " + ControladorCorreos.MOTIVO_MIN + " y " + ControladorCorreos.MOTIVO_MAX
                    + " caracteres).", "motivo");
        }
        return motivo;
    }
}
