package co.com.agsamericas.digsa.plataforma;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plantilla preestablecida de un correo (módulo Correos, fase C2): asunto y cuerpo con variables {@code {{nombre}}}. El texto de
 * fábrica vive en el código del módulo que envía y produce el mismo html de antes, byte a byte. El administrador puede editar el
 * asunto y el cuerpo de las editables ({@link CatalogoPlantillas}); las variables obligatorias no se pueden quitar.
 *
 * <p>Todo valor se escapa al insertarlo, salvo las variables {@link Variable#html} (tablas y listas que arma el código, ya
 * escapadas). En formato {@link Formato#TEXTO} el cuerpo es texto plano: se escapa entero y los saltos de línea pasan a {@code <br>}.
 *
 * @param clave       identificador (el origen de correos_salida cuando es uno a uno)
 * @param nombre      nombre visible
 * @param modulo      módulo que la envía (para agrupar en la pantalla)
 * @param formato     cómo se arma el html
 * @param editable    false para las que llevan un código o enlace de un solo uso (MFA, contraseña, código del OPL)
 * @param titulo      encabezado del marco (con variables; no editable), o null en {@link Formato#SIN_MARCO}
 * @param asunto      asunto de fábrica
 * @param cuerpo      cuerpo de fábrica
 * @param variables   variables que puede usar (con su descripción y un valor de ejemplo para la vista previa)
 */
public record Plantilla(String clave, String nombre, String modulo, Formato formato, boolean editable, String titulo, String asunto, String cuerpo,
                        List<Variable> variables) {

    /** Cómo se arma el html del cuerpo. */
    public enum Formato {
        /** Html restringido dentro del marco común. */
        HTML,
        /** Texto plano dentro del marco común, en un párrafo. */
        TEXTO,
        /** Html restringido sin el marco (así salía antes; se conserva para no cambiar el correo). */
        SIN_MARCO
    }

    /**
     * Variable de una plantilla.
     *
     * @param nombre       como se escribe entre llaves
     * @param descripcion  qué trae
     * @param ejemplo      valor ficticio para la vista previa (html si {@code html})
     * @param obligatoria  no se puede quitar del cuerpo al editar
     * @param html         trae html ya escapado (se inserta tal cual); solo en formatos html
     */
    public record Variable(String nombre, String descripcion, String ejemplo, boolean obligatoria, boolean html) {

        public static Variable de(String nombre, String descripcion, String ejemplo) {
            return new Variable(nombre, descripcion, ejemplo, false, false);
        }

        public static Variable obligatoria(String nombre, String descripcion, String ejemplo) {
            return new Variable(nombre, descripcion, ejemplo, true, false);
        }

        /** Bloque de html armado por el código (tabla, lista o párrafo condicional): obligatorio. */
        public static Variable bloque(String nombre, String descripcion, String ejemplo) {
            return new Variable(nombre, descripcion, ejemplo, true, true);
        }
    }

    /** Asunto y html listos para enviar; {@code texto} es el cuerpo en texto plano (solo en formato TEXTO, si no null). */
    public record Renderizada(String asunto, String html, String texto) {
    }

    static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    public Plantilla {
        variables = List.copyOf(variables);
    }

    /** La misma plantilla con otro asunto y cuerpo (la versión editada). */
    public Plantilla con(String otroAsunto, String otroCuerpo) {
        return new Plantilla(clave, nombre, modulo, formato, editable, titulo, otroAsunto, otroCuerpo, variables);
    }

    /** Arma el correo con estos valores (los que faltan quedan vacíos). */
    public Renderizada render(Map<String, String> valores) {
        String a = sinSaltos(reemplazar(asunto, valores, false));
        if (formato == Formato.TEXTO) {
            String texto = reemplazar(cuerpo, valores, false);
            return new Renderizada(a, PlantillaCorreo.marco(reemplazar(titulo, valores, false), "<p>" + PlantillaCorreo.texto(texto) + "</p>"), texto);
        }
        String html = reemplazar(cuerpo, valores, true);
        return new Renderizada(a, formato == Formato.SIN_MARCO ? html : PlantillaCorreo.marco(reemplazar(titulo, valores, false), html), null);
    }

    /** Con los valores de ejemplo de cada variable (vista previa con datos ficticios). */
    public Renderizada ejemplo() {
        java.util.HashMap<String, String> v = new java.util.HashMap<>();
        variables.forEach(x -> v.put(x.nombre(), x.ejemplo()));
        return render(v);
    }

    private String reemplazar(String plantilla, Map<String, String> valores, boolean escapar) {
        if (plantilla == null) {
            return "";
        }
        Matcher m = VARIABLE.matcher(plantilla);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            Variable v = variable(m.group(1));
            String valor = valores.get(m.group(1));
            valor = valor == null ? "" : valor;
            m.appendReplacement(b, Matcher.quoteReplacement(escapar && (v == null || !v.html()) ? PlantillaCorreo.e(valor) : valor));
        }
        m.appendTail(b);
        return b.toString();
    }

    Variable variable(String nombre) {
        return variables.stream().filter(v -> v.nombre().equals(nombre)).findFirst().orElse(null);
    }

    static String sinSaltos(String s) {
        return s.replaceAll("[\\r\\n\\t]+", " ");
    }

    /** Errores de una versión editada (vacía si se puede guardar). */
    public List<String> validar(String nuevoAsunto, String nuevoCuerpo) {
        List<String> errores = new ArrayList<>();
        if (nuevoAsunto == null || nuevoAsunto.isBlank()) {
            errores.add("Escriba el asunto.");
        } else if (nuevoAsunto.length() > 200) {
            errores.add("El asunto tiene máximo 200 caracteres.");
        } else if (!sinSaltos(nuevoAsunto).equals(nuevoAsunto)) {
            errores.add("El asunto va en una sola línea.");
        }
        if (nuevoCuerpo == null || nuevoCuerpo.isBlank()) {
            errores.add("Escriba el texto del correo.");
            return errores;
        }
        if (nuevoCuerpo.length() > 10_000) {
            errores.add("El texto tiene máximo 10.000 caracteres.");
        }
        Set<String> desconocidas = new LinkedHashSet<>();
        for (String texto : new String[] {nuevoAsunto == null ? "" : nuevoAsunto, nuevoCuerpo}) {
            Matcher m = VARIABLE.matcher(texto);
            while (m.find()) {
                if (variable(m.group(1)) == null) {
                    desconocidas.add(m.group(1));
                }
            }
        }
        if (!desconocidas.isEmpty()) {
            errores.add("Estas variables no existen en esta plantilla: " + String.join(", ", desconocidas.stream().map(x -> "{{" + x + "}}").toList()) + ".");
        }
        Set<String> enCuerpo = usadas(nuevoCuerpo);
        Set<String> enAsunto = usadas(nuevoAsunto == null ? "" : nuevoAsunto);
        List<String> faltan = new ArrayList<>();
        for (Variable v : variables) {
            // Una obligatoria se protege donde está en el texto de fábrica (cuerpo o asunto)
            if (v.obligatoria() && ((usadas(cuerpo).contains(v.nombre()) && !enCuerpo.contains(v.nombre()))
                    || (usadas(asunto).contains(v.nombre()) && !enAsunto.contains(v.nombre())))) {
                faltan.add("{{" + v.nombre() + "}}");
            }
        }
        if (!faltan.isEmpty()) {
            errores.add("Faltan variables protegidas (no se pueden quitar): " + String.join(", ", faltan) + ".");
        }
        if (formato != Formato.TEXTO) {
            errores.addAll(HtmlRestringido.validar(nuevoCuerpo, this));
        } else if (usadas(nuevoAsunto == null ? "" : nuevoAsunto).stream().map(this::variable).anyMatch(v -> v != null && v.html())) {
            errores.add("Un bloque no puede ir en el asunto.");
        }
        return errores;
    }

    static Set<String> usadas(String texto) {
        Set<String> r = new LinkedHashSet<>();
        Matcher m = VARIABLE.matcher(texto);
        while (m.find()) {
            r.add(m.group(1));
        }
        return r;
    }

    /** Html que admite el cuerpo editado: etiquetas de texto y tablas, estilos simples y enlaces solo a variables. */
    static final class HtmlRestringido {

        private static final Set<String> ETIQUETAS = Set.of("p", "br", "strong", "b", "em", "i", "u", "ul", "ol", "li", "table", "tr", "th", "td", "a",
                "span");
        private static final Pattern ETIQUETA = Pattern.compile("<(/?)([a-zA-Z0-9]+)((?:\\s+[a-zA-Z-]+(?:=\"[^\"<>]*\")?)*)\\s*/?>");
        private static final Pattern ATRIBUTO = Pattern.compile("\\s+([a-zA-Z-]+)(?:=\"([^\"<>]*)\")?");
        private static final Pattern ESTILO = Pattern.compile("[a-zA-Z0-9#:;%.,\\- ]*");
        private static final Pattern ENTIDAD = Pattern.compile("&(?:[a-zA-Z]+|#[0-9]+|#x[0-9a-fA-F]+);");

        private HtmlRestringido() {
        }

        static List<String> validar(String html, Plantilla p) {
            Set<String> errores = new LinkedHashSet<>();
            Matcher m = ETIQUETA.matcher(html);
            int desde = 0;
            while (m.find()) {
                texto(html.substring(desde, m.start()), errores);
                desde = m.end();
                String etiqueta = m.group(2).toLowerCase(Locale.ROOT);
                if (!ETIQUETAS.contains(etiqueta)) {
                    errores.add("La etiqueta <" + etiqueta + "> no se admite en el correo.");
                    continue;
                }
                Matcher a = ATRIBUTO.matcher(m.group(3));
                while (a.find()) {
                    String nombre = a.group(1).toLowerCase(Locale.ROOT);
                    String valor = a.group(2) == null ? "" : a.group(2);
                    switch (nombre) {
                        case "style" -> {
                            String bajo = valor.toLowerCase(Locale.ROOT);
                            if (!ESTILO.matcher(valor).matches() || bajo.contains("url") || bajo.contains("expression")) {
                                errores.add("El estilo «" + valor + "» no se admite (solo colores, tamaños y bordes).");
                            }
                        }
                        case "border", "cellpadding" -> {
                            if (!valor.matches("[0-9]{1,2}")) {
                                errores.add("El atributo " + nombre + " lleva un número.");
                            }
                        }
                        case "href" -> {
                            Matcher v = VARIABLE.matcher(valor);
                            if (!v.matches() || p.variable(v.group(1)) == null || p.variable(v.group(1)).html()) {
                                errores.add("Los enlaces solo pueden apuntar a una variable de la plantilla (por ejemplo {{enlace}}).");
                            }
                        }
                        default -> errores.add("El atributo " + nombre + " no se admite en el correo.");
                    }
                }
            }
            texto(html.substring(desde), errores);
            return new ArrayList<>(errores);
        }

        private static void texto(String t, Set<String> errores) {
            if (t.indexOf('<') >= 0) {
                errores.add("Hay un «<» suelto o una etiqueta mal escrita: escriba &lt; si quiere mostrar el signo.");
            }
            Matcher m = Pattern.compile("&").matcher(t);
            while (m.find()) {
                Matcher ent = ENTIDAD.matcher(t);
                if (!ent.find(m.start()) || ent.start() != m.start()) {
                    errores.add("Escriba el signo & como &amp;.");
                    return;
                }
            }
        }
    }
}
