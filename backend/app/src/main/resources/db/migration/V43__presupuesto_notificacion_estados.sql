-- Suite DIGSA · Presupuesto: la notificación formal que salió sin el buzón de la DIGSA (parámetro correo_digsa vacío) queda marcada para
-- reenviar, igual que la que salió sin el buzón de la fuerza (revisión de la F3: antes quedaba «encolada» sin aviso).
ALTER TABLE presupuesto_notificaciones DROP CONSTRAINT presupuesto_notificaciones_estado_check,
    ADD CONSTRAINT presupuesto_notificaciones_estado_check CHECK (estado IN ('encolada', 'sin_buzon_fuerza', 'sin_buzon_digsa', 'sin_destinatarios'));

-- Recálculo del saldo de una línea (suma de las versiones vigentes) sin leer las reemplazadas
CREATE INDEX ix_presupuesto_ejecucion_vigente_linea ON presupuesto_ejecucion (linea_id) INCLUDE (facturado, reconocido) WHERE vigente;
