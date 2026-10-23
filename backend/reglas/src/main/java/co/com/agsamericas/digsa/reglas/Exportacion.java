package co.com.agsamericas.digsa.reglas;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exportación completa de los datos (duda 15.2, decisión 2026-10-20 15.2; docs/especificaciones/exportacion-alertas): un CSV por tabla
 * de la aplicación, la carpeta de soportes por visita con su índice y huella, y un LEEME. Formato de la DIGSA (GMI P77 f): UTF-8 con
 * BOM, «;», coma decimal, fechas en hora de Colombia de 24 h y sin fórmulas. Ningún secreto sale: contraseñas, enlaces, códigos ni sus
 * huellas, ni sesiones.
 */
public final class Exportacion {

    /** Tablas que se exportan, en el orden del ZIP. Una prueba de integración las compara con information_schema. */
    public static final List<String> TABLAS = List.of(
            // V1 · identidad y bitácora (log_transacciones sale completa: todas sus particiones en un solo CSV, decisión 7.5)
            "perfiles", "usuarios", "usuarios_perfiles", "mfa_codigos", "log_transacciones",
            // V30 · parámetros de seguridad del administrador (sesión 2: usuarios, roles y logs)
            "seg_parametros",
            // V31 · roles adicionales parametrizables (Anexo 5, 1.12)
            "perfiles_pantallas",
            // V3 · catálogos, maestros y checklist
            "par_departamentos", "par_municipios", "par_unidades", "par_fuerzas", "par_regionales", "par_modalidades", "par_tipos_visita",
            "par_parametros", "par_festivos", "catalogos", "catalogo_valores", "opl", "puntos", "auditores", "chk_versiones", "chk_vacios",
            "chk_guia_obligacion", "chk_componentes", "chk_items",
            // V6 y V11 · agenda y solicitudes
            "visitas", "visitas_componentes", "visitas_auditores", "visitas_reparto", "visitas_historial", "visitas_notificaciones",
            "visitas_solicitudes",
            // V9 · ejecución y acta
            "visitas_ejecucion", "visitas_personal", "visitas_respuestas", "archivos", "visitas_firmas", "visitas_actas", "visitas_resumen",
            // V12 y V13 · flujo del OPL y seguimiento
            "obligaciones_contrato", "visitas_obligaciones", "visitas_plan", "visitas_hallazgos", "opl_accesos", "opl_codigos",
            "opl_respuestas", "opl_soportes", "reportes_digsa", "opl_planes", "opl_avisos", "prorrogas",
            // V15 y V16 · propuesta de agenda y riesgo
            "propuestas_agenda", "propuestas_agenda_items", "propuestas_agenda_items_otros", "riesgo_incidencias",
            // V18 · las solicitudes de exportación (quién exportó y cuándo)
            "exportaciones",
            // V17 · alertas de la fase 10: bandeja de correo, SMS y alertas críticas
            "correos_salida", "sms_salida", "alertas_criticas", "alertas_eventos",
            // V50 · sesión de reportes: registro de los reportes generados (código de verificación y huellas, Anexo 5 2.10)
            "reportes_generados",
            // --- Presupuesto (rama presupuesto): V40 ---
            "presupuesto_vigencias", "presupuesto_modalidades", "presupuesto_lineas", "presupuesto_saldos", "presupuesto_soportes",
            "presupuesto_lotes", "presupuesto_movimientos", "presupuesto_movimientos_lineas", "presupuesto_ejecucion", "presupuesto_proyeccion",
            "presupuesto_consecutivos", "presupuesto_notificaciones", "presupuesto_umbrales", "presupuesto_desviaciones");
            // --- fin presupuesto ---

    /** Técnicas: no son datos de la aplicación (sesiones y control de migraciones). */
    public static final Set<String> TECNICAS = Set.of("flyway_schema_history", "spring_session", "spring_session_attributes");

    /**
     * Columnas que no salen (secretos y binarios), por tabla. Las firmas (bytea) salen como PNG en la carpeta de la visita; en el CSV no
     * sirven. Las demás columnas de todas las tablas salen completas.
     */
    public static final Map<String, Set<String>> EXCLUIDAS = excluidas();

