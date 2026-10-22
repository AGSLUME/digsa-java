-- Suite DIGSA · Módulo de presupuesto (docs/especificaciones/presupuesto; Anexo 5 §4.6, Anexo 2 L739-741; decisiones del usuario del
-- 2026-10-22: vigencia anual con vista «Contrato», umbrales sobre lo facturado, la ejecución del mes reemplaza con versiones).
-- Los movimientos, la ejecución, la proyección, los umbrales y las notificaciones son solo de inserción: una corrección es un registro
-- nuevo. Lo único que se actualiza son los saldos (mantenidos en la misma transacción que el cambio), la marca «vigente» de las versiones
-- y los catálogos.

-- Vigencia fiscal anual; puede ser parcial (la primera y la última del contrato del OPL). Cerrada: no admite novedades ni ejecución.
CREATE TABLE presupuesto_vigencias (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    anio        smallint NOT NULL UNIQUE CHECK (anio BETWEEN 2020 AND 2100),
    inicio      date NOT NULL,
    fin         date NOT NULL,
    estado      text NOT NULL DEFAULT 'abierta' CHECK (estado IN ('abierta', 'cerrada')),
    creado_por  bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    creado      timestamptz NOT NULL DEFAULT now(),
    actualizado timestamptz NOT NULL DEFAULT now(),
    CHECK (inicio <= fin AND extract(year FROM inicio) = anio AND extract(year FROM fin) = anio)
);
CREATE INDEX ix_presupuesto_vigencias_creado_por ON presupuesto_vigencias (creado_por);

-- Modalidades de facturación (no es par_modalidades, que es la modalidad del punto). Las 3 del Anexo 5 §4.6 activas; las otras 3 del
-- Anexo 3 del OPL §38 precargadas inactivas (duda P.13).
CREATE TABLE presupuesto_modalidades (
    codigo      text PRIMARY KEY CHECK (codigo ~ '^[a-z_]{3,30}$'),
    nombre      text NOT NULL UNIQUE CHECK (char_length(nombre) BETWEEN 3 AND 60),
    activa      boolean NOT NULL DEFAULT true,
    orden       smallint NOT NULL DEFAULT 0,
    actualizado timestamptz NOT NULL DEFAULT now()
);
INSERT INTO presupuesto_modalidades (codigo, nombre, activa, orden) VALUES
  ('dispensacion', 'Dispensación', true, 1),
  ('requisicion', 'Órdenes de requisición', true, 2),
  ('suministro', 'Suministro', true, 3),
  ('consignacion', 'Consignación', false, 4),
  ('satelite', 'Satélite', false, 5),
  ('intramural_homil', 'Intramural HOMIL', false, 6)
ON CONFLICT (codigo) DO NOTHING;

-- Línea presupuestal: vigencia × fuerza × ESM × modalidad. La fuerza es la del ESM, salvo en los ESM con fuerza «NA» (HOMIL), donde es
-- la de adscripción. Los totales de fuerza y nacional son siempre la suma de las líneas.
CREATE TABLE presupuesto_lineas (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vigencia_id bigint NOT NULL REFERENCES presupuesto_vigencias (id) ON DELETE RESTRICT,
    fuerza      text NOT NULL REFERENCES par_fuerzas (codigo) ON DELETE RESTRICT CHECK (fuerza <> 'NA'),
    punto_id    bigint NOT NULL REFERENCES puntos (id) ON DELETE RESTRICT,
    modalidad   text NOT NULL REFERENCES presupuesto_modalidades (codigo) ON DELETE RESTRICT,
    creado      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (vigencia_id, punto_id, modalidad, fuerza),
    UNIQUE (id, vigencia_id)                                            -- destino de las FK compuestas: nada cruza vigencias
);
CREATE INDEX ix_presupuesto_lineas_vigencia ON presupuesto_lineas (vigencia_id, fuerza, modalidad);
CREATE INDEX ix_presupuesto_lineas_punto ON presupuesto_lineas (punto_id);
CREATE INDEX ix_presupuesto_lineas_modalidad ON presupuesto_lineas (modalidad);
CREATE INDEX ix_presupuesto_lineas_fuerza ON presupuesto_lineas (fuerza);

