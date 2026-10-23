package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Presupuesto;
import co.com.agsamericas.digsa.reglas.Presupuesto.Sentido;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tablero de presupuesto y ejecución (spec «Tablero de presupuesto y ejecución»; mockup sección «presupuesto»): cajas de presupuesto
 * vigente, ejecutado, saldo y ESM sobre la proyección; tabla Fuerza × modalidad; ESM con mayor ejecución con semáforo y marcas de umbral;
 * detalle por línea con lo proyectado al corte y la desviación. Todo en el alcance del usuario y con los filtros; los totales de fuerza y
 * nacional son sumas de líneas. La vista «Contrato» (sin vigencia) suma todas las vigencias y no compara con la proyección.
 */
@Service
public class ServicioTablero {

    public record Filtros(Long vigencia, String corte, String fuerza, Long punto, String modalidad) {
    }

    /** Una línea con todo lo que pinta el tablero. proyectado y desviacion son null en la vista «Contrato». */
    record Fila(long id, int vigencia, String fuerza, long puntoId, String esm, String modalidad, String modalidadNombre, int ordenModalidad,
                BigDecimal asignado, BigDecimal facturado, BigDecimal reconocido, BigDecimal ejecutado, BigDecimal proyectado, BigDecimal ejecutadoCorte,
                Integer umbral) {
    }

    private final JdbcClient jdbc;
    private final Lineas lineas;
    private final Proyecciones proyecciones;
    private final ServicioPresupuesto presupuesto;
    private final Clock reloj;

    public ServicioTablero(JdbcClient jdbc, Lineas lineas, Proyecciones proyecciones, ServicioPresupuesto presupuesto, Clock reloj) {
        this.jdbc = jdbc;
        this.lineas = lineas;
        this.proyecciones = proyecciones;
        this.presupuesto = presupuesto;
        this.reloj = reloj;
    }

    /** Las líneas del alcance y los filtros, con lo ejecutado y lo proyectado al corte (lo mismo usan el tablero y los reportes). */
    record Consulta(Optional<Lineas.Vigencia> vigencia, YearMonth corte, String base, List<Fila> filas, Map<String, Object> parametros, StringBuilder where) {
    }

    @Transactional(readOnly = true)
    public Map<String, Object> tablero(Filtros f, Ambito a) {
        BigDecimal tolExceso = presupuesto.tolerancia("presupuesto_tolerancia_exceso", Presupuesto.TOLERANCIA_EXCESO);
        BigDecimal tolSub = presupuesto.tolerancia("presupuesto_tolerancia_subejecucion", Presupuesto.TOLERANCIA_SUBEJECUCION);
        Consulta q = consulta(f, a);
        Optional<Lineas.Vigencia> vig = q.vigencia();
        YearMonth corte = q.corte();
        String base = q.base();
        List<Fila> filas = q.filas();
        Map<String, Object> p = q.parametros();
        StringBuilder w = q.where();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("vigencia", vig.map(Lineas.Vigencia::anio).orElse(null));
        r.put("corte", corte == null ? null : corte.toString());
        r.put("cortes", vig.map(v -> Presupuesto.meses(v.inicio(), v.fin()).stream().map(YearMonth::toString).toList()).orElse(List.of()));
        r.put("base", base);
        r.put("ambito", a.descripcion());
        r.put("cajas", cajas(filas, vig.orElse(null), p, w, tolExceso));
        r.put("porFuerza", porFuerza(filas));
        r.put("mayorEjecucion", mayorEjecucion(filas));
        r.put("detalle", filas.stream().map(x -> detalle(x, tolExceso, tolSub)).toList());
        return r;
    }

