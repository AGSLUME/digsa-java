-- Suite DIGSA · módulo de reportes, ajustes de la fase R3 (revisión): el cupo por usuario del diferido se cuenta en cada solicitud,
-- así que va con su índice; y dos reglas de coherencia de los estados. Las reglas entran NOT VALID y se validan aparte: la validación
-- no bloquea las escrituras de la tabla mientras revisa las filas que ya hay.

-- Generaciones en cola o en curso de un usuario (tope por usuario)
CREATE INDEX ix_rg_en_curso ON reportes_generados (usuario_id) WHERE estado IN ('pendiente', 'generando');

-- Lo que se está generando tiene su inicio (la limpieza repone las interrumpidas por él)
ALTER TABLE reportes_generados ADD CONSTRAINT ck_rg_generando_iniciado CHECK (estado <> 'generando' OR iniciado IS NOT NULL) NOT VALID;
-- Lo que terminó (lista, fallida o vencida) tiene su fin
ALTER TABLE reportes_generados ADD CONSTRAINT ck_rg_terminado CHECK (estado NOT IN ('lista', 'fallida', 'vencida') OR terminado IS NOT NULL) NOT VALID;

ALTER TABLE reportes_generados VALIDATE CONSTRAINT ck_rg_generando_iniciado;
ALTER TABLE reportes_generados VALIDATE CONSTRAINT ck_rg_terminado;
