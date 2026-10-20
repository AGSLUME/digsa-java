-- Suite DIGSA · S2: catálogos, maestros (OPL, puntos, auditores) y checklist versionado.
-- Referencia funcional: Database/Structure/002_catalogos.sql, 003_checklist.sql y 004_maestros.sql del PHP (develop, db2e330).
-- Los datos de los catálogos los carga R__catalogos.sql (repetible: inserta o actualiza sin pisar lo que edita el coordinador);
-- el checklist v6 lo publica V4 (una versión publicada no se modifica).

-- ---------- Catálogos geográficos y de la operación ----------
CREATE TABLE par_departamentos (
    codigo char(2) PRIMARY KEY,
    nombre text NOT NULL
);

CREATE TABLE par_municipios (
    codigo       char(5) PRIMARY KEY,                        -- código DANE
    departamento char(2) NOT NULL REFERENCES par_departamentos (codigo) ON DELETE RESTRICT,
    nombre       text NOT NULL,
    latitud      numeric(9, 6),                              -- DANE (datos.gov.co, gdxc-w37w): cercanía de la propuesta de agenda
    longitud     numeric(9, 6)
);
CREATE INDEX ix_municipios_departamento ON par_municipios (departamento);

CREATE TABLE par_unidades (
    codigo text PRIMARY KEY CHECK (codigo IN ('ESM', 'FA', 'NC')),   -- ESM, farmacia alterna, nivel central del OPL
    nombre text NOT NULL,
    fuente text,
    orden  smallint NOT NULL DEFAULT 0
);

-- Fuerzas: catálogo editable por el coordinador (Anexo 5, prueba 4: se crean, modifican y eliminan en vivo).
CREATE TABLE par_fuerzas (
    codigo  text PRIMARY KEY CHECK (codigo ~ '^[A-Z]{2,5}$'),       -- EJC, ARC, FAC; NA = nivel central del OPL
    nombre  text NOT NULL CHECK (char_length(nombre) BETWEEN 2 AND 120),
    sanidad text,                                                     -- DISAN o JEFSA de la fuerza
    correos text[] NOT NULL DEFAULT '{}',                             -- destinatarios de la notificación previa y del acta
    fuente  text,
    orden   smallint NOT NULL DEFAULT 0,
    activo  boolean NOT NULL DEFAULT true
);

-- Regionales de la interventoría (8 equipos regionales, respuesta AGS-21). Nunca se borran: se inactivan.
CREATE TABLE par_regionales (
    id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE CHECK (char_length(nombre) BETWEEN 2 AND 80),
    activo boolean NOT NULL DEFAULT true
);

CREATE TABLE par_modalidades (
    nombre text PRIMARY KEY,                                          -- Dispensación, Suministro, Consignación, Satélite
    orden  smallint NOT NULL DEFAULT 0
);

CREATE TABLE par_tipos_visita (
    codigo       text PRIMARY KEY,
    nombre       text NOT NULL,
    unidad       text REFERENCES par_unidades (codigo) ON DELETE RESTRICT,   -- NULL = cualquiera
    modalidad    text,
    aviso        text NOT NULL CHECK (aviso IN ('opl', 'no-opl', 'modulo')), -- opl: 15 días (Anexo 3 p.9); no-opl: sin aviso; modulo: N hábiles
    periodicidad text,
    finalidad    text,
    notificacion text,
    fuente       text,
    orden        smallint NOT NULL DEFAULT 0
);

CREATE TABLE par_parametros (
    clave       text PRIMARY KEY,
    valor       text,
    descripcion text,
    fuente      text,
    editable    boolean NOT NULL DEFAULT true,                          -- false = regla fija del Anexo 3
    actualizado_por text,
    actualizado timestamptz NOT NULL DEFAULT now()
);

-- Solo ajustes: los festivos de la Ley 51 de 1983 se calculan para cualquier año (reglas/Festivos).
CREATE TABLE par_festivos (
    fecha date PRIMARY KEY,
    motivo text
);

