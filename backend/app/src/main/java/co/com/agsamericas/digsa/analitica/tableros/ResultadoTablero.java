package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tipo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resultado de un tablero (o de una consulta del asistente): KPI, tablas (cada una con su gráfico), advertencias, observaciones, metadatos
 * de 14.8 y huella SHA-256 del contenido. Siempre agregado: ninguna fila identifica a un paciente. Las descargas exportan exactamente esto.
 *
 * @param disponible falso en los tableros de la siguiente fase y cuando aún no hay cargues aplicados (sin cifras)
 * @param mensaje    «Disponible en la siguiente fase» o «Aún no hay cargues aplicados»
 * @param niveles    vistas a las que llega el alcance (nacional, fuerza, esm)
 * @param huella     SHA-256 del resultado canónico (sin usuario ni hora): igual para el mismo perfil, filtros y corte
 */
public record ResultadoTablero(String tablero, String titulo, String nivel, List<String> niveles, boolean disponible, String mensaje,
                               List<Kpi> kpis, List<Tabla> tablas, List<String> advertencias, List<String> observaciones,
                               List<String> noDisponible, Metadatos metadatos, String huella) {

    /**
     * Indicador. {@code valor} y {@code comparado} van sin redondear; los textos ya tienen el formato 2.4.
     *
     * @param rastreo filtros adicionales con que se rastrea la cifra (vacío = la consulta completa; nulo = no se rastrea)
     */
    public record Kpi(String clave, String etiqueta, Tipo tipo, BigDecimal valor, String texto, BigDecimal comparado, String textoComparado,
                      BigDecimal variacion, BigDecimal variacionPct, String textoVariacion, String nota, Map<String, String> rastreo) {
    }

    public record Columna(String clave, String etiqueta, Tipo tipo) {
    }

    /**
     * Fila de una tabla.
     *
     * @param clave   código de la fila (dimensión), para ordenar y bajar de nivel
     * @param rastreo filtros que identifican la celda (variable ⇒ valor; «periodo» ⇒ etiqueta del periodo); nulo en «Resto» y en
     *                dimensiones que no son filtro
     * @param valores valores sin formato (BigDecimal, Long o texto), en el orden de las columnas
     * @param textos  los mismos con el formato 2.4
     * @param tipo    dato, resto, sin (sin homologar o sin dato) o total
     */
    public record Fila(String clave, Map<String, String> rastreo, List<Object> valores, List<String> textos, String tipo) {
    }

    /**
     * Tabla con su gráfico.
     *
     * @param grafico   barras, serie, calor o ninguno
     * @param valorGrafico clave de la columna que dibuja el gráfico
     * @param comparado clave de la columna comparada de la serie (línea discontinua), si hay comparación
     * @param nota      aclaración (p. ej. «Un paciente puede estar en varias filas»)
     */
    public record Tabla(String id, String titulo, String grafico, String valorGrafico, String comparado, List<Columna> columnas, List<Fila> filas,
                        String nota) {
    }

    public record FiltroAplicado(String variable, String etiqueta, List<String> valores, List<String> textos) {
    }

    /**
     * Metadatos de 4.7.2, 2.9 y 14.8 que no dependen del usuario ni de la hora (los de la descarga se agregan al exportar).
     *
     * @param registros registros del insumo (dispensaciones) detrás de las cifras
     * @param supresion regla de supresión de celdas pequeñas aplicada («Desactivada» o «celdas con menos de k pacientes…»)
     */
    public record Metadatos(Long corteCargueId, OffsetDateTime corte, String corteTexto, Integer tablaMaestra, List<Long> cargues, String fuente,
                            LocalDate desde, LocalDate hasta, String periodoTexto, String granularidad, String comparacion,
                            String periodoComparacion, String alcance, String establecimiento, List<FiltroAplicado> filtros, long registros,
                            LocalDate ultimoDia, String supresion) {
    }

    /** Fila a partir de valores y columnas: los textos con el formato 2.4 de cada tipo. */
    public static Fila fila(String clave, Map<String, String> rastreo, List<Columna> columnas, List<Object> valores, String tipo) {
        List<String> textos = new ArrayList<>(valores.size());
        for (int i = 0; i < valores.size(); i++) {
            textos.add(AnaliticaConsulta.formato(valores.get(i), columnas.get(i).tipo()));
        }
        return new Fila(clave, rastreo, java.util.Collections.unmodifiableList(new ArrayList<>(valores)), List.copyOf(textos), tipo);
    }
}
