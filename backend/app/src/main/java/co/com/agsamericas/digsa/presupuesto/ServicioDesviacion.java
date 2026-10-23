package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.alertas.ServicioAlertas;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Presupuesto;
import co.com.agsamericas.digsa.reglas.Presupuesto.Sentido;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Alarma de desviación frente a la proyección (Anexo 5 §4.6: «generando las alarmas necesarias cuando la ejecución presupuestal no se
 * cumpla de acuerdo con la proyección de consumo por cada ESM, FUERZA y a nivel nacional»; caso M7-3). Al registrar ejecución de un mes
 * (corte), compara lo ejecutado acumulado con lo proyectado acumulado en tres niveles: cada línea tocada (ESM × modalidad), las fuerzas de
 * esas líneas y el país. La fuerza y el país suman solo las líneas que ya reportaron el mes del corte: una línea sin reporte aún no tiene
 * consumo conocido y daría una subejecución falsa cuando la ejecución llega ESM por ESM (duda P.26). Exceso: por encima de la tolerancia de exceso; subejecución: por debajo de menos la de subejecución (parámetros).
 * Una sola alarma por vigencia, corte, nivel y sentido (índice único): la recarga del mismo mes no la repite. Cada alarma lleva la alerta
 * del catálogo y la notificación formal (la nacional, a las tres sanidades y a la DIGSA).
 */
@Component
class ServicioDesviacion implements CorteDeEjecucion {

    private final JdbcClient jdbc;
    private final Proyecciones proyecciones;
    private final ServicioPresupuesto presupuesto;
    private final Notificaciones notificaciones;
    private final ServicioAlertas alertas;
    private final RegistroBitacora bitacora;
    private final Clock reloj;

