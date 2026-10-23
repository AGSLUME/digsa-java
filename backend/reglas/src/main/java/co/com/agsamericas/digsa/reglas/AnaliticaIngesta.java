package co.com.agsamericas.digsa.reglas;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reglas de la ingesta del insumo de dispensación del OPL (docs/especificaciones/analitica-ingesta): detección del delimitador y del
 * BOM, normalización de encabezados, sinónimos de campos y de valores (Fuerza, modalidad), propuesta y validación del mapeo, lectura de
 * números con coma o punto, fechas (dd/mm/aaaa, aaaa-mm-dd y serial de Excel), formato del CUM, documento del paciente para el
 * seudónimo, llave natural, catálogo cerrado de causas de rechazo y umbral de filas mal formadas. Sin Spring ni JDBC.
 *
 * <p>El insumo llega sin diccionario previo (RESP L2017, L3899): el mapeo se propone por sinónimos y el usuario lo confirma.
 */
public final class AnaliticaIngesta {

    private AnaliticaIngesta() {
    }

    /** Perfiles que crean cargues, confirman el mapeo y descargan rechazos (el coordinador solo consulta; decisión de diseño). */
    public static final Set<Perfil> PERFILES_CARGA = Set.copyOf(EnumSet.of(Perfil.ADMINISTRADOR, Perfil.ANALISTA));

    /** Delimitadores que se detectan en el encabezado; el oficial de la DIGSA es «;» (GMI P77 f). */
    public static final char[] DELIMITADORES = {';', ',', '\t', '|'};

    /** CUM tras normalizar: expediente de 1 a 9 dígitos, guion y consecutivo de 1 o 2 dígitos. */
    public static final Pattern CUM = Pattern.compile("^\\d{1,9}-\\d{1,2}$");

    /** Umbral por defecto: más del 1 % de filas con otro número de campos rechaza el archivo completo (duda 8). */
    public static final BigDecimal UMBRAL_MAL_FORMADAS = BigDecimal.ONE;

    // ---------- Campos del modelo ----------

    /** Grupo del campo en la pantalla de mapeo. */
    public enum Tipo {
        OBLIGATORIO, OPCIONAL, DESCARTE
    }

