package co.com.agsamericas.digsa.analitica.riesgo;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de cohortes y alertas tempranas (prefijo {@code digsa.analitica.riesgo}; clase propia del módulo). Valores por defecto en
 * application.yml con {@code ${DIGSA_*:defecto}}; fuera de rango la aplicación no arranca.
 *
 * @param recalcularAlAplicar recalcula cohortes y corre el motor de alertas al quedar «aplicado» cada cargue (en segundo plano, después de
 *                            confirmar: el cargue no espera ni falla por esto). Las pruebas que no son de cohortes lo apagan.
 * @param tiempoMaximoMinutos statement_timeout de cada sentencia del recálculo (el recorrido de 2 M filas tarda segundos)
 * @param memoriaTrabajoMb    work_mem del recálculo
 * @param porPagina           alertas por página en la consola
 * @param cortesConMembresia  cortes de cada versión de cohorte que conservan la membresía (pacientes distintos de cualquier periodo y
 *                            composición reproducibles); los anteriores conservan solo la serie mensual
 * @param mesesGracia         meses seguidos sin membresía que NO cortan la permanencia en una cohorte (un crónico que retira cada dos o tres
 *                            meses no sale y vuelve a «ingresar»; revisión A4 2026-10-24)
 * @param supresionSensibles  umbral k de celdas pequeñas para las cohortes sensibles a nivel ESM aunque el general (tableros) esté en 0
 *                            (recomendación de la duda de supresión; 0 la apaga)
 * @param cohortesSensibles   códigos de las cohortes sensibles (VIH, huérfanas)
 * @param mesesAviso          el aviso por correo de severidad alta sale solo para alertas de los últimos N meses del corte; las de periodos
 *                            anteriores (primer cargue histórico) van en un resumen por ejecución
 */
@ConfigurationProperties(prefix = "digsa.analitica.riesgo")
public record PropiedadesRiesgo(Boolean recalcularAlAplicar, Integer tiempoMaximoMinutos, Integer memoriaTrabajoMb, Integer porPagina,
                                Integer cortesConMembresia, Integer mesesGracia, Integer supresionSensibles, List<String> cohortesSensibles,
                                Integer mesesAviso) {

    public PropiedadesRiesgo {
        rango("tiempo-maximo-minutos", tiempoMaximoMinutos, 1, 240);
        rango("memoria-trabajo-mb", memoriaTrabajoMb, 4, 4096);
        rango("por-pagina", porPagina, 10, 500);
        rango("cortes-con-membresia", cortesConMembresia, 1, 120);
        rango("meses-gracia", mesesGracia, 0, 6);
        rango("supresion-sensibles", supresionSensibles, 0, 100);
        rango("meses-aviso", mesesAviso, 1, 24);
    }

    private static void rango(String nombre, Integer v, int min, int max) {
        if (v != null && (v < min || v > max)) {
            throw new IllegalArgumentException("digsa.analitica.riesgo." + nombre + " = " + v + " está fuera de rango (" + min + " a " + max + ")");
        }
    }

    public boolean recalcular() {
        return recalcularAlAplicar == null || recalcularAlAplicar;
    }

    public int minutos() {
        return tiempoMaximoMinutos == null ? 30 : tiempoMaximoMinutos;
    }

    public int memoria() {
        return memoriaTrabajoMb == null ? 256 : memoriaTrabajoMb;
    }

    public int pagina() {
        return porPagina == null ? 50 : porPagina;
    }

    public int cortesMembresia() {
        return cortesConMembresia == null ? 6 : cortesConMembresia;
    }

    public int gracia() {
        return mesesGracia == null ? 2 : mesesGracia;
    }

    public int kSensibles() {
        return supresionSensibles == null ? 5 : supresionSensibles;
    }

    public List<String> sensibles() {
        return cohortesSensibles == null ? List.of("vih", "huerfanas") : cohortesSensibles.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public int avisoMeses() {
        return mesesAviso == null ? 3 : mesesAviso;
    }
}