    private static Map<String, Set<String>> excluidas() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        // Contraseña (BCrypt), enlace de un solo uso y su vencimiento, y huella de la sesión vigente
        m.put("usuarios", Set.of("clave_hash", "token_hash", "token_vence", "sesion_huella"));
        m.put("mfa_codigos", Set.of("codigo_hash"));
        m.put("opl_accesos", Set.of("token_hash"));
        m.put("opl_codigos", Set.of("codigo_hash"));
        // Imágenes de las firmas: van como PNG en soportes/<visita>/firmas/
        m.put("visitas_firmas", Set.of("imagen"));
        m.put("opl_planes", Set.of("firma"));
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Mensaje de la falla: sin detalle interno (el detalle queda en el registro de errores del servidor). */
    public static final String MENSAJE_FALLA = "No se pudo generar la exportación. Intente de nuevo o comuníquese con TI.";

    /** Columnas del índice de soportes (soportes/indice.csv). */
    public static final List<String> COLUMNAS_INDICE = List.of("visita", "punto", "carpeta", "tabla", "id", "clave", "nombre_original",
            "ruta_zip", "tipo", "tamano", "sha256_guardado", "sha256_calculado", "verificacion", "subido_por", "subido", "eliminado");

    /** Resultado de comparar la huella guardada con la calculada al copiar. */
    public enum Verificacion {
        COINCIDE("coincide"), DISTINTA("huella distinta"), NO_ENCONTRADO("no encontrado"), SIN_HUELLA("sin huella guardada");

        private final String texto;

        Verificacion(String texto) {
            this.texto = texto;
        }

        public String texto() {
            return texto;
        }
    }

    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    private static final String FIN = "\r\n";

    private Exportacion() {
    }

    /** Columnas exportadas de una tabla: las suyas, en su orden, menos las excluidas. */
    public static List<String> columnas(String tabla, List<String> columnasDeLaTabla) {
        Set<String> fuera = EXCLUIDAS.getOrDefault(tabla, Set.of());
        return columnasDeLaTabla.stream().filter(c -> !fuera.contains(c)).toList();
    }

    /**
     * Texto de una celda del CSV. NULL vacío; números con coma decimal, sin separador de miles y sin redondear (coordenadas de 6
     * decimales); fechas en hora de Colombia de 24 h; booleanos true/false; arreglos como JSON; el texto sin fórmulas y entre comillas
     * si hace falta.
     */
    public static String celda(Object v) {
        return switch (v) {
            case null -> "";
            case Boolean b -> b.toString();
            case BigDecimal b -> b.toPlainString().replace('.', ',');
            case Double d -> d.isNaN() || d.isInfinite() ? d.toString() : BigDecimal.valueOf(d).toPlainString().replace('.', ',');
            case Float f -> f.isNaN() || f.isInfinite() ? f.toString() : new BigDecimal(f.toString()).toPlainString().replace('.', ',');
            case Number n -> n.toString();
            case OffsetDateTime o -> o.atZoneSameInstant(Fechas.BOGOTA).format(FECHA_HORA);
            case ZonedDateTime z -> z.withZoneSameInstant(Fechas.BOGOTA).format(FECHA_HORA);
            case Instant i -> i.atZone(Fechas.BOGOTA).format(FECHA_HORA);
            case LocalDateTime l -> l.format(FECHA_HORA);
            case LocalDate d -> d.toString();
            case LocalTime t -> t.format(HORA);
            case Collection<?> c -> campo(json(c));
            case Object[] a -> campo(json(java.util.Arrays.asList(a)));
            default -> campo(v.toString());
        };
    }

    /** Fila del CSV con su fin de línea. */
    public static String fila(List<?> valores) {
        return valores.stream().map(Exportacion::celda).collect(Collectors.joining(";")) + FIN;
    }

    /** Fila de texto (cabeceras): sin fórmulas y entre comillas si hace falta. */
    public static String filaTexto(List<String> valores) {
        return valores.stream().map(Exportacion::campo).collect(Collectors.joining(";")) + FIN;
    }

