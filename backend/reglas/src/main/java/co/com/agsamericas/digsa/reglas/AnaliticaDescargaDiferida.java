package co.com.agsamericas.digsa.reglas;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Descarga diferida de analítica (fase A7.3, duda AN.16; docs/especificaciones/analitica-tableros «Descarga diferida cuando no cabe en la
 * petición»). Solo el CSV de registros de origen de la trazabilidad se difiere: es lo único cuya salida crece con los registros. Las
 * descargas de los tableros son agregadas (Top 20, series y tablas por dimensión; mapa de calor ≤ 60 columnas), miden pocos KB y siguen
 * en la petición siempre (revisión A7.3). Por encima del umbral de registros de salida, la API responde 202 con el id; un ejecutor en
 * segundo plano genera el CSV con el MISMO corte (congelado al pedirlo), filtros, alcance y perfil, lo guarda en el almacén con su SHA-256
 * y queda en «Mis descargas del día» del usuario hasta que vence (24 h). Reglas puras: cuándo se difiere, el alcance que se congela, qué
 * parámetros se guardan, nombres y mensajes.
 */
public final class AnaliticaDescargaDiferida {

    /** Registros de salida desde los que la descarga va a diferido si no se configura otro umbral (A5 2.7). */
    public static final int UMBRAL_FILAS = 100_000;
    /** Tope de registros del CSV de registros de origen generado en diferido (más: 422, nunca un archivo truncado). */
    public static final int MAX_REGISTROS_TRAZA = 2_000_000;
    /** Intentos de generación (una caída a mitad la devuelve a la cola); al agotarlos queda «fallida». */
    public static final int MAX_INTENTOS = 3;

    public static final List<String> ESTADOS = List.of("pendiente", "generando", "lista", "fallida", "vencida");
    /** Código de Fuerza aceptado en el alcance congelado (el mismo patrón del CHECK de V67). */
    public static final Pattern FUERZA = Pattern.compile("[A-Z0-9_]{1,20}");
    /** Parámetros generales de la consulta que se guardan (el corte va aparte, congelado; formato y paginación no son de la consulta). */
    public static final Set<String> GENERALES = Set.of("desde", "hasta", "granularidad", "comparacion", "nivel", "nivelAtc", "orden");

    public static final String EN_CURSO = "Ya tiene una descarga diferida en curso. Espere a que termine (la ve en «Mis descargas del día») para pedir otra.";
    public static final String MENSAJE_FALLA = "No se pudo generar la descarga. Pídala de nuevo o acote los filtros; si se repite, comuníquese con TI.";
    public static final String MENSAJE_INTENTOS = "No se pudo generar la descarga después de varios intentos. Pídala de nuevo; si se repite, comuníquese con TI.";
    public static final String MENSAJE_DEMORA = "La descarga no se pudo generar a tiempo (la cola estaba detenida). Pídala de nuevo.";
    public static final String MENSAJE_SIN_ACCESO = "No se generó: el usuario ya no está activo o perdió el alcance o el permiso de trazabilidad con que la pidió.";
    public static final String VENCIDA = "La descarga venció y su archivo se borró. Pídala de nuevo.";
    public static final String ALCANCE_CAMBIO = "Su alcance de datos cambió desde que pidió esta descarga; pídala de nuevo.";
    public static final String SIN_ALCANCE = "Su perfil no tiene un alcance de datos válido para la analítica farmacéutica.";

    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private AnaliticaDescargaDiferida() {
    }

    /** ¿Va a diferido? Solo si los registros superan el umbral (el umbral mismo cabe en la petición). */
    public static boolean diferir(long registros, int umbral) {
        return registros > umbral;
    }

    /**
     * Registros que el CSV de registros de origen entrega EN LA PETICIÓN: el menor entre el umbral de la descarga diferida y el tope
     * histórico {@code max-registros-traza} de los tableros (por defecto los dos son 100 000).
     */
    public static int limiteTrazaSincrona(int umbral, int maxTrazaTableros) {
        return Math.min(umbral, maxTrazaTableros);
    }

