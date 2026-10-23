package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Accion;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Catalogos;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Existentes;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Fila;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.FilaLeida;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Hoja;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Municipio;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.PuntoExistente;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Resultado;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Tipo;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.UsuarioExistente;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Portado de tests/importador_maestros_test.php, más usuarios, referencias cruzadas y columnas de contraseña (despliegue-carga, parte B). */
class ImportadorMaestrosTest {

    private static final Catalogos CAT = new Catalogos(
            Map.of("05", "Antioquia", "11", "Bogotá, D.C.", "13", "Bolívar"),
            Map.of("05001", new Municipio("05", "MEDELLÍN"), "11001", new Municipio("11", "BOGOTÁ, D.C."),
                    "13001", new Municipio("13", "CARTAGENA DE INDIAS")),
            List.of("Dispensación", "Suministro", "Consignación", "Satélite"),
            Map.of("EJC", true, "ARC", true, "FAC", true, "NA", false),
            Map.of(3L, "Regional Centro", 4L, "Regional Norte"),
            Map.of(1, "Administrador", 2, "Coordinador", 3, "Auditor", 5, "DIGSA", 6, "Sanidad", 7, "ESM", 8, "OPL nacional",
                    9, "OPL regional", 10, "Analista"));

    private static final Existentes VACIO = new Existentes(List.of(), List.of());

    /** Hoja con los encabezados de todas las filas (en orden de aparición); la primera fila de datos es la 2, como en Excel. */
    @SafeVarargs
    private static Hoja hoja(Tipo tipo, Map<String, String>... filas) {
        Set<String> encabezados = new LinkedHashSet<>();
        for (Map<String, String> f : filas) {
            encabezados.addAll(f.keySet());
        }
        List<String> cols = List.copyOf(encabezados);
        List<FilaLeida> leidas = new ArrayList<>();
        for (int i = 0; i < filas.length; i++) {
            List<String> celdas = new ArrayList<>();
            for (String c : cols) {
                celdas.add(filas[i].getOrDefault(c, ""));
            }
            leidas.add(new FilaLeida(i + 2, celdas));
        }
        return new Hoja(tipo, tipo.csv(), cols, leidas);
    }

