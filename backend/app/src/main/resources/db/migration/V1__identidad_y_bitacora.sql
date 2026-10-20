-- Suite DIGSA · S1: perfiles, usuarios, segundo factor, sesiones y bitácora.
-- Referencia funcional: Database/Structure/001_usuarios.sql y Database/Data/001_perfiles.sql del PHP (develop, db2e330).
-- Hora: timestamptz (instantes); la hora de negocio se muestra en America/Bogota.

-- ---------- Perfiles (ids fijos; Consulta se retiró por la decisión 20 y queda inactivo) ----------
CREATE TABLE perfiles (
    id          smallint PRIMARY KEY,
    nombre      text NOT NULL UNIQUE,
    descripcion text,
    activo      boolean NOT NULL DEFAULT true
);

INSERT INTO perfiles (id, nombre, descripcion, activo) VALUES
    (1, 'Administrador', 'Usuarios, logs, reabrir visitas y todo lo del coordinador', true),
    (2, 'Coordinador', 'Agenda, programar, reasignar, reabrir, solicitudes, maestros, catálogos, dashboards y reportes', true),
    (3, 'Auditor', 'Sus visitas (líder u otro auditor): su parte del checklist; el líder, 5W2H, acta y envío', true),
    (4, 'Consulta', 'Reemplazado por DIGSA, Sanidad y ESM (decisión 20)', false),
    (5, 'DIGSA', 'Alcance nacional: calendario, dashboards, reportes y solicitar visita; recibe todas las actas', true),
    (6, 'Sanidad', 'DISAN (EJC, ARC) o JEFSA (FAC): lo mismo que DIGSA en su fuerza (la fuerza va en el usuario)', true),
    (7, 'ESM', 'Su ESM: calendario (sin visitas a necesidad pendientes), dashboards y reportes; no solicita visitas', true),
    (8, 'OPL nacional', 'Operador logístico, país: hallazgos, aceptar u objetar, plan de mejoramiento', true),
    (9, 'OPL regional', 'Operador logístico, su regional: lo mismo que el OPL nacional (la regional va en el usuario)', true),
    (10, 'Analista', 'Analista de la interventoría (Anexo 5, prueba en sitio, ítem 2; respuesta AGS-30)', true);

