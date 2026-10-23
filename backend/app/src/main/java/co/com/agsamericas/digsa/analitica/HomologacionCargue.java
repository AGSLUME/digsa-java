package co.com.agsamericas.digsa.analitica;

/**
 * Etapa «homologación» del cargue (analitica-homologacion, «Enlace con los hechos de dispensación sin reescribirlos»): corre después de
 * la validación y antes de cargar los hechos, sobre los pares distintos (CUM, medicamento) de los registros válidos del staging (pocos
 * miles aunque el cargue tenga 2 M de filas). Homologa solo los datos originales nuevos del cargue (su costo crece con el cargue, no con
 * la historia) y deja en {@code hom_cargue_origen} el par ⇒ (medicamento_id, versión) que la carga de hechos une por llave primaria. Lo
 * ya procesado nunca se reescribe.
 *
 * <p>Todo o nada con el cargue: lo que la etapa escribe queda «pendiente del cargue» (datos originales nuevos, versión de la tabla
 * maestra sin número, pares y conteos por mes) y no se ve en ninguna pantalla. {@link #alAplicar} corre dentro de la transacción que
 * marca el cargue «aplicado» y hace lo que tiene efecto de negocio (publicar la versión, confirmar los datos originales, fecha de
 * referencia y seguimiento de las devoluciones al OPL); {@link #descartar} borra lo pendiente si el cargue termina fallido, rechazado,
 * duplicado o interrumpido.
 *
 * <p>Contrato con la carga de hechos (AplicadorHechos): una fila de {@code hom_cargue_origen} por cada par de los registros válidos del
 * cargue, con {@code par_llave = encode(sha256(convert_to(coalesce(cum_original, '') || chr(1) || coalesce(medicamento, ''), 'UTF8')),
 * 'hex')}. Si hay
 * implementación y algún hecho queda sin {@code medicamento_id}, el cargue falla (la cadena dato original → tabla maestra se rompería).
 */
public interface HomologacionCargue {

    /**
     * Homologa los datos originales nuevos del staging del cargue (en su propia transacción) y deja todo pendiente del cargue. Si lanza, el
     * cargue queda «fallido» en la etapa homologacion y nada se aplica.
     */
    void homologar(long cargueId);

    /** Dentro de la transacción que marca el cargue «aplicado» (después de cargar los hechos): publica y confirma lo pendiente. */
    void alAplicar(long cargueId);

    /** El cargue no se aplicó (fallido, rechazado, duplicado o interrumpido): borra lo pendiente. No lanza (registra el error). */
    void descartar(long cargueId);
}
