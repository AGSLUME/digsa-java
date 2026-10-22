package co.com.agsamericas.digsa.plataforma;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SMS de desarrollo: el mensaje queda en {@code sms/} del almacén con el teléfono y el texto, y NO sale. Solo se permite en el entorno
 * local (lo imponen la configuración y la validación de arranque). Las pruebas leen lo último enviado con {@link #ultimos()}.
 */
public class SmsArchivo implements Sms {

    public record Enviado(String telefono, String texto) {
    }

    private static final DateTimeFormatter NOMBRE = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final AtomicLong SECUENCIA = new AtomicLong();

    private final Almacen almacen;
    private final Clock reloj;
    private final List<Enviado> enviados = new ArrayList<>();

    public SmsArchivo(Almacen almacen, Clock reloj) {
        this.almacen = almacen;
        this.reloj = reloj;
    }

    @Override
    public synchronized Resultado enviar(String telefono, String texto) {
        String ruta = "sms/" + LocalDateTime.now(reloj).format(NOMBRE) + "_" + SECUENCIA.incrementAndGet() + "_" + System.nanoTime() % 100_000 + ".txt";
        try {
            almacen.guardar(ruta, ("Para: " + telefono + "\n\n" + texto + "\n").getBytes(StandardCharsets.UTF_8));
            enviados.add(new Enviado(telefono, texto));
            return Resultado.exito();
        } catch (IOException | RuntimeException e) {
            return Resultado.falla("No se pudo guardar el SMS de desarrollo.");
        }
    }

    @Override
    public boolean real() {
        return false;
    }

    public synchronized List<Enviado> ultimos() {
        return List.copyOf(enviados);
    }
}
