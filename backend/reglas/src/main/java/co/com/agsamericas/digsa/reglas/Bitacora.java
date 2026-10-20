package co.com.agsamericas.digsa.reglas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bitácora de la aplicación (Anexo 5, prueba 15; decisión 5): usuario, fecha, tipo de operación y detalle. Registra inserción,
 * actualización, eliminación, consulta, ingreso y salida. El detalle solo lleva identificadores y filtros (lista blanca), nunca
 * datos personales ni secretos (Ley 1581).
 *
 * <p>En el PHP la operación de cada acción salía de dos tablas por módulo; aquí cada endpoint la declara con su anotación
 * ({@link Declarada}) y una prueba de arquitectura exige que todos la tengan.
 */
public final class Bitacora {

    /** Las 6 operaciones del Anexo 5, con el valor que se guarda en la base. */
    public enum Operacion {
        INSERCION, ACTUALIZACION, ELIMINACION, CONSULTA, INGRESO, SALIDA;

        public String valor() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Lo que declara un endpoint. SEGUN_ID y SEGUN_CONFIRMAR se resuelven con los datos; SIN_REGISTRO no deja fila. */
    public enum Declarada {
        INSERCION, ACTUALIZACION, ELIMINACION, CONSULTA, INGRESO, SALIDA, SEGUN_ID, SEGUN_CONFIRMAR, SIN_REGISTRO
    }

    /** Eventos que registran los servicios ⇒ operación. */
    public static final Map<String, Operacion> TIPOS = Map.ofEntries(
            Map.entry("login", Operacion.INGRESO), Map.entry("login_fallido", Operacion.INGRESO),
            Map.entry("captcha_fallido", Operacion.INGRESO), Map.entry("mfa_enviado", Operacion.INGRESO),
            Map.entry("mfa_fallido", Operacion.INGRESO),
            Map.entry("logout", Operacion.SALIDA), Map.entry("sesion_expirada", Operacion.SALIDA),
            Map.entry("sesion_reemplazada", Operacion.SALIDA), Map.entry("sesion_cerrada", Operacion.SALIDA),
            Map.entry("perfil_editado", Operacion.ACTUALIZACION), Map.entry("parametro_editado", Operacion.ACTUALIZACION),
            Map.entry("regional_creada", Operacion.INSERCION), Map.entry("regional_editada", Operacion.ACTUALIZACION),
            Map.entry("punto_creado", Operacion.INSERCION), Map.entry("punto_editado", Operacion.ACTUALIZACION),
            Map.entry("auditor_editado", Operacion.ACTUALIZACION), Map.entry("puntos_carga", Operacion.INSERCION),
            Map.entry("fuerza_creada", Operacion.INSERCION), Map.entry("fuerza_editada", Operacion.ACTUALIZACION),
            Map.entry("fuerza_eliminada", Operacion.ELIMINACION),
            Map.entry("visita_programada", Operacion.INSERCION), Map.entry("visita_editada", Operacion.ACTUALIZACION),
            Map.entry("visita_notificada", Operacion.ACTUALIZACION), Map.entry("visita_notificacion_fallida", Operacion.ACTUALIZACION), Map.entry("visita_reprogramada", Operacion.ACTUALIZACION),
            Map.entry("visita_cancelada", Operacion.ACTUALIZACION), Map.entry("visita_iniciada", Operacion.ACTUALIZACION),
            Map.entry("visita_reasignada", Operacion.ACTUALIZACION), Map.entry("reparto_movido", Operacion.ACTUALIZACION),
            Map.entry("visita_reabierta", Operacion.ACTUALIZACION), Map.entry("visita_cerrada", Operacion.ACTUALIZACION),
            Map.entry("usuario_creado", Operacion.INSERCION), Map.entry("admin_creado", Operacion.INSERCION),
            Map.entry("catalogo_creado", Operacion.INSERCION),
            Map.entry("usuario_editado", Operacion.ACTUALIZACION), Map.entry("usuario_estado", Operacion.ACTUALIZACION),
            Map.entry("usuario_restablecer", Operacion.ACTUALIZACION), Map.entry("clave_cambio", Operacion.ACTUALIZACION),
            Map.entry("clave_olvido", Operacion.ACTUALIZACION), Map.entry("catalogo_editado", Operacion.ACTUALIZACION),
            Map.entry("catalogo_eliminado", Operacion.ELIMINACION),
            Map.entry("reporte_enviado", Operacion.CONSULTA), Map.entry("reporte_descargado", Operacion.CONSULTA),
            Map.entry("acceso_denegado", Operacion.CONSULTA));

