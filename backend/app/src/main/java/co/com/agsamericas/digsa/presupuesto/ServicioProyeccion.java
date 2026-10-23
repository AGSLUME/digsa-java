package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Presupuesto;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.Clase;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.ErrorFila;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.Fila;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
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
 * Proyección de consumo cargada por archivo (spec «Proyección de consumo mensual»): por vigencia, fuerza, ESM, modalidad y mes. Simular
 * y aplicar como las demás cargas (todo o nada, idempotente por huella). Una proyección nueva del mismo mes reemplaza a la vigente sin
 * borrarla. Si lo cargado de una línea no suma su asignado, se advierte sin rechazar. Las líneas sin proyección cargada usan la lineal.
 */
@Service
public class ServicioProyeccion {

    private static final Set<String> FUENTES = Set.of("digsa", "opl");

    record Valida(Lineas.Destino destino, YearMonth periodo, BigDecimal valor, int fila) {
        String clave() {
            return destino.clave() + "|" + periodo;
        }
    }

    record Revision(List<Valida> validas, List<ErrorFila> errores, String huella, Map<String, Object> yaAplicado) {
    }

    private final JdbcClient jdbc;
    private final Lineas lineas;
    private final ServicioNovedades novedades;
    private final RegistroBitacora bitacora;
    private final Clock reloj;
    private final TransactionTemplate tx;

    public ServicioProyeccion(JdbcClient jdbc, Lineas lineas, ServicioNovedades novedades, RegistroBitacora bitacora, Clock reloj,
                              PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.lineas = lineas;
        this.novedades = novedades;
        this.bitacora = bitacora;
        this.reloj = reloj;
        this.tx = new TransactionTemplate(transacciones);
    }