-- ---------- Usuarios ----------
CREATE TABLE usuarios (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre             text NOT NULL CHECK (char_length(nombre) BETWEEN 3 AND 150),
    documento          text CHECK (char_length(documento) <= 30),
    telefono           text CHECK (char_length(telefono) <= 30),
    usuario            text NOT NULL UNIQUE CHECK (usuario ~ '^[a-z0-9._-]{3,50}$'),
    correo             text NOT NULL UNIQUE CHECK (correo = lower(correo) AND char_length(correo) <= 150),
    clave_hash         text NOT NULL,                      -- BCrypt; los usuarios nuevos reciben una aleatoria que nadie conoce
    activo             boolean NOT NULL DEFAULT true,
    clave_definida     boolean NOT NULL DEFAULT false,     -- false = nunca la ha definido: se le exige al ingresar
    clave_vence        timestamptz,                        -- vencimiento de la contraseña (CLAVE_VIGENCIA_DIAS)
    debe_cambiar_clave boolean NOT NULL DEFAULT false,     -- el administrador exigió el cambio; distinto de "olvidé mi contraseña"
    clave_actualizada  timestamptz,                        -- invalida las sesiones iniciadas antes
    token_hash         char(64),                           -- SHA-256 del enlace de un solo uso
    token_vence        timestamptz,
    intentos           smallint NOT NULL DEFAULT 0,
    bloqueado_hasta    timestamptz,
    ultimo_ingreso     timestamptz,
    alcance_fuerza     text,                               -- Sanidad (DISAN o JEFSA): su fuerza; FK en la migración de catálogos
    alcance_punto      bigint,                             -- ESM: su ESM
    alcance_regional   bigint,                             -- OPL regional: su regional
    sesion_huella      char(64),                           -- SHA-256 de la marca de la única sesión vigente (Anexo 5, 3.2)
    creado             timestamptz NOT NULL DEFAULT now(),
    actualizado        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_usuarios_token ON usuarios (token_hash) WHERE token_hash IS NOT NULL;

CREATE TABLE usuarios_perfiles (
    usuario_id bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    perfil_id  smallint NOT NULL REFERENCES perfiles (id) ON DELETE RESTRICT,
    PRIMARY KEY (usuario_id, perfil_id)
);
CREATE INDEX ix_usuarios_perfiles_perfil ON usuarios_perfiles (perfil_id);

-- ---------- Segundo factor: código de 6 dígitos al correo (decisión del 2026-10-20) ----------
CREATE TABLE mfa_codigos (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id  bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    codigo_hash char(64) NOT NULL,
    creado      timestamptz NOT NULL,
    vence       timestamptz NOT NULL,
    intentos    smallint NOT NULL DEFAULT 0,
    usado       timestamptz,
    ip          text
);
CREATE INDEX ix_mfa_codigos_usuario ON mfa_codigos (usuario_id, creado);

-- ---------- Sesiones (Spring Session JDBC; no hay Redis, decisión del 2026-10-20) ----------
CREATE TABLE spring_session (
    primary_id            char(36) NOT NULL,
    session_id            char(36) NOT NULL,
    creation_time         bigint NOT NULL,
    last_access_time      bigint NOT NULL,
    max_inactive_interval int NOT NULL,
    expiry_time           bigint NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36) NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- ---------- Bitácora (Anexo 5, prueba 15; decisión 5), particionada por mes ----------
-- Se conserva toda (duda 7.5): las particiones solo facilitan una retención futura. Sin FK al usuario a propósito.
CREATE SEQUENCE log_transacciones_id_seq AS bigint;
CREATE TABLE log_transacciones (
    id          bigint NOT NULL DEFAULT nextval('log_transacciones_id_seq'),
    fecha       timestamptz NOT NULL,
    operacion   text NOT NULL CHECK (operacion IN ('insercion', 'actualizacion', 'eliminacion', 'consulta', 'ingreso', 'salida')),
    tipo        text NOT NULL CHECK (char_length(tipo) <= 50),
    modulo      text CHECK (char_length(modulo) <= 40),
    usuario_id  bigint,
    usuario     text CHECK (char_length(usuario) <= 60),
    descripcion text CHECK (char_length(descripcion) <= 500),
    ip          text CHECK (char_length(ip) <= 45),
    PRIMARY KEY (fecha, id)
) PARTITION BY RANGE (fecha);
ALTER SEQUENCE log_transacciones_id_seq OWNED BY log_transacciones.id;

-- Una partición por mes durante el contrato (43 meses, hasta 2030-07-31) con holgura; lo que caiga fuera va a la de defecto.
-- Los meses se cortan a la medianoche de Bogotá, sin importar la zona horaria de la sesión que migra.
DO $$
DECLARE
    mes date := date '2026-10-01';
BEGIN
    WHILE mes < date '2031-01-01' LOOP
        EXECUTE format('CREATE TABLE log_transacciones_%s PARTITION OF log_transacciones FOR VALUES FROM (%L) TO (%L)',
                       to_char(mes, 'YYYY_MM'), (mes::timestamp AT TIME ZONE 'America/Bogota'), ((mes + interval '1 month')::timestamp AT TIME ZONE 'America/Bogota'));
        mes := mes + interval '1 month';
    END LOOP;
END $$;
CREATE TABLE log_transacciones_defecto PARTITION OF log_transacciones DEFAULT;

CREATE INDEX ix_log_usuario_fecha ON log_transacciones (usuario_id, fecha, id);
CREATE INDEX ix_log_operacion_fecha ON log_transacciones (operacion, fecha);