    /** Mensaje del 422 si la cifra pasa del tope de la traza en diferido. */
    public static Optional<String> excedeTraza(long registros, int maximo) {
        return registros > maximo ? Optional.of("La cifra tiene " + registros + " registros de origen (máximo " + maximo
                + " por descarga, también en diferido); acote los filtros o el periodo.") : Optional.empty();
    }

    /** Mensaje del 202 para la pantalla. */
    public static String mensajeDiferida(long registros, int umbral) {
        return "La descarga tiene " + registros + " registros de origen (más de " + umbral + "): se está generando en segundo plano. Quedará en"
                + " «Mis descargas del día» con su enlace cuando esté lista.";
    }

    // ---------- Alcance congelado ----------

    /** Código estable del alcance que se congela al pedir (y se compara al descargar): «nacional», «fuerza:EJC», «esm:12»; vacío si no aplica. */
    public static Optional<String> codigo(Alcance a) {
        return switch (a) {
            case Alcance.Todo t -> Optional.of("nacional");
            case Alcance.Fuerza f -> f.codigo() == null || !FUERZA.matcher(f.codigo()).matches() ? Optional.empty() : Optional.of("fuerza:" + f.codigo());
            case Alcance.Esm e -> e.puntoId() > 0 ? Optional.of("esm:" + e.puntoId()) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** El alcance de un código congelado (lo inverso de {@link #codigo}); vacío si no es válido (la generación falla cerrado). */
    public static Optional<Alcance> alcance(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        if ("nacional".equals(codigo)) {
            return Optional.of(new Alcance.Todo());
        }
        if (codigo.startsWith("fuerza:") && FUERZA.matcher(codigo.substring(7)).matches()) {
            return Optional.of(new Alcance.Fuerza(codigo.substring(7)));
        }
        if (codigo.matches("esm:[1-9][0-9]{0,17}")) {
            return Optional.of(new Alcance.Esm(Long.parseLong(codigo.substring(4))));
        }
        return Optional.empty();
    }

    // ---------- Parámetros guardados ----------

    /**
     * Lista blanca de los parámetros de la URL que se guardan para regenerar la consulta: los generales (sin el corte, que va aparte y
     * congelado), las variables de filtro y la celda rastreada ({@code periodo}). Nada de texto libre (búsqueda de opciones, variable).
     */
    public static boolean parametroGuardado(String clave) {
        return clave != null && (GENERALES.contains(clave) || "periodo".equals(clave) || AnaliticaConsulta.Variable.deParametro(clave).isPresent());
    }

    /** ¿Caben los parámetros guardados? (tope del JSON en la base; la URL ya los limita). */
    public static boolean parametrosCaben(Map<String, List<String>> p, int maxCaracteres) {
        int n = 0;
        for (Map.Entry<String, List<String>> e : p.entrySet()) {
            n += e.getKey().length() + 6;
            for (String v : e.getValue()) {
                n += (v == null ? 4 : v.length()) + 3;
            }
        }
        return n <= maxCaracteres;
    }

    // ---------- Nombres y textos ----------

    /**
     * Nombre del archivo, con el formato de la descarga en la petición más el sello de la generación (hora de Bogotá):
     * «registros_origen_atc_20261030_1415.csv».
     */
    public static String nombreArchivo(String tablero, OffsetDateTime generado) {
        String t = tablero == null ? "consulta" : tablero.replaceAll("[^a-z0-9_-]", "");
        return "registros_origen_" + t + (generado == null ? "" : "_" + generado.atZoneSameInstant(Fechas.BOGOTA).format(SELLO)) + ".csv";
    }

    /** Ruta en el almacén (con una parte al azar: no se adivina). */
    public static String ruta(long id, String azar) {
        return "analitica/descargas/" + id + "_" + azar + ".csv";
    }

    /** Texto de la bitácora: id y tablero (sin filtros ni datos personales). */
    public static String resumen(long id, String tablero) {
        return "Descarga diferida " + id + " · registros de origen " + tablero + " · csv";
    }
}
