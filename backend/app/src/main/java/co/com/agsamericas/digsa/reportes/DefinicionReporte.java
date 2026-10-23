package co.com.agsamericas.digsa.reportes;

import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Definición de un reporte del catálogo. Se valida al construirla: un id fuera del patrón, un filtro repetido o reservado, o un
 * reporte GENERAL con una columna de dato personal (RESPUESTAS l.629 y l.2209) impiden el arranque.
 *
 * @param id           estable: va en la URL, en la huella y en la bitácora
 * @param grupo        grupo del catálogo (mockup)
 * @param modulo       módulo funcional dueño del reporte
 * @param clase        GENERAL (agregado, sin personas) u OPERATIVO (detalle para los perfiles autorizados)
 * @param pantalla     pantalla que hay que poder abrir para generarlo
 * @param fuente       «Fuente» del encabezado (2.9)
 * @param version      se sube al cambiar columnas o cálculos: cambia la huella de los datos
 * @param periodo      campo temporal del rango; NINGUNO si el reporte no se filtra por fechas
 * @param agrupaciones agrupaciones admitidas; vacío = sin agrupación
 * @param maxDias      tope del rango en días
 * @param maxFilas     tope de filas de un archivo; superarlo es 422, nunca un archivo truncado (0 = sin tope)
 * @param orden        orden total de las filas, en palabras (lo documenta; el SQL del proveedor lo cumple con desempate por llave)
 * @param posicion     posición dentro de su grupo en el catálogo
 */
