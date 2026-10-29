package co.com.agsamericas.digsa.analitica;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Evento que se publica después de confirmar que un cargue quedó «aplicado» (su aplicado_en es la nueva fecha de corte). Lo escuchan
 * cohortes y alertas (A4) para recalcular sin bloquear el cargue. Los agregados de los tableros (A3, V62) no esperan este evento: se
 * refrescan dentro de la misma transacción con {@link PasoAplicacion}.
 *
 * @param reemplazados cargues cuyo periodo dejó de verse desde este corte («Reemplazar el periodo»)
 */
public record CargueAplicado(long cargueId, String fuente, OffsetDateTime aplicadoEn, LocalDate periodoDesde, LocalDate periodoHasta,
                             long procesados, java.util.List<Long> reemplazados) {
}
