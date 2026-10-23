package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Presupuesto;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reportes de ejecución presupuestal (spec «Reportes de ejecución presupuestal»; Anexo 5 2.7 a 2.12 y §4.7.5: van en el módulo funcional y
 * en el de reportes, no en la analítica). Encabezado con título, fecha y hora de generación, usuario, periodo, establecimiento y filtros;
 * el PDF numera las páginas. Las modificaciones del presupuesto se ven en los reportes (Anexo 5 §4.6). Mismas cifras para dos usuarios
 * del mismo alcance y filtros.
 */
@Service
public class ServicioReportesPresupuesto {

    public static final Map<String, String> TIPOS = tipos();

    /**
     * Definición de cada reporte alineada al contrato ProveedorReporte del módulo de reportes (docs/especificaciones/reportes/
     * contrato-proveedor.md en la rama reportes): id, grupo PRESUPUESTO, clase, periodo y columnas tipadas. Cuando reportes llegue a
     * develop, cada uno se registra como un bean que delega en generar(); las filas ya salen con BigDecimal, enteros y OffsetDateTime y
     * en orden total con desempate por llave. Filtros de lista: fuerza y modalidad (una, varias o todas); establecimiento: punto.
     */
    record Definicion(String id, String clase, String periodo, List<String> filtros, List<String> columnasTipadas) {
    }

    public static final Map<String, Definicion> DEFINICIONES = Map.of(
            "ejecucion", new Definicion("presupuesto_ejecucion", "GENERAL", "NINGUNO", List.of("vigencia", "corte", "fuerza", "punto", "modalidad"),
                    List.of("Vigencia:ENTERO", "Fuerza:TEXTO", "ESM:TEXTO", "Modalidad:TEXTO", "Asignado:DECIMAL", "Facturado:DECIMAL", "Reconocido:DECIMAL",
                            "Ejecutado:DECIMAL", "Saldo:DECIMAL", "% ejecución:PORCENTAJE", "Proyectado al corte:DECIMAL", "Desviación %:PORCENTAJE",
                            "Umbral notificado:TEXTO")),
            "mensual", new Definicion("presupuesto_mensual", "GENERAL", "FECHA (mes a año)", List.of("vigencia", "fuerza", "punto", "modalidad"),
                    List.of("Periodo:TEXTO", "Fuerza:TEXTO", "ESM:TEXTO", "Modalidad:TEXTO", "Ejecutado:DECIMAL", "Proyectado:DECIMAL", "Diferencia:DECIMAL",
                            "Desviación %:PORCENTAJE")),
            "movimientos", new Definicion("presupuesto_movimientos", "OPERATIVO", "FECHA_HORA", List.of("vigencia", "tipoMovimiento", "fuerza", "punto",
                    "modalidad"), List.of("Fecha y hora:FECHA_HORA", "Movimiento:ENTERO", "Tipo:TEXTO", "Vigencia:ENTERO", "Soporte DIGSA:TEXTO",
                    "Usuario:TEXTO (personal: interventoría)", "Fuerza:TEXTO", "ESM:TEXTO", "Modalidad:TEXTO", "Valor:DECIMAL", "Asignado antes:DECIMAL",
                    "Asignado después:DECIMAL", "Lote:ENTERO", "Observación:TEXTO")),
            "alertas", new Definicion("presupuesto_alertas", "GENERAL", "FECHA_HORA", List.of("vigencia", "fuerza", "punto"),
                    List.of("Fecha y hora:FECHA_HORA", "Consecutivo:TEXTO", "Tipo:TEXTO", "Nivel:TEXTO", "Fuerza:TEXTO", "ESM:TEXTO", "Corte:TEXTO",
                            "Asunto:TEXTO", "Estado:TEXTO")));
    public static final List<String> AGRUPACIONES = List.of("mes", "bimestre", "trimestre", "semestre", "anio");
    static final int MAX_FILAS = 100_000;