-- Saldo de cada línea, mantenido en la misma transacción que el movimiento o la ejecución (bloqueo de fila en orden de linea_id).
-- asignado = Σ movimientos; facturado y reconocido = Σ ejecución vigente. El tablero agrega sobre esta tabla, sin recorrer el historial.
CREATE TABLE presupuesto_saldos (
    linea_id    bigint PRIMARY KEY REFERENCES presupuesto_lineas (id) ON DELETE RESTRICT,
    asignado    numeric(18, 2) NOT NULL DEFAULT 0 CHECK (asignado >= 0),
    facturado   numeric(18, 2) NOT NULL DEFAULT 0 CHECK (facturado >= 0),
    reconocido  numeric(18, 2) NOT NULL DEFAULT 0 CHECK (reconocido >= 0),
    version     bigint NOT NULL DEFAULT 0,
    actualizado timestamptz NOT NULL DEFAULT now()
);

-- Archivo del soporte de una novedad (oficio de la DIGSA). No cabe en «archivos», que exige visita.
CREATE TABLE presupuesto_soportes (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre_original text NOT NULL CHECK (char_length(nombre_original) BETWEEN 1 AND 200),
    ruta            text NOT NULL UNIQUE CHECK (char_length(ruta) <= 300),
    tipo            text NOT NULL CHECK (char_length(tipo) <= 100),
    tamano          bigint NOT NULL CHECK (tamano > 0),
    sha256          char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    subido_por      bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    subido          timestamptz NOT NULL
);
CREATE INDEX ix_presupuesto_soportes_subido_por ON presupuesto_soportes (subido_por);

-- Lote de carga (asignación, ejecución o proyección), idempotente por la huella del contenido: el mismo archivo no se aplica dos veces.
CREATE TABLE presupuesto_lotes (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    clase           text NOT NULL CHECK (clase IN ('asignacion', 'ejecucion', 'proyeccion')),
    fuente          text NOT NULL CHECK (fuente IN ('digsa', 'opl', 'cuentas_medicas')),  -- el registro manual no tiene lote
    sha256          char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    nombre_original text NOT NULL CHECK (char_length(nombre_original) BETWEEN 1 AND 200),
    filas           integer NOT NULL CHECK (filas BETWEEN 1 AND 5000),
    total           numeric(18, 2) NOT NULL,
    aplicado_por    bigint REFERENCES usuarios (id) ON DELETE RESTRICT,           -- NULL: lo publicó otro módulo (cuentas médicas)
    aplicado        timestamptz NOT NULL,
    UNIQUE (clase, sha256)
);
CREATE INDEX ix_presupuesto_lotes_aplicado_por ON presupuesto_lotes (aplicado_por);

-- Movimiento presupuestal: asignación inicial, adición, disminución, reajuste, rebalanceo o recomposición, con el soporte de la DIGSA.
CREATE TABLE presupuesto_movimientos (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vigencia_id    bigint NOT NULL REFERENCES presupuesto_vigencias (id) ON DELETE RESTRICT,
    tipo           text NOT NULL CHECK (tipo IN ('asignacion_inicial', 'adicion', 'disminucion', 'reajuste', 'rebalanceo', 'recomposicion')),
    soporte        text NOT NULL CHECK (char_length(btrim(soporte)) BETWEEN 5 AND 300),
    soporte_id     bigint REFERENCES presupuesto_soportes (id) ON DELETE RESTRICT,
    lote_id        bigint REFERENCES presupuesto_lotes (id) ON DELETE RESTRICT,
    porcentaje     numeric(7, 2),                                                  -- reajuste por porcentaje sobre un ámbito
    observacion    text CHECK (char_length(observacion) <= 1000),
    neto           numeric(18, 2) NOT NULL,
    registrado_por bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    creado         timestamptz NOT NULL,
    CHECK (tipo NOT IN ('rebalanceo', 'recomposicion') OR neto = 0),
    CHECK (porcentaje IS NULL OR tipo = 'reajuste'),
    UNIQUE (id, vigencia_id)
);
CREATE INDEX ix_presupuesto_movimientos_vigencia ON presupuesto_movimientos (vigencia_id, creado DESC, id DESC);
CREATE INDEX ix_presupuesto_movimientos_soporte ON presupuesto_movimientos (soporte_id);
CREATE INDEX ix_presupuesto_movimientos_lote ON presupuesto_movimientos (lote_id);
CREATE INDEX ix_presupuesto_movimientos_registrado_por ON presupuesto_movimientos (registrado_por);

