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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Umbrales del 25, 50, 75 y 100 % (Anexo 2 L741; spec «Umbrales … con notificación única»). Corre dentro de la transacción del cambio,
 * con los saldos de las líneas ya bloqueados (así dos cargas a la vez sobre la misma línea no registran ni avisan dos veces). Cada umbral
 * queda registrado una vez por línea; solo el más alto cruzado en el mismo cambio lleva aviso: la alerta del catálogo (DIGSA y
 * Coordinador) y la notificación formal a la Sanidad de la fuerza y a la DIGSA. Una adición que baja el % no hace que se avise de nuevo.
 */
@Component
class ServicioUmbrales implements CambioDeSaldos {

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

    @Override
    public void saldosCambiados(Lineas.Vigencia vigencia, List<Long> lineas, UsuarioSesion quien) {
        if (lineas.isEmpty()) {
            return;
        }
        String base = presupuesto.base();
        List<Linea> datos = jdbc.sql("""
                SELECT s.linea_id, s.asignado, s.facturado, s.reconocido, l.fuerza, m.nombre AS modalidad,
                       coalesce(nullif(p.sigla, '') || ' · ', '') || p.nombre AS esm
                FROM presupuesto_saldos s JOIN presupuesto_lineas l ON l.id = s.linea_id JOIN puntos p ON p.id = l.punto_id
                JOIN presupuesto_modalidades m ON m.codigo = l.modalidad
                WHERE s.linea_id = ANY(:ids) ORDER BY s.linea_id""").param("ids", lineas.stream().distinct().toArray(Long[]::new))
                .query((rs, n) -> new Linea(rs.getLong("linea_id"), rs.getString("esm"), rs.getString("fuerza"), rs.getString("modalidad"),
                        rs.getBigDecimal("asignado"), rs.getBigDecimal("facturado"), rs.getBigDecimal("reconocido")))
                .list();
        Map<Long, Set<Integer>> registrados = registrados(lineas);
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        for (Linea l : datos) {
            BigDecimal ejecutado = Presupuesto.BASE_RECONOCIDO.equals(base) ? l.reconocido() : l.facturado();
            SortedSet<Integer> nuevos = Presupuesto.nuevosUmbrales(Presupuesto.umbralesAlcanzados(ejecutado, l.asignado()),
                    registrados.getOrDefault(l.id(), Set.of()));
            Optional<Integer> avisar = Presupuesto.umbralANotificar(nuevos);
            if (avisar.isEmpty()) {
                continue;
            }
            BigDecimal pct = Presupuesto.porcentaje(ejecutado, l.asignado()).orElse(null);
            for (int u : nuevos) {
                jdbc.sql("""
                        INSERT INTO presupuesto_umbrales (linea_id, umbral, porcentaje, base, ejecutado, asignado, alcanzado, notificada)
                        VALUES (:l, :u, :pct, :base, :ej, :as, :ahora, :notificada) ON CONFLICT DO NOTHING""")
                        .param("l", l.id()).param("u", u).param("pct", pct).param("base", base).param("ej", ejecutado).param("as", l.asignado())
                        .param("ahora", ahora).param("notificada", u == avisar.get()).update();
            }
            int umbral = avisar.get();
            String porcentaje = pct == null ? "sin presupuesto asignado" : Presupuesto.formato(pct) + " %";
            String titulo = l.nombre() + " · " + l.modalidad() + " alcanzó el " + umbral + " % del presupuesto (" + porcentaje + ")";
            List<String[]> d = new ArrayList<>();
            d.add(new String[] {"Fuerza", l.fuerza()});
            d.add(new String[] {"ESM", l.nombre()});
            d.add(new String[] {"Modalidad", l.modalidad()});
            d.add(new String[] {"Umbral alcanzado", umbral + " %"});
            d.add(new String[] {"Presupuesto asignado", Presupuesto.formato(l.asignado())});
            d.add(new String[] {"Ejecutado (" + base + ")", Presupuesto.formato(ejecutado)});
            d.add(new String[] {"Porcentaje de ejecución", porcentaje});
            if (nuevos.size() > 1) {
                d.add(new String[] {"Umbrales cruzados en este cambio", String.join(", ", nuevos.stream().map(x -> x + " %").toList())});
            }
            Notificaciones.Resultado n = notificaciones.notificar(new Notificaciones.Aviso("umbral", "esm", vigencia, l.id(), l.fuerza(), null, titulo, d),
                    quien == null ? null : quien.id(), quien == null ? null : quien.usuario());
            ServicioAlertas.Disparo disparo = alertas.disparar("presupuesto_umbral", Presupuesto.claveUmbral(vigencia.anio(), l.id(), umbral),
                    n.consecutivo() + " · " + titulo);
            jdbc.sql("UPDATE presupuesto_umbrales SET notificacion_id = :n, evento_id = :e WHERE linea_id = :l AND umbral = :u")
                    .param("n", n.id()).param("e", disparo.eventoId()).param("l", l.id()).param("u", umbral).update();
            bitacora.registrar("presupuesto_umbral", "Umbral " + umbral + " % · línea " + l.id() + " · " + n.consecutivo() + " · " + n.estado().replace('_', ' '),
                    quien == null ? null : quien.id(), quien == null ? null : quien.usuario());
        }
    }

    private Map<Long, Set<Integer>> registrados(List<Long> lineas) {
        Map<Long, Set<Integer>> r = new java.util.HashMap<>();
        jdbc.sql("SELECT linea_id, umbral FROM presupuesto_umbrales WHERE linea_id = ANY(:ids)").param("ids", lineas.stream().distinct().toArray(Long[]::new))
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getInt(2))).list().forEach(e -> r.computeIfAbsent(e.getKey(), k -> new HashSet<>()).add(e.getValue()));
        return r;
    }
}
