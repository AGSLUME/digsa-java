package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Columna;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.FiltroAplicado;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Fila;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Kpi;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Tabla;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Exportador;
import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tipo;
import co.com.agsamericas.digsa.reglas.Exportacion;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Huellas;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Descargas de los tableros en Excel, CSV y PDF con la unión de metadatos de 2.9, 4.7.2 y 14.8 (analitica-tableros, «Descargas …»):
 * título, n.º de página, fecha y hora de generación, usuario y perfil, periodo y granularidad, periodo de comparación, establecimiento,
 * filtros (incluido el alcance), fuente con la versión de la tabla maestra, fecha de corte, registros procesados, gráficos (su tabla en
 * Excel y CSV; dibujados en PDF), observaciones y la huella SHA-256 del contenido.
 *
 * <p><b>Capa delgada</b> sobre {@link Exportador} (dueño: la sesión de reportes): cada tabla sale de sus rutinas públicas
 * ({@code excel}, {@code csv}, {@code pdf}: coma decimal, BOM, «;», sin fórmulas, Excel en streaming) y aquí solo se agregan los
 * metadatos, el armado en hojas o secciones, los gráficos del PDF y el «Página n de m», que el Exportador aún no ofrece (su
 * encabezado es fijo y su PDF numera «Página n» sin total). No se modifica el Exportador. La restricción de edición del PDF (RESP L555)
 * queda para el R2 de reportes: el cifrado de OpenPDF exige BouncyCastle, que no está en el proyecto, y no se agrega una dependencia.
 */
// SUSTITUIR al integrar reportes R2: el encabezado 2.9 con «Página N de M», la escritura en flujo y el PDF protegido pasan a
// plataforma/Exportador (contrato-proveedor.md de la sesión de reportes); entonces esta clase solo aporta los metadatos de 14.8 y los
// gráficos, y desaparece el recubrimiento del pie de página de aquí.
@Component
public class ExportadorAnalitica {

    public static final String TIPO_XLSX = Exportador.TIPO_XLSX;
    public static final String TIPO_CSV = Exportador.TIPO_CSV;
    public static final String TIPO_PDF = Exportador.TIPO_PDF;

    /** Archivo generado. */
    public record Descarga(byte[] datos, String tipo, String nombre, String sha256) {
    }

    private final Exportador exportador;

    ExportadorAnalitica(Exportador exportador) {
        this.exportador = exportador;
    }