    private static Map<String, String> tipos() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ejecucion", "Ejecución presupuestal por Fuerza, ESM y modalidad");
        m.put("mensual", "Ejecución mensual frente a la proyección");
        m.put("movimientos", "Movimientos presupuestales");
        m.put("alertas", "Umbrales y desviaciones notificados");
        return m;
    }

    /** Filtros de los reportes: vacíos = todos. modalidad admite varias separadas por coma. */
    public record Filtros(Long vigencia, String corte, String fuerza, Long punto, String modalidad, String tipoMovimiento, String desde, String hasta,
                          String agrupacion) {
    }

    private final JdbcClient jdbc;
    private final Lineas lineas;
    private final ServicioTablero tablero;
    private final ServicioPresupuesto presupuesto;
    private final Clock reloj;

    public ServicioReportesPresupuesto(JdbcClient jdbc, Lineas lineas, ServicioTablero tablero, ServicioPresupuesto presupuesto, Clock reloj) {
        this.jdbc = jdbc;
        this.lineas = lineas;
        this.tablero = tablero;
        this.presupuesto = presupuesto;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public Reporte generar(String tipo, Filtros f, Ambito a, String usuario) {
        String titulo = Optional.ofNullable(TIPOS.get(tipo)).orElseThrow(() -> ErrorNegocio.noEncontrado("El reporte no existe."));
        Filtros filtros = f == null ? new Filtros(null, null, null, null, null, null, null, null, null) : f;
        ZonedDateTime generado = ZonedDateTime.now(reloj.withZone(Fechas.BOGOTA));
        return switch (tipo) {
            case "ejecucion" -> ejecucion(titulo, filtros, a, usuario, generado);
            case "mensual" -> mensual(titulo, filtros, a, usuario, generado);
            case "movimientos" -> movimientos(titulo, filtros, a, usuario, generado);
            default -> alertas(titulo, filtros, a, usuario, generado);
        };
    }

    // ---------- Encabezado (Anexo 5, 2.9) ----------

    private Map<String, String> encabezado(Filtros f, Ambito a, Optional<Lineas.Vigencia> v, String periodo) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Periodo", periodo != null ? periodo : v.map(x -> x.inicio() + " a " + x.fin()).orElse("Todo el contrato"));
        m.put("Establecimiento", f.punto() == null ? "Todos" : nombrePunto(f.punto()));
        m.put("Alcance", a.descripcion());
        v.ifPresent(x -> m.put("Vigencia", String.valueOf(x.anio())));
        if (f.fuerza() != null && !f.fuerza().isBlank()) {
            m.put("Fuerza", String.join(", ", ServicioTablero.lista(f.fuerza())));
        }
        if (f.modalidad() != null && !f.modalidad().isBlank()) {
            m.put("Modalidad", f.modalidad().strip().replace(",", ", "));
        }
        m.put("Base del ejecutado", presupuesto.base());
        return m;
    }

    private String nombrePunto(long id) {
        return jdbc.sql("SELECT coalesce(nullif(sigla, '') || ' · ', '') || nombre FROM puntos WHERE id = :id").param("id", id).query(String.class).optional()
                .orElse("—");
    }

    private Optional<Lineas.Vigencia> vigencia(Filtros f) {
        return Optional.ofNullable(f.vigencia()).map(id -> lineas.vigencia(id).orElseThrow(() -> ErrorNegocio.validacion("La vigencia no existe.")));
    }

    // ---------- 1. Ejecución por Fuerza, ESM y modalidad ----------

    private Reporte ejecucion(String titulo, Filtros f, Ambito a, String usuario, ZonedDateTime generado) {
        ServicioTablero.Consulta q = tablero.consulta(new ServicioTablero.Filtros(f.vigencia(), f.corte(), f.fuerza(), f.punto(), f.modalidad()), a);
        boolean conCorte = q.corte() != null;
        List<String> columnas = new ArrayList<>(List.of("Vigencia", "Fuerza", "ESM", "Modalidad", "Asignado", "Facturado", "Reconocido",
                conCorte ? "Ejecutado al corte" : "Ejecutado", "Saldo", "% ejecución"));
        if (conCorte) {
            columnas.addAll(List.of("Proyectado al corte", "Desviación %"));
        }
        columnas.add("Umbral notificado");
        List<List<Object>> filas = new ArrayList<>();
        Map<String, BigDecimal[]> porFuerza = new TreeMap<>();
        BigDecimal[] nacional = {cero(), cero(), cero(), cero(), cero()};
        for (ServicioTablero.Fila x : q.filas()) {
            List<Object> fila = new ArrayList<>(List.of(x.vigencia(), x.fuerza(), x.esm(), x.modalidadNombre(), x.asignado(), x.facturado(), x.reconocido(),
                    x.ejecutado(), x.asignado().subtract(x.ejecutado()), pct(x.ejecutado(), x.asignado())));
            if (conCorte) {
                fila.add(x.proyectado());
                fila.add(Presupuesto.desviacion(x.ejecutado(), x.proyectado()).map(Object.class::cast).orElse(""));
            }
            fila.add(x.umbral() == null ? "" : x.umbral() + " %");
            filas.add(fila);
            BigDecimal[] t = porFuerza.computeIfAbsent(x.fuerza(), k -> new BigDecimal[] {cero(), cero(), cero(), cero(), cero()});
            BigDecimal[] valores = {x.asignado(), x.facturado(), x.reconocido(), x.ejecutado(), x.proyectado() == null ? cero() : x.proyectado()};
            for (int i = 0; i < 5; i++) {
                t[i] = t[i].add(valores[i]);
                nacional[i] = nacional[i].add(valores[i]);
            }
        }
        // Totales: por fuerza y nacional (sumas de las líneas)
        porFuerza.forEach((fuerza, t) -> filas.add(total("Total " + fuerza, t, conCorte)));
        if (!q.filas().isEmpty()) {
            filas.add(total(a.nacional() ? "Total nacional" : "Total del alcance", nacional, conCorte));
        }
        Map<String, String> enc = encabezado(f, a, q.vigencia(), null);
        if (conCorte) {
            enc.put("Corte", q.corte().toString());
        }
        return new Reporte(titulo, generado, usuario, enc, columnas, filas);
    }

    private static List<Object> total(String nombre, BigDecimal[] t, boolean conCorte) {
        List<Object> fila = new ArrayList<>(List.of("", nombre, "", "", t[0], t[1], t[2], t[3], t[0].subtract(t[3]), pct(t[3], t[0])));
        if (conCorte) {
            fila.add(t[4]);
            fila.add(Presupuesto.desviacion(t[3], t[4]).map(Object.class::cast).orElse(""));
        }
        fila.add("");
        return fila;
    }

    // ---------- 2. Ejecución mensual frente a la proyección ----------

    private Reporte mensual(String titulo, Filtros f, Ambito a, String usuario, ZonedDateTime generado) {
        Lineas.Vigencia v = vigencia(f).orElseThrow(() -> ErrorNegocio.validacion("Elija la vigencia del reporte mensual."));
        String agrupacion = f.agrupacion() == null || f.agrupacion().isBlank() ? "mes" : f.agrupacion().strip();
        if (!AGRUPACIONES.contains(agrupacion)) {
            throw ErrorNegocio.validacion("La agrupación debe ser mes, bimestre, trimestre, semestre o año.");
        }
        String base = presupuesto.base();
        ServicioTablero.Consulta q = tablero.consulta(new ServicioTablero.Filtros(v.id(), null, f.fuerza(), f.punto(), f.modalidad()), a);
        List<YearMonth> meses = Presupuesto.meses(v.inicio(), v.fin());
        Map<Long, ServicioTablero.Fila> porLinea = new LinkedHashMap<>();
        q.filas().forEach(x -> porLinea.put(x.id(), x));
        Long[] ids = porLinea.keySet().toArray(Long[]::new);
        // Ejecución vigente y proyección cargada por línea y mes (dos consultas)
        Map<String, BigDecimal> ejecutado = new HashMap<>();
        String campo = Presupuesto.BASE_RECONOCIDO.equals(base) ? "coalesce(e.reconocido, 0)" : "e.facturado";
        jdbc.sql("SELECT e.linea_id, e.periodo, sum(" + campo + ") FROM presupuesto_ejecucion e WHERE e.vigente AND e.linea_id = ANY(:ids) GROUP BY e.linea_id, e.periodo")
                .param("ids", ids).query((rs, n) -> Map.entry(rs.getLong(1) + "|" + YearMonth.from(rs.getObject(2, LocalDate.class)), rs.getBigDecimal(3)))
                .list().forEach(e -> ejecutado.put(e.getKey(), e.getValue()));
        Map<String, BigDecimal> cargada = new HashMap<>();
        java.util.Set<Long> conCargada = new java.util.HashSet<>();
        jdbc.sql("SELECT x.linea_id, x.periodo, x.valor FROM presupuesto_proyeccion x WHERE x.vigente AND x.linea_id = ANY(:ids)").param("ids", ids)
                .query((rs, n) -> new Object[] {rs.getLong(1), YearMonth.from(rs.getObject(2, LocalDate.class)), rs.getBigDecimal(3)}).list().forEach(o -> {
                    cargada.put(o[0] + "|" + o[1], (BigDecimal) o[2]);
                    conCargada.add((Long) o[0]);
                });
        List<List<Object>> filas = new ArrayList<>();
        for (ServicioTablero.Fila x : porLinea.values()) {
            List<BigDecimal> lineal = Presupuesto.proyeccionLineal(x.asignado(), meses.size());
            Map<String, BigDecimal[]> grupos = new LinkedHashMap<>();
            for (int i = 0; i < meses.size(); i++) {
                YearMonth m = meses.get(i);
                String k = x.id() + "|" + m;
                BigDecimal proy = conCargada.contains(x.id()) ? cargada.getOrDefault(k, cero()) : lineal.get(i);
                BigDecimal ej = ejecutado.getOrDefault(k, cero());
                grupos.merge(grupo(m, agrupacion), new BigDecimal[] {ej, proy}, (o, n) -> new BigDecimal[] {o[0].add(n[0]), o[1].add(n[1])});
            }
            grupos.forEach((g, e) -> filas.add(List.of(g, x.fuerza(), x.esm(), x.modalidadNombre(), e[0], e[1], e[0].subtract(e[1]),
                    Presupuesto.desviacion(e[0], e[1]).map(Object.class::cast).orElse(""))));
            if (filas.size() > MAX_FILAS) {
                throw ErrorNegocio.validacion("El reporte pasa de " + MAX_FILAS + " filas: filtre por fuerza, ESM o modalidad.");
            }
        }
        Map<String, String> enc = encabezado(f, a, Optional.of(v), v.inicio() + " a " + v.fin());
        enc.put("Agrupación", agrupacion.equals("anio") ? "año" : agrupacion);
        return new Reporte(titulo, generado, usuario, enc,
                List.of("Periodo", "Fuerza", "ESM", "Modalidad", "Ejecutado", "Proyectado", "Diferencia", "Desviación %"), filas);
    }

    /** «2027-03», «2027-B2», «2027-T1», «2027-S1» o «2027». */
    static String grupo(YearMonth m, String agrupacion) {
        int mes = m.getMonthValue();
        return switch (agrupacion) {
            case "bimestre" -> m.getYear() + "-B" + ((mes - 1) / 2 + 1);
            case "trimestre" -> m.getYear() + "-T" + ((mes - 1) / 3 + 1);
            case "semestre" -> m.getYear() + "-S" + ((mes - 1) / 6 + 1);
            case "anio" -> String.valueOf(m.getYear());
            default -> m.toString();
        };
    }

    // ---------- 3. Movimientos presupuestales ----------

    private Reporte movimientos(String titulo, Filtros f, Ambito a, String usuario, ZonedDateTime generado) {
        Optional<Lineas.Vigencia> v = vigencia(f);
        Map<String, Object> p = new HashMap<>(a.parametros());
        StringBuilder w = new StringBuilder(" WHERE (" + a.condicion() + ")");
        v.ifPresent(x -> {
            w.append(" AND mv.vigencia_id = :f_vigencia");
            p.put("f_vigencia", x.id());
        });
        filtrosLinea(f, w, p);
        if (f.tipoMovimiento() != null && !f.tipoMovimiento().isBlank()) {
            if (PresupuestoNovedad.Tipo.deCodigo(f.tipoMovimiento().strip()).isEmpty()) {
                throw ErrorNegocio.validacion("El tipo de movimiento no es válido.");
            }
            w.append(" AND mv.tipo = :f_tipo");
            p.put("f_tipo", f.tipoMovimiento().strip());
        }
        String periodo = fechas(f, w, p, "mv.creado");
        p.put("tope", MAX_FILAS + 1);
        List<List<Object>> filas = jdbc.sql("""
                SELECT mv.creado, mv.id, mv.tipo, v.anio, mv.soporte, u.usuario, l.fuerza, coalesce(nullif(pt.sigla, '') || ' · ', '') || pt.nombre AS esm,
                       m.nombre AS modalidad, ml.valor, ml.asignado_antes, ml.asignado_despues, mv.lote_id, mv.observacion
                FROM presupuesto_movimientos mv JOIN presupuesto_movimientos_lineas ml ON ml.movimiento_id = mv.id
                JOIN presupuesto_lineas l ON l.id = ml.linea_id JOIN puntos pt ON pt.id = l.punto_id JOIN presupuesto_modalidades m ON m.codigo = l.modalidad
                JOIN presupuesto_vigencias v ON v.id = mv.vigencia_id JOIN usuarios u ON u.id = mv.registrado_por""" + w
                + " ORDER BY mv.creado, mv.id, ml.valor, l.id LIMIT :tope").params(p).query((rs, n) -> {
                    List<Object> x = new ArrayList<>();
                    x.add(rs.getObject("creado", java.time.OffsetDateTime.class));
                    x.add(rs.getLong("id"));
                    x.add(PresupuestoNovedad.Tipo.deCodigo(rs.getString("tipo")).map(PresupuestoNovedad.Tipo::nombre).orElse(rs.getString("tipo")));
                    x.add(rs.getInt("anio"));
                    x.add(rs.getString("soporte"));
                    x.add(rs.getString("usuario"));
                    x.add(rs.getString("fuerza"));
                    x.add(rs.getString("esm"));
                    x.add(rs.getString("modalidad"));
                    x.add(rs.getBigDecimal("valor"));
                    x.add(rs.getBigDecimal("asignado_antes"));
                    x.add(rs.getBigDecimal("asignado_despues"));
                    x.add(rs.getObject("lote_id") == null ? "" : rs.getLong("lote_id"));
                    x.add(rs.getString("observacion") == null ? "" : rs.getString("observacion"));
                    return x;
                }).list();
        tope(filas);
        return new Reporte(titulo, generado, usuario, encabezado(f, a, v, periodo), List.of("Fecha y hora", "Movimiento", "Tipo", "Vigencia", "Soporte DIGSA",
                "Usuario", "Fuerza", "ESM", "Modalidad", "Valor", "Asignado antes", "Asignado después", "Lote", "Observación"), filas);
    }

    // ---------- 4. Umbrales y desviaciones notificados ----------

    private Reporte alertas(String titulo, Filtros f, Ambito a, String usuario, ZonedDateTime generado) {
        Optional<Lineas.Vigencia> v = vigencia(f);
        Map<String, Object> p = new HashMap<>(a.parametros());
        String condicion = a.parametros().containsKey("ambito_fuerza") ? "(l.fuerza = :ambito_fuerza OR n.fuerza = :ambito_fuerza OR n.nivel = 'nacional')"
                : a.parametros().containsKey("ambito_punto")
                ? "(l.punto_id = :ambito_punto OR EXISTS (SELECT 1 FROM presupuesto_lineas lc WHERE lc.id = ANY(n.lineas) AND lc.punto_id = :ambito_punto))"
                : a.condicion();
        StringBuilder w = new StringBuilder(" WHERE " + condicion);
        v.ifPresent(x -> {
            w.append(" AND n.vigencia_id = :f_vigencia");
            p.put("f_vigencia", x.id());
        });
        if (f.fuerza() != null && !f.fuerza().isBlank()) {
            w.append(" AND coalesce(l.fuerza, n.fuerza) = ANY(:f_fuerzas)");
            p.put("f_fuerzas", ServicioTablero.lista(f.fuerza()));
        }
        if (f.punto() != null) {
            w.append(" AND (l.punto_id = :f_punto OR EXISTS (SELECT 1 FROM presupuesto_lineas lp WHERE lp.id = ANY(n.lineas) AND lp.punto_id = :f_punto))");
            p.put("f_punto", f.punto());
        }
        String periodo = fechas(f, w, p, "n.creado");
        p.put("tope", MAX_FILAS + 1);
        List<List<Object>> filas = jdbc.sql("""
                SELECT n.creado, n.consecutivo, n.tipo, n.nivel, coalesce(l.fuerza, n.fuerza) AS fuerza,
                       CASE WHEN l.id IS NULL THEN '' ELSE coalesce(nullif(pt.sigla, '') || ' · ', '') || pt.nombre END AS esm, n.corte, n.asunto, n.estado,
                       c.estado AS envio
                FROM presupuesto_notificaciones n LEFT JOIN presupuesto_lineas l ON l.id = n.linea_id LEFT JOIN puntos pt ON pt.id = l.punto_id
                LEFT JOIN correos_salida c ON c.id = n.correo_id""" + w + " ORDER BY n.creado, n.id LIMIT :tope").params(p).query((rs, n) -> {
                    List<Object> x = new ArrayList<>();
                    x.add(rs.getObject("creado", java.time.OffsetDateTime.class));
                    x.add(rs.getString("consecutivo"));
                    x.add("umbral".equals(rs.getString("tipo")) ? "Umbral" : "Desviación");
                    x.add(rs.getString("nivel"));
                    x.add(rs.getString("fuerza") == null ? "Todas" : rs.getString("fuerza"));
                    x.add(rs.getString("esm"));
                    x.add(rs.getObject("corte") == null ? "" : rs.getObject("corte").toString().substring(0, 7));
                    x.add(rs.getString("asunto"));
                    String envio = rs.getString("envio");
                    x.add(rs.getString("estado") + (envio == null ? "" : " · correo " + envio));
                    return x;
                }).list();
        tope(filas);
        return new Reporte(titulo, generado, usuario, encabezado(f, a, v, periodo),
                List.of("Fecha y hora", "Consecutivo", "Tipo", "Nivel", "Fuerza", "ESM", "Corte", "Asunto", "Estado"), filas);
    }

    // ---------- Comunes ----------

    private static void filtrosLinea(Filtros f, StringBuilder w, Map<String, Object> p) {
        if (f.fuerza() != null && !f.fuerza().isBlank()) {
            w.append(" AND l.fuerza = ANY(:f_fuerzas)");
            p.put("f_fuerzas", ServicioTablero.lista(f.fuerza()));
        }
        if (f.punto() != null) {
            w.append(" AND l.punto_id = :f_punto");
            p.put("f_punto", f.punto());
        }
        if (f.modalidad() != null && !f.modalidad().isBlank()) {
            w.append(" AND l.modalidad = ANY(:f_modalidades)");
            p.put("f_modalidades", ServicioTablero.lista(f.modalidad()));
        }
    }

    /** Desde y hasta (días en Bogotá, inclusive) sobre la columna de fecha; devuelve el texto del periodo o null. */
    private static String fechas(Filtros f, StringBuilder w, Map<String, Object> p, String columna) {
        LocalDate desde = f.desde() == null || f.desde().isBlank() ? null
                : Fechas.valida(f.desde()).orElseThrow(() -> ErrorNegocio.validacion("La fecha «desde» no es válida (AAAA-MM-DD)."));
        LocalDate hasta = f.hasta() == null || f.hasta().isBlank() ? null
                : Fechas.valida(f.hasta()).orElseThrow(() -> ErrorNegocio.validacion("La fecha «hasta» no es válida (AAAA-MM-DD)."));
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw ErrorNegocio.validacion("La fecha «desde» no puede ser posterior a «hasta».");
        }
        if (desde != null) {
            w.append(" AND ").append(columna).append(" >= :f_desde");
            p.put("f_desde", desde.atStartOfDay(Fechas.BOGOTA).toOffsetDateTime());
        }
        if (hasta != null) {
            w.append(" AND ").append(columna).append(" < :f_hasta");
            p.put("f_hasta", hasta.plusDays(1).atStartOfDay(Fechas.BOGOTA).toOffsetDateTime());
        }
        return desde == null && hasta == null ? null : (desde == null ? "inicio" : desde.toString()) + " a " + (hasta == null ? "hoy" : hasta.toString());
    }

    private static void tope(List<List<Object>> filas) {
        if (filas.size() > MAX_FILAS) {
            throw ErrorNegocio.validacion("El reporte pasa de " + MAX_FILAS + " filas: filtre por vigencia, fechas, fuerza o ESM.");
        }
    }

    private static Object pct(BigDecimal ejecutado, BigDecimal asignado) {
        return Presupuesto.porcentaje(ejecutado, asignado).map(Object.class::cast).orElse("");
    }

    private static BigDecimal cero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
