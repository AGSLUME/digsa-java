-- Suite DIGSA · Fase 10: bandeja de salida de correos y SMS, y alertas críticas (docs/especificaciones/exportacion-alertas, «Datos que
-- la reescritura Java debe agregar» puntos 7 a 10; GMI P75 «en tiempo real o cuasi real» y P77 b «críticas por correo y además por SMS»).

-- Catálogo de tipos de alerta, editable por el administrador (activa, SMS y destinatarios por perfil o usuario). Las críticas
-- (continuidad, seguridad, disponibilidad o integridad) van por correo y SMS; las operativas, solo por correo.
CREATE TABLE alertas_criticas (
    tipo            text PRIMARY KEY CHECK (tipo ~ '^[a-z_]{3,40}$'),
    nombre          text NOT NULL CHECK (char_length(nombre) BETWEEN 3 AND 80),
    descripcion     text CHECK (char_length(descripcion) <= 300),
    critica         boolean NOT NULL,
    activa          boolean NOT NULL DEFAULT true,
    sms             boolean NOT NULL DEFAULT false,
    perfiles        text[] NOT NULL DEFAULT '{}',                       -- nombres del enum Perfil (ADMINISTRADOR, COORDINADOR…)
    usuarios        bigint[] NOT NULL DEFAULT '{}',
    actualizado     timestamptz NOT NULL DEFAULT now(),
    actualizado_por bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    CHECK (critica OR NOT sms)                                          -- las operativas nunca por SMS
);
CREATE INDEX ix_alertas_criticas_actualizado_por ON alertas_criticas (actualizado_por);

-- Cada vez que se dispara una alerta. Una crítica queda abierta hasta que se resuelve (el monitor de salud la cierra al recuperarse o
-- la cierra un usuario); mientras está abierta, un nuevo disparo con la misma clave solo cuenta (veces) y no repite el aviso antes de
-- una hora. Las operativas nacen cerradas: son un aviso. El detalle no lleva datos personales.
CREATE TABLE alertas_eventos (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo         text NOT NULL REFERENCES alertas_criticas (tipo) ON DELETE RESTRICT,
    clave        text NOT NULL CHECK (char_length(clave) BETWEEN 1 AND 120),   -- deduplicación (p. ej. acta:15, opl:2026-10-22, almacen)
    detalle      text NOT NULL CHECK (char_length(detalle) <= 500),
    abierta      boolean NOT NULL DEFAULT true,
    veces        integer NOT NULL DEFAULT 1 CHECK (veces >= 1),
    creado       timestamptz NOT NULL,
    ultimo       timestamptz NOT NULL,                                  -- último disparo
    ultimo_aviso timestamptz,                                           -- último correo encolado
    ultimo_sms   timestamptz,                                           -- último SMS encolado (no más de uno por hora)
    cerrada_en   timestamptz,
    cerrada_por  bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    aviso_unico  boolean NOT NULL DEFAULT false,                        -- operativa: un solo aviso por (tipo, clave), atómico
    CHECK (abierta = (cerrada_en IS NULL)),
    CHECK (NOT (aviso_unico AND abierta))
);
CREATE UNIQUE INDEX ux_alertas_eventos_abierta ON alertas_eventos (tipo, clave) WHERE abierta;
-- Las operativas avisan una vez por clave: dos disparos a la vez no avisan dos veces (INSERT … ON CONFLICT DO NOTHING)
CREATE UNIQUE INDEX ux_alertas_eventos_unico ON alertas_eventos (tipo, clave) WHERE aviso_unico;
CREATE INDEX ix_alertas_eventos_tipo_clave ON alertas_eventos (tipo, clave);
CREATE INDEX ix_alertas_eventos_creado ON alertas_eventos (creado DESC);
CREATE INDEX ix_alertas_eventos_cerrada_por ON alertas_eventos (cerrada_por);

-- Bandeja de salida de correos (duda 20.6): se encola en la misma transacción del cambio y se envía en segundos después de confirmar;
-- reintentos a 1, 5, 30 min y 2 h, máximo 5 intentos. Varias réplicas toman filas distintas (FOR UPDATE SKIP LOCKED). Los adjuntos son
-- rutas del almacén, no bytes. creado/enviado miden la latencia del aviso.
CREATE TABLE correos_salida (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    origen     text NOT NULL CHECK (char_length(origen) BETWEEN 1 AND 60),    -- tipo de aviso (solicitud_nueva, alerta:almacen_no_disponible…)
    referencia text CHECK (char_length(referencia) <= 120),                    -- visita, acta o solicitud (solo identificadores)
    alerta_id  bigint REFERENCES alertas_eventos (id) ON DELETE RESTRICT,
    critico    boolean NOT NULL DEFAULT false,                                 -- aviso de una alerta crítica: agotado dispara correo_agotado
    para       text[] NOT NULL CHECK (cardinality(para) BETWEEN 1 AND 100),
    copia      text[] NOT NULL DEFAULT '{}',
    asunto     text NOT NULL CHECK (char_length(asunto) <= 300),
    html       text NOT NULL,
    adjuntos   jsonb NOT NULL DEFAULT '[]',                                    -- [{nombre, tipo, ruta}]
    estado     text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'enviando', 'enviado', 'parcial', 'fallido')),
    intentos   integer NOT NULL DEFAULT 0 CHECK (intentos BETWEEN 0 AND 20),
    proximo    timestamptz NOT NULL,
    tomado     timestamptz,                                                    -- cuándo lo tomó un despachador (recupera los colgados)
    error      text CHECK (char_length(error) <= 500),                         -- sin direcciones
    creado     timestamptz NOT NULL,
    enviado    timestamptz,
    CHECK ((estado IN ('enviado', 'parcial')) = (enviado IS NOT NULL))
);
-- Solo los pendientes (el índice queda pequeño aunque la tabla crezca con los enviados), en el orden de la toma: los críticos primero
CREATE INDEX ix_correos_salida_pendientes ON correos_salida (critico DESC, proximo, id) WHERE estado = 'pendiente';
CREATE INDEX ix_correos_salida_enviando ON correos_salida (tomado) WHERE estado = 'enviando';
CREATE INDEX ix_correos_salida_alerta ON correos_salida (alerta_id) WHERE alerta_id IS NOT NULL;
CREATE INDEX ix_correos_salida_creado ON correos_salida (creado DESC);

