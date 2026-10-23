package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Periodos;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.CampoTemporal;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Columna;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Filtro;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoFiltro;
import co.com.agsamericas.digsa.reportes.Opcion;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Motor de reportes (Anexo 5, 2.8 a 2.12): resuelve la petición contra la definición del reporte (periodo, agrupación y filtros de
 * una, varias o todas las opciones, validados contra las opciones del alcance), arma el encabezado de 2.9 y ejecuta el proveedor en
 * una sola transacción de solo lectura en REPEATABLE READ, para que las cifras no cambien entre usuarios con los mismos filtros (2.10).
 */
@Service
public class MotorReportes {

    private static final Logger LOG = LoggerFactory.getLogger(MotorReportes.class);

    /** Valores por filtro: más que esto no es una selección sino un abuso (los catálogos más grandes son de cientos). */
    static final int MAX_VALORES = 1000;
    static final int MAX_TEXTO = 200;

    /** Petición ya validada: la definición, la consulta para el proveedor y lo que va al encabezado. */
    public record Solicitud(ProveedorReporte proveedor, ConsultaReporte consulta, Map<String, String> filtrosEncabezado, String periodo,
                            String establecimiento) {

        public Solicitud {
            filtrosEncabezado = Collections.unmodifiableMap(new LinkedHashMap<>(filtrosEncabezado));
        }

        public DefinicionReporte definicion() {
            return proveedor.definicion();
        }
    }

    /**
     * Reporte generado.
     *
     * @param truncado en pantalla hay más filas que {@code filasPantalla}; el archivo trae todas
     */
    public record Resultado(Solicitud solicitud, ZonedDateTime generado, String usuario, List<String> columnas, List<List<Object>> filas,
                            boolean truncado, long ms) {

        public Resultado {
            columnas = List.copyOf(columnas);
            filas = Collections.unmodifiableList(new ArrayList<>(filas));
        }
    }

    private final RegistroReportes registro;
    private final ConfiguracionReportes cfg;
    private final ServicioCatalogos catalogos;
    private final Clock reloj;
    private final TransactionTemplate lectura;
    /** Para fijar el tiempo máximo de las consultas dentro de la transacción de lectura (null en las pruebas unitarias sin base). */
    private final JdbcClient jdbc;

