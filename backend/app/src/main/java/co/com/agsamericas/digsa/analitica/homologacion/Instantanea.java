package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.CodigoSsfm;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Configuracion;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Forma;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.IndiceSsfm;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Origen;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Presentacion;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.PrincipioCum;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.ReferenciaCie10;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Referencias;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Regla;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lo que una ejecución de la homologación lee al empezar y usa hasta terminar (publicación atómica: si se publica otra versión mientras
 * corre, esta termina con las que tomó): reglas y catálogos vigentes con sus versiones, y la versión publicada de cada fuente oficial.
 * Arma las {@link Referencias} de un lote de datos originales trayendo de la base solo las presentaciones del CUM que pueden consultar.
 */
record Instantanea(Configuracion configuracion, Map<String, Object> reglas, Map<String, Integer> catalogos, Map<String, Fuente> fuentes) {

    /** Versión publicada de una fuente. */
    record Fuente(int versionId, String codigo, String nombre, String tipo, String conjunto, String lista, int numero, LocalDate fechaCorte,
                  boolean ejemplo) {
    }

    private static final TypeReference<Map<String, Object>> MAPA = new TypeReference<>() {
    };

    /** Lee la configuración vigente y las versiones publicadas. */
    static Instantanea cargar(JdbcClient jdbc, ObjectMapper json) {
        Map<String, Regla> reglas = new LinkedHashMap<>();
        Map<String, Object> reglasJson = new TreeMap<>();
        jdbc.sql("SELECT id, orden, activa, parametros::text, version FROM hom_regla ORDER BY orden, id").query(rs -> {
            Map<String, Object> p = leer(json, rs.getString(4));
            Map<String, String> params = new TreeMap<>();
            p.forEach((k, v) -> params.put(k, v == null ? null : String.valueOf(v)));
            reglas.put(rs.getString(1), new Regla(rs.getString(1), rs.getInt(2), rs.getBoolean(3), params));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("version", rs.getInt(5));
            r.put("orden", rs.getInt(2));
            r.put("activa", rs.getBoolean(3));
            r.put("parametros", params);
            reglasJson.put(rs.getString(1), r);
        });
        Map<String, String> sinonimos = new HashMap<>();
        Set<String> sales = new HashSet<>();
        Map<String, String> abreviaturas = new HashMap<>();
        Map<String, String> unidades = new HashMap<>();
        Map<String, Forma> formas = new HashMap<>();
        Map<String, Integer> catalogos = new TreeMap<>();
        jdbc.sql("""
                SELECT c.tipo, c.version, c.clave, c.valor, c.extra FROM hom_catalogo c
                JOIN (SELECT tipo, max(version) AS version FROM hom_catalogo GROUP BY tipo) v ON v.tipo = c.tipo AND v.version = c.version""")
                .query(rs -> {
                    String tipo = rs.getString(1);
                    catalogos.put(tipo, rs.getInt(2));
                    String k = rs.getString(3);
                    String v = rs.getString(4);
                    switch (tipo) {
                        case "sinonimo_dci" -> sinonimos.put(k, v);
                        case "sal" -> sales.add(k);
                        case "abreviatura" -> abreviaturas.put(k, v);
                        case "unidad" -> unidades.put(k, v);
                        case "forma" -> formas.put(k, new Forma(v, rs.getString(5)));
                        default -> {
                        }
                    }
                });
        Configuracion c = new Configuracion(reglas, sinonimos, sales, abreviaturas, unidades, formas);
        return new Instantanea(c, reglasJson, catalogos, publicadas(jdbc));
    }

    /** Versión publicada de cada fuente (código ⇒ versión). */
    static Map<String, Fuente> publicadas(JdbcClient jdbc) {
        Map<String, Fuente> m = new TreeMap<>();
        jdbc.sql("""
                SELECT v.id, f.codigo, f.nombre, f.tipo, f.conjunto, f.lista, v.numero, v.fecha_corte, v.ejemplo
                FROM ref_version v JOIN ref_fuente f ON f.id = v.fuente_id WHERE v.estado = 'publicada'""")
                .query(rs -> {
                    m.put(rs.getString(2), new Fuente(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getInt(7), rs.getDate(8).toLocalDate(), rs.getBoolean(9)));
                });
        return m;
    }

    /** Reglas de hoy con la forma de hom_version.reglas (id ⇒ versión, orden, activa, parámetros), sin armar la configuración. */
    static Map<String, Object> reglasActuales(JdbcClient jdbc, ObjectMapper json) {
        Map<String, Object> m = new TreeMap<>();
        jdbc.sql("SELECT id, orden, activa, parametros::text, version FROM hom_regla").query(rs -> {
            Map<String, String> params = new TreeMap<>();
            leer(json, rs.getString(4)).forEach((k, v) -> params.put(k, v == null ? null : String.valueOf(v)));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("version", rs.getInt(5));
            r.put("orden", rs.getInt(2));
            r.put("activa", rs.getBoolean(3));
            r.put("parametros", params);
            m.put(rs.getString(1), r);
        });
        return m;
    }

