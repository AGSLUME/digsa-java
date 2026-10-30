-- Suite DIGSA · Módulo Correos, fase C1: bitácora de envíos (pedido del usuario del 2026-10-29; dudas CO-2 y CO-3).
-- correos_salida pasa a ser la bitácora de TODO correo que envía la aplicación: lo encolado (bandeja de salida) y los envíos
-- directos (MFA, enlaces de contraseña, código del QR del OPL, notificación previa, acta, reportes…), que se registran con
-- sincrono = true en una transacción propia. El despachador nunca toma una fila directa.

ALTER TABLE correos_salida
    ADD COLUMN sincrono               boolean NOT NULL DEFAULT false,   -- envío directo: ya salió (o falló) al registrarse
    ADD COLUMN enmascarado            boolean NOT NULL DEFAULT false,   -- el html guardado oculta el código o el enlace (MFA, clave, QR del OPL)
    ADD COLUMN reintentable           boolean NOT NULL DEFAULT true,
    ADD COLUMN motivo_no_reintentable text CHECK (char_length(motivo_no_reintentable) <= 200),
    ADD COLUMN reintento_de           bigint REFERENCES correos_salida (id) ON DELETE SET NULL,
    ADD COLUMN motivo_reintento       text CHECK (char_length(motivo_reintento) <= 300),
    ADD COLUMN reintentado_por        bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_correos_salida_reintentable CHECK (reintentable OR motivo_no_reintentable IS NOT NULL),
    ADD CONSTRAINT ck_correos_salida_secreto CHECK (NOT enmascarado OR NOT reintentable),       -- un código enmascarado no se reenvía
    ADD CONSTRAINT ck_correos_salida_reintento CHECK ((reintento_de IS NULL) = (motivo_reintento IS NULL));

-- Un fallido se reintenta una sola vez: el reintento es una fila nueva (si vuelve a fallar, se reintenta esa)
CREATE UNIQUE INDEX ux_correos_salida_reintento_de ON correos_salida (reintento_de) WHERE reintento_de IS NOT NULL;
CREATE INDEX ix_correos_salida_origen_creado ON correos_salida (origen, creado DESC);
CREATE INDEX ix_correos_salida_estado_creado ON correos_salida (estado, creado DESC);
CREATE INDEX ix_correos_salida_reintentado_por ON correos_salida (reintentado_por) WHERE reintentado_por IS NOT NULL;
-- Envíos directos que quedaron en «enviando» (la aplicación cayó a mitad del envío): el respaldo los cierra como fallidos
CREATE INDEX ix_correos_salida_directos_enviando ON correos_salida (creado) WHERE sincrono AND estado = 'enviando';
