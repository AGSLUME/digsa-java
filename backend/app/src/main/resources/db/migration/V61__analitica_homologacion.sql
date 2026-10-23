-- Suite DIGSA · Fase AN: analítica farmacéutica, A2 · homologación, referencias oficiales y tabla maestra
-- (docs/especificaciones/analitica-homologacion).
-- Las referencias oficiales (CUM del INVIMA en sus cuatro conjuntos de datos.gov.co, CIE-10 de SISPRO, precio máximo regulado, vitales no
-- disponibles, listas de estrecho margen y de control especial y códigos SSFM de la DIGSA) se cargan por archivo desde la pantalla como
-- versiones con fecha de corte de la fuente, SHA-256 y filas, y se publican de forma atómica (una publicada por fuente). La homologación
-- es una función pura (reglas/AnaliticaHomologacion) con reglas R-0xx versionadas y parametrizables; cada ejecución crea una versión de
-- la tabla maestra (dato original → fuente → regla → dato final) que no se modifica después. Los hechos de dispensación guardan el id
-- estable del dato original (medicamento_id) y la versión vigente al aplicar el cargue; una re-ejecución nunca los reescribe.
-- Solo siembra las fuentes, las reglas, los catálogos genéricos y los nombres del nivel 1 del ATC: la tabla maestra empieza vacía
-- (A5 L982, condición 4) y ninguna referencia oficial viene cargada.

-- ---------- Fuentes oficiales y versiones ----------
CREATE TABLE ref_fuente (
    id          smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo      text NOT NULL UNIQUE CHECK (codigo ~ '^[A-Z][A-Z0-9_]{2,39}$'),
    nombre      text NOT NULL CHECK (char_length(nombre) BETWEEN 1 AND 160),
    tipo        text NOT NULL CHECK (tipo IN ('cum', 'cie10', 'precio', 'vitales', 'lista', 'ssfm')),
    conjunto    text CHECK (conjunto IN ('vigentes', 'renovacion', 'vencidos', 'otros')),
    lista       text CHECK (lista IN ('estrecho_margen', 'control_especial')),
    dataset     text CHECK (char_length(dataset) <= 40),
    url         text CHECK (char_length(url) <= 500),
    licencia    text CHECK (char_length(licencia) <= 120),
    atribucion  text CHECK (char_length(atribucion) <= 200),
    frecuencia  text CHECK (char_length(frecuencia) <= 60),
    orden       smallint NOT NULL,
    CHECK ((tipo = 'cum') = (conjunto IS NOT NULL)),
    CHECK ((tipo = 'lista') = (lista IS NOT NULL))
);
COMMENT ON TABLE ref_fuente IS 'Fuentes oficiales de la homologación (RESP L4535–4545): las sembradas por V61; cada carga es una ref_version';
COMMENT ON COLUMN ref_fuente.codigo IS 'Código estable de la fuente (INVIMA_CUM_VIGENTES, SISPRO_CIE10…)';
COMMENT ON COLUMN ref_fuente.nombre IS 'Nombre legible';
COMMENT ON COLUMN ref_fuente.tipo IS 'Formato del archivo y tablas de destino: cum, cie10, precio, vitales, lista o ssfm';
COMMENT ON COLUMN ref_fuente.conjunto IS 'Conjunto del CUM del INVIMA (vigentes, renovacion, vencidos, otros); precedencia en ese orden';
COMMENT ON COLUMN ref_fuente.lista IS 'Lista normativa (estrecho_margen, control_especial) de las fuentes de tipo lista';
COMMENT ON COLUMN ref_fuente.dataset IS 'Identificador del conjunto de datos (Socrata en datos.gov.co o tabla de SISPRO)';
COMMENT ON COLUMN ref_fuente.url IS 'Dónde se descarga o se consulta';
COMMENT ON COLUMN ref_fuente.licencia IS 'Licencia verificada (CC BY-SA 4.0 en datos.gov.co); nula si no se verificó (fuentes_oficiales.md §7)';
COMMENT ON COLUMN ref_fuente.atribucion IS 'Atribución exigida por la licencia';
COMMENT ON COLUMN ref_fuente.frecuencia IS 'Frecuencia de actualización declarada por la fuente';
COMMENT ON COLUMN ref_fuente.orden IS 'Orden en la pantalla';

INSERT INTO ref_fuente (codigo, nombre, tipo, conjunto, lista, dataset, url, licencia, atribucion, frecuencia, orden) VALUES
    ('INVIMA_CUM_VIGENTES', 'CUM del INVIMA · vigentes', 'cum', 'vigentes', NULL, 'i7cb-raxc', 'https://www.datos.gov.co/resource/i7cb-raxc.csv',
     'CC BY-SA 4.0', 'INVIMA', 'Mensual', 1),
    ('INVIMA_CUM_RENOVACION', 'CUM del INVIMA · en trámite de renovación', 'cum', 'renovacion', NULL, 'vgr4-gemg',
     'https://www.datos.gov.co/resource/vgr4-gemg.csv', 'CC BY-SA 4.0', 'INVIMA', 'Mensual', 2),
    ('INVIMA_CUM_VENCIDOS', 'CUM del INVIMA · vencidos', 'cum', 'vencidos', NULL, 'vwwf-4ftk', 'https://www.datos.gov.co/resource/vwwf-4ftk.csv',
     'CC BY-SA 4.0', 'INVIMA', 'Mensual', 3),
    ('INVIMA_CUM_OTROS', 'CUM del INVIMA · otros estados', 'cum', 'otros', NULL, 'spzp-dfuc', 'https://www.datos.gov.co/resource/spzp-dfuc.csv',
     'CC BY-SA 4.0', 'INVIMA', 'Mensual', 4),
    ('SISPRO_CIE10', 'CIE-10 (SISPRO, tabla CIE10)', 'cie10', NULL, NULL, 'CIE10',
     'https://web.sispro.gov.co/WebPublico/Consultas/ConsultarDetalleReferenciaBasica.aspx?Code=CIE10', NULL,
     'Ministerio de Salud y Protección Social (SISPRO)', 'Por acto administrativo', 5),
    ('MINSALUD_PRECIO_REGULADO', 'Precio máximo de venta regulado (CNPMDM)', 'precio', NULL, NULL, 'nauz-qkjw',
     'https://www.datos.gov.co/resource/nauz-qkjw.csv', 'CC BY-SA 4.0', 'Ministerio de Salud y Protección Social', 'Mensual', 6),
    ('INVIMA_VITALES', 'Medicamentos vitales no disponibles (INVIMA)', 'vitales', NULL, NULL, 'sdmr-tfmf',
     'https://www.datos.gov.co/resource/sdmr-tfmf.csv', NULL, 'INVIMA', 'Mensual', 7),
    ('INVIMA_ESTRECHO_MARGEN', 'Estrecho margen terapéutico (INVIMA, PDF)', 'lista', NULL, 'estrecho_margen', NULL,
     'https://www.invima.gov.co/productos-vigilados/medicamentos-y-productos-biologicos/bioequivalencia', NULL, 'INVIMA', 'Sin periodicidad', 8),
    ('MSPS_CONTROL_ESPECIAL', 'Medicamentos de control especial (Res. 116 de 2026)', 'lista', NULL, 'control_especial', NULL, NULL, NULL,
     'Ministerio de Salud y Protección Social · Fondo Nacional de Estupefacientes', 'Por resolución', 9),
    ('DIGSA_CODIGO_SSFM', 'Código interno SSFM (DIGSA)', 'ssfm', NULL, NULL, NULL, NULL, NULL, 'DIGSA', 'Cuando la DIGSA lo entregue', 10);

CREATE TABLE ref_version (
    id                 integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fuente_id          smallint NOT NULL REFERENCES ref_fuente (id) ON DELETE RESTRICT,
    numero             integer NOT NULL CHECK (numero >= 1),
    fecha_corte        date NOT NULL,
    cargado            timestamptz NOT NULL DEFAULT now(),
    usuario_id         bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    responsable_texto  text NOT NULL CHECK (char_length(responsable_texto) <= 120),
    origen             text NOT NULL CHECK (origen IN ('archivo', 'edicion')),
    nombre_archivo     text CHECK (char_length(nombre_archivo) <= 255),
    almacen_ruta       text CHECK (almacen_ruta ~ '^[A-Za-z0-9_/-]+\.[a-z0-9]+$'),
    sha256             char(64) NOT NULL,
    filas_leidas       integer NOT NULL DEFAULT 0 CHECK (filas_leidas >= 0),
    filas_validas      integer NOT NULL DEFAULT 0 CHECK (filas_validas >= 0),
    estado             text NOT NULL DEFAULT 'cargando' CHECK (estado IN ('cargando', 'validada', 'publicada', 'reemplazada', 'rechazada', 'fallida')),
    ejemplo            boolean NOT NULL DEFAULT false,
    informe            jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(informe) = 'object'),
    motivo             text CHECK (char_length(motivo) <= 2000),
    publicada_en       timestamptz,
    publicada_por      bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    reemplazada_en     timestamptz,
    UNIQUE (fuente_id, numero),
    CHECK (estado NOT IN ('publicada', 'reemplazada') OR publicada_en IS NOT NULL),
    CHECK ((estado = 'reemplazada') = (reemplazada_en IS NOT NULL))
);
COMMENT ON TABLE ref_version IS 'Versiones de cada fuente oficial: fecha de corte de la fuente, carga, SHA-256, filas y estado; una sola publicada por fuente y las publicadas no se modifican';
COMMENT ON COLUMN ref_version.fuente_id IS 'Fuente oficial';
COMMENT ON COLUMN ref_version.numero IS 'Número de la versión dentro de la fuente (1, 2…)';
COMMENT ON COLUMN ref_version.fecha_corte IS 'Fecha de actualización de la fuente declarada al cargar (rowsUpdatedAt de datos.gov.co o max(Fecha_Actualizacion) de SISPRO)';
COMMENT ON COLUMN ref_version.cargado IS 'Fecha y hora de la carga';
COMMENT ON COLUMN ref_version.usuario_id IS 'Quien cargó el archivo o editó la lista';
COMMENT ON COLUMN ref_version.responsable_texto IS 'Responsable legible («jgarcia · analista»)';
COMMENT ON COLUMN ref_version.origen IS 'archivo (subido desde la pantalla) o edicion (lista normativa editada en la pantalla)';
COMMENT ON COLUMN ref_version.nombre_archivo IS 'Nombre del archivo subido';
COMMENT ON COLUMN ref_version.almacen_ruta IS 'Archivo conservado en el almacén (son públicos, CC BY-SA; duda 10); nulo si la versión se rechazó o falló (el archivo se borra) y en vitales no disponibles tras validar (traen el solicitante)';
COMMENT ON COLUMN ref_version.sha256 IS 'SHA-256 del archivo (o del contenido de la lista editada); el mismo archivo no se carga dos veces';
COMMENT ON COLUMN ref_version.filas_leidas IS 'Filas de datos leídas del archivo';
COMMENT ON COLUMN ref_version.filas_validas IS 'Registros que quedaron en la versión (presentaciones del CUM, códigos, precios…)';
COMMENT ON COLUMN ref_version.estado IS 'cargando → validada → publicada → reemplazada; o rechazada (encabezados, llave o archivo ilegible) o fallida (error técnico o carga interrumpida por un reinicio)';
COMMENT ON COLUMN ref_version.ejemplo IS 'Datos de ejemplo ficticios (pruebas): la pantalla lo muestra y no se publican en producción';
COMMENT ON COLUMN ref_version.informe IS 'Informe de la validación: conteos, fechas no convertibles, ATC no válidos, CUM en otros conjuntos y filas rechazadas con su línea';
COMMENT ON COLUMN ref_version.motivo IS 'Motivo del rechazo («Faltan las columnas: …»)';
COMMENT ON COLUMN ref_version.publicada_en IS 'Cuándo se publicó';
COMMENT ON COLUMN ref_version.publicada_por IS 'Quién la publicó';
COMMENT ON COLUMN ref_version.reemplazada_en IS 'Cuándo la reemplazó la siguiente versión publicada';
-- Una sola versión publicada por fuente (la publicación marca la anterior reemplazada en la misma transacción)
CREATE UNIQUE INDEX uq_ref_version_publicada ON ref_version (fuente_id) WHERE estado = 'publicada';
CREATE INDEX ix_ref_version_sha ON ref_version (fuente_id, sha256);
CREATE INDEX ix_ref_version_usuario ON ref_version (usuario_id) WHERE usuario_id IS NOT NULL;
CREATE INDEX ix_ref_version_publicada_por ON ref_version (publicada_por) WHERE publicada_por IS NOT NULL;

