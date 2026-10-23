package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.alertas.ServicioAlertas;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Presupuesto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Umbrales del 25, 50, 75 y 100 % (Anexo 2 L741; spec «Umbrales … con notificación única»). Corre dentro de la transacción del cambio,
 * con los saldos de las líneas ya bloqueados (así dos cargas a la vez sobre la misma línea no registran ni avisan dos veces). Cada umbral
 * queda registrado una vez por línea; solo el más alto cruzado en el mismo cambio lleva aviso: la alerta del catálogo (DIGSA y
 * Coordinador) y la notificación formal a la Sanidad de la fuerza y a la DIGSA. Una adición que baja el % no hace que se avise de nuevo.
 * Si en un mismo cambio cruzan umbrales varias líneas de una fuerza (la carga del mes), sale una sola notificación consolidada por fuerza.
 */
@Component
class ServicioUmbrales implements CambioDeSaldos {

    /** Líneas que se detallan en una notificación consolidada (el texto tiene tope de 4000 caracteres; el resto, en el reporte). */
    static final int MAX_DETALLE = 25;

    private final JdbcClient jdbc;
    private final ServicioPresupuesto presupuesto;
    private final Notificaciones notificaciones;
    private final ServicioAlertas alertas;
    private final RegistroBitacora bitacora;
    private final Clock reloj;

