package co.com.agsamericas.digsa.analitica.exportacion;

import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.reglas.AnaliticaExportacionHechos;
import co.com.agsamericas.digsa.reglas.Exportacion;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Component;

/**
 * Escribe el .csv.gz de UN mes de hechos en el almacén, en flujo: recibe una conexión ya en una transacción READ ONLY propia del mes,
 * lee la partición del mes con un cursor por lotes (fetchSize) filtrando los cargues congelados al pedir la exportación, y escribe
 * cada fila con el formato de la exportación completa ({@link Exportacion#fila}) sin tener el mes en memoria. Calcula el SHA-256 del
 * .csv.gz tal como queda en el almacén (lo que se descarga), las filas y la suma de valor_total (cuadre).
 */
@Component
class GeneradorHechosMes {

    /** Resultado de un mes. */
    record Resultado(long filas, BigDecimal valorTotal, long tamano, String sha256) {
    }

    /** Solo para pruebas: se llama al empezar cada mes, antes de la primera sentencia (antes de la foto de la transacción). */
    volatile java.util.function.Consumer<YearMonth> alEmpezar = m -> { };
    /** Solo para pruebas: se llama tras escribir el encabezado de cada mes (la de falla corta un mes con el archivo ya abierto). */
    volatile java.util.function.Consumer<YearMonth> trasEncabezado = m -> { };

    private final Almacen almacen;

    GeneradorHechosMes(Almacen almacen) {
        this.almacen = almacen;
    }

    /** SELECT de las columnas exportadas: las de los hechos con el alias d y el SHA-256 del cargue de origen, de los cargues congelados en la solicitud. */
    static String select() {
        List<String> cols = AnaliticaExportacionHechos.COLUMNAS.stream().map(c -> "cargue_sha256".equals(c) ? "c.sha256 AS cargue_sha256" : "d." + c).toList();
        return "SELECT " + String.join(", ", cols) + """
                 FROM ana_dispensaciones d JOIN ana_cargues c ON c.id = d.cargue_id
                WHERE d.fecha >= ? AND d.fecha < ? AND d.cargue_id = ANY(?)
                ORDER BY d.id""";
    }

    /**
     * Comprueba que cada columna de ana_dispensaciones esté decidida: exportada o en {@link AnaliticaExportacionHechos#FUERA}. Una columna
     * nueva sin decidir hace fallar la exportación (falla cerrado: podría ser un dato personal en claro).
     */
    static void verificarColumnas(Connection c) throws SQLException {
        Set<String> tabla = new HashSet<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'ana_dispensaciones'")) {
            while (rs.next()) {
                tabla.add(rs.getString(1));
            }
        }
        Set<String> decididas = new HashSet<>(AnaliticaExportacionHechos.COLUMNAS);
        decididas.addAll(AnaliticaExportacionHechos.FUERA);
        decididas.remove("cargue_sha256");
        Set<String> sinDecidir = new HashSet<>(tabla);
        sinDecidir.removeAll(decididas);
        Set<String> faltan = new HashSet<>(decididas);
        faltan.removeAll(tabla);
        if (!sinDecidir.isEmpty() || !faltan.isEmpty()) {
            throw new IllegalStateException("Columnas de ana_dispensaciones sin decidir para la exportación: " + sinDecidir + "; inexistentes: " + faltan);
        }
    }

    /**
     * @param conexion en una transacción de solo lectura (no se confirma aquí)
     * @param cargues  cargues visibles y confirmados al pedir la exportación (los mismos para todos los meses)
     * @param ruta     ruta nueva del .csv.gz en el almacén
     * @param limite   instante en que el mes se aborta
     */
    Resultado generar(Connection conexion, YearMonth mes, Long[] cargues, String ruta, int lote, Instant limite) throws IOException, SQLException {
        alEmpezar.accept(mes);
        long restanteMs = Math.max(1000, limite.toEpochMilli() - Instant.now().toEpochMilli());
        try (Statement s = conexion.createStatement()) {
            // Ninguna sentencia sola pasa del tope del mes (la revisión entre lotes no alcanza a una consulta que no devuelve nada)
            s.execute("SET LOCAL statement_timeout = " + restanteMs);
        }
        verificarColumnas(conexion);
        long filas = 0;
        BigDecimal valor = BigDecimal.ZERO;
        int iValor = AnaliticaExportacionHechos.COLUMNAS.indexOf("valor_total");
        Contador contador;
        DigestOutputStream huella;
        try (OutputStream archivo = almacen.escribir(ruta)) {
            contador = new Contador(archivo);
            huella = new DigestOutputStream(contador, sha256());
            try (GZIPOutputStream gz = new GZIPOutputStream(huella, 1 << 16);
                 Writer w = new BufferedWriter(new OutputStreamWriter(gz, StandardCharsets.UTF_8), 1 << 16);
                 PreparedStatement ps = conexion.prepareStatement(select(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                w.write('﻿');
                w.write(Exportacion.filaTexto(AnaliticaExportacionHechos.COLUMNAS));
                trasEncabezado.accept(mes);
                ps.setFetchSize(lote); // con la transacción abierta, el controlador de PostgreSQL usa un cursor y trae de a «lote» filas
                ps.setObject(1, mes.atDay(1));
                ps.setObject(2, mes.plusMonths(1).atDay(1));
                ps.setArray(3, conexion.createArrayOf("bigint", cargues));
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    int[] tipos = new int[n];
                    for (int i = 0; i < n; i++) {
                        tipos[i] = md.getColumnType(i + 1);
                    }
                    Object[] valores = new Object[n];
                    List<Object> fila = Arrays.asList(valores);
                    while (rs.next()) {
                        for (int i = 0; i < n; i++) {
                            valores[i] = valor(rs, i + 1, tipos[i]);
                        }
                        w.write(Exportacion.fila(fila));
                        valor = valor.add((BigDecimal) valores[iValor]);
                        if (++filas % lote == 0) {
                            if (Instant.now().isAfter(limite)) {
                                throw new LimiteExcedido();
                            }
                        }
                    }
                }
            }
        }
        return new Resultado(filas, valor, contador.bytes, HexFormat.of().formatHex(huella.getMessageDigest().digest()));
    }

    /** Valor de una celda según su tipo JDBC (mismo criterio que la exportación completa: numeric sin redondear, fechas como fecha). */
    private static Object valor(ResultSet rs, int i, int tipo) throws SQLException {
        return switch (tipo) {
            case Types.NUMERIC, Types.DECIMAL -> rs.getBigDecimal(i);
            case Types.DATE -> rs.getObject(i, LocalDate.class);
            case Types.TIMESTAMP_WITH_TIMEZONE -> rs.getObject(i, OffsetDateTime.class);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.OTHER -> rs.getString(i);
            default -> rs.getObject(i); // smallint, bigint, boolean
        };
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no tiene SHA-256", e);
        }
    }

    /** El mes pasó su tope de tiempo. */
    static final class LimiteExcedido extends RuntimeException {
        LimiteExcedido() {
            super("El mes superó el límite de tiempo de la exportación de hechos");
        }
    }

    /** Cuenta los bytes del .csv.gz (su tamaño en el almacén). */
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

        @Override
        public void close() throws IOException {
            out.flush(); // lo cierra quien abrió el archivo del almacén (try-with-resources de generar)
        }
    }
}
