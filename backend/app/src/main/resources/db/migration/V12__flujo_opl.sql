-- Suite DIGSA · Fase 6: flujo del OPL después del acta (docs/especificaciones/flujo-opl), con las decisiones del 2026-10-20:
-- 11.2 código solo al OPL, 11.5 sin firma tardía (vencido → reporte formal a la DIGSA), 11.7 cierre del plan al levantar el último
-- hallazgo, 11.8 documento en la firma por QR, 12.2 el ESM no ve objeciones ni soportes. La aplicación nunca inicia multas.

-- ---------- Parte del auditor en la visita ----------

-- Obligaciones de la minuta del contrato (parte OBL del formulario). El PHP las extrae por OCR de la minuta (duda 5.1: minutas
-- reales pendientes); aquí el coordinador las registra como catálogo editable y la visita las califica.
CREATE TABLE obligaciones_contrato (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    prestador text NOT NULL CHECK (prestador IN ('opl', 'fa')),              -- operador logístico o farmacia alterna
    codigo    text NOT NULL UNIQUE CHECK (codigo ~ '^OB-[A-Z0-9-]{1,20}$'),
    texto     text NOT NULL CHECK (char_length(texto) BETWEEN 10 AND 2000),
    fuente    text CHECK (char_length(fuente) <= 200),                     -- cláusula o página de la minuta
    activo    boolean NOT NULL DEFAULT true,
    orden     smallint NOT NULL DEFAULT 0
);

CREATE TABLE visitas_obligaciones (
    visita_id     bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    obligacion_id bigint NOT NULL REFERENCES obligaciones_contrato (id) ON DELETE RESTRICT,
    calificacion  text CHECK (calificacion IN ('C', 'NC', 'NA')),
    hallazgo      text CHECK (char_length(hallazgo) <= 2000),
    usuario_id    bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    actualizado   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (visita_id, obligacion_id)
);
CREATE INDEX ix_visitas_obligaciones_obligacion ON visitas_obligaciones (obligacion_id);
CREATE INDEX ix_visitas_obligaciones_usuario ON visitas_obligaciones (usuario_id);

-- Plan de mejoramiento 5W2H, parte del auditor (Anexo 3 p.11): una fila por hallazgo ('ob:OB-…' o 'it:<código>')
CREATE TABLE visitas_plan (
    visita_id   bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    clave       text NOT NULL CHECK (clave ~ '^(ob:OB-[A-Z0-9-]{1,20}|it:[A-Z0-9][A-Z0-9-]{1,15})$'),
    causas      text CHECK (char_length(causas) <= 2000),
    que         text CHECK (char_length(que) <= 2000),
    porque      text CHECK (char_length(porque) <= 2000),
    donde       text CHECK (char_length(donde) <= 500),
    inicio      date,
    fin         date,
    indicador   text CHECK (char_length(indicador) <= 1000),
    estado      text NOT NULL DEFAULT 'Pendiente' CHECK (estado IN ('Pendiente', 'En curso', 'Completado', 'Retrasado', 'Cancelado')),
    avance      smallint NOT NULL DEFAULT 0 CHECK (avance BETWEEN 0 AND 100),
    usuario_id  bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    actualizado timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (visita_id, clave),
    CHECK (fin IS NULL OR inicio IS NULL OR fin >= inicio)
);
CREATE INDEX ix_visitas_plan_usuario ON visitas_plan (usuario_id);

ALTER TABLE visitas_ejecucion ADD COLUMN plan_fecha date;   -- fecha del plan 5W2H

-- Firmas del 5W2H: el auditor y el auditado («recibí los hallazgos»)
ALTER TABLE visitas_firmas DROP CONSTRAINT visitas_firmas_rol_check,
    ADD CONSTRAINT visitas_firmas_rol_check CHECK (rol IN ('auditor', 'esm', 'opl', 'w5_auditor', 'w5_auditado'));

-- Soportes de las obligaciones
ALTER TABLE archivos DROP CONSTRAINT archivos_clave_check,
    ADD CONSTRAINT archivos_clave_check CHECK (clave ~ '^(visita|acta|it:[A-Z0-9][A-Z0-9-]{1,15}|ob:OB-[A-Z0-9-]{1,20})$');

