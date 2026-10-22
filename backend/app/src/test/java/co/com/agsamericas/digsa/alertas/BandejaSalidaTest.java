package co.com.agsamericas.digsa.alertas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.BandejaSalida;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.CorreoArchivo;
import co.com.agsamericas.digsa.plataforma.DespachadorSalida;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.Sms;
import co.com.agsamericas.digsa.reglas.Perfil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.ApplicationEventPublisher;
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
    @Autowired
    Sms sms;
    @Autowired
    Almacen almacen;
    @Autowired
    RegistroBitacora bitacora;
    @Autowired
    ApplicationEventPublisher eventos;
    @Autowired
    ObjectMapper json;

    /** Correo que tarda en cada envío (SMTP lento) y cuenta cuántas veces sale cada asunto. */
    static final class CorreoLento implements Correo {
        final Correo real;
        final long ms;
        final Map<String, Integer> salidas = new ConcurrentHashMap<>();

        CorreoLento(Correo real, long ms) {
            this.real = real;
            this.ms = ms;
        }

        @Override
        public Resultado enviar(Mensaje m) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            salidas.merge(m.asunto(), 1, Integer::sum);
            return real.enviar(m);
        }

        @Override
        public boolean real() {
            return false;
        }
    }

    /** Otro despachador (otra réplica) con su propio correo y su propio umbral de colgado. */
    private DespachadorSalida otro(Correo c, Duration colgado) {
        return new DespachadorSalida(jdbc, c, sms, almacen, reloj, bitacora, eventos, json, 2, colgado);
    }

    /** Correo pendiente directo en la tabla, sin despertar al despachador inmediato. */
    private long pendiente(String asunto, boolean critico, OffsetDateTime proximo) {
        return jdbc.sql("""
                INSERT INTO correos_salida (origen, critico, para, asunto, html, proximo, creado)
                VALUES ('prueba', :c, ARRAY['lento@example.com'], :a, '<p>x</p>', :p, :p) RETURNING id""")
                .param("c", critico).param("a", asunto).param("p", proximo).query(Long.class).single();
    }

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
    void criticoAgotadoSinBucle() throws Exception {
        long id = bandeja.encolar("alerta:tarea_diaria_fallida", null, mensaje("z" + CorreoArchivo.DOMINIO_RECHAZO, unico("Critico ")), List.of(), null, true);
        agotar(id);
        esperar(() -> jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'correo_agotado' AND clave = :c").param("c", "correo:" + id)
                .query(Long.class).single() == 1, "alerta correo_agotado");

        long bucle = bandeja.encolar("alerta:correo_agotado", null, mensaje("w" + CorreoArchivo.DOMINIO_RECHAZO, unico("Bucle ")), List.of(), null, true);
        agotar(bucle);
        Thread.sleep(300); // margen para un oyente que (indebidamente) disparara otra alerta en otro hilo
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'correo_agotado' AND clave = :c").param("c", "correo:" + bucle)
                .query(Long.class).single()).isZero();
    }

    /**
     * Lleva el correo a «fallido». El despachador inmediato puede estar enviando el mismo correo en otro hilo: se espera a que lo suelte
     * y se repite (acotado) hasta que quede fallido, sin depender de quién hizo cada intento.
     */
    private void agotar(long id) {
        esperar(() -> intentos(id) >= 1 && !"enviando".equals(estado(id)), "primer intento");
        for (int i = 0; i < 4 * Alertas.MAX_INTENTOS && !"fallido".equals(estado(id)); i++) {
            reloj.adelantar(Duration.ofHours(3));
            despachador.vaciarCorreos();
            esperar(() -> !"enviando".equals(estado(id)), "el intento en curso terminó");
        }
        assertThat(estado(id)).isEqualTo("fallido");
        assertThat(intentos(id)).isEqualTo(Alertas.MAX_INTENTOS);
        // La bitácora y el evento CorreoAgotado se escriben justo después de marcarlo fallido, en el hilo que lo marcó
        esperar(() -> jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'correo_fallido' AND descripcion LIKE :d")
                .param("d", "Correo " + id + " (%").query(Long.class).single() == 1, "correo_fallido en la bitácora");
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
    @DisplayName("un envío colgado en «enviando» más del umbral de colgado vuelve a la cola y sale")
    void colgadoSeRecupera() {
        String asunto = unico("Colgado ");
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long id = jdbc.sql("""
                INSERT INTO correos_salida (origen, para, asunto, html, estado, intentos, proximo, tomado, creado)
                VALUES ('prueba', ARRAY['colgado@example.com'], :a, '<p>x</p>', 'enviando', 1, :t, :tomado, :t) RETURNING id""")
                .param("a", asunto).param("t", ahora).param("tomado", ahora.minus(despachador.colgado()).minusMinutes(1)).query(Long.class).single();
        assertThat(despachador.recuperarColgados()).isGreaterThanOrEqualTo(1);
        despachador.vaciar();
        assertThat(estado(id)).isEqualTo("enviado");
        assertThat(intentos(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("SMTP lento con correos en fila: cada uno se toma justo antes de enviarlo y no se da por colgado; sale UNA sola vez")
    void envioLentoSaleUnaSolaVez() throws Exception {
        CorreoLento lento = new CorreoLento(correo, 600);
        // Colgado a 1,5 s: con la toma por lotes de antes, el 4.º correo esperaba 2,4 s en «enviando» y otra réplica lo reenviaba
        DespachadorSalida a = otro(lento, Duration.ofMillis(1500));
        DespachadorSalida b = otro(lento, Duration.ofMillis(1500));
        String prefijo = unico("Lento ");
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(pendiente(prefijo + " #" + i, false, ahora));
        }
        AtomicBoolean termino = new AtomicBoolean();
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> envios = hilos.submit(() -> {
                try {
                    return a.vaciarCorreos();
                } finally {
                    termino.set(true);
                }
            });
            // La otra réplica corre su respaldo sin parar mientras tanto: recupera colgados y envía lo que encuentre
            Future<?> respaldo = hilos.submit(() -> {
                while (!termino.get()) {
                    b.recuperarColgados();
                    b.vaciarCorreos();
                    Thread.sleep(100);
                }
                return null;
            });
            envios.get();
            respaldo.get();
        } finally {
            hilos.shutdownNow();
            a.detener();
            b.detener();
        }
        for (int i = 0; i < 4; i++) {
            assertThat(lento.salidas.get(prefijo + " #" + i)).as("correo #" + i).isEqualTo(1);
            assertThat(estado(ids.get(i))).isEqualTo("enviado");
            assertThat(intentos(ids.get(i))).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("colgado con los intentos agotados → «fallido» (sin pasar de 5 ni violar el CHECK), correo_fallido y correo_agotado")
    void colgadoAgotadoQuedaFallido() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long id = jdbc.sql("""
                INSERT INTO correos_salida (origen, critico, para, asunto, html, estado, intentos, proximo, tomado, creado)
                VALUES ('alerta:tarea_diaria_fallida', true, ARRAY['colgado@example.com'], :a, '<p>x</p>', 'enviando', :i, :t, :tomado, :t) RETURNING id""")
                .param("a", unico("Colgado agotado ")).param("i", Alertas.MAX_INTENTOS).param("t", ahora)
                .param("tomado", ahora.minus(despachador.colgado()).minusMinutes(1)).query(Long.class).single();
        for (int k = 0; k < 3; k++) {
            despachador.recuperarColgados();
            despachador.vaciarCorreos();
        }
        assertThat(estado(id)).isEqualTo("fallido");
        assertThat(intentos(id)).isEqualTo(Alertas.MAX_INTENTOS);
        assertThat(jdbc.sql("SELECT error FROM correos_salida WHERE id = :id").param("id", id).query(String.class).single()).contains("colgado");
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'correo_fallido' AND descripcion LIKE :d").param("d", "Correo " + id + " (%")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'correo_agotado' AND clave = :c").param("c", "correo:" + id)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(despachador.colgado()).as("por encima del peor envío por SMTP (10 + 30 + 60 s)").isGreaterThan(Duration.ofSeconds(100));
    }

    @Test
    @DisplayName("un SMTP lento no retrasa el SMS crítico: el SMS va por su propio hilo")
    void smsNoEsperaAlSmtpLento() {
        CorreoLento lento = new CorreoLento(correo, 4000);
        DespachadorSalida d = otro(lento, Duration.ofMinutes(5));
        try {
            long correoId = pendiente(unico("SMTP lento "), true, OffsetDateTime.now(reloj));
            d.despertarCorreos();
            esperar(() -> "enviando".equals(estado(correoId)), "el correo lento está saliendo");

            String u = crearUsuario("smsrapido", Perfil.ADMINISTRADOR);
            jdbc.sql("UPDATE usuarios SET telefono = '+573000000009' WHERE usuario = :u").param("u", u).update();
            OffsetDateTime ahora = OffsetDateTime.now(reloj);
            long alerta = jdbc.sql("""
                    INSERT INTO alertas_eventos (tipo, clave, detalle, abierta, creado, ultimo, cerrada_en)
                    VALUES ('tarea_diaria_fallida', :c, 'prueba de canal', false, :t, :t, :t) RETURNING id""")
                    .param("c", unico("sms-rapido:")).param("t", ahora).query(Long.class).single();
            long smsId = jdbc.sql("""
                    INSERT INTO sms_salida (alerta_id, usuario_id, telefono, texto, proximo, creado) VALUES (:a, :u, '+573*******09', 'Alerta critica DIGSA: prueba', :t, :t)
                    RETURNING id""").param("a", alerta).param("u", idDe(u)).param("t", ahora).query(Long.class).single();
            long t0 = System.nanoTime();
            d.despertarSms();
            esperar(() -> "enviado".equals(jdbc.sql("SELECT estado FROM sms_salida WHERE id = :id").param("id", smsId).query(String.class).single()), "SMS enviado");
            long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            System.out.println("[latencia] SMS con un SMTP de 4 s ocupado: " + ms + " ms");
            assertThat(ms).as("el SMS no espera los 4 s del correo").isLessThan(2000);
            assertThat(estado(correoId)).as("el correo lento sigue saliendo").isEqualTo("enviando");
            esperar(() -> "enviado".equals(estado(correoId)), "el correo lento terminó");
        } finally {
            d.detener();
        }
    }

    @Test
    @DisplayName("prioridad: el correo de una alerta crítica sale antes que los avisos informativos vencidos antes")
    void criticoPrimero() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        String info = unico("Informativo ");
        String critico = unico("Critico primero ");
        pendiente(info, false, ahora.minusMinutes(5));
        pendiente(critico, true, ahora);
        int antes = correos().size();
        despachador.vaciarCorreos();
        List<String> orden = correos().subList(antes, correos().size()).stream().map(Correo.Mensaje::asunto).filter(a -> a.equals(info) || a.equals(critico))
                .toList();
        assertThat(orden).containsExactly(critico, info);
    }

    @Test
    @DisplayName("purga: los enviados de más de bandeja_retencion_dias (90) se borran; lo pendiente y lo reciente se conservan")
    void purga() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        OffsetDateTime viejo = ahora.minusDays(100);
        long enviadoViejo = jdbc.sql("""
                INSERT INTO correos_salida (origen, para, asunto, html, estado, intentos, proximo, creado, enviado)
                VALUES ('prueba', ARRAY['purga@example.com'], 'viejo', '<p>x</p>', 'enviado', 1, :t, :t, :t) RETURNING id""").param("t", viejo).query(Long.class).single();
        long pendienteViejo = jdbc.sql("""
                INSERT INTO correos_salida (origen, para, asunto, html, proximo, creado)
                VALUES ('prueba', ARRAY['purga@example.com'], 'pendiente viejo', '<p>x</p>', :p, :t) RETURNING id""")
                .param("p", ahora.plusYears(1)).param("t", viejo).query(Long.class).single();
        long enviadoReciente = jdbc.sql("""
                INSERT INTO correos_salida (origen, para, asunto, html, estado, intentos, proximo, creado, enviado)
                VALUES ('prueba', ARRAY['purga@example.com'], 'reciente', '<p>x</p>', 'enviado', 1, :t, :t, :t) RETURNING id""")
                .param("t", ahora.minusDays(10)).query(Long.class).single();
        assertThat(despachador.purgar()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT id FROM correos_salida WHERE id IN (:a, :b, :c) ORDER BY id").param("a", enviadoViejo).param("b", pendienteViejo)
                .param("c", enviadoReciente).query(Long.class).list()).containsExactly(pendienteViejo, enviadoReciente);
        jdbc.sql("UPDATE correos_salida SET estado = 'fallido' WHERE id = :id").param("id", pendienteViejo).update();
    }
}
