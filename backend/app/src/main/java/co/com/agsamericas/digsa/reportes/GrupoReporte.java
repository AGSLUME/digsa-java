package co.com.agsamericas.digsa.reportes;

/**
 * Grupos del catálogo, en el orden del mockup (Anexo 5, l.769: cuentas médicas, glosas, objeciones, conciliaciones, visitas,
 * hallazgos, ejecución presupuestal, usuarios y seguridad). La analítica farmacéutica no va aquí: es de su propio módulo (4.7.5).
 */
public enum GrupoReporte {
    DISPENSADO_AUDITADO("Dispensado frente a auditado"),
    PENDIENTES_OPL("Pendientes OPL y gestión"),
    GLOSAS("Glosas por estado y término"),
    CONCILIACIONES("Conciliaciones y actas"),
    VISITAS("Visitas y hallazgos"),
    PRESUPUESTO("Ejecución presupuestal"),
    SEGURIDAD("Usuarios y seguridad");

    private final String nombre;

    GrupoReporte(String nombre) {
        this.nombre = nombre;
    }

    public String nombre() {
        return nombre;
    }
}
