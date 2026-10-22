package co.com.agsamericas.digsa.plataforma;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Puerto de almacenamiento de archivos (evidencias, actas). Adaptador local en disco; Azure Blob Storage (o el almacenamiento del
 * datacenter en Colombia, duda 16.1) entra como otro adaptador sin cambiar a quien guarda.
 */
public interface Almacen {

    /** Guarda el contenido en la ruta relativa (que debe ser nueva). */
    void guardar(String ruta, byte[] contenido) throws IOException;

    InputStream leer(String ruta) throws IOException;

    /**
     * Abre una ruta nueva para escribirla en flujo, sin tener el contenido en memoria (la exportación completa, de varios GB). Quien la
     * abre la cierra; si la operación falla, la descarta.
     */
    OutputStream escribir(String ruta) throws IOException;

    /** Borra un archivo recién escrito cuando la operación que lo contenía falló (todo o nada). Nunca lanza. */
    void descartar(String ruta);
}
