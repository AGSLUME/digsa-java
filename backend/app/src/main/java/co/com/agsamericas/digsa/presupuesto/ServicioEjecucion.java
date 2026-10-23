package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Presupuesto;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.Clase;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.ErrorFila;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.Fila;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ejecución presupuestal (spec «Ejecución por un puerto con varias fuentes» y «Ejecución por encima del 100 %»): cortes mensuales por
 * ESM y modalidad con lo facturado y lo reconocido. Un corte nuevo del mismo mes, línea y punto de origen reemplaza al anterior como
 * versión vigente sin borrarlo; el saldo se recalcula sumando las versiones vigentes, en la misma transacción y con la línea bloqueada.
 * La ejecución nunca se bloquea: puede pasar del 100 % (saldo negativo). Después de cada cambio se evalúan los umbrales y la desviación.
 */
@Service
public class ServicioEjecucion implements PuertoEjecucion {

    /** Registro manual desde el formulario. */
    public record Manual(Long vigenciaId, Long puntoId, String modalidad, String fuerza, String periodo, BigDecimal facturado, BigDecimal reconocido,
                         String referencia) {
    }

    /** Filtros de las versiones de ejecución. */
    public record Filtros(Long vigencia, String fuerza, Long punto, String modalidad, String periodo, Integer pagina) {
    }

    private static final Set<String> FUENTES = Set.of("digsa", "opl");
    static final int POR_PAGINA = 50;

    /** Corte ya resuelto a su línea (el satélite, en punto de origen). */
    record CorteResuelto(Lineas.Destino destino, YearMonth periodo, BigDecimal facturado, BigDecimal reconocido, String referencia, int fila) {
        String clave() {
            return destino.clave() + "|" + destino.puntoOrigenId() + "|" + periodo;
        }
    }

    private final JdbcClient jdbc;
    private final Lineas lineas;
    private final ServicioNovedades novedades;
    private final RegistroBitacora bitacora;
    private final Clock reloj;
    private final TransactionTemplate tx;
    private final ObjectProvider<CambioDeSaldos> alCambiar;
    private final ObjectProvider<CorteDeEjecucion> alCortar;

    public ServicioEjecucion(JdbcClient jdbc, Lineas lineas, ServicioNovedades novedades, RegistroBitacora bitacora, Clock reloj,
                             PlatformTransactionManager transacciones, ObjectProvider<CambioDeSaldos> alCambiar, ObjectProvider<CorteDeEjecucion> alCortar) {
        this.jdbc = jdbc;
        this.lineas = lineas;
        this.novedades = novedades;
        this.bitacora = bitacora;
        this.reloj = reloj;
        this.tx = new TransactionTemplate(transacciones);
        this.alCambiar = alCambiar;
        this.alCortar = alCortar;
    }

    // ---------- Carga de archivo ----------

    record Revision(List<CorteResuelto> validas, List<ErrorFila> errores, String huella, Map<String, Object> yaAplicado) {
    }

    public Map<String, Object> simular(MultipartFile archivo, UsuarioSesion quien) {
        Revision r = revisar(archivo);
        if (r.yaAplicado() != null) {
            return r.yaAplicado();
        }
        bitacora.registrar("presupuesto_carga_simulada", "Ejecución simulada · " + r.validas().size() + " válidas · " + r.errores().size() + " con errores",
                quien.id(), quien.usuario());
        BigDecimal facturado = r.validas().stream().map(CorteResuelto::facturado).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("validas", r.validas().size());
        m.put("errores", r.errores());
        m.put("totalNacional", Presupuesto.formato(facturado));
        m.put("mensaje", r.errores().isEmpty() ? "El archivo está listo para aplicar: " + r.validas().size() + " cortes, facturado " + Presupuesto.formato(facturado) + "."
                : "Hay " + r.errores().size() + " fila(s) con errores: corríjalas antes de aplicar.");
        return m;
    }

