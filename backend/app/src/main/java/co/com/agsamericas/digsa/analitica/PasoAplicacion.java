package co.com.agsamericas.digsa.analitica;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Punto de enganche de la aplicación de un cargue (analitica-tableros: «los agregados se refrescan antes de que el cargue pase a
 * aplicado»). Cada implementación corre dentro de la misma transacción que inserta los hechos y marca el cargue: si lanza, el cargue
 * queda «fallido» y nada se ve. A1 no trae ninguna; A3 agrega la de ana_agregado_mes (V62). Su tiempo cuenta en la etapa «agregados».
 */
public interface PasoAplicacion {

    /**
     * @param jdbc     cliente en la transacción de la aplicación (los hechos del cargue ya están insertados)
     * @param cargueId cargue que se está aplicando
     * @param desde    fecha mínima de los hechos del cargue
     * @param hasta    fecha máxima
     */
    void alAplicar(JdbcClient jdbc, long cargueId, LocalDate desde, LocalDate hasta);
}