-- Cada línea afectada por un movimiento, con el asignado antes y después y lo ejecutado (facturado) en ese momento.
CREATE TABLE presupuesto_movimientos_lineas (
    movimiento_id        bigint NOT NULL,
    vigencia_id          bigint NOT NULL,
    linea_id             bigint NOT NULL,
    valor                numeric(18, 2) NOT NULL CHECK (valor <> 0),
    asignado_antes       numeric(18, 2) NOT NULL,
    asignado_despues     numeric(18, 2) NOT NULL CHECK (asignado_despues >= 0),
    ejecutado_al_momento numeric(18, 2) NOT NULL,
    PRIMARY KEY (movimiento_id, linea_id),
    -- El movimiento y la línea son de la misma vigencia (una novedad de 2027 no mueve una línea de 2026)
    FOREIGN KEY (movimiento_id, vigencia_id) REFERENCES presupuesto_movimientos (id, vigencia_id) ON DELETE RESTRICT,
    FOREIGN KEY (linea_id, vigencia_id) REFERENCES presupuesto_lineas (id, vigencia_id) ON DELETE RESTRICT,
    CHECK (asignado_despues = asignado_antes + valor)
);
CREATE INDEX ix_presupuesto_movimientos_lineas_linea ON presupuesto_movimientos_lineas (linea_id, vigencia_id);

-- Ejecución mensual por línea y punto de origen (el satélite, o el mismo ESM). Una versión nueva del mismo periodo reemplaza a la
-- anterior como vigente sin borrarla. reconocido NULL = sin conciliar.
CREATE TABLE presupuesto_ejecucion (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    linea_id        bigint NOT NULL REFERENCES presupuesto_lineas (id) ON DELETE RESTRICT,
    punto_origen_id bigint NOT NULL REFERENCES puntos (id) ON DELETE RESTRICT,
    periodo         date NOT NULL CHECK (extract(day FROM periodo) = 1),
    facturado       numeric(18, 2) NOT NULL CHECK (facturado >= 0),
    reconocido      numeric(18, 2) CHECK (reconocido IS NULL OR reconocido BETWEEN 0 AND facturado),
    fuente          text NOT NULL CHECK (fuente IN ('digsa', 'opl', 'manual', 'cuentas_medicas')),
    referencia      text CHECK (char_length(referencia) <= 120),
    lote_id         bigint REFERENCES presupuesto_lotes (id) ON DELETE RESTRICT,
    vigente         boolean NOT NULL DEFAULT true,
    reemplazada_en  timestamptz,
    registrado_por  bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    creado          timestamptz NOT NULL,
    CHECK (vigente = (reemplazada_en IS NULL))
);
CREATE UNIQUE INDEX ux_presupuesto_ejecucion_vigente ON presupuesto_ejecucion (linea_id, periodo, punto_origen_id) WHERE vigente;
CREATE INDEX ix_presupuesto_ejecucion_linea ON presupuesto_ejecucion (linea_id, periodo, creado DESC);
CREATE INDEX ix_presupuesto_ejecucion_periodo ON presupuesto_ejecucion (periodo) INCLUDE (linea_id, facturado, reconocido) WHERE vigente;
CREATE INDEX ix_presupuesto_ejecucion_origen ON presupuesto_ejecucion (punto_origen_id);
CREATE INDEX ix_presupuesto_ejecucion_lote ON presupuesto_ejecucion (lote_id);
CREATE INDEX ix_presupuesto_ejecucion_registrado_por ON presupuesto_ejecucion (registrado_por);

