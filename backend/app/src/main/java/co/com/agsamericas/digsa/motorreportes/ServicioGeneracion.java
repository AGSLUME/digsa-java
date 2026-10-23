package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Exportador;
import co.com.agsamericas.digsa.plataforma.ExportadorFlujo;
import co.com.agsamericas.digsa.plataforma.ReporteEncabezado;
import co.com.agsamericas.digsa.plataforma.ReporteEncabezado.Linea;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Filtro;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Generación de los reportes con integridad (Anexo 5, 2.9 y 2.10): cada generación queda en {@code reportes_generados} con su código
 * de verificación y la huella de los datos; el archivo se arma en flujo (cursor del proveedor → temporal → archivo en el almacén) con
 * el encabezado completo y su huella, y queda en el repositorio del día del usuario hasta las 23:59:59 de Bogotá (2.7).
 */
@Service
public class ServicioGeneracion {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioGeneracion.class);
    static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CARPETA = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    static final Map<String, String> TIPOS = Map.of("xlsx", Exportador.TIPO_XLSX, "csv", Exportador.TIPO_CSV, "pdf", Exportador.TIPO_PDF, "txt",
            Exportador.TIPO_TXT);

    /** Quién genera: el id para el registro y el texto del encabezado («Nombre (usuario)»). */
    public record Quien(long id, String texto) {
    }

    /** El reporte en pantalla con su código y su huella (null si en pantalla no caben todas las filas). */
    public record Vista(MotorReportes.Resultado resultado, String codigo, String huella) {
    }

    /** El archivo generado, ya guardado en el almacén. */
    public record Archivo(long id, String codigo, String nombre, String tipo, String ruta, long tamano, long filas, String huellaDatos,
                          String huellaArchivo, long ms) {
    }

    private final MotorReportes motor;
    private final RegistroGenerados registro;
    private final ExportadorFlujo exportador;
    private final Almacen almacen;
    private final Clock reloj;
    private final ConfiguracionReportes cfg;

    public ServicioGeneracion(MotorReportes motor, RegistroGenerados registro, ExportadorFlujo exportador, Almacen almacen, Clock reloj,
                              ConfiguracionReportes cfg) {
        this.cfg = cfg;
        this.motor = motor;
        this.registro = registro;
        this.exportador = exportador;
        this.almacen = almacen;
        this.reloj = reloj;
    }

    private OffsetDateTime ahora() {
        return OffsetDateTime.now(reloj);
    }

    static RegistroGenerados.Nuevo nuevo(MotorReportes.Solicitud s, Quien q, String formato) {
        DefinicionReporte d = s.definicion();
        return new RegistroGenerados.Nuevo(d.id(), d.version(), d.titulo(), q.id(), q.texto(), HuellaDatos.alcance(s.consulta().alcance()),
                parametros(s), MotorReportes.columnas(s), formato);
    }

    /** Parámetros resueltos (fechas explícitas, sin presets): con ellos se regenera el reporte al verificarlo. */
    static Map<String, Object> parametros(MotorReportes.Solicitud s) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (s.consulta().desde() != null) {
            p.put("desde", s.consulta().desde().toString());
            p.put("hasta", s.consulta().hasta().toString());
        }
        if (s.consulta().agrupacion() != null) {
            p.put("agrupacion", s.consulta().agrupacion().clave());
        }
        p.put("filtros", s.consulta().filtros());
        return p;
    }

    /** El reporte en pantalla, registrado con su código; la huella solo si están todas las filas. */
    public Vista ver(MotorReportes.Solicitud s, Quien q) {
        MotorReportes.Resultado r = motor.generar(s, q.texto(), false);
        String huella = null;
        if (!r.truncado()) {
            HuellaDatos h = new HuellaDatos(s.definicion(), s.consulta());
            r.filas().forEach(h::fila);
            huella = h.hex();
        }
        OffsetDateTime t = ahora();
        Map.Entry<Long, String> reg = registro.insertar(nuevo(s, q, null), "vista", t, null);
        registro.vista(reg.getKey(), r.filas().size(), r.truncado(), huella, r.ms(), t);
        return new Vista(r, reg.getValue(), huella);
    }

    /** Fin del día de Bogotá en que se pidió: hasta entonces el archivo está en las descargas del día. */
    static OffsetDateTime finDelDia(OffsetDateTime t) {
        LocalDate dia = t.atZoneSameInstant(Fechas.BOGOTA).toLocalDate();
        return dia.plusDays(1).atStartOfDay(Fechas.BOGOTA).minusNanos(1_000_000).toOffsetDateTime();
    }

    /** Tope de filas del formato: el PDF admite menos (un PDF de cientos de miles de filas no se puede leer). */
    int topeFormato(String formato) {
        return "pdf".equals(formato) ? cfg.maxFilasPdf() : 0;
    }

    /**
     * Genera el archivo y lo guarda en el almacén. Si algo falla, el archivo a medias se descarta, el registro queda «fallida» y el
     * error sigue hacia el usuario: una validación (el tope de filas) con su mensaje; cualquier otro, con el código para buscarlo.
     */
    public Archivo exportar(MotorReportes.Solicitud s, String formato, Quien q) {
        long inicio = System.nanoTime();
        OffsetDateTime creado = ahora();
        Map.Entry<Long, String> reg = registro.insertar(nuevo(s, q, formato), "generando", creado, finDelDia(creado));
        long id = reg.getKey();
        String codigo = reg.getValue();
        String ruta = "reportes/" + creado.atZoneSameInstant(Fechas.BOGOTA).format(CARPETA) + "/" + codigo + "." + formato;
        boolean escrito = false;
        try (CintaFilas cinta = new CintaFilas()) {
            HuellaDatos h = new HuellaDatos(s.definicion(), s.consulta());
            int tope = topeFormato(formato);
            long filas = motor.volcar(s, tope, tope == 0 ? null : "El PDF admite hasta " + tope + " filas; descárguelo en Excel o CSV, o acote los filtros.",
                    f -> {
                        h.fila(f);
                        cinta.escribir(f);
                    });
            cinta.terminar();
            String huellaDatos = h.hex();
            long msConsulta = (System.nanoTime() - inicio) / 1_000_000;
            ReporteEncabezado e = encabezado(s, q.texto(), creado, filas, codigo, huellaDatos);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long[] tamano = {0};
            try (OutputStream destino = new BufferedOutputStream(almacen.escribir(ruta), 64 * 1024)) {
                escrito = true;
                OutputStream contado = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        destino.write(b);
                        tamano[0]++;
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        destino.write(b, off, len);
                        tamano[0] += len;
                    }
                };
                DigestOutputStream salida = new DigestOutputStream(contado, sha);
                exportador.escribir(formato, e, MotorReportes.columnas(s), cinta, salida);
                salida.flush();
            }
            String huellaArchivo = HexFormat.of().formatHex(sha.digest());
            long total = (System.nanoTime() - inicio) / 1_000_000;
            OffsetDateTime fin = ahora();
            registro.lista(id, ruta, tamano[0], filas, huellaDatos, huellaArchivo, msConsulta, total - msConsulta, fin, finDelDia(fin));
            String nombre = "reporte_" + s.definicion().id() + "_" + creado.atZoneSameInstant(Fechas.BOGOTA).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
                    + "_" + codigo.substring(codigo.length() - 6) + "." + formato;
            return new Archivo(id, codigo, nombre, TIPOS.get(formato), ruta, tamano[0], filas, huellaDatos, huellaArchivo, total);
        } catch (ErrorNegocio e) {
            // Una validación (p. ej. el tope de filas) conserva su mensaje para el usuario
            fallo(id, ruta, escrito, recortar(e.getMessage()), e);
            throw e;
        } catch (Exception e) {
            LOG.error("[reportes] Falló la generación de {} ({}) en {}", s.definicion().id(), codigo, formato, e);
            fallo(id, ruta, escrito, "No se pudo generar el archivo.", e);
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo generar el reporte. Código de la solicitud: " + codigo + ".");
        }
    }

    /** Descarta el archivo a medias y marca el registro «fallida»; si eso también falla, queda en el log sin tapar el error original. */
    private void fallo(long id, String ruta, boolean escrito, String mensaje, Exception original) {
        if (escrito) {
            almacen.descartar(ruta);
        }
        try {
            registro.fallida(id, mensaje, ahora());
        } catch (RuntimeException x) {
            original.addSuppressed(x);
            LOG.error("[reportes] No se pudo marcar como fallida la generación {}", id, x);
        }
    }

    private static String recortar(String s) {
        return s == null ? "" : s.length() <= 200 ? s : s.substring(0, 200);
    }

    /**
     * Encabezado de 2.9 del archivo: periodo (y agrupación), fecha y hora de generación, usuario, establecimiento, los demás filtros,
     * fuente, registros, código de verificación y huella de los datos. La página la pone cada formato.
     */
    static ReporteEncabezado encabezado(MotorReportes.Solicitud s, String usuario, OffsetDateTime generado, long registros, String codigo,
                                        String huellaDatos) {
        DefinicionReporte d = s.definicion();
        List<Linea> l = new ArrayList<>();
        if (!s.periodo().isEmpty()) {
            l.add(new Linea("Periodo", s.periodo()));
        }
        if (s.consulta().agrupacion() != null) {
            l.add(new Linea("Agrupado por", s.consulta().agrupacion().nombre().toLowerCase(java.util.Locale.ROOT)));
        }
        l.add(new Linea("Generado", generado.atZoneSameInstant(Fechas.BOGOTA).format(FECHA_HORA)));
        l.add(new Linea("Usuario", usuario));
        l.add(new Linea("Establecimiento", s.establecimiento()));
        String establecimiento = d.filtros().stream().filter(Filtro::establecimiento).map(Filtro::etiqueta).findFirst().orElse(null);
        s.filtrosEncabezado().forEach((k, v) -> {
            if (!k.equals("Periodo") && !k.equals("Agrupado por") && !k.equals(establecimiento)) {
                l.add(new Linea("Filtro: " + k, v));
            }
        });
        l.add(new Linea("Fuente", d.fuente()));
        l.add(new Linea("Registros", String.valueOf(registros)));
        l.add(new Linea("Código de verificación", codigo));
        l.add(new Linea("Huella de los datos (SHA-256)", huellaDatos));
        return new ReporteEncabezado(d.titulo(), l, codigo);
    }
}
