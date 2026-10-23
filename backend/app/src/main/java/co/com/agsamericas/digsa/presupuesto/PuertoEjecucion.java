package co.com.agsamericas.digsa.presupuesto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * Entrada de la ejecución presupuestal (spec «Ejecución por un puerto con varias fuentes»). Hoy la usan la carga de archivo de la DIGSA o
 * del OPL y el registro manual; mañana la usará el módulo de cuentas médicas para publicar lo facturado y lo reconocido tras la
 * conciliación (Anexo 2 L75). Cada corte es el total del mes de un ESM y una modalidad: un corte nuevo del mismo mes reemplaza al
 * anterior como versión vigente, sin borrarlo. Todo o nada: con un corte inválido no se registra ninguno.
 */
public interface PuertoEjecucion {

    /**
     * @param esm        sigla o nombre del ESM (un satélite se imputa a su centralizador)
     * @param fuerza     solo para HOMIL (fuerza de adscripción); vacía toma la del ESM
     * @param reconocido null mientras no se concilie
     */
    record Corte(int vigencia, String fuerza, String esm, String modalidad, YearMonth periodo, BigDecimal facturado, BigDecimal reconocido,
                 String referencia) {
    }

    record Resultado(int cortes, List<String> errores) {
        public boolean aplicado() {
            return errores.isEmpty();
        }
    }

    /** Registra los cortes de otro módulo (fuente «cuentas_medicas»), sin usuario. */
    Resultado registrarCortes(List<Corte> cortes, String referenciaLote);
}
