package co.com.agsamericas.digsa.reglas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Estados de una visita y qué se puede hacer en cada uno (equivalente de app/Support/EstadosVisita.php). Los cambios de estado en la
 * base se hacen con UPDATE … WHERE estado IN (origen) para que dos usuarios a la vez no pisen sus cambios.
 */
public final class EstadosVisita {

    public static final Map<String, String> NOMBRES;
    public static final Set<String> POR_EJECUTAR = Set.of("programada", "notificada", "reprogramada");
    /** acción → estados desde los que se permite */
    public static final Map<String, Set<String>> ORIGEN = Map.of(
            "editar", Set.of("programada"),
            "notificar", Set.of("programada", "reprogramada"),
            "reprogramar", POR_EJECUTAR,
            "cancelar", Set.of("programada", "notificada", "reprogramada", "ejecucion"),
            "reasignar", Set.of("notificada", "reprogramada"),          // decisión 28: la programada sin notificar se edita
            "iniciar", POR_EJECUTAR,
            "reabrir", Set.of("cerrada"));                              // Anexo 5, prueba 10; decisión 1
    /** Motivos permitidos para reabrir (decisión 1, segunda ronda). */
    public static final Map<String, String> MOTIVOS_REAPERTURA;

    static {
        Map<String, String> n = new LinkedHashMap<>();
        n.put("programada", "Programada");
        n.put("notificada", "Notificada");
        n.put("reprogramada", "Reprogramada");
        n.put("ejecucion", "En ejecución");
        n.put("cerrada", "Cerrada");
        n.put("cancelada", "Cancelada");
        NOMBRES = java.util.Collections.unmodifiableMap(n);
        Map<String, String> r = new LinkedHashMap<>();
        r.put("soporte", "Faltó cargar un anexo o soporte");
        r.put("cliente", "Necesidad del cliente (DIGSA)");
        MOTIVOS_REAPERTURA = java.util.Collections.unmodifiableMap(r);
    }

    private EstadosVisita() {
    }

    /**
     * Acciones disponibles en el orden de la pantalla.
     *
     * @param esCoordinador coordinador o administrador
     * @param esDelEquipo   el usuario es el líder u otro auditor de la visita
     * @param aviso         opl | modulo | no-opl
     */
    public static List<String> acciones(String estado, String aviso, boolean esCoordinador, boolean esDelEquipo) {
        List<String> a = new ArrayList<>();
        if (esCoordinador) {
            for (String x : List.of("editar", "reasignar", "reprogramar", "cancelar")) {
                if (permite(x, estado)) {
                    a.add(x);
                }
            }
            if (!"no-opl".equals(aviso) && permite("notificar", estado)) {
                a.add("notificar");
            }
            if (permite("reabrir", estado)) {
                a.add("reabrir");
            }
        }
        if (esDelEquipo && (permite("iniciar", estado) || "ejecucion".equals(estado))) {
            a.add("iniciar");
        }
        return a;
    }

    public static boolean permite(String accion, String estado) {
        return ORIGEN.getOrDefault(accion, Set.of()).contains(estado);
    }
}
