package co.com.agsamericas.digsa.motorreportes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Tipos de celda que admite un reporte (contrato del proveedor): los mismos en pantalla, en la huella y en el archivo, para que un
 * reporte que se ve también se pueda descargar y verificar. Un tipo distinto (Timestamp, UUID, Float…) o un decimal no finito es un
 * error del proveedor y se rechaza igual en los dos caminos, nombrando la columna.
 */
final class TiposCelda {

    private TiposCelda() {
    }

    static boolean admitido(Object v) {
        return switch (v) {
            case null -> true;
            case String s -> true;
            case Integer i -> true;
            case Long l -> true;
            case Short s -> true;
            case BigDecimal b -> true;
            case Double d -> Double.isFinite(d);
            case Boolean b -> true;
            case LocalDate d -> true;
            case LocalDateTime t -> true;
            case OffsetDateTime o -> true;
            case ZonedDateTime z -> true;
            default -> false;
        };
    }

    /** Valida la fila del proveedor. */
    static void validar(String reporte, List<String> columnas, List<Object> fila) {
        for (int i = 0; i < fila.size(); i++) {
            Object v = fila.get(i);
            if (!admitido(v)) {
                throw new IllegalStateException("El reporte «" + reporte + "», columna «" + (i < columnas.size() ? columnas.get(i) : String.valueOf(i))
                        + "», entregó un valor de tipo " + v.getClass().getName() + " (o no finito): use String, Integer, Long, BigDecimal, LocalDate u OffsetDateTime");
            }
        }
    }
}