    /**
     * Campos del modelo a los que se asigna cada columna del insumo. Obligatorios según RESP L3917 (medicamento o CUM: basta uno de los
     * dos). Los de descarte son datos personales que nunca llegan a la base (A5 4.7.3; Ley 1581).
     */
    public enum Campo {
        PACIENTE("paciente", "Identificador del paciente", "identificador del paciente", Tipo.OBLIGATORIO,
                "DOC PACIENTE", "DOCUMENTO PACIENTE", "DOCUMENTO", "NUM DOC", "NUMERO DOC", "NUMERO DOCUMENTO", "NRO DOCUMENTO", "NO DOCUMENTO",
                "IDENTIFICACION", "NUMERO IDENTIFICACION", "ID PACIENTE", "PACIENTE", "CEDULA", "DOC USUARIO", "ID USUARIO",
                "DOCUMENTO USUARIO", "AFILIADO", "ID AFILIADO", "DOCUMENTO AFILIADO", "NUM ID", "NUMERO ID", "IDENTIFICACION PACIENTE"),
        TIPO_DOCUMENTO("tipoDocumento", "Tipo de documento", "tipo de documento", Tipo.OPCIONAL,
                "TIPO DOC", "TIPO DOCUMENTO", "TIPO ID", "TIPO IDENTIFICACION", "TD", "TIPO DOC PACIENTE", "TIPO DOCUMENTO PACIENTE"),
        FUERZA("fuerza", "Fuerza", "Fuerza", Tipo.OBLIGATORIO, "FUERZA", "FZA", "FUERZA MILITAR", "COD FUERZA", "CODIGO FUERZA", "SUBSISTEMA"),
        ESM("esm", "ESM", "ESM", Tipo.OBLIGATORIO, "ESM", "COD ESM", "CODIGO ESM", "ESM CODIGO", "SIGLA ESM", "ESTABLECIMIENTO",
                "ESTABLECIMIENTO SANIDAD", "ESTABLECIMIENTO DE SANIDAD MILITAR", "PUNTO", "PUNTO DISPENSACION", "COD PUNTO", "DISPENSARIO",
                "NOMBRE ESM", "ESM DISPENSACION"),
        MODALIDAD("modalidad", "Modalidad", "modalidad", Tipo.OBLIGATORIO, "MODALIDAD", "MOD", "MODALIDAD DISPENSACION",
                "MODALIDAD SUMINISTRO", "TIPO DISPENSACION", "MODALIDAD DE DISPENSACION"),
        FECHA("fecha", "Fecha de dispensación", "fecha de dispensación", Tipo.OBLIGATORIO, "FECHA", "FECHA DISPENSACION",
                "FECHA DE DISPENSACION", "FECHA ENTREGA", "FECHA DE ENTREGA", "F DISP", "FEC DISP", "FECHA DISP", "FEC DISPENSACION",
                "FECHA SUMINISTRO", "F ENTREGA", "FEC ENTREGA"),
        MEDICAMENTO("medicamento", "Medicamento (descripción)", "medicamento o CUM", Tipo.OBLIGATORIO, "MEDICAMENTO", "DESC MED",
                "DESC MEDICAMENTO", "DESCRIPCION MEDICAMENTO", "DESCRIPCION", "NOMBRE MEDICAMENTO", "PRODUCTO", "NOMBRE PRODUCTO",
                "DESCRIPCION PRODUCTO", "MEDICAMENTO DESCRIPCION"),
        CUM("cum", "CUM", "medicamento o CUM", Tipo.OBLIGATORIO, "CUM", "CODIGO CUM", "COD CUM", "CUM MEDICAMENTO", "EXPEDIENTE CUM"),
        CANTIDAD("cantidad", "Cantidad", "cantidad", Tipo.OBLIGATORIO, "CANTIDAD", "CANT", "CANTIDAD DISPENSADA", "CANTIDAD ENTREGADA",
                "UNIDADES", "CANT DISPENSADA", "CANT ENTREGADA"),
        VALOR("valor", "Valor total", "valor", Tipo.OBLIGATORIO, "VALOR", "VALOR TOTAL", "VR TOTAL", "VLR TOTAL", "COSTO TOTAL",
                "VALOR DISPENSADO", "TOTAL", "VALOR NETO", "VR DISPENSADO"),
        PRINCIPIO_ACTIVO("principioActivo", "Principio activo", "principio activo", Tipo.OPCIONAL, "PRINCIPIO ACTIVO", "PRINCIPIO",
                "DCI", "P ACTIVO", "PRINCIPIOS ACTIVOS"),
        ATC("atc", "ATC", "ATC", Tipo.OPCIONAL, "ATC", "CODIGO ATC", "COD ATC"),
        CIE10("cie10", "CIE-10", "CIE-10", Tipo.OPCIONAL, "CIE10", "CIE 10", "CIE", "DIAGNOSTICO", "DX", "COD DIAGNOSTICO",
                "CODIGO DIAGNOSTICO", "DIAGNOSTICO PRINCIPAL", "COD DX", "DX PRINCIPAL"),
        VALOR_UNITARIO("valorUnitario", "Valor unitario", "valor unitario", Tipo.OPCIONAL, "VALOR UNITARIO", "VR UNITARIO",
                "VLR UNITARIO", "PRECIO UNITARIO", "COSTO UNITARIO", "VR UNIT", "VALOR UNIT"),
        CODIGO_SSFM("codigoSsfm", "Código SSFM", "código SSFM", Tipo.OPCIONAL, "CODIGO SSFM", "SSFM", "COD SSFM"),
        PROFESIONAL("profesional", "Profesional (se seudonimiza)", "profesional", Tipo.OPCIONAL, "PROFESIONAL", "MEDICO",
                "DOC MEDICO", "MEDICO TRATANTE", "PRESCRIPTOR", "ID PROFESIONAL", "DOCUMENTO PROFESIONAL", "REGISTRO MEDICO"),
        ESPECIALIDAD("especialidad", "Especialidad", "especialidad", Tipo.OPCIONAL, "ESPECIALIDAD", "ESPECIALIDAD MEDICO"),
        TIPO_FORMULA("tipoFormula", "Tipo de fórmula", "tipo de fórmula", Tipo.OPCIONAL, "TIPO FORMULA", "TIPO DE FORMULA",
                "TIPO PRESCRIPCION"),
        NUMERO_FORMULA("numeroFormula", "N.º de fórmula", "número de fórmula", Tipo.OPCIONAL, "NUM FORMULA", "NUMERO FORMULA",
                "N FORMULA", "NO FORMULA", "NRO FORMULA", "FORMULA", "NUMERO PRESCRIPCION"),
        ID_DISPENSACION("idDispensacion", "Id de dispensación", "id de dispensación", Tipo.OPCIONAL, "ID DISPENSACION",
                "NUMERO DISPENSACION", "CONSECUTIVO DISPENSACION", "ID REGISTRO", "CONSECUTIVO"),
        ITEM("item", "Ítem de la dispensación", "ítem", Tipo.OPCIONAL, "ITEM", "NUM ITEM", "LINEA FORMULA", "RENGLON"),
        REGIONAL("regional", "Regional", "regional", Tipo.OPCIONAL, "REGIONAL", "COD REGIONAL", "NOMBRE REGIONAL"),
        ANIO_NACIMIENTO("anioNacimiento", "Año de nacimiento", "año de nacimiento", Tipo.OPCIONAL, "ANIO NACIMIENTO", "ANO NACIMIENTO",
                "ANO NAC", "ANIO NAC"),
        FECHA_NACIMIENTO("fechaNacimiento", "Fecha de nacimiento (solo se guarda el año)", "fecha de nacimiento", Tipo.OPCIONAL,
                "FECHA NACIMIENTO", "FECHA NAC", "FEC NAC", "FECHA DE NACIMIENTO", "F NAC"),
        GRUPO_EDAD("grupoEdad", "Grupo de edad", "grupo de edad", Tipo.OPCIONAL, "GRUPO EDAD", "GRUPO ETARIO", "RANGO EDAD", "EDAD",
                "GRUPO DE EDAD"),
        DESCARTAR("descartar", "Se descarta (dato personal)", "dato personal", Tipo.DESCARTE, "NOMBRE", "NOMBRES", "APELLIDO", "APELLIDOS",
                "NOMBRE PACIENTE", "NOMBRES PACIENTE", "NOMBRE COMPLETO", "PRIMER NOMBRE", "SEGUNDO NOMBRE", "PRIMER APELLIDO",
                "SEGUNDO APELLIDO", "DIRECCION", "DIRECCION PACIENTE", "TELEFONO", "CELULAR", "CORREO", "EMAIL", "E MAIL",
                "CORREO ELECTRONICO", "NOMBRE USUARIO", "NOMBRE AFILIADO"),
        IGNORAR("ignorar", "No se usa", "columna sin uso", Tipo.DESCARTE);