-- Proyección mensual cargada. Sin filas vigentes para una línea, la proyección se calcula lineal (no se guarda).
CREATE TABLE presupuesto_proyeccion (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    linea_id       bigint NOT NULL REFERENCES presupuesto_lineas (id) ON DELETE RESTRICT,
    periodo        date NOT NULL CHECK (extract(day FROM periodo) = 1),
    valor          numeric(18, 2) NOT NULL CHECK (valor >= 0),
    lote_id        bigint NOT NULL REFERENCES presupuesto_lotes (id) ON DELETE RESTRICT,
    vigente        boolean NOT NULL DEFAULT true,
    reemplazada_en timestamptz,
    creado         timestamptz NOT NULL,
    CHECK (vigente = (reemplazada_en IS NULL))
);
CREATE UNIQUE INDEX ux_presupuesto_proyeccion_vigente ON presupuesto_proyeccion (linea_id, periodo) WHERE vigente;
CREATE INDEX ix_presupuesto_proyeccion_lote ON presupuesto_proyeccion (lote_id);

-- Consecutivo anual de las notificaciones formales (PRE-2027-0001): una fila por año, bloqueada al tomar el número.
CREATE TABLE presupuesto_consecutivos (
    anio   smallint PRIMARY KEY CHECK (anio BETWEEN 2020 AND 2100),
    ultimo integer NOT NULL DEFAULT 0 CHECK (ultimo >= 0)
);

-- Notificación formal y electrónica (Anexo 2 L741) al buzón de la Sanidad de la fuerza (par_fuerzas.correos) y de la DIGSA (correo_digsa).
-- Sin datos personales. estado: encolada; sin_buzon_fuerza (salió solo a la DIGSA: reenviar al tener el buzón); sin_destinatarios.
CREATE TABLE presupuesto_notificaciones (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    consecutivo text NOT NULL UNIQUE CHECK (consecutivo ~ '^[A-Z]{2,10}-[0-9]{4}-[0-9]{4,}$'),
    tipo        text NOT NULL CHECK (tipo IN ('umbral', 'desviacion')),
    nivel       text NOT NULL CHECK (nivel IN ('esm', 'fuerza', 'nacional')),
    vigencia_id bigint NOT NULL REFERENCES presupuesto_vigencias (id) ON DELETE RESTRICT,
    linea_id    bigint,
    fuerza      text REFERENCES par_fuerzas (codigo) ON DELETE RESTRICT,
    corte       date,
    asunto      text NOT NULL CHECK (char_length(asunto) <= 300),
    texto       text NOT NULL CHECK (char_length(texto) <= 4000),
    para        text[] NOT NULL DEFAULT '{}' CHECK (cardinality(para) <= 50),
    correo_id   bigint REFERENCES correos_salida (id) ON DELETE RESTRICT,
    estado      text NOT NULL CHECK (estado IN ('encolada', 'sin_buzon_fuerza', 'sin_destinatarios')),
    creado      timestamptz NOT NULL,
    FOREIGN KEY (linea_id, vigencia_id) REFERENCES presupuesto_lineas (id, vigencia_id) ON DELETE RESTRICT,
    CHECK (nivel <> 'esm' OR linea_id IS NOT NULL),
    CHECK (nivel <> 'fuerza' OR fuerza IS NOT NULL)
);
CREATE INDEX ix_presupuesto_notificaciones_vigencia ON presupuesto_notificaciones (vigencia_id, creado DESC);
CREATE INDEX ix_presupuesto_notificaciones_linea ON presupuesto_notificaciones (linea_id, vigencia_id);
CREATE INDEX ix_presupuesto_notificaciones_fuerza ON presupuesto_notificaciones (fuerza);
CREATE INDEX ix_presupuesto_notificaciones_correo ON presupuesto_notificaciones (correo_id);

-- Umbral alcanzado por línea: uno solo por línea y umbral (la línea ya es de una vigencia), también con dos cargas a la vez (PK).
-- notificada: solo el umbral más alto cruzado en un mismo cambio lleva aviso propio.
CREATE TABLE presupuesto_umbrales (
    linea_id        bigint NOT NULL REFERENCES presupuesto_lineas (id) ON DELETE RESTRICT,
    umbral          smallint NOT NULL CHECK (umbral IN (25, 50, 75, 100)),
    porcentaje      numeric(9, 2),                                                 -- NULL: sin presupuesto asignado
    base            text NOT NULL CHECK (base IN ('facturado', 'reconocido')),
    ejecutado       numeric(18, 2) NOT NULL,
    asignado        numeric(18, 2) NOT NULL,
    alcanzado       timestamptz NOT NULL,
    notificada      boolean NOT NULL,
    notificacion_id bigint REFERENCES presupuesto_notificaciones (id) ON DELETE RESTRICT,
    evento_id       bigint REFERENCES alertas_eventos (id) ON DELETE RESTRICT,    -- la alerta del catálogo que avisó
    PRIMARY KEY (linea_id, umbral)
);
CREATE INDEX ix_presupuesto_umbrales_notificacion ON presupuesto_umbrales (notificacion_id);
CREATE INDEX ix_presupuesto_umbrales_evento ON presupuesto_umbrales (evento_id);

