-- Suite DIGSA · Fase AN: analítica farmacéutica, A1 · ingesta del insumo del OPL y hechos (docs/especificaciones/analitica-ingesta).
-- El insumo de dispensación llega por archivo (subida por partes) o por el servicio web simulado del OPL (lotes que se guardan en el
-- almacén con su SHA-256); se seudonimiza en Java antes de tocar la base, se copia (COPY) a un staging UNLOGGED que solo existe mientras
-- se procesa el cargue, se valida por conjuntos con causa y línea y se aplica todo o nada a los hechos particionados por mes. Las
-- consultas analíticas solo ven hechos de cargues «aplicado» hasta la fecha de corte (ana_cargues_visibles).
-- No inserta hechos, cargues ni plantillas: el insumo entra solo por la interfaz (A5 L982, condición 4).

-- Las particiones (hechos por mes y staging por cargue) viven en un esquema propio: el esquema public conserva solo las tablas de la
-- aplicación (la lista de la exportación completa se compara con él) y la aplicación las crea con funciones SECURITY DEFINER, sin ser
-- dueña de las tablas.
CREATE SCHEMA IF NOT EXISTS analitica_particiones;
COMMENT ON SCHEMA analitica_particiones IS 'Particiones de ana_dispensaciones (por mes) y de ana_staging_dispensacion (por cargue)';

