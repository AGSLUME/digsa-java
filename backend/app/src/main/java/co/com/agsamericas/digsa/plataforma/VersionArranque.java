package co.com.agsamericas.digsa.plataforma;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Deja en el log de arranque la versión y el commit de la imagen (los entrega el pipeline): «Suite DIGSA 1.2.0 (abc1234)». */
@Component
class VersionArranque {

    private static final Logger LOG = LoggerFactory.getLogger(VersionArranque.class);

    private final String version;
    private final String commit;

    VersionArranque(@Value("${DIGSA_VERSION:local}") String version, @Value("${DIGSA_COMMIT:desconocido}") String commit) {
        this.version = version;
        this.commit = commit;
    }

    @EventListener(ApplicationReadyEvent.class)
    void anunciar() {
        LOG.info("Suite DIGSA {} ({})", version, commit.length() > 7 ? commit.substring(0, 7) : commit);
    }
}
