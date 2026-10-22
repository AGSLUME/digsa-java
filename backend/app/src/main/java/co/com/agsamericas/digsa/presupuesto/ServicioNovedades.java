package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.plataforma.ValidadorArchivos;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Presupuesto;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.Clase;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.ErrorFila;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.Fila;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad.Item;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad.Resultado;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad.Tipo;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Novedades presupuestales y asignación inicial (spec «Novedades presupuestales (M7-2)» y «Asignación inicial y carga masiva (M7-1)»).
 * Cada novedad es un movimiento nuevo con el antes y el después de cada línea; los saldos se actualizan en la misma transacción,
 * bloqueados en orden de línea. Nada se sobrescribe.
 */
@Service
public class ServicioNovedades {

    /** Ítem del formulario: el ESM, la modalidad, la fuerza (solo en HOMIL) y el valor tal como se diligenció. */
    public record ItemEntrada(Long puntoId, String modalidad, String fuerza, BigDecimal valor) {
    }

    /** Reajuste por porcentaje: sobre las líneas con presupuesto de una fuerza (o de todo el país si va vacía) y, si se elige, una modalidad. */
    public record Ambito(String fuerza, String modalidad) {
    }

    public record Novedad(Long vigenciaId, String tipo, String soporte, String observacion, BigDecimal porcentaje, Ambito ambito,
                          List<ItemEntrada> items) {
    }

    private static final DateTimeFormatter MARCA = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final Set<String> TIPOS_SOPORTE = Set.of("application/pdf", "image/jpeg", "image/png");
    private static final Set<String> FUENTES = Set.of("digsa", "opl");
    static final int MAX_ITEMS = 500;

    private final JdbcClient jdbc;
    private final Lineas lineas;
    private final Almacen almacen;
    private final ValidadorArchivos validador;
    private final RegistroBitacora bitacora;
    private final Clock reloj;
    private final TransactionTemplate tx;
    private final org.springframework.beans.factory.ObjectProvider<CambioDeSaldos> alCambiar;

    public ServicioNovedades(JdbcClient jdbc, Lineas lineas, Almacen almacen, ValidadorArchivos validador, RegistroBitacora bitacora, Clock reloj,
                             PlatformTransactionManager transacciones,
                             org.springframework.beans.factory.ObjectProvider<CambioDeSaldos> alCambiar) {
        this.jdbc = jdbc;
        this.lineas = lineas;
        this.almacen = almacen;
        this.validador = validador;
        this.bitacora = bitacora;
        this.reloj = reloj;
        this.tx = new TransactionTemplate(transacciones);
        this.alCambiar = alCambiar;
    }

    // ---------- Novedad del formulario ----------

