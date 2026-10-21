-- Suite DIGSA · Fase 7: seguimiento al plan de mejoramiento (docs/especificaciones/seguimiento), con las decisiones 6.x, 17, 24, 30 y
-- la 12.1 del 2026-10-20 (prórroga «solicitada» hasta que la aprueban el coordinador y la DIGSA).

-- Cadena de seguimientos: cada visita de seguimiento apunta a la visita que verifica
ALTER TABLE visitas ADD COLUMN origen_id bigint REFERENCES visitas (id) ON DELETE RESTRICT,
    ADD COLUMN seguimiento_n smallint CHECK (seguimiento_n >= 1),
    ADD CONSTRAINT visitas_seguimiento_check CHECK ((origen_id IS NULL) = (seguimiento_n IS NULL)),
    ADD CONSTRAINT visitas_origen_distinto CHECK (origen_id IS NULL OR origen_id <> id),
    ADD CONSTRAINT visitas_seguimiento_tipo CHECK (origen_id IS NULL OR tipo = 'plan');
-- Un solo seguimiento vigente por visita de origen
CREATE UNIQUE INDEX ux_visitas_origen_vigente ON visitas (origen_id) WHERE origen_id IS NOT NULL AND estado <> 'cancelada';
CREATE INDEX ix_visitas_origen ON visitas (origen_id);

-- El seguimiento parte del plan anterior con el quién y el cómo que remitió el OPL (solo lectura para el auditor) y guarda la
-- justificación de la prórroga de esa visita (no pasa al siguiente seguimiento)
ALTER TABLE visitas_plan ADD COLUMN quien text CHECK (char_length(quien) <= 200),
    ADD COLUMN como text CHECK (char_length(como) <= 4000),
    ADD COLUMN prorroga_justificacion text CHECK (char_length(prorroga_justificacion) <= 2000);

-- Prórrogas: al enviar el acta del seguimiento cada una queda «solicitada»; pasa a «aprobada» con el coordinador y la DIGSA (12.1)
CREATE TABLE prorrogas (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id        bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,      -- el seguimiento
    origen_visita_id bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    acta_id          bigint NOT NULL REFERENCES visitas_actas (id) ON DELETE RESTRICT,
    clave            text NOT NULL CHECK (clave ~ '^(ob:OB-[A-Z0-9-]{1,20}|it:[A-Z0-9][A-Z0-9-]{1,15})$'),
    codigo           text NOT NULL,
    fin_anterior     date NOT NULL,
    fin_nuevo        date NOT NULL CHECK (fin_nuevo > fin_anterior),
    justificacion    text NOT NULL CHECK (char_length(btrim(justificacion)) BETWEEN 10 AND 2000),
    estado           text NOT NULL DEFAULT 'solicitada' CHECK (estado IN ('solicitada', 'aprobada', 'rechazada')),
    coord_usuario_id bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    coord_decision   text CHECK (coord_decision IN ('aprueba', 'rechaza')),
    coord_fecha      timestamptz,
    digsa_usuario_id bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    digsa_decision   text CHECK (digsa_decision IN ('aprueba', 'rechaza')),
    digsa_fecha      timestamptz,
    motivo_rechazo   text CHECK (char_length(motivo_rechazo) <= 800),
    creado           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (visita_id, clave),
    CHECK ((coord_decision IS NULL) = (coord_fecha IS NULL)),
    CHECK ((digsa_decision IS NULL) = (digsa_fecha IS NULL)),
    CHECK (estado <> 'aprobada' OR (coord_decision = 'aprueba' AND digsa_decision = 'aprueba')),
    CHECK (estado <> 'rechazada' OR motivo_rechazo IS NOT NULL),
    -- Cada lado lo decide una persona distinta (12.1)
    CHECK (coord_usuario_id IS NULL OR digsa_usuario_id IS NULL OR coord_usuario_id <> digsa_usuario_id)
);
CREATE INDEX ix_prorrogas_solicitadas ON prorrogas (creado DESC) WHERE estado = 'solicitada';
CREATE INDEX ix_prorrogas_origen ON prorrogas (origen_visita_id);
CREATE INDEX ix_prorrogas_acta ON prorrogas (acta_id);
CREATE INDEX ix_prorrogas_coord ON prorrogas (coord_usuario_id);
CREATE INDEX ix_prorrogas_digsa ON prorrogas (digsa_usuario_id);

-- El reporte formal a la DIGSA también por plan incumplido (al llegar al máximo de seguimientos con acciones abiertas)
-- (reportes_digsa.motivo ya admite 'plan_incumplido')

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON prorrogas TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
