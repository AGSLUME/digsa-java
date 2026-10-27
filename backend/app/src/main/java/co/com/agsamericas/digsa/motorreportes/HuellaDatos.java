package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Huella de los datos de un reporte (Anexo 5, 2.10): SHA-256 de una serialización canónica del reporte, su versión, la variante de
 * columnas, el periodo resuelto, la agrupación, el alcance, los filtros (ya ordenados) y las filas en su orden, con los números sin el
 * redondeo de presentación y las fechas en ISO. No entra el usuario, la fecha de generación, el código ni el formato: el mismo reporte
 * con los mismos filtros y el mismo alcance da la misma huella para cualquier usuario y en cualquier formato.
 */
final class HuellaDatos {

    private final MessageDigest sha;
    private final StringBuilder linea = new StringBuilder(256);

    HuellaDatos(DefinicionReporte d, ConsultaReporte c) {
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        escribir("reporte=" + d.id());
        escribir("version=" + d.version());
        escribir("columnas=" + String.join("\t", c.columnas()));
        escribir("periodo=" + c.desde() + ".." + c.hasta());
        escribir("agrupacion=" + (c.agrupacion() == null ? "" : c.agrupacion().clave()));
        escribir("alcance=" + alcance(c.alcance()));
        StringBuilder f = new StringBuilder();
        for (Map.Entry<String, List<String>> e : c.filtros().entrySet()) {
            f.append(e.getKey()).append('=').append(String.join(",", e.getValue().stream().map(HuellaDatos::escapar).toList())).append(';');
        }
        escribir("filtros=" + f);
        escribir("filas");
    }

    /** Forma estable del alcance (va en la huella y en el registro del reporte generado). */
    static String alcance(Alcance a) {
        return switch (a) {
            case Alcance.Todo t -> "todo";
            case Alcance.Opl o -> "opl";
            case Alcance.Fuerza f -> "fuerza:" + f.codigo();
            case Alcance.Regional r -> "regional:" + r.id();
            case Alcance.Esm e -> "esm:" + e.puntoId();
            case Alcance.Equipo e -> "equipo:" + e.usuarioId();
            case Alcance.Ninguno n -> "ninguno";
        };
    }

    void fila(List<Object> valores) {
        linea.setLength(0);
        for (int i = 0; i < valores.size(); i++) {
            if (i > 0) {
                linea.append('\t');
            }
            linea.append(valor(valores.get(i)));
        }
        escribir(linea.toString());
    }

    String hex() {
        return HexFormat.of().formatHex(sha.digest());
    }

    private void escribir(String s) {
        sha.update(s.getBytes(StandardCharsets.UTF_8));
        sha.update((byte) '\n');
    }

    /**
     * Valor canónico: el nulo es una barra seguida de N (un texto no la produce: sus barras se duplican al escapar), números sin
     * ceros de sobra, fechas ISO, instantes en UTC y texto con los separadores escapados. Solo los tipos de {@link TiposCelda}.
     */
    static String valor(Object v) {
        return switch (v) {
            case null -> "\\N";
            case BigDecimal b -> b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
            case Double d -> valor(BigDecimal.valueOf(d));
            case Integer i -> i.toString();
            case Long l -> l.toString();
            case Short s -> s.toString();
            case Boolean b -> b.toString();
            case LocalDate d -> d.toString();
            case LocalDateTime t -> t.toString();
            case OffsetDateTime o -> o.toInstant().toString();
            case ZonedDateTime z -> z.toInstant().toString();
            default -> escapar(v.toString());
        };
    }

    private static String escapar(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r").replace(",", "\\,").replace(";", "\\;")
                .replace("=", "\\=");
    }
}
