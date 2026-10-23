package co.com.agsamericas.digsa.analitica.ingesta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Privilegios mínimos del rol de la aplicación en V60 (DIGSA_DB_ROL_APP): las pruebas de API corren como dueño del esquema, así que
 * aquí se crea un rol sin dueño, se le aplica el mismo bloque de GRANT de la migración y se verifica, sentencia por sentencia del
 * código, que tiene lo que usa (y nada de DELETE sobre la bitácora de cargues), y que con SET ROLE las funciones SECURITY DEFINER y la
 * escritura a través de las tablas padre funcionan sin USAGE sobre el esquema de las particiones.
 */
class IngestaPrivilegiosTest extends PruebaIntegracion {

    private static final String ROL = "digsa_app_prueba_analitica";

    /** (tabla, privilegio) que usa el código de la ingesta: ServicioCargues, ServicioWebOpl, ProcesadorCargues, ValidadorStaging, AplicadorHechos y MantenimientoCargues. */
    private static final List<String[]> USADOS = List.of(
            new String[] {"ana_cargues", "SELECT"}, new String[] {"ana_cargues", "INSERT"}, new String[] {"ana_cargues", "UPDATE"},
            new String[] {"ana_mapeo_plantillas", "SELECT"}, new String[] {"ana_mapeo_plantillas", "INSERT"},
            new String[] {"ana_mapeo_plantillas", "UPDATE"},
            new String[] {"ana_cargue_partes", "SELECT"}, new String[] {"ana_cargue_partes", "INSERT"}, new String[] {"ana_cargue_partes", "UPDATE"},
            new String[] {"ana_cargue_partes", "DELETE"},
            new String[] {"ana_ws_lotes", "SELECT"}, new String[] {"ana_ws_lotes", "INSERT"}, new String[] {"ana_ws_lotes", "UPDATE"},
            new String[] {"ana_cargue_rechazos", "SELECT"}, new String[] {"ana_cargue_rechazos", "INSERT"}, new String[] {"ana_cargue_rechazos", "DELETE"},
            new String[] {"ana_dispensaciones", "SELECT"}, new String[] {"ana_dispensaciones", "INSERT"}, new String[] {"ana_dispensaciones", "DELETE"},
            new String[] {"ana_llaves", "SELECT"}, new String[] {"ana_llaves", "INSERT"}, new String[] {"ana_llaves", "DELETE"},
            new String[] {"ana_staging_dispensacion", "SELECT"}, new String[] {"ana_staging_dispensacion", "INSERT"},
            new String[] {"ana_dim_tiempo", "SELECT"}, new String[] {"ana_dim_tiempo", "INSERT"});

    private static final List<String> FUNCIONES = List.of("ana_cargues_visibles(timestamptz)", "ana_asegurar_particiones(date, date)",
            "ana_analizar_particiones(date, date)", "ana_staging_crear(bigint)", "ana_staging_analizar(bigint)", "ana_staging_borrar(bigint)");

    @Autowired
    DataSource ds;

