package co.com.agsamericas.digsa.plataforma;

/** Se encoló un correo o un SMS: al confirmar la transacción despierta al despachador para enviarlo en segundos. */
public record SalidaEncolada(String canal, long id) {
}
