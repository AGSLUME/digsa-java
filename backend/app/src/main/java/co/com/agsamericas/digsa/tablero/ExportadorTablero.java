package co.com.agsamericas.digsa.tablero;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Exportador;
import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Riesgo;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Descargas del dashboard de riesgo y del dashboard de visitas con el exportador común (TXT, CSV «;» UTF-8, Excel y PDF paginado):
 * encabezado con fecha, usuario y filtros, y una columna «Tabla» que separa las secciones en un solo archivo.
 */
@Component
public class ExportadorTablero {

    private static final DateTimeFormatter ARCHIVO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    public record Archivo(String nombre, String tipo, byte[] contenido) {
    }

    private final Exportador exportador;
    private final Clock reloj;

    public ExportadorTablero(Exportador exportador, Clock reloj) {
        this.exportador = exportador;
        this.reloj = reloj;
    }

    @SuppressWarnings("unchecked")
    Archivo riesgo(Map<String, Object> t, String formato, String usuario) {
        List<String> factores = (List<String>) t.get("factores");
        Map<String, Integer> pesos = (Map<String, Integer>) t.get("pesos");
        List<String> columnas = new ArrayList<>(List.of("Tabla", "ESM o fuerza", "Fuerza", "Municipio", "Puntaje", "Nivel"));
        factores.forEach(f -> columnas.add(Riesgo.NOMBRES.get(f) + " (de " + pesos.get(f) + ")"));
        columnas.addAll(List.of("Última visita cerrada", "Días sin visita", "Alto", "Medio", "Bajo"));
        List<List<Object>> filas = new ArrayList<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) t.get("esm")) {
            List<Object> f = new ArrayList<>(List.of("Puntaje por ESM", e.get("nombre"), e.get("fuerza"), texto(e.get("municipio")), e.get("total"), e.get("nivel")));
            Map<String, Double> fs = (Map<String, Double>) e.get("factores");
            factores.forEach(k -> f.add(fs.get(k)));
            f.add(e.get("ultimaCerrada") == null ? "Sin visita cerrada" : e.get("ultimaCerrada"));
            f.add(e.get("diasSinVisita"));
            f.addAll(java.util.Arrays.asList(null, null, null));
            filas.add(f);
        }
        for (Map<String, Object> x : (List<Map<String, Object>>) t.get("porFuerza")) {
            Riesgo.Agregado ag = (Riesgo.Agregado) x.get("agregado");
            List<Object> f = new ArrayList<>(List.of("Por fuerza", x.get("fuerza"), x.get("fuerza"), "", ag.promedio(), "Factor principal: " + x.get("principal")));
            factores.forEach(k -> f.add(ag.factores().get(k)));
            f.add(null);
            f.add(null);
            f.addAll(List.of(ag.niveles().get("alto"), ag.niveles().get("medio"), ag.niveles().get("bajo")));
            filas.add(f);
        }
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("Fuerza", t.get("filtroFuerza") == null ? "Todas" : String.valueOf(t.get("filtroFuerza")));
        StringBuilder p = new StringBuilder();
        factores.forEach(k -> p.append(p.length() == 0 ? "" : "; ").append(Riesgo.NOMBRES.get(k)).append(" ").append(pesos.get(k)));
        filtros.put("Pesos", p.toString());
        Map<String, Integer> u = (Map<String, Integer>) t.get("umbrales");
        filtros.put("Umbrales", "alto desde " + u.get("alto") + ", medio desde " + u.get("medio"));
        filtros.put("Estado de los pesos", String.valueOf(t.get("estadoPesos")));
        filtros.put("Fuente", "Visitas cerradas, planes de mejoramiento e incidencias registradas en la Suite DIGSA");
        filtros.put("Registros", String.valueOf(((List<?>) t.get("esm")).size()) + " ESM");
        return archivo(new Reporte("Dashboard de riesgo por ESM", ahora(), usuario, filtros, columnas, filas), "riesgo", formato);
    }

    @SuppressWarnings("unchecked")
    Archivo dashboard(Map<String, Object> d, String formato, String usuario) {
        List<String> columnas = List.of("Tabla", "Concepto", "Valor");
        List<List<Object>> filas = new ArrayList<>();
        Map<String, Object> ind = (Map<String, Object>) d.get("indicadores");
        Map<String, String> nombres = new LinkedHashMap<>();
        nombres.put("total", "Visitas en el periodo");
        nombres.put("porEjecutar", "Por ejecutar");
        nombres.put("ejecucion", "En ejecución");
        nombres.put("cerradas", "Cerradas");
        nombres.put("canceladas", "Canceladas");
        nombres.put("criticas", "Con alertas críticas");
        nombres.put("planesOplVencidos", "Planes del OPL vencidos (sin firmar a los 5 días hábiles)");
        nombres.forEach((k, n) -> filas.add(List.of("Indicadores", n, ind.get(k))));
        Map<String, Object> lb = (Map<String, Object>) d.get("lineaBase");
        filas.add(List.of("Línea base", "ESM de dispensación con visita inicial cerrada", lb.get("cerradas") + " de " + lb.get("total")));
        filas.add(List.of("Línea base", "En curso o programadas", lb.get("enCurso")));
        filas.add(List.of("Línea base", "Sin programar", lb.get("sinProgramar")));
        Map<String, Object> res = (Map<String, Object>) d.get("resultados");
        filas.add(List.of("Resultados", "Cumple", res.get("c")));
        filas.add(List.of("Resultados", "No cumple", res.get("nc")));
        filas.add(List.of("Resultados", "No aplica", res.get("na")));
        filas.add(List.of("Resultados", "Hallazgos", res.get("hallazgos")));
        filas.add(List.of("Resultados", "Requieren intervención inmediata del OPL", res.get("inmediata")));
        filas.add(List.of("Resultados", "Acciones del plan 5W2H abiertas", res.get("planesAbiertos")));
        Map<String, String> graficas = new LinkedHashMap<>();
        graficas.put("porTipo", "Visitas por tipo");
        graficas.put("porFuerza", "Visitas por fuerza");
        graficas.put("porUnidad", "Visitas por unidad");
        graficas.put("porAuditor", "Visitas por auditor (líder)");
        graficas.put("porEstado", "Visitas por estado");
        graficas.forEach((k, n) -> {
            Object l = d.get(k);
            if (l instanceof List<?> lista) {
                for (Object o : lista) {
                    Map<String, Object> x = (Map<String, Object>) o;
                    filas.add(List.of(n, String.valueOf(x.get("etiqueta")), x.get("valor")));
                }
            }
        });
        Map<String, String> filtros = new LinkedHashMap<>((Map<String, String>) d.get("filtros"));
        if (filtros.isEmpty()) {
            filtros.put("Filtros", "Ninguno");
        }
        filtros.put("Fuente", "Agenda y actas de la Suite DIGSA");
        return archivo(new Reporte("Dashboard de visitas", ahora(), usuario, filtros, columnas, filas), "dashboard", formato);
    }

    private ZonedDateTime ahora() {
        return ZonedDateTime.now(reloj.withZone(Fechas.BOGOTA));
    }

    private Archivo archivo(Reporte r, String base, String formato) {
        String nombre = base + "_" + ahora().format(ARCHIVO);
        return switch (formato == null ? "xlsx" : formato.toLowerCase()) {
            case "csv" -> new Archivo(nombre + ".csv", Exportador.TIPO_CSV, exportador.csv(r));
            case "pdf" -> new Archivo(nombre + ".pdf", Exportador.TIPO_PDF, exportador.pdf(r));
            case "txt" -> new Archivo(nombre + ".txt", Exportador.TIPO_TXT, exportador.txt(r));
            case "xlsx" -> new Archivo(nombre + ".xlsx", Exportador.TIPO_XLSX, exportador.excel(r));
            default -> throw ErrorNegocio.validacion("Formato no válido (TXT, CSV, Excel o PDF).");
        };
    }

    private static String texto(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
