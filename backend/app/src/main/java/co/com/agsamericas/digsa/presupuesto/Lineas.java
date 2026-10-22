package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.Clase;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.Fila;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Líneas presupuestales (vigencia × fuerza × ESM × modalidad): resolución de lo que llega en una carga o en el formulario, creación de la
 * línea con su saldo y bloqueo de saldos. Reglas de la spec: solo ESM activos; la fuerza es la del ESM salvo en los de fuerza «NA»
 * (HOMIL), que exigen la de adscripción; los satélites no tienen presupuesto propio y su ejecución va a su centralizador.
 */
@Component
class Lineas {

    static final String SATELITE = "Satélite";
    static final String SIN_FUERZA = "NA";

    record Punto(long id, String sigla, String nombre, String unidad, String fuerza, String modalidad, Long centralizadorId, boolean activo) {
        String etiqueta() {
            return sigla == null || sigla.isBlank() ? nombre : sigla + " · " + nombre;
        }

        boolean satelite() {
            return SATELITE.equals(modalidad);
        }
    }

    record Vigencia(long id, int anio, LocalDate inicio, LocalDate fin, String estado) {
        boolean abierta() {
            return "abierta".equals(estado);
        }

        boolean contiene(YearMonth m) {
            return !m.isBefore(YearMonth.from(inicio)) && !m.isAfter(YearMonth.from(fin));
        }
    }

    record Modalidad(String codigo, String nombre, boolean activa) {
    }

    /** Línea resuelta: a qué línea va y, en la ejecución de un satélite, de qué punto viene. */
    record Destino(long vigenciaId, String fuerza, long puntoId, String modalidad, long puntoOrigenId, String etiqueta) {
        String clave() {
            return vigenciaId + "|" + fuerza + "|" + puntoId + "|" + modalidad;
        }
    }

    /** Saldo bloqueado de una línea con su nombre para los mensajes. */
    record SaldoLinea(long lineaId, String nombre, String fuerza, BigDecimal asignado, BigDecimal facturado, BigDecimal reconocido) {
        PresupuestoNovedad.Saldo regla() {
            return new PresupuestoNovedad.Saldo(nombre, asignado, facturado);
        }
    }

    /** Catálogos cargados una vez por petición (la carga masiva resuelve miles de filas sin consultar la base por cada una). */
    record Catalogo(Map<Long, Punto> puntos, Map<String, List<Punto>> porTexto, Map<String, Modalidad> modalidades, Set<String> fuerzas,
                    Map<Integer, Vigencia> vigencias) {
    }

    private final JdbcClient jdbc;

    Lineas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Catalogo catalogo() {
        Map<Long, Punto> puntos = new LinkedHashMap<>();
        Map<String, List<Punto>> porTexto = new HashMap<>();
        jdbc.sql("SELECT id, sigla, nombre, unidad, fuerza, modalidad, centralizador_id, activo FROM puntos ORDER BY id")
                .query((rs, n) -> new Punto(rs.getLong("id"), rs.getString("sigla"), rs.getString("nombre"), rs.getString("unidad"), rs.getString("fuerza"),
                        rs.getString("modalidad"), (Long) rs.getObject("centralizador_id", Long.class), rs.getBoolean("activo")))
                .list().forEach(p -> {
                    puntos.put(p.id(), p);
                    if (p.sigla() != null && !p.sigla().isBlank()) {
                        porTexto.computeIfAbsent(PresupuestoCarga.normalizar(p.sigla()), k -> new ArrayList<>()).add(p);
                    }
                    porTexto.computeIfAbsent(PresupuestoCarga.normalizar(p.nombre()), k -> new ArrayList<>()).add(p);
                    porTexto.computeIfAbsent(PresupuestoCarga.normalizar(p.etiqueta()), k -> new ArrayList<>()).add(p);
                });
        Map<String, Modalidad> modalidades = new LinkedHashMap<>();
        jdbc.sql("SELECT codigo, nombre, activa FROM presupuesto_modalidades ORDER BY orden, codigo")
                .query((rs, n) -> new Modalidad(rs.getString("codigo"), rs.getString("nombre"), rs.getBoolean("activa"))).list().forEach(m -> {
                    modalidades.put(m.codigo(), m);
                    modalidades.putIfAbsent(PresupuestoCarga.normalizar(m.nombre()), m);
                });
        Set<String> fuerzas = Set.copyOf(jdbc.sql("SELECT codigo FROM par_fuerzas WHERE activo AND codigo <> 'NA'").query(String.class).list());
        Map<Integer, Vigencia> vigencias = vigencias().stream().collect(Collectors.toMap(Vigencia::anio, v -> v));
        return new Catalogo(puntos, porTexto, modalidades, fuerzas, vigencias);
    }