    /** Versión vigente de cada catálogo (tipo ⇒ versión), como hom_version.catalogos. */
    static Map<String, Integer> catalogosActuales(JdbcClient jdbc) {
        Map<String, Integer> m = new TreeMap<>();
        jdbc.sql("SELECT tipo, max(version) FROM hom_catalogo GROUP BY tipo").query(rs -> {
            m.put(rs.getString(1), rs.getInt(2));
        });
        return m;
    }

    static Map<String, Object> leer(ObjectMapper json, String texto) {
        try {
            return texto == null ? Map.of() : json.readValue(texto, MAPA);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON ilegible", e);
        }
    }

    static List<Object> leerLista(ObjectMapper json, String texto) {
        try {
            return texto == null ? List.of() : json.readValue(texto, new TypeReference<List<Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON ilegible", e);
        }
    }

    /** Versiones publicadas del CUM (los cuatro conjuntos que haya). */
    List<Integer> versionesCum() {
        return fuentes.values().stream().filter(f -> "cum".equals(f.tipo())).map(Fuente::versionId).sorted().toList();
    }

    /** Versión publicada del conjunto del CUM. */
    Optional<Fuente> cum(String conjunto) {
        return fuentes.values().stream().filter(f -> "cum".equals(f.tipo()) && conjunto.equals(f.conjunto())).findFirst();
    }

    Optional<Fuente> deTipo(String tipo) {
        return fuentes.values().stream().filter(f -> tipo.equals(f.tipo())).findFirst();
    }

    /** Lo que se guarda en hom_version.fuentes: código ⇒ id, número, fecha de corte y ejemplo. */
    Map<String, Object> fuentesJson() {
        Map<String, Object> m = new TreeMap<>();
        fuentes.forEach((k, f) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", f.versionId());
            x.put("numero", f.numero());
            x.put("fechaCorte", f.fechaCorte().toString());
            x.put("ejemplo", f.ejemplo());
            m.put(k, x);
        });
        return m;
    }

    /**
     * Referencias para homologar estos datos originales: las presentaciones del CUM con sus CUM o con sus bases de principio activo, en
     * las versiones publicadas de la instantánea, y el catálogo SSFM publicado.
     */
    Referencias referencias(JdbcClient jdbc, Collection<Origen> origenes) {
        Set<String> cums = new HashSet<>();
        Set<String> bases = new HashSet<>();
        for (Origen o : origenes) {
            cums.addAll(AnaliticaHomologacion.cumsDe(o));
            bases.addAll(AnaliticaHomologacion.basesDe(o, configuracion));
        }
        return referencias(jdbc, cums, bases);
    }