-- ---------- Plantillas de mapeo ----------
CREATE TABLE ana_mapeo_plantillas (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre           text NOT NULL CHECK (char_length(nombre) BETWEEN 1 AND 120),
    firma_encabezado char(64) NOT NULL UNIQUE,
    encabezados      jsonb NOT NULL CHECK (jsonb_typeof(encabezados) = 'array'),
    mapeo            jsonb NOT NULL CHECK (jsonb_typeof(mapeo) = 'array'),
    creado_por       bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    creado           timestamptz NOT NULL DEFAULT now(),
    actualizado      timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE ana_mapeo_plantillas IS 'Mapeos confirmados de columnas del insumo a campos del modelo; se proponen solos ante el mismo encabezado';
COMMENT ON COLUMN ana_mapeo_plantillas.nombre IS 'Nombre que le dio el usuario';
COMMENT ON COLUMN ana_mapeo_plantillas.firma_encabezado IS 'SHA-256 de los encabezados normalizados, en orden';
COMMENT ON COLUMN ana_mapeo_plantillas.encabezados IS 'Encabezados del archivo (nombres de columna reconocibles o «Columna n»; nunca una fila de datos)';
COMMENT ON COLUMN ana_mapeo_plantillas.mapeo IS 'Campo del modelo por columna, en el orden del encabezado (clave de AnaliticaIngesta.Campo)';
COMMENT ON COLUMN ana_mapeo_plantillas.creado_por IS 'Usuario que guardó la plantilla';
COMMENT ON COLUMN ana_mapeo_plantillas.creado IS 'Fecha y hora de creación';
COMMENT ON COLUMN ana_mapeo_plantillas.actualizado IS 'Última vez que se guardó con este encabezado';
-- Llave foránea: la revisión RESTRICT al tocar un usuario no recorre la tabla
CREATE INDEX ix_ana_plantillas_creado_por ON ana_mapeo_plantillas (creado_por);

-- ---------- Cargues (bitácora de cargues, A5 4.7.1 punto 3) ----------
-- Es bitácora: la aplicación no borra cargues (no tiene DELETE). Un cargue que no termina queda con un estado explícito: duplicado
-- (archivo ya aplicado), vencido (abandonado), descartado (por su dueño), rechazado o fallido.
CREATE TABLE ana_cargues (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fuente                  text NOT NULL CHECK (fuente IN ('archivo', 'ws_opl')),
    fuente_texto            text NOT NULL CHECK (char_length(fuente_texto) <= 80),
    envio                   text CHECK (envio ~ '^[A-Za-z0-9_.-]{1,80}$'),
    nombre_archivo          text CHECK (char_length(nombre_archivo) <= 255),
    formato                 text NOT NULL CHECK (formato IN ('csv', 'txt', 'xlsx', 'gz', 'zip', 'json')),
    delimitador             text CHECK (char_length(delimitador) = 1),
    tamano_bytes            bigint CHECK (tamano_bytes >= 0),
    partes                  int CHECK (partes BETWEEN 1 AND 10000),
    sha256                  char(64),
    sha256_declarado        char(64),
    almacen_ruta            text CHECK (almacen_ruta ~ '^[A-Za-z0-9_/-]+\.[a-z0-9]+$'),
    conservar_hasta         timestamptz,
    original_borrado        timestamptz,
    encabezados             jsonb CHECK (encabezados IS NULL OR jsonb_typeof(encabezados) = 'array'),
    mapeo                   jsonb CHECK (mapeo IS NULL OR jsonb_typeof(mapeo) = 'array'),
    plantilla_id            bigint REFERENCES ana_mapeo_plantillas (id) ON DELETE SET NULL,
    modo                    text NOT NULL DEFAULT 'agregar' CHECK (modo IN ('agregar', 'reemplazar')),
    declarado_desde         date,
    declarado_hasta         date,
    estado                  text NOT NULL DEFAULT 'subiendo'
                            CHECK (estado IN ('subiendo', 'mapeo', 'pendiente', 'procesando', 'aplicado', 'rechazado', 'fallido',
                                              'duplicado', 'vencido', 'descartado')),
    etapa                   text CHECK (etapa IN ('subida', 'lectura', 'validacion', 'hechos', 'agregados')),
    porcentaje              smallint NOT NULL DEFAULT 0 CHECK (porcentaje BETWEEN 0 AND 100),
    filas_leidas            bigint NOT NULL DEFAULT 0,
    periodo_desde           date,
    periodo_hasta           date,
    recibidos               bigint CHECK (recibidos >= 0),
    procesados              bigint CHECK (procesados >= 0),
    rechazados              bigint CHECK (rechazados >= 0),
    esm_sin_correspondencia bigint CHECK (esm_sin_correspondencia >= 0),
    motivos                 jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(motivos) = 'object'),
    motivo_texto            text CHECK (char_length(motivo_texto) <= 2000),
    avisos                  jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(avisos) = 'array'),
    etapas_ms               jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(etapas_ms) = 'object'),
    duracion_ms             bigint CHECK (duracion_ms >= 0),
    usuario_id              bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    responsable_texto       text NOT NULL CHECK (char_length(responsable_texto) <= 120),
    creado                  timestamptz NOT NULL DEFAULT now(),
    actividad               timestamptz NOT NULL DEFAULT now(),
    recibido                timestamptz,
    iniciado                timestamptz,
    aplicado_en             timestamptz,
    terminado               timestamptz,
    reemplazado_por         bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    reemplazado_en          timestamptz,
    duplicado_de            bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    error                   text CHECK (char_length(error) <= 500),
    CHECK (estado NOT IN ('aplicado', 'rechazado') OR (recibidos IS NOT NULL AND recibidos = procesados + rechazados)),
    CHECK (estado <> 'aplicado' OR (aplicado_en IS NOT NULL AND sha256 IS NOT NULL)),
    CHECK (declarado_desde IS NULL OR declarado_hasta IS NULL OR declarado_desde <= declarado_hasta),
    -- «Reemplazar el periodo» solo con un periodo declarado explícito (reemplaza ese rango y nada más)
    CHECK (modo <> 'reemplazar' OR (declarado_desde IS NOT NULL AND declarado_hasta IS NOT NULL)),
    CHECK ((reemplazado_por IS NULL) = (reemplazado_en IS NULL)),
    CHECK ((estado = 'duplicado') = (duplicado_de IS NOT NULL)),
    CHECK ((fuente = 'ws_opl') = (envio IS NOT NULL))
);
COMMENT ON TABLE ana_cargues IS 'Cargues del insumo de dispensación del OPL con su bitácora (A5 4.7.1 punto 3: fecha y hora, fuente, periodo, recibidos, procesados, rechazados, motivo y responsable)';
COMMENT ON COLUMN ana_cargues.fuente IS 'archivo (subida por partes) o ws_opl (servicio web simulado del OPL)';
COMMENT ON COLUMN ana_cargues.fuente_texto IS 'Fuente legible: «Archivo CSV (;) UTF-8», «Archivo xlsx», «API OPL · dispensación»';
COMMENT ON COLUMN ana_cargues.envio IS 'Identificador del envío del servicio web (solo ws_opl)';
COMMENT ON COLUMN ana_cargues.nombre_archivo IS 'Nombre del archivo que subió el usuario';
COMMENT ON COLUMN ana_cargues.formato IS 'csv, txt, xlsx, gz, zip o json (servicio web)';
COMMENT ON COLUMN ana_cargues.delimitador IS 'Delimitador detectado en el encabezado (CSV y TXT)';
COMMENT ON COLUMN ana_cargues.tamano_bytes IS 'Tamaño del archivo ensamblado o del envío serializado';
COMMENT ON COLUMN ana_cargues.partes IS 'Partes declaradas por el navegador (ceil(tamaño / parte máxima)) o número del lote final del servicio web';
COMMENT ON COLUMN ana_cargues.sha256 IS 'SHA-256 calculado del archivo completo o del envío serializado';
COMMENT ON COLUMN ana_cargues.sha256_declarado IS 'SHA-256 que declaró el navegador al crear el cargue';
COMMENT ON COLUMN ana_cargues.almacen_ruta IS 'Archivo original en el almacén (tiene datos personales en claro: solo ADMINISTRADOR); nulo cuando se verificó su borrado';
COMMENT ON COLUMN ana_cargues.conservar_hasta IS 'Hasta cuándo se conserva el original (parámetro, 365 días desde la creación); luego una tarea lo borra';
COMMENT ON COLUMN ana_cargues.original_borrado IS 'Cuándo se verificó el borrado del original en el almacén';
COMMENT ON COLUMN ana_cargues.encabezados IS 'Encabezados del archivo (nombres de columna reconocibles; si la primera fila no se reconoce, «Columna n»)';
COMMENT ON COLUMN ana_cargues.mapeo IS 'Campo del modelo por columna confirmado por el usuario';
COMMENT ON COLUMN ana_cargues.plantilla_id IS 'Plantilla de mapeo usada o guardada';
COMMENT ON COLUMN ana_cargues.modo IS 'agregar (por defecto) o reemplazar el periodo declarado en cargues anteriores de la misma fuente';
COMMENT ON COLUMN ana_cargues.declarado_desde IS 'Inicio del periodo declarado al confirmar (obligatorio para reemplazar): fechas fuera se rechazan';
COMMENT ON COLUMN ana_cargues.declarado_hasta IS 'Fin del periodo declarado al confirmar (obligatorio para reemplazar)';
COMMENT ON COLUMN ana_cargues.estado IS 'subiendo → mapeo → pendiente → procesando → aplicado; o rechazado (estructura), fallido (error técnico), duplicado (archivo ya aplicado), vencido (abandonado) o descartado (por su dueño)';
COMMENT ON COLUMN ana_cargues.etapa IS 'Etapa en curso o en la que falló';
COMMENT ON COLUMN ana_cargues.porcentaje IS 'Avance del procesamiento (0 a 100)';
COMMENT ON COLUMN ana_cargues.filas_leidas IS 'Registros leídos hasta el momento';
COMMENT ON COLUMN ana_cargues.periodo_desde IS 'Fecha mínima de dispensación de los procesados';
COMMENT ON COLUMN ana_cargues.periodo_hasta IS 'Fecha máxima de dispensación de los procesados';
COMMENT ON COLUMN ana_cargues.recibidos IS 'Registros recibidos (líneas de datos leídas)';
COMMENT ON COLUMN ana_cargues.procesados IS 'Registros que quedaron como hechos';
COMMENT ON COLUMN ana_cargues.rechazados IS 'Registros rechazados (uno por registro aunque tenga varias causas)';
COMMENT ON COLUMN ana_cargues.esm_sin_correspondencia IS 'Procesados cuyo ESM no está en puntos (calidad del dato)';
COMMENT ON COLUMN ana_cargues.motivos IS 'Causa de rechazo → número de registros con esa causa';
COMMENT ON COLUMN ana_cargues.motivo_texto IS 'Motivo agrupado legible, motivo del rechazo estructural o del cierre (duplicado, vencido, descartado)';
COMMENT ON COLUMN ana_cargues.avisos IS 'Avisos sin datos para la bitácora del cargue (p. ej. claves desconocidas de los lotes del servicio web, solo sus nombres)';
COMMENT ON COLUMN ana_cargues.etapas_ms IS 'Duración por etapa en milisegundos (subida, lectura, validacion, hechos, agregados)';
COMMENT ON COLUMN ana_cargues.duracion_ms IS 'Duración total del procesamiento';
COMMENT ON COLUMN ana_cargues.usuario_id IS 'Usuario responsable (quien subió el archivo o la cuenta del OPL del servicio web)';
COMMENT ON COLUMN ana_cargues.responsable_texto IS 'Responsable legible: «jgarcia · analista» o «Proceso automático · API OPL (usuario)»';
COMMENT ON COLUMN ana_cargues.creado IS 'Creación del cargue (inicio de la subida o primer lote)';
COMMENT ON COLUMN ana_cargues.actividad IS 'Última actividad del usuario o del OPL (parte, lote, cierre, mapeo): un cargue abierto sin actividad vence';
COMMENT ON COLUMN ana_cargues.recibido IS 'Recepción completa (archivo ensamblado o lote final)';
COMMENT ON COLUMN ana_cargues.iniciado IS 'Inicio del procesamiento';
COMMENT ON COLUMN ana_cargues.aplicado_en IS 'Fecha y hora de aplicación: es la fecha de corte que publica el cargue';
COMMENT ON COLUMN ana_cargues.terminado IS 'Fin (aplicado, rechazado, fallido, duplicado, vencido o descartado)';
COMMENT ON COLUMN ana_cargues.reemplazado_por IS 'Cargue que reemplazó este periodo («Reemplazar el periodo»)';
COMMENT ON COLUMN ana_cargues.reemplazado_en IS 'Desde cuándo deja de verse (aplicación del cargue que lo reemplazó)';
COMMENT ON COLUMN ana_cargues.duplicado_de IS 'Cargue aplicado con el mismo SHA-256 (estado duplicado)';
COMMENT ON COLUMN ana_cargues.error IS 'Etapa y mensaje genérico de la falla, sin datos';
-- Un archivo aplicado y vigente no se repite (el reemplazado deja de contar) y un solo cargue en proceso en todo el sistema
CREATE UNIQUE INDEX uq_ana_cargues_sha_aplicado ON ana_cargues (sha256) WHERE estado = 'aplicado' AND reemplazado_por IS NULL;
CREATE UNIQUE INDEX uq_ana_cargues_procesando ON ana_cargues ((true)) WHERE estado = 'procesando';
CREATE UNIQUE INDEX uq_ana_cargues_envio ON ana_cargues (envio) WHERE envio IS NOT NULL;
-- Cola (estado, id), bitácora paginada (creado), cortes (aplicado_en), borrado de originales vencidos (conservar_hasta) y vencimiento
-- de los cargues abiertos sin actividad (actividad)
CREATE INDEX ix_ana_cargues_estado ON ana_cargues (estado, id);
CREATE INDEX ix_ana_cargues_creado ON ana_cargues (creado DESC, id DESC);
CREATE INDEX ix_ana_cargues_aplicado ON ana_cargues (aplicado_en) WHERE estado = 'aplicado';
CREATE INDEX ix_ana_cargues_conservar ON ana_cargues (conservar_hasta) WHERE almacen_ruta IS NOT NULL;
CREATE INDEX ix_ana_cargues_abiertos ON ana_cargues (actividad) WHERE estado IN ('subiendo', 'mapeo');
-- Llaves foráneas (y cargues abiertos por usuario)
CREATE INDEX ix_ana_cargues_usuario ON ana_cargues (usuario_id, estado);
CREATE INDEX ix_ana_cargues_plantilla ON ana_cargues (plantilla_id) WHERE plantilla_id IS NOT NULL;
CREATE INDEX ix_ana_cargues_reemplazado ON ana_cargues (reemplazado_por) WHERE reemplazado_por IS NOT NULL;
CREATE INDEX ix_ana_cargues_duplicado ON ana_cargues (duplicado_de) WHERE duplicado_de IS NOT NULL;

