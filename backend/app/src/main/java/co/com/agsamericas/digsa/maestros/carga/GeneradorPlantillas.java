package co.com.agsamericas.digsa.maestros.carga;

import co.com.agsamericas.digsa.reglas.ImportadorMaestros;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Tipo;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Reparto;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plantillas vacías de la carga inicial: {@code plantilla_carga_maestros.xlsx} (Instrucciones, Auditores, Puntos, Usuarios y Listas,
 * con validación de datos en las columnas de lista y los catálogos de la base) y los mismos encabezados en CSV UTF-8 con BOM y «;»
 * en la subcarpeta {@code csv/} (así la carpeta principal no queda con los dos formatos a la vez). Cada hoja trae una fila de ejemplo
 * con «borrar esta fila»: si no se borra, es error de la fila. Ninguna plantilla tiene columna de contraseña.
 */
@Component
public class GeneradorPlantillas {

    public static final String XLSX = "plantilla_carga_maestros.xlsx";
    static final int FILAS_VALIDADAS = ImportadorMaestros.MAX_FILAS + 1;

    /** Encabezados de cada hoja (los nombres de campo de ImportadorMaestros) y su fila de ejemplo (datos ficticios). */
    static final Map<Tipo, List<String>> ENCABEZADOS = Map.of(
            Tipo.AUDITORES, List.of("nombre", "correo", "documento", "telefono", "rol", "municipio_base", "usuario"),
            Tipo.PUNTOS, List.of("sigla", "nombre", "unidad", "fuerza", "modalidad", "nit", "divipola", "departamento", "municipio",
                    "direccion", "contacto", "correos", "exige_qf", "regional", "llega_desde", "auditor_asignado", "activo"),
            Tipo.USUARIOS, List.of("nombre", "correo", "usuario", "documento", "telefono", "perfiles", "alcance_fuerza", "alcance_punto",
                    "alcance_regional", "activo"));

    static final Map<Tipo, List<String>> EJEMPLOS = Map.of(
            Tipo.AUDITORES, List.of("Auditora Ejemplo (borrar esta fila)", "auditora.ejemplo@example.com", "1000000001", "3000000000",
                    "Químico farmacéutico", "11001", ""),
            Tipo.PUNTOS, List.of("EJEMPLO-01", "ESM Ejemplo (borrar esta fila)", "ESM", "EJC", "Dispensación", "", "11001", "", "",
                    "Calle 1 # 2-3", "Contacto Ejemplo", "esm.ejemplo@example.com", "No", "", "", "auditora.ejemplo@example.com", "Sí"),
            Tipo.USUARIOS, List.of("Usuario Ejemplo (borrar esta fila)", "usuario.ejemplo@example.com", "", "", "", "Sanidad", "EJC", "",
                    "", "Sí"));

    private final JdbcClient jdbc;

    public GeneradorPlantillas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Listas tomadas de la base: nombre de la lista ⇒ valores. */
    @Transactional(readOnly = true)
    public Map<String, List<String>> listas() {
        Map<String, List<String>> l = new LinkedHashMap<>();
        l.put("Unidades", jdbc.sql("SELECT codigo FROM par_unidades ORDER BY orden, codigo").query(String.class).list());
        l.put("Fuerzas", jdbc.sql("SELECT codigo FROM par_fuerzas WHERE activo ORDER BY orden, codigo").query(String.class).list());
        l.put("Modalidades", jdbc.sql("SELECT nombre FROM par_modalidades ORDER BY orden, nombre").query(String.class).list());
        l.put("Roles", List.copyOf(Reparto.ROLES.values()));
        l.put("Perfiles", jdbc.sql("SELECT nombre FROM perfiles WHERE activo AND id NOT IN (:admin, :auditor) ORDER BY id")
                .param("admin", Perfil.ADMINISTRADOR.id()).param("auditor", Perfil.AUDITOR.id()).query(String.class).list());
        l.put("Regionales", jdbc.sql("SELECT nombre FROM par_regionales WHERE activo ORDER BY nombre").query(String.class).list());
        l.put("Sí/No", List.of("Sí", "No"));
        List<String> divipola = new ArrayList<>();
        List<String> municipios = new ArrayList<>();
        jdbc.sql("""
                SELECT m.codigo, d.nombre AS departamento, m.nombre FROM par_municipios m JOIN par_departamentos d ON d.codigo = m.departamento
                ORDER BY d.nombre, m.nombre""").query((rs, n) -> {
            divipola.add(rs.getString("codigo"));
            municipios.add(rs.getString("departamento") + " / " + rs.getString("nombre"));
            return null;
        }).list();
        l.put("DIVIPOLA", divipola);
        l.put("Departamento / Municipio", municipios);
        return l;
    }