    ServicioUmbrales(JdbcClient jdbc, ServicioPresupuesto presupuesto, Notificaciones notificaciones, ServicioAlertas alertas, RegistroBitacora bitacora,
                     Clock reloj) {
        this.jdbc = jdbc;
        this.presupuesto = presupuesto;
        this.notificaciones = notificaciones;
        this.alertas = alertas;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    record Linea(long id, String nombre, String fuerza, String modalidad, BigDecimal asignado, BigDecimal facturado, BigDecimal reconocido) {
    }

    /** Una línea que cruzó umbrales en este cambio: el más alto lleva aviso. */
    record Cruce(Linea linea, SortedSet<Integer> nuevos, int umbral, BigDecimal ejecutado, BigDecimal porcentaje) {
        String textoPorcentaje() {
            return porcentaje == null ? "sin presupuesto asignado" : Presupuesto.formato(porcentaje) + " %";
        }
    }

    @Override
    public void saldosCambiados(Lineas.Vigencia vigencia, List<Long> lineas, UsuarioSesion quien) {
        if (lineas.isEmpty()) {
            return;
        }
        String base = presupuesto.base();
        Long[] ids = lineas.stream().distinct().toArray(Long[]::new);
        List<Linea> datos = jdbc.sql("""
                SELECT s.linea_id, s.asignado, s.facturado, s.reconocido, l.fuerza, m.nombre AS modalidad,
                       coalesce(nullif(p.sigla, '') || ' · ', '') || p.nombre AS esm
                FROM presupuesto_saldos s JOIN presupuesto_lineas l ON l.id = s.linea_id JOIN puntos p ON p.id = l.punto_id
                JOIN presupuesto_modalidades m ON m.codigo = l.modalidad
                WHERE s.linea_id = ANY(:ids) ORDER BY s.linea_id""").param("ids", ids)
                .query((rs, n) -> new Linea(rs.getLong("linea_id"), rs.getString("esm"), rs.getString("fuerza"), rs.getString("modalidad"),
                        rs.getBigDecimal("asignado"), rs.getBigDecimal("facturado"), rs.getBigDecimal("reconocido")))
                .list();
        Map<Long, Set<Integer>> registrados = registrados(ids);
        List<Cruce> cruces = new ArrayList<>();
        for (Linea l : datos) {
            BigDecimal ejecutado = Presupuesto.BASE_RECONOCIDO.equals(base) ? l.reconocido() : l.facturado();
            SortedSet<Integer> nuevos = Presupuesto.nuevosUmbrales(Presupuesto.umbralesAlcanzados(ejecutado, l.asignado()),
                    registrados.getOrDefault(l.id(), Set.of()));
            Optional<Integer> avisar = Presupuesto.umbralANotificar(nuevos);
            avisar.ifPresent(u -> cruces.add(new Cruce(l, nuevos, u, ejecutado, Presupuesto.porcentaje(ejecutado, l.asignado()).orElse(null))));
        }
        if (cruces.isEmpty()) {
            return;
        }
        // Todos los umbrales nuevos en una sentencia (una carga del mes puede cruzar umbrales en cientos de líneas)
        List<Long> uL = new ArrayList<>();
        List<Integer> uU = new ArrayList<>();
        List<BigDecimal> uP = new ArrayList<>();
        List<BigDecimal> uE = new ArrayList<>();
        List<BigDecimal> uA = new ArrayList<>();
        List<Boolean> uN = new ArrayList<>();
        for (Cruce c : cruces) {
            for (int u : c.nuevos()) {
                uL.add(c.linea().id());
                uU.add(u);
                uP.add(c.porcentaje());
                uE.add(c.ejecutado());
                uA.add(c.linea().asignado());
                uN.add(u == c.umbral());
            }
        }
        jdbc.sql("""
                INSERT INTO presupuesto_umbrales (linea_id, umbral, porcentaje, base, ejecutado, asignado, alcanzado, notificada)
                SELECT x.l, x.u, x.p, :base, x.e, x.a, :ahora, x.n
                FROM unnest(CAST(:l AS bigint[]), CAST(:u AS smallint[]), CAST(:p AS numeric[]), CAST(:e AS numeric[]), CAST(:a AS numeric[]),
                            CAST(:n AS boolean[])) AS x(l, u, p, e, a, n)
                ON CONFLICT DO NOTHING""")
                .param("l", uL.toArray(Long[]::new)).param("u", uU.toArray(Integer[]::new)).param("p", uP.toArray(BigDecimal[]::new))
                .param("e", uE.toArray(BigDecimal[]::new)).param("a", uA.toArray(BigDecimal[]::new)).param("n", uN.toArray(Boolean[]::new))
                .param("base", base).param("ahora", OffsetDateTime.now(reloj)).update();

        Map<String, List<Cruce>> porFuerza = new TreeMap<>();
        cruces.forEach(c -> porFuerza.computeIfAbsent(c.linea().fuerza(), k -> new ArrayList<>()).add(c));
        for (Map.Entry<String, List<Cruce>> g : porFuerza.entrySet()) {
            avisar(vigencia, g.getKey(), g.getValue(), base, quien);
        }
    }

    private void avisar(Lineas.Vigencia vigencia, String fuerza, List<Cruce> grupo, String base, UsuarioSesion quien) {
        Long usuarioId = quien == null ? null : quien.id();
        String usuario = quien == null ? null : quien.usuario();
        Notificaciones.Resultado n;
        String clave;
        String titulo;
        if (grupo.size() == 1) {
            Cruce c = grupo.get(0);
            Linea l = c.linea();
            titulo = l.nombre() + " · " + l.modalidad() + " alcanzó el " + c.umbral() + " % del presupuesto (" + c.textoPorcentaje() + ")";
            List<String[]> d = new ArrayList<>();
            d.add(new String[] {"Fuerza", l.fuerza()});
            d.add(new String[] {"ESM", l.nombre()});
            d.add(new String[] {"Modalidad", l.modalidad()});
            d.add(new String[] {"Umbral alcanzado", c.umbral() + " %"});
            d.add(new String[] {"Presupuesto asignado", Presupuesto.formato(l.asignado())});
            d.add(new String[] {"Ejecutado (" + base + ")", Presupuesto.formato(c.ejecutado())});
            d.add(new String[] {"Porcentaje de ejecución", c.textoPorcentaje()});
            if (c.nuevos().size() > 1) {
                d.add(new String[] {"Umbrales cruzados en este cambio", String.join(", ", c.nuevos().stream().map(x -> x + " %").toList())});
            }
            n = notificaciones.notificar(new Notificaciones.Aviso("umbral", "esm", vigencia, l.id(), List.of(), fuerza, null, titulo, d), usuarioId, usuario);
            clave = Presupuesto.claveUmbral(vigencia.anio(), l.id(), c.umbral());
        } else {
            titulo = grupo.size() + " líneas de la fuerza " + fuerza + " alcanzaron umbrales del presupuesto";
            List<String[]> d = new ArrayList<>();
            d.add(new String[] {"Fuerza", fuerza});
            d.add(new String[] {"Líneas que cruzaron un umbral", String.valueOf(grupo.size())});
            grupo.stream().limit(MAX_DETALLE).forEach(c -> d.add(new String[] {c.linea().nombre() + " · " + c.linea().modalidad(),
                    c.umbral() + " % (" + c.textoPorcentaje() + "; asignado " + Presupuesto.formato(c.linea().asignado()) + ", ejecutado "
                            + Presupuesto.formato(c.ejecutado()) + ")"}));
            if (grupo.size() > MAX_DETALLE) {
                d.add(new String[] {"…", "y " + (grupo.size() - MAX_DETALLE) + " línea(s) más: ver la pestaña Notificaciones y el reporte de umbrales"});
            }
            n = notificaciones.notificar(new Notificaciones.Aviso("umbral", "esm", vigencia, null, grupo.stream().map(c -> c.linea().id()).toList(), fuerza,
                    null, titulo, d), usuarioId, usuario);
            clave = "pu:" + vigencia.anio() + ":n" + n.id();
        }
        ServicioAlertas.Disparo disparo = alertas.disparar("presupuesto_umbral", clave, n.consecutivo() + " · " + titulo);
        jdbc.sql("""
                UPDATE presupuesto_umbrales u SET notificacion_id = :n, evento_id = :e
                FROM unnest(CAST(:l AS bigint[]), CAST(:u AS smallint[])) AS x(l, u) WHERE u.linea_id = x.l AND u.umbral = x.u""")
                .param("n", n.id()).param("e", disparo.eventoId()).param("l", grupo.stream().map(c -> c.linea().id()).toArray(Long[]::new))
                .param("u", grupo.stream().map(Cruce::umbral).toArray(Integer[]::new)).update();
        bitacora.registrar("presupuesto_umbral", (grupo.size() == 1 ? "Umbral " + grupo.get(0).umbral() + " % · línea " + grupo.get(0).linea().id()
                : "Umbrales · " + grupo.size() + " líneas de " + fuerza) + " · " + n.consecutivo() + " · " + n.estado().replace('_', ' '), usuarioId, usuario);
    }

    private Map<Long, Set<Integer>> registrados(Long[] ids) {
        Map<Long, Set<Integer>> r = new java.util.HashMap<>();
        jdbc.sql("SELECT linea_id, umbral FROM presupuesto_umbrales WHERE linea_id = ANY(:ids)").param("ids", ids)
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getInt(2))).list().forEach(e -> r.computeIfAbsent(e.getKey(), k -> new HashSet<>()).add(e.getValue()));
        return r;
    }
}