-- ---------- Partes de la subida y lotes del servicio web ----------
-- RESTRICT: son evidencia del cargue (la aplicación no borra cargues)
CREATE TABLE ana_cargue_partes (
    cargue_id       bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    numero          int NOT NULL CHECK (numero BETWEEN 1 AND 10000),
    tamano          bigint NOT NULL CHECK (tamano >= 0),
    sha256          char(64) NOT NULL,
    almacen_ruta    text NOT NULL CHECK (almacen_ruta ~ '^[A-Za-z0-9_/-]+\.[a-z0-9]+$'),
    conservar_hasta timestamptz NOT NULL,
    recibido        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (cargue_id, numero)
);
COMMENT ON TABLE ana_cargue_partes IS 'Partes de la subida de un archivo (≤ 50 MB), en el almacén hasta ensamblarse; si el cargue se abandona, vencen';
COMMENT ON COLUMN ana_cargue_partes.cargue_id IS 'Cargue al que pertenece';
COMMENT ON COLUMN ana_cargue_partes.numero IS 'Número de la parte, desde 1';
COMMENT ON COLUMN ana_cargue_partes.tamano IS 'Bytes recibidos (parte máxima, salvo la última)';
COMMENT ON COLUMN ana_cargue_partes.sha256 IS 'SHA-256 de la parte';
COMMENT ON COLUMN ana_cargue_partes.almacen_ruta IS 'Ruta temporal en el almacén (tiene datos personales en claro; se borra al ensamblar o al vencer el cargue)';
COMMENT ON COLUMN ana_cargue_partes.conservar_hasta IS 'Plazo máximo de la parte en el almacén (el del cargue)';
COMMENT ON COLUMN ana_cargue_partes.recibido IS 'Fecha y hora de recepción';