-- SMS de las alertas críticas. El teléfono solo enmascarado (Ley 1581): el despachador toma el número vigente del usuario al enviar.
-- «omitido»: el destinatario no tiene un móvil válido o el SMS no está configurado (la alerta queda solo por correo).
CREATE TABLE sms_salida (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alerta_id  bigint NOT NULL REFERENCES alertas_eventos (id) ON DELETE RESTRICT,
    usuario_id bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    telefono   text NOT NULL CHECK (char_length(telefono) <= 20),
    texto      text NOT NULL CHECK (char_length(texto) <= 160),
    estado     text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'enviando', 'enviado', 'fallido', 'omitido')),
    intentos   integer NOT NULL DEFAULT 0 CHECK (intentos BETWEEN 0 AND 20),
    proximo    timestamptz NOT NULL,
    tomado     timestamptz,
    error      text CHECK (char_length(error) <= 500),
    creado     timestamptz NOT NULL,
    enviado    timestamptz,
    CHECK ((estado = 'enviado') = (enviado IS NOT NULL))
);
CREATE INDEX ix_sms_salida_pendientes ON sms_salida (proximo) WHERE estado = 'pendiente';
CREATE INDEX ix_sms_salida_enviando ON sms_salida (tomado) WHERE estado = 'enviando';
CREATE INDEX ix_sms_salida_alerta ON sms_salida (alerta_id);
CREATE INDEX ix_sms_salida_usuario ON sms_salida (usuario_id);

-- Catálogo inicial (spec: recomendación; GMI P77 b remite los destinatarios y niveles al Anexo Técnico definitivo)
INSERT INTO alertas_criticas (tipo, nombre, descripcion, critica, sms, perfiles) VALUES
  ('tarea_diaria_fallida', 'Tarea diaria con fallos', 'Una tarea diaria (OPL o recordatorio de seguimiento) terminó con fallos', true, true, '{ADMINISTRADOR}'),
  ('correo_agotado', 'Aviso crítico sin entregar', 'El correo de una alerta crítica agotó sus 5 intentos', true, true, '{ADMINISTRADOR}'),
  ('integridad_archivo', 'Archivo sin integridad', 'Un archivo no coincide con su huella SHA-256 o falta en el almacén', true, true, '{ADMINISTRADOR}'),
  ('almacen_no_disponible', 'Base o almacén sin respuesta', 'La base de datos o el almacén de archivos no responden a la verificación de salud', true, true, '{ADMINISTRADOR}'),
  ('bloqueo_masivo', 'Bloqueo masivo de cuentas', 'Varias cuentas bloqueadas por intentos fallidos en una hora (posible ataque)', true, true, '{ADMINISTRADOR}'),
  ('intervencion_inmediata', 'Hallazgo de intervención inmediata', 'Acta enviada con hallazgos marcados de intervención inmediata', false, false, '{COORDINADOR,DIGSA}'),
  ('plan_opl_vencido', 'Plan de mejoramiento vencido', 'El plan de mejoramiento del OPL venció sin firma', false, false, '{COORDINADOR}')
ON CONFLICT (tipo) DO NOTHING;

INSERT INTO par_parametros (clave, valor, descripcion, fuente, editable) VALUES
  ('bandeja_retencion_dias', '90', 'Bandeja de salida: días que se conservan los correos y SMS ya enviados, fallidos u omitidos antes de purgarlos (0 = no purgar)',
   'Spec exportacion-alertas (bandeja de salida)', true),
  ('alertas_bloqueo_cuentas', '5', 'Alertas: cuentas bloqueadas por intentos en una hora que disparan la alerta de bloqueo masivo (0 = desactivada)',
   'Spec exportacion-alertas; GMI P77 b', true)
ON CONFLICT (clave) DO NOTHING;

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    -- El catálogo lo crea la migración; la aplicación solo lo edita
    EXECUTE format('GRANT SELECT, UPDATE ON alertas_criticas TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON alertas_eventos TO %I', app);
    -- DELETE solo para la purga de los envíos terminados (bandeja_retencion_dias)
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON correos_salida, sms_salida TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
