package co.com.agsamericas.digsa.analitica.ingesta;

import java.io.IOException;
import java.util.List;

/**
 * Puerto único de consumo del insumo de dispensación del OPL (A5 4.7.1 punto 1; RESP L827 AGS-64): archivo estructurado
 * ({@link ArchivoDispensacion}) o servicio web ({@link ServicioWebOpl}). Validación, carga, bitácora y trazabilidad son las mismas sea
 * cual sea el mecanismo; solo cambia la fuente que queda en el cargue.
 */
interface FuenteDispensacion extends AutoCloseable {

    /** Encabezado: nombres de columna del archivo, o las claves de los campos en el servicio web. */
    List<String> encabezado();

    /** Fuente legible para la bitácora: «Archivo CSV (;) UTF-8», «Archivo xlsx», «API OPL · dispensación». */
    String fuenteTexto();

    /** ¿Los números traen coma decimal? (CSV con «;»). */
    boolean comaDecimal();

    /**
     * Recorre los registros en flujo, sin tener el insumo en memoria. El consumidor puede detener la lectura lanzando {@link Detener}.
     *
     * @throws ErrorEstructura si el insumo no cumple la estructura (codificación, hojas con otro encabezado...)
     */
    void recorrer(Consumidor consumidor) throws IOException;

    @Override
    void close() throws IOException;

    /** Recibe cada registro: ordinal (para ordenar y cruzar), línea de origen legible y celdas tal como vienen. */
    @FunctionalInterface
    interface Consumidor {
        void registro(long orden, String lineaOrigen, List<String> celdas) throws IOException;
    }

    /** Lo lanza el consumidor para dejar de leer (vista previa). */
    final class Detener extends RuntimeException {
        Detener() {
            super(null, null, false, false);
        }
    }
}
