package co.com.agsamericas.digsa.alertas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.BandejaSalida;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.CorreoArchivo;
import co.com.agsamericas.digsa.plataforma.DespachadorSalida;
import co.com.agsamericas.digsa.reglas.Alertas;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bandeja de salida (spec exportacion-alertas, «Bandeja de salida con reintentos»): encolado en la misma transacción, envío inmediato
 * al confirmar (GMI P75: cuasi real), reintentos y agotado, y dos despachadores a la vez sin doble envío (FOR UPDATE SKIP LOCKED).
 */
class BandejaSalidaTest extends PruebaIntegracion {

    @Autowired
    BandejaSalida bandeja;
    @Autowired
    DespachadorSalida despachador;
    @Autowired
    TransactionTemplate tx;

    private static Correo.Mensaje mensaje(String para, String asunto) {
        return Correo.Mensaje.simple(para, asunto, "<p>Aviso de prueba</p>");
    }

    private String estado(long id) {
        return jdbc.sql("SELECT estado FROM correos_salida WHERE id = :id").param("id", id).query(String.class).single();
    }

    private int intentos(long id) {
        return jdbc.sql("SELECT intentos FROM correos_salida WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    private long llegados(String asunto) {
        return correos().stream().filter(m -> m.asunto().equals(asunto)).count();
    }

    @Test
    @DisplayName("encolado en la misma transacción: si se revierte, ni la fila ni el correo existen")
    void rollbackNoEnvia() throws Exception {
        String asunto = unico("Revertido ");
        try {
            tx.executeWithoutResult(t -> {
                bandeja.encolar("prueba", null, mensaje("revertido@example.com", asunto));
                throw new IllegalStateException("el cambio falla después de encolar");
            });
        } catch (IllegalStateException esperado) {
            // la transacción se revirtió
        }
        assertThat(jdbc.sql("SELECT count(*) FROM correos_salida WHERE asunto = :a").param("a", asunto).query(Long.class).single()).isZero();
        despachador.vaciar();
        Thread.sleep(300); // margen para un despachador que (indebidamente) se hubiera despertado
        assertThat(llegados(asunto)).isZero();
    }

    @Test
    @DisplayName("envío inmediato al confirmar: llega en menos de 3 s (sin esperar el respaldo de 15 s)")
    void inmediatoTrasCommit() {
        String asunto = unico("Inmediato ");
        long t0 = System.nanoTime();
        Long id = tx.execute(t -> bandeja.encolar("prueba", "visita:1", mensaje("inmediato@example.com", asunto)));
        esperarCorreo(m -> m.asunto().equals(asunto));
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        System.out.println("[latencia] bandeja: commit → correo entregado al adaptador en " + ms + " ms");
        assertThat(ms).isLessThan(3000);
        esperar(() -> "enviado".equals(estado(id)), "fila enviada");
        Map<String, Object> f = jdbc.sql("SELECT intentos, extract(epoch FROM enviado - creado) * 1000 AS ms FROM correos_salida WHERE id = :id").param("id", id)
                .query().singleRow();
        assertThat(((Number) f.get("intentos")).intValue()).isEqualTo(1);
        assertThat(((Number) f.get("ms")).doubleValue()).as("latencia guardada creado → enviado").isLessThan(3000);

        // Sin transacción (autocommit) también sale de inmediato
        String otro = unico("Autocommit ");
        bandeja.encolar("prueba", null, mensaje("inmediato@example.com", otro));
        esperarCorreo(m -> m.asunto().equals(otro));
    }

    @Test
    @DisplayName("reintento: falla el primer intento y sale en el segundo; agotado: 5 intentos, «fallido» y correo_fallido en la bitácora")
    void reintentoYAgotado() {
        // Reintento: el servidor rechaza al principio (dominio de rechazo) y luego se recupera
        String asunto = unico("Reintento ");
        long id = bandeja.encolar("prueba", null, mensaje("x" + CorreoArchivo.DOMINIO_RECHAZO, asunto));
        esperar(() -> intentos(id) == 1 && "pendiente".equals(estado(id)), "primer intento fallido");
        Map<String, Object> f = jdbc.sql("SELECT proximo, creado, error FROM correos_salida WHERE id = :id").param("id", id).query().singleRow();
        assertThat(f.get("error").toString()).isEqualTo("El servidor de correo rechazó el envío.").doesNotContain("@");
        despachador.vaciar();
        assertThat(intentos(id)).as("antes del minuto de espera no se reintenta").isEqualTo(1);
        jdbc.sql("UPDATE correos_salida SET para = ARRAY['reintento@example.com'] WHERE id = :id").param("id", id).update();
        reloj.adelantar(Duration.ofMinutes(1).plusSeconds(1));
        despachador.vaciar();
        assertThat(estado(id)).isEqualTo("enviado");
        assertThat(intentos(id)).isEqualTo(2);
        assertThat(llegados(asunto)).isEqualTo(1);

        // Agotado: 5 intentos con las esperas de 1, 5, 30 min y 2 h
        String agotado = unico("Agotado ");
        long id2 = bandeja.encolar("prueba_agotado", null, mensaje("y" + CorreoArchivo.DOMINIO_RECHAZO, agotado));
        esperar(() -> intentos(id2) == 1, "primer intento");
        for (int i = 2; i <= Alertas.MAX_INTENTOS; i++) {
            reloj.adelantar(Alertas.esperaReintento(i - 1).plusSeconds(1));
            despachador.vaciar();
            assertThat(intentos(id2)).isEqualTo(i);
        }
        assertThat(estado(id2)).isEqualTo("fallido");
        reloj.adelantar(Duration.ofHours(3));
        despachador.vaciar();
        assertThat(intentos(id2)).as("un fallido no se reintenta").isEqualTo(Alertas.MAX_INTENTOS);
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'correo_fallido' AND descripcion LIKE :d")
                .param("d", "Correo " + id2 + " (prueba_agotado)%").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("aviso crítico agotado → alerta correo_agotado; el aviso de correo_agotado que se agota no dispara otra (sin bucle)")
    void criticoAgotadoSinBucle() {
        long id = bandeja.encolar("alerta:tarea_diaria_fallida", null, mensaje("z" + CorreoArchivo.DOMINIO_RECHAZO, unico("Critico ")), List.of(), null, true);
        agotar(id);
        esperar(() -> jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'correo_agotado' AND clave = :c").param("c", "correo:" + id)
                .query(Long.class).single() == 1, "alerta correo_agotado");

        long bucle = bandeja.encolar("alerta:correo_agotado", null, mensaje("w" + CorreoArchivo.DOMINIO_RECHAZO, unico("Bucle ")), List.of(), null, true);
        agotar(bucle);
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'correo_agotado' AND clave = :c").param("c", "correo:" + bucle)
                .query(Long.class).single()).isZero();
    }

    private void agotar(long id) {
        esperar(() -> intentos(id) >= 1, "primer intento");
        for (int i = 0; i < Alertas.MAX_INTENTOS && !"fallido".equals(estado(id)); i++) {
            reloj.adelantar(Duration.ofHours(3));
            despachador.vaciar();
        }
        assertThat(estado(id)).isEqualTo("fallido");
    }

    @Test
    @DisplayName("dos despachadores a la vez (réplicas): 30 correos, cada uno sale una sola vez")
    void skipLockedSinDobleEnvio() throws Exception {
        String prefijo = unico("Concurrente ");
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        // Directo en la tabla, sin despertar al despachador inmediato: solo compiten los dos de la prueba
        for (int i = 0; i < 30; i++) {
            jdbc.sql("""
                    INSERT INTO correos_salida (origen, para, asunto, html, proximo, creado) VALUES ('prueba', ARRAY['concurrente@example.com'], :a, '<p>x</p>', :t, :t)""")
                    .param("a", prefijo + " #" + i).param("t", ahora).update();
        }
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService dos = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> r = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                r.add(dos.submit(() -> {
                    salida.await();
                    return despachador.vaciar();
                }));
            }
            salida.countDown();
            int total = r.get(0).get() + r.get(1).get();
            assertThat(total).isGreaterThanOrEqualTo(30);
        } finally {
            dos.shutdownNow();
        }
        for (int i = 0; i < 30; i++) {
            assertThat(llegados(prefijo + " #" + i)).as("correo #" + i).isEqualTo(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM correos_salida WHERE asunto LIKE :p AND estado = 'enviado' AND intentos = 1").param("p", prefijo + " #%")
                .query(Long.class).single()).isEqualTo(30);
    }

    @Test
    @DisplayName("un envío colgado en «enviando» más de 2 minutos vuelve a la cola y sale")
    void colgadoSeRecupera() {
        String asunto = unico("Colgado ");
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long id = jdbc.sql("""
                INSERT INTO correos_salida (origen, para, asunto, html, estado, intentos, proximo, tomado, creado)
                VALUES ('prueba', ARRAY['colgado@example.com'], :a, '<p>x</p>', 'enviando', 1, :t, :tomado, :t) RETURNING id""")
                .param("a", asunto).param("t", ahora).param("tomado", ahora.minusMinutes(3)).query(Long.class).single();
        assertThat(despachador.recuperarColgados()).isGreaterThanOrEqualTo(1);
        despachador.vaciar();
        assertThat(estado(id)).isEqualTo("enviado");
        assertThat(intentos(id)).isEqualTo(2);
    }
}
