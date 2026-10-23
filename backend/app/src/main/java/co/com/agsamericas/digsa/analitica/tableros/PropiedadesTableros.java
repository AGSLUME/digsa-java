package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de los tableros de analítica (prefijo {@code digsa.analitica.tableros}; clase propia del módulo). Los valores por defecto
 * están en application.yml con {@code ${DIGSA_*:defecto}}. Un valor fuera de rango impide el arranque (revisión A3 2026-10-23: antes se
 * reemplazaba en silencio y el umbral mostrado podía no ser el configurado).
 *
 * @param tiempoMaximoSegundos         presupuesto de UNA petición de tablero, rastreo u opciones (170 s: bajo los 3 minutos de A5 2.7;
 *                                     la meta interna son segundos)
 * @param tiempoMaximoDescargaSegundos presupuesto de una descarga (tablero o CSV de registros de origen: 570 s, bajo los 10 min de 2.7)
 * @param percentilAltoCosto           percentil del gasto por paciente en 12 meses móviles que define el alto costo (99)
 * @param poliformuladoMinimo          principios activos distintos en el mes desde los que un paciente es poliformulado (5)
 * @param horasRezago                  horas desde el último día con dispensaciones tras las que se advierte «Datos con más de 48 h» (48)
 * @param maxRegistrosTraza            registros de origen que se descargan en CSV desde la trazabilidad; más responde 422 (nunca un archivo
 *                                     truncado)
 * @param registrosPorPagina           registros de origen por página en la trazabilidad
 * @param supresionMinima              supresión de celdas pequeñas: con k &gt; 0, toda celda con menos de k pacientes distintos oculta sus
 *                                     pacientes y promedios por paciente («&lt;k»), y la distribución por paciente oculta mínimo y máximo;
 *                                     0 = desactivado (por defecto; las sumas siempre cuadran con el insumo, 14.5). Lo decide la DIGSA.
 * @param memoriaTrabajoMb             work_mem de las transacciones de analítica (por nodo de hash u orden)
 * @param paralelos                    max_parallel_workers_per_gather de las transacciones de analítica (0 = sin paralelismo)
 * @param cacheResultados              resultados de tableros en caché (LRU por instancia; 0 = sin caché). Mismo alcance, consulta y corte
 *                                     dan el mismo resultado (14.8), así que no hay que invalidar
 * @param precalentar                  al aplicar un cargue, calcula en segundo plano los tableros por defecto (nacional y por Fuerza) del
 *                                     nuevo corte, para que la primera apertura no espere
 */
@ConfigurationProperties(prefix = "digsa.analitica.tableros")
public record PropiedadesTableros(Integer tiempoMaximoSegundos, Integer tiempoMaximoDescargaSegundos, Integer percentilAltoCosto,
                                  Integer poliformuladoMinimo, Integer horasRezago, Integer maxRegistrosTraza, Integer registrosPorPagina,
                                  Integer supresionMinima, Integer memoriaTrabajoMb, Integer paralelos, Integer cacheResultados,
                                  Boolean precalentar) {

    public PropiedadesTableros {
        rango("tiempo-maximo-segundos", tiempoMaximoSegundos, 5, 600);
        rango("tiempo-maximo-descarga-segundos", tiempoMaximoDescargaSegundos, 5, 3600);
        rango("percentil-alto-costo", percentilAltoCosto, 50, 99);
        rango("poliformulado-minimo", poliformuladoMinimo, 2, 100);
        rango("horas-rezago", horasRezago, 1, 24 * 365);
        rango("max-registros-traza", maxRegistrosTraza, 1, 5_000_000);
        rango("registros-por-pagina", registrosPorPagina, 10, 500);
        rango("supresion-minima", supresionMinima, 0, 1000);
        rango("memoria-trabajo-mb", memoriaTrabajoMb, 4, 2048);
        rango("paralelos", paralelos, 0, 16);
        rango("cache-resultados", cacheResultados, 0, 10_000);
    }

    private static void rango(String nombre, Integer v, int min, int max) {
        if (v != null && (v < min || v > max)) {
            throw new IllegalArgumentException("digsa.analitica.tableros." + nombre + " = " + v + " está fuera de rango (" + min + " a " + max + ")");
        }
    }

    public int tiempoMaximo() {
        return tiempoMaximoSegundos == null ? 170 : tiempoMaximoSegundos;
    }

    public int tiempoMaximoDescarga() {
        return tiempoMaximoDescargaSegundos == null ? 570 : tiempoMaximoDescargaSegundos;
    }

    public int percentil() {
        return percentilAltoCosto == null ? AnaliticaConsulta.PERCENTIL_ALTO_COSTO : percentilAltoCosto;
    }

    public int poliformulado() {
        return poliformuladoMinimo == null ? AnaliticaConsulta.POLIFORMULADO_MINIMO : poliformuladoMinimo;
    }

    public int rezago() {
        return horasRezago == null ? 48 : horasRezago;
    }

    public int maxTraza() {
        return maxRegistrosTraza == null ? 100_000 : maxRegistrosTraza;
    }

    public int porPagina() {
        return registrosPorPagina == null ? 50 : registrosPorPagina;
    }

    /** Umbral de supresión de celdas pequeñas (0 = desactivado). */
    public int supresion() {
        return supresionMinima == null ? 0 : supresionMinima;
    }

    public int memoriaTrabajo() {
        return memoriaTrabajoMb == null ? 128 : memoriaTrabajoMb;
    }

    public int trabajadoresParalelos() {
        return paralelos == null ? 2 : paralelos;
    }

    public int tamanoCache() {
        return cacheResultados == null ? 200 : cacheResultados;
    }

    public boolean precalentarTableros() {
        return precalentar == null || precalentar;
    }
}