-- Una versión publicada o reemplazada no se modifica ni se borra: solo puede pasar de publicada a reemplazada
CREATE FUNCTION ref_version_inmutable() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.estado IN ('publicada', 'reemplazada') THEN
            RAISE EXCEPTION 'La versión % de la referencia está publicada: no se borra', OLD.id USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.estado = 'reemplazada' OR (OLD.estado = 'publicada' AND (NEW.estado <> 'reemplazada'
            OR (to_jsonb(NEW) - 'estado' - 'reemplazada_en') <> (to_jsonb(OLD) - 'estado' - 'reemplazada_en'))) THEN
        RAISE EXCEPTION 'La versión % de la referencia está publicada: no se modifica', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_ref_version_inmutable BEFORE UPDATE OR DELETE ON ref_version FOR EACH ROW EXECUTE FUNCTION ref_version_inmutable();

-- ---------- CUM del INVIMA (cuatro conjuntos de datos.gov.co, 29 columnas) ----------
-- Staging de la carga: las 29 columnas tal como llegan (las fechas MM/DD/YYYY ya convertidas a aaaa-mm-dd en Java) y el CUM calculado;
-- se vacía al terminar la versión.
CREATE UNLOGGED TABLE ref_cum_staging (
    version_id            integer NOT NULL,
    linea                 bigint NOT NULL,
    cum                   text,
    expediente            text, producto text, titular text, registrosanitario text, fechaexpedicion text, fechavencimiento text,
    estadoregistro        text, expedientecum text, consecutivocum text, cantidadcum text, descripcioncomercial text, estadocum text,
    fechaactivo           text, fechainactivo text, muestramedica text, unidad text, atc text, descripcionatc text, viaadministracion text,
    concentracion         text, principioactivo text, unidadmedida text, cantidad text, unidadreferencia text, formafarmaceutica text,
    nombrerol             text, tiporol text, modalidad text, ium text
);
COMMENT ON TABLE ref_cum_staging IS 'Staging técnico de la carga del CUM del INVIMA (UNLOGGED): una fila del archivo por principio activo y rol; se agrupa por CUM y se vacía al terminar';
COMMENT ON COLUMN ref_cum_staging.version_id IS 'Versión en carga';
COMMENT ON COLUMN ref_cum_staging.linea IS 'Línea del archivo';
COMMENT ON COLUMN ref_cum_staging.cum IS 'CUM calculado (expediente-consecutivo) o nulo si la fila no trae una llave válida';
COMMENT ON COLUMN ref_cum_staging.expediente IS 'Columnas del INVIMA tal como llegan (fieldName de Socrata)';

CREATE TABLE ref_cum_presentacion (
    version_id            integer NOT NULL REFERENCES ref_version (id) ON DELETE RESTRICT,
    cum                   text NOT NULL CHECK (cum ~ '^\d{1,8}-\d{1,3}$'),
    conjunto              text NOT NULL CHECK (conjunto IN ('vigentes', 'renovacion', 'vencidos', 'otros')),
    expediente            bigint NOT NULL,
    consecutivo           integer NOT NULL,
    producto              text,
    producto_norm         text,
    titular               text,
    registro_sanitario    text,
    fecha_expedicion      date,
    fecha_vencimiento     date,
    estado_registro       text,
    cantidad_cum          numeric,
    descripcion_comercial text,
    estado_cum            text,
    fecha_activo          date,
    fecha_inactivo        date,
    muestra_medica        text,
    unidad                text,
    atc                   text,
    descripcion_atc       text,
    via_administracion    text,
    forma_farmaceutica    text,
    ium                   text,
    PRIMARY KEY (version_id, cum)
);
COMMENT ON TABLE ref_cum_presentacion IS 'Presentaciones del CUM del INVIMA por versión (una por CUM = expediente-consecutivo); fuente: datos.gov.co, CC BY-SA 4.0, INVIMA';
COMMENT ON COLUMN ref_cum_presentacion.version_id IS 'Versión de la fuente (conjunto del CUM)';
COMMENT ON COLUMN ref_cum_presentacion.cum IS 'CUM = expediente-consecutivo, sin ceros a la izquierda';
COMMENT ON COLUMN ref_cum_presentacion.conjunto IS 'Conjunto de datos de origen (vigentes, renovacion, vencidos, otros)';
COMMENT ON COLUMN ref_cum_presentacion.expediente IS 'Expediente del INVIMA';
COMMENT ON COLUMN ref_cum_presentacion.consecutivo IS 'Consecutivo de la presentación comercial';
COMMENT ON COLUMN ref_cum_presentacion.producto IS 'Nombre del producto';
COMMENT ON COLUMN ref_cum_presentacion.producto_norm IS 'Nombre normalizado para buscar (mayúsculas, sin tildes)';
COMMENT ON COLUMN ref_cum_presentacion.titular IS 'Titular del registro';
COMMENT ON COLUMN ref_cum_presentacion.registro_sanitario IS 'Registro sanitario («INVIMA 2023M-0013598-R2»)';
COMMENT ON COLUMN ref_cum_presentacion.fecha_expedicion IS 'Expedición del registro (MM/DD/YYYY en la fuente)';
COMMENT ON COLUMN ref_cum_presentacion.fecha_vencimiento IS 'Vencimiento del registro (R-003)';
COMMENT ON COLUMN ref_cum_presentacion.estado_registro IS 'Estado del registro (Vigente, Vencido, En trámite renov., otros)';
COMMENT ON COLUMN ref_cum_presentacion.cantidad_cum IS 'Unidades de la presentación (R-016)';
COMMENT ON COLUMN ref_cum_presentacion.descripcion_comercial IS 'Descripción del empaque';
COMMENT ON COLUMN ref_cum_presentacion.estado_cum IS 'Activo o Inactivo (R-021)';
COMMENT ON COLUMN ref_cum_presentacion.fecha_activo IS 'Fecha de activación del CUM';
COMMENT ON COLUMN ref_cum_presentacion.fecha_inactivo IS 'Fecha de inactivación del CUM';
COMMENT ON COLUMN ref_cum_presentacion.muestra_medica IS 'Muestra médica (Sí/No)';
COMMENT ON COLUMN ref_cum_presentacion.unidad IS 'Unidad de la presentación';
COMMENT ON COLUMN ref_cum_presentacion.atc IS 'ATC de 7 caracteres del INVIMA (en mayúsculas)';
COMMENT ON COLUMN ref_cum_presentacion.descripcion_atc IS 'Descripción del ATC del INVIMA';
COMMENT ON COLUMN ref_cum_presentacion.via_administracion IS 'Vía de administración';
COMMENT ON COLUMN ref_cum_presentacion.forma_farmaceutica IS 'Forma farmacéutica (R-015)';
COMMENT ON COLUMN ref_cum_presentacion.ium IS 'Identificador único de medicamento (15 caracteres; atributo, no llave: solo ~21 % lo trae)';
CREATE INDEX ix_ref_cum_pres_expediente ON ref_cum_presentacion (version_id, expediente);
CREATE INDEX ix_ref_cum_pres_atc ON ref_cum_presentacion (version_id, atc);
CREATE INDEX ix_ref_cum_pres_ium ON ref_cum_presentacion (ium) WHERE ium IS NOT NULL;
-- La homologación busca un CUM en todas las versiones publicadas a la vez (ANY de versiones)
CREATE INDEX ix_ref_cum_pres_cum ON ref_cum_presentacion (cum);

CREATE TABLE ref_cum_principio_activo (
    version_id        integer NOT NULL,
    cum               text NOT NULL,
    principio_activo  text NOT NULL,
    principio_norm    text NOT NULL,
    principio_base    text NOT NULL,
    cantidad          numeric,
    unidad_medida     text,
    concentracion     text,
    unidad_referencia text,
    PRIMARY KEY (version_id, cum, principio_norm),
    FOREIGN KEY (version_id, cum) REFERENCES ref_cum_presentacion (version_id, cum) ON DELETE RESTRICT
);
COMMENT ON TABLE ref_cum_principio_activo IS 'Principios activos de cada presentación del CUM (las filas del INVIMA se repiten por principio activo y rol)';
COMMENT ON COLUMN ref_cum_principio_activo.version_id IS 'Versión de la fuente';
COMMENT ON COLUMN ref_cum_principio_activo.cum IS 'CUM de la presentación';
COMMENT ON COLUMN ref_cum_principio_activo.principio_activo IS 'Principio activo tal como lo publica el INVIMA';
COMMENT ON COLUMN ref_cum_principio_activo.principio_norm IS 'Principio activo normalizado (mayúsculas, sin tildes)';
COMMENT ON COLUMN ref_cum_principio_activo.principio_base IS 'Base de búsqueda: el principio sin la sal (LOSARTAN POTASICO ⇒ LOSARTAN)';
COMMENT ON COLUMN ref_cum_principio_activo.cantidad IS 'Cantidad del principio activo (R-012)';
COMMENT ON COLUMN ref_cum_principio_activo.unidad_medida IS 'Unidad de la cantidad (mg, mcg, UI…)';
COMMENT ON COLUMN ref_cum_principio_activo.concentracion IS 'Código de concentración del INVIMA';
COMMENT ON COLUMN ref_cum_principio_activo.unidad_referencia IS 'Unidad de referencia (tableta, ml…)';
CREATE INDEX ix_ref_cum_pa_base ON ref_cum_principio_activo (principio_base, version_id);

CREATE TABLE ref_cum_rol (
    version_id integer NOT NULL,
    cum        text NOT NULL,
    orden      smallint NOT NULL,
    nombre_rol text NOT NULL,
    tipo_rol   text,
    modalidad  text,
    PRIMARY KEY (version_id, cum, orden),
    FOREIGN KEY (version_id, cum) REFERENCES ref_cum_presentacion (version_id, cum) ON DELETE RESTRICT
);
COMMENT ON TABLE ref_cum_rol IS 'Roles de cada presentación del CUM (fabricante, importador…) sin repetir';
COMMENT ON COLUMN ref_cum_rol.version_id IS 'Versión de la fuente';
COMMENT ON COLUMN ref_cum_rol.cum IS 'CUM de la presentación';
COMMENT ON COLUMN ref_cum_rol.orden IS 'Orden del rol en la presentación';
COMMENT ON COLUMN ref_cum_rol.nombre_rol IS 'Nombre del rol (empresa)';
COMMENT ON COLUMN ref_cum_rol.tipo_rol IS 'Tipo de rol';
COMMENT ON COLUMN ref_cum_rol.modalidad IS 'Modalidad (FABRICAR Y VENDER…)';

-- ---------- ATC: del propio CUM (CC BY-SA 4.0); el índice del WHOCC no se copia sin licencia ----------
CREATE TABLE ref_atc_nombre_nivel (
    codigo      text PRIMARY KEY CHECK (codigo ~ '^[A-Z](\d{2}([A-Z]([A-Z])?)?)?$'),
    nivel       smallint NOT NULL CHECK (nivel BETWEEN 1 AND 4),
    nombre      text NOT NULL CHECK (char_length(nombre) BETWEEN 1 AND 200),
    origen      text NOT NULL CHECK (origen IN ('manual', 'WHOCC licenciado')),
    actualizado timestamptz NOT NULL DEFAULT now(),
    CHECK (nivel = CASE char_length(codigo) WHEN 1 THEN 1 WHEN 3 THEN 2 WHEN 4 THEN 3 ELSE 4 END)
);
COMMENT ON TABLE ref_atc_nombre_nivel IS 'Nombres de los niveles 1 a 4 del ATC con su origen (manual o WHOCC licenciado); el nivel 5 sale del CUM del INVIMA (duda 2)';
COMMENT ON COLUMN ref_atc_nombre_nivel.codigo IS 'Código del nivel (A, A10, A10B, A10BA)';
COMMENT ON COLUMN ref_atc_nombre_nivel.nivel IS 'Nivel 1 a 4';
COMMENT ON COLUMN ref_atc_nombre_nivel.nombre IS 'Nombre del grupo';
COMMENT ON COLUMN ref_atc_nombre_nivel.origen IS 'manual (digitado) o WHOCC licenciado';
COMMENT ON COLUMN ref_atc_nombre_nivel.actualizado IS 'Última modificación';
INSERT INTO ref_atc_nombre_nivel (codigo, nivel, nombre, origen) VALUES
    ('A', 1, 'Tracto alimentario y metabolismo', 'manual'), ('B', 1, 'Sangre y órganos hematopoyéticos', 'manual'),
    ('C', 1, 'Sistema cardiovascular', 'manual'), ('D', 1, 'Dermatológicos', 'manual'),
    ('G', 1, 'Sistema genitourinario y hormonas sexuales', 'manual'),
    ('H', 1, 'Preparados hormonales sistémicos, excluye hormonas sexuales e insulinas', 'manual'),
    ('J', 1, 'Antiinfecciosos para uso sistémico', 'manual'), ('L', 1, 'Agentes antineoplásicos e inmunomoduladores', 'manual'),
    ('M', 1, 'Sistema musculoesquelético', 'manual'), ('N', 1, 'Sistema nervioso', 'manual'),
    ('P', 1, 'Productos antiparasitarios, insecticidas y repelentes', 'manual'), ('R', 1, 'Sistema respiratorio', 'manual'),
    ('S', 1, 'Órganos de los sentidos', 'manual'), ('V', 1, 'Varios', 'manual');