    private static Map<String, String> f(String... claveValor) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < claveValor.length; i += 2) {
            m.put(claveValor[i], claveValor[i + 1]);
        }
        return m;
    }

    private static Map<String, String> esm(String nombre, String... mas) {
        Map<String, String> m = f("nombre", nombre, "unidad", "ESM", "fuerza", "EJC", "modalidad", "Dispensación");
        m.putAll(f(mas));
        return m;
    }

    private static Map<String, String> auditor(String nombre, String correo, String documento, String... mas) {
        Map<String, String> m = f("nombre", nombre, "correo", correo, "documento", documento, "rol", "otro");
        m.putAll(f(mas));
        return m;
    }

    private static List<Fila> con(Resultado r, Accion a) {
        return r.filas().stream().filter(x -> x.accion() == a).toList();
    }

    private static String motivos(Fila x) {
        return x.problemas().toString();
    }

    @Test
    @DisplayName("puntos del mockup v6: 3 nuevos, 1 a actualizar (nombre y unidad), 3 errores y la fila vacía ignorada")
    void puntosDelMockup() {
        Hoja h = hoja(Tipo.PUNTOS,
                f("Nombre", "ESM Importado Uno", "Unidad", "ESM", "Fuerza", "Ejército", "Modalidad", "dispensacion", "DIVIPOLA", "5001",
                        "Correos", "a@example.com; b@example.com", "Contacto", "Ana"),
                f("Nombre", "ESM Existente", "Unidad", "ESM", "Fuerza", "EJC", "Modalidad", "Suministro", "Correos", "nuevo@example.com"),
                f("Nombre", "Malo", "Unidad", "XYZ", "Fuerza", "EJC", "Correos", "no-es-correo"),
                f("Nombre", "FA Importada", "Unidad", "Farmacia alterna", "Fuerza", "ARC", "Departamento", "Bolivar",
                        "Municipio", "Cartagena de Indias", "Correos", "fa@example.com", "NIT", "900"),
                f("Nombre", "esm importado uno", "Unidad", "esm", "Fuerza", "EJC", "Modalidad", "Dispensación"),
                f("Nombre", "Nivel central", "Unidad", "NC", "Fuerza", ""),
                f("Nombre", "Sin municipio", "Unidad", "ESM", "Fuerza", "FAC", "Modalidad", "Dispensación", "DIVIPOLA", "99999"),
                f("Nombre", "", "Unidad", "", "Fuerza", ""));
        Existentes ex = new Existentes(List.of(new PuntoExistente(7, null, "ESM Existente", "ESM", "EJC", "Suministro", true)), List.of());
        Resultado r = ImportadorMaestros.validar(List.of(h), CAT, ex);
        assertThat(con(r, Accion.NUEVO)).extracting(Fila::clave).hasSize(3);
        assertThat(con(r, Accion.EXISTENTE)).singleElement().satisfies(x -> assertThat(x.id()).isEqualTo(7L));
        assertThat(con(r, Accion.ERROR)).extracting(Fila::numero).containsExactly(4, 6, 8);
        assertThat(r.filas()).as("la fila vacía (9) se ignora").extracting(Fila::numero).doesNotContain(9);

        Map<String, Object> uno = con(r, Accion.NUEVO).get(0).datos();
        assertThat(uno).containsEntry("municipio", "05001").containsEntry("modalidad", "Dispensación").containsEntry("fuerza", "EJC")
                .containsEntry("correos", List.of("a@example.com", "b@example.com"));
        Map<String, Object> fa = con(r, Accion.NUEVO).get(1).datos();
        assertThat(fa).containsEntry("unidad", "FA").containsEntry("municipio", "13001").containsEntry("nit", "900");
        assertThat(con(r, Accion.NUEVO).get(2).datos()).as("nivel central sin fuerza ⇒ NA").containsEntry("fuerza", "NA");
        Fila repetida = r.filas().stream().filter(x -> x.numero() == 6).findFirst().orElseThrow();
        assertThat(motivos(repetida)).contains("repetido en el archivo (fila 2)");
        Fila sinMunicipio = r.filas().stream().filter(x -> x.numero() == 8).findFirst().orElseThrow();
        assertThat(motivos(sinMunicipio)).contains("divipola").doesNotContain("99999");
    }

    @Test
    @DisplayName("los motivos no repiten el valor de la celda (Ley 1581)")
    void motivosSinValores() {
        Resultado r = ImportadorMaestros.validar(List.of(hoja(Tipo.AUDITORES,
                auditor("Ana Pérez", "ana@example.com", "12.345.678-x x"),
                auditor("Luis Gómez", "luis@@example", "998877"))), CAT, VACIO);
        assertThat(r.errores()).isEqualTo(2);
        assertThat(motivos(r.filas().get(0))).isEqualTo("[documento: no válido (solo letras, números, punto o guion)]");
        assertThat(motivos(r.filas().get(0))).doesNotContain("12.345");
        assertThat(r.filas().get(0).clave()).as("el informe CSV la identifica por el correo").isEqualTo("ana@example.com");
        assertThat(motivos(r.filas().get(1))).doesNotContain("luis");
    }

    @Test
    @DisplayName("auditores: QF de archivos anteriores, correo en minúscula, actualizar por correo, sin correo y repetido")
    void auditoresBasicos() {
        Hoja h = hoja(Tipo.AUDITORES,
                f("Nombre", "Auditor Excel", "Correo", "AUX@example.com", "QF", "Sí", "Documento", "1010"),
                f("Nombre", "Auditor Dos", "Correo", "auditor2@example.com", "QF", "no", "Documento", "2020"),
                f("Nombre", "Sin correo", "Correo", "", "Documento", "3030"),
                f("Nombre", "Repetido", "Correo", "aux@example.com", "Documento", "4040"));
        Existentes ex = new Existentes(List.of(), List.of(
                new UsuarioExistente(12, "auditor2", "auditor2@example.com", Set.of(Perfil.COORDINADOR), true, null, null, null)));
        Resultado r = ImportadorMaestros.validar(List.of(h), CAT, ex);
        assertThat(con(r, Accion.NUEVO)).singleElement().satisfies(x -> {
            assertThat(x.datos()).containsEntry("rol", "quimico_farmaceutico").containsEntry("correo", "aux@example.com")
                    .containsEntry("usuario", "aux");
        });
        assertThat(con(r, Accion.EXISTENTE)).singleElement().satisfies(x -> {
            assertThat(x.id()).isEqualTo(12L);
            assertThat(x.datos()).containsEntry("rol", "otro").containsEntry("usuario", "auditor2");
        });
        assertThat(con(r, Accion.ERROR)).extracting(Fila::numero).containsExactly(4, 5);
        assertThat(motivos(con(r, Accion.ERROR).get(1))).contains("repetido en el archivo (fila 2)");
    }

    @Test
    @DisplayName("rol técnico (decisión 2a) y documento obligatorio (decisión 26)")
    void rolYDocumento() {
        Resultado r = ImportadorMaestros.validar(List.of(hoja(Tipo.AUDITORES,
                auditor("Rol Regente", "r1@example.com", "111", "rol", "Regente de farmacia"),
                auditor("Rol Ing", "r2@example.com", "2222", "rol", "Ingeniero de sistemas"),
                auditor("Rol Salud", "r3@example.com", "CE-998", "rol", "Auditora en salud"),
                auditor("Rol QF", "r4@example.com", "4444", "rol", "químico farmacéutico"),
                auditor("Rol malo", "r5@example.com", "5555", "rol", "médico"),
                auditor("Sin documento", "r6@example.com", ""),
                auditor("Documento raro", "r7@example.com", "12 34"))), CAT, VACIO);
        assertThat(con(r, Accion.NUEVO)).extracting(x -> x.datos().get("rol"))
                .containsExactly("regente", "ingeniero_sistemas", "auditor_salud", "quimico_farmaceutico");
        assertThat(con(r, Accion.ERROR)).extracting(ImportadorMaestrosTest::motivos)
                .satisfiesExactly(m -> assertThat(m).contains("rol"), m -> assertThat(m).contains("documento: falta"),
                        m -> assertThat(m).contains("documento: no válido"));
        assertThat(ImportadorMaestros.rol("Q.F.")).isEmpty();
        assertThat(ImportadorMaestros.rol("Ing. de sistemas")).isEmpty();
        assertThat(ImportadorMaestros.rol("Ingeniero")).isEqualTo("ingeniero_sistemas");
        assertThat(ImportadorMaestros.rol("")).isNull();
    }

    @Test
    @DisplayName("columnas: obligatorias, desconocidas, sinónimos y columna de contraseña (se rechaza sin mirar las filas)")
    void columnas() {
        assertThat(ImportadorMaestros.revisar(Tipo.PUNTOS, List.of("Nombre", "Fuerza")).error()).contains("unidad");
        var conocida = ImportadorMaestros.revisar(Tipo.PUNTOS, List.of("Nombre del punto", "Tipo de punto", "Código municipio", "Teléfono"));
        assertThat(conocida.error()).isEmpty();
        assertThat(conocida.desconocidas()).containsExactly("Teléfono");
        assertThat(conocida.columnas()).containsValues("nombre", "unidad", "divipola");
        assertThat(ImportadorMaestros.revisar(Tipo.PUNTOS, List.of("Sigla", "Correos")).error()).as("completar por sigla").isEmpty();
        assertThat(ImportadorMaestros.revisar(Tipo.AUDITORES, List.of("nombre", "correo", "documento")).error()).contains("rol");
        assertThat(ImportadorMaestros.revisar(Tipo.AUDITORES, List.of("nombre", "correo", "documento", "QF")).error()).isEmpty();
        assertThat(ImportadorMaestros.revisar(Tipo.AUDITORES, List.of("Nombre", "Cédula", "Celular", "Profesión", "Ciudad base", "Email")).error())
                .isEmpty();
        for (String c : List.of("Contraseña", "clave", "PASSWORD", "Clave inicial", "pwd")) {
            var rev = ImportadorMaestros.revisar(Tipo.USUARIOS, List.of("nombre", "correo", "perfiles", c));
            assertThat(rev.error()).as(c).isEqualTo(ImportadorMaestros.MENSAJE_CONTRASENA);
        }
        Hoja conClave = hoja(Tipo.USUARIOS, f("nombre", "Ana Pérez", "correo", "ana@example.com", "perfiles", "DIGSA", "Contraseña", "Secreta.123"));
        Resultado r = ImportadorMaestros.validar(List.of(conClave), CAT, VACIO);
        assertThat(r.archivoRechazado()).isTrue();
        assertThat(r.filas()).isEmpty();
        assertThat(r.erroresArchivo().toString()).contains("contraseñas").doesNotContain("Secreta");
    }

    @Test
    @DisplayName("archivo vacío, más de 5000 filas y fila de ejemplo de la plantilla")
    void vacioLimiteYEjemplo() {
        assertThat(ImportadorMaestros.validar(List.of(hoja(Tipo.PUNTOS, f("nombre", " ", "unidad", ""))), CAT, VACIO).erroresArchivo())
                .containsExactly("La carga está vacía: no tiene filas con datos.");
        List<FilaLeida> muchas = new ArrayList<>();
        for (int i = 0; i <= ImportadorMaestros.MAX_FILAS; i++) {
            muchas.add(new FilaLeida(i + 2, List.of("ESM " + i, "ESM")));
        }
        Resultado grande = ImportadorMaestros.validar(List.of(new Hoja(Tipo.PUNTOS, "puntos.csv", List.of("nombre", "unidad"), muchas)), CAT, VACIO);
        assertThat(grande.erroresArchivo().toString()).contains("5000");
        Resultado ej = ImportadorMaestros.validar(List.of(hoja(Tipo.PUNTOS, esm("ESM Ejemplo Bogotá (borrar esta fila)"))), CAT, VACIO);
        assertThat(motivos(ej.filas().get(0))).contains("ejemplo");
    }

    @Test
    @DisplayName("puntos: correos repetidos, largos, NIT solo en FA, modalidad obligatoria en ESM, exige QF y nivel central")
    void reglasDePuntos() {
        Resultado r = ImportadorMaestros.validar(List.of(hoja(Tipo.PUNTOS,
                esm("ESM Dup", "correos", "a@example.com, A@example.com; a@example.com"),
                esm("á".repeat(200)),
                esm("é".repeat(201)),
                esm("ESM Dir", "direccion", "x".repeat(201)),
                f("nombre", "ESM Nit", "unidad", "ESM", "fuerza", "Armada Nacional", "modalidad", "Dispensación", "nit", "900"),
                f("nombre", "ESM Sin Modalidad", "unidad", "ESM", "fuerza", "EJC"),
                f("nombre", "FA Sin Modalidad", "unidad", "FA", "fuerza", "EJC"),
                esm("Hospital Militar Central", "exige_qf", "Sí"),
                esm("ESM Normal", "Exige químico farmacéutico", "No"),
                f("nombre", "Central con fuerza", "unidad", "NC", "fuerza", "EJC"),
                esm("ESM sin fuerza", "fuerza", "NA"),
                esm("ESM sigla mala", "sigla", "ab cd"))), CAT, VACIO);
        Map<Integer, Fila> porFila = new LinkedHashMap<>();
        r.filas().forEach(x -> porFila.put(x.numero(), x));
        assertThat(porFila.get(2).datos()).containsEntry("correos", List.of("a@example.com"));
        assertThat(porFila.get(3).accion()).as("200 con tildes pasa").isEqualTo(Accion.NUEVO);
        assertThat(motivos(porFila.get(4))).contains("nombre");
        assertThat(motivos(porFila.get(5))).contains("direccion: supera 200");
        assertThat(porFila.get(6).datos()).doesNotContainKey("nit").containsEntry("fuerza", "ARC");
        assertThat(motivos(porFila.get(7))).contains("modalidad");
        assertThat(porFila.get(8).accion()).isEqualTo(Accion.NUEVO);
        assertThat(porFila.get(9).datos()).containsEntry("exigeQf", true);
        assertThat(porFila.get(10).datos()).containsEntry("exigeQf", false);
        assertThat(porFila.get(2).datos()).as("sin dato: no borra lo marcado").doesNotContainKey("exigeQf");
        assertThat(motivos(porFila.get(11))).contains("nivel central va con fuerza NA");
        assertThat(motivos(porFila.get(12))).contains("un ESM pertenece a una fuerza");
        assertThat(motivos(porFila.get(13))).contains("sigla");
    }

    @Test
    @DisplayName("regional, se llega desde y auditor asignado por correo; municipio base de los auditores")
    void fase2() {
        Existentes ex = new Existentes(List.of(), List.of(
                new UsuarioExistente(21, "qf", "qf@example.com", Set.of(Perfil.AUDITOR), true, null, null, null),
                new UsuarioExistente(22, "inactivo", "inactivo@example.com", Set.of(Perfil.AUDITOR), false, null, null, null)));
        Resultado r = ImportadorMaestros.validar(List.of(hoja(Tipo.PUNTOS,
                esm("ESM R1", "regional", "regional centro", "llega_desde", "5001", "auditor_asignado", "QF@example.com"),
                esm("ESM R2", "regional", "Regional Sur"),
                esm("ESM R3", "llega_desde", "99999"),
                esm("ESM R4", "auditor_asignado", "otro@example.com"),
                esm("ESM R5"),
                esm("ESM R6", "auditor_asignado", "inactivo@example.com"))), CAT, ex);
        Fila p1 = r.filas().get(0);
        assertThat(p1.datos()).containsEntry("regionalId", 3L).containsEntry("llegaDesde", "05001").containsEntry("auditorAsignado", "qf@example.com");
        assertThat(con(r, Accion.ERROR)).extracting(Fila::numero).containsExactly(3, 4, 5, 7);
        assertThat(r.filas().get(4).datos()).doesNotContainKeys("regionalId", "llegaDesde", "auditorAsignado");

        Resultado a = ImportadorMaestros.validar(List.of(hoja(Tipo.AUDITORES,
                auditor("Aud B1", "b1@example.com", "1001", "municipio_base", "11001"),
                auditor("Aud B2", "b2@example.com", "1002", "municipio_base", "12345"),
                auditor("Aud B3", "b3@example.com", "1003"),
                auditor("Aud B4", "b4@example.com", "1004", "municipio_base", "Antioquia / Medellín"))), CAT, VACIO);
        assertThat(a.filas().get(0).datos()).containsEntry("municipioBase", "11001");
        assertThat(con(a, Accion.ERROR)).extracting(Fila::numero).containsExactly(3);
        assertThat(a.filas().get(2).datos().get("municipioBase")).isNull();
        assertThat(a.filas().get(3).datos()).containsEntry("municipioBase", "05001");
    }

    @Test
    @DisplayName("clave natural de los puntos: sigla; sin sigla, nombre + unidad; sigla y nombre de puntos distintos es error")
    void claveNaturalPuntos() {
        Existentes ex = new Existentes(List.of(
                new PuntoExistente(1, "BAS02", "BATALLON UNO", "ESM", "EJC", "Dispensación", true),
                new PuntoExistente(2, "DMCAL", "DISPENSARIO CALI", "ESM", "EJC", "Dispensación", true),
                new PuntoExistente(3, null, "Farmacia Sin Sigla", "FA", "ARC", null, true)), List.of());
        Resultado r = ImportadorMaestros.validar(List.of(hoja(Tipo.PUNTOS,
                f("sigla", "bas02", "correos", "esm1@example.com"),
                f("sigla", "DMCAL", "nombre", "Batallon Uno", "unidad", "ESM"),
                f("sigla", "FA-01", "nombre", "farmacia sin sigla", "unidad", "FA"),
                f("sigla", "NUEVA1", "correos", "x@example.com"),
                f("sigla", "OTRA", "nombre", "Dispensario Cali", "unidad", "ESM"))), CAT, ex);
        assertThat(r.filas().get(0).accion()).as("solo sigla y correos: actualiza por sigla").isEqualTo(Accion.EXISTENTE);
        assertThat(r.filas().get(0).id()).isEqualTo(1L);
        assertThat(r.filas().get(0).datos()).containsOnlyKeys("sigla", "correos", "filaPunto");
        assertThat(motivos(r.filas().get(1))).contains("otro punto");
        assertThat(r.filas().get(2).accion()).as("nombre + unidad de un punto sin sigla: le pone la sigla").isEqualTo(Accion.EXISTENTE);
        assertThat(r.filas().get(2).id()).isEqualTo(3L);
        assertThat(motivos(r.filas().get(3))).contains("nombre: falta");
        assertThat(motivos(r.filas().get(4))).contains("otra sigla");
    }

    @Test
    @DisplayName("usuarios: perfiles por nombre vigente o número; Administrador y Auditor no; alcance obligatorio y el que no aplica, vacío")
    void usuarios() {
        Map<Integer, String> renombrados = new LinkedHashMap<>(CAT.perfiles());
        renombrados.put(5, "Dirección DIGSA");
        Catalogos cat = new Catalogos(CAT.departamentos(), CAT.municipios(), CAT.modalidades(), CAT.fuerzas(), CAT.regionales(), renombrados);
        Existentes ex = new Existentes(List.of(new PuntoExistente(9, "DMBUG", "DISPENSARIO MEDICO BUCARAMANGA", "ESM", "EJC", "Dispensación", true)),
                List.of(new UsuarioExistente(40, "coord", "coord@example.com", Set.of(Perfil.COORDINADOR), true, null, null, null)));
        Resultado r = ImportadorMaestros.validar(List.of(hoja(Tipo.USUARIOS,
                f("nombre", "Usuario Digsa", "correo", "digsa@example.com", "perfiles", "direccion digsa"),
                f("nombre", "Usuario Sanidad", "correo", "san@example.com", "perfiles", "6", "alcance_fuerza", "Armada"),
                f("nombre", "Usuario ESM", "correo", "esm@example.com", "perfiles", "ESM", "alcance_punto", "dmbug"),
                f("nombre", "Usuario Regional", "correo", "reg@example.com", "perfiles", "OPL regional", "alcance_regional", "Regional Norte"),
                f("nombre", "Quiere Admin", "correo", "adm@example.com", "perfiles", "Administrador"),
                f("nombre", "Quiere Auditor", "correo", "aud@example.com", "perfiles", "Auditor"),
                f("nombre", "Sanidad Sin Fuerza", "correo", "san2@example.com", "perfiles", "Sanidad"),
                f("nombre", "Alcance De Mas", "correo", "mas@example.com", "perfiles", "DIGSA", "alcance_fuerza", "EJC"),
                f("nombre", "Combinacion Mala", "correo", "comb@example.com", "perfiles", "OPL nacional, Analista"),
                f("nombre", "Perfil Raro", "correo", "raro@example.com", "perfiles", "Consulta"),
                f("nombre", "Coordinador Existente", "correo", "COORD@example.com", "perfiles", "Analista", "usuario", "otro.usuario"),
                f("nombre", "Usuario Sanidad", "correo", "san@example.com", "perfiles", "DIGSA"))), cat, ex);
        Map<Integer, Fila> p = new LinkedHashMap<>();
        r.filas().forEach(x -> p.put(x.numero(), x));
        assertThat(p.get(2).datos()).containsEntry("perfiles", List.of(Perfil.DIGSA)).containsEntry("usuario", "digsa");
        assertThat(p.get(3).datos()).containsEntry("alcanceFuerza", "ARC");
        assertThat(p.get(4).datos()).containsEntry("alcancePunto", 9L);
        assertThat(p.get(5).datos()).containsEntry("alcanceRegional", 4L);
        assertThat(motivos(p.get(6))).contains(ImportadorMaestros.MENSAJE_ADMINISTRADOR);
        assertThat(motivos(p.get(7))).contains("hoja de auditores");
        assertThat(motivos(p.get(8))).contains("alcance_fuerza: el perfil Sanidad necesita la fuerza");
        assertThat(motivos(p.get(9))).contains("alcance_fuerza: no aplica");
        assertThat(motivos(p.get(10))).contains("no se combinan con los de la interventor");
        assertThat(motivos(p.get(11))).contains("no existe o está inactivo");
        assertThat(motivos(p.get(12))).as("el correo existe con otro usuario").contains("otro nombre de usuario");
        assertThat(motivos(p.get(13))).contains("repetido en el archivo (fila 3)");
    }

    @Test
    @DisplayName("referencias dentro de la misma carga: auditores, puntos y usuarios se resuelven en ese orden")
    void referenciasCruzadas() {
        Hoja auditores = hoja(Tipo.AUDITORES, auditor("QF Norte", "qf.norte@example.com", "7788", "rol", "Químico farmacéutico"));
        Hoja puntos = hoja(Tipo.PUNTOS, esm("ESM Nuevo Norte", "sigla", "ESMNN", "auditor_asignado", "qf.norte@example.com"));
        Hoja usuarios = hoja(Tipo.USUARIOS,
                f("nombre", "Jefe ESM Norte", "correo", "jefe.norte@example.com", "perfiles", "ESM", "alcance_punto", "ESMNN"),
                f("nombre", "QF Norte", "correo", "qf.norte@example.com", "perfiles", "Coordinador"));
        // El orden de las hojas en el archivo no importa
        Resultado r = ImportadorMaestros.validar(List.of(usuarios, puntos, auditores), CAT, VACIO);
        assertThat(r.ok()).as(r.filas().toString()).isTrue();
        assertThat(r.filas()).extracting(Fila::tipo).containsExactly(Tipo.AUDITORES, Tipo.PUNTOS, Tipo.USUARIOS, Tipo.USUARIOS);
        assertThat(r.de(Tipo.USUARIOS).get(0).datos()).containsEntry("alcancePuntoFila", 2);
        assertThat(r.de(Tipo.USUARIOS).get(1).accion()).as("creado en la hoja de auditores: se le agregan perfiles").isEqualTo(Accion.EXISTENTE);
        assertThat(r.de(Tipo.USUARIOS).get(1).datos()).containsEntry("usuario", "qf.norte");

        Resultado sinAuditor = ImportadorMaestros.validar(List.of(puntos), CAT, VACIO);
        assertThat(motivos(sinAuditor.filas().get(0))).contains("auditor_asignado");
    }

    @Test
    @DisplayName("usuario derivado del correo sin chocar con los existentes ni con los de la misma carga; enmascarado")
    void usuarioLibreYEnmascarado() {
        assertThat(ImportadorMaestros.usuarioLibre("Ana@x.co", Set.of())).isEqualTo("ana");
        assertThat(ImportadorMaestros.usuarioLibre("a@x.co", Set.of())).isEqualTo("a00");
        assertThat(ImportadorMaestros.usuarioLibre("ana@x.co", Set.of("ana", "ana2"))).isEqualTo("ana3");
        Resultado r = ImportadorMaestros.validar(List.of(hoja(Tipo.USUARIOS,
                f("nombre", "Ana Uno", "correo", "ana@uno.example.com", "perfiles", "DIGSA"),
                f("nombre", "Ana Dos", "correo", "ana@dos.example.com", "perfiles", "DIGSA"))), CAT,
                new Existentes(List.of(), List.of(new UsuarioExistente(1, "ana", "ana@tres.example.com", Set.of(Perfil.DIGSA), true, null, null, null))));
        assertThat(r.filas()).extracting(x -> x.datos().get("usuario")).containsExactly("ana2", "ana3");
        assertThat(ImportadorMaestros.enmascarar("1020304050")).isEqualTo("*******050");
        assertThat(ImportadorMaestros.enmascarar("12")).isEqualTo("**");
        assertThat(ImportadorMaestros.enmascarar(null)).isNull();
    }
}
