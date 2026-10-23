package co.com.agsamericas.digsa.plataforma;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Filas de un reporte que se pueden recorrer más de una vez sin tenerlas en memoria (p. ej. volcadas a un archivo temporal): el TXT
 * las recorre dos veces, una para medir las columnas y otra para escribirlas alineadas.
 */
public interface ReporteFilas {

    /** Cantidad de filas (va en «Registros» del encabezado). */
    long total();

    /**
     * Entrega cada fila al consumidor. Quien implemente no debe atrapar las {@code RuntimeException} del consumidor: el PDF corta así
     * la muestra de los anchos de columna y el motor, los topes.
     */
    void recorrer(Consumer<List<Object>> fila) throws IOException;

    /** Filas que ya están en memoria (reportes pequeños y pruebas). */
    static ReporteFilas de(List<List<Object>> filas) {
        return new ReporteFilas() {
            @Override
            public long total() {
                return filas.size();
            }

            @Override
            public void recorrer(Consumer<List<Object>> fila) {
                filas.forEach(fila);
            }
        };
    }
}