-- Catálogo ATC «observado»: códigos de nivel 5 de las versiones publicadas del CUM con la descripción del INVIMA y sus niveles
CREATE VIEW ref_atc_v AS
SELECT DISTINCT ON (p.atc) p.atc, p.descripcion_atc, left(p.atc, 1) AS nivel1, left(p.atc, 3) AS nivel2, left(p.atc, 4) AS nivel3,
       left(p.atc, 5) AS nivel4, v.id AS version_id
FROM ref_cum_presentacion p
JOIN ref_version v ON v.id = p.version_id AND v.estado = 'publicada'
WHERE p.atc ~ '^[A-Z]\d{2}[A-Z]{2}\d{2}$'
ORDER BY p.atc, p.conjunto = 'vigentes' DESC, v.id DESC;
COMMENT ON VIEW ref_atc_v IS 'ATC de nivel 5 presentes en las versiones publicadas del CUM con sus niveles 1 a 4 (left 1, 3, 4, 5)';

-- ---------- CIE-10 de SISPRO (código de 4 caracteres sin punto) ----------
CREATE TABLE ref_cie10 (
    version_id  integer NOT NULL REFERENCES ref_version (id) ON DELETE RESTRICT,
    codigo      char(4) NOT NULL CHECK (codigo ~ '^[A-Z]\d{2}[0-9A-Z]$'),
    nombre      text NOT NULL,
    grupo       char(3) NOT NULL,
    capitulo    text,
    sexo        text,
    edad_minima text,
    edad_maxima text,
    habilitado  boolean NOT NULL DEFAULT true,
    sistema     text NOT NULL DEFAULT 'CIE10' CHECK (sistema IN ('CIE10', 'CIE11')),
    PRIMARY KEY (version_id, codigo)
);
COMMENT ON TABLE ref_cie10 IS 'Diagnósticos CIE-10 de SISPRO por versión (tabla CIE10); columna sistema reservada para una versión CIE-11';
COMMENT ON COLUMN ref_cie10.version_id IS 'Versión de la fuente';
COMMENT ON COLUMN ref_cie10.codigo IS 'Código de 4 caracteres sin punto (E119)';
COMMENT ON COLUMN ref_cie10.nombre IS 'Nombre del diagnóstico';
COMMENT ON COLUMN ref_cie10.grupo IS 'Grupo de 3 caracteres (E11)';
COMMENT ON COLUMN ref_cie10.capitulo IS 'Capítulo (Extra_VI)';
COMMENT ON COLUMN ref_cie10.sexo IS 'Sexo al que aplica (Extra_I)';
COMMENT ON COLUMN ref_cie10.edad_minima IS 'Edad mínima (Extra_II)';
COMMENT ON COLUMN ref_cie10.edad_maxima IS 'Edad máxima (Extra_III)';
COMMENT ON COLUMN ref_cie10.habilitado IS 'Habilitado en SISPRO';
COMMENT ON COLUMN ref_cie10.sistema IS 'CIE10 (o CIE11 en una versión futura)';
CREATE INDEX ix_ref_cie10_grupo ON ref_cie10 (version_id, grupo);

-- ---------- Precio máximo regulado, vitales no disponibles, listas normativas y códigos SSFM ----------
CREATE TABLE ref_precio_regulado (
    version_id             integer NOT NULL REFERENCES ref_version (id) ON DELETE RESTRICT,
    linea                  integer NOT NULL,
    cum                    text NOT NULL,
    medicamento            text,
    mercado_relevante      text,
    cantidad_unidad        numeric,
    unidad_medida          text,
    precio_institucional   numeric(16, 2),
    margen_ips             numeric(16, 4),
    precio_comercial       numeric(16, 2),
    precio_final_comercial numeric(16, 2),
    circular               text NOT NULL,
    fecha_inicio           date,
    PRIMARY KEY (version_id, linea)
);
COMMENT ON TABLE ref_precio_regulado IS 'Precio máximo de venta regulado por presentación comercial (datos.gov.co nauz-qkjw; Circular 22 de 2026)';
COMMENT ON COLUMN ref_precio_regulado.version_id IS 'Versión de la fuente';
COMMENT ON COLUMN ref_precio_regulado.linea IS 'Línea del archivo';
COMMENT ON COLUMN ref_precio_regulado.cum IS 'CUM (expediente-consecutivo)';
COMMENT ON COLUMN ref_precio_regulado.medicamento IS 'Medicamento';
COMMENT ON COLUMN ref_precio_regulado.mercado_relevante IS 'Mercado relevante';
COMMENT ON COLUMN ref_precio_regulado.cantidad_unidad IS 'Cantidad por unidad de medida';
COMMENT ON COLUMN ref_precio_regulado.unidad_medida IS 'Unidad de medida';
COMMENT ON COLUMN ref_precio_regulado.precio_institucional IS 'Precio máximo transacción primaria, secundaria y final institucional (texto convertido)';
COMMENT ON COLUMN ref_precio_regulado.margen_ips IS 'Margen para IPS';
COMMENT ON COLUMN ref_precio_regulado.precio_comercial IS 'Precio máximo transacción primaria y secundaria comercial';
COMMENT ON COLUMN ref_precio_regulado.precio_final_comercial IS 'Precio máximo transacción final comercial';
COMMENT ON COLUMN ref_precio_regulado.circular IS 'Circular de la CNPMDM';
COMMENT ON COLUMN ref_precio_regulado.fecha_inicio IS 'Inicio de vigencia del precio máximo';
CREATE UNIQUE INDEX uq_ref_precio_llave ON ref_precio_regulado (version_id, cum, circular, fecha_inicio);

CREATE TABLE ref_vital_no_disponible (
    version_id          integer NOT NULL REFERENCES ref_version (id) ON DELETE RESTRICT,
    linea               integer NOT NULL,
    nombre_comercial    text,
    principio_activo1   text,
    principio_norm1     text,
    concentracion1      text,
    unidad1             text,
    principio_activo2   text,
    principio_norm2     text,
    concentracion2      text,
    unidad2             text,
    forma_farmaceutica  text,
    presentacion        text,
    cantidad_solicitada text,
    tipo_solicitud      text,
    fecha_autorizacion  date,
    cie10               text,
    ium                 text,
    PRIMARY KEY (version_id, linea)
);
COMMENT ON TABLE ref_vital_no_disponible IS 'Medicamentos vitales no disponibles del INVIMA (sdmr-tfmf; sin CUM: se cruzan por IUM o principio activo). No se guarda el solicitante';
COMMENT ON COLUMN ref_vital_no_disponible.version_id IS 'Versión de la fuente';
COMMENT ON COLUMN ref_vital_no_disponible.linea IS 'Línea del archivo';
COMMENT ON COLUMN ref_vital_no_disponible.nombre_comercial IS 'Nombre comercial';
COMMENT ON COLUMN ref_vital_no_disponible.principio_activo1 IS 'Primer principio activo';
COMMENT ON COLUMN ref_vital_no_disponible.principio_norm1 IS 'Primer principio activo normalizado';
COMMENT ON COLUMN ref_vital_no_disponible.concentracion1 IS 'Concentración del primero';
COMMENT ON COLUMN ref_vital_no_disponible.unidad1 IS 'Unidad del primero';
COMMENT ON COLUMN ref_vital_no_disponible.principio_activo2 IS 'Segundo principio activo';
COMMENT ON COLUMN ref_vital_no_disponible.principio_norm2 IS 'Segundo principio activo normalizado';
COMMENT ON COLUMN ref_vital_no_disponible.concentracion2 IS 'Concentración del segundo';
COMMENT ON COLUMN ref_vital_no_disponible.unidad2 IS 'Unidad del segundo';
COMMENT ON COLUMN ref_vital_no_disponible.forma_farmaceutica IS 'Forma farmacéutica';
COMMENT ON COLUMN ref_vital_no_disponible.presentacion IS 'Presentación comercial';
COMMENT ON COLUMN ref_vital_no_disponible.cantidad_solicitada IS 'Cantidad solicitada';
COMMENT ON COLUMN ref_vital_no_disponible.tipo_solicitud IS 'Tipo de solicitud';
COMMENT ON COLUMN ref_vital_no_disponible.fecha_autorizacion IS 'Fecha de autorización';
COMMENT ON COLUMN ref_vital_no_disponible.cie10 IS 'Código del diagnóstico (CIE-10)';
COMMENT ON COLUMN ref_vital_no_disponible.ium IS 'IUM, si viene';
CREATE INDEX ix_ref_vital_principio ON ref_vital_no_disponible (principio_norm1, version_id);
CREATE INDEX ix_ref_vital_ium ON ref_vital_no_disponible (ium) WHERE ium IS NOT NULL;

CREATE TABLE ref_lista_normativa (
    version_id       integer NOT NULL REFERENCES ref_version (id) ON DELETE RESTRICT,
    lista            text NOT NULL CHECK (lista IN ('estrecho_margen', 'control_especial')),
    principio_activo text NOT NULL CHECK (char_length(principio_activo) BETWEEN 1 AND 200),
    principio_norm   text NOT NULL,
    norma            text NOT NULL CHECK (char_length(norma) BETWEEN 1 AND 300),
    fecha_norma      date,
    observacion      text CHECK (char_length(observacion) <= 500),
    PRIMARY KEY (version_id, principio_norm)
);
COMMENT ON TABLE ref_lista_normativa IS 'Listas normativas que solo existen en PDF (estrecho margen, control especial), por principio activo; editables desde la pantalla con traza (cada cambio es una versión)';
COMMENT ON COLUMN ref_lista_normativa.version_id IS 'Versión de la lista';
COMMENT ON COLUMN ref_lista_normativa.lista IS 'estrecho_margen o control_especial';
COMMENT ON COLUMN ref_lista_normativa.principio_activo IS 'Principio activo como se digitó';
COMMENT ON COLUMN ref_lista_normativa.principio_norm IS 'Principio activo normalizado (lo leen cohortes y alertas)';
COMMENT ON COLUMN ref_lista_normativa.norma IS 'Norma o documento que lo incluye (p. ej. «INVIMA, listado de estrecho margen, julio de 2024»)';
COMMENT ON COLUMN ref_lista_normativa.fecha_norma IS 'Fecha de la norma';
COMMENT ON COLUMN ref_lista_normativa.observacion IS 'Observación';

CREATE TABLE ref_codigo_ssfm (
    version_id       integer NOT NULL REFERENCES ref_version (id) ON DELETE RESTRICT,
    codigo           text NOT NULL CHECK (char_length(codigo) BETWEEN 1 AND 40),
    cum              text,
    principio_activo text,
    principio_norm   text,
    concentracion    text,
    forma            text,
    descripcion      text,
    PRIMARY KEY (version_id, codigo),
    CHECK (cum IS NOT NULL OR principio_norm IS NOT NULL)
);
COMMENT ON TABLE ref_codigo_ssfm IS 'Catálogo de códigos internos SSFM de la DIGSA (A5 2.13): por CUM o por DCI + concentración + forma (R-030)';
COMMENT ON COLUMN ref_codigo_ssfm.version_id IS 'Versión del catálogo';
COMMENT ON COLUMN ref_codigo_ssfm.codigo IS 'Código SSFM';
COMMENT ON COLUMN ref_codigo_ssfm.cum IS 'CUM al que corresponde, si es por presentación';
COMMENT ON COLUMN ref_codigo_ssfm.principio_activo IS 'Principio activo (DCI), si es genérico';
COMMENT ON COLUMN ref_codigo_ssfm.principio_norm IS 'DCI normalizada';
COMMENT ON COLUMN ref_codigo_ssfm.concentracion IS 'Concentración';
COMMENT ON COLUMN ref_codigo_ssfm.forma IS 'Forma farmacéutica';
COMMENT ON COLUMN ref_codigo_ssfm.descripcion IS 'Descripción';