        private final String clave;
        private final String etiqueta;
        private final String enMensaje;
        private final Tipo tipo;
        private final List<String> sinonimos;

        Campo(String clave, String etiqueta, String enMensaje, Tipo tipo, String... sinonimos) {
            this.clave = clave;
            this.etiqueta = etiqueta;
            this.enMensaje = enMensaje;
            this.tipo = tipo;
            this.sinonimos = List.of(sinonimos);
        }

        public String clave() {
            return clave;
        }

        public String etiqueta() {
            return etiqueta;
        }

        /** Nombre del campo dentro de los mensajes del mapeo («La columna de valor está asignada dos veces»). */
        public String enMensaje() {
            return enMensaje;
        }

        public Tipo tipo() {
            return tipo;
        }

        public List<String> sinonimos() {
            return sinonimos;
        }

        /** ¿Llega a staging? Los de descarte nunca. */
        public boolean seGuarda() {
            return tipo != Tipo.DESCARTE;
        }

        public static Optional<Campo> deClave(String clave) {
            return Arrays.stream(values()).filter(c -> c.clave.equals(clave)).findFirst();
        }
    }

    /** Obligatorios que se exigen uno a uno (medicamento o CUM se revisa aparte: basta uno). */
    static final List<Campo> OBLIGATORIOS_SIMPLES = List.of(Campo.PACIENTE, Campo.FUERZA, Campo.ESM, Campo.MODALIDAD, Campo.FECHA,
            Campo.CANTIDAD, Campo.VALOR);

    private static final Map<String, Campo> POR_SINONIMO = sinonimos();

    private static Map<String, Campo> sinonimos() {
        Map<String, Campo> m = new HashMap<>();
        for (Campo c : Campo.values()) {
            for (String s : c.sinonimos) {
                Campo previo = m.put(normalizarEncabezado(s), c);
                if (previo != null && previo != c) {
                    throw new IllegalStateException("Sinónimo repetido: " + s);
                }
            }
        }
        return Map.copyOf(m);
    }

    // ---------- Encabezado y mapeo ----------

    /** Quita el BOM de UTF-8 si encabeza el texto. */
    public static String sinBom(String texto) {
        return texto != null && !texto.isEmpty() && texto.charAt(0) == '﻿' ? texto.substring(1) : texto;
    }

    /**
     * Delimitador del archivo según la línea del encabezado: el que más aparece fuera de comillas entre «;», «,», tabulador y «|».
     * Sin ninguno (una sola columna) o en empate se toma «;», el oficial.
     */
    public static char detectarDelimitador(String encabezado) {
        int[] cuenta = new int[DELIMITADORES.length];
        boolean comillas = false;
        for (int i = 0; i < encabezado.length(); i++) {
            char ch = encabezado.charAt(i);
            if (ch == '"') {
                comillas = !comillas;
            } else if (!comillas) {
                for (int d = 0; d < DELIMITADORES.length; d++) {
                    if (ch == DELIMITADORES[d]) {
                        cuenta[d]++;
                    }
                }
            }
        }
        int mejor = 0;
        for (int d = 1; d < DELIMITADORES.length; d++) {
            if (cuenta[d] > cuenta[mejor]) {
                mejor = d;
            }
        }
        return DELIMITADORES[mejor];
    }

    /** Nombre del delimitador para la fuente del cargue: «Archivo CSV (;) UTF-8». */
    public static String nombreDelimitador(char d) {
        return d == '\t' ? "tab" : String.valueOf(d);
    }

