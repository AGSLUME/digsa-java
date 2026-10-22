-- Suite DIGSA · fase 10: exportación completa de los datos, diferida (docs/especificaciones/exportacion-alertas, contradicción 6).
-- El administrador la pide; una tarea en segundo plano (una a la vez, candado de PostgreSQL) escribe el ZIP en el almacén, en flujo
-- y desde una sola foto de la base; queda lista para descargar hasta que vence (datos personales, Ley 1581) y una limpieza la borra.

CREATE TABLE exportaciones (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    solicitada_por    bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    estado            text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'generando', 'lista', 'fallida', 'vencida')),
    ruta              text UNIQUE CHECK (ruta ~ '^[A-Za-z0-9_/-]+\.zip$'),     -- en el almacén; el archivo se borra al fallar o vencer
    tamano            bigint CHECK (tamano >= 0),
    tablas            int,
    filas             bigint,
    soportes          int,
    huellas_distintas int,
    no_encontrados    int,
    sha256            char(64),                                                -- del ZIP completo
    error             text CHECK (char_length(error) <= 200),                  -- mensaje genérico, sin detalle interno
    creado            timestamptz NOT NULL DEFAULT now(),
    iniciado          timestamptz,
    terminado         timestamptz,
    vence             timestamptz,
    CHECK (estado <> 'lista' OR (ruta IS NOT NULL AND sha256 IS NOT NULL AND vence IS NOT NULL))
);
-- Una sola pedida en curso por usuario
CREATE UNIQUE INDEX uq_exportaciones_en_curso ON exportaciones (solicitada_por) WHERE estado IN ('pendiente', 'generando');
CREATE INDEX ix_exportaciones_estado ON exportaciones (estado, id);
CREATE INDEX ix_exportaciones_usuario ON exportaciones (solicitada_por, id DESC);

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON exportaciones TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
