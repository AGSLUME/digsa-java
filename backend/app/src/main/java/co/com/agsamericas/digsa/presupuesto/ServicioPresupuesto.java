package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Presupuesto;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consulta y configuración del presupuesto: vigencias, modalidades, centralizadores, parámetros, opciones, líneas e historial. */
@Service
public class ServicioPresupuesto {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioPresupuesto.class);
    static final int POR_PAGINA = 50;
    static final int MAX_LINEAS = 5000;

    public record VigenciaEntrada(Integer anio, String inicio, String fin, String estado) {
    }

    public record ModalidadEntrada(String codigo, String nombre, Boolean activa, Integer orden) {
    }

    /** Filtros del historial y de las líneas: vacíos = todos. */
    public record Filtros(Long vigencia, String tipo, String fuerza, Long punto, String modalidad, String desde, String hasta, Integer pagina) {
    }

    /** Parámetros del módulo: se editan aquí con su validación (en Catálogos no son editables). */
    static final Map<String, String> PARAMETROS = parametros();

    private static Map<String, String> parametros() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("presupuesto_base_umbral", "Cifra para los umbrales del 25 al 100 % (facturado o reconocido)");
        m.put("presupuesto_tolerancia_exceso", "% sobre la proyección desde el que la ejecución es exceso");
        m.put("presupuesto_tolerancia_subejecucion", "% bajo la proyección desde el que la ejecución es subejecución");
        m.put("presupuesto_consecutivo_prefijo", "Prefijo del consecutivo de las notificaciones formales");
        return m;
    }

    private final JdbcClient jdbc;
    private final Lineas lineas;
    private final Almacen almacen;
    private final RegistroBitacora bitacora;
    private final Clock reloj;

    public ServicioPresupuesto(JdbcClient jdbc, Lineas lineas, Almacen almacen, RegistroBitacora bitacora, Clock reloj) {
        this.jdbc = jdbc;
        this.lineas = lineas;
        this.almacen = almacen;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    // ---------- Parámetros ----------

    /** Valor del parámetro (texto crudo). */
    String parametro(String clave, String defecto) {
        return jdbc.sql("SELECT valor FROM par_parametros WHERE clave = :c").param("c", clave).query(String.class).optional().filter(v -> !v.isBlank())
                .orElse(defecto);
    }

    /** Base de los umbrales; si el valor guardado no sirve, facturado y queda en el registro del servidor. */
    String base() {
        String v = parametro("presupuesto_base_umbral", Presupuesto.BASE_FACTURADO);
        return Presupuesto.baseValida(v).orElseGet(() -> {
            LOG.warn("[presupuesto] presupuesto_base_umbral «{}» no es válido: se usa «facturado»", v);
            return Presupuesto.BASE_FACTURADO;
        });
    }

    BigDecimal tolerancia(String clave, BigDecimal defecto) {
        String v = parametro(clave, Presupuesto.formato(defecto));
        return Presupuesto.toleranciaValida(v).orElseGet(() -> {
            LOG.warn("[presupuesto] {} «{}» no es válido: se usa {}", clave, v, defecto);
            return defecto;
        });
    }

    public List<Map<String, Object>> parametrosLista() {
        List<Map<String, Object>> r = new ArrayList<>();
        PARAMETROS.forEach((k, d) -> r.add(Map.of("clave", k, "descripcion", d, "valor", parametro(k, ""))));
        return r;
    }

    @Transactional
    public void editarParametro(String clave, String valor, UsuarioSesion quien) {
        if (!PARAMETROS.containsKey(clave)) {
            throw ErrorNegocio.noEncontrado("El parámetro no existe.");
        }
        String v = valor == null ? "" : valor.strip();
        String limpio = switch (clave) {
            case "presupuesto_base_umbral" -> Presupuesto.baseValida(v)
                    .orElseThrow(() -> ErrorNegocio.validacion("La base de los umbrales debe ser «facturado» o «reconocido»."));
            case "presupuesto_consecutivo_prefijo" -> {
                if (!v.matches("^[A-Za-z]{2,10}$")) {
                    throw ErrorNegocio.validacion("El prefijo debe tener de 2 a 10 letras, sin espacios.");
                }
                yield v.toUpperCase(java.util.Locale.ROOT);
            }
            default -> Presupuesto.formato(Presupuesto.toleranciaValida(v)
                    .orElseThrow(() -> ErrorNegocio.validacion("La tolerancia debe ser un porcentaje entre 0 y 1000, con máximo 2 decimales.")));
        };
        String antes = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = :c FOR UPDATE").param("c", clave).query(String.class).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El parámetro no existe."));
        jdbc.sql("UPDATE par_parametros SET valor = :v, actualizado_por = :u, actualizado = :ahora WHERE clave = :c")
                .param("v", limpio).param("u", quien.usuario()).param("ahora", OffsetDateTime.now(reloj)).param("c", clave).update();
        bitacora.registrar("parametro_editado", "Parámetro " + clave + (limpio.equals(antes) ? " sin cambios" : ": «" + Bitacora.cortar(antes, 40) + "» → «"
                + Bitacora.cortar(limpio, 40) + "»"), quien.id(), quien.usuario());
    }

    // ---------- Vigencias ----------

    @Transactional
    public long crearVigencia(VigenciaEntrada e, UsuarioSesion quien) {
        if (e == null || e.anio() == null || e.anio() < 2020 || e.anio() > 2100) {
            throw ErrorNegocio.validacion("El año de la vigencia debe estar entre 2020 y 2100.");
        }
        LocalDate[] f = fechas(e.anio(), e.inicio(), e.fin());
        long id = jdbc.sql("""
                INSERT INTO presupuesto_vigencias (anio, inicio, fin, creado_por, creado, actualizado) VALUES (:a, :i, :f, :u, :ahora, :ahora)
                ON CONFLICT (anio) DO NOTHING RETURNING id""")
                .param("a", e.anio()).param("i", f[0]).param("f", f[1]).param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).query(Long.class)
                .optional().orElseThrow(() -> ErrorNegocio.conflicto("Ya existe la vigencia " + e.anio() + "."));
        bitacora.registrar("presupuesto_vigencia", "Vigencia " + e.anio() + " creada: " + f[0] + " a " + f[1], quien.id(), quien.usuario());
        return id;
    }

    @Transactional
    public void editarVigencia(long id, VigenciaEntrada e, UsuarioSesion quien) {
        Lineas.Vigencia v = jdbc.sql("SELECT id, anio, inicio, fin, estado FROM presupuesto_vigencias WHERE id = :id FOR UPDATE").param("id", id)
                .query((rs, n) -> new Lineas.Vigencia(rs.getLong("id"), rs.getInt("anio"), rs.getObject("inicio", LocalDate.class),
                        rs.getObject("fin", LocalDate.class), rs.getString("estado"))).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("La vigencia no existe."));
        String estado = e == null || e.estado() == null || e.estado().isBlank() ? v.estado() : e.estado().strip();
        if (!Set.of("abierta", "cerrada").contains(estado)) {
            throw ErrorNegocio.validacion("El estado debe ser «abierta» o «cerrada».");
        }
        LocalDate[] f = fechas(v.anio(), e == null || e.inicio() == null ? v.inicio().toString() : e.inicio(),
                e == null || e.fin() == null ? v.fin().toString() : e.fin());
        // Las fechas no pueden dejar por fuera ejecución o proyección ya registrada
        boolean fuera = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM presupuesto_ejecucion x JOIN presupuesto_lineas l ON l.id = x.linea_id
                               WHERE l.vigencia_id = :v AND (x.periodo < date_trunc('month', CAST(:i AS date)) OR x.periodo > :f))
                    OR EXISTS (SELECT 1 FROM presupuesto_proyeccion x JOIN presupuesto_lineas l ON l.id = x.linea_id
                               WHERE l.vigencia_id = :v AND (x.periodo < date_trunc('month', CAST(:i AS date)) OR x.periodo > :f))""")
                .param("v", id).param("i", f[0]).param("f", f[1]).query(Boolean.class).single();
        if (fuera) {
            throw ErrorNegocio.validacion("Las fechas dejarían por fuera ejecución o proyección ya registrada de la vigencia " + v.anio() + ".");
        }
        jdbc.sql("UPDATE presupuesto_vigencias SET inicio = :i, fin = :f, estado = :e, actualizado = :ahora WHERE id = :id")
                .param("i", f[0]).param("f", f[1]).param("e", estado).param("ahora", OffsetDateTime.now(reloj)).param("id", id).update();
        List<String> cambios = new ArrayList<>();
        if (!v.inicio().equals(f[0])) {
            cambios.add("inicio: «" + v.inicio() + "» → «" + f[0] + "»");
        }
        if (!v.fin().equals(f[1])) {
            cambios.add("fin: «" + v.fin() + "» → «" + f[1] + "»");
        }
        if (!v.estado().equals(estado)) {
            cambios.add("estado: «" + v.estado() + "» → «" + estado + "»");
        }
        bitacora.registrar("presupuesto_vigencia", "Vigencia " + v.anio() + (cambios.isEmpty() ? " sin cambios" : ": " + String.join("; ", cambios)),
                quien.id(), quien.usuario());
    }

    private static LocalDate[] fechas(int anio, String inicio, String fin) {
        LocalDate i = inicio == null || inicio.isBlank() ? LocalDate.of(anio, 1, 1)
                : Fechas.valida(inicio).orElseThrow(() -> ErrorNegocio.validacion("La fecha de inicio no es válida (AAAA-MM-DD)."));
        LocalDate f = fin == null || fin.isBlank() ? LocalDate.of(anio, 12, 31)
                : Fechas.valida(fin).orElseThrow(() -> ErrorNegocio.validacion("La fecha de fin no es válida (AAAA-MM-DD)."));
        if (i.getYear() != anio || f.getYear() != anio || i.isAfter(f)) {
            throw ErrorNegocio.validacion("El inicio y el fin deben estar dentro del año " + anio + " y el inicio no puede ser posterior al fin.");
        }
        return new LocalDate[] {i, f};
    }

    // ---------- Modalidades de facturación ----------

    @Transactional
    public void crearModalidad(ModalidadEntrada e, UsuarioSesion quien) {
        String codigo = e == null || e.codigo() == null ? "" : e.codigo().strip().toLowerCase(java.util.Locale.ROOT);
        if (!codigo.matches("^[a-z_]{3,30}$")) {
            throw ErrorNegocio.validacion("El código debe tener de 3 a 30 letras minúsculas o «_», sin tildes.");
        }
        String nombre = nombreModalidad(e.nombre());
        int orden = e.orden() == null ? 99 : e.orden();
        int n = jdbc.sql("""
                INSERT INTO presupuesto_modalidades (codigo, nombre, activa, orden) VALUES (:c, :n, :a, :o)
                ON CONFLICT DO NOTHING""").param("c", codigo).param("n", nombre).param("a", e.activa() == null || e.activa()).param("o", orden).update();
        if (n == 0) {
            throw ErrorNegocio.conflicto("Ya existe una modalidad con ese código o ese nombre.");
        }
        bitacora.registrar("presupuesto_modalidad", "Modalidad " + codigo + " creada: «" + nombre + "»", quien.id(), quien.usuario());
    }

    @Transactional
    public void editarModalidad(String codigo, ModalidadEntrada e, UsuarioSesion quien) {
        Map<String, Object> m = jdbc.sql("SELECT nombre, activa, orden FROM presupuesto_modalidades WHERE codigo = :c FOR UPDATE").param("c", codigo).query()
                .listOfRows().stream().findFirst().orElseThrow(() -> ErrorNegocio.noEncontrado("La modalidad no existe."));
        String nombre = e == null || e.nombre() == null ? (String) m.get("nombre") : nombreModalidad(e.nombre());
        boolean activa = e == null || e.activa() == null ? (Boolean) m.get("activa") : e.activa();
        int orden = e == null || e.orden() == null ? ((Number) m.get("orden")).intValue() : e.orden();
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM presupuesto_modalidades WHERE nombre = :n AND codigo <> :c)").param("n", nombre).param("c", codigo)
                .query(Boolean.class).single()) {
            throw ErrorNegocio.conflicto("Ya existe otra modalidad llamada «" + nombre + "».");
        }
        jdbc.sql("UPDATE presupuesto_modalidades SET nombre = :n, activa = :a, orden = :o, actualizado = :ahora WHERE codigo = :c")
                .param("n", nombre).param("a", activa).param("o", orden).param("ahora", OffsetDateTime.now(reloj)).param("c", codigo).update();
        List<String> cambios = new ArrayList<>();
        if (!nombre.equals(m.get("nombre"))) {
            cambios.add("nombre: «" + m.get("nombre") + "» → «" + nombre + "»");
        }
        if (activa != (Boolean) m.get("activa")) {
            cambios.add("activa: «" + m.get("activa") + "» → «" + activa + "»");
        }
        if (orden != ((Number) m.get("orden")).intValue()) {
            cambios.add("orden: «" + m.get("orden") + "» → «" + orden + "»");
        }
        bitacora.registrar("presupuesto_modalidad", "Modalidad " + codigo + (cambios.isEmpty() ? " sin cambios" : ": " + String.join("; ", cambios)),
                quien.id(), quien.usuario());
    }

    private static String nombreModalidad(String n) {
        String s = n == null ? "" : n.strip().replaceAll("\\s+", " ");
        if (s.length() < 3 || s.length() > 60) {
            throw ErrorNegocio.validacion("El nombre de la modalidad debe tener de 3 a 60 caracteres.");
        }
        return s;
    }

    // ---------- Centralizador de los satélites ----------

    @Transactional
    public void centralizador(long sateliteId, Long centralizadorId, UsuarioSesion quien) {
        Lineas.Catalogo c = lineas.catalogo();
        Lineas.Punto s = c.puntos().get(sateliteId);
        if (s == null || !"ESM".equals(s.unidad()) || !s.satelite()) {
            throw ErrorNegocio.validacion("Solo un ESM de modalidad Satélite tiene centralizador.");
        }
        if (centralizadorId != null) {
            Lineas.Punto ce = c.puntos().get(centralizadorId);
            if (ce == null || !ce.activo() || !"ESM".equals(ce.unidad()) || ce.satelite() || ce.id() == s.id()) {
                throw ErrorNegocio.validacion("El centralizador debe ser un ESM activo que no sea satélite (Anexo 3 del OPL §27.2).");
            }
        }
        jdbc.sql("UPDATE puntos SET centralizador_id = :c, actualizado = :ahora WHERE id = :id").param("c", centralizadorId)
                .param("ahora", OffsetDateTime.now(reloj)).param("id", sateliteId).update();
        String antes = s.centralizadorId() == null ? "sin centralizador" : c.puntos().get(s.centralizadorId()).etiqueta();
        String despues = centralizadorId == null ? "sin centralizador" : c.puntos().get(centralizadorId).etiqueta();
        bitacora.registrar("presupuesto_centralizador", "Satélite " + s.etiqueta() + ": «" + antes + "» → «" + despues + "»", quien.id(), quien.usuario());
    }

    // ---------- Opciones de la pantalla ----------

    public Map<String, Object> opciones(Ambito a, boolean puedeRegistrar) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("vigencias", lineas.vigencias().stream().map(v -> Map.of("id", v.id(), "anio", v.anio(), "inicio", v.inicio().toString(), "fin",
                v.fin().toString(), "estado", v.estado())).toList());
        r.put("modalidades", jdbc.sql("SELECT codigo, nombre, activa, orden FROM presupuesto_modalidades ORDER BY orden, codigo").query().listOfRows());
        List<Map<String, Object>> fuerzas = jdbc.sql("SELECT codigo, nombre FROM par_fuerzas WHERE activo AND codigo <> 'NA' ORDER BY orden, codigo")
                .query().listOfRows();
        Object fuerzaAmbito = a.parametros().get("ambito_fuerza");
        r.put("fuerzas", fuerzaAmbito == null ? fuerzas : fuerzas.stream().filter(f -> fuerzaAmbito.equals(f.get("codigo"))).toList());
        Map<String, Object> p = new HashMap<>(a.parametros());
        r.put("puntos", jdbc.sql("""
                SELECT p.id, p.sigla, p.nombre, p.fuerza, p.modalidad, p.centralizador_id AS "centralizadorId"
                FROM puntos p WHERE p.activo AND p.unidad = 'ESM'
                  AND (""" + a.condicion().replace("l.fuerza", "p.fuerza").replace("l.punto_id", "p.id") + """
                       OR (p.fuerza = 'NA' AND CAST(:ambito_na AS boolean)))
                ORDER BY p.nombre""").params(p).param("ambito_na", a.parametros().containsKey("ambito_fuerza")).query().listOfRows());
        r.put("ambito", a.descripcion());
        r.put("puedeRegistrar", puedeRegistrar);
        r.put("parametros", parametrosLista());
        r.put("base", base());
        r.put("tipos", java.util.Arrays.stream(PresupuestoNovedad.Tipo.values()).map(t -> Map.of("codigo", t.codigo(), "nombre", t.nombre())).toList());
        return r;
    }

    // ---------- Líneas con su saldo ----------

    public List<Map<String, Object>> lineas(Filtros f, Ambito a) {
        String base = base();
        Map<String, Object> p = new HashMap<>(a.parametros());
        StringBuilder w = new StringBuilder(" WHERE (" + a.condicion() + ")");
        filtrosLinea(f, w, p);
        List<Map<String, Object>> filas = jdbc.sql("""
                SELECT l.id, v.anio AS vigencia, l.fuerza, l.punto_id AS "puntoId", p.sigla, p.nombre, l.modalidad, m.nombre AS "modalidadNombre",
                       s.asignado, s.facturado, s.reconocido
                FROM presupuesto_lineas l JOIN presupuesto_saldos s ON s.linea_id = l.id JOIN puntos p ON p.id = l.punto_id
                JOIN presupuesto_modalidades m ON m.codigo = l.modalidad JOIN presupuesto_vigencias v ON v.id = l.vigencia_id""" + w
                + " ORDER BY v.anio, l.fuerza, p.nombre, m.orden LIMIT " + (MAX_LINEAS + 1)).params(p).query().listOfRows();
        if (filas.size() > MAX_LINEAS) {
            throw ErrorNegocio.validacion("Hay más de " + MAX_LINEAS + " líneas para estos filtros: filtre por vigencia, fuerza o modalidad.");
        }
        return filas.stream().map(r -> {
            BigDecimal asignado = (BigDecimal) r.get("asignado");
            BigDecimal facturado = (BigDecimal) r.get("facturado");
            BigDecimal reconocido = (BigDecimal) r.get("reconocido");
            BigDecimal ejecutado = Presupuesto.BASE_RECONOCIDO.equals(base) ? reconocido : facturado;
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("asignado", Presupuesto.formato(asignado));
            m.put("facturado", Presupuesto.formato(facturado));
            m.put("reconocido", Presupuesto.formato(reconocido));
            m.put("saldo", Presupuesto.formato(asignado.subtract(ejecutado)));
            m.put("porcentaje", Presupuesto.porcentaje(ejecutado, asignado).map(Presupuesto::formato).orElse(null));
            m.put("semaforo", Presupuesto.semaforo(ejecutado, asignado));
            return m;
        }).toList();
    }

    private static void filtrosLinea(Filtros f, StringBuilder w, Map<String, Object> p) {
        if (f == null) {
            return;
        }
        if (f.vigencia() != null) {
            w.append(" AND l.vigencia_id = :f_vigencia");
            p.put("f_vigencia", f.vigencia());
        }
        if (f.fuerza() != null && !f.fuerza().isBlank()) {
            w.append(" AND l.fuerza = :f_fuerza");
            p.put("f_fuerza", f.fuerza().strip());
        }
        if (f.punto() != null) {
            w.append(" AND l.punto_id = :f_punto");
            p.put("f_punto", f.punto());
        }
        if (f.modalidad() != null && !f.modalidad().isBlank()) {
            w.append(" AND l.modalidad = :f_modalidad");
            p.put("f_modalidad", f.modalidad().strip());
        }
    }

    // ---------- Historial de movimientos ----------

    /** Movimientos con sus líneas dentro del alcance (dos consultas, sin N+1), del más reciente al más antiguo, paginados en el servidor. */
    public Map<String, Object> movimientos(Filtros f, Ambito a) {
        Map<String, Object> p = new HashMap<>(a.parametros());
        StringBuilder w = new StringBuilder(" WHERE (" + a.condicion() + ")");
        filtrosLinea(f, w, p);
        StringBuilder wm = new StringBuilder();
        if (f != null && f.vigencia() != null) {
            wm.append(" AND mv.vigencia_id = :f_vigencia"); // usa ix_presupuesto_movimientos_vigencia
        }
        if (f != null && f.tipo() != null && !f.tipo().isBlank()) {
            if (PresupuestoNovedad.Tipo.deCodigo(f.tipo().strip()).isEmpty()) {
                throw ErrorNegocio.validacion("El tipo de movimiento no es válido.");
            }
            wm.append(" AND mv.tipo = :f_tipo");
            p.put("f_tipo", f.tipo().strip());
        }
        if (f != null && f.desde() != null && !f.desde().isBlank()) {
            LocalDate d = Fechas.valida(f.desde()).orElseThrow(() -> ErrorNegocio.validacion("La fecha «desde» no es válida (AAAA-MM-DD)."));
            wm.append(" AND mv.creado >= :f_desde");
            p.put("f_desde", d.atStartOfDay(Fechas.BOGOTA).toOffsetDateTime());
        }
        if (f != null && f.hasta() != null && !f.hasta().isBlank()) {
            LocalDate h = Fechas.valida(f.hasta()).orElseThrow(() -> ErrorNegocio.validacion("La fecha «hasta» no es válida (AAAA-MM-DD)."));
            wm.append(" AND mv.creado < :f_hasta");
            p.put("f_hasta", h.plusDays(1).atStartOfDay(Fechas.BOGOTA).toOffsetDateTime());
        }
        int pagina = f == null || f.pagina() == null || f.pagina() < 1 ? 1 : Math.min(f.pagina(), 100_000);
        String filtroMov = """
                FROM presupuesto_movimientos mv
                WHERE EXISTS (SELECT 1 FROM presupuesto_movimientos_lineas ml JOIN presupuesto_lineas l ON l.id = ml.linea_id
                              """ + w.toString().replaceFirst(" WHERE ", " WHERE ml.movimiento_id = mv.id AND ") + ")" + wm;
        long total = jdbc.sql("SELECT count(*) " + filtroMov).params(p).query(Long.class).single();
        p.put("limite", POR_PAGINA);
        p.put("desplazamiento", (pagina - 1) * POR_PAGINA);
        List<Map<String, Object>> movs = jdbc.sql("""
                SELECT mv.id, mv.tipo, v.anio AS vigencia, mv.soporte, mv.soporte_id AS "soporteId", mv.lote_id AS "loteId", mv.porcentaje, mv.observacion,
                       mv.neto, mv.creado, u.usuario
                """ + filtroMov.replaceFirst("FROM presupuesto_movimientos mv", """
                FROM presupuesto_movimientos mv JOIN presupuesto_vigencias v ON v.id = mv.vigencia_id JOIN usuarios u ON u.id = mv.registrado_por""")
                + " ORDER BY mv.creado DESC, mv.id DESC LIMIT :limite OFFSET :desplazamiento").params(p).query().listOfRows();
        Map<Long, List<Map<String, Object>>> porMov = new HashMap<>();
        if (!movs.isEmpty()) {
            Map<String, Object> p2 = new HashMap<>(a.parametros());
            p2.put("ids", movs.stream().map(m -> ((Number) m.get("id")).longValue()).toArray(Long[]::new));
            jdbc.sql("""
                    SELECT ml.movimiento_id, l.id AS "lineaId", l.fuerza, p.sigla, p.nombre, m.nombre AS modalidad, ml.valor, ml.asignado_antes, ml.asignado_despues,
                           ml.ejecutado_al_momento
                    FROM presupuesto_movimientos_lineas ml JOIN presupuesto_lineas l ON l.id = ml.linea_id JOIN puntos p ON p.id = l.punto_id
                    JOIN presupuesto_modalidades m ON m.codigo = l.modalidad
                    WHERE ml.movimiento_id = ANY(:ids) AND (""" + a.condicion() + ") ORDER BY ml.movimiento_id, ml.valor, l.id").params(p2).query().listOfRows()
                    .forEach(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("lineaId", r.get("lineaId"));
                        m.put("fuerza", r.get("fuerza"));
                        m.put("esm", r.get("sigla") == null ? r.get("nombre") : r.get("sigla") + " · " + r.get("nombre"));
                        m.put("modalidad", r.get("modalidad"));
                        m.put("valor", Presupuesto.formato((BigDecimal) r.get("valor")));
                        m.put("antes", Presupuesto.formato((BigDecimal) r.get("asignado_antes")));
                        m.put("despues", Presupuesto.formato((BigDecimal) r.get("asignado_despues")));
                        m.put("ejecutado", Presupuesto.formato((BigDecimal) r.get("ejecutado_al_momento")));
                        porMov.computeIfAbsent(((Number) r.get("movimiento_id")).longValue(), k -> new ArrayList<>()).add(m);
                    });
        }
        List<Map<String, Object>> items = movs.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            long id = ((Number) r.get("id")).longValue();
            m.put("id", id);
            m.put("tipo", r.get("tipo"));
            m.put("tipoNombre", PresupuestoNovedad.Tipo.deCodigo((String) r.get("tipo")).map(PresupuestoNovedad.Tipo::nombre).orElse((String) r.get("tipo")));
            m.put("vigencia", r.get("vigencia"));
            m.put("soporte", r.get("soporte"));
            m.put("soporteId", r.get("soporteId"));
            m.put("loteId", r.get("loteId"));
            m.put("porcentaje", r.get("porcentaje") == null ? null : Presupuesto.formato((BigDecimal) r.get("porcentaje")));
            m.put("observacion", r.get("observacion"));
            m.put("neto", Presupuesto.formato((BigDecimal) r.get("neto")));
            m.put("fecha", Lineas.fechaHora(r.get("creado")));
            m.put("usuario", r.get("usuario"));
            m.put("lineas", porMov.getOrDefault(id, List.of()));
            return m;
        }).toList();
        return Map.of("total", total, "pagina", pagina, "porPagina", POR_PAGINA, "items", items);
    }

    // ---------- Soporte de una novedad ----------

    public record ArchivoSoporte(String nombre, String tipo, byte[] contenido) {
    }

    /** El archivo del soporte, si alguno de sus movimientos toca una línea del alcance (fuera del alcance responde como si no existiera). */
    public ArchivoSoporte soporte(long id, Ambito a) {
        Map<String, Object> p = new HashMap<>(a.parametros());
        p.put("id", id);
        Map<String, Object> s = jdbc.sql("""
                SELECT so.nombre_original, so.ruta, so.tipo, so.sha256 FROM presupuesto_soportes so
                WHERE so.id = :id AND EXISTS (SELECT 1 FROM presupuesto_movimientos mv JOIN presupuesto_movimientos_lineas ml ON ml.movimiento_id = mv.id
                                              JOIN presupuesto_lineas l ON l.id = ml.linea_id WHERE mv.soporte_id = so.id AND (""" + a.condicion() + "))")
                .params(p).query().listOfRows().stream().findFirst().orElseThrow(() -> ErrorNegocio.noEncontrado("El soporte no existe."));
        try (InputStream in = almacen.leer((String) s.get("ruta"))) {
            byte[] b = in.readAllBytes();
            if (!co.com.agsamericas.digsa.reglas.Huellas.iguales(co.com.agsamericas.digsa.reglas.Huellas.sha256(b), (String) s.get("sha256"))) {
                LOG.error("[presupuesto] El soporte {} no coincide con su huella", id);
                throw new ErrorNegocio(HttpStatus.CONFLICT, "El soporte no coincide con su huella: avise al administrador.");
            }
            return new ArchivoSoporte((String) s.get("nombre_original"), (String) s.get("tipo"), b);
        } catch (IOException e) {
            LOG.error("[presupuesto] No se pudo leer el soporte {}", id, e);
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo leer el soporte.");
        }
    }
}
