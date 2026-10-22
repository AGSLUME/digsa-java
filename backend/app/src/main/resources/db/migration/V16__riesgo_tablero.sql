-- Suite DIGSA · Fase 9: riesgo y tablero (docs/especificaciones/riesgo-tablero; decisión 14, 2026-10-20 14.1 y 14.3; novena ronda 3.2).

-- Marca de intervención inmediata del OPL en el hallazgo (factor del riesgo y tarjeta del dashboard)
ALTER TABLE visitas_respuestas ADD COLUMN inmediata boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT visitas_respuestas_inmediata_nc CHECK (NOT inmediata OR calificacion = 'NC');
ALTER TABLE visitas_obligaciones ADD COLUMN inmediata boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT visitas_obligaciones_inmediata_nc CHECK (NOT inmediata OR calificacion = 'NC');
ALTER TABLE visitas_hallazgos ADD COLUMN inmediata boolean NOT NULL DEFAULT false;

-- Incidencias reportadas por la DIGSA sobre un ESM (14.1; Anexo 3 p.12): suman al riesgo mientras estén abiertas y sean del último año
CREATE TABLE riesgo_incidencias (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    punto_id       bigint NOT NULL REFERENCES puntos (id) ON DELETE RESTRICT,
    fecha          date NOT NULL,
    descripcion    text NOT NULL CHECK (char_length(btrim(descripcion)) BETWEEN 10 AND 2000),
    fuente         text CHECK (char_length(fuente) <= 200),          -- oficio, correo o radicado de la DIGSA
    estado         text NOT NULL DEFAULT 'abierta' CHECK (estado IN ('abierta', 'cerrada')),
    registrada_por bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    creado         timestamptz NOT NULL DEFAULT now(),
    cerrada_por    bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    cerrada_en     timestamptz,
    motivo_cierre  text CHECK (char_length(motivo_cierre) <= 800),
    CHECK ((estado = 'cerrada') = (cerrada_en IS NOT NULL))
);
CREATE INDEX ix_riesgo_incidencias_punto ON riesgo_incidencias (punto_id, estado, fecha);
CREATE INDEX ix_riesgo_incidencias_registrada ON riesgo_incidencias (registrada_por);
CREATE INDEX ix_riesgo_incidencias_cerrada ON riesgo_incidencias (cerrada_por);

-- Consultas del tablero: la última visita cerrada de cada punto y los planes vencidos
CREATE INDEX ix_visitas_punto_estado_fecha ON visitas (punto_id, estado, fecha DESC, id DESC);
CREATE INDEX ix_opl_planes_vencidos ON opl_planes (acta_id) WHERE estado IN ('vencido', 'reportado_digsa');

-- Pesos y umbrales del riesgo: propuesta AGS pendiente de aprobación de la DIGSA (14.1). Los edita el coordinador con bitácora
-- solo desde el dashboard de riesgo (que exige que sumen 100): en Catálogos no son editables, para no dejar una suma inválida.
INSERT INTO par_parametros (clave, valor, descripcion, fuente, editable) VALUES
  ('riesgo_peso_incumplimiento', '30', 'Riesgo: peso del incumplimiento (% de No cumple de la última visita completa)', 'Decisión 14; propuesta AGS', false),
  ('riesgo_peso_recurrencia', '20', 'Riesgo: peso de los hallazgos recurrentes', 'Decisión 14; propuesta AGS', false),
  ('riesgo_peso_inmediata', '15', 'Riesgo: peso de los hallazgos de intervención inmediata', 'Decisión 14; propuesta AGS', false),
  ('riesgo_peso_planes', '20', 'Riesgo: peso de los planes y acciones vencidos', 'Decisión 14; propuesta AGS', false),
  ('riesgo_peso_incidencias', '15', 'Riesgo: peso de las incidencias reportadas por la DIGSA (reemplaza «días sin visita», 14.1)', 'Decisión 2026-10-20 14.1', false),
  ('riesgo_nivel_alto', '60', 'Riesgo: puntaje desde el que el nivel es alto', 'Decisión 14; propuesta AGS', false),
  ('riesgo_nivel_medio', '30', 'Riesgo: puntaje desde el que el nivel es medio', 'Decisión 14; propuesta AGS', false),
  ('riesgo_estado_pesos', 'Propuesta AGS, pendiente de aprobación DIGSA', 'Riesgo: estado de aprobación de los pesos', 'Decisión 2026-10-20 14.1', true)
ON CONFLICT (clave) DO NOTHING;

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON riesgo_incidencias TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