    public Map<String, Object> aplicar(MultipartFile archivo, String fuente, UsuarioSesion quien) {
        String f = fuente == null || fuente.isBlank() ? "digsa" : fuente.strip().toLowerCase(java.util.Locale.ROOT);
        if (!FUENTES.contains(f)) {
            throw ErrorNegocio.validacion("La fuente debe ser la DIGSA o el OPL.");
        }
        Revision r = revisar(archivo);
        if (r.yaAplicado() != null) {
            return r.yaAplicado();
        }
        if (!r.errores().isEmpty()) {
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "La carga no se aplicó: " + r.errores().size() + " fila(s) con errores.",
                    Map.of("errores", r.errores()));
        }
        try {
            return tx.execute(t -> {
                BigDecimal total = r.validas().stream().map(CorteResuelto::facturado).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
                long lote = jdbc.sql("""
                        INSERT INTO presupuesto_lotes (clase, fuente, sha256, nombre_original, filas, total, aplicado_por, aplicado)
                        VALUES ('ejecucion', :f, :h, :n, :filas, :total, :u, :ahora) RETURNING id""")
                        .param("f", f).param("h", r.huella()).param("n", ServicioNovedades.nombreArchivo(archivo)).param("filas", r.validas().size())
                        .param("total", total).param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
                registrar(r.validas(), f, lote, quien);
                bitacora.registrar("presupuesto_ejecucion_carga", "Ejecución · lote " + lote + " · " + r.validas().size() + " cortes · facturado "
                        + Presupuesto.formato(total), quien.id(), quien.usuario());
                return Map.<String, Object>of("lote", lote, "cortes", r.validas().size(), "mensaje",
                        "Ejecución aplicada: " + r.validas().size() + " cortes, facturado " + Presupuesto.formato(total) + ".");
            });
        } catch (DuplicateKeyException e) {
            return novedades.yaAplicado("ejecucion", r.huella()).orElseThrow(() -> e);
        }
    }

    private Revision revisar(MultipartFile archivo) {
        byte[] bytes = ServicioNovedades.bytes(archivo);
        String huella = Huellas.sha256(bytes);
        Optional<Map<String, Object>> ya = novedades.yaAplicado("ejecucion", huella);
        if (ya.isPresent()) {
            return new Revision(List.of(), List.of(), huella, ya.get());
        }
        PresupuestoCarga.Lectura l = PresupuestoCarga.leer(Clase.EJECUCION, LectorTablas.leer(archivo.getOriginalFilename(), bytes));
        List<ErrorFila> errores = new ArrayList<>(l.errores());
        List<CorteResuelto> validas = new ArrayList<>();
        Lineas.Catalogo c = lineas.catalogo();
        Map<String, Integer> vistas = new HashMap<>();
        for (Fila f : l.filas()) {
            List<String> motivos = new ArrayList<>();
            Optional<Lineas.Destino> d = Lineas.resolver(c, f, Clase.EJECUCION, motivos);
            if (d.isPresent()) {
                CorteResuelto cr = new CorteResuelto(d.get(), f.periodo(), f.valor(), f.reconocido(), f.referencia(), f.numero());
                Integer previa = vistas.putIfAbsent(cr.clave(), f.numero());
                if (previa != null) {
                    motivos.add("El mismo ESM, modalidad y periodo ya viene en la fila " + previa);
                } else {
                    validas.add(cr);
                }
            }
            if (!motivos.isEmpty()) {
                errores.add(new ErrorFila(f.numero(), String.join("; ", motivos) + "."));
            }
        }
        errores.sort(Comparator.comparingInt(ErrorFila::fila));
        return new Revision(validas, errores, huella, null);
    }

    // ---------- Registro manual ----------

    public Map<String, Object> manual(Manual m, UsuarioSesion quien) {
        if (m == null) {
            throw ErrorNegocio.validacion("Faltan los datos de la ejecución.");
        }
        YearMonth periodo = Presupuesto.periodo(m.periodo()).orElseThrow(() -> ErrorNegocio.validacion("El periodo debe ser un mes (AAAA-MM)."));
        if (m.facturado() == null || m.facturado().signum() < 0 || m.facturado().stripTrailingZeros().scale() > 2
                || m.facturado().compareTo(Presupuesto.MAXIMO) > 0) {
            throw ErrorNegocio.validacion("El facturado debe ser un valor en pesos mayor o igual a 0, con máximo 2 decimales.");
        }
        if (m.reconocido() != null && (m.reconocido().signum() < 0 || m.reconocido().stripTrailingZeros().scale() > 2)) {
            throw ErrorNegocio.validacion("El reconocido debe ser un valor en pesos mayor o igual a 0, con máximo 2 decimales.");
        }
        if (m.reconocido() != null && m.reconocido().compareTo(m.facturado()) > 0) {
            throw ErrorNegocio.validacion("El reconocido (" + Presupuesto.formato(m.reconocido()) + ") no puede superar el facturado ("
                    + Presupuesto.formato(m.facturado()) + ").");
        }
        String referencia = m.referencia() == null || m.referencia().isBlank() ? null : m.referencia().strip();
        if (referencia != null && referencia.length() > PresupuestoCarga.MAX_REFERENCIA) {
            throw ErrorNegocio.validacion("La referencia tiene máximo " + PresupuestoCarga.MAX_REFERENCIA + " caracteres.");
        }
        return tx.execute(t -> {
            Lineas.Vigencia v = lineas.abierta(m.vigenciaId());
            if (!v.contiene(periodo)) {
                throw ErrorNegocio.validacion("El periodo " + periodo + " está fuera de la vigencia " + v.anio() + ".");
            }
            Lineas.Catalogo c = lineas.catalogo();
            Lineas.Punto p = m.puntoId() == null ? null : c.puntos().get(m.puntoId());
            if (p == null) {
                throw ErrorNegocio.validacion("Seleccione un ESM válido.");
            }
            List<String> motivos = new ArrayList<>();
            Lineas.Destino d = Lineas.destino(c, v, p, m.modalidad(), m.fuerza(), Clase.EJECUCION, motivos)
                    .orElseThrow(() -> ErrorNegocio.validacion(String.join("; ", motivos) + "."));
            CorteResuelto cr = new CorteResuelto(d, periodo, m.facturado().setScale(2, java.math.RoundingMode.UNNECESSARY),
                    m.reconocido() == null ? null : m.reconocido().setScale(2, java.math.RoundingMode.UNNECESSARY), referencia, 0);
            registrar(List.of(cr), "manual", null, quien);
            bitacora.registrar("presupuesto_ejecucion_manual", "Ejecución manual · " + d.etiqueta() + " · " + periodo + " · facturado "
                    + Presupuesto.formato(cr.facturado()) + (cr.reconocido() == null ? "" : " · reconocido " + Presupuesto.formato(cr.reconocido())),
                    quien.id(), quien.usuario());
            return Map.<String, Object>of("mensaje", "Ejecución registrada: " + d.etiqueta() + ", " + periodo + ".");
        });
    }

    // ---------- Puerto para otros módulos (cuentas médicas) ----------

    @Override
    public Resultado registrarCortes(List<Corte> cortes, String referenciaLote) {
        if (cortes == null || cortes.isEmpty()) {
            return new Resultado(0, List.of("No hay cortes."));
        }
        Lineas.Catalogo c = lineas.catalogo();
        List<String> errores = new ArrayList<>();
        List<CorteResuelto> validos = new ArrayList<>();
        Map<String, Integer> vistos = new HashMap<>();
        int i = 0;
        for (Corte k : cortes) {
            i++;
            List<String> motivos = new ArrayList<>();
            if (k.periodo() == null || !montoValido(k.facturado()) || (k.reconocido() != null && !montoValido(k.reconocido()))
                    || (k.reconocido() != null && k.reconocido().compareTo(k.facturado()) > 0)
                    || (k.referencia() != null && k.referencia().length() > PresupuestoCarga.MAX_REFERENCIA)) {
                errores.add("Corte " + i + ": periodo, facturado, reconocido o referencia inválidos");
                continue;
            }
            Fila f = new Fila(i, k.vigencia(), k.fuerza(), k.esm(), k.modalidad(), k.periodo(), k.facturado(), k.reconocido(), k.referencia());
            final int n = i;
            Lineas.resolver(c, f, Clase.EJECUCION, motivos).ifPresent(d -> {
                CorteResuelto cr = new CorteResuelto(d, k.periodo(), k.facturado().setScale(2, java.math.RoundingMode.UNNECESSARY),
                        k.reconocido() == null ? null : k.reconocido().setScale(2, java.math.RoundingMode.UNNECESSARY), k.referencia(), n);
                Integer previo = vistos.putIfAbsent(cr.clave(), n);
                if (previo != null) {
                    motivos.add("repite el ESM, la modalidad y el mes del corte " + previo);
                } else {
                    validos.add(cr);
                }
            });
            motivos.forEach(x -> errores.add("Corte " + k.esm() + " " + k.periodo() + ": " + x));
        }
        if (!errores.isEmpty()) {
            return new Resultado(0, errores);
        }
        tx.executeWithoutResult(t -> {
            registrar(validos, "cuentas_medicas", null, null);
            bitacora.registrar("presupuesto_ejecucion_carga", "Ejecución de cuentas médicas · " + validos.size() + " cortes · " + referenciaLote, null, "sistema");
        });
        return new Resultado(validos.size(), List.of());
    }

    /** Monto de un corte: ≥ 0, máximo 2 decimales y dentro de numeric(18,2) (lo mismo que exige el registro manual). */
    private static boolean montoValido(BigDecimal v) {
        return v != null && v.signum() >= 0 && v.stripTrailingZeros().scale() <= 2 && v.compareTo(Presupuesto.MAXIMO) <= 0;
    }

    // ---------- Núcleo ----------

    /**
     * Registra los cortes (dentro de la transacción de quien llama): asegura las líneas, bloquea sus saldos en orden, marca como
     * reemplazada la versión vigente de cada mes y punto de origen, inserta la nueva, recalcula los saldos con las vigentes y dispara los
     * umbrales y la desviación por vigencia.
     */
    void registrar(List<CorteResuelto> cortes, String fuente, Long loteId, UsuarioSesion quien) {
        Map<Long, List<CorteResuelto>> porVigencia = new TreeMap<>();
        cortes.forEach(c -> porVigencia.computeIfAbsent(c.destino().vigenciaId(), k -> new ArrayList<>()).add(c));
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        for (Map.Entry<Long, List<CorteResuelto>> e : porVigencia.entrySet()) {
            Lineas.Vigencia v = lineas.abierta(e.getKey());
            // Cada línea se asegura una sola vez y en un orden fijo: dos cargas que crean las mismas líneas no se bloquean entre sí
            Map<String, Long> porClave = new TreeMap<>();
            e.getValue().stream().map(CorteResuelto::destino).sorted(Comparator.comparing(Lineas.Destino::clave))
                    .forEach(d -> porClave.computeIfAbsent(d.clave(), k -> lineas.asegurar(d)));
            Map<CorteResuelto, Long> linea = new LinkedHashMap<>();
            for (CorteResuelto c : e.getValue()) {
                linea.put(c, porClave.get(c.destino().clave()));
            }
            List<Long> ids = linea.values().stream().distinct().sorted().toList();
            lineas.bloquear(ids);
            Set<YearMonth> periodos = new LinkedHashSet<>();
            for (Map.Entry<CorteResuelto, Long> x : linea.entrySet()) {
                CorteResuelto c = x.getKey();
                LocalDate periodo = c.periodo().atDay(1);
                jdbc.sql("""
                        UPDATE presupuesto_ejecucion SET vigente = false, reemplazada_en = :ahora
                        WHERE linea_id = :l AND periodo = :p AND punto_origen_id = :o AND vigente""")
                        .param("ahora", ahora).param("l", x.getValue()).param("p", periodo).param("o", c.destino().puntoOrigenId()).update();
                jdbc.sql("""
                        INSERT INTO presupuesto_ejecucion (linea_id, punto_origen_id, periodo, facturado, reconocido, fuente, referencia, lote_id, registrado_por, creado)
                        VALUES (:l, :o, :p, :f, :r, :fuente, :ref, :lote, :u, :ahora)""")
                        .param("l", x.getValue()).param("o", c.destino().puntoOrigenId()).param("p", periodo).param("f", c.facturado()).param("r", c.reconocido())
                        .param("fuente", fuente).param("ref", c.referencia()).param("lote", loteId).param("u", quien == null ? null : quien.id())
                        .param("ahora", ahora).update();
                periodos.add(c.periodo());
            }
            // El saldo es la suma de las versiones vigentes (invariante de la spec): se recalcula, no se acumula
            jdbc.sql("""
                    UPDATE presupuesto_saldos s SET facturado = x.f, reconocido = x.r, version = s.version + 1, actualizado = :ahora
                    FROM (SELECT l.id AS linea_id, coalesce(sum(e.facturado), 0) AS f, coalesce(sum(coalesce(e.reconocido, 0)), 0) AS r
                          FROM presupuesto_lineas l LEFT JOIN presupuesto_ejecucion e ON e.linea_id = l.id AND e.vigente
                          WHERE l.id = ANY(:ids) GROUP BY l.id) x
                    WHERE s.linea_id = x.linea_id""").param("ahora", ahora).param("ids", ids.toArray(Long[]::new)).update();
            alCambiar.orderedStream().forEach(a -> a.saldosCambiados(v, ids, quien));
            alCortar.orderedStream().forEach(a -> a.ejecucionRegistrada(v, periodos, ids, quien));
        }
    }

    // ---------- Consulta de las versiones ----------

    public Map<String, Object> versiones(Filtros f, Ambito a) {
        Map<String, Object> p = new HashMap<>(a.parametros());
        StringBuilder w = new StringBuilder(" WHERE (" + a.condicion() + ")");
        if (f != null && f.vigencia() != null) {
            w.append(" AND l.vigencia_id = :f_vigencia");
            p.put("f_vigencia", f.vigencia());
        }
        if (f != null && f.fuerza() != null && !f.fuerza().isBlank()) {
            w.append(" AND l.fuerza = :f_fuerza");
            p.put("f_fuerza", f.fuerza().strip());
        }
        if (f != null && f.punto() != null) {
            w.append(" AND (l.punto_id = :f_punto OR e.punto_origen_id = :f_punto)");
            p.put("f_punto", f.punto());
        }
        if (f != null && f.modalidad() != null && !f.modalidad().isBlank()) {
            w.append(" AND l.modalidad = :f_modalidad");
            p.put("f_modalidad", f.modalidad().strip());
        }
        if (f != null && f.periodo() != null && !f.periodo().isBlank()) {
            YearMonth m = Presupuesto.periodo(f.periodo()).orElseThrow(() -> ErrorNegocio.validacion("El periodo debe ser un mes (AAAA-MM)."));
            w.append(" AND e.periodo = :f_periodo");
            p.put("f_periodo", m.atDay(1));
        }
        int pagina = f == null || f.pagina() == null || f.pagina() < 1 ? 1 : Math.min(f.pagina(), 100_000);
        String desde = """
                FROM presupuesto_ejecucion e JOIN presupuesto_lineas l ON l.id = e.linea_id JOIN puntos p ON p.id = l.punto_id
                JOIN puntos o ON o.id = e.punto_origen_id JOIN presupuesto_modalidades m ON m.codigo = l.modalidad
                LEFT JOIN usuarios u ON u.id = e.registrado_por""" + w;
        long total = jdbc.sql("SELECT count(*) " + desde).params(p).query(Long.class).single();
        p.put("limite", POR_PAGINA);
        p.put("desplazamiento", (pagina - 1) * POR_PAGINA);
        List<Map<String, Object>> items = jdbc.sql("""
                SELECT e.id, e.periodo, e.facturado, e.reconocido, e.fuente, e.referencia, e.lote_id, e.vigente, e.creado, e.reemplazada_en, l.fuerza,
                       coalesce(nullif(p.sigla, '') || ' · ', '') || p.nombre AS esm, m.nombre AS modalidad,
                       CASE WHEN o.id <> p.id THEN coalesce(nullif(o.sigla, '') || ' · ', '') || o.nombre END AS satelite, u.usuario
                """ + desde + " ORDER BY e.periodo DESC, l.fuerza, esm, e.creado DESC, e.id DESC LIMIT :limite OFFSET :desplazamiento")
                .params(p).query().listOfRows().stream().map(r -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("id", r.get("id"));
                    x.put("periodo", Presupuesto.periodoTexto(YearMonth.from(((java.sql.Date) r.get("periodo")).toLocalDate())));
                    x.put("fuerza", r.get("fuerza"));
                    x.put("esm", r.get("esm"));
                    x.put("satelite", r.get("satelite"));
                    x.put("modalidad", r.get("modalidad"));
                    x.put("facturado", Presupuesto.formato((BigDecimal) r.get("facturado")));
                    x.put("reconocido", r.get("reconocido") == null ? null : Presupuesto.formato((BigDecimal) r.get("reconocido")));
                    x.put("fuente", r.get("fuente"));
                    x.put("referencia", r.get("referencia"));
                    x.put("lote", r.get("lote_id"));
                    x.put("vigente", r.get("vigente"));
                    x.put("registrado", Lineas.fechaHora(r.get("creado")));
                    x.put("reemplazada", r.get("reemplazada_en") == null ? null : Lineas.fechaHora(r.get("reemplazada_en")));
                    x.put("usuario", r.get("usuario") == null ? "sistema" : r.get("usuario"));
                    return x;
                }).toList();
        return Map.of("total", total, "pagina", pagina, "porPagina", POR_PAGINA, "items", items);
    }
}
