package co.com.agsamericas.digsa.maestros.carga;

import co.com.agsamericas.digsa.maestros.carga.LectorPlantillas.ErrorArchivo;
import co.com.agsamericas.digsa.maestros.carga.LectorPlantillas.ErrorUso;
import co.com.agsamericas.digsa.maestros.carga.ServicioCargaMaestros.Modo;
import co.com.agsamericas.digsa.maestros.carga.ServicioCargaMaestros.OtraCarga;
import co.com.agsamericas.digsa.maestros.carga.ServicioCargaMaestros.Responsable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Carga inicial de datos maestros por línea de comandos, dentro de la misma imagen (mismo patrón que el primer administrador):
 * <pre>
 * --digsa.carga.plantillas=/carga                          plantillas vacías con las listas de la base
 * --digsa.carga.carpeta=/carga --digsa.carga.responsable=admin [--digsa.carga.modo=simular|aplicar]
 *     [--digsa.carga.enlaces=enviar|no] [--digsa.carga.pausa-ms=500] [--digsa.carga.informe=/informe]
 * --digsa.carga.enlaces=pendientes --digsa.carga.responsable=admin   enlace a quienes nunca definieron su contraseña
 * </pre>
 * Termina con 0 (sin errores), 1 (uso no válido), 2 (falla técnica, nada escrito), 3 (filas con errores, nada escrito) o 4 (cargado,
 * algún correo no salió). En este modo no se abre el puerto ni corren las tareas programadas (DigsaApplication.argumentos).
 */
