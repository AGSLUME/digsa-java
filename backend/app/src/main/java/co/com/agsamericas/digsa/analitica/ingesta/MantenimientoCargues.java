package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.analitica.PropiedadesAnalitica;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Conservación limitada de los datos personales en claro de la ingesta (Ley 1581; A4 L382; spec analitica-ingesta «Conservación del
 * original»): el original de cada cargue se borra al vencer su plazo; los cargues abiertos (subida, mapeo o esperando lotes del servicio
 * web) sin actividad por más de {@code horas-abandono} vencen y se descartan sus partes, lotes, original y staging; el dueño puede
 * descartar el suyo. Un borrado solo se da por hecho cuando el archivo ya no está en el almacén: si falla, la ruta se conserva, el error
 * queda en el registro y la tarea lo reintenta en la siguiente corrida. Todo queda en la bitácora; los cargues nunca se borran.
 */
@Component
class MantenimientoCargues {

    private static final Logger LOG = LoggerFactory.getLogger(MantenimientoCargues.class);

    private final JdbcClient jdbc;
    private final Almacen almacen;
    private final RegistroBitacora bitacora;
    private final PropiedadesAnalitica propiedades;
    private final Clock reloj;

    MantenimientoCargues(JdbcClient jdbc, Almacen almacen, RegistroBitacora bitacora, PropiedadesAnalitica propiedades, Clock reloj) {
        this.jdbc = jdbc;
        this.almacen = almacen;
        this.bitacora = bitacora;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    // ---------- Borrado verificado ----------

    /**
     * Borra el archivo y comprueba que ya no está (el puerto Almacen no tiene «existe»: se intenta abrirlo). Verdadero solo si el
     * almacén responde que no existe; ante cualquier otra respuesta se toma como no borrado.
     */
    boolean borrarVerificado(String ruta) {
        almacen.descartar(ruta);
        try (InputStream in = almacen.leer(ruta)) {
            LOG.error("[analitica] El archivo {} sigue en el almacén después de borrarlo; se reintenta en la próxima corrida", ruta);
            return false;
        } catch (NoSuchFileException | FileNotFoundException e) {
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.error("[analitica] No se pudo verificar el borrado de {}: {}; se reintenta en la próxima corrida", ruta, e.getMessage());
            return false;
        }
    }

    /**
     * Borra el original de un cargue. Solo si el borrado se verificó deja almacen_ruta en nulo y la fecha del borrado; si no, el cargue
     * conserva la ruta (la tarea diaria lo reintenta) y el error queda en el registro.
     *
     * @return verdadero si el original ya no está
     */
    boolean borrarOriginal(long cargueId, String ruta, String motivo) {
        if (ruta == null) {
            return true;
        }
        if (!borrarVerificado(ruta)) {
            return false;
        }
        int n = jdbc.sql("UPDATE ana_cargues SET almacen_ruta = NULL, original_borrado = :ahora WHERE id = :id AND almacen_ruta = :ruta")
                .param("ahora", OffsetDateTime.now(reloj)).param("id", cargueId).param("ruta", ruta).update();
        if (n == 1) {
            bitacora.registrar("analitica_cargue_original_borrado", "Cargue " + cargueId + " · original borrado (" + motivo + ")", null, null);
        }
        return true;
    }

    /** Partes de la subida de un cargue: del almacén (verificado) y de la tabla. Las que no se pudieron borrar se quedan para reintentar. */
    void descartarPartes(long cargueId) {
        List<String[]> partes = jdbc.sql("SELECT numero, almacen_ruta FROM ana_cargue_partes WHERE cargue_id = :c").param("c", cargueId)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list();
        for (String[] p : partes) {
            if (borrarVerificado(p[1])) {
                jdbc.sql("DELETE FROM ana_cargue_partes WHERE cargue_id = :c AND numero = :n").param("c", cargueId).param("n", Integer.parseInt(p[0]))
                        .update();
            }
        }
    }

    /** Lotes del servicio web de un cargue que siguen en el almacén. */
    void descartarLotes(long cargueId) {
        List<String> rutas = jdbc.sql("SELECT almacen_ruta FROM ana_ws_lotes WHERE cargue_id = :c AND almacen_ruta IS NOT NULL").param("c", cargueId)
                .query(String.class).list();
        for (String r : rutas) {
            if (borrarVerificado(r)) {
                jdbc.sql("UPDATE ana_ws_lotes SET almacen_ruta = NULL WHERE cargue_id = :c AND almacen_ruta = :r").param("c", cargueId).param("r", r)
                        .update();
            }
        }
    }

    void borrarStaging(long cargueId) {
        try {
            jdbc.sql("SELECT ana_staging_borrar(:c)").param("c", cargueId).query().singleRow();
        } catch (RuntimeException e) {
            LOG.warn("[analitica] No se pudo borrar el staging del cargue {}: {}", cargueId, e.getMessage());
        }
    }

    /**
     * Cierra un cargue abierto (subiendo o mapeo) como vencido o descartado: el estado cambia solo si sigue en el que se leyó, y luego se
     * descartan partes, lotes, original y staging.
     *
     * @return falso si el cargue ya no estaba abierto (otro proceso lo movió)
     */
    boolean cerrarAbierto(long cargueId, String estadoLeido, String estadoNuevo, String motivo, String tipoBitacora, Long usuarioId, String usuario) {
        record Fila(String ruta) {
        }
        List<Fila> f = jdbc.sql("""
                UPDATE ana_cargues SET estado = :nuevo, terminado = :ahora, etapa = NULL, motivo_texto = :motivo
                WHERE id = :id AND estado = :leido RETURNING almacen_ruta""")
                .param("nuevo", estadoNuevo).param("ahora", OffsetDateTime.now(reloj)).param("motivo", ProcesadorCargues.cortar(motivo, 2000))
                .param("id", cargueId).param("leido", estadoLeido).query((rs, n) -> new Fila(rs.getString(1))).list();
        if (f.isEmpty()) {
            return false;
        }
        // Primero el evento: en una petición, el primero da el tipo de la fila de la bitácora
        bitacora.registrar(tipoBitacora, ProcesadorCargues.cortar("Cargue " + cargueId + " · " + motivo, 500), usuarioId, usuario);
        descartarPartes(cargueId);
        descartarLotes(cargueId);
        borrarStaging(cargueId);
        borrarOriginal(cargueId, f.getFirst().ruta(), estadoNuevo);
        return true;
    }

    // ---------- Tareas ----------

    /** Cada hora: los cargues abiertos sin actividad por más del plazo vencen y se descartan sus archivos. */
    @Scheduled(cron = "${digsa.analitica.abandono-cron:0 15 * * * *}", zone = "America/Bogota")
    public void vencerTarea() {
        vencerAbandonados();
    }

    int vencerAbandonados() {
        record Abierto(long id, String estado) {
        }
        int horas = propiedades.horasSinActividad();
        List<Abierto> abiertos = jdbc.sql("""
                SELECT id, estado FROM ana_cargues WHERE estado IN ('subiendo', 'mapeo') AND actividad < :limite ORDER BY id""")
                .param("limite", OffsetDateTime.now(reloj).minusHours(horas)).query((rs, n) -> new Abierto(rs.getLong(1), rs.getString(2))).list();
        int n = 0;
        for (Abierto a : abiertos) {
            String motivo = "Vencido: sin actividad en " + horas + " h ("
                    + ("subiendo".equals(a.estado()) ? "subida o envío de lotes sin terminar" : "mapeo sin confirmar")
                    + "); se descartaron el archivo, sus partes y lotes";
            try {
                if (cerrarAbierto(a.id(), a.estado(), "vencido", motivo, "analitica_cargue_vencido", null, null)) {
                    n++;
                }
            } catch (RuntimeException e) {
                LOG.error("[analitica] No se pudo vencer el cargue {}", a.id(), e);
            }
        }
        return n;
    }

    /** Cada día: borra los originales vencidos (deja el cargue y sus hechos) y las partes y lotes que pasaron su plazo. */
    @Scheduled(cron = "${digsa.analitica.borrado-cron:0 50 2 * * *}", zone = "America/Bogota")
    public void borrarTarea() {
        borrarOriginalesVencidos();
    }

    int borrarOriginalesVencidos() {
        record Vencido(long id, String ruta) {
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        List<Vencido> vencidos = jdbc.sql("""
                SELECT id, almacen_ruta FROM ana_cargues WHERE almacen_ruta IS NOT NULL AND conservar_hasta <= :ahora
                  AND estado NOT IN ('mapeo', 'pendiente', 'procesando') ORDER BY id""")
                .param("ahora", ahora).query((rs, n) -> new Vencido(rs.getLong(1), rs.getString(2))).list();
        int n = 0;
        for (Vencido v : vencidos) {
            if (borrarOriginal(v.id(), v.ruta(), "vencido")) {
                n++;
            }
        }
        for (Long c : jdbc.sql("""
                SELECT DISTINCT p.cargue_id FROM ana_cargue_partes p JOIN ana_cargues k ON k.id = p.cargue_id
                WHERE p.conservar_hasta <= :ahora AND k.estado NOT IN ('pendiente', 'procesando')
                UNION
                SELECT DISTINCT l.cargue_id FROM ana_ws_lotes l JOIN ana_cargues k ON k.id = l.cargue_id
                WHERE l.almacen_ruta IS NOT NULL AND l.conservar_hasta <= :ahora AND k.estado NOT IN ('pendiente', 'procesando')""")
                .param("ahora", ahora).query(Long.class).list()) {
            descartarPartes(c);
            descartarLotes(c);
        }
        return n;
    }
}
