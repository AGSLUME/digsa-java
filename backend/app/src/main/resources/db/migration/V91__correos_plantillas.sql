-- Suite DIGSA · Módulo Correos, fase C2: plantillas editables (pedido del usuario del 2026-10-29).
-- El texto de fábrica vive en el código. Cada edición o restauración es una fila nueva: la vigente es la última de su clave y
-- «Restaurar original» agrega una fila sin texto (restaurada = true). Nada se borra: el historial es la evidencia de quién cambió qué.

CREATE TABLE correo_plantillas_versiones (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    clave       text        NOT NULL CHECK (clave ~ '^[a-z0-9_]{1,60}$'),
    restaurada  boolean     NOT NULL DEFAULT false,
    asunto      text CHECK (char_length(asunto) <= 200),
    cuerpo      text CHECK (char_length(cuerpo) <= 10000),
    motivo      text        NOT NULL CHECK (char_length(motivo) BETWEEN 10 AND 300),
    usuario_id  bigint      NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    creado      timestamptz NOT NULL,
    CONSTRAINT ck_correo_plantillas_texto CHECK (restaurada = (asunto IS NULL) AND restaurada = (cuerpo IS NULL))
);

CREATE INDEX ix_correo_plantillas_clave ON correo_plantillas_versiones (clave, id DESC);
CREATE INDEX ix_correo_plantillas_usuario ON correo_plantillas_versiones (usuario_id);

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    -- Solo se agregan versiones: sin UPDATE ni DELETE
    EXECUTE format('GRANT SELECT, INSERT ON correo_plantillas_versiones TO %I', app);
END $$;
