package co.com.agsamericas.digsa.exportacion;

import co.com.agsamericas.digsa.exportacion.IndiceSoportes.Soporte;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.reglas.Exportacion;
import co.com.agsamericas.digsa.reglas.Exportacion.TablaExportada;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * Escribe el ZIP de la exportación completa en el almacén, en flujo («Una sola foto de la base, fila por fila»): recibe una conexión ya
 * en una transacción READ ONLY y REPEATABLE READ (una sola instantánea para todas las tablas), lee cada tabla con un cursor por lotes
 * (fetchSize) y escribe cada fila al ZIP sin tener la tabla ni el ZIP en memoria. Luego los soportes por visita, el índice (en un
 * temporal que se borra siempre) y el LEEME con las filas y la huella de cada CSV.
 */
@Component
class GeneradorExportacion {

    /** Filas por lote del cursor. */
    static final int LOTE = 1000;
    /** Las firmas traen la imagen (hasta 300 KB): lotes cortos. */
    static final int LOTE_FIRMAS = 50;

    /** Solo para pruebas: se llama al terminar cada tabla (la de consistencia escribe una visita a mitad de la exportación). */
    volatile Consumer<String> trasTabla = t -> { };

    record Resultado(int tablas, long filas, long soportes, long distintas, long noEncontrados, long tamano, String sha256) {
    }

    /** Columna de una tabla con su tipo en PostgreSQL (udt_name). */
    record Columna(String nombre, String tipo) {
    }

    private final Almacen almacen;

    GeneradorExportacion(Almacen almacen) {
        this.almacen = almacen;
    }

