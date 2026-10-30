package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.riesgo.PropiedadesRiesgo;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Ambito;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.CohorteResuelta;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.FiltroCohorte;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Dimension;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Variable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Cohorte, GCF y GRC como filtro de los tableros (A7.1; dudas AN.22 y AN.30). Resuelve cada valor contra el corte de la consulta:
 * <ul>
 * <li><b>Cohorte</b> (por su código): la versión de mayor número con resultado en el corte (la misma regla de las tarjetas del tablero 10).
 * Debe estar «evaluada» y conservar la membresía de ese corte (los últimos cortes de cada versión, AN.19); si no, 422 con el motivo (no se
 * responde con cifras en cero). Varias cohortes se combinan con O (miembro de alguna en el mes).</li>
 * <li><b>GCF y GRC</b> (por grupo): la versión de la partición predefinida «gcf» o «grc» con su membresía del corte; los grupos pedidos deben
 * existir en el corte dentro del alcance (inexistente o fuera del alcance: el mismo 422 de los demás filtros).</li>
 * </ul>
 * Pertenecer a una cohorte en un mes = ser miembro en ese mes en la membresía mensual del corte (se evalúa en los meses con dispensación;
 * ingresos y salidas con gracia no cambian la membresía, AN.25; ATC y principio del insumo si no hay homologación, AN.20; alto costo con
 * el umbral de la cohorte, AN.26). Las cohortes sensibles (VIH, huérfanas) marcan el ámbito para la supresión k de AN.24.
 */
final class FiltroCohortes {

    private FiltroCohortes() {
    }

    /** Fila de la versión elegida de una cohorte en el corte. */
    private record Version(String codigo, String nombre, long versionId, int version, String criterio, String tipo, String estado, String motivo,
                           boolean miembros, String criterios) {
    }

    /** Versión de la cohorte que se usa en el corte: la de mayor número con resultado en el corte; si ninguna tiene, la de mayor número. */
    private static Optional<Version> version(JdbcClient j, String codigo, long corte) {
        return j.sql("""
                SELECT c.codigo, c.nombre, v.id, v.version, v.criterio_texto, v.criterios ->> 'tipo' AS tipo, r.estado, r.motivo, coalesce(r.miembros, false),
                       v.criterios::text
                FROM ana_cohortes c JOIN ana_cohortes_versiones v ON v.cohorte_id = c.id
                LEFT JOIN ana_cohortes_resultados r ON r.version_id = v.id AND r.corte_cargue_id = :c
                WHERE c.codigo = :codigo ORDER BY (r.estado IS NOT NULL) DESC, v.version DESC LIMIT 1""").param("c", corte).param("codigo", codigo)
                .query((rs, n) -> new Version(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getBoolean(9), rs.getString(10)))
                .optional();
    }

