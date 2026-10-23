package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Campo;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;

/**
 * Normaliza cada registro según el mapeo confirmado, seudonimiza al paciente y al profesional (antes de tocar la base: el documento en
 * claro nunca llega a staging), descarta los datos personales y escribe en ana_staging_dispensacion con COPY (CopyManager de pgjdbc),
 * en flujo. Los valores que no se pueden leer (fecha, número) se dejan tal cual para que la validación por conjuntos los rechace con su
 * causa; una fecha inválida va con el prefijo «~» para que nunca parezca canónica.
 */
final class CopiadorStaging implements Closeable {

    /** Columnas del COPY, en orden. */
    static final List<String> COLUMNAS = List.of("cargue_id", "linea", "linea_origen", "campos_distintos", "paciente_seudo", "anio_nacimiento",
            "grupo_edad", "fuerza", "esm", "modalidad", "fecha", "medicamento", "cum", "cum_original", "principio_activo", "atc", "cie10",
            "cantidad", "valor", "valor_unitario", "codigo_ssfm", "profesional_seudo", "especialidad", "tipo_formula", "numero_formula",
            "id_dispensacion", "item", "regional", "huella", "llave");

    private final PGCopyOutputStream copia;
    private final Writer salida;
    private final long cargueId;
    private final List<Campo> mapeo;
    private final Map<Campo, Integer> indice = new EnumMap<>(Campo.class);
    private final int columnas;
    private final boolean comaDecimal;
    private final String fuente;
    private final Seudonimizador seudo;
    private final String[] fila = new String[COLUMNAS.size()];
    private long filas;
    private long malFormadas;
    private String primeraMalFormada;

    /**
     * @param conexion    conexión en modo autocommit (el COPY es su propia transacción); el cargue ya tiene su partición de staging
     * @param mapeo       campo por columna del encabezado
     * @param fuente      archivo o ws_opl (entra en la llave natural)
     */
    CopiadorStaging(Connection conexion, long cargueId, List<Campo> mapeo, boolean comaDecimal, String fuente, Seudonimizador seudo)
            throws SQLException {
        this.cargueId = cargueId;
        this.mapeo = List.copyOf(mapeo);
        this.columnas = mapeo.size();
        this.comaDecimal = comaDecimal;
        this.fuente = fuente;
        this.seudo = seudo;
        for (int i = 0; i < mapeo.size(); i++) {
            Campo c = mapeo.get(i);
            if (c != null && c.seGuarda()) {
                indice.putIfAbsent(c, i);
            }
        }
        PGConnection pg = conexion.unwrap(PGConnection.class);
        String sql = "COPY ana_staging_dispensacion (" + String.join(", ", COLUMNAS) + ") FROM STDIN (FORMAT text)";
        this.copia = new PGCopyOutputStream(pg, sql, 1 << 16);
        this.salida = new BufferedWriter(new OutputStreamWriter(copia, StandardCharsets.UTF_8), 1 << 16);
    }

    long filas() {
        return filas;
    }

    long malFormadas() {
        return malFormadas;
    }

    String primeraMalFormada() {
        return primeraMalFormada;
    }

    /** Valores normalizados ya vistos por columna (Fuerza, ESM, modalidad, CUM, ATC, CIE-10, SSFM y fecha se repiten mucho). */
    @SuppressWarnings("unchecked")
    private final Map<String, String>[] memos = new Map[8];
    private static final int MAX_MEMO = 200_000;

    private String memo(int i, String valor, java.util.function.UnaryOperator<String> f) {
        if (valor == null) {
            return null;
        }
        Map<String, String> m = memos[i];
        if (m == null) {
            m = memos[i] = new java.util.HashMap<>();
        }
        String r = m.get(valor);
        if (r == null && !m.containsKey(valor)) {
            r = f.apply(valor);
            if (m.size() < MAX_MEMO) {
                m.put(valor, r);
            }
        }
        return r;
    }

    private String celda(List<String> celdas, Campo c) {
        Integer i = indice.get(c);
        return i == null || i >= celdas.size() ? null : AnaliticaIngesta.texto(celdas.get(i));
    }