    Consulta consulta(Filtros f, Ambito a) {
        String base = presupuesto.base();
        Optional<Lineas.Vigencia> vig = Optional.ofNullable(f == null ? null : f.vigencia())
                .map(id -> lineas.vigencia(id).orElseThrow(() -> ErrorNegocio.validacion("La vigencia no existe.")));
        YearMonth corte = vig.map(v -> corte(v, f.corte())).orElse(null);

        Map<String, Object> p = new HashMap<>(a.parametros());
        StringBuilder w = new StringBuilder(" WHERE (" + a.condicion() + ")");
        if (vig.isPresent()) {
            w.append(" AND l.vigencia_id = :f_vigencia");
            p.put("f_vigencia", vig.get().id());
        }
        if (f != null && f.fuerza() != null && !f.fuerza().isBlank()) {
            // Una o varias fuerzas («EJC,ARC» o el parámetro repetido, que Spring une con coma)
            w.append(" AND l.fuerza = ANY(:f_fuerzas)");
            p.put("f_fuerzas", lista(f.fuerza()));
        }
        if (f != null && f.punto() != null) {
            w.append(" AND l.punto_id = :f_punto");
            p.put("f_punto", f.punto());
        }
        if (f != null && f.modalidad() != null && !f.modalidad().isBlank()) {
            // Una o varias modalidades («dispensacion,suministro»)
            w.append(" AND l.modalidad = ANY(:f_modalidades)");
            p.put("f_modalidades", lista(f.modalidad()));
        }
        Map<Long, Proyecciones.AlCorte> alCorte = vig.map(v -> proyecciones.alCorte(v, corte, base)).orElse(Map.of());
        List<Fila> filas = jdbc.sql("""
                SELECT l.id, v.anio, l.fuerza, l.punto_id, coalesce(nullif(p.sigla, '') || ' · ', '') || p.nombre AS esm, l.modalidad, m.nombre AS modalidad_nombre,
                       m.orden, s.asignado, s.facturado, s.reconocido, (SELECT max(u.umbral) FROM presupuesto_umbrales u WHERE u.linea_id = l.id) AS umbral
                FROM presupuesto_lineas l JOIN presupuesto_saldos s ON s.linea_id = l.id JOIN puntos p ON p.id = l.punto_id
                JOIN presupuesto_modalidades m ON m.codigo = l.modalidad JOIN presupuesto_vigencias v ON v.id = l.vigencia_id""" + w
                + " ORDER BY l.fuerza, esm, m.orden, l.id").params(p).query((rs, n) -> {
                    long id = rs.getLong("id");
                    BigDecimal facturado = rs.getBigDecimal("facturado");
                    BigDecimal reconocido = rs.getBigDecimal("reconocido");
                    Proyecciones.AlCorte c = alCorte.get(id);
                    // Con vigencia, todo se mide al corte (cajas, % por fuerza, mayor ejecución y desviación), para que un corte pasado no
                    // compare la proyección al corte con la ejecución de meses posteriores; en la vista «Contrato», lo ejecutado a la fecha
                    BigDecimal aFecha = Presupuesto.BASE_RECONOCIDO.equals(base) ? reconocido : facturado;
                    return new Fila(id, rs.getInt("anio"), rs.getString("fuerza"), rs.getLong("punto_id"), rs.getString("esm"), rs.getString("modalidad"),
                            rs.getString("modalidad_nombre"), rs.getInt("orden"), rs.getBigDecimal("asignado"), facturado, reconocido,
                            c == null ? aFecha : c.ejecutado(), c == null ? null : c.proyectado(),
                            c == null ? null : c.ejecutado(), (Integer) rs.getObject("umbral", Integer.class));
                }).list();
        return new Consulta(vig, corte, base, filas, p, w);
    }

    /** Valores de un filtro de lista: separados por coma, sin vacíos ni repetidos, en orden. */
    static String[] lista(String valores) {
        return java.util.Arrays.stream(valores.split(",")).map(String::strip).filter(x -> !x.isEmpty()).distinct().sorted().toArray(String[]::new);
    }

    /** El corte pedido (AAAA-MM) o, si no, el mes actual en Bogotá; siempre dentro de la vigencia. */
    YearMonth corte(Lineas.Vigencia v, String pedido) {
        YearMonth ini = YearMonth.from(v.inicio());
        YearMonth fin = YearMonth.from(v.fin());
        YearMonth c = pedido == null || pedido.isBlank() ? YearMonth.from(LocalDate.now(reloj.withZone(Fechas.BOGOTA)))
                : Presupuesto.periodo(pedido).orElseThrow(() -> ErrorNegocio.validacion("El corte debe ser un mes (AAAA-MM)."));
        return c.isBefore(ini) ? ini : c.isAfter(fin) ? fin : c;
    }

