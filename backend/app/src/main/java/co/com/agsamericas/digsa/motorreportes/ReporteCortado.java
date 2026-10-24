package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import org.springframework.http.HttpStatus;

/**
 * El reporte llegó al tope técnico (una consulta colgada, 60 minutos por defecto) y se cortó: 503 con un mensaje para acotarlo, y la
 * alerta «reporte lento». Los plazos de 2.7 (3 y 10 minutos) no cortan: solo avisan.
 */
final class ReporteCortado extends ErrorNegocio {

    private final long plazoMs;

    ReporteCortado(long plazoMs, boolean archivo) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "El reporte superó el tope técnico de " + Math.max(1, plazoMs / 60_000) + " minutos "
                + (archivo ? "para generarse" : "para verse en pantalla") + " (la consulta no respondió); acote el periodo o los filtros"
                + (archivo ? " e intente de nuevo." : ", o descárguelo: el archivo se genera en segundo plano."));
        this.plazoMs = plazoMs;
    }

    long plazoMs() {
        return plazoMs;
    }
}