-- Los lotes del servicio web se guardan durables (almacén y esta tabla, con su SHA-256) y solo pasan a staging cuando el envío está
-- completo y se procesa: una caída de la base no los pierde (el staging es UNLOGGED).
CREATE TABLE ana_ws_lotes (
    cargue_id           bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    envio               text NOT NULL CHECK (envio ~ '^[A-Za-z0-9_.-]{1,80}$'),
    numero              int NOT NULL CHECK (numero BETWEEN 1 AND 10000),
    registros           int NOT NULL CHECK (registros >= 0),
    final               boolean NOT NULL DEFAULT false,
    sha256              char(64) NOT NULL,
    almacen_ruta        text CHECK (almacen_ruta ~ '^[A-Za-z0-9_/-]+\.[a-z0-9]+$'),
    conservar_hasta     timestamptz NOT NULL,
    claves_desconocidas jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(claves_desconocidas) = 'array'),
    recibido            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (envio, numero)
);
COMMENT ON TABLE ana_ws_lotes IS 'Lotes JSON recibidos por el servicio web del OPL; el mismo lote otra vez (mismo SHA-256) responde lo mismo, con otro contenido es 409';
COMMENT ON COLUMN ana_ws_lotes.cargue_id IS 'Cargue del envío';
COMMENT ON COLUMN ana_ws_lotes.envio IS 'Identificador del envío que da el OPL';
COMMENT ON COLUMN ana_ws_lotes.numero IS 'Número del lote dentro del envío (1 a N)';
COMMENT ON COLUMN ana_ws_lotes.registros IS 'Registros del lote';
COMMENT ON COLUMN ana_ws_lotes.final IS 'Último lote del envío (declara N)';
COMMENT ON COLUMN ana_ws_lotes.sha256 IS 'SHA-256 del cuerpo recibido (idempotencia del reenvío)';
COMMENT ON COLUMN ana_ws_lotes.almacen_ruta IS 'Lote en el almacén, solo con las claves del modelo; nulo cuando ya forma parte del envío serializado o venció';
COMMENT ON COLUMN ana_ws_lotes.conservar_hasta IS 'Plazo máximo del lote en el almacén';
COMMENT ON COLUMN ana_ws_lotes.claves_desconocidas IS 'Nombres de las claves del lote que no son del modelo (se ignoraron; sin valores)';
COMMENT ON COLUMN ana_ws_lotes.recibido IS 'Fecha y hora de recepción';
CREATE INDEX ix_ana_ws_lotes_cargue ON ana_ws_lotes (cargue_id, numero);

-- ---------- Rechazos (línea y causa, nunca el valor de la celda) ----------
CREATE TABLE ana_cargue_rechazos (
    cargue_id  bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    orden      bigint NOT NULL,
    linea      text NOT NULL CHECK (char_length(linea) <= 40),
    causa      text NOT NULL CHECK (causa IN ('obligatorio_vacio', 'fecha_invalida', 'fecha_fuera_periodo', 'numero_invalido',
                                              'cantidad_no_positiva', 'valor_negativo', 'cum_invalido', 'fuerza_no_reconocida',
                                              'modalidad_no_reconocida', 'duplicado_exacto', 'duplicado_llave', 'ya_cargado',
                                              'campos_distintos', 'archivo_vacio', 'codificacion', 'encabezados_repetidos',
                                              'hojas_distintas', 'formato')),
    columna    text CHECK (char_length(columna) <= 60),
    referencia text CHECK (char_length(referencia) <= 40)
);
COMMENT ON TABLE ana_cargue_rechazos IS 'Causas de rechazo por registro (un registro puede tener varias); sin el contenido de las celdas';
COMMENT ON COLUMN ana_cargue_rechazos.cargue_id IS 'Cargue';
COMMENT ON COLUMN ana_cargue_rechazos.orden IS 'Ordinal interno del registro (para ordenar y cruzar con staging)';
COMMENT ON COLUMN ana_cargue_rechazos.linea IS 'Línea de origen: número de línea del archivo, «hoja:fila» en xlsx o «lote N · registro M»';
COMMENT ON COLUMN ana_cargue_rechazos.causa IS 'Código del catálogo cerrado de causas (reglas/AnaliticaIngesta.Causa)';
COMMENT ON COLUMN ana_cargue_rechazos.columna IS 'Campo del modelo afectado, si aplica';
COMMENT ON COLUMN ana_cargue_rechazos.referencia IS 'Línea del registro original en los duplicados';
CREATE INDEX ix_ana_rechazos_cargue_causa ON ana_cargue_rechazos (cargue_id, causa);
CREATE INDEX ix_ana_rechazos_cargue_orden ON ana_cargue_rechazos (cargue_id, orden);

