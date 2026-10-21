-- Suite DIGSA · S3: ejecución de la visita (formulario del checklist), evidencias, firmas, acta y reapertura.
-- Referencia funcional: docs/especificaciones/checklist, equipo-reparto y archivos (PHP develop, db2e330). Diferencia de diseño: el
-- PHP guarda el formulario como un JSON con revisión; aquí cada respuesta es una fila, así cada auditor escribe solo sus ítems sin
-- pisar a los demás y los reportes (S4) leen tablas, no el JSON del navegador.

-- Datos de la ejecución (Anexo 5, prueba 12): inicio y fin reales, objeto, observaciones y recomendaciones
CREATE TABLE visitas_ejecucion (
    visita_id             bigint PRIMARY KEY REFERENCES visitas (id) ON DELETE RESTRICT,
    inicio_real           timestamptz,
    fin_real              timestamptz,
    objeto                text CHECK (char_length(objeto) <= 2000),
    recomendaciones       text CHECK (char_length(recomendaciones) <= 4000),
    observaciones_auditor text CHECK (char_length(observaciones_auditor) <= 4000),
    observaciones_esm     text CHECK (char_length(observaciones_esm) <= 4000),
    observaciones_opl     text CHECK (char_length(observaciones_opl) <= 4000),
    actualizado           timestamptz NOT NULL DEFAULT now(),
    actualizado_por       bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    CHECK (fin_real IS NULL OR inicio_real IS NULL OR fin_real >= inicio_real)
);
CREATE INDEX ix_visitas_ejecucion_usuario ON visitas_ejecucion (actualizado_por);

-- Personal auditado (quienes atienden la visita en el punto y por el OPL)
CREATE TABLE visitas_personal (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    entidad   text NOT NULL CHECK (entidad IN ('ESM', 'OPL', 'FA', 'otro')),
    nombre    text NOT NULL CHECK (char_length(nombre) BETWEEN 3 AND 150),
    cargo     text CHECK (char_length(cargo) <= 120),
    documento text CHECK (char_length(documento) <= 30),
    correo    text CHECK (char_length(correo) <= 150),
    orden     smallint NOT NULL DEFAULT 0
);
CREATE INDEX ix_visitas_personal_visita ON visitas_personal (visita_id, orden);

-- Calificación de cada ítem del checklist (C = Cumple, NC = No cumple, NA = No aplica) con su hallazgo
CREATE TABLE visitas_respuestas (
    visita_id    bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    item_id      bigint NOT NULL REFERENCES chk_items (id) ON DELETE RESTRICT,
    parte        text NOT NULL CHECK (parte ~ '^(C[0-9]{2}|OBL|SIS)$'),
    calificacion text CHECK (calificacion IN ('C', 'NC', 'NA')),
    hallazgo     text CHECK (char_length(hallazgo) <= 2000),
    usuario_id   bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    actualizado  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (visita_id, item_id)
);
CREATE INDEX ix_visitas_respuestas_item ON visitas_respuestas (item_id);
CREATE INDEX ix_visitas_respuestas_usuario ON visitas_respuestas (usuario_id);

-- Archivos (evidencias de los ítems, de la visita y el PDF del acta). Viven fuera de la carpeta web con nombre aleatorio; quitar
-- uno lo marca eliminado sin borrarlo (spec archivos)
CREATE TABLE archivos (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id       bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    clave           text NOT NULL CHECK (clave ~ '^(visita|acta|it:[A-Z0-9][A-Z0-9-]{1,15})$'),
    nombre_original text NOT NULL CHECK (char_length(nombre_original) BETWEEN 1 AND 200),
    ruta            text NOT NULL UNIQUE CHECK (ruta ~ '^[A-Za-z0-9_/-]+\.[a-z0-9]+$'),
    tipo            text NOT NULL,
    tamano          bigint NOT NULL CHECK (tamano > 0),
    sha256          char(64) NOT NULL,
    subido_por      bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    subido          timestamptz NOT NULL DEFAULT now(),
    eliminado       timestamptz
);
CREATE INDEX ix_archivos_visita ON archivos (visita_id, clave) WHERE eliminado IS NULL;
CREATE INDEX ix_archivos_usuario ON archivos (subido_por);

-- Firmas del acta: auditor líder y quienes atienden (ESM u OPL). La imagen es un PNG dibujado en la pantalla
CREATE TABLE visitas_firmas (
    visita_id bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    rol       text NOT NULL CHECK (rol IN ('auditor', 'esm', 'opl')),
    nombre    text NOT NULL CHECK (char_length(nombre) BETWEEN 3 AND 150),
    documento text CHECK (char_length(documento) <= 30),
    cargo     text CHECK (char_length(cargo) <= 120),
    imagen    bytea NOT NULL CHECK (octet_length(imagen) BETWEEN 100 AND 300000),
    firmado   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (visita_id, rol)
);

-- Actas: una por ronda (la reapertura genera otra; la anterior se conserva)
CREATE TABLE visitas_actas (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id     bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    ronda         smallint NOT NULL,
    archivo_id    bigint NOT NULL REFERENCES archivos (id) ON DELETE RESTRICT,
    destinatarios text[] NOT NULL DEFAULT '{}',
    estado        text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'enviada', 'parcial', 'fallida')),
    error         text CHECK (char_length(error) <= 500),
    generado_por  bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    generado      timestamptz NOT NULL DEFAULT now(),
    resultado     timestamptz,
    UNIQUE (visita_id, ronda)
);
CREATE INDEX ix_visitas_actas_archivo ON visitas_actas (archivo_id);
CREATE INDEX ix_visitas_actas_usuario ON visitas_actas (generado_por);

-- Resumen de resultados (lo calcula el servidor al enviar el acta; los reportes y el tablero leen de aquí)
CREATE TABLE visitas_resumen (
    visita_id  bigint PRIMARY KEY REFERENCES visitas (id) ON DELETE RESTRICT,
    c          int NOT NULL DEFAULT 0,
    nc         int NOT NULL DEFAULT 0,
    na         int NOT NULL DEFAULT 0,
    pendientes int NOT NULL DEFAULT 0,
    hallazgos  int NOT NULL DEFAULT 0,
    por_componente jsonb NOT NULL DEFAULT '{}',
    calculado  timestamptz NOT NULL DEFAULT now()
);

-- Reapertura (Anexo 5, prueba 10): motivo y descripción en el historial; la ronda sube con cada reapertura
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON visitas_ejecucion, archivos, visitas_actas, visitas_resumen TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON visitas_personal, visitas_respuestas, visitas_firmas TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
