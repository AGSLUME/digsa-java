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
            s.execute(bloque("/db/migration/V61__analitica_homologacion.sql"));
            s.execute(bloque("/db/migration/V62__analitica_tableros.sql"));
            s.execute(bloque("/db/migration/V63__analitica_cohortes_alertas.sql"));
        }
    }

    /** Último bloque DO de GRANT de una migración, con el rol de prueba en lugar de ${rol_app}. */
    private String bloque(String recurso) throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream(recurso)) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int desde = sql.lastIndexOf("DO $$");
        int hasta = sql.indexOf("END $$;", desde) + "END $$;".length();
        return sql.substring(desde, hasta).replace("${rol_app}", ROL);
    }

    // --- Homologación (V61) ---
    /** (tabla, privilegio) que usa el código de la homologación: ServicioReferencias, ServicioHomologacion, ServicioRevision y ServicioReglas. */
    private static final List<String[]> USADOS_V61 = List.of(
            new String[] {"ref_fuente", "SELECT"}, new String[] {"ref_atc_nombre_nivel", "SELECT"}, new String[] {"ref_atc_v", "SELECT"},
            new String[] {"ref_version", "INSERT"}, new String[] {"ref_version", "UPDATE"}, new String[] {"ref_cum_staging", "INSERT"},
            new String[] {"ref_cum_staging", "DELETE"}, new String[] {"ref_cum_presentacion", "INSERT"}, new String[] {"ref_cum_principio_activo", "INSERT"},
            new String[] {"ref_cum_rol", "INSERT"}, new String[] {"ref_cie10", "INSERT"}, new String[] {"ref_precio_regulado", "INSERT"},
            new String[] {"ref_vital_no_disponible", "INSERT"}, new String[] {"ref_lista_normativa", "INSERT"}, new String[] {"ref_codigo_ssfm", "INSERT"},
            new String[] {"hom_regla", "UPDATE"}, new String[] {"hom_regla_historial", "INSERT"}, new String[] {"hom_catalogo", "INSERT"},
            new String[] {"hom_version", "INSERT"}, new String[] {"hom_tabla_maestra", "INSERT"}, new String[] {"hom_tabla_cie10", "INSERT"},
            new String[] {"hom_revision", "INSERT"}, new String[] {"hom_medicamento_origen", "UPDATE"}, new String[] {"hom_cie10_origen", "INSERT"},
            new String[] {"hom_cargue_origen", "INSERT"}, new String[] {"hom_cargue_mes", "INSERT"}, new String[] {"hom_devolucion", "UPDATE"},
            // Lo pendiente de un cargue que no se aplica se borra (los disparadores protegen las versiones publicadas)
            new String[] {"hom_version", "UPDATE"}, new String[] {"hom_version", "DELETE"}, new String[] {"hom_tabla_maestra", "DELETE"},
            new String[] {"hom_tabla_cie10", "DELETE"}, new String[] {"hom_cargue_origen", "DELETE"}, new String[] {"hom_cargue_mes", "DELETE"},
            new String[] {"hom_medicamento_origen", "DELETE"}, new String[] {"hom_cie10_origen", "DELETE"});

    /** Lo publicado no se toca: el rol no tiene UPDATE sobre la tabla maestra, las referencias cargadas ni el historial. */
    private static final List<String[]> PROHIBIDOS_V61 = List.of(
            new String[] {"hom_tabla_maestra", "UPDATE"}, new String[] {"hom_tabla_cie10", "UPDATE"}, new String[] {"hom_revision", "DELETE"},
            new String[] {"hom_regla_historial", "DELETE"}, new String[] {"ref_cum_presentacion", "DELETE"}, new String[] {"ref_cum_presentacion", "UPDATE"},
            new String[] {"ref_version", "DELETE"}, new String[] {"ref_fuente", "INSERT"}, new String[] {"hom_revision", "UPDATE"},
            new String[] {"ana_dispensaciones", "UPDATE"});

    @Test
    @DisplayName("V62: el rol lee e inserta las tablas de apoyo de los tableros y la bitácora de analítica; no las modifica ni las borra")
    void privilegiosTableros() {
        List<String> tablas = List.of("ana_hechos_tablero", "ana_pacientes", "ana_cargue_valores", "ana_consultas");
        for (String[] u : tablas.stream().flatMap(t -> java.util.stream.Stream.of(new String[] {t, "SELECT"}, new String[] {t, "INSERT"})).toList()) {
            assertThat(jdbc.sql("SELECT has_table_privilege(:r, :t, :p)").param("r", ROL).param("t", u[0]).param("p", u[1]).query(Boolean.class).single())
                    .as(u[1] + " sobre " + u[0]).isTrue();
        }
        for (String[] u : tablas.stream().flatMap(t -> java.util.stream.Stream.of(new String[] {t, "UPDATE"}, new String[] {t, "DELETE"})).toList()) {
            assertThat(jdbc.sql("SELECT has_table_privilege(:r, :t, :p)").param("r", ROL).param("t", u[0]).param("p", u[1]).query(Boolean.class).single())
                    .as("sin " + u[1] + " sobre " + u[0]).isFalse();
        }
        for (String s : List.of("ana_consultas_id_seq", "ana_pacientes_id_seq")) {
            assertThat(jdbc.sql("SELECT has_sequence_privilege(:r, :s, 'USAGE')").param("r", ROL).param("s", s).query(Boolean.class).single()).as(s).isTrue();
        }
        assertThat(jdbc.sql("SELECT has_column_privilege(:r, 'ana_cargues', 'campos_presentes', 'UPDATE')").param("r", ROL).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    @DisplayName("V63: el rol tiene lo que usan cohortes y alertas; la traza (historiales, filas de referencia) no se modifica ni se borra; las alertas no se borran")
    void privilegiosCohortesAlertas() {
        List<String[]> usados = List.of(
                new String[] {"ana_cohortes", "SELECT"}, new String[] {"ana_cohortes", "INSERT"}, new String[] {"ana_cohortes", "UPDATE"},
                new String[] {"ana_cohortes_versiones", "INSERT"}, new String[] {"ana_cohortes_versiones", "UPDATE"},
                new String[] {"ana_cohortes_ejecuciones", "INSERT"}, new String[] {"ana_cohortes_ejecuciones", "UPDATE"},
                new String[] {"ana_cohortes_resultados", "INSERT"}, new String[] {"ana_cohortes_resultados", "UPDATE"},
                new String[] {"ana_cohortes_serie", "INSERT"}, new String[] {"ana_cohortes_serie", "DELETE"},
                new String[] {"ana_cohortes_miembros", "INSERT"}, new String[] {"ana_cohortes_miembros", "DELETE"},
                new String[] {"ana_cohortes_conteos", "INSERT"}, new String[] {"ana_cohortes_conteos", "DELETE"}, new String[] {"ana_cohortes_conteos", "SELECT"},
                new String[] {"ana_alertas_parametros", "INSERT"}, new String[] {"ana_alertas_parametros", "UPDATE"},
                new String[] {"ana_alertas_parametros_historial", "INSERT"}, new String[] {"ana_alertas", "INSERT"}, new String[] {"ana_alertas", "UPDATE"},
                new String[] {"ana_alertas_historial", "INSERT"}, new String[] {"ana_alertas_ejecuciones", "INSERT"},
                new String[] {"ana_alertas_ejecuciones", "UPDATE"}, new String[] {"ana_ref_versiones", "INSERT"}, new String[] {"ana_ref_versiones", "UPDATE"},
                new String[] {"ana_ref_filas", "INSERT"});
        List<String[]> prohibidos = List.of(
                new String[] {"ana_alertas", "DELETE"}, new String[] {"ana_alertas_historial", "UPDATE"}, new String[] {"ana_alertas_historial", "DELETE"},
                new String[] {"ana_alertas_parametros_historial", "UPDATE"}, new String[] {"ana_alertas_parametros_historial", "DELETE"},
                new String[] {"ana_ref_filas", "UPDATE"}, new String[] {"ana_ref_filas", "DELETE"}, new String[] {"ana_cohortes", "DELETE"},
                new String[] {"ana_cohortes_versiones", "DELETE"}, new String[] {"ana_cohortes_conteos", "UPDATE"});
        for (String[] u : usados) {
            assertThat(jdbc.sql("SELECT has_table_privilege(:r, :t, :p)").param("r", ROL).param("t", u[0]).param("p", u[1]).query(Boolean.class).single())
                    .as(u[1] + " sobre " + u[0]).isTrue();
        }
        for (String[] u : prohibidos) {
            assertThat(jdbc.sql("SELECT has_table_privilege(:r, :t, :p)").param("r", ROL).param("t", u[0]).param("p", u[1]).query(Boolean.class).single())
                    .as("sin " + u[1] + " sobre " + u[0]).isFalse();
        }
        for (String s : List.of("ana_cohortes_id_seq", "ana_alertas_id_seq", "ana_ref_versiones_id_seq")) {
            assertThat(jdbc.sql("SELECT has_sequence_privilege(:r, :s, 'USAGE')").param("r", ROL).param("s", s).query(Boolean.class).single()).as(s).isTrue();
        }
        // El aviso de severidad alta es operativo: solo correo
        assertThat(jdbc.sql("SELECT critica OR sms FROM alertas_criticas WHERE tipo = 'analitica_alerta_alta'").query(Boolean.class).single()).isFalse();
    }

    @Test
    @DisplayName("V61: el rol tiene lo que usa la homologación y no puede modificar ni borrar lo publicado (tabla maestra, referencias, historial)")
    void privilegiosHomologacion() {
        for (String[] u : USADOS_V61) {
            assertThat(jdbc.sql("SELECT has_table_privilege(:r, :t, :p)").param("r", ROL).param("t", u[0]).param("p", u[1]).query(Boolean.class).single())
                    .as(u[1] + " sobre " + u[0]).isTrue();
        }
        for (String[] u : PROHIBIDOS_V61) {
            assertThat(jdbc.sql("SELECT has_table_privilege(:r, :t, :p)").param("r", ROL).param("t", u[0]).param("p", u[1]).query(Boolean.class).single())
                    .as("sin " + u[1] + " sobre " + u[0]).isFalse();
        }
        for (String f : List.of("hom_version_vigente(timestamptz)", "hom_tabla_maestra_version(integer)", "hom_tabla_cie10_version(integer)",
                "hom_estado_final(text, text)")) {
            assertThat(jdbc.sql("SELECT has_function_privilege(:r, :f, 'EXECUTE')").param("r", ROL).param("f", f).query(Boolean.class).single())
                    .as(f).isTrue();
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
