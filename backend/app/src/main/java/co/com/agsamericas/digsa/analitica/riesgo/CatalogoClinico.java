package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Corte;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lo que cohortes y alertas necesitan saber de cada medicamento y diagnóstico de un corte, leído una vez por recálculo (pocos miles de
 * datos originales aunque el corte tenga millones de registros), y las listas de referencia vigentes.
 *
 * <p><b>ATC y principio activo clínico</b> (decisión A4 2026-10-23): el dato final de la tabla maestra vigente al corte si el dato original
 * está «homologado»; si no, el ATC y el principio activo que trae el insumo (normalizados). Así una cohorte o una alerta clínica no
 * depende de que la homologación esté completa (el mismo principio de dos CUM cuenta uno), y la tarjeta y la alerta dicen cuántos
 * registros se clasificaron con el dato del insumo. Los tableros 1–9 siguen agrupando solo por la tabla maestra («Sin homologar»).
 *
 * <p>CIE-10: el código final homologado (R-060) o, si no lo está, el código normalizado del insumo.
 */
final class CatalogoClinico {

    /** Medicamento (dato original) con su clasificación clínica. */
    record Med(long id, String atc, String principio, String cum, String descripcion, String firma, boolean homologado, boolean inconsistenteTm) {

        String atcNivel(int largo) {
            return atc == null || atc.length() < largo ? null : atc.substring(0, largo);
        }

        /** Llave del principio activo para contar distintos: el principio o, si no se conoce, el propio medicamento. */
        String llavePrincipio() {
            return principio != null ? principio : "MED:" + id;
        }
    }

    final Map<Long, Med> meds = new HashMap<>();
    final Map<String, String> cieFinal = new HashMap<>();
    Set<String> estrecho = Set.of();
    Set<String> control = Set.of();
    Set<String> vitales = Set.of();
    Set<String> interacciones = Set.of();
    /** Prefijo ATC ⇒ prefijos CIE-10 admitidos (indicaciones). */
    Map<String, List<String>> indicaciones = Map.of();
    List<String> huerfanas = List.of();
    String fuenteEstrecho;
    String fuenteControl;
    String fuenteVitales;
    String fuenteInteracciones;
    String fuenteIndicaciones;
    String fuenteHuerfanas;
    boolean profesional;
    /** Registros que se clasificaron con el ATC o el principio del insumo (no homologados). */
    final Set<Long> delInsumo = new HashSet<>();

