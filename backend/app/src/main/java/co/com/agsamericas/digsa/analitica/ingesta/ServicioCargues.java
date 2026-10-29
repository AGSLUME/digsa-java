package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.analitica.PropiedadesAnalitica;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Campo;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cargues por archivo (analitica-ingesta): crear, recibir las partes (tamaño exacto, al almacén; no se toca el multipart global),
 * cerrar (ensamblar y comparar el SHA-256), proponer el mapeo con vista previa enmascarada, confirmarlo (con plantilla reutilizable y,
 * para reemplazar, un periodo declarado) y dejar el cargue en la cola; o descartarlo. Además la bitácora de cargues, los rechazos en
 * CSV (línea y causa, sin celdas, en flujo), el original (solo ADMINISTRADOR). Idempotente: un archivo cuyo SHA-256 ya está aplicado
 * devuelve ese cargue; si se descubre al cerrar, el cargue nuevo queda «duplicado» (nunca se borra: es bitácora).
 */
@Service
class ServicioCargues {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioCargues.class);
    static final String MENSAJE_HUELLA = "El archivo llegó incompleto o alterado; vuelva a subirlo";
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{64}$");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Set<String> ESTADOS = Set.of("subiendo", "mapeo", "pendiente", "procesando", "aplicado", "rechazado", "fallido",
            "duplicado", "vencido", "descartado");
    static final int FILAS_VISTA_PREVIA = 20;
    static final int TAMANO_PAGINA = 50;

    record Solicitud(String nombre, Long tamano, String sha256, Integer partes) {
    }

    record Confirmacion(List<String> columnas, String modo, Boolean guardarPlantilla, String nombrePlantilla, LocalDate periodoDesde,
                        LocalDate periodoHasta) {
    }

    record Filtros(String fuente, String estado, LocalDate desde, LocalDate hasta, Integer pagina) {
    }

    record Descarga(String nombre, long tamano, InputStream contenido) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate flujo;
    private final Almacen almacen;
    private final Seudonimizador seudo;
    private final ProcesadorCargues procesador;
    private final MantenimientoCargues mantenimiento;
    private final RegistroBitacora bitacora;
    private final PropiedadesAnalitica propiedades;
    private final ObjectMapper json;
    private final Clock reloj;
    private final TransactionTemplate lectura;
    private final PrimerasFilas primeras = new PrimerasFilas();

    ServicioCargues(JdbcClient jdbc, DataSource ds, Almacen almacen, Seudonimizador seudo, ProcesadorCargues procesador,
                    MantenimientoCargues mantenimiento, RegistroBitacora bitacora, PropiedadesAnalitica propiedades, ObjectMapper json, Clock reloj,
                    PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.flujo = new JdbcTemplate(ds);
        this.flujo.setFetchSize(1000); // con la transacción abierta el driver trae de a 1000 filas (cursor): memoria constante
        this.almacen = almacen;
        this.seudo = seudo;
        this.procesador = procesador;
        this.mantenimiento = mantenimiento;
        this.bitacora = bitacora;
        this.propiedades = propiedades;
        this.json = json;
        this.reloj = reloj;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
    }

    private ArchivoDispensacion.Limites limites() {
        return new ArchivoDispensacion.Limites(propiedades.celdaMax(), propiedades.registroMax(), propiedades.textosXlsxMaxBytes());
    }

    // ---------- Subida por partes ----------

    /**
     * Crea el cargue («subiendo»). Si el SHA-256 declarado ya está aplicado devuelve ese cargue sin crear otro. Exige la clave del
     * seudónimo, el número exacto de partes (ceil(tamaño / parte máxima)) y como máximo {@code cargues-abiertos-max} cargues abiertos.
     */
    Map<String, Object> crear(Solicitud s, UsuarioSesion quien) {
        seudo.exigir();
        String nombre = s.nombre() == null ? "" : s.nombre().strip();
        if (nombre.isEmpty() || nombre.length() > 255 || nombre.contains("/") || nombre.contains("\\")) {
            throw ErrorNegocio.validacion("Indique el nombre del archivo.");
        }
        String formato = AnaliticaIngesta.formatoDe(nombre)
                .orElseThrow(() -> ErrorNegocio.validacion("Formato no aceptado: use CSV o TXT (UTF-8), xlsx, o esos mismos comprimidos en .gz o .zip."));
        String sha = s.sha256() == null ? "" : s.sha256().strip().toLowerCase(Locale.ROOT);
        if (!SHA.matcher(sha).matches()) {
            throw ErrorNegocio.validacion("Falta el SHA-256 del archivo.");
        }
        long tamano = s.tamano() == null ? -1 : s.tamano();
        if (tamano <= 0) {
            throw ErrorNegocio.validacion("El archivo está vacío.");
        }
        if (tamano > propiedades.archivoMaxBytes()) {
            throw ErrorNegocio.validacion("El archivo supera el tamaño máximo (" + propiedades.archivoMaxBytes() / (1024 * 1024) + " MB).");
        }
        int partes = s.partes() == null ? 0 : s.partes();
        long esperadas = AnaliticaIngesta.partesEsperadas(tamano, propiedades.parteMaxBytes());
        if (partes != esperadas || partes > 10_000) {
            throw ErrorNegocio.validacion("Número de partes no válido: son " + esperadas + " partes de " + propiedades.parteMaxBytes() / (1024 * 1024)
                    + " MB (la última con el resto).");
        }
        Map<String, Object> existente = existenteAplicado(sha);
        if (existente != null) {
            return existente;
        }
        long abiertos = jdbc.sql("SELECT count(*) FROM ana_cargues WHERE usuario_id = :u AND estado IN ('subiendo', 'mapeo')").param("u", quien.id())
                .query(Long.class).single();
        if (abiertos >= propiedades.maxCarguesAbiertos()) {
            throw ErrorNegocio.conflicto("Tiene " + abiertos + " cargues sin terminar (subida o mapeo). Termínelos o descártelos antes de subir otro.");
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long id = jdbc.sql("""
                INSERT INTO ana_cargues (fuente, fuente_texto, nombre_archivo, formato, tamano_bytes, partes, sha256_declarado, estado, etapa,
                                         usuario_id, responsable_texto, creado, actividad, conservar_hasta)
                VALUES ('archivo', :fuenteTexto, :nombre, :formato, :tamano, :partes, :sha, 'subiendo', 'subida', :u, :resp, :ahora, :ahora, :vence)
                RETURNING id""")
                .param("fuenteTexto", AnaliticaIngesta.fuenteTexto(formato, ';')).param("nombre", nombre).param("formato", formato)
                .param("tamano", tamano).param("partes", partes).param("sha", sha).param("u", quien.id())
                .param("resp", AnaliticaIngesta.responsable(quien.usuario(), UsuarioSesion.perfilesActuales(), false))
                .param("ahora", ahora).param("vence", ahora.plusDays(propiedades.diasConservar())).query(Long.class).single();
        bitacora.registrar("analitica_cargue_creado", "Cargue " + id + " · archivo " + formato + " · " + tamano + " bytes en " + partes + " partes",
                quien.id(), quien.usuario());
        return detalle(id);
    }

    private Map<String, Object> existenteAplicado(String sha) {
        return jdbc.sql("SELECT id, aplicado_en FROM ana_cargues WHERE sha256 = :sha AND estado = 'aplicado' AND reemplazado_por IS NULL")
                .param("sha", sha).query((rs, n) -> {
                    Map<String, Object> m = detalle(rs.getLong(1));
                    m.put("existente", true);
                    m.put("mensaje", "Este archivo ya se cargó el " + fechaHora(rs.getObject(2, OffsetDateTime.class)));
                    return m;
                }).optional().orElse(null);
    }

    record Cargue(long id, String estado, String fuente, String nombre, String formato, Long tamano, Integer partes, String shaDeclarado,
                  String ruta, Long usuarioId, OffsetDateTime conservarHasta) {
    }

    private Cargue cargue(long id) {
        return jdbc.sql("""
                SELECT id, estado, fuente, nombre_archivo, formato, tamano_bytes, partes, sha256_declarado, almacen_ruta, usuario_id, conservar_hasta
                FROM ana_cargues WHERE id = :id""").param("id", id)
                .query((rs, n) -> new Cargue(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        (Long) rs.getObject(6), (Integer) rs.getObject(7), strip(rs.getString(8)), rs.getString(9), (Long) rs.getObject(10),
                        rs.getObject(11, OffsetDateTime.class)))
                .optional().orElseThrow(() -> ErrorNegocio.noEncontrado("El cargue no existe."));
    }

    private static boolean esAdministrador() {
        return UsuarioSesion.perfilesActuales().contains(Perfil.ADMINISTRADOR);
    }

    /** Cargue propio (o cualquiera para el administrador) en el estado esperado. */
    private Cargue propio(long id, UsuarioSesion quien, String estado) {
        Cargue c = cargue(id);
        if (!"archivo".equals(c.fuente()) || !esAdministrador() && (c.usuarioId() == null || c.usuarioId() != quien.id())) {
            throw ErrorNegocio.noEncontrado("El cargue no existe.");
        }
        if (estado != null && !estado.equals(c.estado())) {
            throw ErrorNegocio.conflicto("El cargue ya no está en la etapa «" + estado + "» (está «" + c.estado() + "»).");
        }
        return c;
    }

    private void actividad(long id) {
        jdbc.sql("UPDATE ana_cargues SET actividad = :ahora WHERE id = :id AND estado IN ('subiendo', 'mapeo')").param("ahora", OffsetDateTime.now(reloj))
                .param("id", id).update();
    }

    /**
     * Recibe una parte en flujo y la deja en el almacén con su SHA-256. Su tamaño debe ser exacto: la parte máxima, salvo la última, que
     * lleva el resto (así nunca se escribe en el almacén más que el archivo declarado). Una parte repetida reemplaza la anterior.
     */
    Map<String, Object> recibirParte(long id, int numero, InputStream cuerpo, UsuarioSesion quien) {
        Cargue c = propio(id, quien, "subiendo");
        if (numero < 1 || numero > c.partes()) {
            throw ErrorNegocio.validacion("Parte fuera de rango (1 a " + c.partes() + ").");
        }
        long esperado = AnaliticaIngesta.tamanoParte(numero, c.partes(), c.tamano(), propiedades.parteMaxBytes());
        String ruta = "analitica/cargues/" + id + "/parte_" + numero + "_" + Huellas.secreto(6) + ".bin";
        long tamano = 0;
        MessageDigest md = sha256();
        try (OutputStream out = almacen.escribir(ruta); DigestOutputStream dout = new DigestOutputStream(out, md)) {
            byte[] buf = new byte[1 << 16];
            for (int n = cuerpo.read(buf); n >= 0; n = cuerpo.read(buf)) {
                tamano += n;
                if (tamano > esperado) {
                    throw ErrorNegocio.validacion("La parte " + numero + " debe tener " + esperado + " bytes.");
                }
                dout.write(buf, 0, n);
            }
            if (tamano != esperado) {
                throw ErrorNegocio.validacion("La parte " + numero + " debe tener " + esperado + " bytes (llegaron " + tamano + ").");
            }
        } catch (ErrorNegocio e) {
            almacen.descartar(ruta);
            throw e;
        } catch (IOException e) {
            almacen.descartar(ruta);
            LOG.warn("[analitica] La parte {} del cargue {} no llegó completa: {}", numero, id, e.getMessage());
            throw ErrorNegocio.validacion("La parte no llegó completa; vuelva a enviarla.");
        }
        String huella = HexFormat.of().formatHex(md.digest());
        String anterior = jdbc.sql("SELECT almacen_ruta FROM ana_cargue_partes WHERE cargue_id = :c AND numero = :n").param("c", id).param("n", numero)
                .query(String.class).optional().orElse(null);
        // Solo si el cargue sigue subiendo (la tarea de vencidos o un descarte pudieron cerrarlo mientras llegaba la parte)
        int n = jdbc.sql("""
                INSERT INTO ana_cargue_partes (cargue_id, numero, tamano, sha256, almacen_ruta, conservar_hasta, recibido)
                SELECT :c, :n, :t, :sha, :ruta, coalesce(k.conservar_hasta, :vence), :ahora FROM ana_cargues k WHERE k.id = :c AND k.estado = 'subiendo'
                ON CONFLICT (cargue_id, numero) DO UPDATE SET tamano = EXCLUDED.tamano, sha256 = EXCLUDED.sha256, almacen_ruta = EXCLUDED.almacen_ruta,
                       recibido = EXCLUDED.recibido""")
                .param("c", id).param("n", numero).param("t", tamano).param("sha", huella).param("ruta", ruta)
                .param("vence", OffsetDateTime.now(reloj).plusDays(propiedades.diasConservar())).param("ahora", OffsetDateTime.now(reloj)).update();
        if (n != 1) {
            almacen.descartar(ruta);
            throw ErrorNegocio.conflicto("El cargue ya no está recibiendo partes.");
        }
        if (anterior != null) {
            almacen.descartar(anterior);
        }
        actividad(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("numero", numero);
        m.put("tamano", tamano);
        m.put("sha256", huella);
        return m;
    }

    /**
     * Cierra la subida: ensambla las partes en el original, calcula su SHA-256 y lo compara con el declarado. Si falta una parte o la
     * huella no coincide el cargue queda «fallido» (422). Si el archivo ya está aplicado este queda «duplicado» con la referencia y se
     * devuelve el existente. Si no, lee el encabezado (rechazo de estructura si está vacío o con encabezados repetidos; «Columna n» si
     * la primera fila no se reconoce) y pasa a «mapeo».
     */
    Map<String, Object> cerrar(long id, UsuarioSesion quien) {
        Cargue c = propio(id, quien, "subiendo");
        List<String[]> partes = jdbc.sql("SELECT numero, almacen_ruta FROM ana_cargue_partes WHERE cargue_id = :c ORDER BY numero").param("c", id)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list();
        String ruta = "analitica/cargues/" + id + "/original_" + Huellas.secreto(8) + "." + c.formato();
        long tamano = 0;
        if (partes.size() != c.partes()) {
            fallarHuella(c, null, quien);
        }
        MessageDigest md = sha256();
        try (OutputStream out = almacen.escribir(ruta); DigestOutputStream dout = new DigestOutputStream(out, md)) {
            for (String[] p : partes) {
                try (InputStream in = almacen.leer(p[1])) {
                    tamano += in.transferTo(dout);
                }
            }
        } catch (IOException e) {
            LOG.error("[analitica] No se pudo ensamblar el cargue {}", id, e);
            fallarHuella(c, ruta, quien);
        }
        String sha = HexFormat.of().formatHex(md.digest());
        if (!sha.equals(c.shaDeclarado()) || c.tamano() == null || tamano != c.tamano()) {
            fallarHuella(c, ruta, quien);
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        int n = jdbc.sql("""
                UPDATE ana_cargues SET sha256 = :sha, tamano_bytes = :t, almacen_ruta = :ruta, recibido = :ahora, actividad = :ahora
                WHERE id = :id AND estado = 'subiendo' AND almacen_ruta IS NULL""")
                .param("sha", sha).param("t", tamano).param("ruta", ruta).param("ahora", ahora).param("id", id).update();
        if (n != 1) {
            almacen.descartar(ruta);
            throw ErrorNegocio.conflicto("El cargue ya se está cerrando o ya no está en subida.");
        }
        mantenimiento.descartarPartes(id);

        Map<String, Object> existente = existenteAplicado(sha);
        if (existente != null) {
            long otro = ((Number) existente.get("id")).longValue();
            jdbc.sql("""
                    UPDATE ana_cargues SET estado = 'duplicado', duplicado_de = :d, etapa = NULL, terminado = :ahora,
                           motivo_texto = :motivo WHERE id = :id AND estado = 'subiendo'""")
                    .param("d", otro).param("ahora", ahora).param("motivo", "Duplicado de #" + otro + ": el mismo archivo ya se aplicó en ese cargue")
                    .param("id", id).update();
            bitacora.registrar("analitica_cargue_duplicado", "Cargue " + id + " · duplicado de #" + otro, quien.id(), quien.usuario());
            mantenimiento.borrarOriginal(id, ruta, "duplicado");
            return existente;
        }
        try (ArchivoDispensacion a = ArchivoDispensacion.abrir(() -> almacen.leer(ruta), c.nombre(), propiedades.archivoMaxBytes(), limites(), null)) {
            List<String> leidos = a.encabezado();
            boolean reconocido = AnaliticaIngesta.encabezadoReconocido(leidos);
            // Una primera fila sin ningún encabezado reconocible puede ser de datos: no se guarda (ni en el cargue ni en la plantilla)
            List<String> encabezados = reconocido ? leidos : AnaliticaIngesta.encabezadosGenericos(leidos.size());
            List<Integer> repetidos = reconocido ? AnaliticaIngesta.posicionesRepetidas(leidos) : List.of();
            if (!repetidos.isEmpty()) {
                throw new ErrorEstructura(AnaliticaIngesta.Causa.ENCABEZADOS_REPETIDOS, AnaliticaIngesta.motivoRepetidos(repetidos), "1", 0);
            }
            primeras.guardar(id, leerPrimeras(a));
            int m = jdbc.sql("""
                    UPDATE ana_cargues SET estado = 'mapeo', etapa = NULL, encabezados = CAST(:enc AS jsonb), delimitador = :d, fuente_texto = :f,
                           avisos = CASE WHEN :reconocido THEN avisos
                                         ELSE avisos || jsonb_build_array('La primera fila no trae encabezados reconocibles: se tomó como encabezado y no se guarda') END
                    WHERE id = :id AND estado = 'subiendo'""")
                    .param("enc", aJson(encabezados)).param("d", "xlsx".equals(c.formato()) ? null : String.valueOf(a.delimitador()))
                    .param("f", a.fuenteTexto()).param("reconocido", reconocido).param("id", id).update();
            if (m != 1) {
                throw ErrorNegocio.conflicto("El cargue ya no está en subida.");
            }
        } catch (ErrorEstructura e) {
            int m = jdbc.sql("""
                    UPDATE ana_cargues SET estado = 'rechazado', terminado = :ahora, recibidos = 0, procesados = 0, rechazados = 0,
                           motivos = CAST(:m AS jsonb), motivo_texto = :texto WHERE id = :id AND estado = 'subiendo'""")
                    .param("ahora", ahora).param("m", aJson(Map.of(e.causa().codigo(), 0))).param("texto", ProcesadorCargues.cortar(e.getMessage(), 2000))
                    .param("id", id).update();
            if (m == 1) {
                jdbc.sql("INSERT INTO ana_cargue_rechazos (cargue_id, orden, linea, causa) VALUES (:c, 0, :l, :causa)").param("c", id)
                        .param("l", e.linea() == null ? "1" : e.linea()).param("causa", e.causa().codigo()).update();
                bitacora.registrar("analitica_cargue_rechazado", "Cargue " + id + " · rechazo de estructura: " + e.causa().texto(), quien.id(),
                        quien.usuario());
                mantenimiento.borrarOriginal(id, ruta, "rechazo de estructura");
            }
            throw ErrorNegocio.validacion(e.getMessage());
        } catch (IOException e) {
            LOG.error("[analitica] No se pudo leer el encabezado del cargue {}", id, e);
            int m = jdbc.sql("""
                    UPDATE ana_cargues SET estado = 'fallido', error = :e, terminado = :ahora WHERE id = :id AND estado = 'subiendo'""")
                    .param("e", "No se pudo leer el archivo; vuelva a subirlo").param("ahora", ahora).param("id", id).update();
            if (m != 1) {
                return detalle(id); // ya pasó a mapeo: lo que falló fue solo borrar un temporal (queda en el registro)
            }
            bitacora.registrar("analitica_cargue_fallido", "Cargue " + id + " · no se pudo leer el encabezado", quien.id(), quien.usuario());
            mantenimiento.borrarOriginal(id, ruta, "falla al leer");
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo leer el archivo. Vuelva a subirlo.");
        }
        return detalle(id);
    }

    private void fallarHuella(Cargue c, String ruta, UsuarioSesion quien) {
        if (ruta != null) {
            almacen.descartar(ruta);
        }
        int n = jdbc.sql("UPDATE ana_cargues SET estado = 'fallido', error = :e, terminado = :ahora WHERE id = :id AND estado = 'subiendo'")
                .param("e", MENSAJE_HUELLA).param("ahora", OffsetDateTime.now(reloj)).param("id", c.id()).update();
        if (n == 1) {
            mantenimiento.descartarPartes(c.id());
            bitacora.registrar("analitica_cargue_fallido", "Cargue " + c.id() + " · huella distinta o parte faltante", quien.id(), quien.usuario());
        }
        throw ErrorNegocio.validacion(MENSAJE_HUELLA);
    }

    /** Descarta un cargue abierto (subida o mapeo) del usuario (o cualquiera, el administrador): borra sus archivos; el cargue queda. */
    Map<String, Object> descartar(long id, UsuarioSesion quien) {
        Cargue c = cargue(id);
        boolean admin = esAdministrador();
        boolean dueno = "archivo".equals(c.fuente()) && c.usuarioId() != null && c.usuarioId() == quien.id();
        if (!admin && !dueno) {
            throw ErrorNegocio.noEncontrado("El cargue no existe.");
        }
        if (!Set.of("subiendo", "mapeo").contains(c.estado())
                || !mantenimiento.cerrarAbierto(id, c.estado(), "descartado", "Descartado por " + quien.usuario() + "; se borraron el archivo y sus partes",
                        "analitica_cargue_descartado", quien.id(), quien.usuario())) {
            throw ErrorNegocio.conflicto("Solo se descarta un cargue en subida o en mapeo (está «" + cargue(id).estado() + "»).");
        }
        primeras.olvidar(id);
        return detalle(id);
    }

    // ---------- Mapeo ----------

    /**
     * Encabezados, mapeo propuesto (plantilla o sinónimos), columnas con datos personales, campos y las 20 primeras filas enmascaradas
     * según la propuesta.
     */
    Map<String, Object> mapeo(long id, UsuarioSesion quien) {
        Cargue c = propio(id, quien, "mapeo");
        Map<String, Object> fila = jdbc.sql("SELECT encabezados::text AS enc, mapeo::text AS mapeo, avisos::text AS avisos FROM ana_cargues WHERE id = :id")
                .param("id", id).query().singleRow();
        List<String> encabezados = deJson((String) fila.get("enc"), new TypeReference<>() { });
        List<Campo> propuesta;
        Map<String, Object> plantilla = null;
        if (fila.get("mapeo") != null) {
            propuesta = campos(deJson((String) fila.get("mapeo"), new TypeReference<List<String>>() { }));
        } else {
            List<Map<String, Object>> ps = jdbc.sql("SELECT id, nombre, mapeo::text AS mapeo FROM ana_mapeo_plantillas WHERE firma_encabezado = :f")
                    .param("f", AnaliticaIngesta.firmaEncabezado(encabezados)).query().listOfRows();
            if (!ps.isEmpty()) {
                plantilla = new LinkedHashMap<>();
                plantilla.put("id", ps.getFirst().get("id"));
                plantilla.put("nombre", ps.getFirst().get("nombre"));
                propuesta = campos(deJson((String) ps.getFirst().get("mapeo"), new TypeReference<List<String>>() { }));
            } else {
                propuesta = AnaliticaIngesta.proponerMapeo(encabezados);
            }
        }
        if (propuesta.size() != encabezados.size()) {
            propuesta = AnaliticaIngesta.proponerMapeo(encabezados);
        }
        actividad(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("nombreArchivo", c.nombre());
        m.put("encabezados", encabezados);
        m.put("encabezadoReconocido", AnaliticaIngesta.encabezadoReconocido(encabezados));
        m.put("personales", encabezados.stream().map(AnaliticaIngesta::columnaPersonal).toList());
        m.put("propuesta", propuesta.stream().map(Campo::clave).toList());
        m.put("plantilla", plantilla);
        m.put("campos", catalogoCampos());
        List<String> problemas = new ArrayList<>(AnaliticaIngesta.problemasMapeo(propuesta));
        problemas.addAll(AnaliticaIngesta.problemasPrivacidad(encabezados, propuesta));
        m.put("problemas", problemas);
        m.put("avisos", deJson((String) fila.get("avisos"), new TypeReference<List<String>>() { }));
        VistaPrevia v = vistaPrevia(c, encabezados, propuesta);
        m.put("vistaPrevia", v.filas());
        m.put("avisoVistaPrevia", v.aviso());
        return m;
    }

    /** Vista previa con el mapeo que el usuario tiene en pantalla (columnas por clave): cada columna se enmascara según su asignación. */
    Map<String, Object> vistaPrevia(long id, List<String> columnas, UsuarioSesion quien) {
        Cargue c = propio(id, quien, "mapeo");
        List<String> encabezados = deJson(jdbc.sql("SELECT encabezados::text FROM ana_cargues WHERE id = :id").param("id", id).query(String.class).single(),
                new TypeReference<>() { });
        List<Campo> mapeo = new ArrayList<>(encabezados.size());
        for (int i = 0; i < encabezados.size(); i++) {
            String k = columnas != null && i < columnas.size() ? columnas.get(i) : null;
            mapeo.add(k == null ? Campo.IGNORAR : Campo.deClave(k.strip()).orElse(Campo.IGNORAR));
        }
        VistaPrevia v = vistaPrevia(c, encabezados, mapeo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vistaPrevia", v.filas());
        m.put("avisoVistaPrevia", v.aviso());
        return m;
    }

    static List<Map<String, Object>> catalogoCampos() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Campo k : Campo.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clave", k.clave());
            m.put("etiqueta", k.etiqueta());
            m.put("enMensaje", k.enMensaje());
            m.put("tipo", k.tipo().name().toLowerCase(Locale.ROOT));
            m.put("personal", AnaliticaIngesta.CAMPOS_PERSONALES.contains(k));
            l.add(m);
        }
        return l;
    }

    private List<Campo> campos(List<String> claves) {
        return claves.stream().map(k -> k == null ? Campo.IGNORAR : Campo.deClave(k).orElse(Campo.IGNORAR)).toList();
    }

    private record VistaPrevia(List<List<String>> filas, String aviso) {
    }

    /**
     * Primeras filas del archivo enmascaradas: en claro solo las columnas asignadas a un campo no personal cuyo encabezado no es de un
     * dato personal; el paciente y el profesional como seudónimo corto, la fecha de nacimiento como año, lo descartado como «(dato
     * personal)» y lo demás (no se usa, columnas sin reconocer) como «(oculto)». Las filas crudas se guardan solo en memoria (pocas, por
     * poco tiempo) para no volver a abrir el archivo (ni a leer los textos de un xlsx) en cada cambio del mapeo.
     */
    private VistaPrevia vistaPrevia(Cargue c, List<String> encabezados, List<Campo> mapeo) {
        String aviso = null;
        List<List<String>> crudas = primeras.leer(c.id());
        if (crudas == null) {
            try (ArchivoDispensacion a = ArchivoDispensacion.abrir(() -> almacen.leer(c.ruta()), c.nombre(), propiedades.archivoMaxBytes(), limites(), null)) {
                crudas = leerPrimeras(a);
                primeras.guardar(c.id(), crudas);
            } catch (ErrorEstructura e) {
                aviso = "La vista previa está incompleta: " + e.getMessage();
                crudas = List.of();
            } catch (IOException | RuntimeException e) {
                LOG.warn("[analitica] No se pudo leer la vista previa del cargue {}: {}", c.id(), e.getMessage());
                aviso = "No se pudo leer la vista previa del archivo; revise el mapeo con cuidado.";
                crudas = List.of();
            }
        }
        int iTipo = mapeo.indexOf(Campo.TIPO_DOCUMENTO);
        List<List<String>> filas = new ArrayList<>(crudas.size());
        for (List<String> celdas : crudas) {
            List<String> f = new ArrayList<>(celdas.size());
            for (int i = 0; i < celdas.size(); i++) {
                Campo k = i < mapeo.size() ? mapeo.get(i) : Campo.IGNORAR;
                String enc = i < encabezados.size() ? encabezados.get(i) : "";
                String v = celdas.get(i);
                f.add(enmascarar(k, enc, v, iTipo >= 0 && iTipo < celdas.size() ? celdas.get(iTipo) : null));
            }
            filas.add(f);
        }
        return new VistaPrevia(filas, aviso);
    }

    private String enmascarar(Campo k, String encabezado, String v, String tipoDocumento) {
        if (v == null || v.isBlank()) {
            return "";
        }
        return switch (k) {
            case PACIENTE -> corto(seudo.disponible() ? seudo.paciente(tipoDocumento, v) : null);
            case PROFESIONAL -> corto(seudo.disponible() ? seudo.profesional(v) : null);
            case FECHA_NACIMIENTO -> AnaliticaIngesta.anioNacimiento(v).map(String::valueOf).orElse("(dato personal)");
            case DESCARTAR -> "(dato personal)";
            default -> AnaliticaIngesta.visibleEnVistaPrevia(encabezado, k) ? v : "(oculto)";
        };
    }

    /** Hasta 20 filas crudas del archivo (lo leído si el archivo se rechaza a mitad). */
    private static List<List<String>> leerPrimeras(ArchivoDispensacion a) throws IOException {
        List<List<String>> filas = new ArrayList<>();
        try {
            a.recorrer((orden, linea, celdas) -> {
                filas.add(List.copyOf(celdas));
                if (filas.size() >= FILAS_VISTA_PREVIA) {
                    throw new FuenteDispensacion.Detener();
                }
            });
        } catch (FuenteDispensacion.Detener e) {
            // suficientes
        } catch (ErrorEstructura e) {
            if (filas.isEmpty()) {
                throw e;
            }
        }
        return List.copyOf(filas);
    }

    private static String corto(String seudonimo) {
        return seudonimo == null ? "(dato personal)" : "seud. " + seudonimo.substring(0, 10) + "…";
    }

    /**
     * Confirma el mapeo (422 si falta un obligatorio o si una columna personal va a un campo que se guarda en claro), guarda la plantilla
     * si se pide y deja el cargue en la cola. «Reemplazar el periodo» exige el periodo declarado (desde y hasta): solo ese rango se
     * reemplaza y las filas fuera de él se rechazan por «fuera del periodo declarado».
     */
    Map<String, Object> confirmar(long id, Confirmacion conf, UsuarioSesion quien) {
        propio(id, quien, "mapeo");
        List<String> encabezados = deJson(jdbc.sql("SELECT encabezados::text FROM ana_cargues WHERE id = :id").param("id", id).query(String.class).single(),
                new TypeReference<>() { });
        List<String> columnas = conf.columnas() == null ? List.of() : conf.columnas();
        if (columnas.size() != encabezados.size()) {
            throw ErrorNegocio.validacion("El mapeo debe traer un campo por cada columna del archivo (" + encabezados.size() + ").");
        }
        List<Campo> mapeo = new ArrayList<>();
        for (String k : columnas) {
            mapeo.add(k == null || k.isBlank() ? Campo.IGNORAR
                    : Campo.deClave(k).orElseThrow(() -> ErrorNegocio.validacion("Campo desconocido en el mapeo.")));
        }
        List<String> problemas = new ArrayList<>(AnaliticaIngesta.problemasMapeo(mapeo));
        problemas.addAll(AnaliticaIngesta.problemasPrivacidad(encabezados, mapeo));
        if (!problemas.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, problemas.getFirst(), Map.of("problemas", problemas));
        }
        String modo = conf.modo() == null || conf.modo().isBlank() ? "agregar" : conf.modo();
        if (!Set.of("agregar", "reemplazar").contains(modo)) {
            throw ErrorNegocio.validacion("Modo no válido: agregar o reemplazar.");
        }
        if ("reemplazar".equals(modo) && (conf.periodoDesde() == null || conf.periodoHasta() == null)) {
            throw ErrorNegocio.validacion("Para reemplazar el periodo indique el periodo declarado (desde y hasta).",
                    conf.periodoDesde() == null ? "periodoDesde" : "periodoHasta");
        }
        if (conf.periodoDesde() != null && conf.periodoHasta() != null && conf.periodoDesde().isAfter(conf.periodoHasta())) {
            throw ErrorNegocio.validacion("El periodo declarado no es válido (desde después de hasta).", "periodoDesde");
        }
        List<String> claves = mapeo.stream().map(Campo::clave).toList();
        Long plantillaId;
        String detallePlantilla = "";
        if (Boolean.TRUE.equals(conf.guardarPlantilla())) {
            String nombre = conf.nombrePlantilla() == null || conf.nombrePlantilla().isBlank() ? "Plantilla del cargue " + id : conf.nombrePlantilla().strip();
            if (nombre.length() > 120) {
                throw ErrorNegocio.validacion("El nombre de la plantilla es muy largo (máximo 120).", "nombrePlantilla");
            }
            plantillaId = jdbc.sql("""
                    INSERT INTO ana_mapeo_plantillas (nombre, firma_encabezado, encabezados, mapeo, creado_por, creado, actualizado)
                    VALUES (:nombre, :firma, CAST(:enc AS jsonb), CAST(:mapeo AS jsonb), :u, :ahora, :ahora)
                    ON CONFLICT (firma_encabezado) DO UPDATE SET nombre = EXCLUDED.nombre, mapeo = EXCLUDED.mapeo, actualizado = EXCLUDED.actualizado
                    RETURNING id""")
                    .param("nombre", nombre).param("firma", AnaliticaIngesta.firmaEncabezado(encabezados)).param("enc", aJson(encabezados))
                    .param("mapeo", aJson(claves)).param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
            detallePlantilla = " · plantilla " + plantillaId + " guardada";
        } else {
            plantillaId = jdbc.sql("SELECT id FROM ana_mapeo_plantillas WHERE firma_encabezado = :f AND mapeo = CAST(:mapeo AS jsonb)")
                    .param("f", AnaliticaIngesta.firmaEncabezado(encabezados)).param("mapeo", aJson(claves)).query(Long.class).optional().orElse(null);
            if (plantillaId != null) {
                detallePlantilla = " · plantilla " + plantillaId + " usada";
            }
        }
        int n = jdbc.sql("""
                UPDATE ana_cargues SET mapeo = CAST(:mapeo AS jsonb), modo = :modo, plantilla_id = :p, declarado_desde = :d, declarado_hasta = :h,
                       estado = 'pendiente', etapa = NULL, porcentaje = 0, actividad = :ahora
                WHERE id = :id AND estado = 'mapeo'""")
                .param("mapeo", aJson(claves)).param("modo", modo).param("p", plantillaId).param("d", conf.periodoDesde()).param("h", conf.periodoHasta())
                .param("ahora", OffsetDateTime.now(reloj)).param("id", id).update();
        if (n != 1) {
            throw ErrorNegocio.conflicto("El cargue ya no está en la etapa de mapeo.");
        }
        primeras.olvidar(id);
        String periodo = conf.periodoDesde() == null ? "" : " · periodo " + conf.periodoDesde().format(FECHA) + " – "
                + (conf.periodoHasta() == null ? "" : conf.periodoHasta().format(FECHA));
        bitacora.registrar("analitica_cargue_mapeo", "Cargue " + id + " · modo " + modo + periodo + detallePlantilla, quien.id(), quien.usuario());
        procesador.despertar();
        return detalle(id);
    }

    /**
     * Lo que ocultaría «Reemplazar el periodo» con el periodo declarado: cargues aplicados vigentes de la misma fuente contenidos en él y
     * sus registros procesados. La pantalla lo muestra en la confirmación.
     */
    Map<String, Object> reemplazo(long id, LocalDate desde, LocalDate hasta, UsuarioSesion quien) {
        Cargue c = propio(id, quien, "mapeo");
        if (desde == null || hasta == null || desde.isAfter(hasta)) {
            throw ErrorNegocio.validacion("Indique un periodo declarado válido (desde y hasta).", hasta == null && desde != null ? "periodoHasta" : "periodoDesde");
        }
        List<Long> ids = ProcesadorCargues.objetivosReemplazo(jdbc, c.fuente(), id, desde, hasta);
        long registros = ids.isEmpty() ? 0 : jdbc.sql("SELECT coalesce(sum(procesados), 0) FROM ana_cargues WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(Long[]::new)).query(Long.class).single();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("desde", desde);
        m.put("hasta", hasta);
        m.put("cargues", ids);
        m.put("registros", registros);
        return m;
    }

    // ---------- Consulta y descargas ----------

    private static final String SELECT = """
            SELECT c.id, c.estado, c.etapa, c.porcentaje, c.filas_leidas, c.fuente, c.fuente_texto, c.nombre_archivo, c.formato, c.tamano_bytes,
                   c.sha256, c.modo, c.creado, c.recibido, c.iniciado, c.aplicado_en, c.terminado, c.periodo_desde, c.periodo_hasta,
                   c.recibidos, c.procesados, c.rechazados, c.esm_sin_correspondencia, c.motivos::text AS motivos, c.motivo_texto,
                   c.avisos::text AS avisos, c.etapas_ms::text AS etapas, c.duracion_ms, c.responsable_texto, c.error, c.reemplazado_por,
                   c.reemplazado_en, c.duplicado_de, c.usuario_id, c.almacen_ruta IS NOT NULL AS original_disponible, c.conservar_hasta,
                   c.declarado_desde, c.declarado_hasta,
                   CASE WHEN c.estado = 'pendiente'
                        THEN (SELECT count(*) FROM ana_cargues o WHERE o.estado IN ('pendiente', 'procesando') AND o.id < c.id) END AS posicion
            FROM ana_cargues c""";

    Map<String, Object> detalle(long id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id").param("id", id).query(this::fila).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El cargue no existe."));
    }

    private Map<String, Object> fila(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("estado", rs.getString("estado"));
        m.put("etapa", rs.getString("etapa"));
        m.put("porcentaje", rs.getInt("porcentaje"));
        m.put("filasLeidas", rs.getLong("filas_leidas"));
        m.put("fuente", rs.getString("fuente"));
        m.put("fuenteTexto", rs.getString("fuente_texto"));
        m.put("nombreArchivo", rs.getString("nombre_archivo"));
        m.put("formato", rs.getString("formato"));
        m.put("tamano", rs.getObject("tamano_bytes"));
        m.put("sha256", strip(rs.getString("sha256")));
        m.put("modo", rs.getString("modo"));
        m.put("creado", rs.getObject("creado", OffsetDateTime.class));
        m.put("recibido", rs.getObject("recibido", OffsetDateTime.class));
        m.put("iniciado", rs.getObject("iniciado", OffsetDateTime.class));
        m.put("aplicadoEn", rs.getObject("aplicado_en", OffsetDateTime.class));
        m.put("terminado", rs.getObject("terminado", OffsetDateTime.class));
        m.put("periodoDesde", rs.getObject("periodo_desde", LocalDate.class));
        m.put("periodoHasta", rs.getObject("periodo_hasta", LocalDate.class));
        m.put("recibidos", rs.getObject("recibidos"));
        m.put("procesados", rs.getObject("procesados"));
        m.put("rechazados", rs.getObject("rechazados"));
        m.put("esmSinCorrespondencia", rs.getObject("esm_sin_correspondencia"));
        Map<String, Long> motivos = deJson(rs.getString("motivos"), new TypeReference<LinkedHashMap<String, Long>>() { });
        List<Map<String, Object>> lista = new ArrayList<>();
        motivos.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("causa", e.getKey());
                    x.put("texto", AnaliticaIngesta.Causa.deCodigo(e.getKey()).map(AnaliticaIngesta.Causa::texto).orElse(e.getKey()));
                    x.put("cantidad", e.getValue());
                    lista.add(x);
                });
        m.put("motivos", lista);
        m.put("motivo", rs.getString("motivo_texto"));
        m.put("avisos", deJson(rs.getString("avisos"), new TypeReference<List<String>>() { }));
        m.put("etapasMs", deJson(rs.getString("etapas"), new TypeReference<LinkedHashMap<String, Long>>() { }));
        m.put("duracionMs", rs.getObject("duracion_ms"));
        m.put("responsable", rs.getString("responsable_texto"));
        m.put("error", rs.getString("error"));
        m.put("reemplazadoPor", rs.getObject("reemplazado_por"));
        m.put("reemplazadoEn", rs.getObject("reemplazado_en", OffsetDateTime.class));
        m.put("duplicadoDe", rs.getObject("duplicado_de"));
        Long dueno = (Long) rs.getObject("usuario_id");
        m.put("propio", UsuarioSesion.actual().map(u -> dueno != null && dueno == u.id()).orElse(false));
        m.put("originalDisponible", rs.getBoolean("original_disponible"));
        m.put("conservarHasta", rs.getObject("conservar_hasta", OffsetDateTime.class));
        m.put("declaradoDesde", rs.getObject("declarado_desde", LocalDate.class));
        m.put("declaradoHasta", rs.getObject("declarado_hasta", LocalDate.class));
        m.put("posicion", rs.getObject("posicion"));
        return m;
    }

    private record Consulta(String where, Map<String, Object> params) {
    }

    private static Consulta filtrar(Filtros f) {
        List<String> w = new ArrayList<>();
        Map<String, Object> p = new LinkedHashMap<>();
        if (f.fuente() != null && !f.fuente().isBlank()) {
            if (!Set.of("archivo", "ws_opl").contains(f.fuente())) {
                throw ErrorNegocio.validacion("Fuente no válida.");
            }
            w.add("c.fuente = :fuente");
            p.put("fuente", f.fuente());
        }
        if (f.estado() != null && !f.estado().isBlank()) {
            if (!ESTADOS.contains(f.estado())) {
                throw ErrorNegocio.validacion("Estado no válido.");
            }
            w.add("c.estado = :estado");
            p.put("estado", f.estado());
        }
        if (f.desde() != null) {
            w.add("c.creado >= :desde");
            p.put("desde", f.desde().atStartOfDay(Fechas.BOGOTA).toOffsetDateTime());
        }
        if (f.hasta() != null) {
            w.add("c.creado < :hasta");
            p.put("hasta", f.hasta().plusDays(1).atStartOfDay(Fechas.BOGOTA).toOffsetDateTime());
        }
        return new Consulta(w.isEmpty() ? "" : " WHERE " + String.join(" AND ", w), p);
    }

    /** 422 si un filtro no es válido (antes de empezar a escribir un CSV). */
    void validarFiltros(Filtros f) {
        filtrar(f);
    }

    /** Bitácora de cargues, del más reciente al más antiguo, paginada. Con la advertencia si falta la clave del seudónimo. */
    Map<String, Object> listar(Filtros f) {
        Consulta q = filtrar(f);
        int pagina = f.pagina() == null || f.pagina() < 1 ? 1 : f.pagina();
        long total = jdbc.sql("SELECT count(*) FROM ana_cargues c" + q.where()).params(q.params()).query(Long.class).single();
        List<Map<String, Object>> filas = jdbc.sql(SELECT + q.where() + " ORDER BY c.id DESC LIMIT :lim OFFSET :off").params(q.params())
                .param("lim", TAMANO_PAGINA).param("off", (pagina - 1) * TAMANO_PAGINA).query(this::fila).list();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filas", filas);
        m.put("total", total);
        m.put("pagina", pagina);
        m.put("tamanoPagina", TAMANO_PAGINA);
        var perfiles = UsuarioSesion.perfilesActuales();
        m.put("puedeCargar", perfiles.stream().anyMatch(AnaliticaIngesta.PERFILES_CARGA::contains));
        m.put("esAdministrador", perfiles.contains(Perfil.ADMINISTRADOR));
        m.put("avisoConfiguracion", seudo.disponible() ? null : Seudonimizador.MENSAJE_SIN_CLAVE);
        m.put("horasAbandono", propiedades.horasSinActividad());
        m.put("parteMaxBytes", propiedades.parteMaxBytes());
        m.put("archivoMaxBytes", propiedades.archivoMaxBytes());
        return m;
    }

    /**
     * CSV «;» UTF-8 con BOM de los rechazos: línea, causa y columna, nunca el contenido de la celda. En flujo: la consulta corre en una
     * transacción de solo lectura con fetchSize (millones de rechazos sin cargarlos en memoria).
     */
    void rechazosCsv(long id, OutputStream salida, UsuarioSesion quien) {
        cargue(id);
        long[] filas = {0};
        try {
            Writer w = new OutputStreamWriter(salida, StandardCharsets.UTF_8);
            w.write('﻿');
            w.write("linea;causa;columna\r\n");
            lectura.executeWithoutResult(t -> flujo.query(
                    "SELECT linea, causa, columna, referencia FROM ana_cargue_rechazos WHERE cargue_id = ? ORDER BY orden, causa, columna NULLS FIRST",
                    rs -> {
                        try {
                            w.write(csv(rs.getString(1)) + ";" + csv(AnaliticaIngesta.textoCausa(rs.getString(2), rs.getString(4))) + ";"
                                    + csv(rs.getString(3)) + "\r\n");
                            filas[0]++;
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }, id));
            w.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        bitacora.registrar("analitica_cargue_rechazos_descarga", "Cargue " + id + " · " + filas[0] + " filas de rechazos", quien.id(), quien.usuario());
    }

    /** CSV de la bitácora de cargues con los filtros de la pantalla (los ocho atributos de 4.7.1, más estado, SHA-256 y duración). */
    void bitacoraCsv(Filtros f, OutputStream salida, UsuarioSesion quien) {
        Consulta q = filtrar(f);
        long[] filas = {0};
        try {
            Writer w = new OutputStreamWriter(salida, StandardCharsets.UTF_8);
            w.write('﻿');
            w.write("id;fecha_hora_recepcion;fecha_hora_aplicacion;fuente;periodo_procesado;recibidos;procesados;rechazados;motivo_rechazo;"
                    + "responsable;estado;sha256;duracion_s\r\n");
            jdbc.sql(SELECT + q.where() + " ORDER BY c.id DESC").params(q.params()).query(this::fila).list().forEach(m -> {
                try {
                    w.write(m.get("id") + ";" + fechaHora((OffsetDateTime) m.get("recibido")) + ";" + fechaHora((OffsetDateTime) m.get("aplicadoEn")) + ";"
                            + csv((String) m.get("fuenteTexto")) + ";" + csv(periodo(m)) + ";" + nulo(m.get("recibidos")) + ";" + nulo(m.get("procesados"))
                            + ";" + nulo(m.get("rechazados")) + ";" + csv((String) m.get("motivo")) + ";" + csv((String) m.get("responsable")) + ";"
                            + m.get("estado") + ";" + nulo(m.get("sha256")) + ";" + segundos(m.get("duracionMs")) + "\r\n");
                    filas[0]++;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            w.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        bitacora.registrar("analitica_cargue_bitacora_exportar", "Bitácora de cargues · " + filas[0] + " filas", quien.id(), quien.usuario());
    }

    /** El archivo original (datos personales en claro): solo ADMINISTRADOR y queda en la bitácora. */
    Descarga original(long id, UsuarioSesion quien) {
        Cargue c = cargue(id);
        if (c.ruta() == null) {
            throw new ErrorNegocio(HttpStatus.GONE, "El archivo original ya no se conserva.");
        }
        try {
            InputStream in = almacen.leer(c.ruta());
            bitacora.registrar("analitica_cargue_original_descarga", "Cargue " + id + " · original", quien.id(), quien.usuario());
            String nombre = c.nombre() == null ? "cargue_" + id + "." + c.formato() : c.nombre().replaceAll("[^A-Za-z0-9._-]", "_");
            return new Descarga(nombre, c.tamano() == null ? -1 : c.tamano(), in);
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("[analitica] El original del cargue {} no está en el almacén", id, e);
            throw new ErrorNegocio(HttpStatus.GONE, "El archivo original no está disponible.");
        }
    }

    // ---------- Apoyo ----------

    /**
     * Primeras filas crudas de los archivos en mapeo, solo en memoria (máximo 16 cargues, 30 minutos): la vista previa se recalcula con
     * el mapeo de la pantalla sin volver a abrir el archivo. Nunca se escriben en la base ni en el registro.
     */
    private static final class PrimerasFilas {
        private static final int MAX = 16;
        private static final long VIGENCIA_NS = 30L * 60 * 1_000_000_000L;

        private record Entrada(List<List<String>> filas, long desde) {
        }

        private final Map<Long, Entrada> mapa = new LinkedHashMap<>(MAX, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Entrada> e) {
                return size() > MAX;
            }
        };

        synchronized void guardar(long id, List<List<String>> filas) {
            mapa.put(id, new Entrada(filas, System.nanoTime()));
        }

        synchronized List<List<String>> leer(long id) {
            Entrada e = mapa.get(id);
            if (e == null || System.nanoTime() - e.desde() > VIGENCIA_NS) {
                mapa.remove(id);
                return null;
            }
            return e.filas();
        }

        synchronized void olvidar(long id) {
            mapa.remove(id);
        }
    }

    private static String periodo(Map<String, Object> m) {
        LocalDate d = (LocalDate) m.get("periodoDesde");
        LocalDate h = (LocalDate) m.get("periodoHasta");
        return d == null ? "" : d.format(FECHA) + " – " + h.format(FECHA);
    }

    private static String fechaHora(OffsetDateTime f) {
        return f == null ? "" : f.atZoneSameInstant(Fechas.BOGOTA).format(FECHA_HORA);
    }

    private static String segundos(Object ms) {
        return ms == null ? "" : java.math.BigDecimal.valueOf(((Number) ms).longValue(), 3).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                .replace('.', ',');
    }

    private static String nulo(Object o) {
        return o == null ? "" : o.toString();
    }

    /**
     * Celda de CSV con «;»: entre comillas si hace falta y sin fórmulas (=, +, -, @, tabulador o retorno de carro al inicio, también
     * tras espacios: Excel los ignora antes de una fórmula).
     */
    static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        String sinEspacios = s.stripLeading();
        if (!s.isEmpty() && ("\t\r".indexOf(s.charAt(0)) >= 0 || !sinEspacios.isEmpty() && "=+-@".indexOf(sinEspacios.charAt(0)) >= 0)) {
            s = "'" + s;
        }
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String strip(String s) {
        return s == null ? null : s.strip();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T deJson(String texto, TypeReference<T> tipo) {
        try {
            return json.readValue(texto == null ? "null" : texto, tipo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON ilegible en ana_cargues", e);
        }
    }
}
