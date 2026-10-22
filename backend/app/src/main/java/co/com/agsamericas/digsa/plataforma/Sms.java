package co.com.agsamericas.digsa.plataforma;

/**
 * Puerto de SMS de las alertas críticas (GMI P77 b: «SMS u otro canal inmediato»). Adaptadores: archivo (solo en local) y ninguno; el
 * del proveedor que se contrate (ACS SMS o un agregador colombiano) entra como otro adaptador sin cambiar a quien envía.
 */
public interface Sms {

    /**
     * Resultado del envío; el error sin el número ni datos internos. {@code definitivo}: no tiene sentido reintentar (SMS no configurado,
     * número rechazado por el proveedor).
     */
    record Resultado(boolean ok, String error, boolean definitivo) {
        public static Resultado exito() {
            return new Resultado(true, null, true);
        }

        public static Resultado falla(String error) {
            return new Resultado(false, error, false);
        }

        public static Resultado omitido(String motivo) {
            return new Resultado(false, motivo, true);
        }
    }

    /** Envía el texto al teléfono en E.164 y devuelve el resultado; nunca lanza. */
    Resultado enviar(String telefono, String texto);

    /** ¿Es un envío real? (en local el SMS queda en un archivo y no sale). */
    boolean real();
}
