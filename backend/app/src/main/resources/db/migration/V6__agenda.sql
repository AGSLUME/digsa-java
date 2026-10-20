-- Suite DIGSA · S2: agenda de visitas, equipo, componentes elegidos y reparto del formulario.
-- Referencia funcional: Database/Structure/005_agenda.sql del PHP (develop, db2e330) y docs/especificaciones/agenda-notificacion,
-- equipo-reparto y agenda-consulta. Diferencias decididas el 2026-10-20 (docs/decisiones_2026-10-20.md): el coordinador elige los
-- componentes al crear la visita (Anexo 5, prueba 11); las partes solo se mueven antes de iniciar (8.3); al reasignar el reenvío de la
-- notificación es obligatorio (9.8).

-- Datos del anexo del OPL que no son de la interventoría
ALTER TABLE puntos
    ADD COLUMN regional_opl text,          -- "Regional N" del anexo del OPL (no es una de las 8 regionales de la interventoría)
    ADD COLUMN fuente text;                -- de dónde salió el punto (anexo y página, carga masiva o creado a mano)

CREATE TABLE visitas (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero            text UNIQUE,                                     -- VS-AAAAMMDD-NNNN; se asigna en la misma transacción
    tipo              text NOT NULL REFERENCES par_tipos_visita (codigo) ON DELETE RESTRICT,
    punto_id          bigint NOT NULL REFERENCES puntos (id) ON DELETE RESTRICT,
    fecha             date NOT NULL,
    hora              time,
    auditor_id        bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,   -- líder
    alcance           text NOT NULL CHECK (char_length(alcance) BETWEEN 1 AND 5000),
    estado            text NOT NULL DEFAULT 'programada'
                      CHECK (estado IN ('programada', 'notificada', 'reprogramada', 'ejecucion', 'cerrada', 'cancelada')),
    fecha_solicitud   date,                                            -- visita a necesidad: fecha del escrito (Anexo 3 p.14)
    checklist_version text REFERENCES chk_versiones (version) ON DELETE RESTRICT,   -- se fija al iniciar
    equipo_sin_notificar boolean NOT NULL DEFAULT false,               -- cambió el equipo de una visita ya notificada
    iniciada          timestamptz,
    reaperturas       smallint NOT NULL DEFAULT 0,
    creado_por        bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    creado            timestamptz NOT NULL DEFAULT now(),
    actualizado       timestamptz NOT NULL DEFAULT now(),
    CHECK (tipo <> 'necesidad' OR fecha_solicitud IS NOT NULL)
);
CREATE INDEX ix_visitas_fecha ON visitas (fecha, id);
CREATE INDEX ix_visitas_punto ON visitas (punto_id, fecha);
CREATE INDEX ix_visitas_auditor ON visitas (auditor_id);
CREATE INDEX ix_visitas_estado ON visitas (estado) WHERE estado IN ('programada', 'notificada', 'reprogramada', 'ejecucion');
CREATE INDEX ix_visitas_creado_por ON visitas (creado_por);

-- Componentes que el coordinador eligió para la visita (código del componente; vale para cualquier versión del checklist)
CREATE TABLE visitas_componentes (
    visita_id  bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    componente text NOT NULL CHECK (componente ~ '^C[0-9]{2}$'),
    PRIMARY KEY (visita_id, componente)
);

-- Equipo: un líder (también en visitas.auditor_id) y de 0 a 5 de apoyo
CREATE TABLE visitas_auditores (
    visita_id  bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    usuario_id bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    funcion    text NOT NULL CHECK (funcion IN ('lider', 'apoyo')),
    orden      smallint NOT NULL CHECK (orden BETWEEN 0 AND 5),
    PRIMARY KEY (visita_id, usuario_id)
);
CREATE UNIQUE INDEX uq_visitas_auditores_lider ON visitas_auditores (visita_id) WHERE funcion = 'lider';
CREATE INDEX ix_visitas_auditores_usuario ON visitas_auditores (usuario_id, visita_id);

-- Reparto: cada parte (componente, OBL o SIS) tiene exactamente un dueño, que es del equipo
CREATE TABLE visitas_reparto (
    visita_id     bigint NOT NULL,
    parte         text NOT NULL CHECK (parte ~ '^(C[0-9]{2}|OBL|SIS)$'),
    usuario_id    bigint NOT NULL,
    asignado_por  text NOT NULL,                                     -- "sistema" o el usuario del coordinador
    asignado      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (visita_id, parte),
    FOREIGN KEY (visita_id, usuario_id) REFERENCES visitas_auditores (visita_id, usuario_id) ON DELETE RESTRICT
);
CREATE INDEX ix_visitas_reparto_usuario ON visitas_reparto (visita_id, usuario_id);

-- Historial de la visita: evidencia, no se borra
CREATE TABLE visitas_historial (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id  bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    fecha      timestamptz NOT NULL DEFAULT now(),
    usuario_id bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    usuario    text,
    evento     text NOT NULL CHECK (char_length(evento) <= 60),
    detalle    text CHECK (char_length(detalle) <= 1000)
);
CREATE INDEX ix_visitas_historial_visita ON visitas_historial (visita_id, id);
CREATE INDEX ix_visitas_historial_usuario ON visitas_historial (usuario_id);

-- Notificación previa: se registra antes de enviar (Anexo 3 p.9). Se conserva sin plazo (decisión 7.5).
CREATE TABLE visitas_notificaciones (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id      bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    destinatarios  text[] NOT NULL CHECK (cardinality(destinatarios) BETWEEN 1 AND 15),
    asunto         text NOT NULL CHECK (char_length(asunto) <= 255),
    cuerpo         text NOT NULL,
    origen         text NOT NULL,
    estado         text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'enviada', 'fallida')),
    error          text CHECK (char_length(error) <= 500),
    usuario_id     bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    creado         timestamptz NOT NULL DEFAULT now(),
    resultado      timestamptz
);
CREATE INDEX ix_visitas_notificaciones_visita ON visitas_notificaciones (visita_id, id);
CREATE INDEX ix_visitas_notificaciones_usuario ON visitas_notificaciones (usuario_id);

-- Permisos mínimos del rol de la aplicación, si está separado (ver V2): las visitas no se borran
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON visitas, visitas_notificaciones TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT ON visitas_historial TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON visitas_componentes, visitas_auditores, visitas_reparto TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
