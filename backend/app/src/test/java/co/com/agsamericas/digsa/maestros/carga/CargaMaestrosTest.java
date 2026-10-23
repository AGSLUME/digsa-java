package co.com.agsamericas.digsa.maestros.carga;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.identidad.Usuario;
import co.com.agsamericas.digsa.maestros.carga.CargaMaestrosCli.Opciones;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Carga inicial de maestros por línea de comandos (despliegue-carga, parte B), contra PostgreSQL 16 real. */
class CargaMaestrosTest extends PruebaIntegracion {

    @Autowired
    CargaMaestrosCli cli;
    @Autowired
    DataSource fuente;

    @TempDir
    Path carpeta;

    private String admin;
    private String sufijo;

    @BeforeEach
    void preparar() {
        admin = crearUsuario("admcarga", Perfil.ADMINISTRADOR);
        sufijo = unico("c").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Opciones opciones(String modo, String enlaces) {
        return new Opciones(null, carpeta, admin, modo, enlaces, 0);
    }

    private void csv(String nombre, String... lineas) throws IOException {
        Files.writeString(carpeta.resolve(nombre), "﻿" + String.join("\r\n", lineas) + "\r\n", StandardCharsets.UTF_8);
    }

    private String correo(String quien) {
        return quien + "." + sufijo + "@example.com";
    }

    private String sigla() {
        return ("T" + sufijo).toUpperCase(Locale.ROOT).substring(0, Math.min(20, sufijo.length() + 1));
    }

    /** Tres hojas que se referencian entre sí: el ESM nuevo tiene como auditor al de la hoja y el usuario ESM ese mismo punto. */
    private void cargaCompleta() throws IOException {
        csv("auditores.csv", "nombre;correo;documento;telefono;rol;municipio_base",
                "Auditora Carga Uno;" + correo("qf") + ";1000000001;3000000001;Químico farmacéutico;11001",
                "Auditor Carga Dos;" + correo("reg") + ";1000000002;;Regente de farmacia;Antioquia / Medellín");
        csv("puntos.csv", "sigla;nombre;unidad;fuerza;modalidad;divipola;correos;exige_qf;regional;auditor_asignado",
                sigla() + ";ESM Carga " + sufijo + ";ESM;EJC;Dispensación;11001;esm." + sufijo + "@example.com;Sí;Bogotá;" + correo("qf"));
        csv("usuarios.csv", "nombre;correo;perfiles;alcance_fuerza;alcance_punto;alcance_regional",
                "Usuario Sanidad Carga;" + correo("san") + ";Sanidad;Ejército;;",
                "Usuario ESM Carga;" + correo("esm") + ";ESM;;" + sigla() + ";",
                "Usuario Regional Carga;" + correo("regional") + ";OPL regional;;;Cali");
    }

    private long cuenta(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long correosA(String direccion) {
        return correos().stream().filter(m -> m.para().contains(direccion)).count();
    }

    private Path informe() throws IOException {
        try (Stream<Path> s = Files.list(carpeta)) {
            return s.filter(p -> p.getFileName().toString().startsWith("resultado_carga_")).sorted().reduce((a, b) -> b).orElseThrow();
        }
    }

    @Test
    @DisplayName("simular (por defecto) valida todo, informa por fila y no escribe nada; solo deja el resumen simulado")
    void simularNoEscribe() throws IOException {
        cargaCompleta();
        long usuariosAntes = cuenta("SELECT count(*) FROM usuarios");
        long puntosAntes = cuenta("SELECT count(*) FROM puntos");
        int correosAntes = correos().size();
        assertThat(cli.ejecutar(new Opciones(null, carpeta, admin, "simular", "enviar", 0))).isZero();
        assertThat(cuenta("SELECT count(*) FROM usuarios")).isEqualTo(usuariosAntes);
        assertThat(cuenta("SELECT count(*) FROM puntos")).isEqualTo(puntosAntes);
        assertThat(correos()).hasSize(correosAntes);
        String inf = Files.readString(informe(), StandardCharsets.UTF_8);
        assertThat(inf).startsWith("﻿archivo;hoja;fila;clave;accion;campos;motivo").contains(correo("qf") + ";nuevo").contains(sigla() + ";nuevo");
        assertThat(bitacoraDe(idDe(admin))).anySatisfy(f -> assertThat(f).startsWith("consulta|carga_maestros_simulada|")
                .contains("nuevos 6").contains("auditores.csv sha256="));
    }

    @Test
    @DisplayName("aplicar crea auditores, puntos y usuarios con sus referencias y envía la bienvenida; la segunda corrida no cambia nada")
    void aplicarYRepetir() throws IOException {
        cargaCompleta();
        assertThat(cli.ejecutar(opciones("aplicar", "enviar"))).isZero();

        Usuario qf = usuarios.porUsuarioOCorreo(correo("qf")).orElseThrow();
        assertThat(qf.perfiles()).containsExactly(Perfil.AUDITOR);
        assertThat(qf.activo()).isTrue();
        assertThat(qf.claveDefinida()).as("contraseña aleatoria que nadie conoce").isFalse();
        assertThat(jdbc.sql("SELECT rol || '|' || municipio_base FROM auditores WHERE usuario_id = :id").param("id", qf.id()).query(String.class)
                .single()).isEqualTo("quimico_farmaceutico|11001");
        assertThat(jdbc.sql("SELECT municipio_base FROM auditores a JOIN usuarios u ON u.id = a.usuario_id WHERE u.correo = :c")
                .param("c", correo("reg")).query(String.class).single()).isEqualTo("05001");
        long punto = jdbc.sql("SELECT id FROM puntos WHERE sigla = :s AND auditor_asignado = :a AND exige_qf AND modalidad = 'Dispensación'")
                .param("s", sigla()).param("a", qf.id()).query(Long.class).single();
        Usuario esm = usuarios.porUsuarioOCorreo(correo("esm")).orElseThrow();
        assertThat(esm.alcancePunto()).isEqualTo(punto);
        assertThat(usuarios.porUsuarioOCorreo(correo("san")).orElseThrow().alcanceFuerza()).isEqualTo("EJC");
        assertThat(usuarios.porUsuarioOCorreo(correo("regional")).orElseThrow().alcanceRegional()).isNotNull();
        for (String q : List.of("qf", "reg", "san", "esm", "regional")) {
            Correo.Mensaje m = ultimoCorreoA(correo(q));
            assertThat(m.asunto()).isEqualTo("Bienvenido a la Suite DIGSA");
            assertThat(m.html()).contains("definir-clave?tkn=");
        }
        long adminId = idDe(admin);
        assertThat(bitacoraDe(adminId)).anySatisfy(f -> assertThat(f).startsWith("insercion|carga_maestros|").contains("nuevos 6"))
                .anySatisfy(f -> assertThat(f).startsWith("insercion|punto_creado|"))
                .anySatisfy(f -> assertThat(f).startsWith("insercion|auditor_creado|"))
                .noneSatisfy(f -> assertThat(f).contains("1000000001").contains("3000000001"));

        // Segunda corrida: todo «sin cambios», sin correos y solo el resumen en la bitácora
        int correosAntes = correos().size();
        int filasBitacora = bitacoraDe(adminId).size();
        long usuariosAntes = cuenta("SELECT count(*) FROM usuarios");
        assertThat(cli.ejecutar(opciones("aplicar", "enviar"))).isZero();
        assertThat(correos()).hasSize(correosAntes);
        assertThat(cuenta("SELECT count(*) FROM usuarios")).isEqualTo(usuariosAntes);
        assertThat(bitacoraDe(adminId)).hasSize(filasBitacora + 1);
        assertThat(bitacoraDe(adminId).getFirst()).contains("nuevos 0, actualizados 0, sin cambios 6");
    }

    @Test
    @DisplayName("una sola fila con error rechaza toda la carga: nada escrito, ningún correo, código 3 y el informe con el motivo")
    void unErrorRechazaTodo() throws IOException {
        cargaCompleta();
        csv("usuarios.csv", "nombre;correo;perfiles;alcance_fuerza;alcance_punto;alcance_regional",
                "Usuario Sanidad Carga;" + correo("san") + ";Sanidad;Ejército;;",
                "Usuario Admin Carga;" + correo("adm") + ";Administrador;;;");
        long usuariosAntes = cuenta("SELECT count(*) FROM usuarios");
        long puntosAntes = cuenta("SELECT count(*) FROM puntos");
        int correosAntes = correos().size();
        assertThat(cli.ejecutar(opciones("aplicar", "enviar"))).isEqualTo(3);
        assertThat(cuenta("SELECT count(*) FROM usuarios")).isEqualTo(usuariosAntes);
        assertThat(cuenta("SELECT count(*) FROM puntos")).isEqualTo(puntosAntes);
        assertThat(correos()).hasSize(correosAntes);
        assertThat(Files.readString(informe(), StandardCharsets.UTF_8))
                .contains("usuarios;3;" + correo("adm") + ";error;;perfiles: Los administradores se crean con --digsa.crear-admin.");
        assertThat(bitacoraDe(idDe(admin)).getFirst()).startsWith("insercion|carga_maestros|").contains("rechazada, sin escribir");
    }

    @Test
    @DisplayName("el responsable debe ser un administrador activo (código 1, sin leer los archivos)")
    void responsableNoValido() {
        String coordinador = crearUsuario("coordcarga", Perfil.COORDINADOR);
        assertThat(cli.ejecutar(new Opciones(null, carpeta, coordinador, "simular", "enviar", 0))).isEqualTo(1);
        assertThat(cli.ejecutar(new Opciones(null, carpeta, admin, "borrar", "enviar", 0))).as("modo no válido").isEqualTo(1);
        assertThat(cli.ejecutar(opciones("simular", "enviar"))).as("carpeta sin archivos").isEqualTo(1);
    }

    @Test
    @DisplayName("archivo rechazado sin mirar las filas: columna de contraseña, CSV que no es UTF-8, .xlsm y los dos formatos")
    void archivosRechazados() throws IOException {
        csv("usuarios.csv", "nombre;correo;perfiles;Contraseña", "Ana Prueba;" + correo("ana") + ";DIGSA;NoDebeSalir.2026");
        assertThat(cli.ejecutar(opciones("simular", "enviar"))).isEqualTo(3);
        String inf = Files.readString(informe(), StandardCharsets.UTF_8);
        assertThat(inf).contains("Las plantillas no llevan contraseñas").doesNotContain("NoDebeSalir");
        Files.delete(carpeta.resolve("usuarios.csv"));
        Files.delete(informe());

        Files.write(carpeta.resolve("puntos.csv"), "nombre;unidad\r\nESM Bogotá;ESM\r\n".getBytes(Charset.forName("windows-1252")));
        assertThat(cli.ejecutar(opciones("simular", "enviar"))).isEqualTo(3);
        assertThat(Files.readString(informe(), StandardCharsets.UTF_8)).contains("Guarde el archivo como CSV UTF-8");
        Files.delete(carpeta.resolve("puntos.csv"));

        Files.writeString(carpeta.resolve("libro.xlsm"), "x");
        assertThat(cli.ejecutar(opciones("simular", "enviar"))).isEqualTo(3);
        Files.delete(carpeta.resolve("libro.xlsm"));

        csv("puntos.csv", "nombre;unidad", "ESM Uno;ESM");
        Files.writeString(carpeta.resolve("plantilla.xlsx"), "x");
        assertThat(cli.ejecutar(opciones("simular", "enviar"))).as("Excel y CSV a la vez").isEqualTo(1);
    }

    @Test
    @DisplayName("plantillas: hojas, listas de la base (fuerzas, regionales, 1 122 municipios), validación del rol y fila de ejemplo")
    void plantillas() throws IOException {
        Path destino = carpeta.resolve("plantillas");
        assertThat(cli.ejecutar(new Opciones(destino, null, null, "simular", "enviar", 0))).isZero();
        try (InputStream in = Files.newInputStream(destino.resolve(GeneradorPlantillas.XLSX)); XSSFWorkbook libro = new XSSFWorkbook(in)) {
            assertThat(List.of(libro.getSheetName(0), libro.getSheetName(1), libro.getSheetName(2), libro.getSheetName(3), libro.getSheetName(4)))
                    .containsExactly("Instrucciones", "Auditores", "Puntos", "Usuarios", "Listas");
            Sheet listas = libro.getSheet("Listas");
            Row enc = listas.getRow(0);
            int fuerzas = -1;
            int divipola = -1;
            int roles = -1;
            int perfiles = -1;
            for (int i = 0; i < enc.getLastCellNum(); i++) {
                switch (enc.getCell(i).getStringCellValue()) {
                    case "Fuerzas" -> fuerzas = i;
                    case "DIVIPOLA" -> divipola = i;
                    case "Roles" -> roles = i;
                    case "Perfiles" -> perfiles = i;
                    default -> {
                    }
                }
            }
            assertThat(columna(listas, fuerzas)).contains("EJC", "ARC", "FAC", "NA");
            assertThat(columna(listas, divipola)).hasSize(1122);
            assertThat(columna(listas, roles)).hasSize(5);
            assertThat(columna(listas, perfiles)).doesNotContain("Administrador", "Auditor");
            XSSFSheet aud = libro.getSheet("Auditores");
            assertThat(aud.getDataValidations()).anySatisfy(v -> {
                assertThat(v.getRegions().getCellRangeAddress(0).getFirstColumn()).isEqualTo(4); // columna rol
                assertThat(v.getValidationConstraint().getFormula1()).startsWith("Listas!");
                assertThat(v.getErrorStyle()).isEqualTo(DataValidation.ErrorStyle.STOP);
            });
            for (String h : List.of("Auditores", "Puntos", "Usuarios")) {
                Row r0 = libro.getSheet(h).getRow(0);
                for (int i = 0; i < r0.getLastCellNum(); i++) {
                    assertThat(r0.getCell(i).getStringCellValue()).doesNotContainIgnoringCase("clave").doesNotContainIgnoringCase("contrase");
                }
            }
        }
        assertThat(Files.readString(destino.resolve("csv").resolve("usuarios.csv"), StandardCharsets.UTF_8))
                .startsWith("﻿nombre;correo;usuario;documento;telefono;perfiles;");
        // Las plantillas CSV del repositorio (herramientas/plantillas) son las mismas que genera la herramienta
        Path repo = Path.of("..", "..", "herramientas", "plantillas");
        for (String csv : List.of("auditores.csv", "puntos.csv", "usuarios.csv")) {
            assertThat(Files.readString(repo.resolve(csv), StandardCharsets.UTF_8)).as(csv)
                    .isEqualTo(Files.readString(destino.resolve("csv").resolve(csv), StandardCharsets.UTF_8));
        }
        // La plantilla sin diligenciar: las filas de ejemplo son error
        Files.copy(destino.resolve(GeneradorPlantillas.XLSX), carpeta.resolve("carga.xlsx"));
        assertThat(cli.ejecutar(opciones("simular", "enviar"))).isEqualTo(3);
        assertThat(Files.readString(informe(), StandardCharsets.UTF_8)).contains("fila de ejemplo");
    }

    private static List<String> columna(Sheet s, int c) {
        List<String> v = new java.util.ArrayList<>();
        for (int i = 1; i <= s.getLastRowNum(); i++) {
            Row r = s.getRow(i);
            if (r != null && r.getCell(c) != null && !r.getCell(c).getStringCellValue().isBlank()) {
                v.add(r.getCell(c).getStringCellValue());
            }
        }
        return v;
    }

    @Test
    @DisplayName("Excel: se lee en flujo; números como texto, fórmulas por su valor (con aviso) y completa un punto existente por sigla")
    void excelYCompletarPorSigla() throws IOException {
        String s = sigla();
        long id = jdbc.sql("""
                INSERT INTO puntos (sigla, nombre, unidad, fuerza, modalidad, municipio, direccion, horario)
                VALUES (:s, :n, 'ESM', 'EJC', 'Dispensación', '05001', 'Calle 1', '24 HORAS') RETURNING id""")
                .param("s", s).param("n", "ESM Anexo " + sufijo).query(Long.class).single();
        try (XSSFWorkbook libro = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(carpeta.resolve("carga.xlsx"))) {
            Sheet aud = libro.createSheet("Auditores");
            fila(aud, 0, "Nombre", "Correo", "Documento", "Rol", "Municipio base");
            Row r = fila(aud, 1, "Auditor Excel", correo("xls"), null, "Regente", null);
            r.createCell(2).setCellValue(52345678d); // documento numérico
            r.createCell(4).setCellFormula("\"11\"&\"001\""); // fórmula: se usa el valor calculado
            libro.setForceFormulaRecalculation(true);
            libro.getCreationHelper().createFormulaEvaluator().evaluateAll();
            Sheet pun = libro.createSheet("Puntos");
            fila(pun, 0, "Sigla", "Correos", "Auditor asignado");
            fila(pun, 1, s.toLowerCase(Locale.ROOT), "a." + sufijo + "@example.com; b." + sufijo + "@example.com", correo("xls"));
            libro.createSheet("Notas");
            libro.write(out);
        }
        assertThat(cli.ejecutar(opciones("aplicar", "no"))).isZero();
        Usuario u = usuarios.porUsuarioOCorreo(correo("xls")).orElseThrow();
        assertThat(u.documento()).isEqualTo("52345678");
        assertThat(jdbc.sql("SELECT municipio_base FROM auditores WHERE usuario_id = :id").param("id", u.id()).query(String.class).single())
                .isEqualTo("11001");
        var p = jdbc.sql("SELECT nombre, direccion, horario, array_to_string(correos, ',') AS correos, auditor_asignado FROM puntos WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(p).containsEntry("nombre", "ESM Anexo " + sufijo).containsEntry("direccion", "Calle 1").containsEntry("horario", "24 HORAS")
                .containsEntry("correos", "a." + sufijo + "@example.com,b." + sufijo + "@example.com").containsEntry("auditor_asignado", u.id());
        assertThat(correosA(correo("xls"))).as("--digsa.carga.enlaces=no").isZero();
        String inf = Files.readString(informe(), StandardCharsets.UTF_8);
        assertThat(inf).contains("fórmula").contains("Notas").contains(s + ";actualizar;correos, auditorAsignado");

        // Enlaces diferidos: solo a los activos que nunca definieron su contraseña
        // 4 si otra prueba dejó un usuario pendiente con un correo que el servidor rechaza
        assertThat(cli.ejecutar(new Opciones(null, null, admin, "simular", "pendientes", 0))).isIn(0, 4);
        assertThat(correosA(correo("xls"))).isEqualTo(1);
        assertThat(correosA(admin + "@example.com")).as("ya definió su contraseña").isZero();
    }

    private static Row fila(Sheet s, int n, String... valores) {
        Row r = s.createRow(n);
        for (int i = 0; i < valores.length; i++) {
            if (valores[i] != null) {
                r.createCell(i).setCellValue(valores[i]);
            }
        }
        return r;
    }

    @Test
    @DisplayName("usuario existente: se agregan perfiles sin quitar los que tenía; correo con otro usuario es error")
    void usuarioExistente() throws IOException {
        String analista = crearUsuario("analcarga", Perfil.ANALISTA);
        csv("usuarios.csv", "nombre;correo;usuario;perfiles;telefono",
                "Persona Analista;" + analista + "@example.com;;Coordinador;3001234567");
        assertThat(cli.ejecutar(opciones("aplicar", "enviar"))).isZero();
        Usuario u = usuarios.porUsuario(analista).orElseThrow();
        assertThat(u.perfiles()).containsExactlyInAnyOrder(Perfil.ANALISTA, Perfil.COORDINADOR);
        assertThat(u.telefono()).isEqualTo("3001234567");
        assertThat(correosA(analista + "@example.com")).as("a un usuario que ya existía no se le envía nada").isZero();
        assertThat(Files.readString(informe(), StandardCharsets.UTF_8)).contains("perfiles que conserva: Analista");
        assertThat(bitacoraDe(idDe(admin))).anySatisfy(f -> assertThat(f).contains("usuario_editado").contains("*******567")
                .doesNotContain("3001234567"));

        csv("usuarios.csv", "nombre;correo;usuario;perfiles", "Persona Analista;" + analista + "@example.com;otro." + sufijo + ";DIGSA");
        assertThat(cli.ejecutar(opciones("simular", "enviar"))).isEqualTo(3);
    }

    @Test
    @DisplayName("un correo de bienvenida que no sale no deshace la carga: código 4 y el informe lo marca")
    void correoNoEnviado() throws IOException {
        String rechazado = "rechazo." + sufijo + "@rechazo.invalid";
        csv("usuarios.csv", "nombre;correo;perfiles", "Usuario Bueno;" + correo("bueno") + ";DIGSA", "Usuario Rechazado;" + rechazado + ";DIGSA");
        assertThat(cli.ejecutar(opciones("aplicar", "enviar"))).isEqualTo(4);
        assertThat(usuarios.porUsuarioOCorreo(rechazado)).isPresent();
        assertThat(correosA(correo("bueno"))).isEqualTo(1);
        assertThat(Files.readString(informe(), StandardCharsets.UTF_8)).contains(rechazado + ";correo no enviado");
    }

    @Test
    @DisplayName("dos cargas a la vez no se cruzan: la segunda termina con 2 sin escribir")
    void otraCargaEnCurso() throws Exception {
        csv("usuarios.csv", "nombre;correo;perfiles", "Usuario Candado;" + correo("candado") + ";DIGSA");
        try (Connection c = fuente.getConnection(); Statement st = c.createStatement()) {
            st.execute("SELECT pg_advisory_lock(" + ServicioCargaMaestros.CANDADO + ")");
            try {
                assertThat(cli.ejecutar(opciones("aplicar", "enviar"))).isEqualTo(2);
            } finally {
                st.execute("SELECT pg_advisory_unlock(" + ServicioCargaMaestros.CANDADO + ")");
            }
        }
        assertThat(usuarios.porUsuarioOCorreo(correo("candado"))).isEmpty();
    }

    @Test
    @DisplayName("la herramienta arranca sin puerto ni tareas diarias")
    void argumentos() {
        assertThat(co.com.agsamericas.digsa.DigsaApplication.argumentos(new String[] {"--digsa.carga.carpeta=/carga"}))
                .contains("--server.port=-1", "--digsa.opl.cron=-", "--digsa.seguimiento.cron=-");
        assertThat(co.com.agsamericas.digsa.DigsaApplication.argumentos(new String[] {"--digsa.crear-admin.correo=a@example.com"})).hasSize(1);
    }
}