-- ---------- Reglas, catálogos e historial de cambios ----------
CREATE TABLE hom_regla (
    id              text PRIMARY KEY CHECK (id ~ '^R-\d{3}$'),
    nombre          text NOT NULL CHECK (char_length(nombre) BETWEEN 1 AND 160),
    orden           smallint NOT NULL CHECK (orden BETWEEN 1 AND 999),
    activa          boolean NOT NULL DEFAULT true,
    obligatoria     boolean NOT NULL DEFAULT false,
    parametros      jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(parametros) = 'object'),
    version         integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    actualizado     timestamptz NOT NULL DEFAULT now(),
    actualizado_por bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    CHECK (activa OR NOT obligatoria)
);
COMMENT ON TABLE hom_regla IS 'Reglas deterministas de homologación con su versión vigente (cada cambio sube la versión y deja historial)';
COMMENT ON COLUMN hom_regla.id IS 'Id estable (R-001…; los del mockup se conservan)';
COMMENT ON COLUMN hom_regla.nombre IS 'Nombre de la regla';
COMMENT ON COLUMN hom_regla.orden IS 'Orden de aplicación (y desempate de la regla que decide)';
COMMENT ON COLUMN hom_regla.activa IS 'Activa (R-001, R-002 y R-050 son obligatorias)';
COMMENT ON COLUMN hom_regla.obligatoria IS 'No se puede desactivar';
COMMENT ON COLUMN hom_regla.parametros IS 'Parámetros (p. ej. R-015 tolerancia: 0 exacta, 1 misma familia, 2 no compara)';
COMMENT ON COLUMN hom_regla.version IS 'Versión vigente de la regla';
COMMENT ON COLUMN hom_regla.actualizado IS 'Último cambio';
COMMENT ON COLUMN hom_regla.actualizado_por IS 'Quién hizo el último cambio';
CREATE INDEX ix_hom_regla_actualizado_por ON hom_regla (actualizado_por) WHERE actualizado_por IS NOT NULL;

CREATE TABLE hom_regla_historial (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    objeto            text NOT NULL CHECK (objeto ~ '^(R-\d{3}|catalogo:[a-z_]+|lista:[A-Z0-9_]+)$'),
    version           integer NOT NULL CHECK (version >= 1),
    antes             jsonb,
    despues           jsonb NOT NULL,
    descripcion       text NOT NULL CHECK (char_length(descripcion) <= 500),
    usuario_id        bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    responsable_texto text NOT NULL CHECK (char_length(responsable_texto) <= 120),
    fecha             timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE hom_regla_historial IS 'Traza de los cambios de reglas, catálogos y listas normativas: usuario, fecha, valor anterior y nuevo (TAS P5); no se modifica';
COMMENT ON COLUMN hom_regla_historial.objeto IS 'Regla (R-015), catálogo (catalogo:sinonimo_dci) o lista (lista:INVIMA_ESTRECHO_MARGEN)';
COMMENT ON COLUMN hom_regla_historial.version IS 'Versión nueva que creó el cambio';
COMMENT ON COLUMN hom_regla_historial.antes IS 'Valor anterior (JSON)';
COMMENT ON COLUMN hom_regla_historial.despues IS 'Valor nuevo (JSON)';
COMMENT ON COLUMN hom_regla_historial.descripcion IS 'Resumen legible («Regla R-015 modificada (tolerancia de forma farmacéutica)»)';
COMMENT ON COLUMN hom_regla_historial.usuario_id IS 'Quién hizo el cambio';
COMMENT ON COLUMN hom_regla_historial.responsable_texto IS 'Responsable legible con su perfil';
COMMENT ON COLUMN hom_regla_historial.fecha IS 'Fecha y hora del cambio';
CREATE INDEX ix_hom_historial_objeto ON hom_regla_historial (objeto, id DESC);
CREATE INDEX ix_hom_historial_usuario ON hom_regla_historial (usuario_id) WHERE usuario_id IS NOT NULL;

CREATE TABLE hom_catalogo (
    tipo    text NOT NULL CHECK (tipo IN ('sinonimo_dci', 'sal', 'abreviatura', 'unidad', 'forma')),
    version integer NOT NULL CHECK (version >= 1),
    clave   text NOT NULL CHECK (char_length(clave) BETWEEN 1 AND 120),
    valor   text NOT NULL CHECK (char_length(valor) BETWEEN 1 AND 200),
    extra   text CHECK (char_length(extra) <= 120),
    PRIMARY KEY (tipo, version, clave)
);
COMMENT ON TABLE hom_catalogo IS 'Catálogos parametrizables de la homologación por versión (la vigente es la mayor de cada tipo; cada edición crea una versión completa)';
COMMENT ON COLUMN hom_catalogo.tipo IS 'sinonimo_dci (R-004), sal (R-013), abreviatura (R-010), unidad (R-011) o forma (R-015)';
COMMENT ON COLUMN hom_catalogo.version IS 'Versión del catálogo';
COMMENT ON COLUMN hom_catalogo.clave IS 'Texto normalizado de entrada (ACETAMINOFEN, TAB, MILIGRAMOS, CAPSULA DURA…)';
COMMENT ON COLUMN hom_catalogo.valor IS 'Valor (PARACETAMOL, TABLETA, MG, forma canónica…)';
COMMENT ON COLUMN hom_catalogo.extra IS 'Familia de la forma farmacéutica (tolerancia 1 de R-015)';

-- ---------- Datos originales, versiones de la tabla maestra y revisión ----------
-- Versionado por diferencia (revisión A2): una versión guarda en hom_tabla_maestra y hom_tabla_cie10 solo las filas que calculó y
-- cambiaron; la versión N completa es, por dato original, la fila más reciente de las versiones publicadas con número ≤ N más la capa
-- de decisiones humanas (hom_revision) de número ≤ N (función hom_tabla_maestra_version). Aprobar o devolver no copia filas. La
-- versión de un cargue nace «pendiente» (sin número, invisible) en la etapa de homologación y se publica en la transacción que marca
-- el cargue «aplicado»; si el cargue falla, termina duplicado o se descarta, se borra con sus filas.
CREATE TABLE hom_version (
    id                integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero            integer UNIQUE CHECK (numero >= 1),
    estado            text NOT NULL DEFAULT 'publicada' CHECK (estado IN ('pendiente', 'publicada')),
    creada            timestamptz NOT NULL DEFAULT now(),
    motivo            text NOT NULL CHECK (motivo IN ('cargue', 'reejecucion', 'aprobacion', 'devolucion')),
    cargue_id         bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    anterior_id       integer REFERENCES hom_version (id) ON DELETE RESTRICT,
    usuario_id        bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    responsable_texto text NOT NULL CHECK (char_length(responsable_texto) <= 120),
    fuentes           jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(fuentes) = 'object'),
    reglas            jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(reglas) = 'object'),
    catalogos         jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(catalogos) = 'object'),
    origenes          integer NOT NULL DEFAULT 0 CHECK (origenes >= 0),
    conteo            jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(conteo) = 'object'),
    cambios           integer NOT NULL DEFAULT 0 CHECK (cambios >= 0),
    duracion_ms       bigint CHECK (duracion_ms >= 0),
    detalle           text CHECK (char_length(detalle) <= 500),
    CHECK ((estado = 'publicada') = (numero IS NOT NULL)),
    CHECK (estado = 'publicada' OR motivo IN ('cargue', 'reejecucion'))
);
COMMENT ON TABLE hom_version IS 'Versiones de la tabla maestra: cada ejecución (cargue, re-ejecución, aprobación o devolución) crea una; las publicadas no se modifican';
COMMENT ON COLUMN hom_version.numero IS 'Número de la versión (v1, v2…) asignado al publicarla (orden de publicación); nulo mientras está pendiente';
COMMENT ON COLUMN hom_version.estado IS 'pendiente (versión de un cargue aún no aplicado: invisible; se borra si el cargue no se aplica) o publicada';
COMMENT ON COLUMN hom_version.creada IS 'Fecha y hora de publicación: vigente desde ahí para los cortes posteriores';
COMMENT ON COLUMN hom_version.motivo IS 'cargue (datos originales nuevos), reejecucion (desde la pantalla), aprobacion o devolucion (Revisión)';
COMMENT ON COLUMN hom_version.cargue_id IS 'Cargue que la originó (motivo cargue)';
COMMENT ON COLUMN hom_version.anterior_id IS 'Versión anterior';
COMMENT ON COLUMN hom_version.usuario_id IS 'Usuario, o nulo si fue el proceso automático del cargue';
COMMENT ON COLUMN hom_version.responsable_texto IS 'Responsable legible («Proceso automático · cargue 17» o «jgarcia · analista»)';
COMMENT ON COLUMN hom_version.fuentes IS 'Versiones de cada fuente con que se calcularon las filas de esta versión (código ⇒ id, número, fecha de corte, ejemplo)';
COMMENT ON COLUMN hom_version.reglas IS 'Versión, orden, activa y parámetros de cada regla con que se calcularon sus filas';
COMMENT ON COLUMN hom_version.catalogos IS 'Versión de cada catálogo usado';
COMMENT ON COLUMN hom_version.origenes IS 'Datos originales en la versión completa (con los de las anteriores)';
COMMENT ON COLUMN hom_version.conteo IS 'Datos originales por estado en la versión completa, con las decisiones humanas';
COMMENT ON COLUMN hom_version.cambios IS 'Datos originales nuevos o que cambiaron de estado frente a la versión anterior';
COMMENT ON COLUMN hom_version.duracion_ms IS 'Duración de la ejecución';
COMMENT ON COLUMN hom_version.detalle IS 'Resumen legible';
CREATE INDEX ix_hom_version_creada ON hom_version (creada DESC, id DESC) WHERE estado = 'publicada';
CREATE INDEX ix_hom_version_cargue ON hom_version (cargue_id) WHERE cargue_id IS NOT NULL;
CREATE INDEX ix_hom_version_anterior ON hom_version (anterior_id) WHERE anterior_id IS NOT NULL;
CREATE INDEX ix_hom_version_usuario ON hom_version (usuario_id) WHERE usuario_id IS NOT NULL;