    /** Escribe las plantillas en la carpeta. @return los archivos escritos */
    public List<Path> escribir(Path carpeta) throws IOException {
        Files.createDirectories(carpeta);
        Map<String, List<String>> listas = listas();
        Path xlsx = carpeta.resolve(XLSX);
        try (XSSFWorkbook libro = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            CellStyle negrilla = libro.createCellStyle();
            Font f = libro.createFont();
            f.setBold(true);
            negrilla.setFont(f);
            instrucciones(libro.createSheet("Instrucciones"), negrilla);
            Map<String, String> rangos = new LinkedHashMap<>();
            int col = 0;
            for (var e : listas.entrySet()) {
                rangos.put(e.getKey(), "Listas!$" + CellReference.convertNumToColString(col) + "$2:$"
                        + CellReference.convertNumToColString(col) + "$" + Math.max(2, e.getValue().size() + 1));
                col++;
            }
            for (Tipo t : Tipo.values()) {
                Sheet s = libro.createSheet(t.hoja());
                Row enc = s.createRow(0);
                List<String> cols = ENCABEZADOS.get(t);
                for (int i = 0; i < cols.size(); i++) {
                    enc.createCell(i).setCellValue(cols.get(i));
                    enc.getCell(i).setCellStyle(negrilla);
                    s.setColumnWidth(i, 22 * 256);
                }
                Row ej = s.createRow(1);
                List<String> ejemplo = EJEMPLOS.get(t);
                for (int i = 0; i < ejemplo.size(); i++) {
                    ej.createCell(i).setCellValue(ejemplo.get(i));
                }
                s.createFreezePane(0, 1);
                validaciones(s, t, rangos);
            }
            Sheet l = libro.createSheet("Listas");
            Row enc = l.createRow(0);
            int c = 0;
            for (var e : listas.entrySet()) {
                enc.createCell(c).setCellValue(e.getKey());
                enc.getCell(c).setCellStyle(negrilla);
                for (int i = 0; i < e.getValue().size(); i++) {
                    Row r = l.getRow(i + 1) == null ? l.createRow(i + 1) : l.getRow(i + 1);
                    r.createCell(c).setCellValue(e.getValue().get(i));
                }
                l.setColumnWidth(c, (e.getKey().startsWith("Departamento") ? 45 : 22) * 256);
                c++;
            }
            libro.write(out);
        }
        List<Path> escritos = new ArrayList<>(List.of(xlsx));
        Path csv = carpeta.resolve("csv");
        Files.createDirectories(csv);
        for (Tipo t : Tipo.values()) {
            Path p = csv.resolve(t.csv());
            String texto = "﻿" + String.join(";", ENCABEZADOS.get(t)) + "\r\n" + String.join(";", EJEMPLOS.get(t)) + "\r\n";
            Files.writeString(p, texto, StandardCharsets.UTF_8);
            escritos.add(p);
        }
        return escritos;
    }

    private static void validaciones(Sheet s, Tipo t, Map<String, String> rangos) {
        Map<String, String> porCampo = switch (t) {
            case AUDITORES -> Map.of("rol", "Roles", "municipio_base", "DIVIPOLA");
            case PUNTOS -> Map.of("unidad", "Unidades", "fuerza", "Fuerzas", "modalidad", "Modalidades", "divipola", "DIVIPOLA",
                    "exige_qf", "Sí/No", "regional", "Regionales", "activo", "Sí/No");
            case USUARIOS -> Map.of("perfiles", "Perfiles", "alcance_fuerza", "Fuerzas", "alcance_regional", "Regionales", "activo", "Sí/No");
        };
        DataValidationHelper ayuda = s.getDataValidationHelper();
        List<String> cols = ENCABEZADOS.get(t);
        for (var e : porCampo.entrySet()) {
            int i = cols.indexOf(e.getKey());
            DataValidation v = ayuda.createValidation(ayuda.createFormulaListConstraint(rangos.get(e.getValue())),
                    new CellRangeAddressList(1, FILAS_VALIDADAS, i, i));
            // Perfiles admite varios separados por coma y el municipio también «Departamento / Municipio»: allí la lista solo sugiere
            boolean estricta = !Map.of("perfiles", 1, "municipio_base", 1, "divipola", 1).containsKey(e.getKey());
            v.setShowErrorBox(estricta);
            v.setEmptyCellAllowed(true);
            if (estricta) {
                v.setErrorStyle(DataValidation.ErrorStyle.STOP);
                v.createErrorBox("Valor no válido", "Elija un valor de la lista (hoja Listas).");
            }
            s.addValidationData(v);
        }
    }

    private static void instrucciones(Sheet s, CellStyle negrilla) {
        String[] lineas = {
                "Carga inicial de datos maestros · Suite DIGSA",
                "",
                "1. Diligencie las hojas Auditores, Puntos y Usuarios (una o varias). Borre la fila de ejemplo que dice «borrar esta fila».",
                "2. No agregue columnas de contraseña: cada usuario nuevo recibe un enlace para definirla.",
                "3. El perfil Administrador no se carga aquí (se crea con --digsa.crear-admin). El perfil Auditor va en la hoja Auditores.",
                "4. Auditores: nombre, correo, documento y rol son obligatorios. El correo es la clave: si ya existe, se le agrega el perfil Auditor.",
                "5. Puntos: la clave es la sigla; sin sigla, nombre + unidad. Para completar puntos existentes basta la sigla y las columnas a cambiar.",
                "   ESM exige modalidad; el nivel central (NC) va sin fuerza; el NIT solo aplica a la farmacia alterna (FA).",
                "   auditor_asignado es el correo de un auditor activo (de la base o de la hoja Auditores de esta carga).",
                "6. Usuarios: perfiles separados por coma. Sanidad necesita alcance_fuerza; ESM, alcance_punto (sigla o nombre del ESM);",
                "   OPL regional, alcance_regional. El alcance que no aplica se deja vacío. Los perfiles se agregan: no se quitan los que ya tenía.",
                "7. Una celda vacía no borra lo guardado. Nada se borra ni se inactiva por no venir en el archivo.",
                "8. Primero se simula (no escribe nada) y se revisa resultado_carga_<fecha>.csv; luego se aplica. Con una sola fila con error no se escribe nada.",
                "9. Los municipios van por su código DIVIPOLA (hoja Listas) o como «Departamento / Municipio».",
                "",
                "Datos personales (Ley 1581 de 2012): transfiera este archivo por un canal cifrado, no por correo, y bórrelo del servidor al terminar."};
        for (int i = 0; i < lineas.length; i++) {
            s.createRow(i).createCell(0).setCellValue(lineas[i]);
        }
        s.getRow(0).getCell(0).setCellStyle(negrilla);
        s.setColumnWidth(0, 140 * 256);
    }
}