    static CatalogoClinico cargar(JdbcClient j, Corte c) {
        CatalogoClinico k = new CatalogoClinico();
        Long[] cargues = c.cargues().toArray(Long[]::new);
        j.sql("""
                WITH tm AS MATERIALIZED (
                    SELECT origen_id, estado, fin_atc, fin_principio_activo FROM hom_tabla_maestra_version(CAST(:hv AS integer)))
                SELECT o.id, o.atc, o.principio_activo, o.cum_normalizado, o.descripcion, tm.estado, tm.fin_atc, tm.fin_principio_activo
                FROM hom_medicamento_origen o LEFT JOIN tm ON tm.origen_id = o.id
                WHERE o.id IN (SELECT DISTINCT valor::bigint FROM ana_cargue_valores WHERE cargue_id = ANY(:cargues) AND variable = 'medicamento')""")
                .param("hv", c.homVersion()).param("cargues", cargues).query((rs, n) -> {
                    boolean hom = "homologado".equals(rs.getString("estado"));
                    String atc = hom && rs.getString("fin_atc") != null ? rs.getString("fin_atc") : rs.getString("atc");
                    String principio = hom && rs.getString("fin_principio_activo") != null ? rs.getString("fin_principio_activo")
                            : rs.getString("principio_activo");
                    String desc = rs.getString("descripcion");
                    Med m = new Med(rs.getLong("id"), atc == null || atc.isBlank() ? null : atc.strip().toUpperCase(Locale.ROOT),
                            principio == null || principio.isBlank() ? null : AnaliticaHomologacion.normalizarTexto(principio),
                            rs.getString("cum_normalizado"), desc, AnaliticaAlertas.firmaConcentracion(desc), hom, "inconsistente".equals(rs.getString("estado")));
                    k.meds.put(m.id(), m);
                    if (!hom) {
                        k.delInsumo.add(m.id());
                    }
                    return null;
                }).list();
        if (c.homVersion() != null) {
            j.sql("""
                    SELECT o.codigo_original, t.codigo_final FROM hom_tabla_cie10_version(CAST(:hv AS integer)) t JOIN hom_cie10_origen o ON o.id = t.origen_id
                    WHERE t.estado = 'homologado'""").param("hv", c.homVersion())
                    .query((rs, n) -> k.cieFinal.put(rs.getString(1), rs.getString(2).strip())).list();
        }
        // Listas normativas publicadas (estrecho margen y control especial, V61): la versión vigente al recalcular, con su fuente y número
        Map<String, Set<String>> listas = new HashMap<>();
        Map<String, String> fuentes = new HashMap<>();
        j.sql("""
                SELECT l.lista, l.principio_norm, f.nombre, v.numero, v.ejemplo FROM ref_lista_normativa l JOIN ref_version v ON v.id = l.version_id
                JOIN ref_fuente f ON f.id = v.fuente_id WHERE v.estado = 'publicada'""").query((rs, n) -> {
                    listas.computeIfAbsent(rs.getString(1), x -> new TreeSet<>()).add(AnaliticaHomologacion.normalizarTexto(rs.getString(2)));
                    fuentes.put(rs.getString(1), rs.getString(3) + " v" + rs.getInt(4) + (rs.getBoolean(5) ? " (datos de ejemplo)" : ""));
                    return null;
                }).list();
        if (listas.containsKey("estrecho_margen")) {
            k.estrecho = Set.copyOf(listas.get("estrecho_margen"));
            k.fuenteEstrecho = fuentes.get("estrecho_margen");
        }
        if (listas.containsKey("control_especial")) {
            k.control = Set.copyOf(listas.get("control_especial"));
            k.fuenteControl = fuentes.get("control_especial");
        }
        List<Object[]> vit = j.sql("""
                SELECT r.principio_norm1, f.nombre, v.numero FROM ref_vital_no_disponible r JOIN ref_version v ON v.id = r.version_id
                JOIN ref_fuente f ON f.id = v.fuente_id WHERE v.estado = 'publicada' AND r.principio_norm1 IS NOT NULL""")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2) + " v" + rs.getInt(3)}).list();
        if (!vit.isEmpty()) {
            Set<String> s = new TreeSet<>();
            vit.forEach(x -> s.add(AnaliticaHomologacion.normalizarTexto((String) x[0])));
            k.vitales = Set.copyOf(s);
            k.fuenteVitales = (String) vit.getFirst()[1];
        }
        // Referencias de cohortes y alertas cargadas por archivo (V63): la versión vigente de cada lista
        Set<String> pares = new TreeSet<>();
        Map<String, List<String>> ind = new HashMap<>();
        List<String> huer = new ArrayList<>();
        j.sql("""
                SELECT v.lista, v.version, v.fuente, v.ficticio, f.codigo1, f.codigo2 FROM ana_ref_versiones v JOIN ana_ref_filas f ON f.version_id = v.id
                WHERE v.vigente ORDER BY v.lista, f.linea""").query((rs, n) -> {
                    String lista = rs.getString(1);
                    String fuente = rs.getString(3) + " v" + rs.getInt(2) + (rs.getBoolean(4) ? " (datos de ejemplo ficticios)" : "");
                    switch (lista) {
                        case "interacciones" -> {
                            pares.add(AnaliticaAlertas.par(rs.getString(5), rs.getString(6)));
                            k.fuenteInteracciones = fuente;
                        }
                        case "indicaciones" -> {
                            ind.computeIfAbsent(rs.getString(5), x -> new ArrayList<>()).add(rs.getString(6));
                            k.fuenteIndicaciones = fuente;
                        }
                        default -> {
                            huer.add(rs.getString(5));
                            k.fuenteHuerfanas = fuente;
                        }
                    }
                    return null;
                }).list();
        k.interacciones = Set.copyOf(pares);
        k.indicaciones = Map.copyOf(ind);
        k.huerfanas = List.copyOf(huer);
        k.profesional = Boolean.TRUE.equals(j.sql("SELECT EXISTS (SELECT 1 FROM ana_cargues WHERE id = ANY(:c) AND 'profesional' = ANY(campos_presentes))")
                .param("c", cargues).query(Boolean.class).single());
        return k;
    }

    Med med(Long id) {
        return id == null ? null : meds.get(id);
    }

    /** Código CIE-10 clínico: el homologado o el del insumo. */
    String cie(String original) {
        if (original == null) {
            return null;
        }
        return cieFinal.getOrDefault(original, original);
    }

    /** Fuente de una lista de cohorte (nula: no hay lista cargada). */
    String fuenteLista(co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Lista l) {
        return switch (l) {
            case ESTRECHO_MARGEN -> fuenteEstrecho;
            case CONTROL_ESPECIAL -> fuenteControl;
            case HUERFANAS -> fuenteHuerfanas;
        };
    }
}
