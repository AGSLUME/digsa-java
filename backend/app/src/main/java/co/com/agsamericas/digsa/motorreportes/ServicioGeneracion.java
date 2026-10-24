package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Exportador;
import co.com.agsamericas.digsa.plataforma.ExportadorFlujo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.plataforma.ReporteEncabezado;
import co.com.agsamericas.digsa.plataforma.ReporteEncabezado.Linea;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Filtro;
import jakarta.annotation.PreDestroy;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Generación de los reportes con integridad y tiempos (Anexo 5, 2.7, 2.9 y 2.10). Cada generación queda en {@code reportes_generados}
 * con su código de verificación, la huella de los datos y sus tiempos. El archivo se arma en flujo (cursor del proveedor → temporal →
 * archivo en el almacén) y queda en las descargas del día del usuario hasta las 23:59:59 de Bogotá. La descarga va a una cola acotada:
 * si termina pronto se entrega en la misma petición; si no, sigue en segundo plano y aparece en «Descargas del día». Lo que pasa el
 * 80 % de su plazo o se corta dispara la alerta «reporte lento».
 */
@Service
public class ServicioGeneracion {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioGeneracion.class);
    static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CARPETA = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    static final Map<String, String> TIPOS = Map.of("xlsx", Exportador.TIPO_XLSX, "csv", Exportador.TIPO_CSV, "pdf", Exportador.TIPO_PDF, "txt",
            Exportador.TIPO_TXT);
    /**
     * Solicitudes que pueden esperar turno en la cola de la instancia. Pocas: la espera máxima en cola (cola / concurrencia × tope
     * técnico) acota cuándo una «pendiente» se da por perdida (LimpiezaReportes).
     */
    static final int COLA = 20;
    /** Lo que se espera al apagar a que terminen las generaciones en curso antes de marcarlas «fallida». */
    static final long ESPERA_AL_APAGAR_S = 20;

    /** Quién genera: el id y el usuario para el registro y la bitácora, y el texto del encabezado («Nombre (usuario)»). */
    public record Quien(long id, String usuario, String texto) {
    }

    /** El archivo pasó del tope del adjunto del correo: la generación se corta en ese momento, sin terminar el archivo. */
    static final class AdjuntoGrande extends ErrorNegocio {

        AdjuntoGrande(int maxMb) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "El archivo pasa de " + maxMb + " MB, lo que admite el correo. Descárguelo, elija otro formato "
                    + "(csv pesa menos) o acote el periodo o los filtros.");
        }
    }

    /** El reporte en pantalla con su código y su huella (null si en pantalla no caben todas las filas). */
    public record Vista(MotorReportes.Resultado resultado, String codigo, String huella) {
    }

    /** El archivo generado, ya guardado en el almacén. */
    public record Archivo(long id, String codigo, String nombre, String tipo, String ruta, long tamano, long filas, String huellaDatos,
                          String huellaArchivo, long ms) {
    }

    /** Una descarga pedida: el código y, si ya terminó, el archivo (si no, sigue en segundo plano). */
    public record Solicitada(String codigo, CompletableFuture<Archivo> trabajo) {
    }

    private final MotorReportes motor;
    private final RegistroGenerados registro;
    private final ExportadorFlujo exportador;
    private final Almacen almacen;
    private final Clock reloj;
    private final ConfiguracionReportes cfg;
    private final MedicionReportes medicion;
    private final RegistroBitacora bitacora;
    private final ExecutorService cola;
    /** Las solicitudes que esta instancia aceptó y no han terminado: al apagar se marcan «fallida» y liberan el cupo. */
    private final Set<Long> aceptadas = ConcurrentHashMap.newKeySet();

    public ServicioGeneracion(MotorReportes motor, RegistroGenerados registro, ExportadorFlujo exportador, Almacen almacen, Clock reloj,
                              ConfiguracionReportes cfg, MedicionReportes medicion, RegistroBitacora bitacora) {
        this.motor = motor;
        this.registro = registro;
        this.exportador = exportador;
        this.almacen = almacen;
        this.reloj = reloj;
        this.cfg = cfg;
        this.medicion = medicion;
        this.bitacora = bitacora;
        AtomicInteger n = new AtomicInteger();
        this.cola = new ThreadPoolExecutor(cfg.concurrencia(), cfg.concurrencia(), 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(COLA), r -> {
            Thread t = new Thread(r, "reportes-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Al apagar: interrumpe la cola, espera un poco a lo que está en curso y marca «fallida» lo que quedó en cola o sin terminar, para
     * que el usuario no quede bloqueado por su cupo hasta la limpieza. Si la instancia cae sin apagarse, lo repone la limpieza.
     */
    @PreDestroy
    void detener() {
        cola.shutdownNow();
        try {
            if (!cola.awaitTermination(ESPERA_AL_APAGAR_S, TimeUnit.SECONDS)) {
                LOG.warn("[reportes] Al apagar quedaron generaciones sin terminar: {}", aceptadas);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            int n = registro.interrumpidas(Set.copyOf(aceptadas), ahora());
            if (n > 0) {
                LOG.warn("[reportes] {} generaciones en cola o en curso quedaron «fallida» al apagar", n);
            }
        } catch (RuntimeException e) {
            LOG.error("[reportes] No se pudieron marcar las generaciones interrumpidas al apagar {}; las repone la limpieza", aceptadas, e);
        }
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
        MotorReportes.Resultado r;
        try {
            r = motor.generar(s, q.texto(), false);
        } catch (ReporteCortado c) {
            medicion.revisar(s.definicion(), "en pantalla", c.plazoMs(), c.plazoMs(), true, ControladorReportes.resumen(s, "?", c.plazoMs()));
            throw c;
        }
        String huella = null;
        if (!r.truncado()) {
            HuellaDatos h = new HuellaDatos(s.definicion(), s.consulta());
            r.filas().forEach(h::fila);
            huella = h.hex();
        }
        OffsetDateTime t = ahora();
        Map.Entry<Long, String> reg = registro.insertar(nuevo(s, q, null), "vista", t, null);
        registro.vista(reg.getKey(), r.filas().size(), r.truncado(), huella, r.ms(), t);
        medicion.revisar(s.definicion(), "en pantalla", r.ms(), cfg.plazoVerMs(), false,
                reg.getValue() + " · " + ControladorReportes.resumen(s, String.valueOf(r.filas().size()), r.ms()));
        return new Vista(r, reg.getValue(), huella);
    }

    /** Fin del día de Bogotá en que se pidió: hasta entonces el archivo está en las descargas del día. */
    static OffsetDateTime finDelDia(OffsetDateTime t) {
        LocalDate dia = t.atZoneSameInstant(Fechas.BOGOTA).toLocalDate();
        return dia.plusDays(1).atStartOfDay(Fechas.BOGOTA).minusNanos(1_000_000).toOffsetDateTime();
    }

    /** Inicio del día de Bogotá. */
    static OffsetDateTime inicioDelDia(OffsetDateTime t) {
        return t.atZoneSameInstant(Fechas.BOGOTA).toLocalDate().atStartOfDay(Fechas.BOGOTA).toOffsetDateTime();
    }

    /** Pide la descarga, sin tope de tamaño (ver {@link #solicitar(MotorReportes.Solicitud, String, Quien, long)}). */
    public Solicitada solicitar(MotorReportes.Solicitud s, String formato, Quien q) {
        return solicitar(s, formato, q, 0);
    }

    /**
     * Pide el archivo (descarga o envío por correo): lo deja en la cola de la instancia («pendiente») y devuelve su código y el trabajo.
     * Más de {@code enCursoPorUsuario} en cola o en curso del mismo usuario es 429; la cola llena, 503.
     *
     * @param maxBytes tope del archivo (el adjunto del correo): al pasarlo se corta con {@link AdjuntoGrande}; 0 = sin tope
     */
    public Solicitada solicitar(MotorReportes.Solicitud s, String formato, Quien q, long maxBytes) {
        OffsetDateTime creado = ahora();
        Map.Entry<Long, String> reg = registro.pedir(nuevo(s, q, formato), cfg.enCursoPorUsuario(), creado, finDelDia(creado))
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.TOO_MANY_REQUESTS, "Ya tiene " + cfg.enCursoPorUsuario()
                        + " reportes generándose. Espere a que terminen (los verá en «Descargas del día»)."));
        long id = reg.getKey();
        String codigo = reg.getValue();
        aceptadas.add(id);
        try {
            CompletableFuture<Archivo> trabajo = CompletableFuture.supplyAsync(() -> {
                registro.iniciar(id, ahora());
                return generar(id, codigo, creado, s, formato, q, maxBytes);
            }, cola);
            // Nadie lee el resultado de un diferido (la respuesta ya fue 202): lo inesperado queda en el log
            trabajo.whenComplete((a, ex) -> {
                aceptadas.remove(id);
                Throwable causa = ex instanceof java.util.concurrent.CompletionException c && c.getCause() != null ? c.getCause() : ex;
                if (causa != null && !(causa instanceof ErrorNegocio)) {
                    LOG.error("[reportes] La generación {} terminó con un error inesperado", codigo, causa);
                }
            });
            return new Solicitada(codigo, trabajo);
        } catch (RejectedExecutionException e) {
            aceptadas.remove(id);
            String mensaje = "El servidor tiene muchos reportes en cola. Intente en unos minutos.";
            fallo(id, codigo, ruta(codigo, creado, formato), false, mensaje, e, s, formato, q);
            throw new ErrorNegocio(HttpStatus.SERVICE_UNAVAILABLE, mensaje);
        }
    }

    /**
     * Genera el archivo de una fila «generando» y lo guarda en el almacén. Todo (lectura, cinta y escritura) cabe en el tope técnico:
     * así la limpieza nunca da por interrumpida una generación viva. Si algo falla, el archivo a medias se descarta, el registro queda
     * «fallida», la bitácora lo anota y el error sigue: una validación (el tope de tiempo o del adjunto) con su mensaje; cualquier
     * otro, con el código.
     */
    Archivo generar(long id, String codigo, OffsetDateTime creado, MotorReportes.Solicitud s, String formato, Quien q, long maxBytes) {
        long inicio = System.nanoTime();
        long plazo = cfg.topeTecnicoMs();
        long limite = inicio + plazo * 1_000_000L;
        String ruta = ruta(codigo, creado, formato);
        boolean escrito = false;
        try (CintaFilas cinta = new CintaFilas()) {
            HuellaDatos h = new HuellaDatos(s.definicion(), s.consulta());
            // Sin tope de filas por formato: la DIGSA exige los reportes completos (TAS P12); lo grande va en segundo plano
            long filas = motor.volcar(s, 0, null, f -> {
                h.fila(f);
                cinta.escribir(f);
            });
            cinta.terminar();
            cinta.plazo(limite, plazo);
            String huellaDatos = h.hex();
            long msConsulta = (System.nanoTime() - inicio) / 1_000_000;
            ReporteEncabezado e = encabezado(s, q.texto(), creado, filas, codigo, huellaDatos);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long[] tamano = {0};
            try (OutputStream destino = new BufferedOutputStream(almacen.escribir(ruta), 64 * 1024)) {
                escrito = true;
                // Cuenta los bytes, corta al pasar el tope del adjunto y revisa el tope técnico cada mega escrito
                OutputStream contado = new OutputStream() {
                    private long revisado;

                    @Override
                    public void write(int b) throws IOException {
                        destino.write(b);
                        contar(1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        destino.write(b, off, len);
                        contar(len);
                    }

                    private void contar(int n) {
                        tamano[0] += n;
                        if (maxBytes > 0 && tamano[0] > maxBytes) {
                            throw new AdjuntoGrande(cfg.adjuntoMaxMb());
                        }
                        if (tamano[0] - revisado >= 1024 * 1024) {
                            revisado = tamano[0];
                            if (System.nanoTime() > limite) {
                                throw new ReporteCortado(plazo, true);
                            }
                        }
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
            medicion.revisar(s.definicion(), "al exportar en " + formato, total, cfg.plazoExportarMs(), false,
                    codigo + " · " + ControladorReportes.resumen(s, String.valueOf(filas), total));
            return new Archivo(id, codigo, nombre(s, creado, codigo, formato), TIPOS.get(formato), ruta, tamano[0], filas, huellaDatos, huellaArchivo, total);
        } catch (Exception e) {
            // Una validación (el tope de tiempo o del adjunto) conserva su mensaje para el usuario, aunque la librería del formato la
            // haya envuelto al lanzarse desde la salida
            ErrorNegocio n = validacion(e);
            if (n != null) {
                if (n instanceof ReporteCortado c) {
                    medicion.revisar(s.definicion(), "al exportar en " + formato, c.plazoMs(), c.plazoMs(), true,
                            codigo + " · " + ControladorReportes.resumen(s, "?", c.plazoMs()));
                }
                fallo(id, codigo, ruta, escrito, recortar(n.getMessage()), e, s, formato, q);
                throw n;
            }
            LOG.error("[reportes] Falló la generación de {} ({}) en {}", s.definicion().id(), codigo, formato, e);
            fallo(id, codigo, ruta, escrito, "No se pudo generar el archivo.", e, s, formato, q);
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo generar el reporte. Código de la solicitud: " + codigo + ".");
        } catch (Error e) {
            // Sin memoria u otro error grave: la fila no queda «generando» hasta la limpieza
            LOG.error("[reportes] Error grave en la generación de {} ({}) en {}", s.definicion().id(), codigo, formato, e);
            fallo(id, codigo, ruta, escrito, "No se pudo generar el archivo.", e, s, formato, q);
            throw e;
        }
    }

    /** Ruta del archivo en el almacén: el día de la solicitud y el código (también sirve para borrar lo que quedó a medias). */
    static String ruta(String codigo, OffsetDateTime creado, String formato) {
        return "reportes/" + creado.atZoneSameInstant(Fechas.BOGOTA).format(CARPETA) + "/" + codigo + "." + formato;
    }

    /** Nombre del archivo descargado: reporte, fecha y hora de la solicitud y el final del código. */
    static String nombre(MotorReportes.Solicitud s, OffsetDateTime creado, String codigo, String formato) {
        return nombre(s.definicion().id(), creado, codigo, formato);
    }

    static String nombre(String reporte, OffsetDateTime creado, String codigo, String formato) {
        return "reporte_" + reporte + "_" + creado.atZoneSameInstant(Fechas.BOGOTA).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + "_"
                + codigo.substring(codigo.length() - 6) + "." + formato;
    }

    /**
     * Descarta el archivo a medias, marca el registro «fallida» y lo anota en la bitácora; si eso también falla, queda en el log sin
     * tapar el error original.
     */
    private void fallo(long id, String codigo, String ruta, boolean escrito, String mensaje, Throwable original, MotorReportes.Solicitud s, String formato,
                       Quien q) {
        if (escrito) {
            almacen.descartar(ruta);
        }
        try {
            registro.fallida(id, mensaje, ahora());
        } catch (RuntimeException x) {
            original.addSuppressed(x);
            LOG.error("[reportes] No se pudo marcar como fallida la generación {}", id, x);
        }
        bitacora.registrar("reporte_fallido", s.definicion().titulo() + " en " + formato + " no se generó (" + codigo + "): " + mensaje, q.id(), q.usuario());
    }

    /** El error de negocio en la cadena de causas (máximo unas pocas vueltas), o null. */
    static ErrorNegocio validacion(Throwable e) {
        int vueltas = 0;
        for (Throwable t = e; t != null && vueltas++ < 10; t = t.getCause()) {
            if (t instanceof ErrorNegocio n) {
                return n;
            }
        }
        return null;
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