    List<Vigencia> vigencias() {
        return jdbc.sql("SELECT id, anio, inicio, fin, estado FROM presupuesto_vigencias ORDER BY anio")
                .query((rs, n) -> new Vigencia(rs.getLong("id"), rs.getInt("anio"), rs.getObject("inicio", LocalDate.class),
                        rs.getObject("fin", LocalDate.class), rs.getString("estado"))).list();
    }

    Optional<Vigencia> vigencia(long id) {
        return vigencias().stream().filter(v -> v.id() == id).findFirst();
    }

    /** La vigencia que admite escrituras, o 422. */
    Vigencia abierta(Long id) {
        Vigencia v = id == null ? null : vigencia(id).orElse(null);
        if (v == null) {
            throw ErrorNegocio.validacion("Seleccione una vigencia.");
        }
        if (!v.abierta()) {
            throw ErrorNegocio.validacion("La vigencia " + v.anio() + " está cerrada: no admite novedades ni ejecución.");
        }
        return v;
    }

    /** ESM por sigla, nombre o «sigla · nombre». Ambiguo (dos con el mismo nombre) pide la sigla. */
    static Optional<Punto> buscarPunto(Catalogo c, String texto, List<String> motivos) {
        List<Punto> encontrados = c.porTexto().getOrDefault(PresupuestoCarga.normalizar(texto), List.of()).stream().distinct().toList();
        if (encontrados.isEmpty()) {
            motivos.add("El ESM «" + corto(texto) + "» no existe");
            return Optional.empty();
        }
        if (encontrados.size() > 1) {
            motivos.add("Hay varios puntos llamados «" + corto(texto) + "»: use la sigla");
            return Optional.empty();
        }
        return Optional.of(encontrados.get(0));
    }

    /**
     * Valida el destino de una fila o de un ítem del formulario. En la ejecución, un satélite se imputa a su centralizador; en la
     * asignación y la proyección, un satélite se rechaza.
     */
    static Optional<Destino> destino(Catalogo c, Vigencia v, Punto p, String modalidadTexto, String fuerzaTexto, Clase clase, List<String> motivos) {
        if (!p.activo()) {
            motivos.add(p.etiqueta() + " está inactivo");
            return Optional.empty();
        }
        if (!"ESM".equals(p.unidad())) {
            motivos.add("Solo los ESM tienen presupuesto (" + p.etiqueta() + " no es un ESM)");
            return Optional.empty();
        }
        Modalidad m = c.modalidades().get(modalidadTexto == null ? "" : modalidadTexto);
        if (m == null) {
            m = c.modalidades().get(PresupuestoCarga.normalizar(modalidadTexto));
        }
        if (m == null) {
            motivos.add("La modalidad «" + corto(modalidadTexto) + "» no existe");
            return Optional.empty();
        }
        if (!m.activa()) {
            motivos.add("La modalidad " + m.nombre() + " no está activa en el presupuesto");
            return Optional.empty();
        }
        Punto linea = p;
        if (p.satelite()) {
            if (clase != Clase.EJECUCION) {
                motivos.add(p.etiqueta() + " es satélite: su presupuesto está en su ESM centralizador");
                return Optional.empty();
            }
            Punto centralizador = p.centralizadorId() == null ? null : c.puntos().get(p.centralizadorId());
            if (centralizador == null || !centralizador.activo()) {
                motivos.add(p.etiqueta() + " es satélite y no tiene ESM centralizador registrado");
                return Optional.empty();
            }
            linea = centralizador;
        }
        String f = fuerzaTexto == null ? "" : fuerzaTexto.strip().toUpperCase(java.util.Locale.ROOT);
        String fuerza;
        if (SIN_FUERZA.equals(linea.fuerza())) {
            if (!c.fuerzas().contains(f)) {
                motivos.add(linea.etiqueta() + " exige la fuerza de adscripción (" + String.join(", ", c.fuerzas().stream().sorted().toList()) + ")");
                return Optional.empty();
            }
            fuerza = f;
        } else {
            if (!f.isEmpty() && !f.equals(linea.fuerza()) && !f.equals(p.fuerza())) {
                motivos.add("La fuerza " + corto(f) + " no corresponde al ESM " + p.etiqueta() + " (" + p.fuerza() + ")");
                return Optional.empty();
            }
            fuerza = linea.fuerza();
        }
        String etiqueta = linea.etiqueta() + " · " + m.nombre() + (SIN_FUERZA.equals(linea.fuerza()) ? " · " + fuerza : "");
        return Optional.of(new Destino(v.id(), fuerza, linea.id(), m.codigo(), p.id(), etiqueta));
    }

