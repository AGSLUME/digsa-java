package co.com.agsamericas.digsa.ejecucion;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Acta de la visita en PDF (Anexo 5, prueba 10): establecimiento, componentes evaluados, quién visita, fechas de inicio y fin,
 * hallazgos y recomendaciones, firmas y observaciones del ESM y del OPL, más los soportes con su huella SHA-256. Sin tope de filas.
 */
final class GeneradorActa {

    private static final Color AZUL = new Color(11, 61, 92);
    private static final Color GRIS = new Color(235, 239, 243);
    private static final Font TITULO = new Font(Font.HELVETICA, 14, Font.BOLD, AZUL);
    private static final Font SECCION = new Font(Font.HELVETICA, 11, Font.BOLD, AZUL);
    private static final Font NORMAL = new Font(Font.HELVETICA, 9);
    private static final Font NEGRITA = new Font(Font.HELVETICA, 9, Font.BOLD);
    private static final Font PEQUENA = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.DARK_GRAY);

    /** Contenido del acta, armado por el servicio. */
    record Contenido(String numero, int ronda, Map<String, String> datos, List<String[]> equipo, List<String[]> personal,
                     List<Componente> componentes, Map<String, String> resumen, String recomendaciones, String observacionesAuditor,
                     String observacionesEsm, String observacionesOpl, List<String[]> soportes, List<FirmaActa> firmas, String generado) {
    }

    record Componente(String codigo, String nombre, List<String[]> items) {
    }

    record FirmaActa(String rotulo, String nombre, String documento, String cargo, byte[] png) {
    }

    private GeneradorActa() {
    }

    static byte[] generar(Contenido c) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.LETTER, 40, 40, 50, 50);
        try {
            PdfWriter w = PdfWriter.getInstance(doc, out);
            w.setPageEvent(new PdfPageEventHelper() {
                @Override
                public void onEndPage(PdfWriter writer, Document d) {
                    ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER,
                            new Phrase("Acta " + c.numero() + " · ronda " + c.ronda() + " · página " + writer.getPageNumber() + " · generada " + c.generado(), PEQUENA),
                            (d.left() + d.right()) / 2, d.bottom() - 20, 0);
                }
            });
            doc.addTitle("Acta de visita " + c.numero());
            doc.addAuthor("Suite DIGSA · Interventoría AGS Colombia");
            doc.open();
            doc.add(new Paragraph("Acta de visita de seguimiento farmacéutico", TITULO));
            doc.add(new Paragraph("Interventoría DIGSA · " + c.numero() + (c.ronda() > 1 ? " · ronda " + c.ronda() + " (visita reabierta)" : ""), NORMAL));

            seccion(doc, "Datos de la visita");
            PdfPTable datos = new PdfPTable(new float[] {1.3f, 3f});
            datos.setWidthPercentage(100);
            for (var e : c.datos().entrySet()) {
                datos.addCell(celda(e.getKey(), NEGRITA, GRIS));
                datos.addCell(celda(e.getValue(), NORMAL, null));
            }
            doc.add(datos);

            seccion(doc, "Equipo auditor (quién visita)");
            doc.add(tabla(new String[] {"Nombre", "Rol", "Función"}, c.equipo(), new float[] {3, 2, 1.2f}));

            seccion(doc, "Personal auditado (quién atiende)");
            doc.add(c.personal().isEmpty() ? new Paragraph("Sin registrar.", NORMAL)
                    : tabla(new String[] {"Entidad", "Nombre", "Cargo", "Documento"}, c.personal(), new float[] {1, 3, 2, 1.5f}));

            seccion(doc, "Resultado");
            PdfPTable res = new PdfPTable(c.resumen().size());
            res.setWidthPercentage(100);
            c.resumen().keySet().forEach(k -> res.addCell(celda(k, NEGRITA, GRIS)));
            c.resumen().values().forEach(v -> res.addCell(celda(v, NORMAL, null)));
            doc.add(res);

            seccion(doc, "Componentes evaluados");
            for (Componente comp : c.componentes()) {
                Paragraph p = new Paragraph(comp.codigo() + " · " + comp.nombre(), NEGRITA);
                p.setSpacingBefore(6);
                doc.add(p);
                doc.add(tabla(new String[] {"Ítem", "Criterio", "Calificación", "Hallazgo"}, comp.items(), new float[] {0.9f, 4f, 1.1f, 3f}));
            }

            seccion(doc, "Hallazgos");
            List<String[]> hallazgos = c.componentes().stream().flatMap(x -> x.items().stream()).filter(i -> "No cumple".equals(i[2]))
                    .map(i -> new String[] {i[0], i[3]}).toList();
            doc.add(hallazgos.isEmpty() ? new Paragraph("Sin hallazgos.", NORMAL)
                    : tabla(new String[] {"Ítem", "Hallazgo"}, hallazgos, new float[] {1, 6}));

            texto(doc, "Recomendaciones", c.recomendaciones());
            texto(doc, "Observaciones del auditor", c.observacionesAuditor());
            texto(doc, "Observaciones del ESM", c.observacionesEsm());
            texto(doc, "Observaciones del OPL", c.observacionesOpl());

            seccion(doc, "Soportes");
            doc.add(c.soportes().isEmpty() ? new Paragraph("Sin soportes.", NORMAL)
                    : tabla(new String[] {"Clave", "Archivo", "Tipo", "SHA-256"}, c.soportes(), new float[] {1, 2.5f, 1.5f, 4}));

            seccion(doc, "Firmas");
            PdfPTable firmas = new PdfPTable(Math.max(1, Math.min(3, c.firmas().size())));
            firmas.setWidthPercentage(100);
            for (FirmaActa f : c.firmas()) {
                PdfPCell cel = new PdfPCell();
                cel.setPadding(6);
                try {
                    Image img = Image.getInstance(f.png());
                    img.scaleToFit(150, 60);
                    cel.addElement(img);
                } catch (IOException | RuntimeException e) {
                    // Un acta legal no sale con una firma faltante: la firma se valida al registrarla, así que esto es un error del servidor
                    throw new IllegalStateException("No se pudo leer la firma de " + f.rotulo() + " para el acta", e);
                }
                cel.addElement(new Paragraph(f.nombre(), NEGRITA));
                cel.addElement(new Paragraph(f.rotulo() + (f.cargo() == null ? "" : " · " + f.cargo())
                        + (f.documento() == null ? "" : " · " + f.documento()), PEQUENA));
                firmas.addCell(cel);
            }
            firmas.completeRow(); // una fila incompleta no se imprime
            doc.add(firmas);
            Paragraph ley = new Paragraph("Los datos personales de esta acta se tratan según la Ley 1581 de 2012 y solo para el seguimiento del contrato.", PEQUENA);
            ley.setSpacingBefore(10);
            doc.add(ley);
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("No se pudo generar el acta", e);
        }
        return out.toByteArray();
    }

    private static void seccion(Document doc, String titulo) throws DocumentException {
        Paragraph p = new Paragraph(titulo, SECCION);
        p.setSpacingBefore(10);
        p.setSpacingAfter(4);
        doc.add(p);
    }

    private static void texto(Document doc, String titulo, String valor) throws DocumentException {
        seccion(doc, titulo);
        doc.add(new Paragraph(valor == null || valor.isBlank() ? "Sin observaciones." : valor, NORMAL));
    }

    private static PdfPTable tabla(String[] encabezados, List<String[]> filas, float[] anchos) {
        PdfPTable t = new PdfPTable(anchos);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String e : encabezados) {
            t.addCell(celda(e, NEGRITA, GRIS));
        }
        for (String[] f : filas) {
            for (int i = 0; i < encabezados.length; i++) {
                t.addCell(celda(i < f.length ? f[i] : "", NORMAL, null));
            }
        }
        return t;
    }

    private static PdfPCell celda(String texto, Font f, Color fondo) {
        PdfPCell c = new PdfPCell(new Phrase(new Chunk(texto == null ? "" : texto, f)));
        c.setPadding(4);
        if (fondo != null) {
            c.setBackgroundColor(fondo);
        }
        return c;
    }
}