-- Catálogos parametrizables genéricos (Anexo 5, prueba 4: tipos de documento C, T, R, V; tipos de factura 1 Acuerdo, 2 CTC,
-- 3 Tutela, 4 Requisición; y los que pida el comité). Se crean, modifican y eliminan en vivo desde la pantalla.
CREATE TABLE catalogos (
    codigo      text PRIMARY KEY CHECK (codigo ~ '^[a-z][a-z0-9_]{2,40}$'),
    nombre      text NOT NULL,
    descripcion text,
    patron      text NOT NULL DEFAULT '^[A-Z0-9]{1,10}$',              -- formato del código de cada valor (validación)
    orden       smallint NOT NULL DEFAULT 0
);

CREATE TABLE catalogo_valores (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    catalogo    text NOT NULL REFERENCES catalogos (codigo) ON DELETE RESTRICT,
    codigo      text NOT NULL CHECK (char_length(codigo) BETWEEN 1 AND 10),
    descripcion text NOT NULL CHECK (char_length(descripcion) BETWEEN 2 AND 120),
    activo      boolean NOT NULL DEFAULT true,
    orden       smallint NOT NULL DEFAULT 0,
    creado      timestamptz NOT NULL DEFAULT now(),
    actualizado timestamptz NOT NULL DEFAULT now(),
    UNIQUE (catalogo, codigo)
);

