package co.com.agsamericas.digsa.analitica;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Puerto del sugeridor de homologación (analitica-homologacion, «Revisión humana con sugerencia opcional de la IA»; analitica-asistente,
 * «Sugeridor de homologación como puerto compartido»). Recibe solo el texto del medicamento del insumo y los candidatos del CUM del
 * INVIMA (nunca seudónimos, ESM ni cantidades) y devuelve una sugerencia que la revisión valida contra los candidatos (un CUM inventado se
 * descarta) y muestra como «Sugerencia (IA)». La decisión es siempre humana: nada cambia de estado por una sugerencia.
 *
 * <p>Por defecto está el adaptador «no disponible» (sin sugerencia automática); A6 conecta los adaptadores con IA.
 */
public interface SugeridorHomologacion {

    /** Candidato de la referencia oficial: CUM y su descripción (producto, principio, concentración y forma). */
    record Candidato(String cum, String descripcion) {
    }

    /** Sugerencia del proveedor: CUM elegido entre los candidatos, justificación y confianza (0 a 1). */
    record Sugerencia(String cum, String justificacion, BigDecimal confianza) {
    }

    /** ¿Hay proveedor configurado? Sin él la pantalla dice «Sin sugerencia automática». */
    boolean disponible();

    /** Nombre del proveedor y modelo para la auditoría («ninguno» por defecto). */
    String proveedor();

    /**
     * @param textoOriginal descripción del medicamento tal como vino del insumo
     * @param candidatos    CUM candidatos de la versión publicada
     */
    Optional<Sugerencia> sugerir(String textoOriginal, List<Candidato> candidatos);
}