    /** Exporta exactamente lo visualizado en el formato pedido (xlsx, csv o pdf). */
    public Descarga exportar(ResultadoTablero r, String formato, String usuario, String perfil, OffsetDateTime generado) {
        if (r.metadatos() == null) {
            throw ErrorNegocio.validacion(ServicioConsultaAnalitica.SIN_CARGUES + ": no hay nada que descargar.");
        }
        if (!r.disponible()) {
            throw ErrorNegocio.validacion("«" + r.titulo() + "»: " + (r.mensaje() == null ? "sin datos" : r.mensaje()) + "; no hay nada que descargar.");
        }
        String base = "analitica_" + r.tablero() + "_" + generado.atZoneSameInstant(Fechas.BOGOTA).format(java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd_HHmm"));
        byte[] datos;
        String tipo;
        switch (formato) {
            case "xlsx" -> {
                datos = excel(r, usuario, perfil, generado);
                tipo = TIPO_XLSX;
            }
            case "csv" -> {
                datos = csv(r, usuario, perfil, generado);
                tipo = TIPO_CSV;
            }
            case "pdf" -> {
                datos = pdf(r, usuario, perfil, generado);
                tipo = TIPO_PDF;
            }
            default -> throw ErrorNegocio.validacion("Formato no válido: use xlsx, csv o pdf.");
        }
        return new Descarga(datos, tipo, base + "." + formato, Huellas.sha256(datos));
    }

    // ---------- Metadatos ----------

    /** Las secciones exportables: indicadores y cada tabla (la primera es «Datos»). */
    static List<Tabla> secciones(ResultadoTablero r) {
        List<Tabla> s = new ArrayList<>();
        List<Columna> ck = List.of(new Columna("indicador", "Indicador", Tipo.TEXTO), new Columna("valor", "Valor", Tipo.TEXTO),
                new Columna("comparado", "Periodo de comparación", Tipo.TEXTO), new Columna("variacion", "Variación", Tipo.TEXTO),
                new Columna("nota", "Nota", Tipo.TEXTO));
        List<Fila> fk = new ArrayList<>();
        for (Kpi k : r.kpis()) {
            fk.add(new Fila(k.clave(), null, java.util.Arrays.asList(k.etiqueta(), k.texto(), k.textoComparado(), k.textoVariacion(), k.nota()),
                    List.of(), "dato"));
        }
        s.add(new Tabla("indicadores", "Indicadores", "ninguno", null, null, ck, fk, null));
        s.addAll(r.tablas());
        return s;
    }

    /** Líneas de metadatos (clave, valor) de la unión 2.9 + 4.7.2 + 14.8, en orden fijo. */
    static List<String[]> metadatos(ResultadoTablero r, String usuario, String perfil, OffsetDateTime generado, String pagina) {
        ResultadoTablero.Metadatos m = r.metadatos();
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"Título", r.titulo()});
        l.add(new String[] {"N.º de página", pagina});
        l.add(new String[] {"Fecha y hora de generación", AnaliticaConsulta.fechaHora(generado) + " (hora de Colombia, 24 h)"});
        l.add(new String[] {"Usuario", usuario});
        l.add(new String[] {"Perfil", perfil});
        l.add(new String[] {"Periodo", m.periodoTexto()});
        l.add(new String[] {"Granularidad", m.granularidad()});
        l.add(new String[] {"Periodo de comparación", m.periodoComparacion() == null ? m.comparacion() : m.comparacion() + ": " + m.periodoComparacion()});
        l.add(new String[] {"Establecimiento", m.establecimiento()});
        l.add(new String[] {"Filtros", "Alcance: " + m.alcance()});
        l.add(new String[] {"", "Vista: " + r.nivel()});
        for (FiltroAplicado f : m.filtros()) {
            l.add(new String[] {"", f.etiqueta() + ": " + String.join(", ", f.textos())});
        }
        if (m.filtros().isEmpty()) {
            l.add(new String[] {"", "Sin otros filtros (todas las opciones del alcance)"});
        }
        l.add(new String[] {"Fuente", m.fuente()});
        l.add(new String[] {"Fecha de corte", m.corteTexto()});
        l.add(new String[] {"N.º de registros procesados", Long.toString(m.registros())});
        int filas = r.tablas().stream().mapToInt(t -> t.filas().size()).sum();
        l.add(new String[] {"N.º de filas", Integer.toString(filas)});
        l.add(new String[] {"Supresión de celdas pequeñas", m.supresion()});
        int n = 1;
        boolean primero = true;
        for (Tabla t : r.tablas()) {
            if (!"ninguno".equals(t.grafico())) {
                l.add(new String[] {primero ? "Gráficos" : "", "Gráfico " + n++ + ": " + t.titulo() + " (" + t.grafico() + "; su tabla va en la descarga)"});
                primero = false;
            }
        }
        if (primero) {
            l.add(new String[] {"Gráficos", "Sin gráficos"});
        }
        List<String> obs = new ArrayList<>();
        obs.addAll(r.advertencias());
        obs.addAll(r.observaciones());
        obs.addAll(r.noDisponible());
        if (obs.isEmpty()) {
            obs.add("Sin observaciones");
        }
        for (int i = 0; i < obs.size(); i++) {
            l.add(new String[] {i == 0 ? "Observaciones y alertas" : "", obs.get(i)});
        }
        l.add(new String[] {"Huella SHA-256 del contenido", r.huella()});
        return l;
    }

    /** Reporte del Exportador para una tabla: los valores en su tipo (BigDecimal con 2 decimales, conteos Long) y el filtro del alcance. */
    private static Reporte reporte(ResultadoTablero r, Tabla t, String usuario, OffsetDateTime generado, boolean pdf) {
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("Alcance", r.metadatos().alcance());
        for (FiltroAplicado f : r.metadatos().filtros()) {
            filtros.put(f.etiqueta(), String.join(", ", f.textos()));
        }
        List<List<Object>> filas = new ArrayList<>();
        for (Fila f : t.filas()) {
            List<Object> v = new ArrayList<>();
            for (int i = 0; i < t.columnas().size(); i++) {
                Object x = i < f.valores().size() ? f.valores().get(i) : null;
                Object e = AnaliticaConsulta.paraExportar(x, t.columnas().get(i).tipo());
                v.add(pdf && e instanceof String s ? paraPdf(s) : e);
            }
            filas.add(v);
        }
        return new Reporte((pdf ? paraPdf(r.titulo()) : r.titulo()) + " · " + (pdf ? paraPdf(t.titulo()) : t.titulo()),
                generado.atZoneSameInstant(Fechas.BOGOTA), usuario, filtros, t.columnas().stream().map(c -> pdf ? paraPdf(c.etiqueta()) : c.etiqueta())
                        .toList(), filas);
    }

    /** Helvetica (WinAnsi) no tiene «≥»: se escribe «>=» en el PDF. */
    static String paraPdf(String s) {
        return s == null ? null : s.replace("≥", ">=").replace("≤", "<=").replace("×", "x").replace("→", "->");
    }

    // ---------- Excel ----------

    private byte[] excel(ResultadoTablero r, String usuario, String perfil, OffsetDateTime generado) {
        try (XSSFWorkbook libro = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet meta = libro.createSheet("Metadatos");
            int f = 0;
            for (String[] l : metadatos(r, usuario, perfil, generado, "Página 1 de 1 (hoja de cálculo)")) {
                Row row = meta.createRow(f++);
                row.createCell(0).setCellValue(l[0]); // texto: Excel no lo evalúa como fórmula
                row.createCell(1).setCellValue(l[1]);
            }
            meta.setColumnWidth(0, 32 * 256);
            meta.setColumnWidth(1, 110 * 256);
            List<Tabla> secciones = secciones(r);
            // La primera tabla del tablero es «Datos»; los indicadores y las demás (una por gráfico) van en su hoja
            copiar(libro, "Datos", exportador.excel(reporte(r, secciones.get(1 < secciones.size() ? 1 : 0), usuario, generado, false)));
            copiar(libro, "Indicadores", exportador.excel(reporte(r, secciones.getFirst(), usuario, generado, false)));
            for (int i = 2; i < secciones.size(); i++) {
                Tabla t = secciones.get(i);
                String nombre = (i + "-" + t.titulo()).replaceAll("[\\\\/?*\\[\\]:]", " ");
                copiar(libro, nombre.length() > 31 ? nombre.substring(0, 31) : nombre, exportador.excel(reporte(r, t, usuario, generado, false)));
            }
            libro.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el Excel de analítica", e);
        }
    }

    /** Copia los valores de la hoja que generó el Exportador (texto o número, sin fórmulas) a una hoja nueva del libro. */
    private static void copiar(XSSFWorkbook destino, String nombre, byte[] origen) throws IOException {
        try (XSSFWorkbook o = new XSSFWorkbook(new ByteArrayInputStream(origen))) {
            Sheet s = o.getSheetAt(0);
            Sheet d = destino.createSheet(nombre);
            for (Row ro : s) {
                Row rd = d.createRow(ro.getRowNum());
                for (Cell co : ro) {
                    Cell cd = rd.createCell(co.getColumnIndex());
                    if (co.getCellType() == CellType.NUMERIC) {
                        cd.setCellValue(co.getNumericCellValue());
                    } else {
                        cd.setCellValue(co.getStringCellValue());
                    }
                }
            }
        }
    }

    // ---------- CSV ----------

    private byte[] csv(ResultadoTablero r, String usuario, String perfil, OffsetDateTime generado) {
        StringBuilder sb = new StringBuilder("﻿");
        for (String[] l : metadatos(r, usuario, perfil, generado, "Página 1 de 1 (texto continuo)")) {
            sb.append(Exportacion.campo(l[0])).append(';').append(Exportacion.campo(l[1])).append("\r\n");
        }
        for (Tabla t : secciones(r)) {
            sb.append("\r\n");
            String seccion = new String(exportador.csv(reporte(r, t, usuario, generado, false)), StandardCharsets.UTF_8);
            sb.append(seccion.startsWith("﻿") ? seccion.substring(1) : seccion);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---------- PDF ----------

    private static final com.lowagie.text.Font TITULO = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 13, com.lowagie.text.Font.BOLD,
            new Color(11, 61, 92));
    private static final com.lowagie.text.Font NORMAL = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 8);
    private static final com.lowagie.text.Font NEGRITA = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 8, com.lowagie.text.Font.BOLD);

    private byte[] pdf(ResultadoTablero r, String usuario, String perfil, OffsetDateTime generado) {
        List<byte[]> partes = new ArrayList<>();
        partes.add(portada(r, usuario, perfil, generado));
        for (Tabla t : secciones(r)) {
            partes.add(exportador.pdf(reporte(r, t, usuario, generado, true)));
        }
        return numerar(unir(partes));
    }

    /** Metadatos y gráficos dibujados (barras y series en vectores; el mapa de calor va como tabla). */
    private static byte[] portada(ResultadoTablero r, String usuario, String perfil, OffsetDateTime generado) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document doc = new com.lowagie.text.Document(com.lowagie.text.PageSize.LETTER.rotate(), 30, 30, 36, 40);
        try {
            com.lowagie.text.pdf.PdfWriter w = com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
            doc.addTitle(paraPdf(r.titulo()));
            doc.open();
            doc.add(new com.lowagie.text.Paragraph(paraPdf(r.titulo()), TITULO));
            com.lowagie.text.pdf.PdfPTable m = new com.lowagie.text.pdf.PdfPTable(new float[] {1.3f, 4f});
            m.setWidthPercentage(100);
            for (String[] l : metadatos(r, usuario, perfil, generado, "Página n de m (pie de cada página)")) {
                m.addCell(celda(paraPdf(l[0]), NEGRITA));
                m.addCell(celda(paraPdf(l[1]), NORMAL));
            }
            doc.add(m);
            int n = 1;
            for (Tabla t : r.tablas()) {
                if ("barras".equals(t.grafico()) || "serie".equals(t.grafico())) {
                    doc.newPage();
                    doc.add(new com.lowagie.text.Paragraph("Gráfico " + n++ + ": " + paraPdf(t.titulo()), NEGRITA));
                    com.lowagie.text.Image img = "barras".equals(t.grafico()) ? barras(w, t) : serie(w, t);
                    if (img != null) {
                        doc.add(img);
                    }
                    doc.add(new com.lowagie.text.Paragraph("La tabla del gráfico va en la sección «" + paraPdf(t.titulo()) + "».", NORMAL));
                }
            }
            doc.close();
        } catch (com.lowagie.text.DocumentException e) {
            throw new IllegalStateException("No se pudo generar el PDF de analítica", e);
        }
        return out.toByteArray();
    }

    private static com.lowagie.text.pdf.PdfPCell celda(String texto, com.lowagie.text.Font f) {
        com.lowagie.text.pdf.PdfPCell c = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(texto == null ? "" : texto, f));
        c.setBorderColor(new Color(220, 226, 221));
        return c;
    }

    private static int indice(Tabla t, String clave) {
        for (int i = 0; i < t.columnas().size(); i++) {
            if (t.columnas().get(i).clave().equals(clave)) {
                return i;
            }
        }
        return -1;
    }

    private static double numero(Object v) {
        return v instanceof BigDecimal b ? b.doubleValue() : v instanceof Number x ? x.doubleValue() : 0;
    }

    /** Barras horizontales (sin «Total»): etiqueta, barra proporcional y valor en texto. */
    private static com.lowagie.text.Image barras(com.lowagie.text.pdf.PdfWriter w, Tabla t) throws com.lowagie.text.DocumentException {
        int col = indice(t, t.valorGrafico());
        List<Fila> filas = t.filas().stream().filter(f -> !"total".equals(f.tipo())).limit(25).toList();
        if (col < 0 || filas.isEmpty()) {
            return null;
        }
        double max = filas.stream().mapToDouble(f -> numero(f.valores().get(col))).max().orElse(0);
        float alto = 16 * filas.size() + 10;
        com.lowagie.text.pdf.PdfTemplate p = w.getDirectContent().createTemplate(720, alto);
        com.lowagie.text.pdf.BaseFont bf = NORMAL.getCalculatedBaseFont(false);
        float y = alto - 14;
        for (Fila f : filas) {
            String etiqueta = paraPdf(String.valueOf(f.valores().getFirst()));
            if (etiqueta.length() > 60) {
                etiqueta = etiqueta.substring(0, 59) + "...";
            }
            double v = numero(f.valores().get(col));
            float ancho = max <= 0 ? 0 : (float) (360 * v / max);
            p.beginText();
            p.setFontAndSize(bf, 7);
            p.setTextMatrix(0, y + 2);
            p.showText(etiqueta);
            p.endText();
            p.setColorFill("resto".equals(f.tipo()) || "sin".equals(f.tipo()) ? new Color(154, 167, 159) : new Color(36, 86, 131));
            p.rectangle(250, y, Math.max(ancho, 0.5f), 10);
            p.fill();
            p.setColorFill(Color.BLACK);
            p.beginText();
            p.setFontAndSize(bf, 7);
            p.setTextMatrix(615, y + 2);
            p.showText(paraPdf(f.textos().get(col)));
            p.endText();
            y -= 16;
        }
        return com.lowagie.text.Image.getInstance(p);
    }

    /** Serie de tiempo: línea continua del periodo y discontinua del periodo de comparación, con ejes y etiquetas. */
    private static com.lowagie.text.Image serie(com.lowagie.text.pdf.PdfWriter w, Tabla t) throws com.lowagie.text.DocumentException {
        int col = indice(t, t.valorGrafico());
        int comp = t.comparado() == null ? -1 : indice(t, t.comparado());
        List<Fila> filas = t.filas().stream().filter(f -> "dato".equals(f.tipo())).toList();
        if (col < 0 || filas.isEmpty()) {
            return null;
        }
        double max = 0;
        for (Fila f : filas) {
            max = Math.max(max, numero(f.valores().get(col)));
            if (comp >= 0) {
                max = Math.max(max, numero(f.valores().get(comp)));
            }
        }
        float ancho = 720;
        float alto = 220;
        com.lowagie.text.pdf.PdfTemplate p = w.getDirectContent().createTemplate(ancho, alto);
        com.lowagie.text.pdf.BaseFont bf = NORMAL.getCalculatedBaseFont(false);
        float x0 = 70;
        float y0 = 30;
        float w0 = ancho - x0 - 10;
        float h0 = alto - y0 - 15;
        p.setColorStroke(new Color(220, 226, 221));
        for (int i = 0; i <= 4; i++) {
            float y = y0 + h0 * i / 4;
            p.moveTo(x0, y);
            p.lineTo(x0 + w0, y);
            p.stroke();
            p.beginText();
            p.setFontAndSize(bf, 6);
            p.setTextMatrix(2, y - 2);
            p.showText(AnaliticaConsulta.decimal(BigDecimal.valueOf(max * i / 4)));
            p.endText();
        }
        int n = filas.size();
        for (int s = 0; s < (comp >= 0 ? 2 : 1); s++) {
            int c = s == 0 ? col : comp;
            p.setColorStroke(s == 0 ? new Color(28, 70, 50) : new Color(36, 86, 131));
            p.setLineWidth(1.5f);
            if (s == 1) {
                p.setLineDash(5, 3, 0);
            }
            for (int i = 0; i < n; i++) {
                float x = x0 + (n == 1 ? w0 / 2 : w0 * i / (n - 1));
                float y = (float) (y0 + (max <= 0 ? 0 : h0 * numero(filas.get(i).valores().get(c)) / max));
                if (i == 0) {
                    p.moveTo(x, y);
                } else {
                    p.lineTo(x, y);
                }
            }
            p.stroke();
            p.setLineDash(0);
        }
        int paso = Math.max(1, n / 12);
        for (int i = 0; i < n; i += paso) {
            float x = x0 + (n == 1 ? w0 / 2 : w0 * i / (n - 1));
            p.beginText();
            p.setFontAndSize(bf, 6);
            p.setTextMatrix(x - 12, y0 - 12);
            p.showText(String.valueOf(filas.get(i).valores().getFirst()));
            p.endText();
        }
        p.beginText();
        p.setFontAndSize(bf, 7);
        p.setTextMatrix(x0, 2);
        p.showText(comp >= 0 ? "Línea continua: periodo consultado · Discontinua: periodo de comparación" : "Línea continua: periodo consultado");
        p.endText();
        return com.lowagie.text.Image.getInstance(p);
    }

    /** Une los PDF parciales en uno solo. */
    private static byte[] unir(List<byte[]> partes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document doc = new com.lowagie.text.Document();
        try {
            com.lowagie.text.pdf.PdfCopy copia = new com.lowagie.text.pdf.PdfCopy(doc, out);
            doc.open();
            for (byte[] b : partes) {
                com.lowagie.text.pdf.PdfReader lector = new com.lowagie.text.pdf.PdfReader(b);
                for (int i = 1; i <= lector.getNumberOfPages(); i++) {
                    copia.addPage(copia.getImportedPage(lector, i));
                }
                copia.freeReader(lector);
                lector.close();
            }
            doc.close();
        } catch (com.lowagie.text.DocumentException | IOException e) {
            throw new IllegalStateException("No se pudo unir el PDF de analítica", e);
        }
        return out.toByteArray();
    }

    /**
     * «Página n de m» en el pie de cada página (cubre el «Página n» de cada sección del Exportador).
     */
    private static byte[] numerar(byte[] pdf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            com.lowagie.text.pdf.PdfReader lector = new com.lowagie.text.pdf.PdfReader(pdf);
            com.lowagie.text.pdf.PdfStamper sello = new com.lowagie.text.pdf.PdfStamper(lector, out);
            int m = lector.getNumberOfPages();
            com.lowagie.text.pdf.BaseFont bf = NORMAL.getCalculatedBaseFont(false);
            for (int i = 1; i <= m; i++) {
                com.lowagie.text.Rectangle pagina = lector.getPageSizeWithRotation(i);
                com.lowagie.text.pdf.PdfContentByte c = sello.getOverContent(i);
                c.setColorFill(Color.WHITE);
                c.rectangle(pagina.getWidth() - 200, 8, 200, 22);
                c.fill();
                c.setColorFill(Color.BLACK);
                c.beginText();
                c.setFontAndSize(bf, 8);
                c.showTextAligned(com.lowagie.text.Element.ALIGN_RIGHT, "Página " + i + " de " + m, pagina.getWidth() - 30, 18, 0);
                c.endText();
            }
            sello.close();
            lector.close();
        } catch (com.lowagie.text.DocumentException | IOException e) {
            throw new IllegalStateException("No se pudo numerar el PDF de analítica", e);
        }
        return out.toByteArray();
    }
}
