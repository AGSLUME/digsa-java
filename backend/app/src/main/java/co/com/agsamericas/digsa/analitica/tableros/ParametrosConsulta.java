package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;

import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Comparacion;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.ConsultaAnalitica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Granularidad;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Intencion;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Metrica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Nivel;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tablero;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Variable;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.MultiValueMap;

/**
 * Lee la consulta estructurada de los parámetros de la URL (contrato de reportes 2.11: filtros de lista con parámetro repetido,
 * {@code fuerza=EJC&fuerza=ARC}; sin el parámetro = todas). Así la URL de la pantalla, la descarga y el rastreo llevan exactamente la
 * misma consulta. Un parámetro desconocido o un código que no existe responde 422.
 */
final class ParametrosConsulta {

    /** Parámetros que no son de la consulta (los usa el endpoint). */
    static final Set<String> APARTE = Set.of("formato", "pagina", "despues", "periodo", "variable", "texto");
    static final Set<String> GENERALES = Set.of("desde", "hasta", "granularidad", "comparacion", "corte", "nivel", "nivelAtc", "orden");

    private ParametrosConsulta() {
    }

    static ConsultaAnalitica leer(String tablero, MultiValueMap<String, String> p) {
        Tablero t = Tablero.deCodigo(tablero).orElseThrow(() -> ErrorNegocio.noEncontrado("El tablero «" + tablero + "» no existe."));
        Map<Variable, List<String>> filtros = new EnumMap<>(Variable.class);
        for (String k : p.keySet()) {
            if (APARTE.contains(k) || GENERALES.contains(k)) {
                continue;
            }
            Variable v = Variable.deParametro(k).orElseThrow(() -> ErrorNegocio.validacion("Parámetro desconocido: «" + k + "»."));
            List<String> valores = p.get(k).stream().filter(x -> x != null && !x.isBlank()).toList();
            if (!valores.isEmpty()) {
                filtros.put(v, valores);
            }
        }
        return new ConsultaAnalitica(Intencion.TABLERO, t, null, null, filtros, fecha(p, "desde"), fecha(p, "hasta"),
                codigo(p, "granularidad", Granularidad::deCodigo, "La granularidad"), codigo(p, "comparacion", Comparacion::deCodigo, "La comparación"),
                entero(p, "corte"), codigo(p, "nivel", Nivel::deCodigo, "La vista"), entero(p, "nivelAtc") == null ? null : entero(p, "nivelAtc").intValue(),
                codigo(p, "orden", Metrica::deCodigo, "El orden"));
    }

    private static String uno(MultiValueMap<String, String> p, String k) {
        String v = p.getFirst(k);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static LocalDate fecha(MultiValueMap<String, String> p, String k) {
        String v = uno(p, k);
        try {
            return v == null ? null : LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw ErrorNegocio.validacion("La fecha «" + k + "» no es válida (aaaa-mm-dd).");
        }
    }

    private static Long entero(MultiValueMap<String, String> p, String k) {
        String v = uno(p, k);
        try {
            return v == null ? null : Long.valueOf(v);
        } catch (NumberFormatException e) {
            throw ErrorNegocio.validacion("El valor de «" + k + "» no es un número.");
        }
    }

    private static <T> T codigo(MultiValueMap<String, String> p, String k, java.util.function.Function<String, java.util.Optional<T>> de, String que) {
        String v = uno(p, k);
        return v == null ? null : de.apply(v).orElseThrow(() -> ErrorNegocio.validacion(que + " «" + v + "» no es válida."));
    }

    /** Variable de las opciones de filtro. */
    static Variable variable(String v) {
        return Variable.deParametro(v == null ? "" : v).orElseThrow(() -> ErrorNegocio.validacion("La variable «" + v + "» no es un filtro."));
    }

    /** Etiqueta de periodo para la bitácora: solo si tiene la forma de una etiqueta de periodo (lista blanca); si no, un marcador. */
    static String periodoSeguro(String periodo) {
        String p = periodo == null ? "" : periodo.strip();
        return p.matches("^\\d{4}(-(\\d{2}(-\\d{2})?|W\\d{2}|[BTS]\\d))?$") ? p : "(no válido)";
    }

    /** Texto de la consulta para la bitácora (objeto). */
    static String objeto(ConsultaAnalitica q, String extra) {
        if (q == null) {
            return "consulta no válida" + (extra == null ? "" : " · " + extra);
        }
        return (q.tablero() == null ? "consulta" : "Tablero " + q.tablero().codigo()) + (q.nivel() == null ? "" : " · vista " + q.nivel().codigo())
                + (extra == null ? "" : " · " + extra);
    }

    static String formato(String f) {
        if (f == null || !Set.of("xlsx", "csv", "pdf").contains(f)) {
            throw ErrorNegocio.validacion("Formato no válido: use xlsx, csv o pdf.");
        }
        return f;
    }

}