-- ---------- Staging (UNLOGGED por cargue; solo existe mientras se procesa) ----------
-- La tabla padre no guarda filas: cada cargue tiene su partición UNLOGGED en analitica_particiones, que se crea al empezar el
-- procesamiento (archivo o envío completo del servicio web) y se borra entera al terminar.
CREATE TABLE ana_staging_dispensacion (
    cargue_id         bigint NOT NULL,
    linea             bigint NOT NULL,
    linea_origen      text NOT NULL,
    campos_distintos  boolean NOT NULL DEFAULT false,
    paciente_seudo    text,
    anio_nacimiento   text,
    grupo_edad        text,
    fuerza            text,
    esm               text,
    modalidad         text,
    fecha             text,
    medicamento       text,
    cum               text,
    cum_original      text,
    principio_activo  text,
    atc               text,
    cie10             text,
    cantidad          text,
    valor             text,
    valor_unitario    text,
    codigo_ssfm       text,
    profesional_seudo text,
    especialidad      text,
    tipo_formula      text,
    numero_formula    text,
    id_dispensacion   text,
    item              text,
    regional          text,
    huella            uuid NOT NULL,
    llave             uuid NOT NULL
) PARTITION BY LIST (cargue_id);
COMMENT ON TABLE ana_staging_dispensacion IS 'Staging técnico de la ingesta: texto ya normalizado y seudonimizado (nunca el documento en claro; una fila con otro número de campos solo guarda su línea); una partición UNLOGGED por cargue';
COMMENT ON COLUMN ana_staging_dispensacion.cargue_id IS 'Cargue (llave de la partición)';
COMMENT ON COLUMN ana_staging_dispensacion.linea IS 'Ordinal del registro (línea física en CSV)';
COMMENT ON COLUMN ana_staging_dispensacion.linea_origen IS 'Línea de origen legible';
COMMENT ON COLUMN ana_staging_dispensacion.campos_distintos IS 'La fila tenía otro número de campos que el encabezado (sus columnas van vacías)';
COMMENT ON COLUMN ana_staging_dispensacion.paciente_seudo IS 'HMAC-SHA256 del documento normalizado';
COMMENT ON COLUMN ana_staging_dispensacion.anio_nacimiento IS 'Año de nacimiento (la fecha completa se descarta)';
COMMENT ON COLUMN ana_staging_dispensacion.grupo_edad IS 'Grupo de edad tal como viene';
COMMENT ON COLUMN ana_staging_dispensacion.fuerza IS 'Fuerza normalizada por sinónimos';
COMMENT ON COLUMN ana_staging_dispensacion.esm IS 'Código del ESM normalizado';
COMMENT ON COLUMN ana_staging_dispensacion.modalidad IS 'Modalidad normalizada por sinónimos';
COMMENT ON COLUMN ana_staging_dispensacion.fecha IS 'Fecha canónica aaaa-mm-dd o el texto original si no es fecha';
COMMENT ON COLUMN ana_staging_dispensacion.medicamento IS 'Descripción del medicamento tal como viene';
COMMENT ON COLUMN ana_staging_dispensacion.cum IS 'CUM normalizado';
COMMENT ON COLUMN ana_staging_dispensacion.cum_original IS 'CUM tal como viene';
COMMENT ON COLUMN ana_staging_dispensacion.principio_activo IS 'Principio activo tal como viene';
COMMENT ON COLUMN ana_staging_dispensacion.atc IS 'ATC normalizado';
COMMENT ON COLUMN ana_staging_dispensacion.cie10 IS 'CIE-10 normalizado';
COMMENT ON COLUMN ana_staging_dispensacion.cantidad IS 'Cantidad canónica o el texto original si no es número';
COMMENT ON COLUMN ana_staging_dispensacion.valor IS 'Valor total canónico o el texto original si no es número';
COMMENT ON COLUMN ana_staging_dispensacion.valor_unitario IS 'Valor unitario canónico o el texto original';
COMMENT ON COLUMN ana_staging_dispensacion.codigo_ssfm IS 'Código SSFM';
COMMENT ON COLUMN ana_staging_dispensacion.profesional_seudo IS 'HMAC-SHA256 del documento del profesional';
COMMENT ON COLUMN ana_staging_dispensacion.especialidad IS 'Especialidad';
COMMENT ON COLUMN ana_staging_dispensacion.tipo_formula IS 'Tipo de fórmula';
COMMENT ON COLUMN ana_staging_dispensacion.numero_formula IS 'Número de fórmula';
COMMENT ON COLUMN ana_staging_dispensacion.id_dispensacion IS 'Id de dispensación del OPL';
COMMENT ON COLUMN ana_staging_dispensacion.item IS 'Ítem de la dispensación';
COMMENT ON COLUMN ana_staging_dispensacion.regional IS 'Regional tal como viene';
COMMENT ON COLUMN ana_staging_dispensacion.huella IS 'Huella de todas las columnas mapeadas (duplicado exacto)';
COMMENT ON COLUMN ana_staging_dispensacion.llave IS 'Llave natural: (fuente, id, ítem) o la fila completa';