    /** Encabezado normalizado: mayúsculas, sin tildes, signos como espacio y espacios simples («F. DISP.» ⇒ «F DISP»). */
    public static String normalizarEncabezado(String encabezado) {
        if (encabezado == null) {
            return "";
        }
        String rapido = normalizarAscii(encabezado);
        if (rapido != null) {
            return rapido;
        }
        String s = Normalizer.normalize(sinBom(encabezado), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        s = s.replace('Ñ', 'N');
        return s.replaceAll("[^A-Z0-9]+", " ").strip();
    }

    /** Camino rápido de la normalización para texto ASCII (el caso de casi todas las celdas): sin Normalizer ni expresiones regulares. */
    private static String normalizarAscii(String t) {
        StringBuilder sb = new StringBuilder(t.length());
        boolean espacio = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c > 127) {
                return null;
            }
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                if (espacio && sb.length() > 0) {
                    sb.append(' ');
                }
                espacio = false;
                sb.append(c);
            } else {
                espacio = true;
            }
        }
        return sb.toString();
    }

    /** Huella de un encabezado (para reconocer la plantilla de mapeo de un archivo con las mismas columnas). */
    public static String firmaEncabezado(List<String> encabezados) {
        return sha256(String.join("|", encabezados.stream().map(AnaliticaIngesta::normalizarEncabezado).toList()));
    }

    /** Encabezados repetidos tras normalizar (rechazo estructural). */
    public static List<String> repetidos(List<String> encabezados) {
        Map<String, Integer> vistos = new LinkedHashMap<>();
        List<String> rep = new ArrayList<>();
        for (String e : encabezados) {
            String n = normalizarEncabezado(e);
            if (n.isEmpty()) {
                continue;
            }
            if (vistos.merge(n, 1, Integer::sum) == 2) {
                rep.add(e);
            }
        }
        return rep;
    }

    /**
     * Posiciones (desde 1) de las columnas cuyo encabezado repite uno anterior tras normalizar. El mensaje del rechazo usa las posiciones
     * y nunca los valores: si la primera fila fuera de datos, un documento o un nombre llegaría a la bitácora.
     */
    public static List<Integer> posicionesRepetidas(List<String> encabezados) {
        Set<String> vistos = new java.util.HashSet<>();
        List<Integer> rep = new ArrayList<>();
        for (int i = 0; i < encabezados.size(); i++) {
            String n = normalizarEncabezado(encabezados.get(i));
            if (!n.isEmpty() && !vistos.add(n)) {
                rep.add(i + 1);
            }
        }
        return rep;
    }

    /** Motivo del rechazo por encabezados repetidos: «Encabezados repetidos (columnas 3, 7)». */
    public static String motivoRepetidos(List<Integer> posiciones) {
        return Causa.ENCABEZADOS_REPETIDOS.texto() + " (columnas " + String.join(", ", posiciones.stream().map(String::valueOf).toList()) + ")";
    }

    /** ¿Alguna celda de la primera fila corresponde a un campo del modelo? Si ninguna, no es un encabezado reconocible. */
    public static boolean encabezadoReconocido(List<String> encabezados) {
        return encabezados.stream().anyMatch(e -> campoDe(e).isPresent());
    }

    /**
     * Encabezados que se guardan cuando la primera fila no se reconoce: «Columna 1», «Columna 2»... La primera fila puede ser de datos
     * (documento, nombre) y no debe quedar en el cargue, la plantilla ni la exportación.
     */
    public static List<String> encabezadosGenericos(int columnas) {
        List<String> l = new ArrayList<>(columnas);
        for (int i = 1; i <= columnas; i++) {
            l.add("Columna " + i);
        }
        return l;
    }

    /** Campos que tratan un dato personal: se seudonimizan, se reducen al año o se descartan antes de staging. */
    public static final Set<Campo> CAMPOS_PERSONALES = Set.copyOf(EnumSet.of(Campo.PACIENTE, Campo.PROFESIONAL, Campo.FECHA_NACIMIENTO,
            Campo.DESCARTAR));

    /** ¿El encabezado es de un dato personal (documento, profesional, nombre, contacto o fecha de nacimiento) según los sinónimos? */
    public static boolean columnaPersonal(String encabezado) {
        return campoDe(encabezado).filter(CAMPOS_PERSONALES::contains).isPresent();
    }

    /**
     * ¿El valor de la columna puede verse en claro en la vista previa del mapeo? Solo si se asigna a un campo no personal que se guarda y
     * su encabezado no es de un dato personal. Todo lo demás (personales, «No se usa», columnas sin reconocer) va enmascarado.
     */
    public static boolean visibleEnVistaPrevia(String encabezado, Campo asignado) {
        return asignado != null && asignado.seGuarda() && !CAMPOS_PERSONALES.contains(asignado) && !columnaPersonal(encabezado);
    }

    /**
     * Problemas de privacidad del mapeo: una columna cuyo encabezado es de un dato personal solo puede ir a «Se descarta», «No se usa» o
     * a un campo que se seudonimiza o se reduce (paciente, profesional, fecha de nacimiento). Si no, su valor quedaría en claro en un
     * campo de texto de los hechos (Ley 1581). El mensaje nombra la columna (un encabezado reconocido, no un dato).
     */
    public static List<String> problemasPrivacidad(List<String> encabezados, List<Campo> mapeo) {
        List<String> p = new ArrayList<>();
        for (int i = 0; i < Math.min(encabezados.size(), mapeo.size()); i++) {
            Campo c = mapeo.get(i);
            if (c != null && c != Campo.IGNORAR && !CAMPOS_PERSONALES.contains(c) && columnaPersonal(encabezados.get(i))) {
                p.add("La columna «" + encabezados.get(i) + "» tiene datos personales: asígnela a «Se descarta (dato personal)», «No se usa» o a un "
                        + "campo que se seudonimiza");
            }
        }
        return p;
    }

    // ---------- Subida por partes ----------

    /** Partes en que se sube un archivo: ceil(tamaño / parte máxima). */
    public static long partesEsperadas(long tamano, long parteMax) {
        return tamano <= 0 || parteMax <= 0 ? 0 : (tamano + parteMax - 1) / parteMax;
    }

    /** Tamaño exacto de la parte {@code numero} (desde 1): la parte máxima, salvo la última, que lleva el resto. */
    public static long tamanoParte(int numero, long partes, long tamano, long parteMax) {
        return numero < partes ? parteMax : tamano - (partes - 1) * parteMax;
    }

    /** Campo que corresponde a un encabezado: primero por sinónimo exacto, luego por palabras clave; si no, vacío. */
    public static Optional<Campo> campoDe(String encabezado) {
        String n = normalizarEncabezado(encabezado);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        Campo exacto = POR_SINONIMO.get(n);
        if (exacto != null) {
            return Optional.of(exacto);
        }
        Campo sinEspacios = POR_SINONIMO.get(n.replace(" ", ""));
        if (sinEspacios != null) {
            return Optional.of(sinEspacios);
        }
        Set<String> p = Set.of(n.split(" "));
        if (p.contains("APELLIDO") || p.contains("APELLIDOS") || p.contains("TELEFONO") || p.contains("CELULAR") || p.contains("CORREO")
                || p.contains("EMAIL") || p.contains("DIRECCION")
                || (p.contains("NOMBRE") || p.contains("NOMBRES")) && (p.contains("PACIENTE") || p.contains("USUARIO") || p.contains("AFILIADO"))) {
            return Optional.of(Campo.DESCARTAR);
        }
        if ((p.contains("FECHA") || p.contains("FEC") || p.contains("F")) && (p.contains("NAC") || p.contains("NACIMIENTO"))) {
            return Optional.of(Campo.FECHA_NACIMIENTO);
        }
        if ((p.contains("FECHA") || p.contains("FEC")) && (p.contains("DISP") || p.contains("DISPENSACION") || p.contains("ENTREGA"))) {
            return Optional.of(Campo.FECHA);
        }
        if (p.contains("CUM")) {
            return Optional.of(Campo.CUM);
        }
        if (p.contains("ATC")) {
            return Optional.of(Campo.ATC);
        }
        if (p.contains("CIE10") || p.contains("CIE") || p.contains("DIAGNOSTICO")) {
            return Optional.of(Campo.CIE10);
        }
        if ((p.contains("VALOR") || p.contains("VR") || p.contains("VLR") || p.contains("PRECIO") || p.contains("COSTO"))
                && (p.contains("UNITARIO") || p.contains("UNIT"))) {
            return Optional.of(Campo.VALOR_UNITARIO);
        }
        if ((p.contains("VALOR") || p.contains("VR") || p.contains("VLR")) && p.contains("TOTAL")) {
            return Optional.of(Campo.VALOR);
        }
        if (p.contains("ESM")) {
            return Optional.of(Campo.ESM);
        }
        if ((p.contains("DOC") || p.contains("DOCUMENTO") || p.contains("IDENTIFICACION")) && (p.contains("PACIENTE") || p.contains("USUARIO")
                || p.contains("AFILIADO"))) {
            return Optional.of(Campo.PACIENTE);
        }
        return Optional.empty();
    }

    /**
     * Mapeo propuesto: un campo por columna (IGNORAR si no se reconoce). Cada campo que se guarda se asigna a la primera columna que
     * lo reconoce; las de descarte pueden repetirse.
     */
    public static List<Campo> proponerMapeo(List<String> encabezados) {
        List<Campo> mapeo = new ArrayList<>();
        Set<Campo> usados = EnumSet.noneOf(Campo.class);
        for (String e : encabezados) {
            Campo c = campoDe(e).orElse(Campo.IGNORAR);
            if (c.seGuarda() && !usados.add(c)) {
                c = Campo.IGNORAR;
            }
            mapeo.add(c);
        }
        return mapeo;
    }

    /**
     * Problemas del mapeo que impiden confirmarlo, con el mensaje para el usuario: campos asignados a dos columnas y obligatorios sin
     * asignar («Falta asignar la columna de cantidad»). Vacío = se puede confirmar.
     */
    public static List<String> problemasMapeo(List<Campo> mapeo) {
        List<String> p = new ArrayList<>();
        Set<Campo> usados = EnumSet.noneOf(Campo.class);
        for (Campo c : mapeo) {
            if (c != null && c.seGuarda() && !usados.add(c)) {
                p.add("La columna de " + c.enMensaje + " está asignada dos veces");
            }
        }
        for (Campo c : OBLIGATORIOS_SIMPLES) {
            if (!usados.contains(c)) {
                p.add("Falta asignar la columna de " + c.enMensaje);
            }
        }
        if (!usados.contains(Campo.MEDICAMENTO) && !usados.contains(Campo.CUM)) {
            p.add("Falta asignar la columna de medicamento o CUM");
        }
        return p;
    }

    /** ¿El insumo trae id de dispensación? Entonces la llave natural es (fuente, id, ítem) (duda 4). */
    public static boolean llavePorId(List<Campo> mapeo) {
        return mapeo.contains(Campo.ID_DISPENSACION);
    }

    // ---------- Valores ----------

    private static final Pattern NUMERO_CANONICO = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern MILES_PUNTO = Pattern.compile("^-?\\d{1,3}(\\.\\d{3})+$");
    private static final Pattern MILES_COMA = Pattern.compile("^-?\\d{1,3}(,\\d{3})+$");

    /**
     * Número del insumo. Con «;» la coma es decimal («1234,50»; un «1.234,50» con puntos de miles también se acepta); con «,» el punto
     * es decimal. Quita espacios y el signo «$». Vacío si no es un número.
     */
    public static Optional<BigDecimal> numero(String texto, boolean comaDecimal) {
        if (texto == null) {
            return Optional.empty();
        }
        String s = texto.strip().replace("$", "").replace(" ", "").replace(" ", "");
        if (s.isEmpty()) {
            return Optional.empty();
        }
        if (comaDecimal) {
            if (s.indexOf(',') >= 0) {
                s = s.replace(".", "").replace(',', '.');
            } else if (MILES_PUNTO.matcher(s).matches()) {
                s = s.replace(".", "");
            }
        } else if (s.indexOf(',') >= 0) {
            if (s.indexOf('.') >= 0 || MILES_COMA.matcher(s).matches()) {
                s = s.replace(",", "");
            } else {
                s = s.replace(',', '.'); // «12,5» en un archivo con «,» solo llega entre comillas: se toma como decimal
            }
        }
        if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (!NUMERO_CANONICO.matcher(s).matches()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(s));
    }

    /** Texto canónico del número para staging («1234.5»), o el texto original si no es un número (la validación lo rechaza). */
    public static String numeroCanonico(String texto, boolean comaDecimal) {
        return numero(texto, comaDecimal).map(BigDecimal::toPlainString).orElse(texto == null ? null : texto.strip());
    }

    private static final List<DateTimeFormatter> FORMATOS_FECHA = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
    private static final Pattern SERIAL_EXCEL = Pattern.compile("^\\d{5}(\\.\\d+)?$");
    private static final LocalDate ORIGEN_EXCEL = LocalDate.of(1899, 12, 30);

    /**
     * Fecha del insumo: dd/mm/aaaa (o con «-» o «.»), aaaa-mm-dd, aaaa/mm/dd, aaaammdd o el serial de Excel (días desde 1899-12-30, entre
     * 1955 y 2173); se ignora la hora si viene. Estricta: «31/02/2025» no es fecha.
     */
    public static Optional<LocalDate> fecha(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String s = texto.strip();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        if (SERIAL_EXCEL.matcher(s).matches()) {
            long dias = (long) Double.parseDouble(s);
            return dias >= 20_000 && dias <= 99_999 ? Optional.of(ORIGEN_EXCEL.plusDays(dias)) : Optional.empty();
        }
        int corte = s.indexOf('T') > 0 ? s.indexOf('T') : s.indexOf(' ');
        if (corte > 0) {
            s = s.substring(0, corte);
        }
        for (DateTimeFormatter f : FORMATOS_FECHA) {
            try {
                return Optional.of(LocalDate.parse(s, f));
            } catch (DateTimeParseException e) {
                // siguiente formato
            }
        }
        return Optional.empty();
    }

    /** Fecha canónica para staging (aaaa-mm-dd), o el texto original si no es fecha (la validación lo rechaza). */
    public static String fechaCanonica(String texto) {
        return fecha(texto).map(LocalDate::toString).orElse(texto == null ? null : texto.strip());
    }

    /** CUM normalizado: sin espacios ni puntos, en mayúsculas («19901234 - 1» ⇒ «19901234-1»). */
    public static String normalizarCum(String texto) {
        if (texto == null) {
            return null;
        }
        String s = texto.strip().replace(" ", "").replace(".", "").toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    public static boolean cumValido(String cumNormalizado) {
        return cumNormalizado != null && CUM.matcher(cumNormalizado).matches();
    }

    /** Código normalizado (ESM, ATC, CIE-10, SSFM): mayúsculas, sin espacios; en CIE-10 y ATC también sin puntos ni guiones. */
    public static String normalizarCodigo(String texto, boolean sinSignos) {
        if (texto == null) {
            return null;
        }
        String s = Normalizer.normalize(texto.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).replace(" ", "");
        if (sinSignos) {
            s = s.replace(".", "").replace("-", "");
        }
        return s.isEmpty() ? null : s;
    }

    /** Texto libre recortado; vacío ⇒ null. */
    public static String texto(String t) {
        if (t == null) {
            return null;
        }
        String s = t.strip();
        return s.isEmpty() ? null : s;
    }

    private static final Map<String, String> FUERZAS = Map.ofEntries(
            Map.entry("EJC", "EJC"), Map.entry("EJERCITO", "EJC"), Map.entry("EJERCITO NACIONAL", "EJC"),
            Map.entry("EJERCITO NACIONAL DE COLOMBIA", "EJC"), Map.entry("EJERCITO NACIONAL DE COLOMBIA EJC", "EJC"), Map.entry("EJE", "EJC"),
            Map.entry("ARC", "ARC"), Map.entry("ARMADA", "ARC"), Map.entry("ARMADA NACIONAL", "ARC"),
            Map.entry("ARMADA DE LA REPUBLICA DE COLOMBIA", "ARC"), Map.entry("ARMADA DE LA REPUBLICA DE COLOMBIA ARC", "ARC"),
            Map.entry("FAC", "FAC"), Map.entry("FUERZA AEREA", "FAC"), Map.entry("FUERZA AEREA COLOMBIANA", "FAC"),
            Map.entry("FUERZA AEROESPACIAL", "FAC"), Map.entry("FUERZA AEROESPACIAL COLOMBIANA", "FAC"),
            Map.entry("FUERZA AEROESPACIAL COLOMBIANA FAC", "FAC"), Map.entry("NA", "NA"), Map.entry("NO APLICA", "NA"));

    /**
     * Fuerza por código o nombre con sinónimos («Ejército», «EJERCITO», «EJC» ⇒ EJC). Lo que no se reconoce vuelve normalizado, y la
     * validación lo compara con par_fuerzas (una fuerza nueva del catálogo se reconoce por su código).
     */
    public static String fuerza(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String n = normalizarEncabezado(texto);
        return FUERZAS.getOrDefault(n, n.replace(" ", ""));
    }

    private static final Map<String, String> MODALIDADES = Map.ofEntries(
            Map.entry("DISPENSACION", "Dispensación"), Map.entry("DISP", "Dispensación"), Map.entry("D", "Dispensación"),
            Map.entry("SUMINISTRO", "Suministro"), Map.entry("SUM", "Suministro"), Map.entry("S", "Suministro"),
            Map.entry("CONSIGNACION", "Consignación"), Map.entry("CONS", "Consignación"),
            Map.entry("SATELITE", "Satélite"), Map.entry("SAT", "Satélite"));

    /** Modalidad con el nombre de par_modalidades («DISPENSACION» ⇒ «Dispensación»); lo que no se reconoce vuelve recortado. */
    public static String modalidad(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return MODALIDADES.getOrDefault(normalizarEncabezado(texto), texto.strip());
    }

    // ---------- Documento para el seudónimo ----------

    /**
     * Documento normalizado que entra al HMAC: «TIPO|NUMERO» sin puntos, espacios ni guiones («CC 1.023.456» = «CC1023456» =
     * tipo «cc» + «1023456»). Si no hay columna de tipo, las letras iniciales del número se toman como tipo. Vacío si no hay número.
     */
    public static String documento(String tipo, String numero) {
        String num = numero == null ? "" : normalizarEncabezado(numero).replace(" ", "");
        String t = tipo == null ? "" : normalizarEncabezado(tipo).replace(" ", "");
        if (t.isEmpty()) {
            int i = 0;
            while (i < num.length() && Character.isLetter(num.charAt(i))) {
                i++;
            }
            if (i > 0 && i < num.length()) {
                t = num.substring(0, i);
                num = num.substring(i);
            }
        }
        return num.isEmpty() ? "" : t + "|" + num;
    }

    /** Año de nacimiento a partir de la fecha completa (que se descarta); vacío si no es fecha. */
    public static Optional<Integer> anioNacimiento(String fechaNacimiento) {
        return fecha(fechaNacimiento).map(LocalDate::getYear);
    }

    // ---------- Llave natural y huella de la fila ----------

    /**
     * Huella de 128 bits (SHA-256 truncado, en forma de UUID) de una lista de valores. Para la fila completa detecta el duplicado exacto;
     * para (fuente, id, ítem), la llave natural.
     */
    public static String huella(List<String> valores) {
        StringBuilder sb = new StringBuilder();
        for (String v : valores) {
            sb.append(v == null ? "\u0000" : v).append('\u001F');
        }
        byte[] h = digest(sb.toString());
        String hex = HEX.formatHex(h, 0, 16);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-"
                + hex.substring(20, 32);
    }

    /**
     * Llave natural del registro (duda 4): con id de dispensación, (fuente, id, ítem); sin él, la fila completa de la fuente (solo se
     * rechazan duplicados exactos). La fuente separa archivo y servicio web.
     */
    public static String llaveNatural(String fuente, String idDispensacion, String item, List<String> filaNormalizada) {
        if (idDispensacion != null && !idDispensacion.isBlank()) {
            return huella(List.of(fuente, "id", idDispensacion.strip(), item == null ? "" : item.strip()));
        }
        List<String> v = new ArrayList<>(filaNormalizada.size() + 1);
        v.add(fuente);
        v.addAll(filaNormalizada);
        return huella(v);
    }

    // ---------- Causas de rechazo ----------

    /** Catálogo cerrado de causas (código que se guarda ⇒ texto que se muestra). */
    public enum Causa {
        OBLIGATORIO_VACIO("obligatorio_vacio", "Obligatorio vacío"),
        FECHA_INVALIDA("fecha_invalida", "Fecha inválida"),
        FECHA_FUERA_PERIODO("fecha_fuera_periodo", "Fecha fuera del periodo declarado o futura"),
        NUMERO_INVALIDO("numero_invalido", "Número inválido"),
        CANTIDAD_NO_POSITIVA("cantidad_no_positiva", "Cantidad menor o igual a cero"),
        VALOR_NEGATIVO("valor_negativo", "Valor menor que cero"),
        CUM_INVALIDO("cum_invalido", "CUM con formato inválido"),
        FUERZA_NO_RECONOCIDA("fuerza_no_reconocida", "Fuerza no reconocida"),
        MODALIDAD_NO_RECONOCIDA("modalidad_no_reconocida", "Modalidad no reconocida"),
        DUPLICADO_EXACTO("duplicado_exacto", "Duplicado exacto"),
        DUPLICADO_LLAVE("duplicado_llave", "Duplicado por llave natural"),
        YA_CARGADO("ya_cargado", "Ya cargado en otro cargue aplicado"),
        CAMPOS_DISTINTOS("campos_distintos", "Número de campos distinto del encabezado"),
        // Estructura: rechazan el archivo completo
        ARCHIVO_VACIO("archivo_vacio", "Archivo vacío o sin encabezado"),
        CODIFICACION("codificacion", "Codificación distinta de UTF-8"),
        ENCABEZADOS_REPETIDOS("encabezados_repetidos", "Encabezados repetidos"),
        HOJAS_DISTINTAS("hojas_distintas", "Las hojas del Excel no tienen el mismo encabezado"),
        FORMATO("formato", "Formato de archivo no aceptado");

        private final String codigo;
        private final String texto;

        Causa(String codigo, String texto) {
            this.codigo = codigo;
            this.texto = texto;
        }

        public String codigo() {
            return codigo;
        }

        public String texto() {
            return texto;
        }

        public static Optional<Causa> deCodigo(String codigo) {
            return Arrays.stream(values()).filter(c -> c.codigo.equals(codigo)).findFirst();
        }
    }

    /** Texto de una causa para el CSV de rechazos: «Duplicado exacto de la línea 50», «Obligatorio vacío». */
    public static String textoCausa(String codigo, String referencia) {
        String t = Causa.deCodigo(codigo).map(Causa::texto).orElse(codigo);
        if (referencia != null && !referencia.isBlank()
                && (Causa.DUPLICADO_EXACTO.codigo.equals(codigo) || Causa.DUPLICADO_LLAVE.codigo.equals(codigo))) {
            return t + " de la línea " + referencia;
        }
        return t;
    }

    /**
     * Motivo agrupado para la bitácora: «CUM con formato inválido (241), Fecha inválida (71)», de mayor a menor y luego por texto.
     * Vacío si no hubo rechazos.
     */
    public static String motivo(Map<String, Long> conteos) {
        return conteos.entrySet().stream().filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(e -> Causa.deCodigo(e.getKey()).map(Causa::texto).orElse(e.getKey())))
                .map(e -> Causa.deCodigo(e.getKey()).map(Causa::texto).orElse(e.getKey()) + " (" + e.getValue() + ")")
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** ¿Las filas mal formadas superan el umbral (en %) de las leídas? Más del umbral rechaza el archivo completo. */
    public static boolean excedeUmbral(long malFormadas, long leidas, BigDecimal umbralPorcentaje) {
        if (malFormadas <= 0 || leidas <= 0) {
            return false;
        }
        return BigDecimal.valueOf(malFormadas).multiply(BigDecimal.valueOf(100)).compareTo(umbralPorcentaje.multiply(BigDecimal.valueOf(leidas))) > 0;
    }

    /** Motivo del rechazo estructural por columnas corridas: «Número de campos distinto del encabezado (60000; primera línea 18)». */
    public static String motivoCamposDistintos(long cuantas, String primeraLinea) {
        return Causa.CAMPOS_DISTINTOS.texto() + " (" + cuantas + "; primera línea " + primeraLinea + ")";
    }

    /** Responsable del cargue en la bitácora: «jgarcia · analista» o «Proceso automático · API OPL (usuario)». */
    public static String responsable(String usuario, Set<Perfil> perfiles, boolean servicioWeb) {
        if (servicioWeb) {
            return "Proceso automático · API OPL (" + usuario + ")";
        }
        String perfil = perfiles.contains(Perfil.ANALISTA) ? "analista" : perfiles.contains(Perfil.ADMINISTRADOR) ? "administrador" : "usuario";
        return usuario + " · " + perfil;
    }

    /** Fuente legible del cargue: «Archivo CSV (;) UTF-8», «Archivo xlsx», «API OPL · dispensación». */
    public static String fuenteTexto(String formato, char delimitador) {
        return switch (formato) {
            case "xlsx" -> "Archivo xlsx";
            case "json" -> "API OPL · dispensación";
            case "txt" -> "Archivo TXT (" + nombreDelimitador(delimitador) + ") UTF-8";
            default -> "Archivo CSV (" + nombreDelimitador(delimitador) + ") UTF-8";
        };
    }

    /** Formato por la extensión del nombre: csv, txt, xlsx, gz o zip; vacío si no se acepta (xls, xlsm, pdf...). */
    public static Optional<String> formatoDe(String nombre) {
        if (nombre == null) {
            return Optional.empty();
        }
        String n = nombre.strip().toLowerCase(Locale.ROOT);
        for (String f : List.of("csv", "txt", "xlsx", "gz", "zip")) {
            if (n.endsWith("." + f)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    private static final java.util.HexFormat HEX = java.util.HexFormat.of();
    /** Un MessageDigest por hilo: huella() y llaveNatural() corren dos veces por fila (2 M filas por cargue). */
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no tiene SHA-256", e);
        }
    });

    private static String sha256(String texto) {
        return HEX.formatHex(digest(texto));
    }

    private static byte[] digest(String texto) {
        MessageDigest md = SHA256.get();
        md.reset();
        return md.digest(texto.getBytes(StandardCharsets.UTF_8));
    }
}