CREATE TABLE hom_medicamento_origen (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    llave            char(64) NOT NULL UNIQUE,
    cum_original     text CHECK (char_length(cum_original) <= 100),
    cum_normalizado  text,
    descripcion      text CHECK (char_length(descripcion) <= 1000),
    descripcion_norm text NOT NULL DEFAULT '',
    principio_activo text CHECK (char_length(principio_activo) <= 500),
    atc              text CHECK (char_length(atc) <= 20),
    codigo_ssfm      text CHECK (char_length(codigo_ssfm) <= 60),
    fecha_referencia date,
    primer_cargue_id bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    ultimo_cargue_id bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    pendiente_cargue_id bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    creado           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_hom_origen_pendiente ON hom_medicamento_origen (pendiente_cargue_id) WHERE pendiente_cargue_id IS NOT NULL;
CREATE INDEX ix_hom_origen_desc ON hom_medicamento_origen (descripcion_norm) WHERE descripcion_norm <> '';
COMMENT ON COLUMN hom_medicamento_origen.pendiente_cargue_id IS 'Cargue aún no aplicado que lo trajo por primera vez (invisible hasta aplicarlo; se borra si el cargue no se aplica); nulo = confirmado';
COMMENT ON TABLE hom_medicamento_origen IS 'Dato original distinto del insumo (id estable = medicamento_id de los hechos); llave: CUM normalizado + descripción normalizada';
COMMENT ON COLUMN hom_medicamento_origen.llave IS 'SHA-256 de CUM normalizado | descripción normalizada';
COMMENT ON COLUMN hom_medicamento_origen.cum_original IS 'CUM tal como llegó (primera vez)';
COMMENT ON COLUMN hom_medicamento_origen.cum_normalizado IS 'CUM normalizado por R-002 (nulo si no cumple el formato)';
COMMENT ON COLUMN hom_medicamento_origen.descripcion IS 'Descripción del medicamento tal como llegó';
COMMENT ON COLUMN hom_medicamento_origen.descripcion_norm IS 'Descripción normalizada (R-010; buscador)';
COMMENT ON COLUMN hom_medicamento_origen.principio_activo IS 'Principio activo del insumo: el más frecuente en sus registros del primer cargue (empate: el menor lexicográfico)';
COMMENT ON COLUMN hom_medicamento_origen.atc IS 'ATC del insumo (misma regla del más frecuente)';
COMMENT ON COLUMN hom_medicamento_origen.codigo_ssfm IS 'Código SSFM del insumo (misma regla del más frecuente)';
COMMENT ON COLUMN hom_medicamento_origen.fecha_referencia IS 'Fecha de dispensación más reciente de los cargues aplicados (vigencia del registro, R-003; ver la decisión en la spec)';
COMMENT ON COLUMN hom_medicamento_origen.primer_cargue_id IS 'Cargue en que apareció';
COMMENT ON COLUMN hom_medicamento_origen.ultimo_cargue_id IS 'Último cargue aplicado que lo trajo';
COMMENT ON COLUMN hom_medicamento_origen.creado IS 'Fecha de creación';
CREATE INDEX ix_hom_origen_primer ON hom_medicamento_origen (primer_cargue_id);
CREATE INDEX ix_hom_origen_ultimo ON hom_medicamento_origen (ultimo_cargue_id);

CREATE TABLE hom_revision (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    origen_id         bigint NOT NULL REFERENCES hom_medicamento_origen (id) ON DELETE RESTRICT,
    version_id        integer NOT NULL REFERENCES hom_version (id) ON DELETE RESTRICT,
    accion            text NOT NULL CHECK (accion IN ('aprobar', 'devolver')),
    cum_elegido       text,
    dato              jsonb CHECK (dato IS NULL OR jsonb_typeof(dato) = 'object'),
    antes             jsonb NOT NULL CHECK (jsonb_typeof(antes) = 'object'),
    despues           jsonb NOT NULL CHECK (jsonb_typeof(despues) = 'object'),
    motivo            text CHECK (char_length(motivo) <= 500),
    usuario_id        bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    responsable_texto text NOT NULL CHECK (char_length(responsable_texto) <= 120),
    fecha             timestamptz NOT NULL DEFAULT now(),
    CHECK (accion <> 'devolver' OR char_length(motivo) > 0),
    CHECK (accion <> 'aprobar' OR dato IS NOT NULL)
);
COMMENT ON TABLE hom_revision IS 'Decisiones humanas de «Revisión»: aprobar (dato final elegido, regla MANUAL) o devolver al OPL con motivo; la IA nunca decide';
COMMENT ON COLUMN hom_revision.origen_id IS 'Dato original revisado';
COMMENT ON COLUMN hom_revision.version_id IS 'Versión de la tabla maestra que creó la decisión';
COMMENT ON COLUMN hom_revision.accion IS 'aprobar o devolver';
COMMENT ON COLUMN hom_revision.cum_elegido IS 'CUM elegido al aprobar (el del INVIMA o uno buscado)';
COMMENT ON COLUMN hom_revision.dato IS 'Dato final aprobado (se conserva en las versiones siguientes)';
COMMENT ON COLUMN hom_revision.antes IS 'Estado, regla y dato antes de la decisión';
COMMENT ON COLUMN hom_revision.despues IS 'Estado, regla y dato después';
COMMENT ON COLUMN hom_revision.motivo IS 'Motivo de la devolución (≤ 500 caracteres)';
COMMENT ON COLUMN hom_revision.usuario_id IS 'Quién decidió';
COMMENT ON COLUMN hom_revision.responsable_texto IS 'Responsable legible con su perfil';
COMMENT ON COLUMN hom_revision.fecha IS 'Fecha y hora';
CREATE INDEX ix_hom_revision_origen ON hom_revision (origen_id, id DESC);
CREATE INDEX ix_hom_revision_version ON hom_revision (version_id);
CREATE INDEX ix_hom_revision_usuario ON hom_revision (usuario_id);

CREATE TABLE hom_tabla_maestra (
    version_id               integer NOT NULL REFERENCES hom_version (id) ON DELETE RESTRICT,
    origen_id                bigint NOT NULL REFERENCES hom_medicamento_origen (id) ON DELETE RESTRICT,
    orig_cum                 text,
    orig_descripcion         text,
    orig_principio           text,
    orig_concentracion       text,
    orig_forma               text,
    orig_presentacion        text,
    orig_unidad              text,
    orig_ssfm                text,
    orig_atc                 text,
    fuente_codigo            text,
    fuente_version_id        integer REFERENCES ref_version (id) ON DELETE RESTRICT,
    fuente_fecha_corte       date,
    regla                    text NOT NULL CHECK (regla ~ '^(R-\d{3}|MANUAL)$'),
    causa                    text CHECK (char_length(causa) <= 500),
    reglas_aplicadas         jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(reglas_aplicadas) = 'array'),
    fin_nombre_comercial     text,
    fin_dci                  text,
    fin_principio_activo     text,
    fin_concentracion        text,
    fin_forma                text,
    fin_presentacion         text,
    fin_unidad               text,
    fin_cum                  text,
    fin_codigo_ssfm          text,
    fin_atc                  text,
    fin_grupo_farmacologico  text,
    fin_registro_sanitario   text,
    estado                   text NOT NULL CHECK (estado IN ('homologado', 'inconsistente', 'duplicado', 'sin_correspondencia', 'pendiente', 'no_valido')),
    diferencias              text[] NOT NULL DEFAULT '{}',
    candidatos               text[] NOT NULL DEFAULT '{}',
    devuelto                 boolean NOT NULL DEFAULT false CHECK (NOT devuelto),
    revision_id              bigint CHECK (revision_id IS NULL),
    huella                   char(64) NOT NULL,
    PRIMARY KEY (version_id, origen_id),
    CHECK (estado <> 'inconsistente' OR cardinality(diferencias) > 0),
    CHECK (regla <> 'MANUAL')
);
COMMENT ON TABLE hom_tabla_maestra IS 'Tabla maestra normalizada (A5 L691), filas calculadas por las reglas: cada versión guarda solo las nuevas o cambiadas (la versión completa y las decisiones humanas: hom_tabla_maestra_version); no se modifica';
COMMENT ON COLUMN hom_tabla_maestra.huella IS 'SHA-256 del contenido calculado (sin la versión): una ejecución solo guarda la fila si cambió';
COMMENT ON COLUMN hom_tabla_maestra.version_id IS 'Versión de la tabla maestra';
COMMENT ON COLUMN hom_tabla_maestra.origen_id IS 'Dato original (medicamento_id de los hechos)';
COMMENT ON COLUMN hom_tabla_maestra.orig_cum IS 'CUM tal como llegó';
COMMENT ON COLUMN hom_tabla_maestra.orig_descripcion IS 'Descripción normalizada del insumo';
COMMENT ON COLUMN hom_tabla_maestra.orig_principio IS 'Principio activo del insumo (o leído de la descripción)';
COMMENT ON COLUMN hom_tabla_maestra.orig_concentracion IS 'Concentración leída del insumo';
COMMENT ON COLUMN hom_tabla_maestra.orig_forma IS 'Forma farmacéutica leída del insumo';
COMMENT ON COLUMN hom_tabla_maestra.orig_presentacion IS 'Presentación leída del insumo';
COMMENT ON COLUMN hom_tabla_maestra.orig_unidad IS 'Unidad de la concentración del insumo';
COMMENT ON COLUMN hom_tabla_maestra.orig_ssfm IS 'Código SSFM del insumo';
COMMENT ON COLUMN hom_tabla_maestra.orig_atc IS 'ATC del insumo';
COMMENT ON COLUMN hom_tabla_maestra.fuente_codigo IS 'Fuente consultada (código de ref_fuente; nula = sin fuente)';
COMMENT ON COLUMN hom_tabla_maestra.fuente_version_id IS 'Versión de la fuente consultada';
COMMENT ON COLUMN hom_tabla_maestra.fuente_fecha_corte IS 'Fecha de corte de esa versión';
COMMENT ON COLUMN hom_tabla_maestra.regla IS 'Regla que decidió el estado (R-0xx) o MANUAL (Revisión)';
COMMENT ON COLUMN hom_tabla_maestra.causa IS 'Explicación corta';
COMMENT ON COLUMN hom_tabla_maestra.reglas_aplicadas IS 'Reglas que transformaron el dato, en orden, con el valor antes y después';
COMMENT ON COLUMN hom_tabla_maestra.fin_nombre_comercial IS 'Dato final: nombre comercial';
COMMENT ON COLUMN hom_tabla_maestra.fin_dci IS 'Dato final: DCI';
COMMENT ON COLUMN hom_tabla_maestra.fin_principio_activo IS 'Dato final: principio activo';
COMMENT ON COLUMN hom_tabla_maestra.fin_concentracion IS 'Dato final: concentración';
COMMENT ON COLUMN hom_tabla_maestra.fin_forma IS 'Dato final: forma farmacéutica';
COMMENT ON COLUMN hom_tabla_maestra.fin_presentacion IS 'Dato final: presentación';
COMMENT ON COLUMN hom_tabla_maestra.fin_unidad IS 'Dato final: unidad';
COMMENT ON COLUMN hom_tabla_maestra.fin_cum IS 'Dato final: CUM';
COMMENT ON COLUMN hom_tabla_maestra.fin_codigo_ssfm IS 'Dato final: código SSFM';
COMMENT ON COLUMN hom_tabla_maestra.fin_atc IS 'Dato final: ATC (nivel 5)';
COMMENT ON COLUMN hom_tabla_maestra.fin_grupo_farmacologico IS 'Dato final: grupo farmacológico (ATC nivel 3)';
COMMENT ON COLUMN hom_tabla_maestra.fin_registro_sanitario IS 'Dato final: registro sanitario';
COMMENT ON COLUMN hom_tabla_maestra.estado IS 'Estado único (precedencia: no válido > duplicado > inconsistente > sin correspondencia > pendiente > homologado)';
COMMENT ON COLUMN hom_tabla_maestra.diferencias IS 'Diferencias encontradas (principio activo, concentración, forma, presentación)';
COMMENT ON COLUMN hom_tabla_maestra.candidatos IS 'CUM candidatos para la revisión';
COMMENT ON COLUMN hom_tabla_maestra.devuelto IS 'Siempre falso en las filas guardadas: «devuelto al OPL» lo pone la capa de decisiones al leer (hom_tabla_maestra_version)';
COMMENT ON COLUMN hom_tabla_maestra.revision_id IS 'Siempre nulo en las filas guardadas: la decisión humana (regla MANUAL) la pone la capa de decisiones al leer';
CREATE INDEX ix_hom_tm_origen ON hom_tabla_maestra (origen_id, version_id);
CREATE INDEX ix_hom_tm_fuente ON hom_tabla_maestra (fuente_version_id) WHERE fuente_version_id IS NOT NULL;

CREATE TABLE hom_cie10_origen (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo_original     text NOT NULL UNIQUE CHECK (char_length(codigo_original) <= 40),
    primer_cargue_id    bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    pendiente_cargue_id bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    creado              timestamptz NOT NULL DEFAULT now()
);
COMMENT ON COLUMN hom_cie10_origen.pendiente_cargue_id IS 'Cargue aún no aplicado que lo trajo por primera vez (se borra si no se aplica); nulo = confirmado';
CREATE INDEX ix_hom_cie10_origen_pendiente ON hom_cie10_origen (pendiente_cargue_id) WHERE pendiente_cargue_id IS NOT NULL;
COMMENT ON TABLE hom_cie10_origen IS 'Códigos CIE-10 distintos del insumo (normalizados por la ingesta)';
COMMENT ON COLUMN hom_cie10_origen.codigo_original IS 'Código tal como quedó en los hechos (sin punto)';
COMMENT ON COLUMN hom_cie10_origen.primer_cargue_id IS 'Cargue en que apareció';
COMMENT ON COLUMN hom_cie10_origen.creado IS 'Fecha de creación';
CREATE INDEX ix_hom_cie10_origen_cargue ON hom_cie10_origen (primer_cargue_id);

