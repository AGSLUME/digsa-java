package co.com.agsamericas.digsa.soporte;

import co.com.agsamericas.digsa.identidad.RepositorioUsuarios;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.CorreoArchivo;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base de las pruebas de integración y de API: aplicación completa en un puerto aleatorio, PostgreSQL 16 real con las migraciones
 * de Flyway y el correo de desarrollo en memoria. Cada prueba crea sus propios usuarios con nombres únicos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@Import(PruebaIntegracion.Reloj.class)
public abstract class PruebaIntegracion {

    public static final String CLAVE = "Digsa.Prueba.2026";
    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @TestConfiguration
    static class Reloj {
        @Bean
        @Primary
        RelojMovible relojPrueba() {
            return new RelojMovible(Fechas.BOGOTA);
        }
    }

    @LocalServerPort
    protected int puerto;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected RepositorioUsuarios usuarios;
    @Autowired
    protected PasswordEncoder codificador;
    @Autowired
    protected RelojMovible reloj;
    @Autowired
    protected Correo correo;

    @AfterEach
    void relojAlDia() {
        reloj.reiniciar();
    }

    protected Navegador navegador() {
        return new Navegador(puerto);
    }

    protected static String unico(String prefijo) {
        return prefijo + System.nanoTime() % 1_000_000 + "-" + SECUENCIA.incrementAndGet();
    }

    /** Usuario listo para ingresar (contraseña definida y vigente). Devuelve su nombre de usuario. */
    protected String crearUsuario(String prefijo, Perfil... perfiles) {
        String u = unico(prefijo).toLowerCase().replaceAll("[^a-z0-9._-]", "");
        long id = usuarios.crear("Persona " + u, "1000" + SECUENCIA.get(), null, u, u + "@example.com", codificador.encode(CLAVE),
                null, null, null, Instant.now());
        usuarios.reemplazarPerfiles(id, List.of(perfiles));
        jdbc.sql("UPDATE usuarios SET clave_definida = true, clave_vence = :v WHERE id = :id")
                .param("v", Instant.now().plusSeconds(30L * 86400).atOffset(ZoneOffset.UTC)).param("id", id).update();
        return u;
    }

    protected long idDe(String usuario) {
        return usuarios.porUsuario(usuario).orElseThrow().id();
    }

    protected List<Correo.Mensaje> correos() {
        return ((CorreoArchivo) correo).ultimos();
    }

    /**
     * Espera (sondeo cada 20 ms, máximo 5 s) un correo que cumpla la condición: los avisos de la bandeja de salida salen en otro hilo
     * unos milisegundos después de confirmar la transacción.
     */
    protected Correo.Mensaje esperarCorreo(java.util.function.Predicate<Correo.Mensaje> condicion) {
        long limite = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (true) {
            java.util.Optional<Correo.Mensaje> m = correos().reversed().stream().filter(condicion).findFirst();
            if (m.isPresent()) {
                return m.get();
            }
            if (System.nanoTime() > limite) {
                throw new AssertionError("No llegó el correo esperado en 5 s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** Espera (sondeo cada 20 ms, máximo 5 s) a que se cumpla la condición; p. ej. el estado de una fila de la bandeja. */
    protected void esperar(java.util.function.BooleanSupplier condicion, String que) {
        long limite = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!condicion.getAsBoolean()) {
            if (System.nanoTime() > limite) {
                throw new AssertionError("No se cumplió en 5 s: " + que);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** Espera el correo a la dirección cuyo asunto empieza por el texto (aviso de la bandeja de salida). */
    protected Correo.Mensaje esperarCorreoA(String direccion, String inicioAsunto) {
        return esperarCorreo(m -> m.para().contains(direccion) && m.asunto().startsWith(inicioAsunto));
    }

    protected Correo.Mensaje ultimoCorreoA(String direccion) {
        return correos().reversed().stream().filter(m -> m.para().contains(direccion)).findFirst()
                .orElseThrow(() -> new AssertionError("No hay correo para " + direccion));
    }

    /** Filas de la bitácora de un usuario, de la más reciente a la más antigua: "operacion|tipo|descripcion". */
    protected List<String> bitacoraDe(long usuarioId) {
        return jdbc.sql("SELECT operacion || '|' || tipo || '|' || coalesce(descripcion, '') FROM log_transacciones WHERE usuario_id = :u ORDER BY fecha DESC, id DESC")
                .param("u", usuarioId).query(String.class).list();
    }
}