-- ---------- Hechos de dispensación, particionados por mes ----------
CREATE SEQUENCE ana_dispensaciones_id_seq AS bigint CACHE 1000;
CREATE TABLE ana_dispensaciones (
    id                        bigint NOT NULL DEFAULT nextval('ana_dispensaciones_id_seq'),
    cargue_id                 bigint NOT NULL,   -- sin llave foránea: su revisión fila a fila pesa en la carga de 2 M (la escribe solo AplicadorHechos)
    linea_origen              text NOT NULL,
    paciente_seudo            char(64) NOT NULL,
    anio_nacimiento           smallint CHECK (anio_nacimiento BETWEEN 1900 AND 2100),
    grupo_edad                text,
    fuerza                    text NOT NULL,
    disan                     text,
    regional_id               bigint,
    punto_id                  bigint,
    esm_codigo                text NOT NULL,
    modalidad                 text NOT NULL,
    fecha                     date NOT NULL,
    cum                       text,
    cum_original              text,
    medicamento_original      text,
    principio_activo_original text,
    atc_original              text,
    cie10                     text,
    cantidad                  numeric(14, 3) NOT NULL CHECK (cantidad > 0),
    valor_total               numeric(16, 2) NOT NULL CHECK (valor_total >= 0),
    valor_unitario            numeric(16, 2),
    codigo_ssfm               text,
    profesional_seudo         char(64),
    especialidad              text,
    tipo_formula              text,
    numero_formula            text,
    homil                     boolean,
    id_dispensacion           text,
    item                      text,
    llave                     uuid NOT NULL,
    PRIMARY KEY (id, fecha)
) PARTITION BY RANGE (fecha);
ALTER SEQUENCE ana_dispensaciones_id_seq OWNED BY ana_dispensaciones.id;
COMMENT ON TABLE ana_dispensaciones IS 'Hechos de dispensación (un registro del insumo por fila); solo cuentan los de cargues aplicados al corte (ana_cargues_visibles). Fuera de la exportación completa por volumen (duda AN.14)';
COMMENT ON COLUMN ana_dispensaciones.id IS 'Identificador del hecho';
COMMENT ON COLUMN ana_dispensaciones.cargue_id IS 'Cargue que lo trajo (trazabilidad 14.9)';
COMMENT ON COLUMN ana_dispensaciones.linea_origen IS 'Línea del archivo («hoja:fila» en xlsx; «lote N · registro M» en el servicio web)';
COMMENT ON COLUMN ana_dispensaciones.paciente_seudo IS 'Seudónimo HMAC-SHA256 del paciente (nunca el documento en claro)';
COMMENT ON COLUMN ana_dispensaciones.anio_nacimiento IS 'Año de nacimiento';
COMMENT ON COLUMN ana_dispensaciones.grupo_edad IS 'Grupo de edad tal como viene';
COMMENT ON COLUMN ana_dispensaciones.fuerza IS 'Código de la Fuerza (par_fuerzas)';
COMMENT ON COLUMN ana_dispensaciones.disan IS 'DISAN o JEFSA de la Fuerza al aplicar (par_fuerzas.sanidad)';
COMMENT ON COLUMN ana_dispensaciones.regional_id IS 'Regional del ESM (par_regionales), si se conoce';
COMMENT ON COLUMN ana_dispensaciones.punto_id IS 'ESM en puntos; nulo = ESM sin correspondencia';
COMMENT ON COLUMN ana_dispensaciones.esm_codigo IS 'Código del ESM tal como viene en el insumo (normalizado)';
COMMENT ON COLUMN ana_dispensaciones.modalidad IS 'Modalidad (par_modalidades)';
COMMENT ON COLUMN ana_dispensaciones.fecha IS 'Fecha de dispensación (llave de la partición mensual)';
COMMENT ON COLUMN ana_dispensaciones.cum IS 'CUM normalizado (lo homologa analitica-homologacion, V61)';
COMMENT ON COLUMN ana_dispensaciones.cum_original IS 'CUM tal como viene';
COMMENT ON COLUMN ana_dispensaciones.medicamento_original IS 'Descripción del medicamento tal como viene';
COMMENT ON COLUMN ana_dispensaciones.principio_activo_original IS 'Principio activo tal como viene';
COMMENT ON COLUMN ana_dispensaciones.atc_original IS 'ATC del insumo normalizado';
COMMENT ON COLUMN ana_dispensaciones.cie10 IS 'CIE-10 normalizado (sin punto)';
COMMENT ON COLUMN ana_dispensaciones.cantidad IS 'Unidades dispensadas (hasta tres decimales; más se rechaza como número inválido)';
COMMENT ON COLUMN ana_dispensaciones.valor_total IS 'Valor total dispensado (pesos, dos decimales)';
COMMENT ON COLUMN ana_dispensaciones.valor_unitario IS 'Valor unitario, si viene';
COMMENT ON COLUMN ana_dispensaciones.codigo_ssfm IS 'Código SSFM, si viene';
COMMENT ON COLUMN ana_dispensaciones.profesional_seudo IS 'Seudónimo HMAC del profesional, si viene';
COMMENT ON COLUMN ana_dispensaciones.especialidad IS 'Especialidad del profesional, si viene';
COMMENT ON COLUMN ana_dispensaciones.tipo_formula IS 'Tipo de fórmula, si viene';
COMMENT ON COLUMN ana_dispensaciones.numero_formula IS 'Número de fórmula, si viene';
COMMENT ON COLUMN ana_dispensaciones.homil IS 'Dispensación del Hospital Militar (reservado; el insumo aún no lo trae)';
COMMENT ON COLUMN ana_dispensaciones.id_dispensacion IS 'Id de dispensación del OPL, si viene';
COMMENT ON COLUMN ana_dispensaciones.item IS 'Ítem de la dispensación, si viene';
COMMENT ON COLUMN ana_dispensaciones.llave IS 'Llave natural (idempotencia: «Ya cargado», que la busca en ana_llaves)';
-- Índices en la tabla padre (cada partición mensual los recibe al crearse). Solo los que una consulta de A1 usa: cada índice se
-- mantiene fila a fila en la carga de 2 M dentro de una transacción (medición de A1: la etapa de hechos era el cuello con 7 índices).
--   · PK (id, fecha): identidad del hecho (exigida por la partición).
--   · BRIN (fecha): poda dentro del mes para rangos de días de los tableros (A3); su mantenimiento es casi nulo.
--   · BRIN (cargue_id): trazabilidad 14.9 (hechos de un cargue) y limpieza de un cargue fallido; cargue_id crece con cada cargue y
--     los hechos de un cargue quedan contiguos en cada partición, así que el BRIN es diminuto (antes era un B-tree).
-- Los B-tree por ESM, CUM, medicamento, CIE-10 y paciente que pide la spec (requisito «Carga en hechos») se crean en la fase que los
-- consulta (A2 homologación, A3 tableros y agregados, A4 cohortes), compuestos según la consulta real y en cada partición tras la carga
-- (CREATE INDEX en la partición y ALTER INDEX … ATTACH PARTITION). La llave natural no se indexa aquí: va en ana_llaves.
CREATE INDEX ix_ana_disp_fecha_brin ON ana_dispensaciones USING brin (fecha);
CREATE INDEX ix_ana_disp_cargue_brin ON ana_dispensaciones USING brin (cargue_id);

