package co.com.agsamericas.digsa.plataforma;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reporte exportable (Anexo 5, 2.9 a 2.12 y respuesta TAS P12): encabezado con título, fecha y hora de generación, usuario y
 * filtros aplicados; columnas y filas completas, sin truncar.
 */
public record Reporte(String titulo, ZonedDateTime generado, String usuario, Map<String, String> filtros, List<String> columnas,
                      List<List<Object>> filas) {

    public Reporte {
        filtros = filtros == null ? Map.of() : new LinkedHashMap<>(filtros);
        columnas = List.copyOf(columnas);
        filas = List.copyOf(filas);
    }
}