    /** Datos de la petición que pueden ir al detalle: identificadores y filtros. No "motivo": es texto libre. */
    public static final Set<String> CLAVES_DETALLE = Set.of("id", "archivo", "acta", "revision", "tipo", "estado",
            "punto_id", "auditor_id", "fecha", "hora", "desde", "hasta", "fuerza", "unidad", "confirmar", "prefijo", "reemplazar",
            "dep", "parte", "usuario_id", "formato", "reporte", "cantidad", "solicitud_id", "regional_id", "municipio", "ciclo",
            "anio", "item", "catalogo", "componente", "operacion", "pagina");

    /** Nunca van al detalle, aunque alguien las agregue a la lista blanca: secretos del ingreso y de las contraseñas. */
    public static final Set<String> SECRETOS = Set.of("clave", "confirmacion", "password", "contrasena", "codigo", "tkn", "token", "captcha");

    static final int MAX_VALOR = 60;
    static final int MAX_ESTADO = 20;
    public static final int MAX_DETALLE = 500;

    private static final Pattern ESPACIOS = Pattern.compile("[\\r\\n\\t]+");
    private static final Pattern ACCION_LIMPIA = Pattern.compile("[^\\w.-]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern CORREO = Pattern.compile("[^\\s<>()\"',;:]+@[^\\s<>()\"',;:]+\\.[a-z]{2,}",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private Bitacora() {
    }

    /** Operación de una petición, o vacío si no se registra (el latido de la sesión). */
    public static Optional<Operacion> operacion(String metodo, Declarada declarada, Map<String, ?> datos) {
        if (declarada == Declarada.SIN_REGISTRO) {
            return Optional.empty();
        }
        if ("GET".equalsIgnoreCase(metodo)) {
            return Optional.of(Operacion.CONSULTA);
        }
        if (declarada == null) {
            return Optional.of(Operacion.ACTUALIZACION);
        }
        return Optional.of(switch (declarada) {
            case SEGUN_ID -> entero(datos.get("id")) > 0 ? Operacion.ACTUALIZACION : Operacion.INSERCION;
            case SEGUN_CONFIRMAR -> "1".equals(texto(datos.get("confirmar"))) || Boolean.TRUE.equals(datos.get("confirmar"))
                    ? Operacion.INSERCION : Operacion.CONSULTA;
            case SIN_REGISTRO -> throw new IllegalStateException("inalcanzable");
            default -> Operacion.valueOf(declarada.name());
        });
    }

    public static Operacion operacionDeTipo(String tipo) {
        return TIPOS.getOrDefault(tipo, Operacion.ACTUALIZACION);
    }

    /** "modulo.accion · clave=valor, ..." solo con las claves permitidas, en el orden en que llegan. */
    public static String detalle(String modulo, String accion, Map<String, ?> datos) {
        List<String> pares = new ArrayList<>();
        for (var e : datos.entrySet()) {
            Object v = e.getValue();
            if (SECRETOS.contains(e.getKey()) || !CLAVES_DETALLE.contains(e.getKey()) || !esEscalar(v)) {
                continue;
            }
            String valor = ESPACIOS.matcher(String.valueOf(v)).replaceAll(" ");
            if (e.getKey().equals("estado") && largo(valor) > MAX_ESTADO) {
                continue; // en el checklist "estado" es el contenido completo
            }
            if (largo(valor) > MAX_VALOR) {
                valor = cortar(valor, MAX_VALOR - 1) + "…";
            }
            pares.add(e.getKey() + "=" + valor);
        }
        // La acción puede venir del cliente: sin saltos de línea y corta
        String limpia = cortar(ACCION_LIMPIA.matcher(accion == null ? "" : accion).replaceAll("_"), 40);
        String texto = modulo + "." + limpia + (pares.isEmpty() ? "" : " · " + String.join(", ", pares));
        return cortar(texto, MAX_DETALLE);
    }

    /** Petición que terminó: módulo, método, acción, lo declarado, los datos y lo que anotaron los servicios. */
    public record Peticion(String modulo, String metodo, String accion, Declarada declarada, Map<String, ?> datos,
                           List<Anotacion> anotaciones) {
        public Peticion {
            datos = datos == null ? Map.of() : datos;
            anotaciones = anotaciones == null ? List.of() : List.copyOf(anotaciones);
        }
    }

    public record Anotacion(String tipo, String descripcion) {
    }

    public record Fila(Operacion operacion, String tipo, String descripcion) {
    }

    /**
     * Qué se registra al terminar una petición. No: las respuestas rechazadas sin efecto (validación, consultas con error) ni el
     * latido. Sí: lo exitoso, el acceso denegado (403) y la falta de token CSRF (419), las escrituras con error del servidor y todo
     * lo que un servicio anotó, con el código si no fue exitoso. El código y las anotaciones caben siempre en los 500 caracteres.
     */
    public static Optional<Fila> fila(Peticion p, int codigo, boolean transaccionAbierta) {
        Optional<Operacion> operacion = operacion(p.metodo(), p.declarada(), p.datos());
        boolean denegado = codigo == 403 || codigo == 419;
        boolean escrituraConError = codigo >= 500 && !"GET".equalsIgnoreCase(p.metodo());
        boolean anotada = !p.anotaciones().isEmpty();
        if (!anotada && !denegado && !escrituraConError && (operacion.isEmpty() || codigo >= 300)) {
            return Optional.empty();
        }
        String sufijo = (anotada ? " · " + p.anotaciones().stream().map(Anotacion::descripcion).collect(Collectors.joining("; ")) : "")
                + (codigo >= 300 ? " · HTTP " + codigo : "")
                + (transaccionAbierta ? " · transacción sin confirmar" : "");
        sufijo = cortar(sufijo, MAX_DETALLE - 60); // siempre queda espacio para el comienzo del detalle
        String detalle = cortar(detalle(p.modulo(), p.accion(), p.datos()), MAX_DETALLE - largo(sufijo));
        Operacion op = denegado ? operacionDeTipo("acceso_denegado") : operacion.orElse(Operacion.CONSULTA);
        String tipo = denegado ? "acceso_denegado" : anotada ? p.anotaciones().getFirst().tipo() : p.modulo() + "." + p.accion();
        return Optional.of(new Fila(op, tipo, detalle + sufijo));
    }

    /**
     * Valores de antes y después de una modificación (Anexo 5, prueba 15: la trazabilidad de las modificaciones). Los campos con datos
     * personales (Ley 1581) dicen solo que cambiaron, sin los valores. Solo se listan los campos que cambiaron, en el orden de
     * {@code despues}.
     *
     * @return p. ej. "estado: «programada» → «cancelada»; nombre: modificado" o vacío si nada cambió
     */
    public static String cambios(Map<String, ?> antes, Map<String, ?> despues, Set<String> sinValor) {
        List<String> partes = new ArrayList<>();
        for (var e : despues.entrySet()) {
            Object a = antes.get(e.getKey());
            Object b = e.getValue();
            if (java.util.Objects.equals(normalizar(a), normalizar(b))) {
                continue;
            }
            if (sinValor.contains(e.getKey())) {
                partes.add(e.getKey() + ": modificado");
            } else {
                partes.add(e.getKey() + ": «" + cortar(sinCorreos(normalizar(a)), 40) + "» → «" + cortar(sinCorreos(normalizar(b)), 40) + "»");
            }
        }
        return String.join("; ", partes);
    }

    private static String normalizar(Object v) {
        if (v == null) {
            return "";
        }
        String s = v instanceof java.util.Collection<?> c ? c.stream().map(String::valueOf).sorted().collect(Collectors.joining(","))
                : String.valueOf(v);
        return ESPACIOS.matcher(s).replaceAll(" ").strip();
    }

    /** Sin correos en la bitácora (Ley 1581): p. ej. un error de SMTP que lista los destinatarios. */
    public static String sinCorreos(String texto) {
        return texto == null ? null : CORREO.matcher(texto).replaceAll("[correo]");
    }

    /** Usuario escrito en un ingreso fallido: solo los 3 primeros caracteres. */
    public static String enmascararUsuario(String escrito) {
        String s = escrito == null ? "" : escrito.strip();
        return cortar(s, 3) + "***";
    }

    /** Corta contando caracteres (puntos de código), sin romper UTF-8. */
    public static String cortar(String s, int max) {
        if (s == null || largo(s) <= max) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, Math.max(0, max)));
    }

    private static int largo(String s) {
        return s.codePointCount(0, s.length());
    }

    private static boolean esEscalar(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean;
    }

    private static String texto(Object v) {
        return v == null ? "" : String.valueOf(v).strip();
    }

    private static long entero(Object v) {
        try {
            return Long.parseLong(texto(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