-- Desviación frente a la proyección (Anexo 5 §4.6; caso M7-3): una por vigencia, corte, nivel (línea, fuerza o nacional) y sentido.
CREATE TABLE presupuesto_desviaciones (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vigencia_id     bigint NOT NULL REFERENCES presupuesto_vigencias (id) ON DELETE RESTRICT,
    corte           date NOT NULL CHECK (extract(day FROM corte) = 1),
    nivel           text NOT NULL CHECK (nivel IN ('esm', 'fuerza', 'nacional')),
    linea_id        bigint,
    fuerza          text REFERENCES par_fuerzas (codigo) ON DELETE RESTRICT,
    sentido         text NOT NULL CHECK (sentido IN ('exceso', 'subejecucion')),
    ejecutado       numeric(18, 2) NOT NULL,
    proyectado      numeric(18, 2) NOT NULL,
    desviacion      numeric(12, 2),                                                -- NULL: proyectado 0
    base            text NOT NULL CHECK (base IN ('facturado', 'reconocido')),
    notificacion_id bigint REFERENCES presupuesto_notificaciones (id) ON DELETE RESTRICT,
    creado          timestamptz NOT NULL,
    FOREIGN KEY (linea_id, vigencia_id) REFERENCES presupuesto_lineas (id, vigencia_id) ON DELETE RESTRICT,
    CHECK ((nivel = 'esm') = (linea_id IS NOT NULL) AND (nivel = 'fuerza') = (fuerza IS NOT NULL)),
    UNIQUE NULLS NOT DISTINCT (vigencia_id, corte, nivel, linea_id, fuerza, sentido)
);
CREATE INDEX ix_presupuesto_desviaciones_notificacion ON presupuesto_desviaciones (notificacion_id);
CREATE INDEX ix_presupuesto_desviaciones_linea ON presupuesto_desviaciones (linea_id, vigencia_id);
CREATE INDEX ix_presupuesto_desviaciones_fuerza ON presupuesto_desviaciones (fuerza);

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    -- Catálogos y saldos: se editan
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON presupuesto_vigencias, presupuesto_modalidades, presupuesto_saldos, presupuesto_consecutivos TO %I', app);
    -- Solo de inserción (invariante de la spec): sin UPDATE ni DELETE
    EXECUTE format('GRANT SELECT, INSERT ON presupuesto_lineas, presupuesto_soportes, presupuesto_lotes, presupuesto_movimientos, '
                   'presupuesto_movimientos_lineas TO %I', app);
    -- Versiones: solo se marca la reemplazada
    EXECUTE format('GRANT SELECT, INSERT ON presupuesto_ejecucion, presupuesto_proyeccion TO %I', app);
    EXECUTE format('GRANT UPDATE (vigente, reemplazada_en) ON presupuesto_ejecucion, presupuesto_proyeccion TO %I', app);
    -- Umbrales, desviaciones y notificaciones: el enlace a la notificación se pone después de crearla; la notificación, al reenviarla
    EXECUTE format('GRANT SELECT, INSERT ON presupuesto_umbrales, presupuesto_desviaciones, presupuesto_notificaciones TO %I', app);
    EXECUTE format('GRANT UPDATE (notificacion_id, evento_id) ON presupuesto_umbrales TO %I', app);
    EXECUTE format('GRANT UPDATE (notificacion_id) ON presupuesto_desviaciones TO %I', app);
    EXECUTE format('GRANT UPDATE (correo_id, estado, para) ON presupuesto_notificaciones TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