@Component
public class CargaMaestrosCli implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CargaMaestrosCli.class);

    /** @param informe carpeta del informe; vacía = la de la carga (con otra, la de la carga se puede montar en solo lectura) */
    public record Opciones(Path plantillas, Path carpeta, String responsable, String modo, String enlaces, long pausaMs, Path informe) {
        public Opciones(Path plantillas, Path carpeta, String responsable, String modo, String enlaces, long pausaMs) {
            this(plantillas, carpeta, responsable, modo, enlaces, pausaMs, null);
        }

        static Opciones de(Environment env) {
            return new Opciones(ruta(env, "digsa.carga.plantillas"), ruta(env, "digsa.carga.carpeta"), env.getProperty("digsa.carga.responsable"),
                    env.getProperty("digsa.carga.modo", "simular"), env.getProperty("digsa.carga.enlaces", "enviar"),
                    env.getProperty("digsa.carga.pausa-ms", Long.class, 500L), ruta(env, "digsa.carga.informe"));
        }

        private static Path ruta(Environment env, String propiedad) {
            String v = env.getProperty(propiedad);
            return v == null || v.isBlank() ? null : Path.of(v);
        }

        Path carpetaInforme() {
            return informe != null ? informe : carpeta;
        }

        boolean activa() {
            return plantillas != null || carpeta != null || "pendientes".equalsIgnoreCase(enlaces);
        }
    }

    private final GeneradorPlantillas generador;
    private final LectorPlantillas lector;
    private final ServicioCargaMaestros servicio;
    private final ConfigurableApplicationContext contexto;
    private final Clock reloj;

    public CargaMaestrosCli(GeneradorPlantillas generador, LectorPlantillas lector, ServicioCargaMaestros servicio,
                            ConfigurableApplicationContext contexto, Clock reloj) {
        this.generador = generador;
        this.lector = lector;
        this.servicio = servicio;
        this.contexto = contexto;
        this.reloj = reloj;
    }

    @Override
    public void run(ApplicationArguments args) {
        Opciones o = Opciones.de(contexto.getEnvironment());
        if (!o.activa()) {
            return;
        }
        int codigo = ejecutar(o);
        if (contexto.getEnvironment().getProperty("digsa.carga.salir", Boolean.class, true)) {
            System.exit(SpringApplication.exit(contexto, () -> codigo));
        }
    }

    /** @return el código de salida */
    public int ejecutar(Opciones o) {
        try {
            if (o.plantillas() != null) {
                List<Path> escritos = generador.escribir(o.plantillas());
                LOG.info("Plantillas de la carga escritas: {}.", escritos.stream().map(p -> o.plantillas().relativize(p).toString()).toList());
                return 0;
            }
            String modo = o.modo().strip().toLowerCase(Locale.ROOT);
            String enlaces = o.enlaces().strip().toLowerCase(Locale.ROOT);
            if (!List.of("simular", "aplicar").contains(modo) || !List.of("enviar", "no", "pendientes").contains(enlaces) || o.pausaMs() < 0) {
                LOG.error("Uso: --digsa.carga.carpeta=/carga --digsa.carga.responsable=usuario [--digsa.carga.modo=simular|aplicar] "
                        + "[--digsa.carga.enlaces=enviar|no|pendientes] [--digsa.carga.pausa-ms=500]");
                return 1;
            }
            Optional<Responsable> quien = servicio.responsable(o.responsable());
            if (quien.isEmpty()) {
                LOG.error("El responsable de la carga debe ser un administrador activo.");
                return 1;
            }
            if (enlaces.equals("pendientes")) {
                InformeCarga informe = servicio.enviarPendientes(quien.get(), o.pausaMs());
                informe.registrar(LOG);
                if (o.carpetaInforme() != null) {
                    escribirInforme(informe, o.carpetaInforme());
                }
                return informe.codigo();
            }
            if (o.carpeta() == null) {
                LOG.error("Falta --digsa.carga.carpeta (la carpeta montada con los archivos de la carga).");
                return 1;
            }
            LectorPlantillas.Lectura lectura;
            try {
                lectura = lector.leer(o.carpeta());
            } catch (ErrorArchivo e) {
                InformeCarga informe = new InformeCarga(modo);
                informe.agregar(new InformeCarga.Linea(e.archivo(), null, null, null, InformeCarga.Accion.ERROR, null, e.getMessage()));
                informe.registrar(LOG);
                LOG.error("Archivo rechazado ({}): {} No se escribió nada.", e.archivo(), e.getMessage());
                escribirInforme(informe, o.carpetaInforme());
                return 3;
            }
            InformeCarga informe = servicio.ejecutar(lectura, modo.equals("aplicar") ? Modo.APLICAR : Modo.SIMULAR, quien.get(),
                    enlaces.equals("enviar"), o.pausaMs());
            informe.registrar(LOG);
            escribirInforme(informe, o.carpetaInforme());
            switch (informe.codigo()) {
                case 0 -> LOG.info(modo.equals("aplicar") ? "Carga aplicada." : "Simulación sin errores: no se escribió nada. Para cargar, repita con --digsa.carga.modo=aplicar.");
                case 3 -> LOG.error("Hay filas con errores: no se escribió nada. Corrija el archivo y repita.");
                case 4 -> LOG.warn("Carga aplicada, pero algún correo de bienvenida no salió (ver el informe).");
                default -> {
                }
            }
            return informe.codigo();
        } catch (ErrorUso e) {
            LOG.error("{}", e.getMessage());
            return 1;
        } catch (OtraCarga e) {
            LOG.error("{}", e.getMessage());
            return 2;
        } catch (DataAccessException e) {
            LOG.error("Falla técnica: la base de datos no está disponible o rechazó la operación. No se escribió nada.");
            return 2;
        } catch (IOException | RuntimeException e) {
            LOG.error("Falla técnica ({}). No se escribió nada.", e.getClass().getSimpleName());
            return 2;
        }
    }

    private void escribirInforme(InformeCarga informe, Path carpeta) {
        try {
            LOG.info("Informe por fila: {}", informe.escribir(carpeta, reloj).getFileName());
        } catch (IOException e) {
            LOG.error("No se pudo escribir el informe en la carpeta de la carga (¿montada en solo lectura?). Los errores están arriba.");
        }
    }
}