-- Hallazgos de la visita al cerrarla (por acta: una ronda nueva los reemplaza)
CREATE TABLE visitas_hallazgos (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id   bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    acta_id     bigint NOT NULL REFERENCES visitas_actas (id) ON DELETE RESTRICT,
    clave       text NOT NULL,
    codigo      text NOT NULL,
    origen      text NOT NULL CHECK (origen IN ('obligacion', 'item')),
    texto       text NOT NULL,
    hallazgo    text,
    fin         date,
    estado_plan text NOT NULL DEFAULT 'Pendiente' CHECK (estado_plan IN ('Pendiente', 'En curso', 'Completado', 'Retrasado', 'Cancelado')),
    UNIQUE (acta_id, clave)
);
CREATE INDEX ix_visitas_hallazgos_visita ON visitas_hallazgos (visita_id, acta_id);

-- ---------- Parte del OPL ----------

-- Enlace del acta (QR): uno por visita, se reemplaza con el acta nueva. El token solo se guarda como huella
CREATE TABLE opl_accesos (
    visita_id        bigint PRIMARY KEY REFERENCES visitas (id) ON DELETE RESTRICT,
    token_hash       char(64) NOT NULL UNIQUE,
    acta_id          bigint NOT NULL REFERENCES visitas_actas (id) ON DELETE RESTRICT,
    enviado          timestamptz NOT NULL,
    vence_objeciones timestamptz NOT NULL,
    vence_plan       timestamptz NOT NULL,
    CHECK (vence_plan >= vence_objeciones)
);
CREATE INDEX ix_opl_accesos_acta ON opl_accesos (acta_id);
CREATE INDEX ix_opl_accesos_vence ON opl_accesos (vence_objeciones);

CREATE TABLE opl_codigos (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id   bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    correo      text NOT NULL,
    codigo_hash char(64) NOT NULL,
    creado      timestamptz NOT NULL DEFAULT now(),
    vence       timestamptz NOT NULL,
    intentos    smallint NOT NULL DEFAULT 0,
    usado       timestamptz,
    estado      text NOT NULL DEFAULT 'enviando' CHECK (estado IN ('enviando', 'enviado', 'fallido')), -- reserva del tope mientras sale el correo
    ip          text
);
CREATE INDEX ix_opl_codigos_visita ON opl_codigos (visita_id, creado);
CREATE INDEX ix_opl_codigos_ip ON opl_codigos (ip, creado);

-- Respuesta del OPL a cada hallazgo y decisión del auditor líder sobre la objeción (se conservan entre rondas)
CREATE TABLE opl_respuestas (
    visita_id         bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    clave             text NOT NULL,
    decision          text NOT NULL CHECK (decision IN ('acepta', 'objeta')),
    automatica        boolean NOT NULL DEFAULT false,
    justificacion     text CHECK (char_length(justificacion) <= 4000),
    quien             text CHECK (char_length(quien) <= 200),
    como              text CHECK (char_length(como) <= 4000),
    nombre            text CHECK (char_length(nombre) <= 150),
    cargo             text CHECK (char_length(cargo) <= 150),
    canal             text NOT NULL CHECK (canal IN ('qr', 'usuario', 'sistema')),
    usuario_id        bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    correo            text,
    ip                text,
    fecha             timestamptz NOT NULL DEFAULT now(),
    resolucion        text CHECK (resolucion IN ('ratifica', 'levanta')),
    resolucion_motivo text CHECK (char_length(resolucion_motivo) <= 800),
    resuelto_por      bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    resuelto_fecha    timestamptz,
    PRIMARY KEY (visita_id, clave),
    CHECK (decision = 'objeta' OR resolucion IS NULL),
    CHECK ((resolucion IS NULL) = (resuelto_fecha IS NULL))
);
CREATE INDEX ix_opl_respuestas_usuario ON opl_respuestas (usuario_id);
CREATE INDEX ix_opl_respuestas_resuelto ON opl_respuestas (resuelto_por);

