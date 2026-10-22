package co.com.agsamericas.digsa.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Plazos;
import co.com.agsamericas.digsa.reglas.PropuestaAgenda;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Propuesta de agenda (docs/especificaciones/propuesta-agenda): solo el coordinador; cobertura por ciclo y año de contrato; borrador
 * que no programa nada; ajuste que fija la fila; recalcular respetando lo fijado; confirmar una sola vez programando y notificando.
 * Usa el ciclo del nivel central (los datos iniciales no traen puntos NC), con un punto propio.
 */
class PropuestaApiTest extends PruebaIntegracion {

    private String inicioAntes;

    @BeforeEach
    void contrato() {
        inicioAntes = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'inicio_contrato'").query(String.class).single();
        jdbc.sql("UPDATE par_parametros SET valor = :v WHERE clave = 'inicio_contrato'").param("v", hoy().minusDays(30).toString()).update();
        // Ningún punto NC de otra prueba queda en el ciclo del nivel central
        jdbc.sql("UPDATE puntos SET activo = false WHERE unidad = 'NC'").update();
    }

    @AfterEach
    void restaurar() {
        jdbc.sql("UPDATE par_parametros SET valor = :v WHERE clave = 'inicio_contrato'").param("v", inicioAntes).update();
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA));
    }

    private Navegador entrar(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private String auditor(String prefijo, String rol, String municipio) {
        String u = crearUsuario(prefijo, Perfil.AUDITOR);
        jdbc.sql("INSERT INTO auditores (usuario_id, rol, municipio_base) VALUES (:id, :r, :m)").param("id", idDe(u)).param("r", rol).param("m", municipio).update();
        return u;
    }

    @Test
    @DisplayName("propuesta del nivel central: borrador sin programar, ajuste fijo, recalcular, confirmar una vez y la cobertura lo cuenta")
    void flujoCompleto() {
        Navegador coord = entrar(crearUsuario("coord", Perfil.COORDINADOR));
        String qf = auditor("qfpro", "quimico_farmaceutico", "11001");
        String ing = auditor("ingpro", "ingeniero_sistemas", "11001");
        long punto = jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, municipio, correos, creado_por, auditor_asignado)
                VALUES (:n, 'NC', 'EJC', '11001', ARRAY['central.opl@example.com'], 'prueba', :a) RETURNING id""")
                .param("n", unico("Nivel central ")).param("a", idDe(qf)).query(Long.class).single();

        assertThat(entrar(ing).get("/api/propuesta").estado()).as("solo el coordinador").isEqualTo(403);
        JsonNode datos = coord.get("/api/propuesta").json();
        List<String> ciclos = new ArrayList<>();
        datos.path("ciclos").forEach(c -> ciclos.add(c.path("ciclo").asText()));
        assertThat(ciclos).containsExactly("linea_base", "anual", "farmacia", "central");
        JsonNode central = datos.path("ciclos").get(3);
        assertThat(central.path("anioContrato").asInt()).isEqualTo(1);
        assertThat(central.path("total").asInt()).isEqualTo(1);
        assertThat(central.path("faltan").asInt()).isEqualTo(1);
        assertThat(datos.path("avisos").toString()).as("ciudades sin auditor con base").contains("Neiva");

        Respuesta gen = coord.post("/api/propuesta/generar", Map.of("ciclo", "central", "anio", 1));
        assertThat(gen.estado()).as(gen.texto()).isEqualTo(200);
        long id = gen.json().path("id").asLong();
        assertThat(jdbc.sql("SELECT count(*) FROM visitas WHERE punto_id = :p").param("p", punto).query(Long.class).single()).as("nada se programa").isZero();
        JsonNode borrador = coord.get("/api/propuesta/" + id).json();
        JsonNode fila = borrador.path("items").get(0);
        assertThat(fila.path("puntoId").asLong()).isEqualTo(punto);
        assertThat(fila.path("auditorId").asLong()).as("el asignado al punto").isEqualTo(idDe(qf));
        assertThat(fila.path("motivo").asText()).isEqualTo("Auditor asignado al punto");
        assertThat(fila.path("otros").get(0).path("id").asLong()).as("el nivel central suma el ingeniero").isEqualTo(idDe(ing));
        long item = fila.path("id").asLong();

        // Ajuste: un sábado no; el primer día posible sí, y la fila queda fija
        LocalDate sabado = hoy().plusDays(30).with(DayOfWeek.SATURDAY);
        assertThat(coord.put("/api/propuesta/items/" + item, Map.of("auditorId", idDe(qf), "otros", List.of(idDe(ing)), "fecha", sabado.toString())).estado())
                .isEqualTo(422);
        assertThat(coord.put("/api/propuesta/items/" + item, Map.of("auditorId", idDe(ing), "otros", List.of(), "fecha", "")).mensaje())
                .as("el ingeniero no lidera").contains("químico farmacéutico, regente o auditor en salud");
        LocalDate dia = PropuestaAgenda.dias(new Plazos(), hoy(), hoy(), hoy().plusDays(90)).get(3);
        Respuesta aj = coord.put("/api/propuesta/items/" + item, Map.of("auditorId", idDe(qf), "otros", List.of(idDe(ing)), "fecha", dia.toString()));
        assertThat(aj.estado()).as(aj.texto()).isEqualTo(200);
        assertThat(coord.post("/api/propuesta/" + id + "/recalcular", null).estado()).isEqualTo(200);
        JsonNode recal = coord.get("/api/propuesta/" + id).json().path("items").get(0);
        assertThat(recal.path("fijo").asBoolean()).isTrue();
        assertThat(recal.path("fecha").asText()).as("lo fijado se respeta").isEqualTo(dia.toString());

        // Confirmar: programa y notifica una vez
        Respuesta conf = coord.post("/api/propuesta/" + id + "/confirmar", null);
        assertThat(conf.estado()).as(conf.texto()).isEqualTo(200);
        assertThat(conf.json().path("programadas").asInt()).isEqualTo(1);
        Map<String, Object> v = jdbc.sql("SELECT tipo, fecha, auditor_id, alcance FROM visitas WHERE punto_id = :p").param("p", punto).query().singleRow();
        assertThat(v.get("tipo")).isEqualTo("central");
        assertThat(v.get("fecha").toString()).isEqualTo(dia.toString());
        assertThat(((Number) v.get("auditor_id")).longValue()).isEqualTo(idDe(qf));
        assertThat((String) v.get("alcance")).contains("propuesta de agenda N° " + id);
        assertThat(coord.post("/api/propuesta/" + id + "/confirmar", null).estado()).as("una sola vez").isEqualTo(404);
        assertThat(jdbc.sql("SELECT estado FROM propuestas_agenda WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("confirmada");
        JsonNode despues = coord.get("/api/propuesta").json().path("ciclos").get(3);
        assertThat(despues.path("agendadas").asInt()).isEqualTo(1);
        assertThat(despues.path("faltan").asInt()).isZero();
        // Decisión 13.3: distribución auditor–punto para la DIGSA (coordinación y DIGSA; los demás perfiles no la ven)
        Respuesta dist = coord.get("/api/reportes/distribucion");
        assertThat(dist.estado()).as(dist.texto()).isEqualTo(200);
        assertThat(dist.texto()).contains("Persona " + qf, "Sí");
        String sanidad = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE id = :id").param("id", idDe(sanidad)).update();
        assertThat(entrar(sanidad).get("/api/reportes/distribucion").estado()).isEqualTo(404);
        Respuesta otra = coord.post("/api/propuesta/generar", Map.of("ciclo", "central", "anio", 1));
        assertThat(otra.mensaje()).contains("No hay puntos pendientes");
        assertThat(jdbc.sql("SELECT tipo FROM log_transacciones WHERE tipo LIKE 'propuesta_%' ORDER BY id").query(String.class).list()).contains("propuesta_generada");
        // La confirmación queda en la fila de su petición, junto con la programación de cada visita
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE descripcion LIKE :d").param("d", "%Propuesta " + id + " · 1 programada(s)%")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("sin inicio del contrato no se propone; un borrador se descarta y el ciclo queda con un solo borrador")
    void validacionesYDescartar() {
        Navegador coord = entrar(crearUsuario("coord", Perfil.COORDINADOR));
        auditor("qfdesc", "quimico_farmaceutico", "05001");
        jdbc.sql("INSERT INTO puntos (nombre, unidad, fuerza, municipio, correos, creado_por) VALUES (:n, 'NC', 'EJC', '05001', ARRAY['nc@example.com'], 'prueba')")
                .param("n", unico("Nivel central ")).update();
        jdbc.sql("UPDATE par_parametros SET valor = '' WHERE clave = 'inicio_contrato'").update();
        assertThat(coord.post("/api/propuesta/generar", Map.of("ciclo", "central", "anio", 1)).mensaje()).contains("inicio del contrato");
        jdbc.sql("UPDATE par_parametros SET valor = :v WHERE clave = 'inicio_contrato'").param("v", hoy().minusDays(30).toString()).update();
        assertThat(coord.post("/api/propuesta/generar", Map.of("ciclo", "otro")).estado()).isEqualTo(422);
        long a = coord.post("/api/propuesta/generar", Map.of("ciclo", "central", "anio", 1)).json().path("id").asLong();
        long b = coord.post("/api/propuesta/generar", Map.of("ciclo", "central", "anio", 1)).json().path("id").asLong();
        assertThat(jdbc.sql("SELECT estado FROM propuestas_agenda WHERE id = :id").param("id", a).query(String.class).single()).as("proponer de nuevo descarta").isEqualTo("descartada");
        assertThat(coord.post("/api/propuesta/" + b + "/descartar", null).estado()).isEqualTo(200);
        assertThat(coord.post("/api/propuesta/" + b + "/descartar", null).estado()).isEqualTo(404);
        // La línea base de un contrato que empezó hace dos años ya venció
        jdbc.sql("UPDATE par_parametros SET valor = :v WHERE clave = 'inicio_contrato'").param("v", hoy().minusYears(2).toString()).update();
        assertThat(coord.post("/api/propuesta/generar", Map.of("ciclo", "linea_base")).mensaje()).contains("ya venció");
    }
}