    public Map<String, Object> simular(MultipartFile archivo, UsuarioSesion quien) {
        Revision r = revisar(archivo);
        if (r.yaAplicado() != null) {
            return r.yaAplicado();
        }
        bitacora.registrar("presupuesto_carga_simulada", "Proyección simulada · " + r.validas().size() + " válidas · " + r.errores().size() + " con errores",
                quien.id(), quien.usuario());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("validas", r.validas().size());
        m.put("errores", r.errores());
        m.put("advertencias", advertencias(r.validas()));
        m.put("mensaje", r.errores().isEmpty() ? "El archivo está listo para aplicar: " + r.validas().size() + " meses de proyección."
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
                lineas.serializar(r.validas().stream().map(x -> x.destino().vigenciaId()).toList());
                OffsetDateTime ahora = OffsetDateTime.now(reloj);
                BigDecimal total = r.validas().stream().map(Valida::valor).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
                long lote = jdbc.sql("""
                        INSERT INTO presupuesto_lotes (clase, fuente, sha256, nombre_original, filas, total, aplicado_por, aplicado)
                        VALUES ('proyeccion', :f, :h, :n, :filas, :total, :u, :ahora) RETURNING id""")
                        .param("f", f).param("h", r.huella()).param("n", ServicioNovedades.nombreArchivo(archivo)).param("filas", r.validas().size())
                        .param("total", total).param("u", quien.id()).param("ahora", ahora).query(Long.class).single();
                Map<String, Long> porClave = new TreeMap<>();
                r.validas().stream().map(Valida::destino).sorted(Comparator.comparing(Lineas.Destino::clave))
                        .forEach(d -> porClave.computeIfAbsent(d.clave(), k -> lineas.asegurar(d)));
                for (Valida v : r.validas()) {
                    long linea = porClave.get(v.destino().clave());
                    jdbc.sql("UPDATE presupuesto_proyeccion SET vigente = false, reemplazada_en = :ahora WHERE linea_id = :l AND periodo = :p AND vigente")
                            .param("ahora", ahora).param("l", linea).param("p", v.periodo().atDay(1)).update();
                    jdbc.sql("INSERT INTO presupuesto_proyeccion (linea_id, periodo, valor, lote_id, creado) VALUES (:l, :p, :v, :lote, :ahora)")
                            .param("l", linea).param("p", v.periodo().atDay(1)).param("v", v.valor()).param("lote", lote).param("ahora", ahora).update();
                }
                bitacora.registrar("presupuesto_proyeccion_carga", "Proyección · lote " + lote + " · " + r.validas().size() + " meses · total "
                        + Presupuesto.formato(total), quien.id(), quien.usuario());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("lote", lote);
                m.put("advertencias", advertencias(r.validas()));
                m.put("mensaje", "Proyección aplicada: " + r.validas().size() + " meses, total " + Presupuesto.formato(total) + ".");
                return m;
            });
        } catch (DuplicateKeyException e) {
            return novedades.yaAplicado("proyeccion", r.huella()).orElseThrow(() -> e);
        }
    }

    private Revision revisar(MultipartFile archivo) {
        byte[] bytes = ServicioNovedades.bytes(archivo);
        String huella = Huellas.sha256(bytes);
        Optional<Map<String, Object>> ya = novedades.yaAplicado("proyeccion", huella);
        if (ya.isPresent()) {
            return new Revision(List.of(), List.of(), huella, ya.get());
        }
        PresupuestoCarga.Lectura l = PresupuestoCarga.leer(Clase.PROYECCION, LectorTablas.leer(archivo.getOriginalFilename(), bytes));
        List<ErrorFila> errores = new ArrayList<>(l.errores());
        List<Valida> validas = new ArrayList<>();
        Lineas.Catalogo c = lineas.catalogo();
        Map<String, Integer> vistas = new HashMap<>();
        for (Fila f : l.filas()) {
            List<String> motivos = new ArrayList<>();
            Optional<Lineas.Destino> d = Lineas.resolver(c, f, Clase.PROYECCION, motivos);
            if (d.isPresent()) {
                Valida v = new Valida(d.get(), f.periodo(), f.valor(), f.numero());
                Integer previa = vistas.putIfAbsent(v.clave(), f.numero());
                if (previa != null) {
                    motivos.add("El mismo ESM, modalidad y mes ya viene en la fila " + previa);
                } else {
                    validas.add(v);
                }
            }
            if (!motivos.isEmpty()) {
                errores.add(new ErrorFila(f.numero(), String.join("; ", motivos) + "."));
            }
        }
        errores.sort(Comparator.comparingInt(ErrorFila::fila));
        return new Revision(validas, errores, huella, null);
    }

    /**
     * «La proyección suma X y el asignado es Y» por línea cuya proyección quedaría sin cuadrar con su asignado: lo del archivo más lo
     * vigente de los meses que el archivo no trae (los meses sin valor cuentan 0 en la alarma de desviación).
     */
    private List<String> advertencias(List<Valida> validas) {
        Map<String, BigDecimal> suma = new TreeMap<>();
        Map<String, Lineas.Destino> destino = new HashMap<>();
        Map<String, List<java.time.LocalDate>> meses = new HashMap<>();
        validas.forEach(v -> {
            suma.merge(v.destino().clave(), v.valor(), BigDecimal::add);
            destino.putIfAbsent(v.destino().clave(), v.destino());
            meses.computeIfAbsent(v.destino().clave(), k -> new ArrayList<>()).add(v.periodo().atDay(1));
        });
        List<String> r = new ArrayList<>();
        suma.forEach((k, enArchivo) -> {
            Lineas.Destino d = destino.get(k);
            Optional<Long> linea = lineas.existente(d);
            BigDecimal asignado = linea.flatMap(id -> jdbc.sql("SELECT asignado FROM presupuesto_saldos WHERE linea_id = :l").param("l", id)
                    .query(BigDecimal.class).optional()).orElse(BigDecimal.ZERO);
            BigDecimal otros = linea.map(id -> jdbc.sql("""
                    SELECT coalesce(sum(valor), 0) FROM presupuesto_proyeccion WHERE linea_id = :l AND vigente AND periodo <> ALL(CAST(:meses AS date[]))""")
                    .param("l", id).param("meses", meses.get(k).stream().map(java.time.LocalDate::toString).toArray(String[]::new)).query(BigDecimal.class).single()).orElse(BigDecimal.ZERO);
            BigDecimal total = enArchivo.add(otros);
            if (total.compareTo(asignado) != 0) {
                r.add(d.etiqueta() + ": la proyección suma " + Presupuesto.formato(total) + " y el asignado es " + Presupuesto.formato(asignado) + ".");
            }
        });
        return r.size() > 200 ? r.subList(0, 200) : r;
    }
}