    /** Referencias con los CUM y las bases de principio activo dados. */
    Referencias referencias(JdbcClient jdbc, Set<String> cums, Set<String> bases) {
        List<Integer> versiones = versionesCum();
        Map<String, List<Presentacion>> porCum = new HashMap<>();
        Map<String, List<Presentacion>> porBase = new HashMap<>();
        if (!versiones.isEmpty() && (!cums.isEmpty() || !bases.isEmpty())) {
            Map<Integer, String> conjunto = new HashMap<>();
            fuentes.values().stream().filter(f -> "cum".equals(f.tipo())).forEach(f -> conjunto.put(f.versionId(), f.conjunto()));
            String claves = """
                    WITH k AS (
                        SELECT version_id, cum FROM ref_cum_presentacion WHERE cum = ANY(:cums) AND version_id = ANY(:vs)
                        UNION
                        SELECT version_id, cum FROM ref_cum_principio_activo WHERE principio_base = ANY(:bases) AND version_id = ANY(:vs)
                    )""";
            Integer[] vs = versiones.toArray(Integer[]::new);
            String[] cs = cums.toArray(String[]::new);
            String[] bs = bases.toArray(String[]::new);
            Map<String, List<PrincipioCum>> principios = new HashMap<>();
            Map<String, Set<String>> basesDe = new HashMap<>();
            jdbc.sql(claves + """
                    SELECT a.version_id, a.cum, a.principio_activo, a.cantidad, a.unidad_medida, a.unidad_referencia, a.principio_base
                    FROM k JOIN ref_cum_principio_activo a ON a.version_id = k.version_id AND a.cum = k.cum
                    ORDER BY a.version_id, a.cum, a.principio_norm""")
                    .param("cums", cs).param("bases", bs).param("vs", vs).query(rs -> {
                        String llave = rs.getInt(1) + "|" + rs.getString(2);
                        principios.computeIfAbsent(llave, x -> new ArrayList<>()).add(new PrincipioCum(rs.getString(3), rs.getBigDecimal(4),
                                rs.getString(5), rs.getString(6)));
                        basesDe.computeIfAbsent(llave, x -> new HashSet<>()).add(rs.getString(7));
                    });
            jdbc.sql(claves + """
                    SELECT p.version_id, p.cum, p.producto, p.titular, p.registro_sanitario, p.fecha_vencimiento, p.estado_registro, p.estado_cum,
                           p.cantidad_cum, p.descripcion_comercial, p.unidad, p.atc, p.descripcion_atc, p.via_administracion, p.forma_farmaceutica, p.ium
                    FROM k JOIN ref_cum_presentacion p ON p.version_id = k.version_id AND p.cum = k.cum""")
                    .param("cums", cs).param("bases", bs).param("vs", vs).query(rs -> {
                        String llave = rs.getInt(1) + "|" + rs.getString(2);
                        Date venc = rs.getDate(6);
                        Presentacion p = new Presentacion(rs.getString(2), conjunto.get(rs.getInt(1)), rs.getString(3), rs.getString(4),
                                rs.getString(5), venc == null ? null : venc.toLocalDate(), rs.getString(7), rs.getString(8), rs.getBigDecimal(9),
                                rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15),
                                rs.getString(16), principios.getOrDefault(llave, List.of()));
                        porCum.computeIfAbsent(p.cum(), x -> new ArrayList<>()).add(p);
                        for (String b : basesDe.getOrDefault(llave, Set.of())) {
                            porBase.computeIfAbsent(b, x -> new ArrayList<>()).add(p);
                        }
                    });
            Comparator<Presentacion> orden = Comparator.comparing(Presentacion::cum)
                    .thenComparing(p -> AnaliticaHomologacion.CONJUNTOS.indexOf(p.conjunto()));
            porCum.values().forEach(l -> l.sort(orden));
            porBase.values().forEach(l -> l.sort(orden));
        }
        boolean hayCum = !versiones.isEmpty();
        Optional<Fuente> ssfm = deTipo("ssfm");
        List<CodigoSsfm> codigos = ssfm.map(f -> jdbc.sql("""
                SELECT codigo, cum, principio_norm, concentracion, forma FROM ref_codigo_ssfm WHERE version_id = :v ORDER BY codigo""")
                .param("v", f.versionId())
                .query((rs, n) -> new CodigoSsfm(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))).list())
                .orElse(List.of());
        return new Referencias() {
            /** Catálogo SSFM indexado una sola vez por ejecución (R-030), con las formas de la configuración de la instantánea. */
            private IndiceSsfm indice;
            private Map<String, Forma> formasDelIndice;

            @Override
            public synchronized IndiceSsfm indiceSsfm(Map<String, Forma> formas) {
                if (indice == null || formas != formasDelIndice) {
                    indice = IndiceSsfm.de(codigos, formas);
                    formasDelIndice = formas;
                }
                return indice;
            }

            @Override
            public boolean hayCum() {
                return hayCum;
            }

            @Override
            public List<Presentacion> porCum(String cum) {
                return porCum.getOrDefault(cum, List.of());
            }

            @Override
            public List<Presentacion> porPrincipio(String base) {
                return porBase.getOrDefault(base, List.of());
            }

            @Override
            public boolean haySsfm() {
                return ssfm.isPresent();
            }

            @Override
            public List<CodigoSsfm> ssfm() {
                return codigos;
            }
        };
    }

    /** CIE-10 publicada para estos códigos (los de 4 caracteres y sus grupos). */
    ReferenciaCie10 cie10(JdbcClient jdbc, Collection<String> codigos) {
        Optional<Fuente> f = deTipo("cie10");
        Set<String> existen = new HashSet<>();
        Set<String> grupos = new HashSet<>();
        if (f.isPresent() && !codigos.isEmpty()) {
            Set<String> c4 = new HashSet<>();
            Set<String> g3 = new HashSet<>();
            for (String c : codigos) {
                String n = AnaliticaHomologacion.normalizarCie10(c);
                if (n.length() >= 3) {
                    g3.add(n.substring(0, 3));
                }
                if (n.length() == 4) {
                    c4.add(n);
                }
            }
            existen.addAll(jdbc.sql("SELECT codigo FROM ref_cie10 WHERE version_id = :v AND codigo = ANY(:c)").param("v", f.get().versionId())
                    .param("c", c4.toArray(String[]::new)).query(String.class).list());
            grupos.addAll(jdbc.sql("SELECT DISTINCT grupo FROM ref_cie10 WHERE version_id = :v AND grupo = ANY(:g)").param("v", f.get().versionId())
                    .param("g", g3.toArray(String[]::new)).query(String.class).list());
        }
        return new ReferenciaCie10() {
            @Override
            public boolean hayReferencia() {
                return f.isPresent();
            }

            @Override
            public boolean existe(String codigo4) {
                return existen.contains(codigo4);
            }

            @Override
            public boolean existeGrupo(String grupo3) {
                return grupos.contains(grupo3);
            }
        };
    }
}
