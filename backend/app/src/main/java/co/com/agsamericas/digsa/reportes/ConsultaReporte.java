package co.com.agsamericas.digsa.reportes;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Consulta ya validada por el motor: el proveedor no vuelve a validar los valores, solo los aplica en el SQL junto con el alcance.
 *
 * @param desde      inicio del rango (incluido), o null si el reporte no tiene periodo
 * @param hasta      fin del rango (incluido)
 * @param agrupacion null = sin agrupar; si viene, cada fila empieza por la etiqueta del periodo ({@code Periodos.Agrupacion.etiqueta})
 * @param filtros    valores elegidos por filtro, ordenados y sin repetir; un filtro ausente = todas las opciones
 * @param columnas   nombres de las columnas visibles para el perfil, en orden (sin la de «Periodo»)
 * @param limite     filas que el proveedor debe pedir como máximo ({@code LIMIT}, con {@link JdbcReportes#limite}); 0 = sin límite.
 *                   El motor pide una más de las que muestra o admite, para saber si hay más
 */
public record ConsultaReporte(Alcance alcance, LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Map<String, List<String>> filtros,
                              List<String> columnas, int limite) {

    public ConsultaReporte {
        Map<String, List<String>> copia = new java.util.LinkedHashMap<>();
        filtros.forEach((k, v) -> copia.put(k, List.copyOf(v)));
        filtros = java.util.Collections.unmodifiableMap(copia);
        columnas = List.copyOf(columnas);
        if (limite < 0) {
            throw new IllegalArgumentException("El límite no puede ser negativo");
        }
    }

    /** La misma consulta con otro límite (el motor la ajusta según sea para pantalla o para archivo). */
    public ConsultaReporte conLimite(int n) {
        return new ConsultaReporte(alcance, desde, hasta, agrupacion, filtros, columnas, n);
    }

    /** Valores del filtro; vacío = todas. */
    public List<String> valores(String filtro) {
        return filtros.getOrDefault(filtro, List.of());
    }

    public boolean filtra(String filtro) {
        return !valores(filtro).isEmpty();
    }

    /** ¿El perfil ve esta columna? */
    public boolean ve(String columna) {
        return columnas.contains(columna);
    }
}
