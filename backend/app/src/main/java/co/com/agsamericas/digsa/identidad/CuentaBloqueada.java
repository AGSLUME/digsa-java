package co.com.agsamericas.digsa.identidad;

import java.time.Instant;

/**
 * Una cuenta quedó bloqueada por intentos fallidos (Anexo 5, 3.1). Sin datos del usuario: el módulo de alertas solo cuenta cuántas se
 * bloquearon en la última hora para la alerta de bloqueo masivo (posible ataque).
 *
 * @param cuando        hora del bloqueo
 * @param bloqueadaHasta hasta cuándo queda bloqueada (la duración del bloqueo es la diferencia)
 */
public record CuentaBloqueada(Instant cuando, Instant bloqueadaHasta) {
}