CREATE TABLE hom_tabla_cie10 (
    version_id        integer NOT NULL REFERENCES hom_version (id) ON DELETE RESTRICT,
    origen_id         bigint NOT NULL REFERENCES hom_cie10_origen (id) ON DELETE RESTRICT,
    codigo_final      char(4),
    estado            text NOT NULL CHECK (estado IN ('homologado', 'sin_correspondencia', 'pendiente')),
    regla             text NOT NULL DEFAULT 'R-060' CHECK (regla = 'R-060'),
    causa             text CHECK (char_length(causa) <= 300),
    fuente_version_id integer REFERENCES ref_version (id) ON DELETE RESTRICT,
    PRIMARY KEY (version_id, origen_id),
    CHECK ((estado = 'homologado') = (codigo_final IS NOT NULL))
);
COMMENT ON TABLE hom_tabla_cie10 IS 'Homologación de los códigos CIE-10 (R-060): cada versión guarda solo los códigos nuevos o cambiados (versión completa: hom_tabla_cie10_version); no se modifica';
COMMENT ON COLUMN hom_tabla_cie10.version_id IS 'Versión de la tabla maestra';
COMMENT ON COLUMN hom_tabla_cie10.origen_id IS 'Código original';
COMMENT ON COLUMN hom_tabla_cie10.codigo_final IS 'Código CIE-10 de 4 caracteres homologado';
COMMENT ON COLUMN hom_tabla_cie10.estado IS 'homologado, pendiente (grupo de 3 o sin referencia) o sin correspondencia';
COMMENT ON COLUMN hom_tabla_cie10.regla IS 'R-060';
COMMENT ON COLUMN hom_tabla_cie10.causa IS 'Explicación';
COMMENT ON COLUMN hom_tabla_cie10.fuente_version_id IS 'Versión de la CIE-10 consultada';
CREATE INDEX ix_hom_tc_origen ON hom_tabla_cie10 (origen_id);
CREATE INDEX ix_hom_tc_fuente ON hom_tabla_cie10 (fuente_version_id) WHERE fuente_version_id IS NOT NULL;

-- Correspondencia del cargue: (CUM, medicamento) tal como vienen en el staging ⇒ dato original. La carga de hechos se une por la llave
-- primaria (pocos miles de pares por cargue, aunque el cargue tenga 2 M de filas); los conteos por mes alimentan el tablero de calidad
-- sin recorrer los hechos (por eso A2 no crea índices B-tree en ana_dispensaciones: cada índice se mantiene fila a fila en la carga).
-- La llave es un SHA-256 del par, no el texto: una celda larga no puede pasar el máximo de una fila del B-tree (además, la ingesta
-- rechaza el medicamento, el CUM o el principio de más de 1000 caracteres con la causa texto_largo).
CREATE TABLE hom_cargue_origen (
    cargue_id      bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    par_llave      char(64) NOT NULL CHECK (par_llave ~ '^[0-9a-f]{64}$'),
    medicamento_id bigint NOT NULL REFERENCES hom_medicamento_origen (id) ON DELETE RESTRICT,
    version_id     integer NOT NULL REFERENCES hom_version (id) ON DELETE RESTRICT,
    registros      bigint NOT NULL CHECK (registros >= 0),
    ultima_fecha   date NOT NULL,
    PRIMARY KEY (cargue_id, par_llave)
);
COMMENT ON TABLE hom_cargue_origen IS 'Pares (CUM, medicamento) de cada cargue y su dato original y versión de la tabla maestra; se borran si el cargue no se aplica';
COMMENT ON COLUMN hom_cargue_origen.cargue_id IS 'Cargue';
COMMENT ON COLUMN hom_cargue_origen.par_llave IS 'encode(sha256(convert_to(coalesce(cum_original, '''') || chr(1) || coalesce(medicamento, ''''), ''UTF8'')), ''hex'') del staging (llave técnica, no secreta)';
COMMENT ON COLUMN hom_cargue_origen.medicamento_id IS 'Dato original';
COMMENT ON COLUMN hom_cargue_origen.version_id IS 'Versión de la tabla maestra del cargue (la suya, publicada al aplicarlo, o la vigente al homologarlo)';
COMMENT ON COLUMN hom_cargue_origen.registros IS 'Registros válidos del cargue con ese par';
COMMENT ON COLUMN hom_cargue_origen.ultima_fecha IS 'Fecha de dispensación más reciente del par en el cargue (al aplicarlo actualiza fecha_referencia del dato original)';
CREATE INDEX ix_hom_co_medicamento ON hom_cargue_origen (medicamento_id);
CREATE INDEX ix_hom_co_version ON hom_cargue_origen (version_id);

CREATE TABLE hom_cargue_mes (
    cargue_id      bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    medicamento_id bigint NOT NULL REFERENCES hom_medicamento_origen (id) ON DELETE RESTRICT,
    mes            date NOT NULL CHECK (extract(day FROM mes) = 1),
    registros      bigint NOT NULL CHECK (registros >= 0),
    PRIMARY KEY (cargue_id, medicamento_id, mes)
);
COMMENT ON TABLE hom_cargue_mes IS 'Registros de dispensación por cargue, dato original y mes (tablero de calidad por fuente, periodo y cargue)';
COMMENT ON COLUMN hom_cargue_mes.cargue_id IS 'Cargue';
COMMENT ON COLUMN hom_cargue_mes.medicamento_id IS 'Dato original';
COMMENT ON COLUMN hom_cargue_mes.mes IS 'Mes de dispensación (primer día)';
COMMENT ON COLUMN hom_cargue_mes.registros IS 'Registros válidos';
CREATE INDEX ix_hom_cm_medicamento ON hom_cargue_mes (medicamento_id);

CREATE TABLE hom_devolucion (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    origen_id           bigint NOT NULL REFERENCES hom_medicamento_origen (id) ON DELETE RESTRICT,
    revision_id         bigint NOT NULL REFERENCES hom_revision (id) ON DELETE RESTRICT,
    cargue_id           bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    fuente              text CHECK (fuente IN ('archivo', 'ws_opl')),
    periodo             date,
    motivo              text NOT NULL CHECK (char_length(motivo) BETWEEN 1 AND 500),
    fecha               timestamptz NOT NULL DEFAULT now(),
    usuario_id          bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    estado              text NOT NULL DEFAULT 'devuelta' CHECK (estado IN ('devuelta', 'corregida', 'persiste', 'resuelta')),
    corregida_cargue_id bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    resuelta_revision_id bigint REFERENCES hom_revision (id) ON DELETE RESTRICT,
    actualizado         timestamptz NOT NULL DEFAULT now(),
    CHECK ((estado = 'corregida') = (corregida_cargue_id IS NOT NULL)),
    CHECK ((estado = 'resuelta') = (resuelta_revision_id IS NOT NULL))
);
COMMENT ON TABLE hom_devolucion IS 'Devoluciones al OPL y su seguimiento por fuente y periodo (RESP L4361): devuelta, corregida (un cargue aplicado posterior trae el dato, o su corrección, homologado), persiste o resuelta (aprobada en Revisión)';
COMMENT ON COLUMN hom_devolucion.resuelta_revision_id IS 'Aprobación en Revisión que la cerró sin corrección del OPL';
CREATE INDEX ix_hom_dev_resuelta ON hom_devolucion (resuelta_revision_id) WHERE resuelta_revision_id IS NOT NULL;
COMMENT ON COLUMN hom_devolucion.origen_id IS 'Dato original devuelto';
COMMENT ON COLUMN hom_devolucion.revision_id IS 'Decisión de devolución';
COMMENT ON COLUMN hom_devolucion.cargue_id IS 'Último cargue que traía el dato al devolverlo';
COMMENT ON COLUMN hom_devolucion.fuente IS 'Fuente de ese cargue (archivo o ws_opl)';
COMMENT ON COLUMN hom_devolucion.periodo IS 'Mes de la dispensación más reciente del dato';
COMMENT ON COLUMN hom_devolucion.motivo IS 'Motivo';
COMMENT ON COLUMN hom_devolucion.fecha IS 'Fecha de la devolución';
COMMENT ON COLUMN hom_devolucion.usuario_id IS 'Quién devolvió';
COMMENT ON COLUMN hom_devolucion.estado IS 'devuelta, corregida, persiste o resuelta';
COMMENT ON COLUMN hom_devolucion.corregida_cargue_id IS 'Cargue aplicado que trajo el dato (o su corrección: mismo CUM o misma descripción) homologado';
COMMENT ON COLUMN hom_devolucion.actualizado IS 'Último cambio de estado';
CREATE INDEX ix_hom_dev_origen ON hom_devolucion (origen_id);
CREATE INDEX ix_hom_dev_revision ON hom_devolucion (revision_id);
CREATE INDEX ix_hom_dev_cargue ON hom_devolucion (cargue_id) WHERE cargue_id IS NOT NULL;
CREATE INDEX ix_hom_dev_corregida ON hom_devolucion (corregida_cargue_id) WHERE corregida_cargue_id IS NOT NULL;
CREATE INDEX ix_hom_dev_usuario ON hom_devolucion (usuario_id);
CREATE INDEX ix_hom_dev_estado ON hom_devolucion (estado, periodo);

-- Lo publicado no se modifica ni se borra: versiones de la tabla maestra, sus filas, el historial y las decisiones. Solo una versión
-- «pendiente» (la del cargue que aún no se aplica) se publica (UPDATE) o se borra con sus filas si el cargue no se aplica.
CREATE FUNCTION hom_inmutable() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    RAISE EXCEPTION 'La tabla % no se modifica ni se borra (versión publicada)', TG_TABLE_NAME USING ERRCODE = 'check_violation';
END $$;
CREATE FUNCTION hom_version_inmutable() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF OLD.estado = 'pendiente' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    RAISE EXCEPTION 'La tabla % no se modifica ni se borra (versión publicada)', TG_TABLE_NAME USING ERRCODE = 'check_violation';
END $$;
CREATE FUNCTION hom_fila_inmutable() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF TG_OP = 'DELETE' AND EXISTS (SELECT 1 FROM hom_version WHERE id = OLD.version_id AND estado = 'pendiente') THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'La tabla % no se modifica ni se borra (versión publicada)', TG_TABLE_NAME USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER tr_hom_version_inmutable BEFORE UPDATE OR DELETE ON hom_version FOR EACH ROW EXECUTE FUNCTION hom_version_inmutable();
CREATE TRIGGER tr_hom_tabla_maestra_inmutable BEFORE UPDATE OR DELETE ON hom_tabla_maestra FOR EACH ROW EXECUTE FUNCTION hom_fila_inmutable();
CREATE TRIGGER tr_hom_tabla_cie10_inmutable BEFORE UPDATE OR DELETE ON hom_tabla_cie10 FOR EACH ROW EXECUTE FUNCTION hom_fila_inmutable();
CREATE TRIGGER tr_hom_historial_inmutable BEFORE UPDATE OR DELETE ON hom_regla_historial FOR EACH ROW EXECUTE FUNCTION hom_inmutable();
CREATE TRIGGER tr_hom_revision_inmutable BEFORE UPDATE OR DELETE ON hom_revision FOR EACH ROW EXECUTE FUNCTION hom_inmutable();

-- Versión de la tabla maestra vigente a una fecha de corte (los tableros de A3 resuelven el dato final con ella por defecto)
CREATE FUNCTION hom_version_vigente(corte timestamptz) RETURNS integer
    LANGUAGE sql STABLE AS $$
    SELECT id FROM hom_version WHERE estado = 'publicada' AND creada <= corte ORDER BY numero DESC LIMIT 1
$$;
COMMENT ON FUNCTION hom_version_vigente(timestamptz) IS 'Versión publicada de la tabla maestra vigente a la fecha de corte (nula si aún no hay)';

-- Estado final con la última decisión humana (la misma regla de reglas/AnaliticaHomologacion.estadoFinal)
CREATE FUNCTION hom_estado_final(reglas text, accion text) RETURNS text
    LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN accion = 'aprobar' THEN 'homologado' WHEN accion = 'devolver' AND reglas <> 'homologado' THEN 'pendiente' ELSE reglas END
