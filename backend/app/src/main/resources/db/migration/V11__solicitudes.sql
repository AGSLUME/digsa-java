-- Suite DIGSA · Fase 5: solicitudes de visita a necesidad (decisiones 12, 31 y 33; decisiones del 2026-10-20 9.3, 9.4 y 9.6).
-- La DIGSA y la Sanidad de cada fuerza las registran con la fecha del escrito (desde ella corren los 5 días hábiles, Anexo 3 p.14);
-- el coordinador las agenda como visita a necesidad (sin previo aviso al OPL) o las anula. Referencia: PHP 005_agenda.sql.

CREATE TABLE visitas_solicitudes (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    punto_id         bigint NOT NULL REFERENCES puntos (id) ON DELETE RESTRICT,
    solicitante_id   bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    entidad          text NOT NULL CHECK (char_length(entidad) BETWEEN 2 AND 60),   -- DIGSA, DISAN EJC, DISAN ARC o JEFSA FAC
    fecha_solicitud  date NOT NULL,
    motivo           text NOT NULL CHECK (char_length(motivo) BETWEEN 10 AND 4000),
    -- Escrito escaneado (PDF, opcional): mismas reglas de los archivos (fuera de la carpeta web, nombre aleatorio, huella)
    archivo_ruta     text CHECK (archivo_ruta ~ '^[A-Za-z0-9_/-]+\.pdf$'),
    archivo_nombre   text CHECK (char_length(archivo_nombre) <= 200),
    archivo_tamano   bigint,
    archivo_sha256   char(64),
    estado           text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'programada', 'anulada')),
    visita_id        bigint UNIQUE REFERENCES visitas (id) ON DELETE RESTRICT,
    registrado       timestamptz NOT NULL DEFAULT now(),
    motivo_anulacion text CHECK (char_length(motivo_anulacion) <= 800),
    actualizado_por  bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    actualizado      timestamptz NOT NULL DEFAULT now(),
    CHECK ((estado = 'programada') = (visita_id IS NOT NULL)),
    CHECK ((archivo_ruta IS NULL) = (archivo_sha256 IS NULL))
);
CREATE INDEX ix_solicitudes_estado ON visitas_solicitudes (estado, fecha_solicitud);
CREATE INDEX ix_solicitudes_solicitante ON visitas_solicitudes (solicitante_id, registrado);
CREATE INDEX ix_solicitudes_punto ON visitas_solicitudes (punto_id);
CREATE INDEX ix_solicitudes_actualizado_por ON visitas_solicitudes (actualizado_por);

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON visitas_solicitudes TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
