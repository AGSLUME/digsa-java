package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Causa;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV o TXT en flujo con el delimitador detectado: comillas dobles (una celda entre comillas puede tener el delimitador, saltos de línea y
 * "" escapadas), CR LF o LF. Lleva la línea física donde empieza cada registro (la «línea de origen» de los rechazos). No guarda más que
 * el registro en curso, con un tope por celda y por registro: una comilla sin cerrar se tragaría el resto del archivo como una sola
 * celda (registros perdidos sin rechazo y la memoria agotada); al pasar el tope, o al llegar al final con la comilla abierta, el archivo
 * se rechaza por estructura con la línea donde empezó el registro.
 */
final class LectorCsv {

    /** Topes por defecto: 64 KB por celda y 1 MB por registro (en caracteres). */
    static final int CELDA_MAX = 64 * 1024;
    static final int REGISTRO_MAX = 1024 * 1024;
    private static final int FIN = -1;

    private final Reader in;
    private final char delimitador;
    private final int celdaMax;
    private final int registroMax;
    private final char[] buffer = new char[1 << 16];
    private int pos;
    private int largo;
    /** Línea física actual (1 = primera línea del archivo). */
    private long lineaActual = 1;
    private long lineaRegistro;
    private final StringBuilder celda = new StringBuilder(64);

    LectorCsv(Reader in, char delimitador) {
        this(in, delimitador, CELDA_MAX, REGISTRO_MAX);
    }

    LectorCsv(Reader in, char delimitador, int celdaMax, int registroMax) {
        this.in = in;
        this.delimitador = delimitador;
        this.celdaMax = celdaMax;
        this.registroMax = registroMax;
    }

    /** Línea física donde empezó el último registro devuelto. */
    long linea() {
        return lineaRegistro;
    }

    private int leer() throws IOException {
        if (pos >= largo) {
            largo = in.read(buffer, 0, buffer.length);
            pos = 0;
            if (largo <= 0) {
                largo = 0;
                return FIN;
            }
        }
        return buffer[pos++];
    }

    private int mirar() throws IOException {
        if (pos >= largo) {
            largo = in.read(buffer, 0, buffer.length);
            pos = 0;
            if (largo <= 0) {
                largo = 0;
                return FIN;
            }
        }
        return buffer[pos];
    }

    private ErrorEstructura demasiadoLargo(boolean comillas) {
        String l = Long.toString(lineaRegistro);
        String que = comillas ? "Comilla sin cerrar o celda demasiado larga" : "Registro demasiado largo";
        return new ErrorEstructura(Causa.FORMATO, que + " (más de " + (comillas ? celdaMax : registroMax) / 1024 + " KB) en el registro que empieza "
                + "en la línea " + l, l, 1);
    }

    /**
     * Siguiente registro con sus celdas, o null al final. Una línea en blanco devuelve una lista vacía (conserva la numeración; quien
     * lee la salta).
     *
     * @throws ErrorEstructura si una comilla no se cierra o una celda o el registro pasan del tope
     */
    List<String> siguiente() throws IOException {
        int c = leer();
        if (c == FIN) {
            return null;
        }
        lineaRegistro = lineaActual;
        List<String> celdas = new ArrayList<>(24);
        celda.setLength(0);
        boolean comillas = false;
        boolean hayDatos = false;
        long enRegistro = 0;
        while (true) {
            if (c == FIN) {
                if (comillas) {
                    String l = Long.toString(lineaRegistro);
                    throw new ErrorEstructura(Causa.FORMATO, "Comilla sin cerrar en el registro que empieza en la línea " + l, l, 1);
                }
                if (hayDatos || celda.length() > 0) {
                    celdas.add(celda.toString());
                }
                return celdas;
            }
            if (++enRegistro > registroMax) {
                throw demasiadoLargo(false);
            }
            char ch = (char) c;
            if (comillas) {
                if (ch == '"') {
                    if (mirar() == '"') {
                        leer();
                        celda.append('"');
                    } else {
                        comillas = false;
                    }
                } else {
                    if (ch == '\n') {
                        lineaActual++;
                    }
                    celda.append(ch);
                }
                if (celda.length() > celdaMax) {
                    throw demasiadoLargo(true);
                }
            } else if (ch == '"' && celda.length() == 0) {
                comillas = true;
                hayDatos = true;
            } else if (ch == delimitador) {
                celdas.add(celda.toString());
                celda.setLength(0);
                hayDatos = true;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && mirar() == '\n') {
                    leer();
                }
                lineaActual++;
                if (hayDatos || celda.length() > 0) {
                    celdas.add(celda.toString());
                }
                return celdas;
            } else {
                celda.append(ch);
                hayDatos = true;
                if (celda.length() > celdaMax) {
                    throw demasiadoLargo(true);
                }
            }
            c = leer();
        }
    }
}
