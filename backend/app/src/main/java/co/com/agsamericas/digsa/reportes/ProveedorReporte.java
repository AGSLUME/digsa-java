package co.com.agsamericas.digsa.reportes;

import co.com.agsamericas.digsa.reglas.Alcance;
import java.util.List;
import java.util.function.Consumer;

/**
 * Contrato de un reporte del módulo de reportes (Anexo 5, 2.8 y l.769): cada módulo funcional registra sus reportes como beans que
 * implementan esta interfaz y el motor los descubre al arrancar. El proveedor solo entrega datos; el encabezado (2.9), los formatos
 * (2.12), la huella (2.10), el repositorio del día (2.7), el correo y la bitácora son del motor. Documentado en
 * {@code docs/especificaciones/reportes/contrato-proveedor.md}.
 */
public interface ProveedorReporte {

    /** Definición estática: id, título, grupo, clase, pantalla, filtros, periodo, columnas, fuente y versión. */
    DefinicionReporte definicion();

    /**
     * Opciones de un filtro de lista, recortadas al alcance (el motor valida contra ellas lo que llega del cliente). Con
     * {@code Alcance.Todo} devuelve todas: así el motor distingue «no existe» de «fuera de su alcance».
     */
    default List<Opcion> opciones(String filtro, Alcance alcance) {
        return List.of();
    }

    /** ¿Lo puede generar quien tiene este alcance? (p. ej. la distribución auditor–punto solo con alcance total). */
    default boolean disponible(Alcance alcance) {
        return true;
    }

    /**
     * Escribe las filas, en el orden total de la definición, con las columnas visibles de la consulta y en el mismo orden. El alcance
     * se aplica en el SQL. Corre dentro de una transacción de solo lectura que abre el motor.
     */
    void filas(ConsultaReporte consulta, Consumer<List<Object>> destino);
}