    /**
     * Campo de texto del CSV: apóstrofo delante de lo que empieza por = + - @ tabulador o retorno (inyección de fórmulas); entre
     * comillas dobles si tiene «;», comillas o saltos de línea, con las comillas internas duplicadas.
     */
    public static String campo(String s) {
        String t = s == null ? "" : s;
        if (!t.isEmpty() && "=+-@\t\r".indexOf(t.charAt(0)) >= 0) {
            t = "'" + t;
        }
        if (t.indexOf(';') >= 0 || t.indexOf('"') >= 0 || t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0) {
            t = "\"" + t.replace("\"", "\"\"") + "\"";
        }
        return t;
    }

    /** Arreglo de PostgreSQL (text[], etc.) como JSON: ["a","b"]. */
    static String json(Collection<?> valores) {
        List<String> partes = new ArrayList<>();
        for (Object o : valores) {
            if (o == null) {
                partes.add("null");
            } else if (o instanceof Number || o instanceof Boolean) {
                partes.add(o.toString());
            } else {
                StringBuilder sb = new StringBuilder("\"");
                for (char c : o.toString().toCharArray()) {
                    switch (c) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\n' -> sb.append("\\n");
                        case '\r' -> sb.append("\\r");
                        case '\t' -> sb.append("\\t");
                        default -> {
                            if (c < 0x20) {
                                sb.append(String.format("\\u%04x", (int) c));
                            } else {
                                sb.append(c);
                            }
                        }
                    }
                }
                partes.add(sb.append('"').toString());
            }
        }
        return "[" + String.join(",", partes) + "]";
    }

    /** Nombre de un archivo dentro del ZIP: {@code <id>_<nombre original saneado>}, sin rutas, solo [A-Za-z0-9._-]. */
    public static String nombreSoporte(long id, String original) {
        return id + "_" + seguro(original, "archivo");
    }

    /** Carpeta de la visita: su número (VS-...) o, si aún no lo tiene, visita_<id>. */
    public static String carpetaVisita(String numero, long visitaId) {
        return numero == null || numero.isBlank() ? "visita_" + visitaId : seguro(numero, "visita_" + visitaId);
    }

    /** Nombre seguro para el ZIP: sin rutas ni tildes, solo [A-Za-z0-9._-], sin «..» y de máximo 120 caracteres. */
    public static String seguro(String original, String porDefecto) {
        String n = original == null ? "" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("\\.{2,}", ".").replaceAll("^[._]+", "");
        if (n.length() > 120) {
            n = n.substring(n.length() - 120);
        }
        return n.isEmpty() ? porDefecto : n;
    }

    /** Verificación de un soporte: huella guardada frente a la calculada al copiarlo (null = no se encontró en el almacén). */
    public static Verificacion verificar(String guardada, String calculada) {
        if (calculada == null) {
            return Verificacion.NO_ENCONTRADO;
        }
        if (guardada == null || guardada.isBlank()) {
            return Verificacion.SIN_HUELLA;
        }
        return Huellas.iguales(guardada.strip().toLowerCase(java.util.Locale.ROOT), calculada) ? Verificacion.COINCIDE : Verificacion.DISTINTA;
    }

    /** Nombre del ZIP que se descarga: digsa_exportacion_aaaammdd_hhmm.zip, con la hora de Colombia en que se generó. */
    public static String nombreZip(OffsetDateTime generado) {
        return "digsa_exportacion_" + generado.atZoneSameInstant(Fechas.BOGOTA).format(SELLO) + ".zip";
    }

    /** Detalle de la bitácora (sin datos personales). */
    public static String resumen(int tablas, long filas, int soportes) {
        return tablas + " tablas · " + filas + " filas · " + soportes + " soportes";
    }

    /** Una tabla exportada: filas y SHA-256 de su CSV (AGS-36). */
    public record TablaExportada(String tabla, long filas, String sha256) {
    }

    /**
     * Datos del LEEME.
     *
     * @param generadoPor nombre y usuario de quien la pidió, p. ej. «Admin (admin)»
     */
    public record Leeme(String generadoPor, OffsetDateTime generado, List<TablaExportada> tablas, long soportes, long huellasDistintas,
                        long noEncontrados) {
    }

    /** LEEME.txt del ZIP (UTF-8, \r\n). */
    public static String leeme(Leeme l) {
        StringBuilder sb = new StringBuilder();
        linea(sb, "Suite DIGSA · Exportación completa de los datos");
        linea(sb, "");
        linea(sb, "Generada por: " + l.generadoPor());
        linea(sb, "Fecha: " + celda(l.generado()) + " (hora de Colombia, 24 h)");
        linea(sb, "");
        linea(sb, "FORMATO");
        linea(sb, "Un archivo CSV por tabla (UTF-8 con BOM, separado por punto y coma, coma decimal).");
        linea(sb, "La primera fila de cada CSV trae los nombres de las columnas, en el orden de la tabla. Fechas y horas en hora de Colombia");
        linea(sb, "(yyyy-MM-dd HH:mm:ss), booleanos true/false, NULL como campo vacío, arreglos y JSON tal cual. El texto que empieza por");
        linea(sb, "= + - @ lleva un apóstrofo delante para que la hoja de cálculo no lo tome como fórmula.");
        linea(sb, "log_transacciones.csv trae la bitácora completa (todas sus particiones mensuales).");
        linea(sb, "Todas las tablas se leyeron en una misma transacción de solo lectura (una sola foto de la base).");
        linea(sb, "");
        linea(sb, "COLUMNAS EXCLUIDAS (secretos y binarios)");
        EXCLUIDAS.forEach((t, c) -> linea(sb, "- " + t + ": " + c.stream().sorted().collect(Collectors.joining(", "))));
        linea(sb, "No se exportan las tablas técnicas: " + TECNICAS.stream().sorted().collect(Collectors.joining(", ")) + ".");
        linea(sb, "Las imágenes de las firmas salen como PNG en la carpeta firmas/ de cada visita.");
        linea(sb, "");
        linea(sb, "SOPORTES");
        linea(sb, "soportes/<número de visita>/ trae los archivos de cada visita:");
        linea(sb, "- evidencias/: fotos, soportes de ítems y obligaciones y minuta (tabla archivos);");
        linea(sb, "- actas/: el PDF de cada ronda del acta, como acta_ronda_<n>.pdf;");
        linea(sb, "- opl/: los soportes de la respuesta del OPL (tabla opl_soportes);");
        linea(sb, "- firmas/: la imagen PNG de cada firma, como <rol>_ronda_<n>.png, y la del plan del OPL;");
        linea(sb, "- solicitud/: el escrito escaneado de la solicitud de visita a necesidad.");
        linea(sb, "Cada archivo se llama <id>_<nombre original saneado>. soportes/solicitudes/ trae los escritos de solicitudes sin visita.");
        linea(sb, "soportes/indice.csv (mismo formato) tiene una fila por archivo: " + String.join(", ", COLUMNAS_INDICE) + ".");
        linea(sb, "verificacion compara el SHA-256 guardado con el calculado al leer el archivo: coincide, huella distinta, no encontrado o");
        linea(sb, "sin huella guardada (firmas). Los archivos eliminados se exportan, marcados en la columna eliminado.");
        linea(sb, "");
        linea(sb, "DATOS PERSONALES");
        linea(sb, "Contiene datos personales (Ley 1581 de 2012): guárdela en un lugar seguro y no la envíe por correo.");
        linea(sb, "");
        linea(sb, "Tablas y filas (tabla, filas · SHA-256 del CSV)");
        long total = 0;
        for (TablaExportada t : l.tablas()) {
            linea(sb, t.tabla() + " " + t.filas() + " · " + t.sha256());
            total += t.filas();
        }
        linea(sb, "Total: " + l.tablas().size() + " tablas, " + total + " filas");
        linea(sb, "");
        linea(sb, "Soportes: " + l.soportes() + " (huella distinta: " + l.huellasDistintas() + "; no encontrados: " + l.noEncontrados() + ")");
        return sb.toString();
    }

    private static void linea(StringBuilder sb, String texto) {
        sb.append(texto).append(FIN);
    }
}