    ServicioDesviacion(JdbcClient jdbc, Proyecciones proyecciones, ServicioPresupuesto presupuesto, Notificaciones notificaciones, ServicioAlertas alertas,
                       RegistroBitacora bitacora, Clock reloj) {
        this.jdbc = jdbc;
        this.proyecciones = proyecciones;
        this.presupuesto = presupuesto;
        this.notificaciones = notificaciones;
        this.alertas = alertas;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    /** Un nivel evaluado: la línea (esm), la fuerza o el país. */
    record Nivel(String nivel, Long lineaId, String fuerza, String nombre, BigDecimal ejecutado, BigDecimal proyectado) {
    }

    @Override
    public void ejecucionRegistrada(Lineas.Vigencia v, Set<YearMonth> periodos, List<Long> lineas, UsuarioSesion quien) {
        String base = presupuesto.base();
        BigDecimal tolExceso = presupuesto.tolerancia("presupuesto_tolerancia_exceso", Presupuesto.TOLERANCIA_EXCESO);
        BigDecimal tolSub = presupuesto.tolerancia("presupuesto_tolerancia_subejecucion", Presupuesto.TOLERANCIA_SUBEJECUCION);
        if (periodos.isEmpty()) {
            return;
        }
        // El corte es el mes más reciente de la carga: los meses anteriores de un mismo archivo no alarman uno por uno (F6, rendimiento)
        YearMonth corte = new TreeSet<>(periodos).last();
        Map<Long, String> nombres = nombres(lineas);
        Map<Long, Proyecciones.AlCorte> todas = proyecciones.alCorte(v, corte, base);
        Set<String> fuerzas = new TreeSet<>();
        // Nivel ESM: cada línea tocada que reportó el mes; las nuevas alarmas se notifican consolidadas por fuerza y sentido
        Map<String, List<Object[]>> grupos = new TreeMap<>();
        for (Long id : new TreeSet<>(lineas)) {
            Proyecciones.AlCorte a = todas.get(id);
            if (a == null || !a.reporto()) {
                continue;
            }
            fuerzas.add(a.fuerza());
            Nivel n = new Nivel("esm", id, a.fuerza(), nombres.getOrDefault(id, "línea " + id), a.ejecutado(), a.proyectado());
            Sentido s = Presupuesto.sentido(n.ejecutado(), n.proyectado(), tolExceso, tolSub);
            if (s != Sentido.NINGUNO) {
                registrar(v, corte, n, s, base).ifPresent(d -> grupos.computeIfAbsent(a.fuerza() + "|" + s.codigo(), k -> new ArrayList<>())
                        .add(new Object[] {n, s, d}));
            }
        }
        for (List<Object[]> g : grupos.values()) {
            Sentido s = (Sentido) g.get(0)[1];
            BigDecimal tol = s == Sentido.EXCESO ? tolExceso : tolSub;
            if (g.size() == 1) {
                avisar(v, corte, (Nivel) g.get(0)[0], s, base, tol, (Long) g.get(0)[2], quien);
            } else {
                avisarGrupo(v, corte, g, s, base, tol, quien);
            }
        }
        List<Nivel> agregados = new ArrayList<>();
        for (String f : fuerzas) {
            agregados.add(sumar("fuerza", f, "Fuerza " + f, todas.values().stream().filter(a -> a.reporto() && f.equals(a.fuerza())).toList()));
        }
        agregados.add(sumar("nacional", null, "Nacional", todas.values().stream().filter(Proyecciones.AlCorte::reporto).toList()));
        for (Nivel n : agregados) {
            Sentido s = Presupuesto.sentido(n.ejecutado(), n.proyectado(), tolExceso, tolSub);
            if (s != Sentido.NINGUNO) {
                Optional<Long> d = registrar(v, corte, n, s, base);
                if (d.isPresent()) {
                    avisar(v, corte, n, s, base, s == Sentido.EXCESO ? tolExceso : tolSub, d.get(), quien);
                }
            }
        }
    }

    private static Nivel sumar(String nivel, String fuerza, String nombre, Collection<Proyecciones.AlCorte> lineas) {
        BigDecimal ej = BigDecimal.ZERO.setScale(2);
        BigDecimal pr = BigDecimal.ZERO.setScale(2);
        for (Proyecciones.AlCorte a : lineas) {
            ej = ej.add(a.ejecutado());
            pr = pr.add(a.proyectado());
        }
        return new Nivel(nivel, null, fuerza, nombre, ej, pr);
    }

    /** Registra la desviación; vacío si ya estaba (mismo corte, nivel y sentido: índice único). */
    private Optional<Long> registrar(Lineas.Vigencia v, YearMonth corte, Nivel n, Sentido s, String base) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        BigDecimal desv = Presupuesto.desviacion(n.ejecutado(), n.proyectado()).orElse(null);
        return jdbc.sql("""
                INSERT INTO presupuesto_desviaciones (vigencia_id, corte, nivel, linea_id, fuerza, sentido, ejecutado, proyectado, desviacion, base, creado)
                VALUES (:v, :corte, :nivel, :linea, :fuerza, :sentido, :ej, :pr, :desv, :base, :ahora)
                ON CONFLICT DO NOTHING RETURNING id""")
                .param("v", v.id()).param("corte", corte.atDay(1)).param("nivel", n.nivel()).param("linea", n.lineaId())
                .param("fuerza", "fuerza".equals(n.nivel()) ? n.fuerza() : null).param("sentido", s.codigo()).param("ej", n.ejecutado())
                .param("pr", n.proyectado()).param("desv", desv).param("base", base).param("ahora", ahora).query(Long.class).optional();
    }

    private static String porcentaje(Nivel n) {
        BigDecimal desv = Presupuesto.desviacion(n.ejecutado(), n.proyectado()).orElse(null);
        return desv == null ? "sin proyección al corte" : (desv.signum() > 0 ? "+" : "") + Presupuesto.formato(desv) + " %";
    }

    private void avisar(Lineas.Vigencia v, YearMonth corte, Nivel n, Sentido s, String base, BigDecimal tolerancia, long desviacionId, UsuarioSesion quien) {
        Optional<Long> id = Optional.of(desviacionId);
        String porcentaje = porcentaje(n);
        String titulo = n.nombre() + ": " + s.texto() + " frente a la proyección a " + corte + " (" + porcentaje + ")";
        List<String[]> d = new ArrayList<>();
        d.add(new String[] {"Nivel", switch (n.nivel()) {
            case "esm" -> "ESM y modalidad";
            case "fuerza" -> "Fuerza";
            default -> "Nacional";
        }});
        d.add(new String[] {"Alcance", n.nombre()});
        d.add(new String[] {"Corte", corte.toString()});
        d.add(new String[] {"Ejecutado acumulado (" + base + ")", Presupuesto.formato(n.ejecutado())});
        d.add(new String[] {"Proyectado acumulado", Presupuesto.formato(n.proyectado())});
        d.add(new String[] {"Desviación", porcentaje});
        d.add(new String[] {"Alarma", s.texto() + " (tolerancia " + Presupuesto.formato(tolerancia) + " %)"});
        String fuerzaAviso = "nacional".equals(n.nivel()) ? null : n.fuerza();
        Notificaciones.Resultado r = notificaciones.notificar(new Notificaciones.Aviso("desviacion", n.nivel(), v, n.lineaId(), List.of(), fuerzaAviso,
                corte.atDay(1), titulo, d), quien == null ? null : quien.id(), quien == null ? null : quien.usuario());
        String referencia = "esm".equals(n.nivel()) ? String.valueOf(n.lineaId()) : "fuerza".equals(n.nivel()) ? n.fuerza() : "nacional";
        alertas.disparar("presupuesto_desviacion", Presupuesto.claveDesviacion(v.anio(), corte, n.nivel(), referencia, s), r.consecutivo() + " · " + titulo);
        jdbc.sql("UPDATE presupuesto_desviaciones SET notificacion_id = :n WHERE id = :id").param("n", r.id()).param("id", id.get()).update();
        bitacora.registrar("presupuesto_desviacion", "Desviación " + s.codigo() + " · " + n.nivel() + " " + referencia + " · corte " + corte + " · " + porcentaje
                + " · " + r.consecutivo(), quien == null ? null : quien.id(), quien == null ? null : quien.usuario());
    }