$$;
COMMENT ON FUNCTION hom_estado_final(text, text) IS 'Estado final: aprobar ⇒ homologado; devolver ⇒ pendiente si las reglas no lo homologaron; sin decisión, el de las reglas';

-- Versión N completa de la tabla maestra: por dato original, la fila calculada más reciente de las versiones publicadas con número ≤ N
-- y, encima, la última decisión de Revisión con número ≤ N (aprobar: dato elegido, homologado, MANUAL; devolver: pendiente · devuelto
-- al OPL, si las reglas no lo homologaron). La causa no copia el motivo libre: remite a la revisión. Una consulta con una versión o un
-- corte anterior a la decisión no la ve. Función SQL sin CTE para que el planificador la expanda (un filtro por origen_id baja al índice).
CREATE FUNCTION hom_tabla_maestra_version(v integer) RETURNS SETOF hom_tabla_maestra
    LANGUAGE sql STABLE AS $$
    SELECT v, b.origen_id, b.orig_cum, b.orig_descripcion, b.orig_principio, b.orig_concentracion, b.orig_forma, b.orig_presentacion,
           b.orig_unidad, b.orig_ssfm, b.orig_atc,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'fuenteCodigo' ELSE b.fuente_codigo END,
           CASE WHEN d.accion = 'aprobar' THEN (d.dato ->> 'fuenteVersionId')::integer ELSE b.fuente_version_id END,
           CASE WHEN d.accion = 'aprobar' THEN (d.dato ->> 'fuenteFechaCorte')::date ELSE b.fuente_fecha_corte END,
           CASE WHEN d.id IS NULL THEN b.regla ELSE 'MANUAL' END,
           CASE WHEN d.accion = 'aprobar' THEN 'aprobado en revisión · revisión ' || d.id
                WHEN d.accion = 'devolver' THEN 'devuelto al OPL · revisión ' || d.id ELSE b.causa END,
           CASE WHEN d.id IS NULL THEN b.reglas_aplicadas
                ELSE b.reglas_aplicadas || jsonb_build_array(jsonb_build_object('regla', 'MANUAL', 'antes', b.estado || ' · ' || b.regla,
                     'despues', CASE WHEN d.accion = 'aprobar' THEN 'homologado' ELSE 'pendiente · devuelto al OPL' END)) END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'nombreComercial' ELSE b.fin_nombre_comercial END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'dci' ELSE b.fin_dci END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'principioActivo' ELSE b.fin_principio_activo END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'concentracion' ELSE b.fin_concentracion END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'forma' ELSE b.fin_forma END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'presentacion' ELSE b.fin_presentacion END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'unidad' ELSE b.fin_unidad END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'cum' ELSE b.fin_cum END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'codigoSsfm' ELSE b.fin_codigo_ssfm END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'atc' ELSE b.fin_atc END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'grupoFarmacologico' ELSE b.fin_grupo_farmacologico END,
           CASE WHEN d.accion = 'aprobar' THEN d.dato ->> 'registroSanitario' ELSE b.fin_registro_sanitario END,
           hom_estado_final(b.estado, d.accion),
           CASE WHEN d.id IS NULL THEN b.diferencias ELSE '{}'::text[] END,
           b.candidatos,
           coalesce(d.accion = 'devolver', false),
           d.id,
           b.huella
    FROM (SELECT DISTINCT ON (t.origen_id) t.*
          FROM hom_tabla_maestra t JOIN hom_version hv ON hv.id = t.version_id
          WHERE hv.numero <= (SELECT x.numero FROM hom_version x WHERE x.id = v AND x.estado = 'publicada')
          ORDER BY t.origen_id, hv.numero DESC) b
    LEFT JOIN LATERAL (SELECT r.id, r.accion, r.dato FROM hom_revision r JOIN hom_version rv ON rv.id = r.version_id
                       WHERE r.origen_id = b.origen_id AND rv.numero <= (SELECT x.numero FROM hom_version x WHERE x.id = v)
                       ORDER BY rv.numero DESC, r.id DESC LIMIT 1) d
           ON d.accion = 'aprobar' OR d.accion = 'devolver' AND b.estado <> 'homologado'
$$;
COMMENT ON FUNCTION hom_tabla_maestra_version(integer) IS 'Versión completa de la tabla maestra (filas calculadas por diferencia + capa de decisiones de Revisión); vacía si la versión no está publicada';

-- Versión N completa de la CIE-10 (las decisiones humanas no la tocan)
CREATE FUNCTION hom_tabla_cie10_version(v integer) RETURNS SETOF hom_tabla_cie10
    LANGUAGE sql STABLE AS $$
    SELECT DISTINCT ON (t.origen_id) v, t.origen_id, t.codigo_final, t.estado, t.regla, t.causa, t.fuente_version_id
    FROM hom_tabla_cie10 t JOIN hom_version hv ON hv.id = t.version_id
    WHERE hv.numero <= (SELECT x.numero FROM hom_version x WHERE x.id = v AND x.estado = 'publicada')
    ORDER BY t.origen_id, hv.numero DESC
$$;
COMMENT ON FUNCTION hom_tabla_cie10_version(integer) IS 'Versión completa de la CIE-10 homologada (filas por diferencia)';

-- ---------- Hechos y cargues (V60) ----------
-- Dato original y versión de la tabla maestra de cada hecho: columnas anulables sin valor por defecto (en una tabla particionada,
-- ADD COLUMN sin DEFAULT no reescribe las particiones). Sin índices: ver hom_cargue_origen.
ALTER TABLE ana_dispensaciones ADD COLUMN medicamento_id bigint, ADD COLUMN hom_version_id integer;
COMMENT ON COLUMN ana_dispensaciones.medicamento_id IS 'Dato original homologado (hom_medicamento_origen); el dato final sale de la tabla maestra de la versión elegida';
COMMENT ON COLUMN ana_dispensaciones.hom_version_id IS 'Versión de la tabla maestra vigente al aplicar el cargue (una re-ejecución no la cambia)';
-- Nueva etapa del cargue: la homologación de los datos originales nuevos corre antes de cargar los hechos
ALTER TABLE ana_cargues DROP CONSTRAINT ana_cargues_etapa_check;
ALTER TABLE ana_cargues ADD CONSTRAINT ana_cargues_etapa_check CHECK (etapa IN ('subida', 'lectura', 'validacion', 'homologacion', 'hechos', 'agregados'));
COMMENT ON COLUMN ana_cargues.etapas_ms IS 'Duración por etapa en milisegundos (subida, lectura, validacion, homologacion, hechos, agregados)';
-- Causa nueva del catálogo cerrado (reglas/AnaliticaIngesta.Causa.TEXTO_LARGO): medicamento, CUM o principio de más de 1000 caracteres
ALTER TABLE ana_cargue_rechazos DROP CONSTRAINT ana_cargue_rechazos_causa_check;
ALTER TABLE ana_cargue_rechazos ADD CONSTRAINT ana_cargue_rechazos_causa_check CHECK (causa IN ('obligatorio_vacio', 'fecha_invalida',
    'fecha_fuera_periodo', 'numero_invalido', 'cantidad_no_positiva', 'valor_negativo', 'cum_invalido', 'fuerza_no_reconocida',
    'modalidad_no_reconocida', 'duplicado_exacto', 'duplicado_llave', 'ya_cargado', 'campos_distintos', 'texto_largo', 'archivo_vacio',
    'codificacion', 'encabezados_repetidos', 'hojas_distintas', 'formato'));

-- ---------- Reglas y catálogos iniciales (los mismos de reglas/AnaliticaHomologacion: Configuracion.porDefecto) ----------
INSERT INTO hom_regla (id, nombre, orden, activa, obligatoria, parametros) VALUES
    ('R-002', 'Formato del CUM', 10, true, true, '{}'),
    ('R-001', 'CUM exacto en la versión publicada', 20, true, true, '{}'),
    ('R-021', 'CUM vencido, en otros estados o inactivo', 30, true, false, '{}'),
    ('R-003', 'Registro sanitario y su vigencia a la fecha de dispensación', 40, true, false, '{}'),
    ('R-010', 'Texto normalizado y abreviaturas', 50, true, false, '{}'),
    ('R-011', 'Unidades y conversión g ↔ mg ↔ mcg', 60, true, false, '{}'),
    ('R-004', 'Sinónimos DCI', 70, true, false, '{}'),
    ('R-013', 'Principio activo con sales toleradas', 80, true, false, '{}'),
    ('R-012', 'Concentración', 90, true, false, '{"toleranciaPorcentaje": "0"}'),
    ('R-015', 'Forma farmacéutica con equivalencias y tolerancia', 100, true, false, '{"tolerancia": "0"}'),
    ('R-016', 'Presentación (cantidad del empaque)', 110, true, false, '{}'),
    ('R-041', 'Corrección automática de incompletos', 120, true, false, '{}'),
    ('R-030', 'Código interno SSFM', 130, true, false, '{}'),
    ('R-040', 'Duplicados', 140, true, false, '{}'),
    ('R-050', 'Sin correspondencia', 150, true, true, '{}'),
    ('R-060', 'CIE-10', 160, true, false, '{}');

