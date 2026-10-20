package co.com.agsamericas.digsa.plataforma;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Correo de desarrollo: el mensaje queda en un archivo .html (y sus adjuntos al lado) y NO sale. Solo se permite en el entorno
 * local (lo impone la configuración). Las pruebas leen lo último enviado con {@link #ultimos()}.
 */
public class CorreoArchivo implements Correo {

    private static final DateTimeFormatter NOMBRE = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final AtomicLong SECUENCIA = new AtomicLong();

    private final Path carpeta;
    private final Clock reloj;
    private final List<Mensaje> enviados = new ArrayList<>();

    public CorreoArchivo(Path carpeta, Clock reloj) {
        this.carpeta = carpeta;
        this.reloj = reloj;
    }

    /** Dominio reservado (RFC 2606) que el correo de desarrollo rechaza, para probar los caminos de correo fallido. */
    public static final String DOMINIO_RECHAZO = "@rechazo.invalid";

    @Override
    public synchronized Resultado enviar(Mensaje m) {
        List<String> todos = new ArrayList<>(m.para());
        todos.addAll(m.copia());
        List<String> rechazados = todos.stream().filter(c -> c.toLowerCase().endsWith(DOMINIO_RECHAZO)).toList();
        if (!rechazados.isEmpty()) {
            List<String> entregados = todos.stream().filter(c -> !rechazados.contains(c)).toList();
            return entregados.isEmpty() ? Resultado.falla(todos, "El servidor de correo rechazó el envío.")
                    : new Resultado(true, entregados, rechazados, "Algunos destinatarios rechazaron el correo.");
        }
        try {
            Files.createDirectories(carpeta);
            String base = LocalDateTime.now(reloj).format(NOMBRE) + "_" + SECUENCIA.incrementAndGet();
            String cabecera = "<!-- Para: " + String.join(", ", m.para()) + " | Copia: " + String.join(", ", m.copia())
                    + " | Asunto: " + m.asunto() + " -->\n";
            Files.writeString(carpeta.resolve(base + ".html"), cabecera + m.html(), StandardCharsets.UTF_8);
            int i = 0;
            for (Adjunto a : m.adjuntos()) {
                String limpio = a.nombre().replaceAll("[^A-Za-z0-9._-]", "_");
                Files.write(carpeta.resolve(base + "_adjunto" + (++i) + "_" + limpio), a.contenido());
            }
            enviados.add(m);
            return Resultado.exito(todos);
        } catch (IOException e) {
            return Resultado.falla(todos, "No se pudo guardar el correo de desarrollo.");
        }
    }

    @Override
    public boolean real() {
        return false;
    }

    public synchronized List<Mensaje> ultimos() {
        return List.copyOf(enviados);
    }
}
