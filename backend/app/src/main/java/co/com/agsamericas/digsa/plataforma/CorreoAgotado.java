package co.com.agsamericas.digsa.plataforma;

/**
 * Un correo de la bandeja agotó sus intentos y era el aviso de una alerta crítica: el módulo de alertas dispara «correo_agotado»
 * (con SMS). Solo identificadores: el origen es el tipo de aviso (p. ej. «alerta:almacen_no_disponible»).
 */
public record CorreoAgotado(long correoId, String origen) {
}