    /** Filtros de cohorte resueltos y si la consulta es sensible (cohorte, CIE-10, ATC o grupo de GCF de VIH o huérfanas; AN.31). */
    record Resolucion(Map<Variable, FiltroCohorte> cohortes, boolean sensible) {
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static AnaliticaCohortes.Criterio criterio(String json) {
        try {
            return AnaliticaCohortes.leer(JSON.readValue(json, Object.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Códigos sensibles de la configuración (AN.31): CIE-10, ATC y listas de los criterios vigentes de las cohortes sensibles
     * ({@code digsa.analitica.riesgo.cohortes-sensibles}) y los CIE-10 de la lista de huérfanas vigente. Ninguno si {@code supresion-sensibles} = 0.
     */
    static AnaliticaCohortes.Sensibles sensibles(JdbcClient j, PropiedadesRiesgo riesgo) {
        if (riesgo.kSensibles() <= 0 || riesgo.sensibles().isEmpty()) {
            return AnaliticaCohortes.Sensibles.NINGUNO;
        }
        List<AnaliticaCohortes.Criterio> criterios = new ArrayList<>();
        for (String json : j.sql("""
                SELECT v.criterios::text FROM ana_cohortes c JOIN ana_cohortes_versiones v ON v.cohorte_id = c.id
                WHERE c.codigo = ANY(CAST(:codigos AS text[])) AND v.vigente""").param("codigos", riesgo.sensibles().toArray(String[]::new))
                .query(String.class).list()) {
            AnaliticaCohortes.Criterio c = criterio(json);
            if (c != null) {
                criterios.add(c);
            }
        }
        AnaliticaCohortes.Sensibles base = AnaliticaCohortes.Sensibles.de(criterios, List.of());
        List<String> deListas = base.listas().isEmpty() ? List.of() : j.sql("""
                SELECT DISTINCT f.codigo1 FROM ana_ref_versiones v JOIN ana_ref_filas f ON f.version_id = v.id
                WHERE v.vigente AND v.lista = ANY(CAST(:listas AS text[]))""").param("listas", base.listas().toArray(String[]::new))
                .query(String.class).list().stream().map(x -> AnaliticaConsulta.normalizarValor(Variable.CIE10, x)).toList();
        return AnaliticaCohortes.Sensibles.de(criterios, deListas);
    }

    /** ¿Es sensible la versión de la cohorte? Por su código (configuración) o por su criterio (usa un código o una lista sensible). */
    private static boolean sensible(Version v, AnaliticaCohortes.Sensibles s, PropiedadesRiesgo riesgo) {
        if (riesgo.kSensibles() <= 0 || "particion".equals(v.tipo())) {
            return false;
        }
        if (riesgo.sensibles().contains(v.codigo())) {
            return true;
        }
        AnaliticaCohortes.Criterio c = criterio(v.criterios());
        return c != null && s.criterio(c);
    }

    /** ¿Es sensible el valor de un filtro? (cohorte por su versión; CIE-10, ATC y grupos de GCF por solaparse con los códigos sensibles) */
    private static boolean valorSensible(Variable v, String valor, AnaliticaCohortes.Sensibles s) {
        return switch (v) {
            case CIE10 -> s.cie10(valor);
            case ATC, GCF -> s.atc(valor);
            default -> false;
        };
    }

    /** ¿El valor de la variable es sensible en el corte? (predicción: la serie usa la supresión reforzada) */
    static boolean sensible(JdbcClient j, Variable v, String valor, long corte, PropiedadesRiesgo riesgo) {
        AnaliticaCohortes.Sensibles s = sensibles(j, riesgo);
        if (v == Variable.COHORTE) {
            return version(j, valor, corte).map(x -> sensible(x, s, riesgo)).orElse(false);
        }
        return valorSensible(v, valor, s);
    }

    /** Valida que la versión sirva para filtrar en el corte; si no, 422 con el motivo (nunca cifras en cero). */
    private static CohorteResuelta usable(Version v, Variable variable, long corte, PropiedadesRiesgo riesgo, AnaliticaCohortes.Sensibles s) {
        String nombre = "«" + v.nombre() + "»";
        if (v.estado() == null) {
            throw campo("La cohorte " + nombre + " aún no tiene resultados en el corte del cargue " + corte
                    + ": se calcula después de aplicar el cargue o con «Recalcular» en «Cohortes y riesgo».", variable);
        }
        if (!"evaluada".equals(v.estado())) {
            throw campo("La cohorte " + nombre + " no está evaluada en el corte del cargue " + corte + ": " + v.motivo(), variable);
        }
        if (!v.miembros() && "particion".equals(v.tipo())) {
            throw campo("La partición " + nombre + " no tiene su membresía por grupo en el corte del cargue " + corte + " (corte calculado antes de"
                    + " A7.1 o fuera de los últimos " + riesgo.cortesMembresia() + " cortes): use «Recalcular» en «Cohortes y riesgo» o elija un corte más"
                    + " reciente.", variable);
        }
        if (!v.miembros()) {
            throw campo("La cohorte " + nombre + " solo conserva su serie mensual en el corte del cargue " + corte + " (la membresía por mes se guarda"
                    + " para los últimos " + riesgo.cortesMembresia() + " cortes de cada versión): elija un corte más reciente para filtrar por ella.", variable);
        }
        return new CohorteResuelta(v.codigo(), v.nombre(), v.versionId(), v.version(), v.criterio(), sensible(v, s, riesgo));
    }

    private static ErrorNegocio campo(String mensaje, Variable v) {
        return new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, mensaje, Map.of("variable", v.parametro()));
    }

    /**
     * Resuelve los filtros de cohorte, GCF y GRC del ámbito contra su corte y su alcance, y decide si la consulta es sensible (AN.31): una
     * cohorte sensible (por código o por criterio) o un valor de CIE-10, ATC o GCF que se solapa con los códigos sensibles. Mezclar en una
     * misma variable un valor sensible con otros no sensibles responde 422 (P2-4: con «O», la diferencia entre «A o VIH» y «A» daría VIH).
     */
    static Resolucion resolver(JdbcClient j, Ambito a, PropiedadesRiesgo riesgo) {
        Map<Variable, FiltroCohorte> r = new EnumMap<>(Variable.class);
        long corte = a.corte().cargueId();
        AnaliticaCohortes.Sensibles s = sensibles(j, riesgo);
        boolean sensible = false;
        for (Map.Entry<Variable, List<String>> e : a.filtros().entrySet()) {
            Variable v = e.getKey();
            if (e.getValue().isEmpty()) {
                continue;
            }
            List<Boolean> sens = new ArrayList<>();
            if (v == Variable.COHORTE) {
                List<CohorteResuelta> l = new ArrayList<>();
                for (String codigo : e.getValue()) {
                    Version x = version(j, codigo, corte).orElseThrow(() -> campo(AnaliticaConsulta.mensajeInexistente(v, codigo), v));
                    if ("particion".equals(x.tipo())) {
                        throw campo("«" + x.nombre() + "» es una partición: filtre por sus grupos con GCF o GRC.", v);
                    }
                    CohorteResuelta c = usable(x, v, corte, riesgo, s);
                    l.add(c);
                    sens.add(c.sensible());
                }
                r.put(v, new FiltroCohorte(v, List.copyOf(l), List.of()));
            } else if (v.deCohorte()) {
                Version x = version(j, v.particion(), corte).filter(y -> "particion".equals(y.tipo()))
                        .orElseThrow(() -> campo("La partición " + v.etiqueta() + " no está definida.", v));
                CohorteResuelta c = usable(x, v, corte, riesgo, s);
                List<String> hay = grupos(j, a, c.versionId());
                for (String g : e.getValue()) {
                    if (!hay.contains(g)) {
                        throw campo(AnaliticaConsulta.mensajeInexistente(v, g), v);
                    }
                    sens.add(valorSensible(v, g, s));
                }
                r.put(v, new FiltroCohorte(v, List.of(c), List.copyOf(e.getValue())));
            } else {
                for (String x : e.getValue()) {
                    sens.add(valorSensible(v, x, s));
                }
            }
            boolean alguno = sens.contains(Boolean.TRUE);
            if (alguno && sens.contains(Boolean.FALSE)) {
                throw campo("No se puede combinar un valor sensible (VIH o enfermedades huérfanas) con otros en el filtro " + v.etiqueta()
                        + ": la diferencia entre las dos consultas revelaría las cifras de la cohorte sensible. Consúltelo solo.", v);
            }
            sensible |= alguno;
        }
        return new Resolucion(r, sensible);
    }

    /**
     * Grupos de la partición con pacientes en el corte DENTRO del alcance (de la serie agregada de la versión: nacional, la Fuerza o los ESM
     * del punto), toda la historia.
     */
    static List<String> grupos(JdbcClient j, Ambito a, long versionId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("v", versionId);
        p.put("c", a.corte().cargueId());
        String nivel = switch (a.alcance()) {
            case Alcance.Todo t -> "s.nivel = 'nacional'";
            case Alcance.Fuerza f -> {
                p.put("f", f.codigo());
                yield "s.nivel = 'fuerza' AND s.codigo = :f";
            }
            case Alcance.Esm e -> {
                p.put("p", e.puntoId());
                p.put("cargues", a.corte().cargues().toArray(Long[]::new));
                yield "s.nivel = 'esm' AND s.codigo IN (SELECT DISTINCT valor FROM ana_cargue_valores WHERE cargue_id = ANY(CAST(:cargues AS bigint[]))"
                        + " AND variable = 'esm' AND punto_id = :p)";
            }
            default -> throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Su perfil no tiene un alcance de datos para la analítica farmacéutica.");
        };
        return new ArrayList<>(new TreeSet<>(j.sql("SELECT DISTINCT s.grupo FROM ana_cohortes_serie s WHERE s.version_id = :v AND s.corte_cargue_id = :c"
                + " AND s.grupo <> '' AND s.pacientes > 0 AND " + nivel).params(p).query(String.class).list()));
    }

    /**
     * Opciones del filtro (valor y etiqueta): las cohortes que se pueden usar en el corte (evaluadas y con membresía; sin las particiones) o
     * los grupos de GCF / GRC del alcance. Vacío si la partición no sirve en el corte.
     */
    static List<Map<String, String>> opciones(JdbcClient j, Ambito a, Variable v, PropiedadesRiesgo riesgo) {
        long corte = a.corte().cargueId();
        List<Map<String, String>> r = new ArrayList<>();
        if (v == Variable.COHORTE) {
            List<String> codigos = j.sql("SELECT codigo FROM ana_cohortes ORDER BY orden, id").query(String.class).list();
            for (String codigo : codigos) {
                version(j, codigo, corte).filter(x -> !"particion".equals(x.tipo()) && "evaluada".equals(x.estado()) && x.miembros())
                        .ifPresent(x -> r.add(Map.of("valor", x.codigo(), "etiqueta", x.nombre() + " · v" + x.version())));
            }
            return r;
        }
        Optional<Version> x = version(j, v.particion(), corte).filter(y -> "particion".equals(y.tipo()) && "evaluada".equals(y.estado()) && y.miembros());
        if (x.isEmpty()) {
            return r;
        }
        List<String> g = grupos(j, a, x.get().versionId());
        Etiquetas et = new Etiquetas(j);
        if (v == Variable.GCF) {
            et.cargar(Dimension.ATC2, g.stream().filter(y -> !y.startsWith("SIN_")).toList());
        }
        for (String y : g) {
            r.add(Map.of("valor", y, "etiqueta", etiqueta(et, v, y)));
        }
        return r;
    }

    /**
     * Top {@value AnaliticaConsulta#TOP} por gasto de las cohortes (o de los grupos de GCF / GRC) en el corte, para el nivel de la serie de
     * la predicción (AN.30): de la serie agregada de cada versión (ana_cohortes_serie, el mismo gasto que da el filtro), toda la historia.
     * Solo códigos y nombres: el gasto ordena, no se devuelve.
     */
    static List<Map<String, String>> top(JdbcClient j, Ambito a, Variable v, AnaliticaConsulta.Nivel nivel, String nivelValor, PropiedadesRiesgo riesgo) {
        long corte = a.corte().cargueId();
        Map<Long, String[]> candidatos = new LinkedHashMap<>();
        if (v == Variable.COHORTE) {
            for (String codigo : j.sql("SELECT codigo FROM ana_cohortes ORDER BY orden, id").query(String.class).list()) {
                version(j, codigo, corte).filter(x -> !"particion".equals(x.tipo()) && "evaluada".equals(x.estado()) && x.miembros())
                        .ifPresent(x -> candidatos.put(x.versionId(), new String[] {x.codigo(), x.nombre() + " · v" + x.version()}));
            }
        } else {
            version(j, v.particion(), corte).filter(x -> "particion".equals(x.tipo()) && "evaluada".equals(x.estado()) && x.miembros())
                    .ifPresent(x -> candidatos.put(x.versionId(), null));
        }
        if (candidatos.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("vs", candidatos.keySet().toArray(Long[]::new));
        p.put("c", corte);
        p.put("val", nivelValor == null ? "" : nivelValor);
        String donde = switch (nivel) {
            case NACIONAL -> "s.nivel = 'nacional'";
            case FUERZA -> "s.nivel = 'fuerza' AND s.codigo = :val";
            case ESM -> {
                if (a.alcance() instanceof Alcance.Fuerza f) {
                    p.put("f", f.codigo());
                    yield "s.nivel = 'esm' AND s.codigo = :val AND s.fuerza = :f";
                }
                yield "s.nivel = 'esm' AND s.codigo = :val";
            }
        };
        List<Object[]> filas = j.sql("SELECT s.version_id, s.grupo, sum(s.gasto_centavos) AS g FROM ana_cohortes_serie s WHERE s.version_id = ANY(CAST(:vs AS"
                + " bigint[])) AND s.corte_cargue_id = :c AND " + donde + " GROUP BY s.version_id, s.grupo HAVING sum(s.gasto_centavos) > 0"
                + " ORDER BY g DESC, s.version_id, s.grupo").params(p).query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).list();
        Etiquetas et = new Etiquetas(j);
        if (v == Variable.GCF) {
            et.cargar(Dimension.ATC2, filas.stream().map(x -> (String) x[1]).filter(x -> !x.isEmpty() && !x.startsWith("SIN_")).toList());
        }
        List<Map<String, String>> r = new ArrayList<>();
        for (Object[] x : filas) {
            if (r.size() >= AnaliticaConsulta.TOP) {
                break;
            }
            String grupo = (String) x[1];
            if (v == Variable.COHORTE) {
                String[] c = candidatos.get((Long) x[0]);
                if (grupo.isEmpty() && c != null) {
                    r.add(Map.of("valor", c[0], "etiqueta", c[1]));
                }
            } else if (!grupo.isEmpty()) {
                r.add(Map.of("valor", grupo, "etiqueta", etiqueta(et, v, grupo)));
            }
        }
        return r;
    }

    /** Nombre de un grupo: «A10 · nombre del ATC» en GCF, «GRC2 · un diagnóstico crónico» en GRC. */
    static String etiqueta(Etiquetas et, Variable v, String grupo) {
        if (v == Variable.GCF && !grupo.startsWith("SIN_")) {
            return et.de(Dimension.ATC2, grupo);
        }
        return AnaliticaCohortes.etiquetaGrupo(grupo);
    }
}
