package co.com.agsamericas.digsa.ejecucion;

import co.com.agsamericas.digsa.agenda.RepositorioVisitas;
import java.util.Set;

/**
 * Qué puede hacer un usuario con el formulario de una visita (docs/especificaciones/equipo-reparto y checklist):
 * <ul>
 * <li>cada integrante escribe solo sus partes; el líder, además, los datos de la visita, el personal, las firmas y el acta;</li>
 * <li>el único integrante de un equipo de uno escribe todo;</li>
 * <li>el coordinador que no es del equipo corrige todo solo si la visita fue reabierta (Anexo 5, prueba 10).</li>
 * </ul>
 */
record Acceso(RepositorioVisitas.Visita visita, boolean miembro, boolean lider, boolean coordinador, boolean todo, Set<String> misPartes,
              boolean escribible, String motivoSoloLectura) {

    /** El líder (o quien corrige todo) escribe los datos de la visita, el personal y las firmas. */
    boolean escribeVisita() {
        return escribible && (lider || todo);
    }

    boolean escribeParte(String parte) {
        return escribible && (todo || misPartes.contains(parte));
    }

    /** Corrección del coordinador en una visita reabierta: queda en el historial. */
    boolean correccion() {
        return coordinador && !miembro && visita.reaperturas() > 0;
    }
}