CREATE TABLE opl_soportes (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id       bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    clave           text NOT NULL,
    nombre_original text NOT NULL CHECK (char_length(nombre_original) BETWEEN 1 AND 200),
    ruta            text NOT NULL UNIQUE CHECK (ruta ~ '^[A-Za-z0-9_/-]+\.[a-z0-9]+$'),
    tipo            text NOT NULL,
    tamano          bigint NOT NULL CHECK (tamano > 0),
    sha256          char(64) NOT NULL,
    usuario_id      bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    correo          text,                                   -- por el QR no hay usuario: el correo verificado
    subido          timestamptz NOT NULL DEFAULT now(),
    CHECK (usuario_id IS NOT NULL OR correo IS NOT NULL)
);
CREATE INDEX ix_opl_soportes_visita ON opl_soportes (visita_id, clave);
CREATE INDEX ix_opl_soportes_usuario ON opl_soportes (usuario_id);

-- Reporte formal a la DIGSA (plan no presentado o incumplido) con consecutivo. Nunca inicia una multa
CREATE TABLE reportes_digsa (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    consecutivo   text NOT NULL UNIQUE CHECK (consecutivo ~ '^RD-[0-9]{4}-[0-9]{4,}$'),
    anio          smallint NOT NULL,
    numero        int NOT NULL CHECK (numero > 0),
    visita_id     bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    acta_id       bigint NOT NULL REFERENCES visitas_actas (id) ON DELETE RESTRICT,
    motivo        text NOT NULL CHECK (motivo IN ('plan_no_presentado', 'plan_incumplido')),
    contenido     jsonb NOT NULL,
    destinatarios text[] NOT NULL DEFAULT '{}',
    estado        text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'enviado', 'fallido')),
    error         text CHECK (char_length(error) <= 500),
    creado        timestamptz NOT NULL DEFAULT now(),
    enviado       timestamptz,
    UNIQUE (anio, numero),
    UNIQUE (acta_id, motivo)
);
CREATE INDEX ix_reportes_digsa_visita ON reportes_digsa (visita_id);

-- Plan del OPL por acta: firma ("configuración del plan") y su estado
CREATE TABLE opl_planes (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id     bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    acta_id       bigint NOT NULL UNIQUE REFERENCES visitas_actas (id) ON DELETE RESTRICT,
    estado        text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'firmado', 'vencido', 'reportado_digsa', 'cerrado')),
    nombre        text CHECK (char_length(nombre) <= 150),
    cargo         text CHECK (char_length(cargo) <= 150),
    documento     text CHECK (char_length(documento) <= 30),
    firma         bytea CHECK (octet_length(firma) <= 300000),
    canal         text CHECK (canal IN ('qr', 'usuario')),
    usuario_id    bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    correo        text,
    ip            text,
    firmado       timestamptz,
    vencido_en    timestamptz,
    reportado_en  timestamptz,
    reporte_id    bigint REFERENCES reportes_digsa (id) ON DELETE RESTRICT,
    cerrado_en    timestamptz,
    cerrado_por   bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    motivo_cierre text CHECK (char_length(motivo_cierre) <= 800),
    CHECK (firmado IS NULL OR (nombre IS NOT NULL AND firma IS NOT NULL)),
    CHECK (estado <> 'firmado' OR firmado IS NOT NULL)
);
CREATE INDEX ix_opl_planes_visita ON opl_planes (visita_id);
CREATE INDEX ix_opl_planes_usuario ON opl_planes (usuario_id);
CREATE INDEX ix_opl_planes_cerrado_por ON opl_planes (cerrado_por);
CREATE INDEX ix_opl_planes_reporte ON opl_planes (reporte_id);

-- Avisos de la tarea diaria (una vez por acta y tipo)
CREATE TABLE opl_avisos (
    visita_id bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    acta_id   bigint NOT NULL REFERENCES visitas_actas (id) ON DELETE RESTRICT,
    tipo      text NOT NULL CHECK (tipo IN ('aceptacion_automatica', 'plan_vencido', 'reporte_digsa', 'plan_cerrado')),
    fecha     timestamptz NOT NULL DEFAULT now(),
    resultado text CHECK (char_length(resultado) <= 500),
    PRIMARY KEY (visita_id, acta_id, tipo)
);
CREATE INDEX ix_opl_avisos_acta ON opl_avisos (acta_id);

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON obligaciones_contrato, visitas_plan TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON visitas_obligaciones, opl_accesos TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON visitas_hallazgos TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON opl_codigos, opl_respuestas, opl_planes, reportes_digsa, opl_avisos TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT ON opl_soportes TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