    /** Resuelve una fila de carga: vigencia abierta, periodo dentro de la vigencia y destino. */
    static Optional<Destino> resolver(Catalogo c, Fila f, Clase clase, List<String> motivos) {
        Vigencia v = c.vigencias().get(f.vigencia());
        if (v == null) {
            motivos.add("La vigencia " + f.vigencia() + " no existe: créela primero");
        } else if (!v.abierta()) {
            motivos.add("La vigencia " + f.vigencia() + " está cerrada");
        }
        if (v != null && f.periodo() != null && !v.contiene(f.periodo())) {
            motivos.add("El periodo " + f.periodo() + " está fuera de la vigencia " + v.anio());
        }
        Optional<Punto> p = buscarPunto(c, f.esm(), motivos);
        if (v == null || !v.abierta() || p.isEmpty() || !motivos.isEmpty()) {
            return Optional.empty();
        }
        return destino(c, v, p.get(), f.modalidad(), f.fuerza(), clase, motivos);
    }

    /** Id de la línea (la crea con su saldo en 0 si no existe). */
    long asegurar(Destino d) {
        jdbc.sql("""
                INSERT INTO presupuesto_lineas (vigencia_id, fuerza, punto_id, modalidad) VALUES (:v, :f, :p, :m)
                ON CONFLICT (vigencia_id, punto_id, modalidad, fuerza) DO NOTHING""")
                .param("v", d.vigenciaId()).param("f", d.fuerza()).param("p", d.puntoId()).param("m", d.modalidad()).update();
        long id = jdbc.sql("SELECT id FROM presupuesto_lineas WHERE vigencia_id = :v AND fuerza = :f AND punto_id = :p AND modalidad = :m")
                .param("v", d.vigenciaId()).param("f", d.fuerza()).param("p", d.puntoId()).param("m", d.modalidad()).query(Long.class).single();
        jdbc.sql("INSERT INTO presupuesto_saldos (linea_id) VALUES (:id) ON CONFLICT (linea_id) DO NOTHING").param("id", id).update();
        return id;
    }

    /** Id de la línea si existe (sin crearla). */
    Optional<Long> existente(Destino d) {
        return jdbc.sql("SELECT id FROM presupuesto_lineas WHERE vigencia_id = :v AND fuerza = :f AND punto_id = :p AND modalidad = :m")
                .param("v", d.vigenciaId()).param("f", d.fuerza()).param("p", d.puntoId()).param("m", d.modalidad()).query(Long.class).optional();
    }

    /** Bloquea los saldos en orden de linea_id (dos novedades a la vez sobre las mismas líneas no se cruzan) y los devuelve por línea. */
    Map<Long, SaldoLinea> bloquear(Collection<Long> lineas) {
        Map<Long, SaldoLinea> r = new LinkedHashMap<>();
        if (lineas.isEmpty()) {
            return r;
        }
        jdbc.sql("""
                SELECT s.linea_id, s.asignado, s.facturado, s.reconocido, l.fuerza,
                       coalesce(nullif(p.sigla, '') || ' · ', '') || p.nombre || ' · ' || m.nombre
                         || CASE WHEN p.fuerza = 'NA' THEN ' · ' || l.fuerza ELSE '' END AS nombre
                FROM presupuesto_saldos s JOIN presupuesto_lineas l ON l.id = s.linea_id JOIN puntos p ON p.id = l.punto_id
                JOIN presupuesto_modalidades m ON m.codigo = l.modalidad
                WHERE s.linea_id = ANY(:ids) ORDER BY s.linea_id FOR UPDATE OF s""")
                .param("ids", lineas.stream().distinct().sorted().toArray(Long[]::new))
                .query((rs, n) -> new SaldoLinea(rs.getLong("linea_id"), rs.getString("nombre"), rs.getString("fuerza"), rs.getBigDecimal("asignado"),
                        rs.getBigDecimal("facturado"), rs.getBigDecimal("reconocido")))
                .list().forEach(s -> r.put(s.lineaId(), s));
        return r;
    }

    /** «AAAA-MM-DD HH:MM:SS» en Bogotá de un timestamptz leído con listOfRows (llega como java.sql.Timestamp). */
    static String fechaHora(Object valor) {
        java.time.Instant i = switch (valor) {
            case java.sql.Timestamp t -> t.toInstant();
            case java.time.OffsetDateTime o -> o.toInstant();
            case java.time.Instant x -> x;
            default -> throw new IllegalArgumentException("No es una fecha: " + valor);
        };
        return i.atZone(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA).toLocalDateTime().withNano(0).toString().replace('T', ' ');
    }

    static String corto(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}
