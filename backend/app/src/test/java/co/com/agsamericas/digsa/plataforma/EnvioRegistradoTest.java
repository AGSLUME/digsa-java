package co.com.agsamericas.digsa.plataforma;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Envío directo registrado (duda CO-2): la bitácora de envíos nunca tumba un envío. */
class EnvioRegistradoTest {

    /** Correo de prueba que guarda lo que envía. */
    static final class CorreoEnMemoria implements Correo {
        final List<Mensaje> enviados = new ArrayList<>();

        @Override
        public Resultado enviar(Mensaje m) {
            enviados.add(m);
            return Resultado.exito(m.para());
        }

        @Override
        public boolean real() {
            return false;
        }
    }

    @Test
    @DisplayName("si la base no deja registrar el envío, el correo sale igual y el resultado es el del canal")
    void registroCaidoNoTumbaElEnvio() {
        // Puerto 1: nadie escucha; obtener la conexión falla en el acto (la transacción propia no se puede abrir)
        DriverManagerDataSource sinBase = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/nada?connectTimeout=1", "x", "x");
        CorreoEnMemoria correo = new CorreoEnMemoria();
        EnvioRegistrado envios = new EnvioRegistrado(JdbcClient.create(sinBase), correo, Clock.systemUTC(), new ObjectMapper(),
                new DataSourceTransactionManager(sinBase));

        Correo.Resultado r = envios.enviar(EnvioRegistrado.Registro.de("mfa_codigo", "usuario:1").secreto("<p>••••••</p>"),
                Correo.Mensaje.simple("persona@example.com", "Código de verificación · Suite DIGSA", "<p>123456</p>"));

        assertThat(r.ok()).isTrue();
        assertThat(correo.enviados).hasSize(1);
        assertThat(correo.enviados.get(0).html()).isEqualTo("<p>123456</p>");
    }
}
