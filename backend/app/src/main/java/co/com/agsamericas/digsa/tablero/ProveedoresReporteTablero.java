package co.com.agsamericas.digsa.tablero;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Riesgo;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.CampoTemporal;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.ClaseReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Filtro;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoColumna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import co.com.agsamericas.digsa.reportes.Opcion;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El dashboard de visitas y el de riesgo en el catálogo del módulo de reportes (Anexo 5, 2.7 a 2.12). Las filas salen de las mismas
 * respuestas que pintan la pantalla ({@link ServicioDashboard#datos}, {@link ServicioRiesgo#tablero}), con las mismas tablas que la
 * descarga de {@link ExportadorTablero}. Sus pantallas y endpoints siguen igual.
 */
@Configuration
class ProveedoresReporteTablero {

    static final String DASHBOARD = "dashboard_visitas";
    static final String RIESGO = "dashboard_riesgo";
    private static final List<String> COLUMNAS_DASHBOARD = List.of("Tabla", "Concepto", "Valor", "Cumple", "No cumple", "No aplica", "Sin calificar");

    @Bean
    ProveedorReporte reporteDashboardVisitas(ServicioDashboard dashboard) {
        DefinicionReporte.Constructor c = DefinicionReporte.nuevo(DASHBOARD, "Dashboard de visitas")
                .descripcion("Indicadores, línea base, resultados y gráficas del dashboard de visitas, con las mismas cifras de la pantalla.")
                .grupo(GrupoReporte.VISITAS).modulo("tablero").clase(ClaseReporte.OPERATIVO).pantalla(Pantallas.DASHBOARD)
                .fuente("Agenda y actas de la Suite DIGSA").periodo(CampoTemporal.FECHA, Set.of())
                .filtro(Filtro.lista("fuerza", "Fuerza").deUnValor()).filtro(Filtro.lista("tipo", "Tipo de visita").deUnValor())
                .filtro(Filtro.lista("auditor", "Auditor líder").deUnValor()).filtro(Filtro.lista("unidad", "Unidad").deUnValor()).orden("tabla en el orden de la pantalla y, dentro de cada una, el de la pantalla").posicion(80);
        c.columna("Tabla", TipoColumna.TEXTO).columna("Concepto", TipoColumna.TEXTO).columna("Valor", TipoColumna.TEXTO);
        COLUMNAS_DASHBOARD.subList(3, COLUMNAS_DASHBOARD.size()).forEach(n -> c.columna(n, TipoColumna.ENTERO));
        DefinicionReporte definicion = c.crear();
        return new ProveedorReporte() {
            @Override
            public DefinicionReporte definicion() {
                return definicion;
            }

            @Override
            public boolean disponible(Alcance alcance) {
                return consulta(alcance);
            }

            @Override
            @SuppressWarnings("unchecked")
            public List<Opcion> opciones(String filtro, Alcance alcance) {
                if (!consulta(alcance)) {
                    return List.of();
                }
                Map<String, Object> o = dashboard.opciones(alcance);
                return switch (filtro) {
                    case "fuerza" -> deFilas((List<Map<String, Object>>) o.get("fuerzas"), "codigo", "nombre");
                    case "tipo" -> deFilas((List<Map<String, Object>>) o.get("tipos"), "codigo", "nombre");
                    case "auditor" -> deFilas((List<Map<String, Object>>) o.getOrDefault("auditores", List.of()), "id", "nombre");
                    case "unidad" -> ((List<String>) o.get("unidades")).stream().map(u -> new Opcion(u, u)).toList();
                    default -> List.of();
                };
            }

            @Override
            public void filas(ConsultaReporte c, Consumer<List<Object>> destino) {
                String auditor = primero(c, "auditor");
                Map<String, Object> d = dashboard.datos(new ServicioDashboard.Filtros(c.desde().toString(), c.hasta().toString(), primero(c, "fuerza"),
                        primero(c, "tipo"), auditor == null ? null : Long.valueOf(auditor), primero(c, "unidad")), c.alcance());
                entregar(c, filasDashboard(d), COLUMNAS_DASHBOARD, destino);
            }
        };
    }

    @Bean
    ProveedorReporte reporteDashboardRiesgo(ServicioRiesgo riesgo, ServicioDashboard dashboard) {
        DefinicionReporte.Constructor c = DefinicionReporte.nuevo(RIESGO, "Dashboard de riesgo por ESM")
                .descripcion("Puntaje y nivel de riesgo de cada ESM de dispensación y el agregado por fuerza, con los pesos y umbrales vigentes.")
                .grupo(GrupoReporte.VISITAS).modulo("tablero").clase(ClaseReporte.GENERAL).pantalla(Pantallas.RIESGO)
                .fuente("Visitas cerradas, planes de mejoramiento e incidencias registradas en la Suite DIGSA").filtro(Filtro.lista("fuerza", "Fuerza").deUnValor())
                .orden("ESM de mayor a menor puntaje y nombre; luego por fuerza; al final los pesos y umbrales").posicion(90);
        columnasRiesgo().forEach((n, t) -> c.columna(n, t));
        DefinicionReporte definicion = c.crear();
        List<String> columnas = List.copyOf(columnasRiesgo().keySet());
        return new ProveedorReporte() {
            @Override
            public DefinicionReporte definicion() {
                return definicion;
            }

            @Override
            public boolean disponible(Alcance alcance) {
                return consulta(alcance);
            }

            @Override
            @SuppressWarnings("unchecked")
            public List<Opcion> opciones(String filtro, Alcance alcance) {
                if (!"fuerza".equals(filtro) || !consulta(alcance)) {
                    return List.of();
                }
                // Las fuerzas del alcance (las mismas opciones del dashboard de visitas): sin calcular el tablero completo
                return deFilas((List<Map<String, Object>>) dashboard.opciones(alcance).get("fuerzas"), "codigo", "nombre");
            }

            @Override
            public void filas(ConsultaReporte c, Consumer<List<Object>> destino) {
                entregar(c, filasRiesgo(riesgo.tablero(c.alcance(), primero(c, "fuerza"))), columnas, destino);
            }
        };
    }

    /** Los tableros son de la coordinación y de los perfiles de consulta (ServicioRiesgo.exigirConsulta). */
    static boolean consulta(Alcance a) {
        return a instanceof Alcance.Todo || a instanceof Alcance.Fuerza || a instanceof Alcance.Esm;
    }

    private static List<Opcion> deFilas(List<Map<String, Object>> filas, String valor, String etiqueta) {
        return filas == null ? List.of() : filas.stream().map(x -> new Opcion(String.valueOf(x.get(valor)), String.valueOf(x.get(etiqueta)))).toList();
    }

    /**
     * El valor de un filtro {@code deUnValor}: el motor ya garantiza que hay a lo sumo uno. Cuando los servicios de los tableros admitan
     * listas, se pasa {@code c.valores(filtro)} completo y se quita la marca de la definición.
     */
    static String primero(ConsultaReporte c, String filtro) {
        return c.valores(filtro).stream().findFirst().orElse(null);
    }

    /** Entrega las columnas visibles, en su orden, respetando el límite del motor. */
    static void entregar(ConsultaReporte c, List<List<Object>> filas, List<String> columnas, Consumer<List<Object>> destino) {
        List<Integer> pos = c.columnas().stream().map(columnas::indexOf).toList();
        int n = 0;
        for (List<Object> f : filas) {
            if (c.limite() > 0 && n++ >= c.limite()) {
                return;
            }
            List<Object> salida = new ArrayList<>(pos.size());
            pos.forEach(i -> salida.add(i < 0 ? null : f.get(i)));
            destino.accept(salida);
        }
    }

    // ---------- Dashboard de visitas: las mismas tablas que ExportadorTablero.dashboard ----------

    private static List<Object> fila(String tabla, Object concepto, Object valor, Object... cnc) {
        List<Object> f = new ArrayList<>(Arrays.asList(tabla, concepto == null ? null : String.valueOf(concepto), valor == null ? null : String.valueOf(valor),
                null, null, null, null));
        for (int i = 0; i < cnc.length && i < 4; i++) {
            f.set(3 + i, cnc[i] instanceof Number x ? x.longValue() : null);
        }
        return f;
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> filasDashboard(Map<String, Object> d) {
        List<List<Object>> filas = new ArrayList<>();
        Map<String, Object> ind = requerido(d, "indicadores");
        Map<String, String> nombres = new LinkedHashMap<>();
        nombres.put("total", "Visitas en el periodo");
        nombres.put("porEjecutar", "Por ejecutar");
        nombres.put("ejecucion", "En ejecución");
        nombres.put("cerradas", "Cerradas");
        nombres.put("canceladas", "Canceladas");
        nombres.put("criticas", "Con alertas críticas");
        nombres.put("planesOplVencidos", "Planes del OPL vencidos (sin firmar a los 5 días hábiles)");
        nombres.forEach((k, n) -> filas.add(fila("Indicadores", n, ind.get(k))));
        Map<String, Object> lb = requerido(d, "lineaBase");
        filas.add(fila("Línea base", "ESM de dispensación con visita inicial cerrada", lb.get("cerradas") + " de " + lb.get("total")));
        filas.add(fila("Línea base", "En curso o programadas", lb.get("enCurso")));
        filas.add(fila("Línea base", "Sin programar", lb.get("sinProgramar")));
        Map<String, Object> res = requerido(d, "resultados");
        filas.add(fila("Resultados", "Cumple", res.get("c")));
        filas.add(fila("Resultados", "No cumple", res.get("nc")));
        filas.add(fila("Resultados", "No aplica", res.get("na")));
        filas.add(fila("Resultados", "Hallazgos", res.get("hallazgos")));
        filas.add(fila("Resultados", "Requieren intervención inmediata del OPL", res.get("inmediata")));
        filas.add(fila("Resultados", "Acciones del plan 5W2H abiertas", res.get("planesAbiertos")));
        Map<String, Object> hab = (Map<String, Object>) res.get("habilitacion");
        if (hab != null) {
            filas.add(fila(ExportadorTablero.HABILITACION, "Habilitado", hab.get("habilitado")));
            filas.add(fila(ExportadorTablero.HABILITACION, "No habilitado · en trámite", hab.get("tramite")));
            filas.add(fila(ExportadorTablero.HABILITACION, "No habilitado sin soporte", hab.get("no_habilitado")));
        }
        for (Map<String, Object> x : (List<Map<String, Object>>) res.getOrDefault("porCategoria", List.of())) {
            filas.add(fila(ExportadorTablero.CATEGORIAS, ExportadorTablero.etiquetaCategoria(x), null, x.get("c"), x.get("nc"), x.get("na"), x.get("pendientes")));
        }
        for (Map<String, Object> x : (List<Map<String, Object>>) res.getOrDefault("porPrestador", List.of())) {
            filas.add(fila(ExportadorTablero.PRESTADORES, x.get("prestador"), null, x.get("c"), x.get("nc"), x.get("na"), x.get("pendientes")));
        }
        for (Map<String, Object> x : (List<Map<String, Object>>) res.getOrDefault("vacios", List.of())) {
            filas.add(fila(ExportadorTablero.VACIOS, x.get("nombre"), null, null, x.get("nc")));
        }
        Map<String, String> graficas = new LinkedHashMap<>();
        graficas.put("porTipo", "Visitas por tipo");
        graficas.put("porFuerza", "Visitas por fuerza");
        graficas.put("porUnidad", "Visitas por unidad");
        graficas.put("porAuditor", "Visitas por auditor (líder)");
        graficas.put("porEstado", "Visitas por estado");
        graficas.forEach((k, n) -> {
            if (d.get(k) instanceof List<?> lista) {
                for (Object o : lista) {
                    Map<String, Object> x = (Map<String, Object>) o;
                    filas.add(fila(n, x.get("etiqueta"), x.get("valor")));
                }
            }
        });
        return filas;
    }

    /** Una sección que el dashboard siempre trae: si falta, el servicio cambió y el reporte no sale incompleto en silencio. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requerido(Map<String, Object> d, String llave) {
        if (!(d.get(llave) instanceof Map<?, ?> m)) {
            throw new IllegalStateException("El dashboard no trae «" + llave + "»");
        }
        return (Map<String, Object>) m;
    }

    // ---------- Dashboard de riesgo: las mismas tablas que ExportadorTablero.riesgo, con los pesos como filas ----------

    static Map<String, TipoColumna> columnasRiesgo() {
        Map<String, TipoColumna> m = new LinkedHashMap<>();
        for (String n : List.of("Tabla", "ESM o fuerza", "Fuerza", "Municipio")) {
            m.put(n, TipoColumna.TEXTO);
        }
        m.put("Puntaje", TipoColumna.DECIMAL);
        m.put("Nivel", TipoColumna.TEXTO);
        Riesgo.FACTORES.forEach(f -> m.put(Riesgo.NOMBRES.get(f), TipoColumna.DECIMAL));
        m.put("Última visita cerrada", TipoColumna.TEXTO);
        m.put("Días sin visita", TipoColumna.ENTERO);
        for (String n : List.of("Alto", "Medio", "Bajo")) {
            m.put(n, TipoColumna.ENTERO);
        }
        return m;
    }

    private static Object numero(Object v) {
        // Decimales como BigDecimal (contrato): la huella no depende de cómo se imprime un double
        return v instanceof Double || v instanceof Float ? java.math.BigDecimal.valueOf(((Number) v).doubleValue()) : v;
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> filasRiesgo(Map<String, Object> t) {
        Map<String, Integer> pesos = (Map<String, Integer>) t.get("pesos");
        List<List<Object>> filas = new ArrayList<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) t.get("esm")) {
            List<Object> f = new ArrayList<>(Arrays.asList("Puntaje por ESM", e.get("nombre"), e.get("fuerza"), e.get("municipio") == null ? "" : e.get("municipio"),
                    numero(e.get("total")), e.get("nivel")));
            Map<String, ?> fs = (Map<String, ?>) e.get("factores");
            Riesgo.FACTORES.forEach(k -> f.add(numero(fs.get(k))));
            f.add(e.get("ultimaCerrada") == null ? "Sin visita cerrada" : String.valueOf(e.get("ultimaCerrada")));
            f.add(e.get("diasSinVisita") instanceof Number n ? n.longValue() : null);
            f.addAll(Arrays.asList(null, null, null));
            filas.add(f);
        }
        for (Map<String, Object> x : (List<Map<String, Object>>) t.get("porFuerza")) {
            Riesgo.Agregado ag = (Riesgo.Agregado) x.get("agregado");
            List<Object> f = new ArrayList<>(Arrays.asList("Por fuerza", x.get("fuerza"), x.get("fuerza"), "", numero(ag.promedio()), "Factor principal: " + x.get("principal")));
            Riesgo.FACTORES.forEach(k -> f.add(numero(ag.factores().get(k))));
            f.add(null);
            f.add(null);
            f.addAll(Arrays.asList(entero(ag.niveles().get("alto")), entero(ag.niveles().get("medio")), entero(ag.niveles().get("bajo"))));
            filas.add(f);
        }
        // Los pesos y umbrales con que se calculó (en la descarga de la pantalla van en el encabezado)
        int ancho = columnasRiesgo().size();
        for (String k : Riesgo.FACTORES) {
            filas.add(parametro(ancho, "Peso de " + Riesgo.NOMBRES.get(k), pesos.get(k), null));
        }
        Map<String, Integer> u = (Map<String, Integer>) t.get("umbrales");
        filas.add(parametro(ancho, "Umbral de nivel alto (desde)", u.get("alto"), null));
        filas.add(parametro(ancho, "Umbral de nivel medio (desde)", u.get("medio"), null));
        filas.add(parametro(ancho, "Estado de los pesos", null, String.valueOf(t.get("estadoPesos"))));
        return filas;
    }

    private static Long entero(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static List<Object> parametro(int ancho, String nombre, Integer valor, String nivel) {
        List<Object> f = new ArrayList<>(Arrays.asList(new Object[ancho]));
        f.set(0, "Pesos y umbrales");
        f.set(1, nombre);
        f.set(4, valor == null ? null : java.math.BigDecimal.valueOf(valor));
        f.set(5, nivel);
        return f;
    }
}
