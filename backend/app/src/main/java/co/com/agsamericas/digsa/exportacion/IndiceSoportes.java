package co.com.agsamericas.digsa.exportacion;

import co.com.agsamericas.digsa.reglas.Exportacion;
import co.com.agsamericas.digsa.reglas.Exportacion.Verificacion;
import java.io.IOException;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.util.Arrays;

/**
 * soportes/indice.csv (mismo formato de los CSV): una fila por archivo con la huella guardada y la calculada al copiarlo. Se escribe
 * fila por fila en el escritor que se le dé (un temporal en la exportación completa), sin acumular las filas en memoria.
 */
public final class IndiceSoportes {

    /** Un soporte del índice. {@code sha256} es la huella guardada en la base (null en las firmas, que no la tienen). */
    public record Soporte(String visita, String punto, String carpeta, String tabla, Long id, String clave, String nombreOriginal,
                          String tipo, Long tamano, String sha256, String subidoPor, OffsetDateTime subido, OffsetDateTime eliminado) {
    }

    private final Writer salida;
    private long total;
    private long distintas;
    private long noEncontrados;

    public IndiceSoportes(Writer salida) throws IOException {
        this.salida = salida;
        salida.write('﻿');
        salida.write(Exportacion.filaTexto(Exportacion.COLUMNAS_INDICE));
    }

    /**
     * Agrega la fila del soporte.
     *
     * @param rutaZip   dónde quedó en el ZIP (se deja vacía si no se encontró)
     * @param calculada SHA-256 calculado al copiarlo, o null si el almacén no lo tiene
     */
    public Verificacion agregar(Soporte s, String rutaZip, String calculada) throws IOException {
        Verificacion v = Exportacion.verificar(s.sha256(), calculada);
        total++;
        if (v == Verificacion.DISTINTA) {
            distintas++;
        } else if (v == Verificacion.NO_ENCONTRADO) {
            noEncontrados++;
        }
        salida.write(Exportacion.fila(Arrays.asList(s.visita(), s.punto(), s.carpeta(), s.tabla(), s.id(), s.clave(), s.nombreOriginal(),
                calculada == null ? null : rutaZip, s.tipo(), s.tamano(), s.sha256() == null ? null : s.sha256().strip(), calculada, v.texto(),
                s.subidoPor(), s.subido(), s.eliminado())));
        return v;
    }

    public long total() {
        return total;
    }

    public long distintas() {
        return distintas;
    }

    public long noEncontrados() {
        return noEncontrados;
    }

    /** Detalle del evento de integridad: solo conteos, sin nombres ni datos personales. */
    public String detalleIntegridad(String origen) {
        return origen + ": " + distintas + " archivo(s) con huella distinta y " + noEncontrados + " no encontrado(s) en el almacén";
    }
}
