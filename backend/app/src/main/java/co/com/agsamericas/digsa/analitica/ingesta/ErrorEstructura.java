package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Causa;

/**
 * El insumo no cumple la estructura y se rechaza completo, sin ningún hecho (RESP L2279, GMI P77 f): archivo vacío o sin encabezado,
 * codificación distinta de UTF-8, encabezados repetidos, hojas con otro encabezado o más filas mal formadas que el umbral.
 */
class ErrorEstructura extends RuntimeException {

    private final Causa causa;
    private final String linea;
    private final long cuantas;

    ErrorEstructura(Causa causa, String mensaje, String linea, long cuantas) {
        super(mensaje, null, false, false);
        this.causa = causa;
        this.linea = linea;
        this.cuantas = cuantas;
    }

    Causa causa() {
        return causa;
    }

    /** Primera línea donde ocurre. */
    String linea() {
        return linea;
    }

    /** Registros afectados (1 si no aplica). */
    long cuantas() {
        return cuantas;
    }
}