-- ---------- Llaves naturales de los hechos («Ya cargado») ----------
-- Tabla no particionada: «Ya cargado» hace una sola búsqueda por fila sin importar cuántos meses de historia haya (con el índice de la
-- llave en cada partición mensual, la búsqueda recorría todas las particiones). Se llena en la misma transacción que los hechos.
CREATE TABLE ana_llaves (
    llave     uuid NOT NULL,
    cargue_id bigint NOT NULL,   -- sin llave foránea por la misma razón que ana_dispensaciones
    PRIMARY KEY (llave, cargue_id)
);
COMMENT ON TABLE ana_llaves IS 'Llave natural de cada hecho y su cargue (índice de «Ya cargado»); técnica, se reconstruye de ana_dispensaciones';
COMMENT ON COLUMN ana_llaves.llave IS 'Llave natural del hecho';
COMMENT ON COLUMN ana_llaves.cargue_id IS 'Cargue que la trajo (un reemplazo puede repetir la llave en otro cargue)';
-- Limpieza de un cargue fallido (casi nunca: la carga es una transacción); BRIN porque cargue_id crece
CREATE INDEX ix_ana_llaves_cargue_brin ON ana_llaves USING brin (cargue_id);

-- ---------- Dimensión de tiempo ----------
CREATE TABLE ana_dim_tiempo (
    fecha     date PRIMARY KEY,
    semana    char(8) NOT NULL,
    mes       char(7) NOT NULL,
    bimestre  char(7) NOT NULL,
    trimestre char(7) NOT NULL,
    semestre  char(7) NOT NULL,
    anio      smallint NOT NULL
);
COMMENT ON TABLE ana_dim_tiempo IS 'Calendario para las granularidades de los tableros; se llena con el rango de cada cargue';
COMMENT ON COLUMN ana_dim_tiempo.fecha IS 'Día';
COMMENT ON COLUMN ana_dim_tiempo.semana IS 'Semana ISO (aaaa-Wss)';
COMMENT ON COLUMN ana_dim_tiempo.mes IS 'Mes (aaaa-mm)';
COMMENT ON COLUMN ana_dim_tiempo.bimestre IS 'Bimestre (aaaa-Bn)';
COMMENT ON COLUMN ana_dim_tiempo.trimestre IS 'Trimestre (aaaa-Tn)';
COMMENT ON COLUMN ana_dim_tiempo.semestre IS 'Semestre (aaaa-Sn)';
COMMENT ON COLUMN ana_dim_tiempo.anio IS 'Año';

-- ---------- Funciones ----------
-- Cargues visibles a una fecha de corte: aplicados hasta ese momento y no reemplazados antes de él (A5 L723–731, 14.8). Toda consulta
-- analítica (tableros, cohortes, alertas, asistente) filtra los hechos con ella.
CREATE FUNCTION ana_cargues_visibles(corte timestamptz) RETURNS TABLE (cargue_id bigint)
    LANGUAGE sql STABLE AS $$
    SELECT c.id FROM ana_cargues c
    WHERE c.estado = 'aplicado' AND c.aplicado_en <= corte AND (c.reemplazado_en IS NULL OR c.reemplazado_en > corte)
$$;
COMMENT ON FUNCTION ana_cargues_visibles(timestamptz) IS 'Cargues cuyos hechos se ven con la fecha de corte dada';

-- Crea las particiones mensuales que falten entre dos fechas (inclusive). SECURITY DEFINER: la aplicación no es dueña de la tabla.
-- La aplicación la llama mes a mes solo con los meses que tienen filas (una fecha suelta antigua no crea años de particiones vacías).
CREATE FUNCTION ana_asegurar_particiones(desde date, hasta date) RETURNS int
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    mes    date := date_trunc('month', desde)::date;
    nombre text;
    nuevas int := 0;