    /**
     * @param conexion    en una transacción de solo lectura REPEATABLE READ; no se confirma aquí
     * @param ruta        ruta nueva del ZIP en el almacén
     * @param generadoPor «Nombre (usuario)» para el LEEME
     * @param limite      instante en que se aborta (lanza {@link LimiteExcedido})
     */
    Resultado generar(Connection conexion, String ruta, String generadoPor, OffsetDateTime ahora, Instant limite) throws IOException, SQLException {
        Runnable plazo = () -> {
            if (Instant.now().isAfter(limite)) {
                throw new LimiteExcedido();
            }
        };
        long restanteMs = Math.max(1000, limite.toEpochMilli() - Instant.now().toEpochMilli());
        SingleConnectionDataSource unica = new SingleConnectionDataSource(conexion, true);
        JdbcClient db = cliente(unica, LOTE);
        // Ninguna consulta sola pasa del límite (la revisión entre filas no alcanza a una consulta que no devuelve nada)
        db.sql("SET LOCAL statement_timeout = " + restanteMs).update();

        Map<String, List<Columna>> esquema = esquema(db);
        for (String t : Exportacion.TABLAS) {
            if (!esquema.containsKey(t)) {
                throw new IllegalStateException("La tabla " + t + " de la lista de exportación no existe");
            }
        }

        Path temporal = Files.createTempFile("digsa-indice-", ".csv");
        try {
            OutputStream archivo = almacen.escribir(ruta);
            Contador contador = new Contador(archivo);
            DigestOutputStream huellaZip = new DigestOutputStream(contador, EscritorZip.sha256());
            List<TablaExportada> tablas = new ArrayList<>();
            IndiceSoportes indice;
            try (EscritorZip zip = new EscritorZip(new BufferedOutputStream(huellaZip, 1 << 16), plazo)) {
                for (String t : Exportacion.TABLAS) {
                    tablas.add(tabla(db, zip, t, esquema.get(t), plazo));
                    trasTabla.accept(t);
                }
                zip.carpeta("soportes/");
                try (Writer w = Files.newBufferedWriter(temporal, StandardCharsets.UTF_8)) {
                    indice = new IndiceSoportes(w);
                    archivos(db, zip, indice, plazo);
                    firmas(cliente(unica, LOTE_FIRMAS), zip, indice, plazo);
                }
                try (InputStream in = Files.newInputStream(temporal)) {
                    zip.copiar(in, "soportes/indice.csv", true);
                }
                zip.texto("LEEME.txt", Exportacion.leeme(new Exportacion.Leeme(generadoPor, ahora, tablas, indice.total(), indice.distintas(),
                        indice.noEncontrados())));
            }
            long filas = tablas.stream().mapToLong(TablaExportada::filas).sum();
            return new Resultado(tablas.size(), filas, indice.total(), indice.distintas(), indice.noEncontrados(), contador.bytes,
                    HexFormat.of().formatHex(huellaZip.getMessageDigest().digest()));
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    private static JdbcClient cliente(SingleConnectionDataSource ds, int lote) {
        JdbcTemplate t = new JdbcTemplate(ds);
        t.setFetchSize(lote); // con la transacción abierta, el controlador de PostgreSQL usa un cursor y trae de a «lote» filas
        return JdbcClient.create(t);
    }

    /** Tablas y columnas del esquema public, en el orden de cada tabla (sin las particiones de la bitácora). */
    private static Map<String, List<Columna>> esquema(JdbcClient db) {
        Map<String, List<Columna>> m = new LinkedHashMap<>();
        db.sql("""
                SELECT c.table_name, c.column_name, c.udt_name FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE' AND c.table_name = ANY(:t)
                ORDER BY c.table_name, c.ordinal_position""").param("t", Exportacion.TABLAS.toArray(String[]::new))
                .query(rs -> {
                    m.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Columna(rs.getString(2), rs.getString(3)));
                });
        return m;
    }

    private TablaExportada tabla(JdbcClient db, EscritorZip zip, String tabla, List<Columna> todas, Runnable plazo) throws IOException {
        List<String> nombres = Exportacion.columnas(tabla, todas.stream().map(Columna::nombre).toList());
        List<Columna> cols = todas.stream().filter(c -> nombres.contains(c.nombre())).toList();
        for (Columna c : cols) {
            if ("bytea".equals(c.tipo())) {
                // Un binario nuevo sin decidir qué hacer con él: falla cerrado (podría ser un secreto)
                throw new IllegalStateException("La columna binaria " + tabla + "." + c.nombre() + " no está en las excluidas");
            }
        }
        String sql = "SELECT " + String.join(", ", cols.stream().map(c -> citar(c.nombre())).toList()) + " FROM " + citar(tabla);
        long[] filas = {0};
        try (EscritorZip.Entrada e = zip.abrir(tabla + ".csv", true)) {
            Writer w = e.escritor();
            w.write('﻿');
            w.write(Exportacion.filaTexto(nombres));
            Object[] valores = new Object[cols.size()];
            List<Object> fila = Arrays.asList(valores);
            db.sql(sql).query(rs -> {
                for (int i = 0; i < valores.length; i++) {
                    valores[i] = valor(rs, i + 1, cols.get(i).tipo());
                }
                escribir(w, Exportacion.fila(fila));
                if (++filas[0] % LOTE == 0) {
                    plazo.run();
                }
            });
            return new TablaExportada(tabla, filas[0], e.terminar());
        }
    }

    /** Valor de una celda según su tipo en PostgreSQL. */
    static Object valor(ResultSet rs, int i, String tipo) throws SQLException {
        return switch (tipo) {
            case "timestamptz" -> rs.getObject(i, OffsetDateTime.class);
            case "timestamp" -> rs.getObject(i, LocalDateTime.class);
            case "date" -> rs.getObject(i, LocalDate.class);
            case "time" -> rs.getObject(i, LocalTime.class);
            case "numeric" -> rs.getBigDecimal(i); // sin redondear (coordenadas de 6 decimales): solo cambia el punto por la coma
            case "int2", "int4", "int8", "float4", "float8", "bool" -> rs.getObject(i);
            default -> {
                if (tipo.startsWith("_")) {
                    Array a = rs.getArray(i);
                    yield a == null ? null : Arrays.asList((Object[]) a.getArray());
                }
                yield rs.getString(i); // text, char, json y jsonb tal cual
            }
        };
    }

    /** Evidencias, actas, soportes del OPL y escritos de solicitud: se copian del almacén con su huella. */
    private void archivos(JdbcClient db, EscritorZip zip, IndiceSoportes indice, Runnable plazo) {
        db.sql("""
                SELECT 'archivos' AS tabla, a.id, a.visita_id, v.numero, p.nombre AS punto,
                       CASE WHEN va.id IS NOT NULL THEN 'actas' ELSE 'evidencias' END AS carpeta, va.ronda, a.clave, a.nombre_original,
                       a.ruta, a.tipo, a.tamano, a.sha256, u.usuario AS subido_por, a.subido, a.eliminado
                FROM archivos a JOIN visitas v ON v.id = a.visita_id JOIN puntos p ON p.id = v.punto_id
                LEFT JOIN visitas_actas va ON va.archivo_id = a.id LEFT JOIN usuarios u ON u.id = a.subido_por
                UNION ALL
                SELECT 'opl_soportes', s.id, s.visita_id, v.numero, p.nombre, 'opl', NULL, s.clave, s.nombre_original, s.ruta, s.tipo,
                       s.tamano, s.sha256, coalesce(u.usuario, s.correo), s.subido, NULL
                FROM opl_soportes s JOIN visitas v ON v.id = s.visita_id JOIN puntos p ON p.id = v.punto_id
                LEFT JOIN usuarios u ON u.id = s.usuario_id
                UNION ALL
                SELECT 'visitas_solicitudes', so.id, so.visita_id, v.numero, p.nombre, 'solicitud', NULL, NULL, so.archivo_nombre,
                       so.archivo_ruta, 'application/pdf', so.archivo_tamano, so.archivo_sha256, u.usuario, so.registrado, NULL
                FROM visitas_solicitudes so JOIN puntos p ON p.id = so.punto_id LEFT JOIN visitas v ON v.id = so.visita_id
                LEFT JOIN usuarios u ON u.id = so.solicitante_id
                WHERE so.archivo_ruta IS NOT NULL""").query(rs -> {
            String tabla = rs.getString("tabla");
            long id = rs.getLong("id");
            long visitaId = rs.getLong("visita_id");
            boolean sinVisita = rs.wasNull();
            String numero = rs.getString("numero");
            String carpeta = rs.getString("carpeta");
            String clave = rs.getString("clave");
            String original = rs.getString("nombre_original");
            String nombre = "actas".equals(carpeta) ? "acta_ronda_" + rs.getInt("ronda") + ".pdf" : Exportacion.nombreSoporte(id, original);
            String rutaZip = sinVisita ? "soportes/solicitudes/" + nombre
                    : "soportes/" + Exportacion.carpetaVisita(numero, visitaId) + "/" + carpeta + "/"
                    + ("opl".equals(carpeta) ? Exportacion.seguro(clave, "sin_clave") + "/" : "") + nombre;
            Soporte s = new Soporte(numero, rs.getString("punto"), carpeta, tabla, id, clave, original, rs.getString("tipo"),
                    rs.getLong("tamano"), rs.getString("sha256"), rs.getString("subido_por"), rs.getObject("subido", OffsetDateTime.class),
                    rs.getObject("eliminado", OffsetDateTime.class));
            try {
                indice.agregar(s, rutaZip, zip.copiarDelAlmacen(almacen, rs.getString("ruta"), rutaZip, s.tipo()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            plazo.run();
        });
    }

    /** Firmas del acta (<rol>_ronda_<n>.png) y del plan del OPL: la imagen sale del CSV y va aquí. */
    private static void firmas(JdbcClient db, EscritorZip zip, IndiceSoportes indice, Runnable plazo) {
        db.sql("""
                SELECT 'visitas_firmas' AS tabla, NULL::bigint AS id, f.visita_id, v.numero, p.nombre AS punto, f.rol, f.ronda, f.imagen,
                       f.nombre, f.firmado
                FROM visitas_firmas f JOIN visitas v ON v.id = f.visita_id JOIN puntos p ON p.id = v.punto_id
                UNION ALL
                SELECT 'opl_planes', pl.id, pl.visita_id, v.numero, p.nombre, 'plan_opl', va.ronda, pl.firma, pl.nombre, pl.firmado
                FROM opl_planes pl JOIN visitas_actas va ON va.id = pl.acta_id JOIN visitas v ON v.id = pl.visita_id
                JOIN puntos p ON p.id = v.punto_id
                WHERE pl.firma IS NOT NULL""").query(rs -> {
            String numero = rs.getString("numero");
            String nombre = Exportacion.seguro(rs.getString("rol"), "firma") + "_ronda_" + rs.getInt("ronda") + ".png";
            String rutaZip = "soportes/" + Exportacion.carpetaVisita(numero, rs.getLong("visita_id")) + "/firmas/" + nombre;
            long id = rs.getLong("id");
            Long idFila = rs.wasNull() ? null : id;
            byte[] imagen = rs.getBytes("imagen");
            Soporte s = new Soporte(numero, rs.getString("punto"), "firmas", rs.getString("tabla"), idFila, rs.getString("rol"), nombre, "image/png",
                    (long) imagen.length, null, rs.getString("nombre"), rs.getObject("firmado", OffsetDateTime.class), null);
            try {
                indice.agregar(s, rutaZip, zip.copiar(imagen, rutaZip, false));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            plazo.run();
        });
    }

    private static void escribir(Writer w, String texto) {
        try {
            w.write(texto);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Nombre de tabla o columna entre comillas (vienen de la lista y de information_schema, nunca del usuario). */
    private static String citar(String identificador) {
        return "\"" + identificador.replace("\"", "\"\"") + "\"";
    }

    /** Se pasó el límite de tiempo de la generación. */
    static final class LimiteExcedido extends RuntimeException {
        LimiteExcedido() {
            super("La exportación superó el límite de tiempo");
        }
    }

    /** Cuenta los bytes del ZIP (su tamaño en el almacén). */
    private static final class Contador extends FilterOutputStream {
        long bytes;

        Contador(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            bytes += len;
        }
    }
}