    private Map<String, Object> cajas(List<Fila> filas, Lineas.Vigencia v, Map<String, Object> p, StringBuilder w, BigDecimal tolExceso) {
        BigDecimal asignado = suma(filas, Fila::asignado);
        BigDecimal ejecutado = suma(filas, Fila::ejecutado);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("presupuestoVigente", Presupuesto.formato(asignado));
        long adiciones = filas.isEmpty() ? 0 : jdbc.sql("""
                SELECT count(DISTINCT mv.id) FROM presupuesto_movimientos mv JOIN presupuesto_movimientos_lineas ml ON ml.movimiento_id = mv.id
                JOIN presupuesto_lineas l ON l.id = ml.linea_id""" + w + " AND mv.tipo = 'adicion'").params(p).query(Long.class).single();
        m.put("adiciones", adiciones);
        m.put("ejecutado", Presupuesto.formato(ejecutado));
        m.put("porcentaje", Presupuesto.porcentaje(ejecutado, asignado).map(Presupuesto::formato).orElse(null));
        m.put("saldo", Presupuesto.formato(asignado.subtract(ejecutado)));
        if (v != null) {
            BigDecimal proyectado = suma(filas, x -> x.proyectado() == null ? BigDecimal.ZERO : x.proyectado());
            m.put("proyectado", Presupuesto.formato(proyectado));
            m.put("porcentajeProyectado", Presupuesto.porcentaje(proyectado, asignado).map(Presupuesto::formato).orElse(null));
            // ESM sobre la proyección: ESM (sumando sus modalidades) con lo ejecutado por encima de lo proyectado al corte
            Map<Long, BigDecimal[]> porEsm = new TreeMap<>();
            filas.forEach(x -> porEsm.merge(x.puntoId(), new BigDecimal[] {x.ejecutadoCorte() == null ? BigDecimal.ZERO : x.ejecutadoCorte(),
                    x.proyectado() == null ? BigDecimal.ZERO : x.proyectado()},
                    (o, n) -> new BigDecimal[] {o[0].add(n[0]), o[1].add(n[1])}));
            m.put("esmSobreProyeccion", porEsm.values().stream().filter(e -> e[0].compareTo(e[1]) > 0).count());
            m.put("esmTotal", porEsm.size());
        }
        return m;
    }

    /** Tabla Fuerza × modalidad (mockup): ejecutado por modalidad, total, % de ejecución, % proyectado al corte y umbral alcanzado. */
    private List<Map<String, Object>> porFuerza(List<Fila> filas) {
        Map<String, List<Fila>> grupos = new TreeMap<>();
        filas.forEach(x -> grupos.computeIfAbsent(x.fuerza(), k -> new ArrayList<>()).add(x));
        List<Map<String, Object>> r = new ArrayList<>();
        grupos.forEach((fuerza, fs) -> {
            Map<String, BigDecimal> sumas = new LinkedHashMap<>();
            fs.stream().sorted(Comparator.comparingInt(Fila::ordenModalidad)).forEach(x -> sumas.merge(x.modalidadNombre(), x.ejecutado(), BigDecimal::add));
            Map<String, String> porModalidad = new LinkedHashMap<>();
            sumas.forEach((k, v) -> porModalidad.put(k, Presupuesto.formato(v)));
            BigDecimal asignado = suma(fs, Fila::asignado);
            BigDecimal ejecutado = suma(fs, Fila::ejecutado);
            boolean conProyeccion = fs.stream().anyMatch(x -> x.proyectado() != null);
            BigDecimal proyectado = suma(fs, x -> x.proyectado() == null ? BigDecimal.ZERO : x.proyectado());
            SortedSet<Integer> umbrales = Presupuesto.umbralesAlcanzados(ejecutado, asignado);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fuerza", fuerza);
            m.put("porModalidad", porModalidad);
            m.put("asignado", Presupuesto.formato(asignado));
            m.put("ejecutado", Presupuesto.formato(ejecutado));
            m.put("porcentaje", Presupuesto.porcentaje(ejecutado, asignado).map(Presupuesto::formato).orElse(null));
            m.put("porcentajeProyectado", conProyeccion ? Presupuesto.porcentaje(proyectado, asignado).map(Presupuesto::formato).orElse(null) : null);
            m.put("umbral", umbrales.isEmpty() ? null : umbrales.last());
            m.put("semaforo", Presupuesto.semaforo(ejecutado, asignado));
            r.add(m);
        });
        return r;
    }

