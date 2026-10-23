package co.com.agsamericas.digsa.reglas;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validación de la carga inicial de datos maestros (auditores, puntos y usuarios), sin base de datos: recibe las hojas ya leídas, los
 * catálogos y lo que ya existe, y devuelve por fila qué se crea, qué se actualiza y qué tiene error. Las tres hojas se resuelven en
 * ese orden, de modo que un punto puede asignarse a un auditor de la misma carga y un usuario ESM a un punto de la misma carga.
 *
 * <p>Los motivos de error NUNCA repiten el valor de la celda (Ley 1581): dicen el campo y la regla. Referencia: PHP
 * app/Support/ImportadorMaestros.php y docs/especificaciones/despliegue-carga (parte B) y maestros-carga-masiva.
 */
public final class ImportadorMaestros {

    public static final int MAX_FILAS = 5000;
    public static final String MARCA_EJEMPLO = "borrar esta fila";
    public static final String MENSAJE_CONTRASENA = "Las plantillas no llevan contraseñas: cada usuario recibe un enlace para definirla.";
    public static final String MENSAJE_ADMINISTRADOR = "Los administradores se crean con --digsa.crear-admin.";
    public static final String MENSAJE_AUDITOR = "El perfil Auditor va en la hoja de auditores (exige documento y rol).";

    private static final Pattern SIGLA = Pattern.compile("^[A-Z0-9-]{2,20}$");
    private static final Pattern DOCUMENTO = Pattern.compile("^[A-Za-z0-9.\\-]{3,30}$");
    private static final Pattern USUARIO = Pattern.compile("^[a-z0-9._-]{3,50}$");
    private static final Pattern MARCAS = Pattern.compile("\\p{Mn}+");
    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

    /** Hojas de la plantilla, en el orden en que se resuelven. */
    public enum Tipo {
        AUDITORES("Auditores", "auditores.csv"), PUNTOS("Puntos", "puntos.csv"), USUARIOS("Usuarios", "usuarios.csv");

        private final String hoja;
        private final String csv;

        Tipo(String hoja, String csv) {
            this.hoja = hoja;
            this.csv = csv;
        }

        public String hoja() {
            return hoja;
        }

        public String csv() {
            return csv;
        }

