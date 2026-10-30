package co.com.agsamericas.digsa.reglas;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Exportación de los hechos de dispensación por periodo (duda AN.14, fase A7.2; docs/especificaciones/analitica-ingesta «Exportación de
 * los hechos por periodo»). Los hechos (unos 2 M de filas por mes) no caben en la exportación completa (una transacción con tope de 10
 * minutos, AGS-75): se entregan aparte, un archivo por mes, cada uno leído en su propia transacción de solo lectura y escrito en flujo.
 * Mismo formato que la exportación completa ({@link Exportacion#celda}, GMI P77 f): CSV UTF-8 con BOM, «;», coma decimal, fechas ISO,
 * sin fórmulas; comprimido en gzip (.csv.gz) con el SHA-256 del archivo comprimido (lo que se descarga y se verifica).
 *
 * <p>El paciente sale SOLO como seudónimo HMAC (paciente_seudo); la tabla nunca guarda el documento en claro. No sale la llave natural
 * (técnica: huella de la fila o del id de dispensación, se reconstruye). Cada fila trae su cargue de origen, su línea y el SHA-256 del
 * archivo del cargue (trazabilidad 14.9).
 */
public final class AnaliticaExportacionHechos {

    /** Columnas del CSV, en orden: las de ana_dispensaciones (sin la llave) más el SHA-256 del cargue de origen. */
    public static final List<String> COLUMNAS = List.of("id", "cargue_id", "cargue_sha256", "linea_origen", "paciente_seudo", "anio_nacimiento",
            "grupo_edad", "fuerza", "disan", "regional_id", "punto_id", "esm_codigo", "modalidad", "fecha", "cum", "cum_original",
            "medicamento_original", "principio_activo_original", "atc_original", "cie10", "cantidad", "valor_total", "valor_unitario", "codigo_ssfm",
            "profesional_seudo", "especialidad", "tipo_formula", "numero_formula", "homil", "id_dispensacion", "item", "medicamento_id", "hom_version_id");

    /** Columnas de ana_dispensaciones que NO salen (técnicas). */
    public static final Set<String> FUERA = Set.of("llave");

    /** Meses por solicitud si no se configura otro tope (cinco años: todo el contrato y la entrega final). */
    public static final int MESES_MAX = 60;
    /** El mes más antiguo que se acepta (la ingesta rechaza fechas anteriores, ValidadorStaging). */
    public static final YearMonth MES_MINIMO = YearMonth.of(1990, 1);

    /** Mensaje de una falla, sin detalle interno (el detalle queda en el registro de errores del servidor). */
    public static final String MENSAJE_FALLA = "No se pudo generar el archivo de este mes. Pida una nueva exportación o comuníquese con TI.";

    private AnaliticaExportacionHechos() {
    }

    /** Un problema de la solicitud, con el campo que lo causa (la pantalla lo marca). */
    public record Problema(String campo, String mensaje) {
    }

    /** Mes en formato aaaa-mm, o vacío si no lo es. */
    public static Optional<YearMonth> mes(String texto) {
        if (texto == null || !texto.strip().matches("\\d{4}-\\d{2}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(texto.strip()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Valida el periodo pedido.
     *
     * @param mesActual mes actual en hora de Colombia (el último que se puede pedir; si está en curso, sale lo cargado hasta el corte)
     * @param mesesMax  tope de meses por solicitud
     */
    public static Optional<Problema> validar(String desde, String hasta, YearMonth mesActual, int mesesMax) {
        Optional<YearMonth> d = mes(desde);
        if (d.isEmpty()) {
            return Optional.of(new Problema("desde", "Indique el mes inicial (aaaa-mm)."));
        }
        Optional<YearMonth> h = hasta == null || hasta.isBlank() ? d : mes(hasta);
        if (h.isEmpty()) {
            return Optional.of(new Problema("hasta", "Indique el mes final (aaaa-mm)."));
        }
        if (d.get().isBefore(MES_MINIMO)) {
            return Optional.of(new Problema("desde", "El mes inicial no puede ser anterior a " + MES_MINIMO + "."));
        }
        if (d.get().isAfter(mesActual)) {
            return Optional.of(new Problema("desde", "El mes inicial no puede ser posterior al mes actual."));
        }
        if (h.get().isAfter(mesActual)) {
            return Optional.of(new Problema("hasta", "El mes final no puede ser posterior al mes actual."));
        }
        if (h.get().isBefore(d.get())) {
            return Optional.of(new Problema("hasta", "El mes final no puede ser anterior al inicial."));
        }
        if (meses(d.get(), h.get()).size() > mesesMax) {
            return Optional.of(new Problema("hasta", "Pida como máximo " + mesesMax + " meses por exportación."));
        }
        return Optional.empty();
    }

    /** Meses del periodo, en orden (ambos extremos incluidos). */
    public static List<YearMonth> meses(YearMonth desde, YearMonth hasta) {
        List<YearMonth> l = new ArrayList<>();
        for (YearMonth m = desde; !m.isAfter(hasta); m = m.plusMonths(1)) {
            l.add(m);
        }
        return l;
    }

    /** Nombre del archivo de un mes: digsa_hechos_dispensacion_<id>_aaaa-mm.csv.gz. */
    public static String nombreArchivo(long exportacionId, YearMonth mes) {
        return "digsa_hechos_dispensacion_" + exportacionId + "_" + mes + ".csv.gz";
    }

    /** Nombre del LEEME de una exportación. */
    public static String nombreLeeme(long exportacionId) {
        return "LEEME_hechos_dispensacion_" + exportacionId + ".txt";
    }

    /** Detalle de la bitácora (sin datos personales). */
    public static String resumen(long exportacionId, YearMonth desde, YearMonth hasta) {
        return "Exportación de hechos " + exportacionId + " · " + desde + (desde.equals(hasta) ? "" : " a " + hasta);
    }

    /** Un mes exportado: filas, suma de valor_total (cuadre), tamaño y SHA-256 del .csv.gz; estado lista o fallida. */
    public record MesExportado(YearMonth mes, String estado, long filas, BigDecimal valorTotal, long tamano, String sha256) {
    }

    /**
     * Datos del LEEME.
     *
     * @param generadoPor   «Nombre (usuario)» de quien la pidió
     * @param corte         instante de corte: solo salen hechos de cargues aplicados hasta él y no reemplazados antes de él
     * @param corteCargueId último cargue visible al corte (nulo si no había ninguno)
     * @param cargues       cargues visibles y confirmados al pedir la exportación: los únicos cuyos hechos salen, en todos los meses
     * @param versionEsquema versión de la última migración aplicada (flyway)
     */
    public record Leeme(long id, String generadoPor, OffsetDateTime solicitada, OffsetDateTime terminada, OffsetDateTime corte, Long corteCargueId,
                        List<Long> cargues, YearMonth desde, YearMonth hasta, String versionEsquema, List<MesExportado> meses) {
    }

    /** LEEME.txt (UTF-8, \r\n), coherente con el de la exportación completa. */
    public static String leeme(Leeme l) {
        StringBuilder sb = new StringBuilder();
        linea(sb, "Suite DIGSA · Exportación de los hechos de dispensación por periodo");
        linea(sb, "");
        linea(sb, "Exportación: " + l.id());
        linea(sb, "Generada por: " + l.generadoPor());
        linea(sb, "Solicitada: " + Exportacion.celda(l.solicitada()) + " · terminada: " + Exportacion.celda(l.terminada())
                + " (hora de Colombia, 24 h)");
        linea(sb, "Periodo: " + l.desde() + " a " + l.hasta() + " (" + l.meses().size() + " meses)");
        linea(sb, "Corte: " + Exportacion.celda(l.corte()) + " · último cargue visible: " + (l.corteCargueId() == null ? "ninguno" : l.corteCargueId()));
        linea(sb, "Cargues incluidos (" + l.cargues().size() + "): " + (l.cargues().isEmpty() ? "ninguno"
                : l.cargues().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "))));
        linea(sb, "Versión del esquema de la base: " + l.versionEsquema());
        linea(sb, "");
        linea(sb, "CONTENIDO");
        linea(sb, "Un archivo por mes, digsa_hechos_dispensacion_<exportación>_<aaaa-mm>.csv.gz, con los hechos de dispensación (tabla");
        linea(sb, "ana_dispensaciones) cuya fecha cae en ese mes y cuyo cargue está en la lista de cargues incluidos: los aplicados,");
        linea(sb, "confirmados y no reemplazados en el momento de la solicitud (los mismos hechos que ven los tableros con ese corte). La");
        linea(sb, "lista se fijó al pedir la exportación y es la misma para todos los meses: un cargue que se aplicó mientras la exportación");
        linea(sb, "corría no entra en ningún mes. Un mes sin datos trae solo la fila de encabezados.");
        linea(sb, "Cada mes se leyó en su propia transacción de solo lectura (una foto de la base por mes, con la misma lista de cargues).");
        linea(sb, "");
        linea(sb, "FORMATO");
        linea(sb, "CSV en UTF-8 con BOM, separado por punto y coma, coma decimal, comprimido en gzip. La primera fila trae los nombres de las");
        linea(sb, "columnas. Fechas en formato aaaa-mm-dd, booleanos true/false, NULL como campo vacío. El texto que empieza por = + - @ lleva");
        linea(sb, "un apóstrofo delante para que la hoja de cálculo no lo tome como fórmula. Mismo formato que la exportación completa.");
        linea(sb, "Columnas: " + String.join(", ", COLUMNAS) + ".");
        linea(sb, "cargue_id, cargue_sha256 y linea_origen llevan cada fila a su cargue (bitácora de cargues), al SHA-256 del archivo o envío");
        linea(sb, "y a su línea de origen. medicamento_id y hom_version_id llevan al dato original homologado (hom_medicamento_origen) y a");
        linea(sb, "la versión de la tabla maestra (hom_tabla_maestra), que van en la exportación completa.");
        linea(sb, "No sale la llave natural (" + String.join(", ", FUERA) + "): es técnica y se reconstruye.");
        linea(sb, "");
        linea(sb, "VERIFICACIÓN");
        linea(sb, "El SHA-256 de abajo es el del archivo .csv.gz tal como se descarga (sha256sum o Get-FileHash en Windows). valor_total es la");
        linea(sb, "suma de la columna valor_total del mes, para cuadrar con la base o con los tableros del mismo corte.");
        linea(sb, "");
        linea(sb, "DATOS PERSONALES");
        linea(sb, "El paciente y el profesional salen solo como seudónimo (HMAC-SHA256); nunca el documento en claro. Aun así son datos de");
        linea(sb, "salud seudonimizados (Ley 1581 de 2012): guárdelos en un lugar seguro y no los envíe por correo.");
        linea(sb, "");
        linea(sb, "Meses (mes · estado · filas · valor_total · bytes · SHA-256 del .csv.gz)");
        long filas = 0;
        BigDecimal valor = BigDecimal.ZERO;
        int fallidos = 0;
        for (MesExportado m : l.meses()) {
            if ("lista".equals(m.estado())) {
                linea(sb, m.mes() + " · lista · " + m.filas() + " · " + Exportacion.celda(m.valorTotal()) + " · " + m.tamano() + " · " + m.sha256());
                filas += m.filas();
                valor = valor.add(m.valorTotal());
            } else {
                linea(sb, m.mes() + " · " + m.estado() + " · sin archivo");
                fallidos++;
            }
        }
        linea(sb, "Total: " + (l.meses().size() - fallidos) + " meses, " + filas + " filas, valor_total " + Exportacion.celda(valor)
                + (fallidos > 0 ? " · " + fallidos + " meses sin archivo (falla): pida una nueva exportación de esos meses" : ""));
        return sb.toString();
    }

    private static void linea(StringBuilder sb, String texto) {
        sb.append(texto).append("\r\n");
    }
}
