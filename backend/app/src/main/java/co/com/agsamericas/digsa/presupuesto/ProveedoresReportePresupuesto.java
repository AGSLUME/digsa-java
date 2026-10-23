package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.CampoTemporal;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.ClaseReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Columna;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Filtro;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoColumna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import co.com.agsamericas.digsa.reportes.Opcion;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Los cuatro reportes de ejecución presupuestal en el catálogo del módulo de reportes (contrato {@link ProveedorReporte}). Solo
 * traducen: la definición sale de {@link ServicioReportesPresupuesto#DEFINICIONES} y las filas de {@link ServicioReportesPresupuesto#generar},
 * sin tocar sus cálculos ni sus consultas; el alcance es el mismo {@link Ambito} de presupuesto. Sus pantallas y endpoints
 * ({@code /api/presupuesto/reportes}) siguen igual.
 */
@Configuration
class ProveedoresReportePresupuesto {

    /** Columnas que el motor reserva para su encabezado: aquí cambian de nombre. */
    static final Map<String, String> RENOMBRADAS = Map.of("Periodo", "Periodo de ejecución");
    /** Nombres de columna del servicio que varían según los filtros, con el nombre fijo de la definición. */
    static final Map<String, String> ALIAS = Map.of("Ejecutado al corte", "Ejecutado", "Periodo", "Periodo de ejecución");
    static final List<String> AGRUPAR = List.of("mes", "bimestre", "trimestre", "semestre", "anio");
    /** Columnas que el servicio trae solo con algunos filtros (con corte): sin ellos salen vacías. Cualquier otra que falte es un error. */
    static final Set<String> OPCIONALES = Set.of("Proyectado al corte", "Desviación %");

    @Bean
    ProveedorReporte reportePresupuestoEjecucion(ServicioReportesPresupuesto servicio, JdbcClient jdbc) {
        return new Proveedor("ejecucion", 10, servicio, jdbc);
    }

    @Bean
    ProveedorReporte reportePresupuestoMensual(ServicioReportesPresupuesto servicio, JdbcClient jdbc) {
        return new Proveedor("mensual", 20, servicio, jdbc);
    }

    @Bean
    ProveedorReporte reportePresupuestoMovimientos(ServicioReportesPresupuesto servicio, JdbcClient jdbc) {
        return new Proveedor("movimientos", 30, servicio, jdbc);
    }

    @Bean
    ProveedorReporte reportePresupuestoAlertas(ServicioReportesPresupuesto servicio, JdbcClient jdbc) {
        return new Proveedor("alertas", 40, servicio, jdbc);
    }

    /** Un reporte de presupuesto: {@code tipo} es la llave de {@link ServicioReportesPresupuesto#TIPOS}. */
    static final class Proveedor implements ProveedorReporte {

        private final String tipo;
        private final DefinicionReporte definicion;
        private final ServicioReportesPresupuesto servicio;
        private final JdbcClient jdbc;

        Proveedor(String tipo, int posicion, ServicioReportesPresupuesto servicio, JdbcClient jdbc) {
            this.tipo = tipo;
            this.servicio = servicio;
            this.jdbc = jdbc;
            this.definicion = definicion(tipo, posicion);
        }

        static DefinicionReporte definicion(String tipo, int posicion) {
            ServicioReportesPresupuesto.Definicion d = ServicioReportesPresupuesto.DEFINICIONES.get(tipo);
            DefinicionReporte.Constructor c = DefinicionReporte.nuevo(d.id(), ServicioReportesPresupuesto.TIPOS.get(tipo)).grupo(GrupoReporte.PRESUPUESTO)
                    .modulo("presupuesto").clase(ClaseReporte.valueOf(d.clase())).pantalla(Pantallas.PRESUPUESTO)
                    .fuente("Presupuesto, ejecución y proyección cargados en la Suite DIGSA").posicion(posicion);
            // El reporte mensual toma el periodo de la vigencia (y agrupa por su cuenta); movimientos y alertas, por fecha y hora
            c.periodo("FECHA_HORA".equals(d.periodo()) ? CampoTemporal.FECHA_HORA : CampoTemporal.NINGUNO, Set.of());
            for (String f : d.filtros()) {
                switch (f) {
                    case "vigencia" -> c.filtro(Filtro.lista("vigencia", "Vigencia").deUnValor());
                    case "corte" -> c.filtro(Filtro.texto("corte", "Corte (AAAA-MM)"));
                    case "fuerza" -> c.filtro(Filtro.lista("fuerza", "Fuerza"));
                    case "punto" -> c.filtro(Filtro.establecimiento("punto", "ESM").deUnValor());
                    case "modalidad" -> c.filtro(Filtro.lista("modalidad", "Modalidad"));
                    case "tipoMovimiento" -> c.filtro(Filtro.lista("tipo_movimiento", "Tipo de movimiento").deUnValor());
                    default -> throw new IllegalStateException("Filtro de presupuesto sin traducir: " + f);
                }
            }
            if ("mensual".equals(tipo)) {
                c.filtro(Filtro.lista("agrupar", "Agrupar por").deUnValor());
            }
            for (String col : d.columnasTipadas()) {
                String nombre = col.substring(0, col.indexOf(':'));
                String tipoCol = col.substring(col.indexOf(':') + 1);
                nombre = RENOMBRADAS.getOrDefault(nombre, nombre);
                if (tipoCol.contains("personal")) {
                    c.columna(Columna.personal(nombre, Alcance.PERFILES_INTERVENTORIA));
                } else {
                    c.columna(nombre, TipoColumna.valueOf(tipoCol.strip()));
                }
            }
            if ("ejecucion".equals(tipo)) {
                // Sin corte elegido, el servicio toma el mes en curso: el corte efectivo va en cada fila (y en la huella), así se sabe a
                // qué mes corresponden las cifras y una verificación del mes siguiente lo explica
                c.columna("Corte efectivo", TipoColumna.TEXTO);
            }
            return c.orden(switch (tipo) {
                case "ejecucion" -> "vigencia, fuerza, ESM y modalidad; al final los totales por fuerza y el nacional";
                case "mensual" -> "línea (fuerza, ESM, modalidad) y periodo";
                case "movimientos" -> "fecha y hora, movimiento, valor y línea";
                default -> "fecha y hora y consecutivo";
            }).crear();
        }

        @Override
        public DefinicionReporte definicion() {
            return definicion;
        }

        @Override
        public List<Opcion> opciones(String filtro, Alcance alcance) {
            Ambito a = Ambito.de(alcance, Set.of());
            return switch (filtro) {
                case "vigencia" -> jdbc.sql("SELECT id, anio FROM presupuesto_vigencias ORDER BY anio, id")
                        .query((rs, n) -> new Opcion(String.valueOf(rs.getLong(1)), String.valueOf(rs.getInt(2)))).list();
                case "fuerza" -> jdbc.sql("SELECT DISTINCT l.fuerza, f.nombre FROM presupuesto_lineas l JOIN par_fuerzas f ON f.codigo = l.fuerza WHERE ("
                        + a.condicion() + ") ORDER BY l.fuerza").params(a.parametros()).query((rs, n) -> new Opcion(rs.getString(1), rs.getString(2))).list();
                case "punto" -> jdbc.sql("""
                        SELECT DISTINCT p.id, coalesce(nullif(p.sigla, '') || ' · ', '') || p.nombre AS nombre
                        FROM presupuesto_lineas l JOIN puntos p ON p.id = l.punto_id WHERE (""" + a.condicion() + ") ORDER BY nombre, p.id")
                        .params(a.parametros()).query((rs, n) -> new Opcion(String.valueOf(rs.getLong(1)), rs.getString(2))).list();
                case "modalidad" -> jdbc.sql("SELECT codigo, nombre FROM presupuesto_modalidades ORDER BY orden, codigo")
                        .query((rs, n) -> new Opcion(rs.getString(1), rs.getString(2))).list();
                case "tipo_movimiento" -> Arrays.stream(PresupuestoNovedad.Tipo.values()).map(t -> new Opcion(t.codigo(), t.nombre())).toList();
                case "agrupar" -> AGRUPAR.stream().map(g -> new Opcion(g, "anio".equals(g) ? "año" : g)).toList();
                default -> List.of();
            };
        }

        @Override
        public boolean disponible(Alcance alcance) {
            return !"FALSE".equals(Ambito.de(alcance, Set.of()).condicion());
        }

        @Override
        public void filas(ConsultaReporte c, Consumer<List<Object>> destino) {
            ServicioReportesPresupuesto.Filtros f = new ServicioReportesPresupuesto.Filtros(numero(primero(c, "vigencia")),
                    c.valores("corte").stream().findFirst().orElse(null), lista(c, "fuerza"), numero(primero(c, "punto")), lista(c, "modalidad"),
                    primero(c, "tipo_movimiento"), c.desde() == null ? null : c.desde().toString(),
                    c.hasta() == null ? null : c.hasta().toString(), primero(c, "agrupar"));
            Reporte r = servicio.generar(tipo, f, Ambito.de(c.alcance(), Set.of()), "");
            // Posición de cada columna visible en las filas del servicio (las que el servicio no trae con estos filtros salen vacías)
            Map<String, Integer> indice = new HashMap<>();
            for (int i = 0; i < r.columnas().size(); i++) {
                indice.put(ALIAS.getOrDefault(r.columnas().get(i), r.columnas().get(i)), i);
            }
            String corte = r.filtros().get("Corte");
            List<Integer> posiciones = c.columnas().stream().map(n -> indice.getOrDefault(n, -1)).toList();
            for (int i = 0; i < posiciones.size(); i++) {
                if (posiciones.get(i) < 0 && !OPCIONALES.contains(c.columnas().get(i)) && !"Corte efectivo".equals(c.columnas().get(i))) {
                    // El servicio cambió el nombre de una columna: mejor un error que una columna vacía con huella válida
                    throw new IllegalStateException("El reporte de presupuesto «" + tipo + "» no trae la columna «" + c.columnas().get(i) + "»");
                }
            }
            List<Boolean> textos = c.columnas().stream().map(n -> definicion.columnas().stream().filter(x -> x.nombre().equals(n)).findFirst()
                    .map(x -> x.tipo() == TipoColumna.TEXTO).orElse(true)).toList();
            int n = 0;
            for (List<Object> fila : r.filas()) {
                if (c.limite() > 0 && n++ >= c.limite()) {
                    return;
                }
                List<Object> salida = new ArrayList<>(posiciones.size());
                for (int i = 0; i < posiciones.size(); i++) {
                    Object v = "Corte efectivo".equals(c.columnas().get(i)) ? corte
                            : posiciones.get(i) < 0 || posiciones.get(i) >= fila.size() ? null : fila.get(posiciones.get(i));
                    // El servicio pone «» donde no hay valor; en una columna numérica o de fecha es un vacío
                    salida.add(v instanceof String s && s.isEmpty() && !textos.get(i) ? null : v);
                }
                destino.accept(salida);
            }
        }

        /**
         * El valor de un filtro {@code deUnValor}: el motor ya garantiza que hay a lo sumo uno. Cuando el servicio admita listas, se pasa
         * {@code c.valores(filtro)} completo y se quita la marca de la definición.
         */
        private static String primero(ConsultaReporte c, String filtro) {
            return c.valores(filtro).stream().findFirst().orElse(null);
        }

        private static String lista(ConsultaReporte c, String filtro) {
            return c.filtra(filtro) ? String.join(",", c.valores(filtro)) : null;
        }

        private static Long numero(String v) {
            return v == null ? null : Long.valueOf(v);
        }
    }
}