    /** ESM con mayor ejecución (todas sus modalidades), hasta 10, de mayor a menor %. */
    private List<Map<String, Object>> mayorEjecucion(List<Fila> filas) {
        Map<Long, List<Fila>> porEsm = new LinkedHashMap<>();
        filas.forEach(x -> porEsm.computeIfAbsent(x.puntoId(), k -> new ArrayList<>()).add(x));
        record Esm(long id, String nombre, String fuerza, BigDecimal asignado, BigDecimal ejecutado, Integer umbral) {
            BigDecimal pct() {
                return Presupuesto.porcentaje(ejecutado, asignado).orElse(ejecutado.signum() > 0 ? BigDecimal.valueOf(100000) : BigDecimal.ZERO);
            }
        }
        List<Esm> esm = new ArrayList<>();
        porEsm.forEach((id, fs) -> esm.add(new Esm(id, fs.get(0).esm(), fs.get(0).fuerza(), suma(fs, Fila::asignado), suma(fs, Fila::ejecutado),
                fs.stream().map(Fila::umbral).filter(java.util.Objects::nonNull).max(Integer::compare).orElse(null))));
        return esm.stream().filter(e -> e.ejecutado().signum() > 0).sorted(Comparator.comparing(Esm::pct).reversed().thenComparing(Esm::nombre)).limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("puntoId", e.id());
                    m.put("esm", e.nombre());
                    m.put("fuerza", e.fuerza());
                    m.put("asignado", Presupuesto.formato(e.asignado()));
                    m.put("ejecutado", Presupuesto.formato(e.ejecutado()));
                    m.put("porcentaje", Presupuesto.porcentaje(e.ejecutado(), e.asignado()).map(Presupuesto::formato).orElse(null));
                    m.put("semaforo", Presupuesto.semaforo(e.ejecutado(), e.asignado()));
                    m.put("umbralNotificado", e.umbral());
                    return m;
                }).toList();
    }

    private static Map<String, Object> detalle(Fila x, BigDecimal tolExceso, BigDecimal tolSub) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineaId", x.id());
        m.put("vigencia", x.vigencia());
        m.put("fuerza", x.fuerza());
        m.put("puntoId", x.puntoId());
        m.put("esm", x.esm());
        m.put("modalidad", x.modalidad());
        m.put("modalidadNombre", x.modalidadNombre());
        m.put("asignado", Presupuesto.formato(x.asignado()));
        m.put("facturado", Presupuesto.formato(x.facturado()));
        m.put("reconocido", Presupuesto.formato(x.reconocido()));
        m.put("saldo", Presupuesto.formato(x.asignado().subtract(x.ejecutado())));
        m.put("porcentaje", Presupuesto.porcentaje(x.ejecutado(), x.asignado()).map(Presupuesto::formato).orElse(null));
        m.put("semaforo", Presupuesto.semaforo(x.ejecutado(), x.asignado()));
        m.put("umbralNotificado", x.umbral());
        if (x.proyectado() != null) {
            // Ejecutado y proyectado acumulados al corte (lo mismo que compara la alarma de desviación)
            m.put("proyectado", Presupuesto.formato(x.proyectado()));
            m.put("ejecutadoCorte", Presupuesto.formato(x.ejecutadoCorte()));
            m.put("desviacion", Presupuesto.desviacion(x.ejecutadoCorte(), x.proyectado()).map(Presupuesto::formato).orElse(null));
            m.put("sentido", Presupuesto.sentido(x.ejecutadoCorte(), x.proyectado(), tolExceso, tolSub).codigo());
        }
        return m;
    }

    private static BigDecimal suma(List<Fila> filas, java.util.function.Function<Fila, BigDecimal> campo) {
        return filas.stream().map(campo).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }
}
