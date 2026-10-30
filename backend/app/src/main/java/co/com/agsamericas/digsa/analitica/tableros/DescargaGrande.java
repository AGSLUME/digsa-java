package co.com.agsamericas.digsa.analitica.tableros;

/**
 * La descarga pedida supera el umbral de registros y no se genera en la petición (A7.3, duda AN.16): se lanza ANTES de generar o escribir
 * nada, dentro de la transacción de lectura (que se descarta), con los registros contados y el corte resuelto, que el controlador
 * congela en la descarga diferida (202).
 */
final class DescargaGrande extends RuntimeException {

    private final long registros;
    private final long corteCargueId;

    DescargaGrande(long registros, long corteCargueId) {
        super(null, null, false, false);
        this.registros = registros;
        this.corteCargueId = corteCargueId;
    }

    long registros() {
        return registros;
    }

    long corteCargueId() {
        return corteCargueId;
    }
}