    /** Varias líneas de una fuerza con la misma alarma en el mismo corte: una sola notificación y una alerta. */
    private void avisarGrupo(Lineas.Vigencia v, YearMonth corte, List<Object[]> grupo, Sentido s, String base, BigDecimal tolerancia, UsuarioSesion quien) {
        String fuerza = ((Nivel) grupo.get(0)[0]).fuerza();
        String titulo = grupo.size() + " líneas de la fuerza " + fuerza + ": " + s.texto() + " frente a la proyección a " + corte;
        List<String[]> d = new ArrayList<>();
        d.add(new String[] {"Nivel", "ESM y modalidad"});
        d.add(new String[] {"Fuerza", fuerza});
        d.add(new String[] {"Corte", corte.toString()});
        d.add(new String[] {"Alarma", s.texto() + " (tolerancia " + Presupuesto.formato(tolerancia) + " %; ejecutado = " + base + ")"});
        grupo.stream().limit(ServicioUmbrales.MAX_DETALLE).forEach(x -> {
            Nivel n = (Nivel) x[0];
            d.add(new String[] {n.nombre(), porcentaje(n) + " (ejecutado " + Presupuesto.formato(n.ejecutado()) + ", proyectado " + Presupuesto.formato(n.proyectado())
                    + ")"});
        });
        if (grupo.size() > ServicioUmbrales.MAX_DETALLE) {
            d.add(new String[] {"…", "y " + (grupo.size() - ServicioUmbrales.MAX_DETALLE) + " línea(s) más: ver el reporte de umbrales y desviaciones"});
        }
        List<Long> lineas = grupo.stream().map(x -> ((Nivel) x[0]).lineaId()).toList();
        Notificaciones.Resultado r = notificaciones.notificar(new Notificaciones.Aviso("desviacion", "esm", v, null, lineas, fuerza, corte.atDay(1), titulo, d),
                quien == null ? null : quien.id(), quien == null ? null : quien.usuario());
        alertas.disparar("presupuesto_desviacion", "pd:" + v.anio() + ":" + corte + ":n" + r.id(), r.consecutivo() + " · " + titulo);
        jdbc.sql("UPDATE presupuesto_desviaciones SET notificacion_id = :n WHERE id = ANY(:ids)").param("n", r.id())
                .param("ids", grupo.stream().map(x -> (Long) x[2]).toArray(Long[]::new)).update();
        bitacora.registrar("presupuesto_desviacion", "Desviación " + s.codigo() + " · " + grupo.size() + " líneas de " + fuerza + " · corte " + corte + " · "
                + r.consecutivo(), quien == null ? null : quien.id(), quien == null ? null : quien.usuario());
    }

    private Map<Long, String> nombres(List<Long> lineas) {
        Map<Long, String> r = new TreeMap<>();
        if (lineas.isEmpty()) {
            return r;
        }
        jdbc.sql("""
                SELECT l.id, coalesce(nullif(p.sigla, '') || ' · ', '') || p.nombre || ' · ' || m.nombre AS nombre
                FROM presupuesto_lineas l JOIN puntos p ON p.id = l.punto_id JOIN presupuesto_modalidades m ON m.codigo = l.modalidad
                WHERE l.id = ANY(:ids)""").param("ids", lineas.toArray(Long[]::new))
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getString(2))).list().forEach(e -> r.put(e.getKey(), e.getValue()));
        return r;
    }
}