        public String clave() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Por el nombre de la hoja o del CSV, sin mayúsculas, tildes ni espacios. */
        public static Optional<Tipo> de(String nombre) {
            String n = normalizar(nombre).replaceAll("\\.csv$", "").strip();
            for (Tipo t : values()) {
                if (t.clave().equals(n)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    /** Columnas aceptadas (campo ⇒ sinónimos ya normalizados), en el orden de la plantilla. */
    public static final Map<Tipo, Map<String, List<String>>> COLUMNAS;
    /** Campos obligatorios de cada hoja (en puntos, nombre + unidad o la sigla: completar puntos existentes por sigla). */
    public static final Map<Tipo, List<String>> OBLIGATORIAS = Map.of(
            Tipo.AUDITORES, List.of("nombre", "correo", "documento", "rol"),
            Tipo.PUNTOS, List.of("nombre", "unidad"),
            Tipo.USUARIOS, List.of("nombre", "correo", "perfiles"));

    static {
        Map<Tipo, Map<String, List<String>>> c = new LinkedHashMap<>();
        Map<String, List<String>> a = new LinkedHashMap<>();
        a.put("nombre", List.of("nombre", "auditor", "nombre_del_auditor", "nombre_completo"));
        a.put("correo", List.of("correo", "email", "correo_electronico"));
        a.put("documento", List.of("documento", "cedula", "numero_de_documento"));
        a.put("telefono", List.of("telefono", "celular"));
        a.put("rol", List.of("rol", "rol_tecnico", "profesion", "perfil_tecnico"));
        a.put("municipio_base", List.of("municipio_base", "codigo_municipio_base", "ciudad_base"));
        a.put("usuario", List.of("usuario", "nombre_de_usuario", "login"));
        a.put("qf", List.of("qf", "quimico_farmaceutico", "es_qf")); // archivos anteriores a la fase 1: «Sí» = químico farmacéutico
        c.put(Tipo.AUDITORES, a);
        Map<String, List<String>> p = new LinkedHashMap<>();
        p.put("sigla", List.of("sigla", "sigla_del_punto", "codigo_del_punto"));
        p.put("nombre", List.of("nombre", "punto", "nombre_del_punto", "establecimiento"));
        p.put("unidad", List.of("unidad", "unidad_de_intervencion", "tipo_de_punto"));
        p.put("fuerza", List.of("fuerza"));
        p.put("modalidad", List.of("modalidad"));
        p.put("nit", List.of("nit"));
        p.put("divipola", List.of("divipola", "codigo_divipola", "codigo_municipio", "cod_divipola"));
        p.put("departamento", List.of("departamento", "depto"));
        p.put("municipio", List.of("municipio", "ciudad"));
        p.put("direccion", List.of("direccion"));
        p.put("contacto", List.of("contacto", "nombre_contacto", "nombre_del_contacto", "persona_de_contacto"));
        p.put("correos", List.of("correos", "correo", "email", "correos_contacto", "correo_contacto", "correos_de_contacto"));
        p.put("exige_qf", List.of("exige_qf", "exige_quimico_farmaceutico", "requiere_qf", "requiere_quimico_farmaceutico"));
        p.put("regional", List.of("regional"));
        p.put("llega_desde", List.of("llega_desde", "se_llega_desde", "municipio_de_acceso"));
        p.put("auditor_asignado", List.of("auditor_asignado", "correo_auditor_asignado"));
        p.put("activo", List.of("activo"));
        c.put(Tipo.PUNTOS, p);
        Map<String, List<String>> u = new LinkedHashMap<>();
        u.put("nombre", List.of("nombre", "nombre_completo"));
        u.put("correo", List.of("correo", "email", "correo_electronico"));
        u.put("usuario", List.of("usuario", "nombre_de_usuario", "login"));
        u.put("documento", List.of("documento", "cedula", "numero_de_documento"));
        u.put("telefono", List.of("telefono", "celular"));
        u.put("perfiles", List.of("perfiles", "perfil"));
        u.put("alcance_fuerza", List.of("alcance_fuerza", "fuerza"));
        u.put("alcance_punto", List.of("alcance_punto", "esm", "punto"));
        u.put("alcance_regional", List.of("alcance_regional", "regional"));
        u.put("activo", List.of("activo"));
        c.put(Tipo.USUARIOS, u);
        COLUMNAS = java.util.Collections.unmodifiableMap(c);
    }

    private ImportadorMaestros() {
    }

    // ---------- Entrada ----------

    /** Fila leída de un archivo: su número (como lo ve el usuario en Excel o en el CSV) y sus celdas en el orden de los encabezados. */
    public record FilaLeida(int numero, List<String> celdas) {
    }

    /** Hoja leída (del .xlsx o de un CSV). */
    public record Hoja(Tipo tipo, String archivo, List<String> encabezados, List<FilaLeida> filas) {
    }

    public record Municipio(String departamento, String nombre) {
    }

    /**
     * Catálogos de la base.
     *
     * @param fuerzas     fuerzas activas: código ⇒ tiene sanidad (DISAN o JEFSA)
     * @param regionales  regionales activas: id ⇒ nombre
     * @param perfiles    perfiles activos: id ⇒ nombre vigente (los nombres son editables)
     */
    public record Catalogos(Map<String, String> departamentos, Map<String, Municipio> municipios, List<String> modalidades,
                            Map<String, Boolean> fuerzas, Map<Long, String> regionales, Map<Integer, String> perfiles) {
    }

    public record PuntoExistente(long id, String sigla, String nombre, String unidad, String fuerza, String modalidad, boolean activo) {
    }

    public record UsuarioExistente(long id, String usuario, String correo, Set<Perfil> perfiles, boolean activo, String alcanceFuerza,
                                   Long alcancePunto, Long alcanceRegional) {
    }

    public record Existentes(List<PuntoExistente> puntos, List<UsuarioExistente> usuarios) {
    }

    // ---------- Salida ----------

    /** Problema de una fila: el campo y la regla, sin el valor de la celda. */
    public record Problema(String campo, String motivo) {
        @Override
        public String toString() {
            return campo + ": " + motivo;
        }
    }

    public enum Accion {
        NUEVO, EXISTENTE, ERROR
    }

    /**
     * Fila validada. {@code datos} lleva solo los campos que se escriben (una celda vacía no borra): ver cada hoja. {@code id} es el
     * del registro de la base cuando ya existía.
     */
    public record Fila(Tipo tipo, String archivo, int numero, String clave, Accion accion, Long id, Map<String, Object> datos,
                       List<Problema> problemas) {
    }

    public record Resultado(List<String> erroresArchivo, Map<Tipo, List<String>> desconocidas, List<Fila> filas) {
        public boolean archivoRechazado() {
            return !erroresArchivo.isEmpty();
        }

        public long errores() {
            return filas.stream().filter(f -> f.accion() == Accion.ERROR).count();
        }

        public boolean ok() {
            return erroresArchivo.isEmpty() && errores() == 0;
        }

        public List<Fila> de(Tipo tipo) {
            return filas.stream().filter(f -> f.tipo() == tipo).toList();
        }
    }

    /** Revisión de los encabezados de una hoja. {@code columnas}: índice de la columna ⇒ campo. */
    public record Revision(String error, List<String> desconocidas, Map<Integer, String> columnas) {
    }

    // ---------- Revisión del archivo ----------

    /**
     * Encabezados: columnas obligatorias, columnas de contraseña (rechazo sin mirar las filas) y columnas desconocidas (se avisan).
     */
    public static Revision revisar(Tipo tipo, List<String> encabezados) {
        Map<Integer, String> columnas = new LinkedHashMap<>();
        List<String> desconocidas = new ArrayList<>();
        Set<String> presentes = new HashSet<>();
        for (int i = 0; i < encabezados.size(); i++) {
            String original = encabezados.get(i) == null ? "" : encabezados.get(i).strip();
            String k = clave(original);
            if (k.isEmpty()) {
                continue;
            }
            if (esContrasena(k)) {
                return new Revision(MENSAJE_CONTRASENA, List.of(), Map.of());
            }
            String campo = campoDe(tipo, k);
            if (campo == null) {
                desconocidas.add(original);
            } else {
                columnas.put(i, campo);
                presentes.add(campo);
            }
        }
        List<String> faltan = new ArrayList<>();
        for (String o : OBLIGATORIAS.get(tipo)) {
            boolean alterna = tipo == Tipo.AUDITORES && o.equals("rol") && presentes.contains("qf")
                    || tipo == Tipo.PUNTOS && presentes.contains("sigla");
            if (!presentes.contains(o) && !alterna) {
                faltan.add(o);
            }
        }
        String error = faltan.isEmpty() ? "" : "Al archivo le faltan las columnas obligatorias: " + String.join(", ", faltan) + ". Use la plantilla.";
        return new Revision(error, desconocidas, columnas);
    }

    /** clave, contraseña, password y sus variantes (pass, pwd, clave_inicial…). */
    static boolean esContrasena(String claveNormalizada) {
        return claveNormalizada.contains("clave") || claveNormalizada.contains("contrasen") || claveNormalizada.contains("password")
                || claveNormalizada.contains("passwd") || Set.of("pass", "pwd", "pin").contains(claveNormalizada);
    }

    private static String campoDe(Tipo tipo, String clave) {
        for (var e : COLUMNAS.get(tipo).entrySet()) {
            if (e.getValue().contains(clave)) {
                return e.getKey();
            }
        }
        return null;
    }

    // ---------- Validación ----------

    /** Valida las hojas (auditores, puntos y usuarios, en ese orden). */
    public static Resultado validar(Collection<Hoja> hojas, Catalogos cat, Existentes ex) {
        List<String> erroresArchivo = new ArrayList<>();
        Map<Tipo, List<String>> desconocidas = new LinkedHashMap<>();
        Map<Tipo, List<Hoja>> porTipo = new LinkedHashMap<>();
        Map<Tipo, Map<Integer, String>> columnas = new HashMap<>();
        for (Hoja h : hojas) {
            Revision r = revisar(h.tipo(), h.encabezados());
            if (!r.error().isEmpty()) {
                erroresArchivo.add(h.archivo() + " · " + h.tipo().hoja() + ": " + r.error());
                continue;
            }
            if (h.filas().size() > MAX_FILAS) {
                erroresArchivo.add(h.archivo() + " · " + h.tipo().hoja() + ": la hoja supera " + MAX_FILAS + " filas. Divídala en varias cargas.");
                continue;
            }
            if (!r.desconocidas().isEmpty()) {
                desconocidas.computeIfAbsent(h.tipo(), t -> new ArrayList<>()).addAll(r.desconocidas());
            }
            porTipo.computeIfAbsent(h.tipo(), t -> new ArrayList<>()).add(h);
            columnas.put(h.tipo(), r.columnas());
        }
        if (!erroresArchivo.isEmpty()) {
            return new Resultado(erroresArchivo, desconocidas, List.of());
        }
        Estado estado = new Estado(cat, ex);
        List<Fila> filas = new ArrayList<>();
        for (Tipo t : Tipo.values()) {
            for (Hoja h : porTipo.getOrDefault(t, List.of())) {
                Map<Integer, String> cols = revisar(t, h.encabezados()).columnas();
                for (FilaLeida f : h.filas()) {
                    Map<String, String> c = campos(t, cols, f.celdas());
                    if (c.values().stream().allMatch(String::isEmpty)) {
                        continue; // fila vacía
                    }
                    boolean ejemplo = f.celdas().stream().anyMatch(v -> v != null && normalizar(v).contains(MARCA_EJEMPLO));
                    filas.add(switch (t) {
                        case AUDITORES -> estado.auditor(h.archivo(), f.numero(), c, ejemplo);
                        case PUNTOS -> estado.punto(h.archivo(), f.numero(), c, ejemplo);
                        case USUARIOS -> estado.usuario(h.archivo(), f.numero(), c, ejemplo);
                    });
                }
            }
        }
        if (filas.isEmpty()) {
            erroresArchivo.add("La carga está vacía: no tiene filas con datos.");
        }
        return new Resultado(erroresArchivo, desconocidas, filas);
    }

    /** Lleva las celdas a los campos de la hoja: el primer sinónimo con valor gana. */
    static Map<String, String> campos(Tipo tipo, Map<Integer, String> columnas, List<String> celdas) {
        Map<String, String> c = new LinkedHashMap<>();
        for (String campo : COLUMNAS.get(tipo).keySet()) {
            c.put(campo, "");
        }
        for (var e : columnas.entrySet()) {
            String v = e.getKey() < celdas.size() && celdas.get(e.getKey()) != null ? celdas.get(e.getKey()).strip() : "";
            if (!v.isEmpty() && c.get(e.getValue()).isEmpty()) {
                c.put(e.getValue(), v);
            }
        }
        return c;
    }

    /** Estado de la carga mientras se resuelven las hojas: lo que existe más lo que esta misma carga crea. */
    private static final class Estado {
        private final Catalogos cat;
        private final Map<String, UsuarioV> porCorreo = new HashMap<>();
        private final Set<String> usuariosTomados = new HashSet<>();
        private final Map<String, Integer> correosAuditores = new HashMap<>();
        private final Map<String, Integer> correosUsuarios = new HashMap<>();
        private final List<PuntoV> puntos = new ArrayList<>();
        private final Map<PuntoV, Integer> puntosTocados = new HashMap<>();

        Estado(Catalogos cat, Existentes ex) {
            this.cat = cat;
            for (UsuarioExistente u : ex.usuarios()) {
                UsuarioV v = new UsuarioV(u.id(), u.usuario(), EnumSet.noneOf(Perfil.class), u.activo(), u.alcanceFuerza(), u.alcancePunto(),
                        u.alcanceRegional());
                v.perfiles.addAll(u.perfiles());
                porCorreo.put(u.correo().toLowerCase(Locale.ROOT), v);
                usuariosTomados.add(u.usuario());
            }
            for (PuntoExistente p : ex.puntos()) {
                puntos.add(new PuntoV(p.id(), 0, p.sigla(), p.nombre(), p.unidad(), p.fuerza(), p.modalidad(), p.activo()));
            }
        }

        // ----- Auditores -----

        Fila auditor(String archivo, int numero, Map<String, String> c, boolean ejemplo) {
            List<Problema> e = new ArrayList<>();
            if (ejemplo) {
                e.add(new Problema("fila", "es una fila de ejemplo de la plantilla: bórrela"));
            }
            String nombre = c.get("nombre");
            String correo = Correos.normalizar(c.get("correo"));
            nombre(e, nombre);
            if (correo.isEmpty()) {
                e.add(new Problema("correo", "falta (el auditor lo necesita para ingresar)"));
            } else if (!Correos.valido(correo)) {
                e.add(new Problema("correo", "no válido"));
            }
            String documento = c.get("documento");
            if (documento.isEmpty()) {
                e.add(new Problema("documento", "falta (va en la notificación de la visita para el ingreso a la unidad)"));
            } else if (!DOCUMENTO.matcher(documento).matches()) {
                e.add(new Problema("documento", "no válido (solo letras, números, punto o guion)"));
            }
            telefono(e, c.get("telefono"));
            String rol = rol(c.get("rol"));
            if ("".equals(rol)) {
                e.add(new Problema("rol", "no válido (químico farmacéutico, regente, auditor en salud, ingeniero de sistemas u otro)"));
            }
            boolean rolExplicito = !c.get("rol").isEmpty() || !c.get("qf").isEmpty();
            if (rol == null) {
                rol = siNo(c.get("qf")) ? "quimico_farmaceutico" : "otro";
            }
            String municipioBase = municipioTexto(e, "municipio_base", c.get("municipio_base"));
            String usuario = c.get("usuario").toLowerCase(Locale.ROOT);
            UsuarioV v = correo.isEmpty() ? null : porCorreo.get(correo);
            usuario = nombreDeUsuario(e, usuario, v, correo);
            Integer previa = correosAuditores.get(correo);
            if (previa != null) {
                e.add(new Problema("correo", "repetido en el archivo (fila " + previa + ")"));
            }
            if (v != null) {
                Set<Perfil> combinados = EnumSet.noneOf(Perfil.class);
                combinados.addAll(v.perfiles);
                combinados.add(Perfil.AUDITOR);
                Alcance.combinacionInvalida(combinados).ifPresent(m -> e.add(new Problema("correo", "ya es de un usuario que no puede tener el perfil Auditor. " + m)));
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("nombre", nombre);
            d.put("correo", correo);
            d.put("documento", documento);
            d.put("telefono", vacioANulo(c.get("telefono")));
            d.put("usuario", usuario);
            d.put("rol", rol);
            d.put("rolExplicito", rolExplicito);
            d.put("municipioBase", municipioBase);
            if (!e.isEmpty()) {
                return new Fila(Tipo.AUDITORES, archivo, numero, correo, Accion.ERROR, v == null ? null : v.id, d, e);
            }
            correosAuditores.put(correo, numero);
            if (v == null) {
                v = new UsuarioV(null, usuario, EnumSet.noneOf(Perfil.class), true, null, null, null);
                porCorreo.put(correo, v);
                usuariosTomados.add(usuario);
            }
            v.perfiles.add(Perfil.AUDITOR);
            return new Fila(Tipo.AUDITORES, archivo, numero, correo, v.id == null ? Accion.NUEVO : Accion.EXISTENTE, v.id, d, e);
        }

        // ----- Puntos -----

        Fila punto(String archivo, int numero, Map<String, String> c, boolean ejemplo) {
            List<Problema> e = new ArrayList<>();
            if (ejemplo) {
                e.add(new Problema("fila", "es una fila de ejemplo de la plantilla: bórrela"));
            }
            String sigla = c.get("sigla").toUpperCase(Locale.ROOT);
            if (!sigla.isEmpty() && !SIGLA.matcher(sigla).matches()) {
                e.add(new Problema("sigla", "no válida (de 2 a 20 letras mayúsculas, números o guion)"));
            }
            String nombre = c.get("nombre");
            String unidad = unidad(c.get("unidad"));
            if (!c.get("unidad").isEmpty() && unidad.isEmpty()) {
                e.add(new Problema("unidad", "no válida (ESM, FA o NC)"));
            }
            PuntoV porSigla = sigla.isEmpty() ? null : puntos.stream().filter(p -> sigla.equals(p.sigla)).findFirst().orElse(null);
            String unidadBusqueda = !unidad.isEmpty() ? unidad : porSigla != null ? porSigla.unidad : "";
            PuntoV porNombre = nombre.isEmpty() || unidadBusqueda.isEmpty() ? null
                    : puntos.stream().filter(p -> p.clave().equals(claveUnidad(nombre, unidadBusqueda))).findFirst().orElse(null);
            if (porSigla != null && porNombre != null && porSigla != porNombre) {
                e.add(new Problema("nombre", "el nombre y la unidad son de otro punto (no de esta sigla)"));
            }
            if (porSigla == null && !sigla.isEmpty() && porNombre != null && porNombre.sigla != null) {
                e.add(new Problema("sigla", "el nombre y la unidad son de un punto con otra sigla"));
            }
            PuntoV destino = porSigla != null ? porSigla : porNombre;
            Integer previa = destino == null ? null : puntosTocados.get(destino);
            if (previa != null) {
                e.add(new Problema("fila", "repetido en el archivo (fila " + previa + ")"));
            }
            boolean nuevo = destino == null;
            if (nuevo) {
                if (nombre.isEmpty()) {
                    e.add(new Problema("nombre", "falta (el punto no existe: para crearlo se necesitan nombre y unidad)"));
                }
                if (c.get("unidad").isEmpty()) {
                    e.add(new Problema("unidad", "falta (ESM, FA o NC)"));
                }
            }
            if (!nombre.isEmpty() && (nombre.codePointCount(0, nombre.length()) < 3 || nombre.codePointCount(0, nombre.length()) > 200)) {
                e.add(new Problema("nombre", "debe tener de 3 a 200 caracteres"));
            }
            String unidadEf = !unidad.isEmpty() ? unidad : destino != null ? destino.unidad : "";
            String fuerza = fuerza(c.get("fuerza"));
            if (!c.get("fuerza").isEmpty() && fuerza.isEmpty()) {
                e.add(new Problema("fuerza", "no válida o inactiva (" + String.join(", ", cat.fuerzas().keySet().stream().sorted().toList()) + ")"));
            }
            String fuerzaEf = !fuerza.isEmpty() ? fuerza : destino != null && destino.fuerza != null ? destino.fuerza : "NC".equals(unidadEf) ? "NA" : "";
            if (fuerza.isEmpty() && "NC".equals(unidadEf) && nuevo) {
                fuerza = "NA";
            }
            if (nuevo && fuerzaEf.isEmpty() && c.get("fuerza").isEmpty()) {
                e.add(new Problema("fuerza", "falta (EJC, ARC o FAC; vacía solo en el nivel central)"));
            }
            if ("NC".equals(unidadEf) && !fuerzaEf.isEmpty() && !"NA".equals(fuerzaEf)) {
                e.add(new Problema("fuerza", "el nivel central va con fuerza NA (déjela vacía)"));
            }
            if ("ESM".equals(unidadEf) && "NA".equals(fuerzaEf)) {
                e.add(new Problema("fuerza", "un ESM pertenece a una fuerza (EJC, ARC o FAC)"));
            }
            String modalidad = null;
            if (!c.get("modalidad").isEmpty()) {
                for (String m : cat.modalidades()) {
                    if (normalizar(m).equals(normalizar(c.get("modalidad")))) {
                        modalidad = m;
                    }
                }
                if (modalidad == null) {
                    e.add(new Problema("modalidad", "no válida (" + String.join(", ", cat.modalidades()) + ")"));
                }
            }
            String modalidadEf = modalidad != null ? modalidad : destino != null ? destino.modalidad : null;
            if ("ESM".equals(unidadEf) && modalidadEf == null && c.get("modalidad").isEmpty()) {
                e.add(new Problema("modalidad", "falta la modalidad del ESM (" + String.join(", ", cat.modalidades()) + ")"));
            }
            String municipio = municipioPunto(e, c);
            largo(e, "direccion", c.get("direccion"), 200);
            largo(e, "contacto", c.get("contacto"), 150);
            largo(e, "nit", c.get("nit"), 20);
            List<String> correos = correos(e, c.get("correos"));
            Long regional = null;
            if (!c.get("regional").isEmpty()) {
                regional = regional(c.get("regional"));
                if (regional == null) {
                    e.add(new Problema("regional", "no existe o está inactiva (regístrela en Puntos y auditores › Contrato)"));
                }
            }
            String llega = municipioTexto(e, "llega_desde", c.get("llega_desde"));
            String auditor = null;
            if (!c.get("auditor_asignado").isEmpty()) {
                auditor = Correos.normalizar(c.get("auditor_asignado"));
                UsuarioV a = porCorreo.get(auditor);
                if (a == null || !a.activo || !a.perfiles.contains(Perfil.AUDITOR)) {
                    e.add(new Problema("auditor_asignado", "no es un auditor activo de la base ni de la hoja de auditores (use su correo)"));
                }
            }
            Boolean activo = activo(e, c.get("activo"));

            Map<String, Object> d = new LinkedHashMap<>();
            poner(d, "sigla", sigla.isEmpty() ? null : sigla);
            poner(d, "nombre", vacioANulo(nombre));
            poner(d, "unidad", vacioANulo(unidad));
            poner(d, "fuerza", vacioANulo(fuerza));
            poner(d, "modalidad", "ESM".equals(unidadEf) ? modalidad : null);
            poner(d, "nit", "FA".equals(unidadEf) ? vacioANulo(c.get("nit")) : null); // el NIT solo aplica a la farmacia alterna
            poner(d, "municipio", municipio);
            poner(d, "direccion", vacioANulo(c.get("direccion")));
            poner(d, "contacto", vacioANulo(c.get("contacto")));
            poner(d, "correos", correos.isEmpty() ? null : correos);
            poner(d, "exigeQf", c.get("exige_qf").isEmpty() ? null : siNo(c.get("exige_qf")));
            poner(d, "regionalId", regional);
            poner(d, "llegaDesde", llega);
            poner(d, "auditorAsignado", auditor);
            poner(d, "activo", activo);
            String clave = !sigla.isEmpty() ? sigla : destino != null && destino.sigla != null ? destino.sigla
                    : nombre + " · " + unidadEf;
            if (!e.isEmpty()) {
                return new Fila(Tipo.PUNTOS, archivo, numero, clave, Accion.ERROR, destino == null ? null : destino.id, d, e);
            }
            if (nuevo) {
                destino = new PuntoV(null, numero, vacioANulo(sigla), nombre, unidadEf, fuerzaEf, modalidadEf, activo == null || activo);
                puntos.add(destino);
            } else {
                if (!sigla.isEmpty()) {
                    destino.sigla = sigla;
                }
                if (!nombre.isEmpty()) {
                    destino.nombre = nombre;
                }
                destino.unidad = unidadEf;
                destino.fuerza = fuerzaEf;
                destino.modalidad = modalidadEf;
                if (activo != null) {
                    destino.activo = activo;
                }
            }
            puntosTocados.put(destino, numero);
            d.put("filaPunto", destino.fila);
            return new Fila(Tipo.PUNTOS, archivo, numero, clave, nuevo ? Accion.NUEVO : Accion.EXISTENTE, destino.id, d, e);
        }

        private String municipioPunto(List<Problema> e, Map<String, String> c) {
            if (!c.get("divipola").isEmpty()) {
                return municipioTexto(e, "divipola", c.get("divipola"));
            }
            if (c.get("departamento").isEmpty() && c.get("municipio").isEmpty()) {
                return null;
            }
            String codigo = municipio(c.get("departamento"), c.get("municipio"));
            if (codigo == null) {
                e.add(new Problema("municipio", "departamento o municipio no encontrado"));
            }
            return codigo;
        }

        // ----- Usuarios -----

        Fila usuario(String archivo, int numero, Map<String, String> c, boolean ejemplo) {
            List<Problema> e = new ArrayList<>();
            if (ejemplo) {
                e.add(new Problema("fila", "es una fila de ejemplo de la plantilla: bórrela"));
            }
            String nombre = c.get("nombre");
            String correo = Correos.normalizar(c.get("correo"));
            nombre(e, nombre);
            if (correo.isEmpty()) {
                e.add(new Problema("correo", "falta (el usuario lo necesita para ingresar)"));
            } else if (!Correos.valido(correo)) {
                e.add(new Problema("correo", "no válido"));
            }
            String documento = c.get("documento");
            if (!documento.isEmpty() && !DOCUMENTO.matcher(documento).matches()) {
                e.add(new Problema("documento", "no válido (solo letras, números, punto o guion)"));
            }
            telefono(e, c.get("telefono"));
            UsuarioV v = correo.isEmpty() ? null : porCorreo.get(correo);
            String usuario = nombreDeUsuario(e, c.get("usuario").toLowerCase(Locale.ROOT), v, correo);
            Integer previa = correosUsuarios.get(correo);
            if (previa != null) {
                e.add(new Problema("correo", "repetido en el archivo (fila " + previa + ")"));
            }
            Set<Perfil> nuevos = perfiles(e, c.get("perfiles"));
            if (v == null && nuevos.isEmpty() && c.get("perfiles").isEmpty()) {
                e.add(new Problema("perfiles", "falta (nombres o números separados por coma)"));
            }
            Set<Perfil> combinados = EnumSet.noneOf(Perfil.class);
            combinados.addAll(nuevos);
            if (v != null) {
                combinados.addAll(v.perfiles);
            }
            Alcance.combinacionInvalida(combinados).ifPresent(m -> e.add(new Problema("perfiles", m)));

            // Alcance: obligatorio según el perfil; el que no aplica, vacío
            String fuerza = null;
            if (!c.get("alcance_fuerza").isEmpty()) {
                if (!combinados.contains(Perfil.SANIDAD)) {
                    e.add(new Problema("alcance_fuerza", "no aplica a los perfiles del usuario (déjela vacía)"));
                } else {
                    fuerza = fuerza(c.get("alcance_fuerza"));
                    if (fuerza.isEmpty() || !Boolean.TRUE.equals(cat.fuerzas().get(fuerza))) {
                        e.add(new Problema("alcance_fuerza", "no es una fuerza con sanidad activa (DISAN Ejército, DISAN Armada o JEFSA)"));
                        fuerza = null;
                    }
                }
            } else if (combinados.contains(Perfil.SANIDAD) && (v == null || v.alcanceFuerza == null)) {
                e.add(new Problema("alcance_fuerza", "el perfil Sanidad necesita la fuerza (EJC, ARC o FAC)"));
            }
            PuntoV punto = null;
            if (!c.get("alcance_punto").isEmpty()) {
                if (!combinados.contains(Perfil.ESM)) {
                    e.add(new Problema("alcance_punto", "no aplica a los perfiles del usuario (déjelo vacío)"));
                } else {
                    punto = esmActivo(c.get("alcance_punto"));
                    if (punto == null) {
                        e.add(new Problema("alcance_punto", "no es un ESM activo de la base ni de la hoja de puntos (use su sigla o su nombre)"));
                    }
                }
            } else if (combinados.contains(Perfil.ESM) && (v == null || v.alcancePunto == null)) {
                e.add(new Problema("alcance_punto", "el perfil ESM necesita el establecimiento (sigla o nombre del ESM)"));
            }
            Long regional = null;
            if (!c.get("alcance_regional").isEmpty()) {
                if (!combinados.contains(Perfil.OPL_REGIONAL)) {
                    e.add(new Problema("alcance_regional", "no aplica a los perfiles del usuario (déjela vacía)"));
                } else {
                    regional = regional(c.get("alcance_regional"));
                    if (regional == null) {
                        e.add(new Problema("alcance_regional", "no existe o está inactiva (regístrela en Puntos y auditores › Contrato)"));
                    }
                }
            } else if (combinados.contains(Perfil.OPL_REGIONAL) && (v == null || v.alcanceRegional == null)) {
                e.add(new Problema("alcance_regional", "el perfil OPL regional necesita la regional"));
            }
            Boolean activo = activo(e, c.get("activo"));

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("nombre", vacioANulo(nombre));
            d.put("correo", correo);
            d.put("usuario", usuario);
            poner(d, "documento", vacioANulo(documento));
            poner(d, "telefono", vacioANulo(c.get("telefono")));
            d.put("perfiles", List.copyOf(nuevos));
            poner(d, "alcanceFuerza", fuerza);
            if (punto != null) {
                if (punto.id != null) {
                    d.put("alcancePunto", punto.id);
                } else {
                    d.put("alcancePuntoFila", punto.fila);
                }
            }
            poner(d, "alcanceRegional", regional);
            poner(d, "activo", activo);
            if (!e.isEmpty()) {
                return new Fila(Tipo.USUARIOS, archivo, numero, correo, Accion.ERROR, v == null ? null : v.id, d, e);
            }
            correosUsuarios.put(correo, numero);
            boolean nuevo = v == null;
            if (nuevo) {
                v = new UsuarioV(null, usuario, EnumSet.noneOf(Perfil.class), activo == null || activo, null, null, null);
                porCorreo.put(correo, v);
                usuariosTomados.add(usuario);
            }
            v.perfiles.addAll(nuevos);
            if (fuerza != null) {
                v.alcanceFuerza = fuerza;
            }
            if (punto != null) {
                v.alcancePunto = punto.id != null ? punto.id : -punto.fila;
            }
            if (regional != null) {
                v.alcanceRegional = regional;
            }
            if (activo != null) {
                v.activo = activo;
            }
            // Un usuario creado en la hoja de auditores de esta carga ya no es «nuevo» aquí: se le agregan perfiles
            return new Fila(Tipo.USUARIOS, archivo, numero, correo, nuevo ? Accion.NUEVO : Accion.EXISTENTE, v.id, d, e);
        }

        private Set<Perfil> perfiles(List<Problema> e, String texto) {
            Set<Perfil> r = EnumSet.noneOf(Perfil.class);
            if (texto.isEmpty()) {
                return r;
            }
            boolean desconocido = false;
            for (String parte : texto.split("[,;]+")) {
                String p = parte.strip();
                if (p.isEmpty()) {
                    continue;
                }
                Integer id = null;
                if (p.matches("\\d{1,3}")) {
                    id = cat.perfiles().containsKey(Integer.parseInt(p)) ? Integer.parseInt(p) : null;
                } else {
                    for (var en : cat.perfiles().entrySet()) {
                        if (normalizar(en.getValue()).equals(normalizar(p))) {
                            id = en.getKey();
                        }
                    }
                }
                Optional<Perfil> perfil = id == null ? Optional.empty() : Perfil.deId(id);
                if (perfil.isEmpty()) {
                    desconocido = true;
                } else if (perfil.get() == Perfil.ADMINISTRADOR) {
                    e.add(new Problema("perfiles", MENSAJE_ADMINISTRADOR));
                } else if (perfil.get() == Perfil.AUDITOR) {
                    e.add(new Problema("perfiles", MENSAJE_AUDITOR));
                } else {
                    r.add(perfil.get());
                }
            }
            if (desconocido) {
                e.add(new Problema("perfiles", "hay un perfil que no existe o está inactivo (use el nombre vigente o su número)"));
            }
            return r;
        }

        /** ESM activo por sigla o nombre, de la base o de la hoja de puntos de esta carga. */
        private PuntoV esmActivo(String texto) {
            String sigla = texto.strip().toUpperCase(Locale.ROOT);
            String nombre = normalizar(texto);
            return puntos.stream().filter(p -> "ESM".equals(p.unidad) && p.activo)
                    .filter(p -> sigla.equals(p.sigla) || normalizar(p.nombre).equals(nombre))
                    .findFirst().orElse(null);
        }

        // ----- Apoyo común -----

        private String nombreDeUsuario(List<Problema> e, String escrito, UsuarioV v, String correo) {
            if (!escrito.isEmpty() && !USUARIO.matcher(escrito).matches()) {
                e.add(new Problema("usuario", "no válido (3 a 50 letras minúsculas sin tildes, números, punto, guion o guion bajo)"));
                return escrito;
            }
            if (v != null) {
                if (!escrito.isEmpty() && !escrito.equals(v.usuario)) {
                    e.add(new Problema("usuario", "el correo ya es de un usuario con otro nombre de usuario"));
                }
                return v.usuario;
            }
            if (!escrito.isEmpty()) {
                if (usuariosTomados.contains(escrito)) {
                    e.add(new Problema("usuario", "ya existe con otro correo"));
                }
                return escrito;
            }
            return correo.contains("@") ? usuarioLibre(correo, usuariosTomados) : "";
        }

        private Long regional(String texto) {
            for (var en : cat.regionales().entrySet()) {
                if (normalizar(en.getValue()).equals(normalizar(texto))) {
                    return en.getKey();
                }
            }
            return null;
        }

        private String fuerza(String texto) {
            String n = normalizar(texto);
            if (n.isEmpty()) {
                return "";
            }
            String codigo = n.toUpperCase(Locale.ROOT);
            if (!cat.fuerzas().containsKey(codigo)) {
                codigo = n.contains("ejercito") ? "EJC" : n.contains("armada") ? "ARC" : n.matches(".*(aerea|aeroespacial).*") ? "FAC"
                        : n.contains("no aplica") ? "NA" : "";
            }
            return cat.fuerzas().containsKey(codigo) ? codigo : "";
        }

        /** Código DIVIPOLA (5001 ⇒ 05001) o «Departamento / Municipio». null si viene vacío. */
        private String municipioTexto(List<Problema> e, String campo, String texto) {
            if (texto == null || texto.isBlank()) {
                return null;
            }
            String codigo;
            if (texto.contains("/")) {
                String[] partes = texto.split("/", 2);
                codigo = municipio(partes[0], partes[1]);
            } else {
                String digitos = texto.replaceAll("\\D", "");
                codigo = digitos.isEmpty() || digitos.length() > 5 ? null : "0".repeat(5 - digitos.length()) + digitos;
                if (codigo != null && !cat.municipios().containsKey(codigo)) {
                    codigo = null;
                }
            }
            if (codigo == null) {
                e.add(new Problema(campo, "el municipio no existe (código DIVIPOLA o «Departamento / Municipio»)"));
            }
            return codigo;
        }

        private String municipio(String departamento, String municipio) {
            String dep = null;
            String d = normalizar(departamento);
            for (var en : cat.departamentos().entrySet()) {
                if (normalizar(en.getValue()).equals(d) || en.getKey().equals(d.length() == 1 ? "0" + d : d)) {
                    dep = en.getKey();
                    break;
                }
            }
            if (dep == null || municipio == null || municipio.isBlank()) {
                return null; // el municipio es el dato que se guarda: sin él no hay ubicación
            }
            String m = normalizar(municipio);
            for (var en : cat.municipios().entrySet()) {
                if (en.getValue().departamento().equals(dep)
                        && normalizar(en.getValue().nombre().replace(" (área no municipalizada)", "")).equals(m)) {
                    return en.getKey();
                }
            }
            return null;
        }
    }

    private static final class UsuarioV {
        final Long id;
        final String usuario;
        final Set<Perfil> perfiles;
        boolean activo;
        String alcanceFuerza;
        Long alcancePunto;
        Long alcanceRegional;

        UsuarioV(Long id, String usuario, Set<Perfil> perfiles, boolean activo, String alcanceFuerza, Long alcancePunto, Long alcanceRegional) {
            this.id = id;
            this.usuario = usuario;
            this.perfiles = perfiles;
            this.activo = activo;
            this.alcanceFuerza = alcanceFuerza;
            this.alcancePunto = alcancePunto;
            this.alcanceRegional = alcanceRegional;
        }
    }

    private static final class PuntoV {
        final Long id;
        final int fila;
        String sigla;
        String nombre;
        String unidad;
        String fuerza;
        String modalidad;
        boolean activo;

        PuntoV(Long id, int fila, String sigla, String nombre, String unidad, String fuerza, String modalidad, boolean activo) {
            this.id = id;
            this.fila = fila;
            this.sigla = sigla;
            this.nombre = nombre;
            this.unidad = unidad;
            this.fuerza = fuerza;
            this.modalidad = modalidad;
            this.activo = activo;
        }

        String clave() {
            return claveUnidad(nombre, unidad);
        }
    }

    // ---------- Reglas sueltas (iguales al PHP) ----------

    /** Sin tildes, en minúscula y sin espacios a los lados. */
    public static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return MARCAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT).strip();
    }

    /** Encabezado normalizado: «Código municipio» ⇒ codigo_municipio. */
    public static String clave(String encabezado) {
        String n = NO_ALFANUMERICO.matcher(normalizar(encabezado)).replaceAll("_");
        return n.replaceAll("^_+|_+$", "");
    }

    public static String claveUnidad(String nombre, String unidad) {
        return normalizar(nombre).replaceAll("\\s+", " ") + "|" + unidad;
    }

    /** Sí / No escrito de distintas formas en una celda. */
    public static boolean siNo(String texto) {
        return normalizar(texto).matches("^(si|s|x|1|true|verdadero|yes)$");
    }

    public static String unidad(String s) {
        String n = normalizar(s);
        if (Set.of("esm", "fa", "nc").contains(n)) {
            return n.toUpperCase(Locale.ROOT);
        }
        if (n.contains("farmacia")) {
            return "FA";
        }
        if (n.contains("central")) {
            return "NC";
        }
        if (n.matches(".*(esm|establecimiento|sanidad).*")) {
            return "ESM";
        }
        return "";
    }

    /** Rol técnico escrito de distintas formas (decisión 2a). null si viene vacío; "" si no se reconoce. */
    public static String rol(String texto) {
        String n = normalizar(texto).replaceAll("[^a-z ]+", " ").replaceAll("\\s+", " ").strip();
        if (n.isEmpty()) {
            return null;
        }
        if (n.matches("^(qf|quimic[oa] farmaceutic[oa])$")) {
            return "quimico_farmaceutico";
        }
        if (n.startsWith("regente")) {
            return "regente";
        }
        if (n.matches("^auditor(a)?( en)? salud$")) {
            return "auditor_salud";
        }
        if (n.matches("^ingenier[oa]( de)?( sistemas)?$")) {
            return "ingeniero_sistemas";
        }
        if (n.equals("otro") || n.equals("otra")) {
            return "otro";
        }
        return "";
    }

    /** Usuario libre a partir del correo (como CrearAdministrador): solo a-z0-9._-, de 3 a 45 y sufijo 2, 3… si ya existe. */
    public static String usuarioLibre(String correo, Set<String> tomados) {
        String base = correo.substring(0, correo.indexOf('@')).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        base = base.length() > 45 ? base.substring(0, 45) : base;
        base = (base + "000").substring(0, Math.max(3, base.length()));
        String candidato = base;
        for (int i = 2; tomados.contains(candidato); i++) {
            candidato = base + i;
        }
        return candidato;
    }

    /** Documento o teléfono en la bitácora: solo los 3 últimos caracteres (Ley 1581). */
    public static String enmascarar(String valor) {
        if (valor == null || valor.isBlank()) {
            return valor;
        }
        int n = valor.length();
        return n <= 3 ? "*".repeat(n) : "*".repeat(n - 3) + valor.substring(n - 3);
    }

    private static List<String> correos(List<Problema> e, String texto) {
        Set<String> r = new LinkedHashSet<>();
        boolean malo = false;
        for (String parte : (texto == null ? "" : texto).split("[\\s,;]+")) {
            String m = Correos.normalizar(parte);
            if (m.isEmpty()) {
                continue;
            }
            if (!Correos.valido(m)) {
                malo = true;
            } else {
                r.add(m);
            }
        }
        if (malo) {
            e.add(new Problema("correos", "hay un correo no válido"));
        }
        if (r.size() > 10) {
            e.add(new Problema("correos", "máximo 10 correos"));
        }
        if (String.join(", ", r).length() > 500) {
            e.add(new Problema("correos", "supera 500 caracteres"));
        }
        return List.copyOf(r);
    }

    private static void nombre(List<Problema> e, String nombre) {
        int n = nombre.codePointCount(0, nombre.length());
        if (n == 0) {
            e.add(new Problema("nombre", "falta"));
        } else if (n < 3 || n > 150) {
            e.add(new Problema("nombre", "debe tener de 3 a 150 caracteres"));
        }
    }

    private static void telefono(List<Problema> e, String telefono) {
        largo(e, "telefono", telefono, 30);
    }

    private static void largo(List<Problema> e, String campo, String valor, int max) {
        if (valor != null && valor.codePointCount(0, valor.length()) > max) {
            e.add(new Problema(campo, "supera " + max + " caracteres"));
        }
    }

    private static Boolean activo(List<Problema> e, String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String n = normalizar(texto);
        if (siNo(n) || n.equals("activo")) {
            return true;
        }
        if (n.matches("^(no|n|0|false|falso|inactivo)$")) {
            return false;
        }
        e.add(new Problema("activo", "escriba Sí o No"));
        return null;
    }

    private static void poner(Map<String, Object> d, String campo, Object valor) {
        if (valor != null) {
            d.put(campo, valor);
        }
    }

    private static String vacioANulo(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