    /** Un registro del insumo: se normaliza y se escribe en el COPY. */
    void agregar(long orden, String lineaOrigen, List<String> celdas) throws IOException {
        int n = celdas.size();
        while (n > columnas && celdas.get(n - 1).isBlank()) {
            n--; // celdas vacías de más al final (un «;» sobrante) no cuentan
        }
        boolean distintos = n != columnas;
        if (distintos) {
            // Columnas corridas: un «;» dentro del nombre o una columna faltante llevaría el nombre, el documento o la fecha de nacimiento
            // a una columna que se guarda (fuerza, medicamento...). La fila solo deja su línea: se rechaza entera por campos distintos.
            malFormadas++;
            if (primeraMalFormada == null) {
                primeraMalFormada = lineaOrigen;
            }
            java.util.Arrays.fill(fila, null);
            fila[0] = Long.toString(cargueId);
            fila[1] = Long.toString(orden);
            fila[2] = lineaOrigen;
            fila[3] = "t";
            String h = AnaliticaIngesta.huella(List.of("mal_formada", fuente, Long.toString(orden)));
            fila[28] = h;
            fila[29] = h;
            escribir();
            filas++;
            return;
        }
        String pacienteSeudo = seudo.paciente(celda(celdas, Campo.TIPO_DOCUMENTO), celda(celdas, Campo.PACIENTE));
        String profesionalSeudo = celdaOpcional(celdas, Campo.PROFESIONAL) == null ? null : seudo.profesional(celda(celdas, Campo.PROFESIONAL));
        String anio = anio(celdas);
        String cumOriginal = celda(celdas, Campo.CUM);
        String fechaTexto = celda(celdas, Campo.FECHA);
        String fecha = memo(7, fechaTexto, v -> AnaliticaIngesta.fecha(v).map(Object::toString).orElse("~" + v));

        fila[0] = Long.toString(cargueId);
        fila[1] = Long.toString(orden);
        fila[2] = lineaOrigen;
        fila[3] = distintos ? "t" : "f";
        fila[4] = pacienteSeudo;
        fila[5] = anio;
        fila[6] = celda(celdas, Campo.GRUPO_EDAD);
        fila[7] = memo(0, celda(celdas, Campo.FUERZA), AnaliticaIngesta::fuerza);
        fila[8] = memo(1, celda(celdas, Campo.ESM), v -> AnaliticaIngesta.normalizarCodigo(v, false));
        fila[9] = memo(2, celda(celdas, Campo.MODALIDAD), AnaliticaIngesta::modalidad);
        fila[10] = fecha;
        fila[11] = celda(celdas, Campo.MEDICAMENTO);
        fila[12] = memo(3, cumOriginal, AnaliticaIngesta::normalizarCum);
        fila[13] = cumOriginal;
        fila[14] = celda(celdas, Campo.PRINCIPIO_ACTIVO);
        fila[15] = memo(4, celda(celdas, Campo.ATC), v -> AnaliticaIngesta.normalizarCodigo(v, true));
        fila[16] = memo(5, celda(celdas, Campo.CIE10), v -> AnaliticaIngesta.normalizarCodigo(v, true));
        fila[17] = numero(celda(celdas, Campo.CANTIDAD));
        fila[18] = numero(celda(celdas, Campo.VALOR));
        fila[19] = numero(celda(celdas, Campo.VALOR_UNITARIO));
        fila[20] = memo(6, celda(celdas, Campo.CODIGO_SSFM), v -> AnaliticaIngesta.normalizarCodigo(v, false));
        fila[21] = profesionalSeudo;
        fila[22] = celda(celdas, Campo.ESPECIALIDAD);
        fila[23] = celda(celdas, Campo.TIPO_FORMULA);
        fila[24] = celda(celdas, Campo.NUMERO_FORMULA);
        fila[25] = celda(celdas, Campo.ID_DISPENSACION);
        fila[26] = celda(celdas, Campo.ITEM);
        fila[27] = celda(celdas, Campo.REGIONAL);
        List<String> valores = valoresHuella(celdas, pacienteSeudo, profesionalSeudo, anio);
        fila[28] = AnaliticaIngesta.huella(valores);
        fila[29] = AnaliticaIngesta.llaveNatural(fuente, fila[25], fila[26], valores);
        escribir();
        filas++;
    }

    private String celdaOpcional(List<String> celdas, Campo c) {
        return indice.containsKey(c) ? celda(celdas, c) : null;
    }

    private String anio(List<String> celdas) {
        String a = celda(celdas, Campo.ANIO_NACIMIENTO);
        if (a != null) {
            return a.matches("^\\d{4}$") ? a : null;
        }
        String f = celda(celdas, Campo.FECHA_NACIMIENTO);
        return f == null ? null : AnaliticaIngesta.anioNacimiento(f).map(String::valueOf).orElse(null);
    }

    /** Número canónico sin ceros de más a la derecha («2,5000» ⇒ «2.5»): los decimales que cuenta la validación son los reales. */
    private String numero(String texto) {
        if (texto == null) {
            return null;
        }
        return AnaliticaIngesta.numero(texto, comaDecimal).map(b -> b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString())
                .orElse(texto.strip());
    }

    /**
     * Valores de la fila para el duplicado exacto: todas las columnas mapeadas en su orden, con el paciente y el profesional por su
     * seudónimo (nunca el documento) y la fecha de nacimiento por su año. El tipo de documento ya va dentro del seudónimo.
     */
    private List<String> valoresHuella(List<String> celdas, String pacienteSeudo, String profesionalSeudo, String anio) {
        List<String> v = new ArrayList<>(columnas);
        for (int i = 0; i < columnas; i++) {
            Campo c = mapeo.get(i);
            if (c == null || !c.seGuarda() || c == Campo.TIPO_DOCUMENTO || indice.get(c) != i) {
                continue;
            }
            v.add(switch (c) {
                case PACIENTE -> pacienteSeudo;
                case PROFESIONAL -> profesionalSeudo;
                case FECHA_NACIMIENTO, ANIO_NACIMIENTO -> anio;
                default -> i < celdas.size() ? AnaliticaIngesta.texto(celdas.get(i)) : null;
            });
        }
        return v;
    }

    private void escribir() throws IOException {
        for (int i = 0; i < fila.length; i++) {
            if (i > 0) {
                salida.write('\t');
            }
            String v = fila[i];
            if (v == null) {
                salida.write("\\N");
                continue;
            }
            for (int k = 0; k < v.length(); k++) {
                char ch = v.charAt(k);
                switch (ch) {
                    case '\\' -> salida.write("\\\\");
                    case '\t' -> salida.write("\\t");
                    case '\n' -> salida.write("\\n");
                    case '\r' -> salida.write("\\r");
                    default -> salida.write(ch);
                }
            }
        }
        salida.write('\n');
    }

    /** Aborta el COPY tras una falla (nada queda escrito); nunca lanza. */
    void cancelar() {
        try {
            if (copia.isActive()) {
                copia.cancelCopy();
            }
        } catch (SQLException e) {
            // la conexión se descarta igual
        }
    }

    /** Termina el COPY (confirma las filas). */
    @Override
    public void close() throws IOException {
        salida.close();
    }
}