    public Map<String, Object> registrar(Novedad n, MultipartFile archivo, UsuarioSesion quien) {
        Tipo tipo = PresupuestoNovedad.Tipo.deCodigo(n == null ? null : n.tipo())
                .orElseThrow(() -> ErrorNegocio.validacion("Seleccione el tipo de novedad."));
        PresupuestoNovedad.problemaSoporte(n.soporte()).ifPresent(p -> {
            throw ErrorNegocio.validacion(p);
        });
        String observacion = n.observacion() == null || n.observacion().isBlank() ? null : n.observacion().strip();
        if (observacion != null && observacion.length() > 1000) {
            throw ErrorNegocio.validacion("La observación tiene máximo 1000 caracteres.");
        }
        boolean porPorcentaje = tipo == Tipo.REAJUSTE && n.porcentaje() != null;
        if (n.porcentaje() != null && (!porPorcentaje || (n.items() != null && !n.items().isEmpty()))) {
            throw ErrorNegocio.validacion("El porcentaje solo aplica al reajuste por ámbito, sin líneas.");
        }
        if (!porPorcentaje && (n.items() == null || n.items().isEmpty())) {
            throw ErrorNegocio.validacion("La novedad no tiene líneas.");
        }
        if (!porPorcentaje && n.items().size() > MAX_ITEMS) {
            throw ErrorNegocio.validacion("Una novedad tiene máximo " + MAX_ITEMS + " líneas: para más, use la carga por archivo.");
        }
        Soporte soporte = guardarSoporte(archivo, quien);
        try {
            return tx.execute(t -> {
                Lineas.Vigencia v = lineas.abierta(n.vigenciaId());
                Lineas.Catalogo c = lineas.catalogo();
                Map<Long, BigDecimal> cambios;
                if (porPorcentaje) {
                    cambios = cambiosPorPorcentaje(v, n.porcentaje(), n.ambito());
                } else {
                    List<Item> items = new ArrayList<>();
                    List<String> motivos = new ArrayList<>();
                    for (ItemEntrada i : n.items()) {
                        Lineas.Punto p = i == null || i.puntoId() == null ? null : c.puntos().get(i.puntoId());
                        if (p == null) {
                            motivos.add("Seleccione un ESM válido");
                            continue;
                        }
                        Optional<Lineas.Destino> d = Lineas.destino(c, v, p, i.modalidad(), i.fuerza(), Clase.ASIGNACION, motivos);
                        if (d.isEmpty()) {
                            continue;
                        }
                        // Lo que resta, rebalancea o recompone exige una línea que ya exista; la adición y el reajuste positivo la pueden crear
                        boolean resta = tipo == Tipo.DISMINUCION || (tipo == Tipo.REBALANCEO && items.isEmpty())
                                || (i.valor() != null && i.valor().signum() < 0);
                        Optional<Long> id = resta ? lineas.existente(d.get()) : Optional.of(lineas.asegurar(d.get()));
                        if (id.isEmpty()) {
                            motivos.add(d.get().etiqueta() + " no tiene presupuesto en la vigencia " + v.anio());
                            continue;
                        }
                        if (tipo == Tipo.ASIGNACION_INICIAL && tieneMovimientos(id.get())) {
                            motivos.add(d.get().etiqueta() + ": la línea ya tiene presupuesto, use una adición, disminución o reajuste");
                            continue;
                        }
                        items.add(new Item(id.get(), i.valor()));
                    }
                    if (!motivos.isEmpty()) {
                        throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, String.join(". ", motivos) + ".", Map.of("errores", motivos));
                    }
                    Resultado r = PresupuestoNovedad.cambios(tipo, items);
                    if (!r.valido()) {
                        throw ErrorNegocio.validacion(r.problema());
                    }
                    cambios = r.cambios();
                }
                long id = aplicar(v, tipo, n.soporte().strip(), soporte == null ? null : soporte.id(t), null, porPorcentaje ? n.porcentaje() : null,
                        observacion, cambios, quien);
                return Map.<String, Object>of("id", id, "mensaje", tipo.nombre() + " registrada: " + cambios.size() + " línea(s), neto "
                        + Presupuesto.formato(neto(cambios)) + ".");
            });
        } catch (RuntimeException e) {
            if (soporte != null) {
                almacen.descartar(soporte.ruta());
            }
            throw e;
        }
    }

    private Map<Long, BigDecimal> cambiosPorPorcentaje(Lineas.Vigencia v, BigDecimal porcentaje, Ambito a) {
        String fuerza = a == null || a.fuerza() == null || a.fuerza().isBlank() ? null : a.fuerza().strip();
        String modalidad = a == null || a.modalidad() == null || a.modalidad().isBlank() ? null : a.modalidad().strip();
        Map<Long, BigDecimal> asignados = new TreeMap<>();
        List<Long> ambito = jdbc.sql("""
                SELECT l.id FROM presupuesto_lineas l
                WHERE l.vigencia_id = :v AND (CAST(:f AS text) IS NULL OR l.fuerza = :f) AND (CAST(:m AS text) IS NULL OR l.modalidad = :m) ORDER BY l.id""")
                .param("v", v.id()).param("f", fuerza).param("m", modalidad).query(Long.class).list();
        // El reparto se calcula con los saldos ya bloqueados: una adición en paralelo no deja el % sobre un asignado viejo
        lineas.bloquear(ambito).forEach((id, s) -> {
            if (s.asignado().signum() > 0) {
                asignados.put(id, s.asignado());
            }
        });
        Resultado r = PresupuestoNovedad.reajustePorcentaje(asignados, porcentaje);
        if (!r.valido()) {
            throw ErrorNegocio.validacion(r.problema());
        }
        return r.cambios();
    }

    /**
     * Aplica el movimiento: bloquea los saldos, verifica que ninguna línea quede negativa ni bajo lo ejecutado, inserta el movimiento con
     * el antes y el después de cada línea, actualiza los saldos y deja la bitácora. Debe correr dentro de una transacción.
     */
    long aplicar(Lineas.Vigencia v, Tipo tipo, String soporte, Long soporteId, Long loteId, BigDecimal porcentaje, String observacion,
                 Map<Long, BigDecimal> cambios, UsuarioSesion quien) {
        Map<Long, Lineas.SaldoLinea> saldos = lineas.bloquear(cambios.keySet());
        // Con los saldos bloqueados: dos asignaciones iniciales a la vez sobre la misma línea no se suman
        if (tipo == Tipo.ASIGNACION_INICIAL) {
            List<Long> conPresupuesto = jdbc.sql("SELECT DISTINCT linea_id FROM presupuesto_movimientos_lineas WHERE linea_id = ANY(:ids)")
                    .param("ids", cambios.keySet().toArray(Long[]::new)).query(Long.class).list();
            if (!conPresupuesto.isEmpty()) {
                throw ErrorNegocio.conflicto("Otra persona asignó presupuesto a " + saldos.get(conPresupuesto.get(0)).nombre()
                        + " mientras tanto: use una adición, disminución o reajuste.");
            }
        }
        Map<Long, PresupuestoNovedad.Saldo> regla = new HashMap<>();
        saldos.forEach((k, s) -> regla.put(k, s.regla()));
        List<String> problemas = PresupuestoNovedad.problemasDeSaldo(cambios, regla);
        if (!problemas.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, String.join(" ", problemas), Map.of("errores", problemas));
        }
        BigDecimal neto = neto(cambios);
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long id = jdbc.sql("""
                INSERT INTO presupuesto_movimientos (vigencia_id, tipo, soporte, soporte_id, lote_id, porcentaje, observacion, neto, registrado_por, creado)
                VALUES (:v, :t, :s, :sid, :lote, :pct, :obs, :neto, :u, :ahora) RETURNING id""")
                .param("v", v.id()).param("t", tipo.codigo()).param("s", soporte).param("sid", soporteId).param("lote", loteId).param("pct", porcentaje)
                .param("obs", observacion).param("neto", neto).param("u", quien.id()).param("ahora", ahora).query(Long.class).single();
        StringBuilder detalle = new StringBuilder(tipo.codigo() + " · " + cambios.size() + " línea(s) · neto " + Presupuesto.formato(neto) + " · vigencia "
                + v.anio() + " · movimiento " + id);
        List<Long> orden = cambios.keySet().stream().sorted().toList();
        String separador = " · ";
        for (Long linea : orden) {
            Lineas.SaldoLinea s = saldos.get(linea);
            BigDecimal valor = cambios.get(linea);
            BigDecimal despues = s.asignado().add(valor);
            jdbc.sql("""
                    INSERT INTO presupuesto_movimientos_lineas (movimiento_id, vigencia_id, linea_id, valor, asignado_antes, asignado_despues, ejecutado_al_momento)
                    VALUES (:m, :v, :l, :valor, :antes, :despues, :ej)""")
                    .param("m", id).param("v", v.id()).param("l", linea).param("valor", valor).param("antes", s.asignado()).param("despues", despues)
                    .param("ej", s.facturado()).update();
            jdbc.sql("UPDATE presupuesto_saldos SET asignado = :a, version = version + 1, actualizado = :ahora WHERE linea_id = :l")
                    .param("a", despues).param("ahora", ahora).param("l", linea).update();
            detalle.append(separador).append(s.nombre()).append(": «").append(Presupuesto.formato(s.asignado()))
                    .append("» → «").append(Presupuesto.formato(despues)).append("»");
            separador = "; ";
        }
        // En una carga, la bitácora la deja la carga (una fila de 500 caracteres no alcanza para miles de líneas)
        if (loteId == null) {
            bitacora.registrar("presupuesto_movimiento", detalle.toString(), quien.id(), quien.usuario());
        }
        alCambiar.orderedStream().forEach(a -> a.saldosCambiados(v, orden, quien));
        return id;
    }

    private static BigDecimal neto(Map<Long, BigDecimal> cambios) {
        return cambios.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    // ---------- Asignación inicial por archivo (M7-1) ----------

    /** Fila válida resuelta a su línea. */
    record FilaAsignacion(Fila fila, Lineas.Destino destino) {
    }

    record Revision(List<FilaAsignacion> validas, List<ErrorFila> errores, String huella, Map<String, Object> yaAplicado) {
    }

    /** Simular: valida todo y devuelve las filas válidas, los errores por fila y los totales; no escribe nada. */
    public Map<String, Object> simularAsignacion(MultipartFile archivo, UsuarioSesion quien) {
        Revision r = revisar(archivo);
        Map<String, Object> resumen = resumen(r);
        bitacora.registrar("presupuesto_carga_simulada", "Asignación simulada · " + r.validas().size() + " válidas · " + r.errores().size()
                + " con errores", quien.id(), quien.usuario());
        return resumen;
    }

    /** Aplicar: todo o nada; con un solo error no se aplica nada. El mismo archivo no se aplica dos veces. */
    public Map<String, Object> aplicarAsignacion(MultipartFile archivo, String soporteTexto, String fuente, UsuarioSesion quien) {
        PresupuestoNovedad.problemaSoporte(soporteTexto).ifPresent(p -> {
            throw ErrorNegocio.validacion(p);
        });
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
        String nombre = nombreArchivo(archivo);
        try {
            return tx.execute(t -> {
                BigDecimal total = r.validas().stream().map(x -> x.fila().valor()).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
                long lote = jdbc.sql("""
                        INSERT INTO presupuesto_lotes (clase, fuente, sha256, nombre_original, filas, total, aplicado_por, aplicado)
                        VALUES ('asignacion', :f, :h, :n, :filas, :total, :u, :ahora) RETURNING id""")
                        .param("f", f).param("h", r.huella()).param("n", nombre).param("filas", r.validas().size()).param("total", total)
                        .param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
                // Una carga puede traer varias vigencias: un movimiento de asignación inicial por vigencia
                Map<Long, Map<Long, BigDecimal>> porVigencia = new TreeMap<>();
                for (FilaAsignacion fa : r.validas()) {
                    long linea = lineas.asegurar(fa.destino());
                    if (tieneMovimientos(linea)) {
                        throw ErrorNegocio.conflicto("Otra persona asignó presupuesto a " + fa.destino().etiqueta() + " mientras se revisaba el archivo: "
                                + "vuelva a simular.");
                    }
                    porVigencia.computeIfAbsent(fa.destino().vigenciaId(), k -> new LinkedHashMap<>()).put(linea, fa.fila().valor());
                }
                List<Long> movimientos = new ArrayList<>();
                porVigencia.forEach((vid, cambios) -> movimientos.add(aplicar(lineas.abierta(vid), Tipo.ASIGNACION_INICIAL, soporteTexto.strip(), null, lote,
                        null, "Carga de archivo (lote " + lote + ")", cambios, quien)));
                bitacora.registrar("presupuesto_carga_aplicada", "Asignación inicial · lote " + lote + " · " + r.validas().size() + " filas · total "
                        + Presupuesto.formato(total), quien.id(), quien.usuario());
                return Map.<String, Object>of("lote", lote, "movimientos", movimientos, "filas", r.validas().size(), "total", Presupuesto.formato(total),
                        "mensaje", "Carga aplicada: " + r.validas().size() + " líneas, total " + Presupuesto.formato(total) + ".");
            });
        } catch (DuplicateKeyException e) {
            // Dos aplicaciones del mismo archivo a la vez: la segunda choca con el índice único del lote
            return yaAplicado("asignacion", r.huella()).orElseThrow(() -> e);
        }
    }

    private Revision revisar(MultipartFile archivo) {
        byte[] bytes = bytes(archivo);
        String huella = Huellas.sha256(bytes);
        Optional<Map<String, Object>> ya = yaAplicado("asignacion", huella);
        if (ya.isPresent()) {
            return new Revision(List.of(), List.of(), huella, ya.get());
        }
        PresupuestoCarga.Lectura l = PresupuestoCarga.leer(Clase.ASIGNACION, LectorTablas.leer(archivo.getOriginalFilename(), bytes));
        List<ErrorFila> errores = new ArrayList<>(l.errores());
        List<FilaAsignacion> validas = new ArrayList<>();
        Lineas.Catalogo c = lineas.catalogo();
        Map<String, Integer> vistas = new HashMap<>();
        for (Fila f : l.filas()) {
            List<String> motivos = new ArrayList<>();
            Optional<Lineas.Destino> d = Lineas.resolver(c, f, Clase.ASIGNACION, motivos);
            if (d.isPresent()) {
                Integer previa = vistas.putIfAbsent(d.get().clave(), f.numero());
                if (previa != null) {
                    motivos.add("La línea ya viene en la fila " + previa);
                } else if (lineas.existente(d.get()).map(this::tieneMovimientos).orElse(false)) {
                    motivos.add("La línea ya tiene presupuesto: use una adición, disminución o reajuste");
                }
            }
            if (motivos.isEmpty()) {
                validas.add(new FilaAsignacion(f, d.orElseThrow()));
            } else {
                errores.add(new ErrorFila(f.numero(), String.join("; ", motivos) + "."));
            }
        }
        errores.sort(Comparator.comparingInt(ErrorFila::fila));
        return new Revision(validas, errores, huella, null);
    }

    private Map<String, Object> resumen(Revision r) {
        if (r.yaAplicado() != null) {
            return r.yaAplicado();
        }
        Map<String, BigDecimal> porFuerza = new TreeMap<>();
        BigDecimal nacional = BigDecimal.ZERO.setScale(2);
        for (FilaAsignacion f : r.validas()) {
            porFuerza.merge(f.destino().fuerza(), f.fila().valor(), BigDecimal::add);
            nacional = nacional.add(f.fila().valor());
        }
        Map<String, String> fuerzas = new LinkedHashMap<>();
        porFuerza.forEach((k, v) -> fuerzas.put(k, Presupuesto.formato(v)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("validas", r.validas().size());
        m.put("errores", r.errores());
        m.put("totalesPorFuerza", fuerzas);
        m.put("totalNacional", Presupuesto.formato(nacional));
        m.put("filas", r.validas().stream().limit(200).map(f -> Map.of("fila", f.fila().numero(), "linea", f.destino().etiqueta(), "fuerza",
                f.destino().fuerza(), "valor", Presupuesto.formato(f.fila().valor()))).toList());
        m.put("mensaje", r.errores().isEmpty() ? "El archivo está listo para aplicar: " + r.validas().size() + " líneas, total " + Presupuesto.formato(nacional) + "."
                : "Hay " + r.errores().size() + " fila(s) con errores: corríjalas antes de aplicar.");
        return m;
    }

    private boolean tieneMovimientos(long linea) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM presupuesto_movimientos_lineas WHERE linea_id = :l)").param("l", linea).query(Boolean.class).single();
    }

    Optional<Map<String, Object>> yaAplicado(String clase, String huella) {
        return jdbc.sql("SELECT id, aplicado FROM presupuesto_lotes WHERE clase = :c AND sha256 = :h").param("c", clase).param("h", huella).query()
                .listOfRows().stream().findFirst().map(f -> {
                    String fecha = Lineas.fechaHora(f.get("aplicado")).substring(0, 10);
                    return Map.<String, Object>of("yaAplicado", true, "lote", f.get("id"), "mensaje",
                            "Este archivo ya se aplicó el " + fecha + " (lote " + f.get("id") + ").");
                });
    }

    // ---------- Archivos ----------

    static byte[] bytes(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw ErrorNegocio.validacion("Adjunte el archivo (CSV separado por «;» en UTF-8, o Excel .xlsx).");
        }
        if (archivo.getSize() > LectorTablas.MAX_BYTES) {
            throw new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo pesa más de " + LectorTablas.MAX_BYTES / (1024 * 1024) + " MB: divídalo.");
        }
        try {
            return archivo.getBytes();
        } catch (IOException e) {
            throw ErrorNegocio.validacion("No se pudo leer el archivo.");
        }
    }

    static String nombreArchivo(MultipartFile archivo) {
        String n = archivo == null || archivo.getOriginalFilename() == null ? "" : archivo.getOriginalFilename();
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (n.isEmpty()) {
            n = "archivo";
        }
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    /** Soporte de una novedad guardado en el almacén; la fila de la base se crea dentro de la transacción del movimiento. */
    private final class Soporte {
        private final String ruta;
        private final String nombre;
        private final ValidadorArchivos.Valido valido;
        private final UsuarioSesion quien;

        Soporte(String ruta, String nombre, ValidadorArchivos.Valido valido, UsuarioSesion quien) {
            this.ruta = ruta;
            this.nombre = nombre;
            this.valido = valido;
            this.quien = quien;
        }

        String ruta() {
            return ruta;
        }

        long id(org.springframework.transaction.TransactionStatus t) {
            return jdbc.sql("""
                    INSERT INTO presupuesto_soportes (nombre_original, ruta, tipo, tamano, sha256, subido_por, subido)
                    VALUES (:n, :r, :t, :tam, :h, :u, :ahora) RETURNING id""")
                    .param("n", nombre).param("r", ruta).param("t", valido.tipo()).param("tam", (long) valido.contenido().length).param("h", valido.sha256())
                    .param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
        }
    }

    private Soporte guardarSoporte(MultipartFile archivo, UsuarioSesion quien) {
        if (archivo == null || archivo.isEmpty()) {
            return null;
        }
        String nombre = nombreArchivo(archivo);
        if (archivo.getSize() > ValidadorArchivos.MAX_ARCHIVO) {
            throw new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "El soporte pesa más de " + ValidadorArchivos.MAX_ARCHIVO / (1024 * 1024) + " MB.");
        }
        ValidadorArchivos.Valido v;
        try {
            v = validador.validar(nombre, archivo.getBytes());
        } catch (IOException e) {
            throw ErrorNegocio.validacion("No se pudo leer el soporte.");
        }
        if (!TIPOS_SOPORTE.contains(v.tipo())) {
            throw ErrorNegocio.validacion("El soporte debe ser un PDF o una imagen (JPG o PNG).");
        }
        String ruta = "presupuesto/soportes/" + MARCA.format(OffsetDateTime.now(reloj).atZoneSameInstant(Fechas.BOGOTA)) + "_" + Huellas.secreto(8) + "."
                + v.extension();
        try {
            almacen.guardar(ruta, v.contenido());
        } catch (IOException e) {
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar el soporte. Intente de nuevo.");
        }
        return new Soporte(ruta, nombre, v, quien);
    }
}