BEGIN
    IF desde IS NULL OR hasta IS NULL OR hasta < desde OR desde < DATE '1990-01-01' OR hasta > DATE '2100-12-31' THEN
        RAISE EXCEPTION 'Rango de particiones no válido: % – %', desde, hasta;
    END IF;
    WHILE mes <= hasta LOOP
        nombre := 'ana_dispensaciones_' || to_char(mes, 'YYYY_MM');
        IF to_regclass('analitica_particiones.' || nombre) IS NULL THEN
            EXECUTE format('CREATE TABLE analitica_particiones.%I PARTITION OF public.ana_dispensaciones FOR VALUES FROM (%L) TO (%L)',
                           nombre, mes, (mes + interval '1 month')::date);
            nuevas := nuevas + 1;
        END IF;
        mes := (mes + interval '1 month')::date;
    END LOOP;
    RETURN nuevas;
END $$;
COMMENT ON FUNCTION ana_asegurar_particiones(date, date) IS 'Crea las particiones mensuales de ana_dispensaciones que falten en el rango';

-- Estadísticas de las particiones mensuales del rango tras aplicar un cargue (la aplicación no es dueña: no puede hacer ANALYZE).
CREATE FUNCTION ana_analizar_particiones(desde date, hasta date) RETURNS int
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    mes       date := date_trunc('month', desde)::date;
    nombre    text;
    analizadas int := 0;
BEGIN
    IF desde IS NULL OR hasta IS NULL OR hasta < desde THEN
        RETURN 0;
    END IF;
    WHILE mes <= hasta LOOP
        nombre := 'ana_dispensaciones_' || to_char(mes, 'YYYY_MM');
        IF to_regclass('analitica_particiones.' || nombre) IS NOT NULL THEN
            EXECUTE format('ANALYZE analitica_particiones.%I', nombre);
            analizadas := analizadas + 1;
        END IF;
        mes := (mes + interval '1 month')::date;
    END LOOP;
    RETURN analizadas;
END $$;
COMMENT ON FUNCTION ana_analizar_particiones(date, date) IS 'ANALYZE de las particiones mensuales de ana_dispensaciones del rango';

-- Partición UNLOGGED del staging para un cargue, sus estadísticas tras el COPY y su borrado al terminar.
CREATE FUNCTION ana_staging_crear(cargue bigint) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF to_regclass('analitica_particiones.ana_staging_' || cargue) IS NULL THEN
        EXECUTE format('CREATE UNLOGGED TABLE analitica_particiones.%I PARTITION OF public.ana_staging_dispensacion FOR VALUES IN (%s)',
                       'ana_staging_' || cargue, cargue);
    END IF;
END $$;
COMMENT ON FUNCTION ana_staging_crear(bigint) IS 'Crea la partición UNLOGGED del staging de un cargue';

CREATE FUNCTION ana_staging_analizar(cargue bigint) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF to_regclass('analitica_particiones.ana_staging_' || cargue) IS NOT NULL THEN
        EXECUTE format('ANALYZE analitica_particiones.%I', 'ana_staging_' || cargue);
    END IF;
END $$;
COMMENT ON FUNCTION ana_staging_analizar(bigint) IS 'ANALYZE de la partición del staging tras el COPY (la validación se planifica con datos reales)';

CREATE FUNCTION ana_staging_borrar(cargue bigint) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    EXECUTE format('DROP TABLE IF EXISTS analitica_particiones.%I', 'ana_staging_' || cargue);
END $$;
COMMENT ON FUNCTION ana_staging_borrar(bigint) IS 'Borra la partición del staging de un cargue (al terminar, fallar o vencer)';

-- Privilegios mínimos del rol de la aplicación, sentencia por sentencia del código (IngestaPrivilegiosTest los verifica):
--   ana_cargues            SELECT, INSERT, UPDATE (sin DELETE: es la bitácora de cargues)
--   ana_mapeo_plantillas   SELECT, INSERT, UPDATE (INSERT … ON CONFLICT DO UPDATE)
--   ana_cargue_partes      SELECT, INSERT, UPDATE, DELETE (ON CONFLICT DO UPDATE al repetir una parte; se borran al ensamblar o vencer)
--   ana_ws_lotes           SELECT, INSERT, UPDATE (la ruta queda nula al serializar el envío o vencer)
--   ana_cargue_rechazos    SELECT, INSERT, DELETE (la validación borra los de un intento anterior)
--   ana_dispensaciones     SELECT, INSERT, DELETE (limpieza de un cargue fallido)
--   ana_llaves             SELECT, INSERT, DELETE (igual)
--   ana_staging_dispensacion SELECT, INSERT (COPY; la partición se borra con ana_staging_borrar)
--   ana_dim_tiempo         SELECT, INSERT (ON CONFLICT DO NOTHING)
--   funciones              EXECUTE; secuencias USAGE. Las particiones se tocan siempre a través de la tabla padre: no hace falta USAGE
--                          sobre el esquema analitica_particiones.
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON ana_cargues, ana_mapeo_plantillas, ana_ws_lotes TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ana_cargue_partes TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT ON ana_dim_tiempo, ana_staging_dispensacion TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON ana_cargue_rechazos, ana_dispensaciones, ana_llaves TO %I', app);
    EXECUTE format('GRANT EXECUTE ON FUNCTION ana_cargues_visibles(timestamptz), ana_asegurar_particiones(date, date), '
                   'ana_analizar_particiones(date, date), ana_staging_crear(bigint), ana_staging_analizar(bigint), '
                   'ana_staging_borrar(bigint) TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
-- Las funciones SECURITY DEFINER no quedan abiertas a cualquiera
REVOKE EXECUTE ON FUNCTION ana_asegurar_particiones(date, date), ana_analizar_particiones(date, date), ana_staging_crear(bigint),
    ana_staging_analizar(bigint), ana_staging_borrar(bigint) FROM PUBLIC;
