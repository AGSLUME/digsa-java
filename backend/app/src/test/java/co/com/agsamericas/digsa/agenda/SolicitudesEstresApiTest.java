package co.com.agsamericas.digsa.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Error 500 intermitente al registrar una solicitud con su escrito: registros repetidos y concurrentes, con las variantes que mandan
 * los navegadores (Content-Type del PDF vacío u octet-stream, nombres raros) y fechas en los bordes. Ningún registro responde 5xx.
 */
class SolicitudesEstresApiTest extends PruebaIntegracion {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes();

    private String hoy(int dias) {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA)).plusDays(dias).toString();
    }

    private Navegador sanidad() {
        String u = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", u).update();
        Navegador n = navegador();
        assertThat(n.ingresar(u, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private long punto() {
        return jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, creado_por) VALUES (:n, 'ESM', 'EJC', 'Dispensación', '05001', 'prueba')
                RETURNING id""").param("n", unico("Punto estrés ")).query(Long.class).single();
    }

    private static Respuesta solicitar(Navegador n, long punto, String fecha, String nombre, String tipo) {
        Map<String, String> campos = new HashMap<>();
        campos.put("punto_id", String.valueOf(punto));
        campos.put("fecha_solicitud", fecha);
        campos.put("motivo", "Quejas de usuarios por medicamentos pendientes de entrega.");
        return n.multipart("/api/solicitudes", campos, nombre == null ? Map.of() : Map.of(nombre, PDF), "escrito", tipo);
    }

    @Test
    @DisplayName("con más de 100 coordinadores activos el registro no falla y el aviso les llega a todos")
    void muchosCoordinadores() {
        String hash = codificador.encode(CLAVE);
        List<String> correos = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            String u = unico("coordmasivo").replaceAll("[^a-z0-9._-]", "");
            long id = usuarios.crear("Coordinador " + u, "2000" + i, null, u, u + "@example.com", hash, null, null, null, java.time.Instant.now());
            usuarios.reemplazarPerfiles(id, List.of(Perfil.COORDINADOR));
            correos.add(u + "@example.com");
        }
        Respuesta r = solicitar(sanidad(), punto(), hoy(0), "escrito.pdf", "application/pdf");
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        long id = r.json().path("id").asLong();
        esperarCorreoA(correos.get(0), "Solicitud de visita a necesidad N.º " + id);
        esperarCorreoA(correos.get(104), "Solicitud de visita a necesidad N.º " + id);
        assertThat(jdbc.sql("SELECT count(*) FROM correos_salida WHERE referencia = :r").param("r", "solicitud:" + id).query(Long.class).single())
                .as("el aviso va en filas de hasta 100 destinatarios").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("registros repetidos y concurrentes con escrito, variantes de Content-Type, nombres y fechas: ninguno responde 5xx")
    void repetidosYConcurrentes() throws Exception {
        crearUsuario("coord", Perfil.COORDINADOR);
        long p = punto();
        List<Navegador> usuarios = List.of(sanidad(), sanidad(), sanidad());
        List<String> tipos = java.util.Arrays.asList("application/pdf", "application/octet-stream", "", "application/x-pdf", null);
        List<String> nombres = List.of("escrito.pdf", "ESCRITO.PDF", "C:\\fakepath\\escrito.pdf", "Escrito ñ «oficio» N.º 3.pdf", "a".repeat(300) + ".pdf",
                "\u200Bescrito.pdf", "escrito");
        List<String> fallas = Collections.synchronizedList(new ArrayList<>());
        ExecutorService hilos = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> tareas = new ArrayList<>();
            for (int i = 0; i < 42; i++) {
                Navegador n = usuarios.get(i % usuarios.size());
                String tipo = tipos.get(i % tipos.size());
                String nombre = nombres.get(i % nombres.size());
                String fecha = i % 3 == 0 ? hoy(-60) : hoy(0);
                tareas.add(hilos.submit(() -> {
                    Respuesta r = solicitar(n, p, fecha, nombre, tipo);
                    if (r.estado() != 201) {
                        fallas.add(r.estado() + " [" + tipo + " | " + nombre + " | " + fecha + "] " + r.texto());
                    }
                }));
            }
            for (Future<?> t : tareas) {
                t.get();
            }
        } finally {
            hilos.shutdown();
        }
        assertThat(fallas).as("registros que no quedaron").isEmpty();
        Navegador n = usuarios.get(0);
        assertThat(solicitar(n, p, hoy(-61), "escrito.pdf", "application/pdf").estado()).isEqualTo(422);
        assertThat(solicitar(n, p, hoy(1), "escrito.pdf", "application/pdf").estado()).isEqualTo(422);
    }
}
