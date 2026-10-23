package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Presupuesto de tiempo de UNA petición de analítica (revisión A3 2026-10-23: el tiempo máximo es por petición, no por sentencia). Antes
 * de cada sentencia fija {@code SET LOCAL statement_timeout} con los milisegundos que quedan; si ya no queda nada, corta con el 422 «La
 * consulta tardó demasiado». Así la suma de las sentencias de un tablero (corte, filtros, agrupaciones, comparación, percentiles) nunca
 * pasa del presupuesto sin detectarse. PostgreSQL 16 no tiene {@code transaction_timeout}.
 */
final class Plazo {

    private final JdbcClient base;
    private final long inicio;
    private final long fin;
    private final long presupuestoMs;

    Plazo(JdbcClient base, long presupuestoMs) {
        this.base = base;
        this.presupuestoMs = presupuestoMs;
        this.inicio = System.nanoTime();
        this.fin = inicio + presupuestoMs * 1_000_000L;
    }

    /** Cliente que fija el tiempo restante antes de cada sentencia (dentro de la transacción de lectura). */
    JdbcClient cliente() {
        return sql -> {
            fijar();
            return base.sql(sql);
        };
    }

    /** Fija el statement_timeout con lo que queda (para las sentencias que no pasan por {@link #cliente()}, como el CSV en flujo). */
    void fijar() {
        long restante = restanteMs();
        if (restante <= 0) {
            throw agotado();
        }
        base.sql("SET LOCAL statement_timeout = " + restante).update();
    }

    long restanteMs() {
        return (fin - System.nanoTime()) / 1_000_000L;
    }

    long transcurridoMs() {
        return (System.nanoTime() - inicio) / 1_000_000L;
    }

    /** Después de terminar: si la petición completa pasó del presupuesto, tampoco se entrega (el anexo fija el máximo de la petición). */
    void verificar() {
        if (transcurridoMs() > presupuestoMs) {
            throw agotado();
        }
    }

    static ErrorNegocio agotado() {
        return new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, ServicioConsultaAnalitica.TIEMPO_AGOTADO, Map.of("tiempoAgotado", true));
    }
}
