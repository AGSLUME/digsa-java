-- Suite DIGSA · módulo de reportes, fases R2 y R3 (docs/especificaciones/reportes): registro de cada reporte generado (Anexo 5, 2.10)
-- y repositorio de las descargas del día (2.7). Cada generación (en pantalla, descarga o envío) queda con su código de verificación,
-- los parámetros resueltos, el alcance, la huella de los datos (sin usuario ni fecha) y, si hay archivo, la huella del archivo. El
-- archivo vive en el almacén hasta las 23:59:59 de Bogotá del día en que se pidió; después se borra (Ley 1581) y el registro queda
-- para verificar. Rango de migraciones de la sesión de reportes: V50 a V59.
--
-- Estados: vista (en pantalla, sin archivo) · pendiente (diferido, en la cola) · generando (lo toma una petición o la tarea) ·
-- lista (archivo en el almacén) · fallida · vencida (el día terminó: archivo borrado, registro conservado).

CREATE TABLE reportes_generados (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo          text NOT NULL CONSTRAINT uq_rg_codigo UNIQUE
                    CONSTRAINT ck_rg_codigo CHECK (codigo ~ '^RPT-[0-9]{8}-[A-HJ-NP-Z2-9]{8}$'),
    reporte         text NOT NULL CONSTRAINT ck_rg_reporte CHECK (reporte ~ '^[a-z][a-z0-9_]{2,40}$'),
    version         int NOT NULL CONSTRAINT ck_rg_version CHECK (version >= 1),
    titulo          text NOT NULL CONSTRAINT ck_rg_titulo CHECK (char_length(titulo) <= 200),
    usuario_id      bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    usuario         text NOT NULL CONSTRAINT ck_rg_usuario CHECK (char_length(usuario) <= 210),   -- «Nombre (usuario)» del encabezado
    alcance         text NOT NULL CONSTRAINT ck_rg_alcance CHECK (char_length(alcance) <= 60),   -- todo | opl | fuerza:EJC | esm:12…
    parametros      jsonb NOT NULL CONSTRAINT ck_rg_parametros CHECK (jsonb_typeof(parametros) = 'object'),   -- desde, hasta, agrupación, filtros
    columnas        jsonb NOT NULL CONSTRAINT ck_rg_columnas CHECK (jsonb_typeof(columnas) = 'array'),       -- la variante que vio el perfil
    formato         text CONSTRAINT ck_rg_formato CHECK (formato IN ('xlsx', 'csv', 'pdf', 'txt')),          -- NULL = en pantalla
    estado          text NOT NULL CONSTRAINT ck_rg_estado CHECK (estado IN ('vista', 'pendiente', 'generando', 'lista', 'fallida', 'vencida')),
    ruta            text CONSTRAINT uq_rg_ruta UNIQUE
                    CONSTRAINT ck_rg_ruta CHECK (ruta ~ '^reportes/[0-9]{4}/[0-9]{2}/[0-9]{2}/[A-Za-z0-9_-]+\.(xlsx|csv|pdf|txt)$'),
    tamano          bigint CONSTRAINT ck_rg_tamano CHECK (tamano >= 0),
    filas           bigint CONSTRAINT ck_rg_filas CHECK (filas >= 0),
    truncado        boolean NOT NULL DEFAULT false,                          -- en pantalla había más filas de las mostradas
    huella_datos    char(64) CONSTRAINT ck_rg_huella_datos CHECK (huella_datos ~ '^[0-9a-f]{64}$'),
    huella_archivo  char(64) CONSTRAINT ck_rg_huella_archivo CHECK (huella_archivo ~ '^[0-9a-f]{64}$'),
    ms_consulta     int CONSTRAINT ck_rg_ms_consulta CHECK (ms_consulta >= 0),
    ms_archivo      int CONSTRAINT ck_rg_ms_archivo CHECK (ms_archivo >= 0),
    ms_total        int CONSTRAINT ck_rg_ms_total CHECK (ms_total >= 0),
    error           text CONSTRAINT ck_rg_error CHECK (char_length(error) <= 200),   -- mensaje genérico, sin detalle interno
    intentos        int NOT NULL DEFAULT 0 CONSTRAINT ck_rg_intentos CHECK (intentos BETWEEN 0 AND 5),
    creado          timestamptz NOT NULL DEFAULT now(),
    iniciado        timestamptz,                                             -- cuando pasó a generando
    terminado       timestamptz,
    vence           timestamptz,                                             -- fin del día de Bogotá: hasta entonces está en las descargas
    archivo_borrado timestamptz,                                             -- la limpieza borró el archivo del almacén
    CONSTRAINT ck_rg_vista CHECK ((estado = 'vista') = (formato IS NULL)),
    CONSTRAINT ck_rg_vista_sin_archivo CHECK (estado <> 'vista' OR (ruta IS NULL AND huella_archivo IS NULL AND vence IS NULL)),
    CONSTRAINT ck_rg_archivo_vence CHECK (formato IS NULL OR vence IS NOT NULL),
    CONSTRAINT ck_rg_lista CHECK (estado <> 'lista' OR (ruta IS NOT NULL AND huella_archivo IS NOT NULL AND huella_datos IS NOT NULL)),
    CONSTRAINT ck_rg_fallida CHECK (estado <> 'fallida' OR (ruta IS NULL AND error IS NOT NULL)),
    CONSTRAINT ck_rg_vencida CHECK (estado <> 'vencida' OR huella_archivo IS NOT NULL),
    CONSTRAINT ck_rg_truncado CHECK (NOT truncado OR estado = 'vista'),
    CONSTRAINT ck_rg_ruta_formato CHECK (ruta IS NULL OR ruta LIKE '%.' || formato),
    CONSTRAINT ck_rg_borrado CHECK (archivo_borrado IS NULL OR estado IN ('vencida', 'fallida'))
);
-- Historial de cada usuario y, con formato, sus descargas del día
CREATE INDEX ix_rg_usuario ON reportes_generados (usuario_id, creado DESC);
CREATE INDEX ix_rg_descargas ON reportes_generados (usuario_id, creado DESC) WHERE formato IS NOT NULL;
-- Verificar un archivo subido por su huella
CREATE INDEX ix_rg_archivo ON reportes_generados (huella_archivo, id DESC) WHERE huella_archivo IS NOT NULL;
-- Cola del diferido, huérfanas, vencimiento y archivos por borrar
CREATE INDEX ix_rg_cola ON reportes_generados (id) WHERE estado = 'pendiente';
CREATE INDEX ix_rg_huerfanas ON reportes_generados (iniciado) WHERE estado = 'generando';
CREATE INDEX ix_rg_vencer ON reportes_generados (vence) WHERE estado = 'lista';
CREATE INDEX ix_rg_por_borrar ON reportes_generados (id) WHERE estado = 'vencida' AND ruta IS NOT NULL AND archivo_borrado IS NULL;

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    -- Sin DELETE y sin cambiar lo que identifica la generación (código, usuario, parámetros, columnas, creación): el registro es la
    -- evidencia para verificar después (decisión 7.5, sin purga)
    EXECUTE format('GRANT SELECT, INSERT ON reportes_generados TO %I', app);
    EXECUTE format('GRANT UPDATE (estado, ruta, tamano, filas, truncado, huella_datos, huella_archivo, ms_consulta, ms_archivo, ms_total, error, '
                   'intentos, iniciado, terminado, vence, archivo_borrado) ON reportes_generados TO %I', app);
END $$;