public record DefinicionReporte(String id, String titulo, String descripcion, GrupoReporte grupo, String modulo, ClaseReporte clase,
                                Pantallas pantalla, String fuente, int version, CampoTemporal periodo, Set<Agrupacion> agrupaciones, int maxDias,
                                int maxFilas, List<Filtro> filtros, List<Columna> columnas, String orden, int posicion) {

    static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{2,40}$");
    /** Parámetros del motor: ningún filtro puede llamarse así. */
    static final Set<String> RESERVADOS = Set.of("desde", "hasta", "agrupacion", "formato", "preset", "para", "codigo");
    /** Líneas del encabezado y columna que pone el motor: ningún filtro ni columna puede llamarse así. */
    static final Set<String> ETIQUETAS_DEL_MOTOR = Set.of("Periodo", "Agrupado por", "Fuente", "Establecimiento", "Registros", "Página");

    public enum ClaseReporte {
        GENERAL, OPERATIVO
    }

    /** Campo temporal del rango. La agrupación por horas solo se admite sobre FECHA_HORA. */
    public enum CampoTemporal {
        NINGUNO, FECHA, FECHA_HORA
    }

    public enum TipoColumna {
        TEXTO, ENTERO, DECIMAL, PORCENTAJE, FECHA, FECHA_HORA
    }

    public enum TipoFiltro {
        /** Una, varias o todas las opciones (2.11); el valor debe estar entre las opciones del alcance. */
        LISTA,
        /** Texto libre de búsqueda; un solo valor. */
        TEXTO
    }

    /**
     * @param establecimiento el filtro es el del establecimiento: va en la línea «Establecimiento» del encabezado (2.9)
     */
    public record Filtro(String clave, String etiqueta, TipoFiltro tipo, boolean establecimiento) {

        public Filtro {
            Objects.requireNonNull(clave);
            Objects.requireNonNull(etiqueta);
            Objects.requireNonNull(tipo);
        }

        public static Filtro lista(String clave, String etiqueta) {
            return new Filtro(clave, etiqueta, TipoFiltro.LISTA, false);
        }

        public static Filtro establecimiento(String clave, String etiqueta) {
            return new Filtro(clave, etiqueta, TipoFiltro.LISTA, true);
        }

        public static Filtro texto(String clave, String etiqueta) {
            return new Filtro(clave, etiqueta, TipoFiltro.TEXTO, false);
        }
    }

    /**
     * @param datoPersonal identifica a una persona (Ley 1581): prohibida en un reporte GENERAL
     * @param soloPara     perfiles que ven la columna; vacío = todos. El Administrador ve todas. Si un perfil no la ve, su reporte es
     *                     otra variante con su propia huella
     */
    public record Columna(String nombre, TipoColumna tipo, boolean datoPersonal, Set<Perfil> soloPara) {

        public Columna {
            Objects.requireNonNull(nombre);
            Objects.requireNonNull(tipo);
            soloPara = soloPara == null || soloPara.isEmpty() ? Set.of() : Set.copyOf(soloPara);
        }

        public static Columna de(String nombre, TipoColumna tipo) {
            return new Columna(nombre, tipo, false, Set.of());
        }

        public static Columna personal(String nombre, Collection<Perfil> soloPara) {
            return new Columna(nombre, TipoColumna.TEXTO, true, EnumSet.copyOf(soloPara));
        }

        /** ¿La ve quien tiene estos perfiles? */
        public boolean visiblePara(Set<Perfil> perfiles) {
            return soloPara.isEmpty() || perfiles.contains(Perfil.ADMINISTRADOR) || soloPara.stream().anyMatch(perfiles::contains);
        }
    }

    public DefinicionReporte {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("El id de reporte «" + id + "» no cumple " + ID.pattern());
        }
        Objects.requireNonNull(titulo, "titulo");
        Objects.requireNonNull(grupo, "grupo");
        Objects.requireNonNull(modulo, "modulo");
        Objects.requireNonNull(clase, "clase");
        Objects.requireNonNull(pantalla, "pantalla");
        Objects.requireNonNull(periodo, "periodo");
        descripcion = descripcion == null ? "" : descripcion;
        fuente = fuente == null || fuente.isBlank() ? "Suite DIGSA" : fuente;
        orden = orden == null ? "" : orden;
        agrupaciones = agrupaciones == null || agrupaciones.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(agrupaciones));
        filtros = List.copyOf(filtros == null ? List.of() : filtros);
        columnas = List.copyOf(columnas);
        if (columnas.isEmpty()) {
            throw new IllegalArgumentException("El reporte «" + id + "» no tiene columnas.");
        }
        Set<String> claves = new HashSet<>();
        Set<String> etiquetas = new HashSet<>();
        for (Filtro f : filtros) {
            if (RESERVADOS.contains(f.clave()) || !claves.add(f.clave())) {
                throw new IllegalArgumentException("El filtro «" + f.clave() + "» del reporte «" + id + "» está repetido o es reservado.");
            }
            if (ETIQUETAS_DEL_MOTOR.contains(f.etiqueta()) || !etiquetas.add(f.etiqueta())) {
                throw new IllegalArgumentException("La etiqueta «" + f.etiqueta() + "» del reporte «" + id + "» está repetida o la usa el encabezado.");
            }
        }
        Set<String> nombres = new HashSet<>();
        for (Columna c : columnas) {
            if (ETIQUETAS_DEL_MOTOR.contains(c.nombre()) || !nombres.add(c.nombre())) {
                throw new IllegalArgumentException("La columna «" + c.nombre() + "» del reporte «" + id + "» está repetida o la pone el motor.");
            }
        }
        if (filtros.stream().filter(Filtro::establecimiento).count() > 1) {
            throw new IllegalArgumentException("El reporte «" + id + "» tiene más de un filtro de establecimiento.");
        }
        if (clase == ClaseReporte.GENERAL && columnas.stream().anyMatch(Columna::datoPersonal)) {
            throw new IllegalArgumentException("El reporte GENERAL «" + id + "» no puede llevar columnas con datos personales (Ley 1581).");
        }
        if (periodo == CampoTemporal.NINGUNO && !agrupaciones.isEmpty()) {
            throw new IllegalArgumentException("El reporte «" + id + "» agrupa por periodo sin campo temporal.");
        }
        if (periodo != CampoTemporal.FECHA_HORA && agrupaciones.contains(Agrupacion.HORA)) {
            throw new IllegalArgumentException("El reporte «" + id + "» agrupa por horas sobre un campo sin hora.");
        }
        if (maxDias < 0 || maxFilas < 0 || version < 1 || (periodo != CampoTemporal.NINGUNO && maxDias == 0)) {
            throw new IllegalArgumentException("El reporte «" + id + "» tiene un tope o una versión inválidos.");
        }
    }

    /** Las columnas que ve quien tiene estos perfiles, en el orden de la definición. */
    public List<Columna> columnasPara(Set<Perfil> perfiles) {
        return columnas.stream().filter(c -> c.visiblePara(perfiles)).toList();
    }

    public Filtro filtro(String clave) {
        return filtros.stream().filter(f -> f.clave().equals(clave)).findFirst().orElse(null);
    }

    public static Constructor nuevo(String id, String titulo) {
        return new Constructor(id, titulo);
    }

    /** Arma la definición sin una lista larga de argumentos posicionales. */
    public static final class Constructor {
        private final String id;
        private final String titulo;
        private String descripcion = "";
        private GrupoReporte grupo;
        private String modulo;
        private ClaseReporte clase = ClaseReporte.GENERAL;
        private Pantallas pantalla = Pantallas.REPORTES;
        private String fuente = "Suite DIGSA";
        private int version = 1;
        private CampoTemporal periodo = CampoTemporal.NINGUNO;
        private Set<Agrupacion> agrupaciones = Set.of();
        private int maxDias = 3 * 366;
        private int maxFilas;
        private final List<Filtro> filtros = new ArrayList<>();
        private final List<Columna> columnas = new ArrayList<>();
        private String orden = "";
        private int posicion = 100;

        private Constructor(String id, String titulo) {
            this.id = id;
            this.titulo = titulo;
        }

        public Constructor descripcion(String v) {
            descripcion = v;
            return this;
        }

        public Constructor grupo(GrupoReporte v) {
            grupo = v;
            return this;
        }

        public Constructor modulo(String v) {
            modulo = v;
            return this;
        }

        public Constructor clase(ClaseReporte v) {
            clase = v;
            return this;
        }

        public Constructor pantalla(Pantallas v) {
            pantalla = v;
            return this;
        }

        public Constructor fuente(String v) {
            fuente = v;
            return this;
        }

        public Constructor version(int v) {
            version = v;
            return this;
        }

        public Constructor periodo(CampoTemporal v, Set<Agrupacion> agrupadoPor) {
            periodo = v;
            agrupaciones = agrupadoPor;
            return this;
        }

        public Constructor maxDias(int v) {
            maxDias = v;
            return this;
        }

        public Constructor maxFilas(int v) {
            maxFilas = v;
            return this;
        }

        public Constructor filtro(Filtro f) {
            filtros.add(f);
            return this;
        }

        public Constructor columna(String nombre, TipoColumna tipo) {
            columnas.add(Columna.de(nombre, tipo));
            return this;
        }

        public Constructor columna(Columna c) {
            columnas.add(c);
            return this;
        }

        public Constructor orden(String v) {
            orden = v;
            return this;
        }

        public Constructor posicion(int v) {
            posicion = v;
            return this;
        }

        public DefinicionReporte crear() {
            return new DefinicionReporte(id, titulo, descripcion, grupo, modulo, clase, pantalla, fuente, version, periodo, agrupaciones, maxDias,
                    maxFilas, filtros, columnas, orden, posicion);
        }
    }
}
