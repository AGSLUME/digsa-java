package co.com.agsamericas.digsa.plataforma;

/**
 * Evento: un archivo del almacén no coincide con su huella SHA-256 o falta (exportación, descarga de soportes). Lo escucha el módulo de
 * alertas críticas (tipo integridad_archivo, GMI P77 b). El detalle no lleva datos personales: solo identificadores y conteos.
 */
public record ArchivoIntegridadFallida(String detalle) {
}