    public MotorReportes(RegistroReportes registro, ConfiguracionReportes cfg, ServicioCatalogos catalogos, Clock reloj,
                         PlatformTransactionManager transacciones, JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.registro = registro;
        this.cfg = cfg;
        this.catalogos = catalogos;
        this.reloj = reloj;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
        this.lectura.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // Siempre una transacción propia: dentro de otra, el REPEATABLE READ se ignoraría y un error la marcaría para deshacer
        this.lectura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Reportes que el usuario puede generar, en el orden del catálogo. */
    public List<ProveedorReporte> catalogo(Set<Perfil> perfiles, Alcance alcance) {
        return registro.todos().stream().filter(p -> puedeGenerar(p, perfiles, alcance)).toList();
    }

    /** El reporte, si el usuario lo puede generar; si no, 404 sin decir que existe. */
    public ProveedorReporte proveedor(String id, Set<Perfil> perfiles, Alcance alcance) {
        return registro.buscar(id).filter(p -> puedeGenerar(p, perfiles, alcance))
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El reporte no existe."));
    }

    private static boolean puedeGenerar(ProveedorReporte p, Set<Perfil> perfiles, Alcance alcance) {
        return p.definicion().pantalla().puedeEntrar(perfiles, true) && p.disponible(alcance);
    }

    /** Opciones de cada filtro de lista, recortadas al alcance. */
    public Map<String, List<Opcion>> opciones(ProveedorReporte p, Alcance alcance) {
        return lectura.execute(t -> {
            Map<String, List<Opcion>> m = new LinkedHashMap<>();
            for (Filtro f : p.definicion().filtros()) {
                if (f.tipo() == TipoFiltro.LISTA) {
                    m.put(f.clave(), p.opciones(f.clave(), alcance));
                }
            }
            return m;
        });
    }

    /** Presets del rango; el año de contrato sale del parámetro «inicio_contrato» (Catálogos › Parámetros) si está registrado. */
    public List<Periodos.Preset> presets() {
        String inicio = catalogos.textoParametro("inicio_contrato", "").strip();
        LocalDate fecha = inicio.isEmpty() ? null : Fechas.valida(inicio).orElse(null);
        if (!inicio.isEmpty() && fecha == null) {
            LOG.warn("[reportes] El parámetro inicio_contrato «{}» no es una fecha AAAA-MM-DD: no se ofrece el periodo «Año de contrato vigente»", inicio);
        }
        return Periodos.presets(hoy(), fecha);
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA));
    }

    /** Parámetros del periodo: el reporte sin periodo no los admite. */
    private static final Set<String> DEL_PERIODO = Set.of("desde", "hasta", "preset", "agrupacion");

    /**
     * Valida la petición. {@code parametros} son los de la URL (o el cuerpo del envío): desde, hasta, preset, agrupacion y los filtros
     * del reporte, cada uno con uno o varios valores. Nada se ignora en silencio: un parámetro que no aplica, repetido o que choca con
     * otro (preset con fechas) es 422.
     */
    public Solicitud resolver(String id, Map<String, List<String>> parametros, Set<Perfil> perfiles, Alcance alcance) {
        ProveedorReporte p = proveedor(id, perfiles, alcance);
        DefinicionReporte d = p.definicion();
        Map<String, List<String>> limpios = new LinkedHashMap<>();
        parametros.forEach((k, v) -> limpios.put(k, v == null ? List.of() : v.stream().filter(x -> x != null && !x.isBlank()).map(String::strip).toList()));
        for (String clave : limpios.keySet()) {
            if (DEL_PERIODO.contains(clave) ? d.periodo() == CampoTemporal.NINGUNO : d.filtro(clave) == null) {
                throw ErrorNegocio.validacion("El filtro «" + clave + "» no aplica al reporte «" + d.titulo() + "».");
            }
        }
        Map<String, String> encabezado = new LinkedHashMap<>();
        LocalDate desde = null;
        LocalDate hasta = null;
        Agrupacion agrupacion = null;
        String periodo = "";
        if (d.periodo() != CampoTemporal.NINGUNO) {
            String preset = uno(limpios, "preset");
            String de = uno(limpios, "desde");
            String a = uno(limpios, "hasta");
            if (preset != null) {
                if (de != null || a != null) {
                    throw ErrorNegocio.validacion("Elija un periodo predefinido o las fechas «desde» y «hasta», no los dos.");
                }
                Periodos.Preset x = presets().stream().filter(z -> z.clave().equals(preset)).findFirst()
                        .orElseThrow(() -> ErrorNegocio.validacion("El periodo «" + preset + "» no existe."));
                desde = x.desde();
                hasta = x.hasta();
            } else {
                LocalDate hoy = hoy();
                desde = de == null ? hoy.withDayOfYear(1) : Fechas.valida(de).orElseThrow(() -> ErrorNegocio.validacion("La fecha «desde» no es válida."));
                hasta = a == null ? hoy : Fechas.valida(a).orElseThrow(() -> ErrorNegocio.validacion("La fecha «hasta» no es válida."));
            }
            if (hasta.isBefore(desde) || new Periodos.Rango(desde, hasta).dias() > d.maxDias()) {
                throw ErrorNegocio.validacion("El periodo debe ir de «desde» a «hasta» y ser de máximo " + tope(d.maxDias()) + ".");
            }
            periodo = desde + " a " + hasta;
            encabezado.put("Periodo", periodo);
            String ag = uno(limpios, "agrupacion");
            if (ag != null) {
                agrupacion = Agrupacion.deClave(ag).filter(d.agrupaciones()::contains).orElseThrow(() -> ErrorNegocio.validacion(
                        d.agrupaciones().isEmpty() ? "El reporte «" + d.titulo() + "» no se agrupa por periodo."
                                : "El reporte «" + d.titulo() + "» no se agrupa por «" + ag + "». Admite: "
                                + d.agrupaciones().stream().sorted().map(x -> x.nombre().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", ")) + "."));
                encabezado.put("Agrupado por", agrupacion.nombre().toLowerCase(Locale.ROOT));
            }
        }
        Map<String, List<String>> filtros = new LinkedHashMap<>();
        String establecimiento = alcance instanceof Alcance.Todo || alcance instanceof Alcance.Opl ? "Todos" : "Todos (dentro de su alcance)";
        // Los filtros en el orden de la definición: el encabezado y la huella no dependen del orden de los parámetros
        for (Filtro f : d.filtros()) {
            List<String> valores = limpios.getOrDefault(f.clave(), List.of());
            if (valores.isEmpty()) {
                continue;
            }
            List<String> ordenados = new ArrayList<>(new TreeSet<>(valores));
            String etiquetas;
            if (f.tipo() == TipoFiltro.TEXTO) {
                if (ordenados.size() > 1 || ordenados.get(0).length() > MAX_TEXTO) {
                    throw ErrorNegocio.validacion("«" + f.etiqueta() + "» admite un solo texto de hasta " + MAX_TEXTO + " caracteres.");
                }
                // Sin saltos de línea ni otros controles: el texto va en el encabezado de los archivos y no puede inventar líneas
                if (ordenados.get(0).chars().anyMatch(Character::isISOControl)) {
                    throw ErrorNegocio.validacion("«" + f.etiqueta() + "» no admite saltos de línea ni caracteres de control.");
                }
                etiquetas = ordenados.get(0);
            } else {
                if (ordenados.size() > MAX_VALORES) {
                    throw ErrorNegocio.validacion("«" + f.etiqueta() + "» admite hasta " + MAX_VALORES + " opciones; sin el filtro salen todas.");
                }
                Map<String, String> validas = lectura.execute(t -> p.opciones(f.clave(), alcance)).stream()
                        .collect(Collectors.toMap(Opcion::valor, Opcion::etiqueta, (x, y) -> x, LinkedHashMap::new));
                List<String> nombres = new ArrayList<>();
                for (String v : ordenados) {
                    String nombre = validas.get(v);
                    if (nombre == null) {
                        // El mismo mensaje si no existe o si está fuera del alcance: no revela lo que el usuario no puede ver
                        throw ErrorNegocio.validacion("«" + f.etiqueta() + "»: la opción «" + v + "» no existe o está fuera de su alcance.");
                    }
                    nombres.add(nombre);
                }
                etiquetas = String.join(", ", nombres);
            }
            filtros.put(f.clave(), List.copyOf(ordenados));
            encabezado.put(f.etiqueta(), etiquetas);
            if (f.establecimiento()) {
                establecimiento = etiquetas;
            }
        }
        List<String> columnas = d.columnasPara(perfiles).stream().map(Columna::nombre).toList();
        return new Solicitud(p, new ConsultaReporte(alcance, desde, hasta, agrupacion, filtros, columnas, 0), encabezado, periodo, establecimiento);
    }

    private static String tope(int dias) {
        return dias % 366 == 0 && dias >= 366 ? (dias / 366 == 1 ? "un año" : dias / 366 + " años") : dias + " días";
    }

    /** El valor de un parámetro de un solo valor (ya sin vacíos), o null si no vino. */
    private static String uno(Map<String, List<String>> parametros, String clave) {
        List<String> v = parametros.getOrDefault(clave, List.of());
        if (v.size() > 1) {
            throw ErrorNegocio.validacion("El parámetro «" + clave + "» admite un solo valor.");
        }
        return v.isEmpty() ? null : v.get(0);
    }

    /**
     * Genera el reporte. Pide al proveedor (LIMIT) una fila más de las que admite: en pantalla, {@code filasPantalla}, y si hay más
     * se avisa; en un archivo, el tope del reporte, y pasarlo es 422 en vez de un archivo incompleto.
     */
    public Resultado generar(Solicitud s, String usuario, boolean paraArchivo) {
        long inicio = System.nanoTime();
        ZonedDateTime generado = ZonedDateTime.now(reloj.withZone(Fechas.BOGOTA));
        DefinicionReporte d = s.definicion();
        List<String> columnas = columnas(s);
        int limite = paraArchivo ? d.maxFilas() : cfg.filasPantalla();
        ConsultaReporte consulta = s.consulta().conLimite(limite == 0 ? 0 : limite + 1);
        List<List<Object>> filas = new ArrayList<>();
        // Los plazos de 2.7 (3 y 10 minutos) no cortan: disparan la alerta al medir. Solo el tope técnico corta una consulta colgada (en
        // pantalla, uno más corto: la petición no espera una hora; lo grande se descarga en segundo plano)
        long plazo = paraArchivo ? cfg.topeTecnicoMs() : cfg.topeTecnicoVerMs();
        leer(plazo, paraArchivo, () -> s.proveedor().filas(consulta, recolector(d, columnas, limite, filas)));
        if (System.nanoTime() - inicio > plazo * 1_000_000L) {
            throw new ReporteCortado(plazo, paraArchivo); // terminó, pero pasado el tope: lo mismo que si se hubiera cortado
        }
        boolean truncado = limite > 0 && filas.size() > limite;
        if (truncado && paraArchivo) {
            throw ErrorNegocio.validacion("El reporte pasa de " + d.maxFilas() + " filas; acote el periodo o los filtros.");
        }
        if (truncado) {
            filas.subList(limite, filas.size()).clear();
        }
        return new Resultado(s, generado, usuario, columnas, filas, truncado, (System.nanoTime() - inicio) / 1_000_000);
    }

    /** Columnas del resultado: «Periodo» si se agrupa, y las visibles para el perfil. */
    static List<String> columnas(Solicitud s) {
        List<String> columnas = new ArrayList<>();
        if (s.consulta().agrupacion() != null) {
            columnas.add("Periodo");
        }
        columnas.addAll(s.consulta().columnas());
        return List.copyOf(columnas);
    }

    /**
     * Lee el reporte completo para un archivo y entrega cada fila al destino sin acumularlas (cursor del proveedor). Pasar del tope es
     * 422 antes de entregar la fila de más: nunca sale un archivo incompleto.
     *
     * @param tope filas que admite el formato (0 = el tope del reporte); el menor de los dos manda
     * @return filas entregadas
     */
    public long volcar(Solicitud s, int tope, String motivoTope, Consumer<List<Object>> destino) {
        DefinicionReporte d = s.definicion();
        List<String> cols = columnas(s);
        int ancho = cols.size();
        int limite = tope <= 0 ? d.maxFilas() : d.maxFilas() == 0 ? tope : Math.min(tope, d.maxFilas());
        String mensaje = tope > 0 && limite == tope && motivoTope != null ? motivoTope
                : "El reporte pasa de " + limite + " filas; acote el periodo o los filtros.";
        ConsultaReporte consulta = s.consulta().conLimite(limite == 0 ? 0 : limite + 1);
        long[] n = {0};
        long plazo = cfg.topeTecnicoMs();
        long fin = System.nanoTime() + plazo * 1_000_000L;
        leer(plazo, true, () -> s.proveedor().filas(consulta, fila -> {
            if (fila.size() != ancho) {
                throw new IllegalStateException("El reporte «" + d.id() + "» entregó una fila de " + fila.size() + " columnas; esperaba " + ancho);
            }
            if (limite > 0 && n[0] >= limite) {
                throw ErrorNegocio.validacion(mensaje);
            }
            TiposCelda.validar(d.id(), cols, fila);
            // El tiempo total (consulta, cinta y huella) tampoco puede pasar del plazo: se revisa cada mil filas
            if (++n[0] % 1000 == 0 && System.nanoTime() > fin) {
                throw new ReporteCortado(plazo, true);
            }
            destino.accept(fila);
        }));
        if (System.nanoTime() > fin) {
            throw new ReporteCortado(plazo, true);
        }
        return n[0];
    }

    /**
     * Lee en la transacción propia con el tope técnico fijado en PostgreSQL ({@code SET LOCAL statement_timeout}): solo una consulta
     * colgada llega a él, y entonces PostgreSQL la cancela y el reporte se corta con {@link ReporteCortado}.
     */
    private void leer(long plazoMs, boolean archivo, Runnable lectura) {
        try {
            this.lectura.executeWithoutResult(t -> {
                if (jdbc != null) {
                    // Como SET LOCAL: solo dura la transacción de la lectura
                    jdbc.sql("SELECT set_config('statement_timeout', :ms, true)").param("ms", String.valueOf(plazoMs)).query(String.class).single();
                }
                lectura.run();
            });
        } catch (DataAccessException e) {
            if (cancelada(e)) {
                throw new ReporteCortado(plazoMs, archivo);
            }
            throw e;
        }
    }

    /** ¿PostgreSQL canceló la consulta por el tiempo máximo? (SQLState 57014, query_canceled). */
    static boolean cancelada(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof QueryTimeoutException || t instanceof java.sql.SQLException se && "57014".equals(se.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Junta las filas validando el ancho. El proveedor entrega una lista nueva por fila (contrato), así que no se copia. Si un proveedor
     * no aplicó el LIMIT, las filas de más se descartan sin guardarlas: la memoria no crece más allá del límite.
     */
    private static Consumer<List<Object>> recolector(DefinicionReporte d, List<String> columnas, int limite, List<List<Object>> filas) {
        int ancho = columnas.size();
        return fila -> {
            if (fila.size() != ancho) {
                throw new IllegalStateException("El reporte «" + d.id() + "» entregó una fila de " + fila.size() + " columnas; esperaba " + ancho);
            }
            TiposCelda.validar(d.id(), columnas, fila);
            if (limite == 0 || filas.size() <= limite) {
                filas.add(Collections.unmodifiableList(fila));
            }
        };
    }
}
