package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.plataforma.ReporteFilas;
import co.com.agsamericas.digsa.plataforma.ReporteTemporales;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filas de un reporte volcadas a un archivo temporal mientras el proveedor las lee (con cursor): así el archivo final se escribe con
 * el encabezado completo (registros y huella de los datos, que solo se conocen al final) sin tener las filas en memoria. Se recorre
 * las veces que haga falta y se borra al cerrar.
 */
final class CintaFilas implements ReporteFilas, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CintaFilas.class);
    private static final byte NULO = 0, TEXTO = 1, LARGO = 2, ENTERO = 3, DECIMAL = 4, DOBLE = 5, FECHA = 6, INSTANTE = 7, LOGICO = 8, FECHA_HORA = 9;

    private final Path archivo;
    private final DataOutputStream salida;
    private long total;
    private boolean cerrada;

    CintaFilas() throws IOException {
        archivo = ReporteTemporales.nuevo(".cinta");
        try {
            salida = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(archivo), 64 * 1024));
        } catch (IOException | RuntimeException e) {
            ReporteTemporales.borrar(archivo);
            throw e;
        }
    }

    void escribir(List<Object> fila) {
        try {
            salida.writeInt(fila.size());
            for (Object v : fila) {
                valor(v);
            }
            total++;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el temporal del reporte", e);
        }
    }

    private void valor(Object v) throws IOException {
        switch (v) {
            case null -> salida.writeByte(NULO);
            case String s -> {
                salida.writeByte(TEXTO);
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                salida.writeInt(b.length);
                salida.write(b);
            }
            case Long l -> {
                salida.writeByte(LARGO);
                salida.writeLong(l);
            }
            case Integer i -> {
                salida.writeByte(ENTERO);
                salida.writeInt(i);
            }
            case BigDecimal d -> {
                salida.writeByte(DECIMAL);
                salida.writeUTF(d.toString());
            }
            case Double d -> {
                salida.writeByte(DOBLE);
                salida.writeDouble(d);
            }
            case LocalDate d -> {
                salida.writeByte(FECHA);
                salida.writeLong(d.toEpochDay());
            }
            case OffsetDateTime o -> instante(o.toInstant());
            case ZonedDateTime z -> instante(z.toInstant());
            case LocalDateTime t -> {
                salida.writeByte(FECHA_HORA);
                salida.writeUTF(t.toString());
            }
            case Boolean b -> {
                salida.writeByte(LOGICO);
                salida.writeBoolean(b);
            }
            case Short s -> {
                salida.writeByte(ENTERO);
                salida.writeInt(s);
            }
            default -> throw new IllegalArgumentException("Tipo de celda no admitido en un reporte: " + v.getClass().getName() + " (ver TiposCelda)");
        }
    }

    private void instante(Instant i) throws IOException {
        salida.writeByte(INSTANTE);
        salida.writeLong(i.getEpochSecond());
        salida.writeInt(i.getNano());
    }

    /** Termina de escribir; después solo se lee. */
    void terminar() throws IOException {
        salida.close();
        cerrada = true;
    }

    @Override
    public long total() {
        return total;
    }

    @Override
    public void recorrer(Consumer<List<Object>> fila) throws IOException {
        if (!cerrada) {
            throw new IllegalStateException("La cinta del reporte no se terminó de escribir");
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(archivo), 64 * 1024))) {
            for (long k = 0; k < total; k++) {
                int n = in.readInt();
                List<Object> f = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    f.add(leer(in));
                }
                fila.accept(f);
            }
        } catch (EOFException e) {
            throw new IOException("El temporal del reporte está incompleto", e);
        }
    }

    private static Object leer(DataInputStream in) throws IOException {
        byte tipo = in.readByte();
        return switch (tipo) {
            case NULO -> null;
            case TEXTO -> {
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                yield new String(b, StandardCharsets.UTF_8);
            }
            case LARGO -> in.readLong();
            case ENTERO -> in.readInt();
            case DECIMAL -> new BigDecimal(in.readUTF());
            case DOBLE -> in.readDouble();
            case FECHA -> LocalDate.ofEpochDay(in.readLong());
            case INSTANTE -> OffsetDateTime.ofInstant(Instant.ofEpochSecond(in.readLong(), in.readInt()), ZoneOffset.UTC);
            case FECHA_HORA -> LocalDateTime.parse(in.readUTF());
            case LOGICO -> in.readBoolean();
            default -> throw new IOException("Tipo de celda desconocido en el temporal del reporte: " + tipo);
        };
    }

    @Override
    public void close() {
        try {
            if (!cerrada) {
                salida.close();
            }
        } catch (IOException e) {
            LOG.warn("[reportes] No se pudo cerrar el temporal {}", archivo, e);
        }
        ReporteTemporales.borrar(archivo); // si no puede, queda en el log con su ruta (datos personales, Ley 1581)
    }
}