    /** Crea el rol (si no existe) y le aplica el bloque de GRANT de V60 tal cual, con el rol en lugar de ${rol_app}. */
    @BeforeEach
    void rolConLosGrantDeV60() throws IOException, SQLException {
        String v60;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V60__analitica_ingesta.sql")) {
            v60 = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int desde = v60.lastIndexOf("DO $$");
        int hasta = v60.indexOf("END $$;", desde) + "END $$;".length();
        String bloque = v60.substring(desde, hasta).replace("${rol_app}", ROL);
        assertThat(bloque).contains("GRANT SELECT, INSERT, UPDATE ON ana_cargues");
        // Statement simple: el bloque tiene «:=» y $$ que el JdbcClient tomaría por parámetros
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + ROL + "') THEN CREATE ROLE " + ROL + " NOLOGIN; END IF; END $$");
            s.execute("GRANT USAGE ON SCHEMA public TO " + ROL); // lo da V2 al rol real
            s.execute(bloque);
        }
    }

    @Test
    @DisplayName("el rol de la aplicación tiene cada privilegio que usa el código y no tiene DELETE sobre ana_cargues (bitácora)")
    void privilegiosMinimos() {
        for (String[] u : USADOS) {
            assertThat(jdbc.sql("SELECT has_table_privilege(:r, :t, :p)").param("r", ROL).param("t", u[0]).param("p", u[1]).query(Boolean.class).single())
                    .as(u[1] + " sobre " + u[0]).isTrue();
        }
        for (String f : FUNCIONES) {
            assertThat(jdbc.sql("SELECT has_function_privilege(:r, :f, 'EXECUTE')").param("r", ROL).param("f", f).query(Boolean.class).single())
                    .as("EXECUTE sobre " + f).isTrue();
        }
        assertThat(jdbc.sql("SELECT has_table_privilege(:r, 'ana_cargues', 'DELETE')").param("r", ROL).query(Boolean.class).single())
                .as("los cargues son bitácora: sin DELETE").isFalse();
        assertThat(jdbc.sql("SELECT has_sequence_privilege(:r, 'ana_dispensaciones_id_seq', 'USAGE')").param("r", ROL).query(Boolean.class).single())
                .isTrue();
        // Las SECURITY DEFINER no quedan abiertas a PUBLIC
        assertThat(jdbc.sql("SELECT has_function_privilege('public', 'ana_staging_borrar(bigint)', 'EXECUTE')").query(Boolean.class).single()).isFalse();
    }

    @Test
    @DisplayName("con SET ROLE: staging, particiones, hechos, llaves y ANALYZE por las funciones y las tablas padre; DELETE de un cargue falla")
    void sentenciasConElRol() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET ROLE " + ROL);
                s.execute("SELECT ana_staging_crear(987654321)");
                s.execute("INSERT INTO ana_staging_dispensacion (cargue_id, linea, linea_origen, huella, llave) VALUES (987654321, 1, '1', gen_random_uuid(), "
                        + "gen_random_uuid())");
                try (var rs = s.executeQuery("SELECT count(*) FROM ana_staging_dispensacion WHERE cargue_id = 987654321")) {
                    rs.next();
                    assertThat(rs.getLong(1)).isEqualTo(1);
                }
                s.execute("SELECT ana_staging_analizar(987654321)");
                s.execute("SELECT ana_staging_borrar(987654321)");
                s.execute("SELECT ana_asegurar_particiones(DATE '2031-01-01', DATE '2031-01-01')");
                s.execute("""
                        INSERT INTO ana_dispensaciones (cargue_id, linea_origen, paciente_seudo, fuerza, esm_codigo, modalidad, fecha, cantidad, valor_total, llave)
                        VALUES (987654321, '1', repeat('a', 64), 'EJC', 'BAS02', 'Dispensación', DATE '2031-01-15', 1, 1, gen_random_uuid())""");
                s.execute("INSERT INTO ana_llaves (llave, cargue_id) VALUES (gen_random_uuid(), 987654321)");
                s.execute("SELECT ana_analizar_particiones(DATE '2031-01-01', DATE '2031-01-31')");
                s.execute("DELETE FROM ana_dispensaciones WHERE cargue_id = 987654321");
                s.execute("DELETE FROM ana_llaves WHERE cargue_id = 987654321");
                s.execute("SELECT count(*) FROM ana_cargues_visibles(now())");
                Savepoint antes = c.setSavepoint();
                assertThatThrownBy(() -> s.execute("DELETE FROM ana_cargues WHERE id = 987654321")).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).as("insufficient_privilege").isEqualTo("42501"));
                c.rollback(antes);
            } finally {
                c.rollback();
                try (Statement s = c.createStatement()) {
                    s.execute("RESET ROLE");
                }
                c.setAutoCommit(true);
            }
        }
    }
}