-- ---------- Maestros ----------
CREATE TABLE opl (
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre  text NOT NULL,                                            -- razón social del operador logístico
    nit     text,
    correos text[] NOT NULL DEFAULT '{}',                             -- reciben la notificación previa y el acta
    activo  boolean NOT NULL DEFAULT true,
    actualizado timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE puntos (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sigla            text,                                            -- sigla del anexo del OPL (p. ej. DIS-...)
    nombre           text NOT NULL CHECK (char_length(nombre) BETWEEN 3 AND 200),
    unidad           text NOT NULL REFERENCES par_unidades (codigo) ON DELETE RESTRICT,
    fuerza           text NOT NULL REFERENCES par_fuerzas (codigo) ON DELETE RESTRICT,
    modalidad        text REFERENCES par_modalidades (nombre) ON DELETE RESTRICT,   -- solo ESM
    nit              text,                                            -- solo farmacia alterna
    municipio        char(5) REFERENCES par_municipios (codigo) ON DELETE RESTRICT,
    direccion        text,
    nivel            text,
    horario          text,
    contacto         text,
    correos          text[] NOT NULL DEFAULT '{}',                    -- contacto del punto: notificación previa y acta
    regional_id      bigint REFERENCES par_regionales (id) ON DELETE RESTRICT,
    llega_desde      char(5) REFERENCES par_municipios (codigo) ON DELETE RESTRICT,   -- puntos de difícil acceso
    auditor_asignado bigint REFERENCES usuarios (id) ON DELETE RESTRICT,              -- Anexo 3 p.9
    exige_qf         boolean NOT NULL DEFAULT false,                  -- HMC y Hospital Naval de Cartagena (Anexo 3 p.9)
    activo           boolean NOT NULL DEFAULT true,
    creado_por       text,
    creado           timestamptz NOT NULL DEFAULT now(),
    actualizado      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (nombre, unidad),
    CHECK (unidad <> 'ESM' OR modalidad IS NOT NULL)
);
CREATE UNIQUE INDEX uq_puntos_sigla ON puntos (sigla) WHERE sigla IS NOT NULL;
CREATE INDEX ix_puntos_unidad_fuerza ON puntos (unidad, fuerza);
CREATE INDEX ix_puntos_municipio ON puntos (municipio);
CREATE INDEX ix_puntos_regional ON puntos (regional_id);
CREATE INDEX ix_puntos_llega_desde ON puntos (llega_desde);
CREATE INDEX ix_puntos_auditor_asignado ON puntos (auditor_asignado);

-- Los auditores son usuarios con el perfil Auditor; aquí va lo propio: rol técnico (decisión 2a) y municipio base.
CREATE TABLE auditores (
    usuario_id     bigint PRIMARY KEY REFERENCES usuarios (id) ON DELETE RESTRICT,
    rol            text NOT NULL DEFAULT 'otro'
                   CHECK (rol IN ('quimico_farmaceutico', 'regente', 'auditor_salud', 'ingeniero_sistemas', 'otro')),
    municipio_base char(5) REFERENCES par_municipios (codigo) ON DELETE RESTRICT,
    actualizado    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_auditores_municipio_base ON auditores (municipio_base);

-- Alcance de los perfiles de consulta (pendiente de S1)
ALTER TABLE usuarios
    ADD CONSTRAINT fk_usuarios_alcance_fuerza FOREIGN KEY (alcance_fuerza) REFERENCES par_fuerzas (codigo) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_usuarios_alcance_punto FOREIGN KEY (alcance_punto) REFERENCES puntos (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_usuarios_alcance_regional FOREIGN KEY (alcance_regional) REFERENCES par_regionales (id) ON DELETE RESTRICT;
CREATE INDEX ix_usuarios_alcance_fuerza ON usuarios (alcance_fuerza);
CREATE INDEX ix_usuarios_alcance_punto ON usuarios (alcance_punto);
CREATE INDEX ix_usuarios_alcance_regional ON usuarios (alcance_regional);

-- ---------- Checklist versionado (una versión publicada no se modifica; cada visita queda ligada a la suya) ----------
CREATE TABLE chk_versiones (
    version          text PRIMARY KEY,
    descripcion      text,
    fecha_generacion date NOT NULL,
    vigente          boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uq_chk_versiones_vigente ON chk_versiones (vigente) WHERE vigente;   -- una sola vigente

CREATE TABLE chk_vacios (
    version text NOT NULL REFERENCES chk_versiones (version) ON DELETE RESTRICT,
    codigo  smallint NOT NULL,
    nombre  text NOT NULL,                                            -- vacío de mayor riesgo (Res. 1403 frente a Res. 3100)
    PRIMARY KEY (version, codigo)
);

CREATE TABLE chk_guia_obligacion (
    version   text PRIMARY KEY REFERENCES chk_versiones (version) ON DELETE RESTRICT,
    cumple    text NOT NULL,
    no_cumple text NOT NULL,
    no_aplica text NOT NULL,
    fuente    text
);

CREATE TABLE chk_componentes (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version     text NOT NULL REFERENCES chk_versiones (version) ON DELETE RESTRICT,
    codigo      text NOT NULL,                                        -- C00, C01, ...
    nombre      text NOT NULL,
    descripcion text,
    grupo       text,
    especial    text,                                                 -- plan = solo con plan de mejoramiento vigente o seguimiento
    escala      jsonb,                                                -- escala propia (habilitación); NULL = Cumple / No cumple / No aplica
    guia        text,
    unidades    text[] NOT NULL CHECK (unidades <@ ARRAY['ESM', 'FA', 'NC']::text[] AND cardinality(unidades) > 0),
    fuente      text,
    orden       smallint NOT NULL DEFAULT 0,
    UNIQUE (version, codigo)
);

CREATE TABLE chk_items (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    componente_id bigint NOT NULL REFERENCES chk_componentes (id) ON DELETE RESTRICT,
    codigo        text NOT NULL,                                      -- HB-01, P05-07, ...
    punto         text NOT NULL,
    criterio      text NOT NULL,
    fuente        text,
    origen        text NOT NULL,
    requisito     text,
    evidencia     text,
    aplica        text,
    condicion     text,                                               -- con condición se admite No aplica en escalas propias
    prioridad     text,                                               -- vacío de mayor riesgo al que pertenece
    medicion      jsonb,
    subvalidacion jsonb,                                              -- soporte de inscripción en el REPS
    orden         smallint NOT NULL DEFAULT 0,
    UNIQUE (componente_id, codigo)
);

-- Permisos mínimos del rol de la aplicación, si está separado (ver V2)
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT ON par_departamentos, par_municipios, par_unidades, par_modalidades, par_tipos_visita, chk_versiones, '
                   'chk_vacios, chk_guia_obligacion, chk_componentes, chk_items TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON par_fuerzas, catalogo_valores, par_festivos TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON par_regionales, par_parametros, catalogos, opl, puntos, auditores TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
