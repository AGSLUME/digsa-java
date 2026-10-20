package co.com.agsamericas.digsa;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;

/**
 * Arranque local sin Docker ni PostgreSQL instalado: levanta un PostgreSQL 16 embebido con datos persistentes en
 * {@code .local/pgdata} (raíz del repositorio) y la aplicación en http://localhost:8080 con correo a archivo y sin captcha.
 * Solo para desarrollo y para las pruebas de Playwright; no va en el paquete de producción (está en las fuentes de prueba).
 *
 * <pre>cd backend && ./mvnw -pl app spring-boot:test-run -Dspring-boot.run.main-class=co.com.agsamericas.digsa.DigsaLocal</pre>
 */
public final class DigsaLocal {

    private DigsaLocal() {
    }

    public static void main(String[] args) throws IOException {
        Path raiz = Path.of(System.getProperty("digsa.local.raiz", "../..")).toAbsolutePath().normalize();
        Path datos = raiz.resolve(".local/pgdata");
        Files.createDirectories(datos);
        int puerto = Integer.parseInt(System.getProperty("digsa.local.pgpuerto", "54329"));
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setDataDirectory(datos)
                .setCleanDataDirectory(false)
                .setPort(puerto)
                .setServerConfig("timezone", "America/Bogota")
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (IOException e) {
                // al cerrar el proceso
            }
        }));
        List<String> a = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:postgresql://localhost:" + puerto + "/postgres",
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=",
                "--digsa.entorno=local",
                "--digsa.correo.modo=archivo",
                "--digsa.correo.carpeta=" + raiz.resolve(".local/correos"),
                "--digsa.archivos.carpeta=" + raiz.resolve(".local/archivos"),
                "--digsa.url=http://localhost:8080/",
                "--digsa.reportes.dominios-permitidos=example.com",
                // La SPA compilada se sirve desde el mismo origen (CSP y cookies reales en las pruebas de Playwright)
                // frontend/dist primero: es la compilación más reciente (target/classes/static puede tener una vieja del último package)
                "--spring.web.resources.static-locations=file:" + raiz.resolve("frontend/dist").toString().replace('\\', '/') + "/,classpath:/static/"));
        a.addAll(List.of(args));
        var ctx = SpringApplication.run(DigsaApplication.class, a.toArray(String[]::new));
        asegurarAdministradorDePruebas(ctx);
    }

    /**
     * Administrador de las pruebas de Playwright, con contraseña conocida (solo local): -Ddigsa.local.admin=usuario:Clave.
     * Si no se pide, no se crea nada.
     */
    private static void asegurarAdministradorDePruebas(org.springframework.context.ConfigurableApplicationContext ctx) {
        String dato = System.getProperty("digsa.local.admin");
        if (dato == null || !dato.contains(":")) {
            return;
        }
        String usuario = dato.substring(0, dato.indexOf(':'));
        String clave = dato.substring(dato.indexOf(':') + 1);
        var jdbc = ctx.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
        var codificador = ctx.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
        jdbc.sql("""
                INSERT INTO usuarios (nombre, usuario, correo, clave_hash, clave_definida, clave_vence, documento)
                VALUES ('Administrador de pruebas', :u, :c, :h, true, now() + interval '30 days', '0000')
                ON CONFLICT (usuario) DO UPDATE SET clave_hash = :h, clave_definida = true, clave_vence = now() + interval '30 days',
                  activo = true, intentos = 0, bloqueado_hasta = NULL, debe_cambiar_clave = false""")
                .param("u", usuario).param("c", usuario + "@example.com").param("h", codificador.encode(clave)).update();
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) SELECT id, 1 FROM usuarios WHERE usuario = :u ON CONFLICT DO NOTHING")
                .param("u", usuario).update();
    }
}