INSERT INTO hom_catalogo (tipo, version, clave, valor, extra) VALUES
    ('sinonimo_dci', 1, 'ACETAMINOFEN', 'PARACETAMOL', NULL),
    ('sinonimo_dci', 1, 'ACETAMINOFENO', 'PARACETAMOL', NULL),
    ('sinonimo_dci', 1, 'ACIDO ACETIL SALICILICO', 'ACIDO ACETILSALICILICO', NULL),
    ('sinonimo_dci', 1, 'ADRENALINA', 'EPINEFRINA', NULL),
    ('sinonimo_dci', 1, 'ALBUTEROL', 'SALBUTAMOL', NULL),
    ('sinonimo_dci', 1, 'ASA', 'ACIDO ACETILSALICILICO', NULL),
    ('sinonimo_dci', 1, 'DIPIRONA', 'METAMIZOL', NULL),
    ('sinonimo_dci', 1, 'NORADRENALINA', 'NOREPINEFRINA', NULL),
    ('sinonimo_dci', 1, 'PETIDINA', 'MEPERIDINA', NULL),
    ('sinonimo_dci', 1, 'XILOCAINA', 'LIDOCAINA', NULL),
    ('sal', 1, 'ACETATO', 'ACETATO', NULL),
    ('sal', 1, 'ANHIDRA', 'ANHIDRA', NULL),
    ('sal', 1, 'ANHIDRO', 'ANHIDRO', NULL),
    ('sal', 1, 'BASE', 'BASE', NULL),
    ('sal', 1, 'BESILATO', 'BESILATO', NULL),
    ('sal', 1, 'BITARTRATO', 'BITARTRATO', NULL),
    ('sal', 1, 'BROMURO', 'BROMURO', NULL),
    ('sal', 1, 'CALCICA', 'CALCICA', NULL),
    ('sal', 1, 'CALCICO', 'CALCICO', NULL),
    ('sal', 1, 'CITRATO', 'CITRATO', NULL),
    ('sal', 1, 'CLORHIDRATO', 'CLORHIDRATO', NULL),
    ('sal', 1, 'CLORURO', 'CLORURO', NULL),
    ('sal', 1, 'DIHIDRATO', 'DIHIDRATO', NULL),
    ('sal', 1, 'DIPROPIONATO', 'DIPROPIONATO', NULL),
    ('sal', 1, 'DISODICA', 'DISODICA', NULL),
    ('sal', 1, 'DISODICO', 'DISODICO', NULL),
    ('sal', 1, 'ESTOLATO', 'ESTOLATO', NULL),
    ('sal', 1, 'FOSFATO', 'FOSFATO', NULL),
    ('sal', 1, 'FUMARATO', 'FUMARATO', NULL),
    ('sal', 1, 'FUROATO', 'FUROATO', NULL),
    ('sal', 1, 'GLUCONATO', 'GLUCONATO', NULL),
    ('sal', 1, 'HCL', 'HCL', NULL),
    ('sal', 1, 'HEMIHIDRATO', 'HEMIHIDRATO', NULL),
    ('sal', 1, 'HIDROCLORURO', 'HIDROCLORURO', NULL),
    ('sal', 1, 'LACTATO', 'LACTATO', NULL),
    ('sal', 1, 'MAGNESICA', 'MAGNESICA', NULL),
    ('sal', 1, 'MAGNESICO', 'MAGNESICO', NULL),
    ('sal', 1, 'MALEATO', 'MALEATO', NULL),
    ('sal', 1, 'MESILATO', 'MESILATO', NULL),
    ('sal', 1, 'MONOHIDRATO', 'MONOHIDRATO', NULL),
    ('sal', 1, 'NITRATO', 'NITRATO', NULL),
    ('sal', 1, 'OXALATO', 'OXALATO', NULL),
    ('sal', 1, 'PAMOATO', 'PAMOATO', NULL),
    ('sal', 1, 'POTASICA', 'POTASICA', NULL),
    ('sal', 1, 'POTASICO', 'POTASICO', NULL),
    ('sal', 1, 'PROPIONATO', 'PROPIONATO', NULL),
    ('sal', 1, 'SESQUIHIDRATO', 'SESQUIHIDRATO', NULL),
    ('sal', 1, 'SODICA', 'SODICA', NULL),
    ('sal', 1, 'SODICO', 'SODICO', NULL),
    ('sal', 1, 'SUCCINATO', 'SUCCINATO', NULL),
    ('sal', 1, 'SULFATO', 'SULFATO', NULL),
    ('sal', 1, 'TARTRATO', 'TARTRATO', NULL),
    ('sal', 1, 'TRIHIDRATO', 'TRIHIDRATO', NULL),
    ('sal', 1, 'TROMETAMOL', 'TROMETAMOL', NULL),
    ('sal', 1, 'VALERATO', 'VALERATO', NULL),
    ('sal', 1, 'XINAFOATO', 'XINAFOATO', NULL),
    ('abreviatura', 1, 'AMP', 'AMPOLLA', NULL),
    ('abreviatura', 1, 'AMPOLLAS', 'AMPOLLA', NULL),
    ('abreviatura', 1, 'CAP', 'CAPSULA', NULL),
    ('abreviatura', 1, 'CAPS', 'CAPSULA', NULL),
    ('abreviatura', 1, 'CAPSULAS', 'CAPSULA', NULL),
    ('abreviatura', 1, 'CR', 'CREMA', NULL),
    ('abreviatura', 1, 'FCO', 'FRASCO', NULL),
    ('abreviatura', 1, 'GTS', 'GOTAS', NULL),
    ('abreviatura', 1, 'INH', 'INHALADOR', NULL),
    ('abreviatura', 1, 'INY', 'INYECTABLE', NULL),
    ('abreviatura', 1, 'JBE', 'JARABE', NULL),
    ('abreviatura', 1, 'JER', 'JERINGA', NULL),
    ('abreviatura', 1, 'JERINGAS', 'JERINGA', NULL),
    ('abreviatura', 1, 'LIB', 'LIBERACION', NULL),
    ('abreviatura', 1, 'OVU', 'OVULO', NULL),
    ('abreviatura', 1, 'PLUMAS', 'PLUMA', NULL),
    ('abreviatura', 1, 'PLV', 'POLVO', NULL),
    ('abreviatura', 1, 'PROL', 'PROLONGADA', NULL),
    ('abreviatura', 1, 'REC', 'RECUBIERTA', NULL),
    ('abreviatura', 1, 'RECUB', 'RECUBIERTA', NULL),
    ('abreviatura', 1, 'SOL', 'SOLUCION', NULL),
    ('abreviatura', 1, 'SUP', 'SUPOSITORIO', NULL),
    ('abreviatura', 1, 'SUSP', 'SUSPENSION', NULL),
    ('abreviatura', 1, 'TAB', 'TABLETA', NULL),
    ('abreviatura', 1, 'TABLETAS', 'TABLETA', NULL),
    ('abreviatura', 1, 'TABS', 'TABLETA', NULL),
    ('abreviatura', 1, 'UNG', 'UNGUENTO', NULL),
    ('abreviatura', 1, 'VIALES', 'VIAL', NULL),
    ('unidad', 1, '%', '%', NULL),
    ('unidad', 1, 'CC', 'ML', NULL),
    ('unidad', 1, 'G', 'G', NULL),
    ('unidad', 1, 'GR', 'G', NULL),
    ('unidad', 1, 'GRAMO', 'G', NULL),
    ('unidad', 1, 'GRAMOS', 'G', NULL),
    ('unidad', 1, 'GRS', 'G', NULL),
    ('unidad', 1, 'MCG', 'MCG', NULL),
    ('unidad', 1, 'MEQ', 'MEQ', NULL),
    ('unidad', 1, 'MG', 'MG', NULL),
    ('unidad', 1, 'MGS', 'MG', NULL),
    ('unidad', 1, 'MICROGRAMO', 'MCG', NULL),
    ('unidad', 1, 'MICROGRAMOS', 'MCG', NULL),
    ('unidad', 1, 'MILIGRAMO', 'MG', NULL),
    ('unidad', 1, 'MILIGRAMOS', 'MG', NULL),
    ('unidad', 1, 'MILILITRO', 'ML', NULL),
    ('unidad', 1, 'MILILITROS', 'ML', NULL),
    ('unidad', 1, 'ML', 'ML', NULL),
    ('unidad', 1, 'U', 'UI', NULL),
    ('unidad', 1, 'UG', 'MCG', NULL),
    ('unidad', 1, 'UI', 'UI', NULL),
    ('unidad', 1, 'UNIDAD INTERNACIONAL', 'UI', NULL),
    ('unidad', 1, 'UNIDADES', 'UI', NULL),
    ('forma', 1, 'AEROSOL PARA INHALACION', 'AEROSOL PARA INHALACION', 'INHALATORIA'),
    ('forma', 1, 'AMPOLLA', 'SOLUCION INYECTABLE', 'PARENTERAL'),
    ('forma', 1, 'CAPSULA', 'CAPSULA', 'SOLIDA ORAL'),
    ('forma', 1, 'CAPSULA BLANDA', 'CAPSULA BLANDA', 'SOLIDA ORAL'),
    ('forma', 1, 'CAPSULA DURA', 'CAPSULA', 'SOLIDA ORAL'),
    ('forma', 1, 'CREMA', 'CREMA', 'TOPICA'),
    ('forma', 1, 'GEL', 'GEL', 'TOPICA'),
    ('forma', 1, 'GOTAS', 'SOLUCION ORAL', 'LIQUIDA ORAL'),
    ('forma', 1, 'GRAGEA', 'TABLETA RECUBIERTA', 'SOLIDA ORAL'),
    ('forma', 1, 'INHALADOR', 'AEROSOL PARA INHALACION', 'INHALATORIA'),
    ('forma', 1, 'INYECTABLE', 'SOLUCION INYECTABLE', 'PARENTERAL'),
    ('forma', 1, 'JARABE', 'JARABE', 'LIQUIDA ORAL'),
    ('forma', 1, 'JERINGA', 'SOLUCION INYECTABLE', 'PARENTERAL'),
    ('forma', 1, 'JERINGA PRELLENADA', 'SOLUCION INYECTABLE', 'PARENTERAL'),
    ('forma', 1, 'OVULO', 'OVULO', 'VAGINAL'),
    ('forma', 1, 'PARCHE', 'PARCHE TRANSDERMICO', 'TRANSDERMICA'),
    ('forma', 1, 'PLUMA', 'SOLUCION INYECTABLE', 'PARENTERAL'),
    ('forma', 1, 'POLVO LIOFILIZADO PARA RECONSTITUIR A SOLUCION INYECTABLE', 'POLVO PARA SOLUCION INYECTABLE', 'PARENTERAL'),
    ('forma', 1, 'POLVO PARA INHALACION', 'POLVO PARA INHALACION', 'INHALATORIA'),
    ('forma', 1, 'POLVO PARA SOLUCION INYECTABLE', 'POLVO PARA SOLUCION INYECTABLE', 'PARENTERAL'),
    ('forma', 1, 'SOLUCION INYECTABLE', 'SOLUCION INYECTABLE', 'PARENTERAL'),
    ('forma', 1, 'SOLUCION ORAL', 'SOLUCION ORAL', 'LIQUIDA ORAL'),
    ('forma', 1, 'SUPOSITORIO', 'SUPOSITORIO', 'RECTAL'),
    ('forma', 1, 'SUSPENSION', 'SUSPENSION ORAL', 'LIQUIDA ORAL'),
    ('forma', 1, 'SUSPENSION INYECTABLE', 'SUSPENSION INYECTABLE', 'PARENTERAL'),
    ('forma', 1, 'SUSPENSION ORAL', 'SUSPENSION ORAL', 'LIQUIDA ORAL'),
    ('forma', 1, 'SUSPENSION PARA INHALACION', 'AEROSOL PARA INHALACION', 'INHALATORIA'),
    ('forma', 1, 'TABLETA', 'TABLETA', 'SOLIDA ORAL'),
    ('forma', 1, 'TABLETA CON CUBIERTA PELICULAR', 'TABLETA RECUBIERTA', 'SOLIDA ORAL'),
    ('forma', 1, 'TABLETA CUBIERTA CON PELICULA', 'TABLETA RECUBIERTA', 'SOLIDA ORAL'),
    ('forma', 1, 'TABLETA DE LIBERACION PROLONGADA', 'TABLETA DE LIBERACION PROLONGADA', 'SOLIDA ORAL'),
    ('forma', 1, 'TABLETA MASTICABLE', 'TABLETA MASTICABLE', 'SOLIDA ORAL'),
    ('forma', 1, 'TABLETA RECUBIERTA', 'TABLETA RECUBIERTA', 'SOLIDA ORAL'),
    ('forma', 1, 'UNGUENTO', 'UNGUENTO', 'TOPICA'),
    ('forma', 1, 'VIAL', 'POLVO PARA SOLUCION INYECTABLE', 'PARENTERAL');

-- Privilegios mínimos del rol de la aplicación, sentencia por sentencia del código (IngestaPrivilegiosTest los verifica):
--   ref_fuente, ref_atc_nombre_nivel                 SELECT (sembradas aquí)
--   ref_version                                      SELECT, INSERT, UPDATE (estado; el disparador protege las publicadas)
--   ref_cum_staging                                  SELECT, INSERT, DELETE (se vacía al terminar cada versión)
--   ref_cum_*, ref_cie10, ref_precio_regulado, ref_vital_no_disponible, ref_lista_normativa, ref_codigo_ssfm
--                                                    SELECT, INSERT (nunca UPDATE ni DELETE: las versiones no se modifican)
--   hom_regla                                        SELECT, UPDATE (parámetros, orden y activa; versión + 1)
--   hom_regla_historial, hom_catalogo, hom_revision  SELECT, INSERT
--   hom_version                                      SELECT, INSERT, UPDATE, DELETE (solo la pendiente de un cargue: publicarla o
--                                                    borrarla si el cargue no se aplica; el disparador protege las publicadas)
--   hom_tabla_maestra, hom_tabla_cie10               SELECT, INSERT, DELETE (solo filas de una versión pendiente; disparador)
--   hom_cargue_origen, hom_cargue_mes                SELECT, INSERT, DELETE (las de un cargue que no se aplicó)
--   hom_medicamento_origen, hom_cie10_origen         SELECT, INSERT, UPDATE, DELETE (confirmar o borrar los pendientes de un cargue)
--   hom_devolucion                                   SELECT, INSERT, UPDATE (seguimiento: corregida, persiste o resuelta)
--   ana_dispensaciones y ana_cargues                 sin cambios (V60)
--   ref_atc_v                                        SELECT; funciones hom_* EXECUTE; secuencias USAGE
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT ON ref_fuente, ref_atc_nombre_nivel, ref_atc_v TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON ref_version, hom_devolucion TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON hom_version, hom_medicamento_origen, hom_cie10_origen TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON ref_cum_staging, hom_tabla_maestra, hom_tabla_cie10, hom_cargue_origen, hom_cargue_mes TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT ON ref_cum_presentacion, ref_cum_principio_activo, ref_cum_rol, ref_cie10, ref_precio_regulado, '
                   'ref_vital_no_disponible, ref_lista_normativa, ref_codigo_ssfm, hom_regla_historial, hom_catalogo, hom_revision TO %I', app);
    EXECUTE format('GRANT SELECT, UPDATE ON hom_regla TO %I', app);
    EXECUTE format('GRANT EXECUTE ON FUNCTION hom_version_vigente(timestamptz), hom_estado_final(text, text), hom_tabla_maestra_version(integer), '
                   'hom_tabla_cie10_version(integer) TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
