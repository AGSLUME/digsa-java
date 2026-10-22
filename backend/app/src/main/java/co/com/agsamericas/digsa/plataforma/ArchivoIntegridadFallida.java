package co.com.agsamericas.digsa.plataforma;

/**
 * Un archivo no coincide con su huella SHA-256 o falta en el almacén (exportación, acta). El módulo de alertas dispara
 * «integridad_archivo». El detalle lleva solo identificadores (tabla, id, visita), nunca datos personales.
 */
public record ArchivoIntegridadFallida(String detalle) {
}
