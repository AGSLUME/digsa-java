package co.com.agsamericas.digsa.analitica.ingesta;

import jakarta.annotation.PostConstruct;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.stereotype.Component;

/**
 * Límites de descompresión de POI (estáticos de toda la JVM) fijados UNA sola vez al arrancar, no en cada lectura, para que dos cargas
 * xlsx simultáneas no se cambien los límites entre sí. Una hoja de 1 048 576 filas pasa de los 200 MB de entrada que usa la carga de
 * maestros; la proporción mínima de compresión sigue protegiendo contra una bomba de zip.
 * <p>
 * Coordinación: maestros/carga/LectorPlantillas todavía fija sus propios límites en cada lectura (solo corre por línea de comandos);
 * al integrar en develop se unifican aquí o en un componente de plataforma.
 */
@Component
class LimitesPoi {

    static final double PROPORCION_MINIMA = 0.005;
    static final long ENTRADA_MAXIMA = 0xFFFFFFFFL;

    @PostConstruct
    void fijar() {
        ZipSecureFile.setMinInflateRatio(PROPORCION_MINIMA);
        ZipSecureFile.setMaxEntrySize(ENTRADA_MAXIMA);
    }
}
