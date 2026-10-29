-- Suite DIGSA · Fase AN: analítica farmacéutica, A4 · cohortes y alertas tempranas (docs/especificaciones/analitica-cohortes-alertas).
-- Cohortes persistentes, parametrizables y versionadas (A5 14.6; RESP L5143): la definición (árbol de criterios en jsonb, reglas puras en
-- reglas/AnaliticaCohortes) y, por cada corte, su serie mensual AGREGADA por nivel (nacional, Fuerza, ESM) con pacientes, ingresos, salidas,
-- dispensaciones, unidades y gasto. La membresía (número de paciente por mes) solo existe para el último corte de cada versión y nunca sale
-- de la base salvo como conteos. Motor de alertas tempranas (A5 14.7, A4 §11.1 d, PLIEGO P4): parámetros por tipo con su historial (antes y
-- después), alertas con contexto obligatorio (NOT NULL) y análisis técnico, validación humana con historial, ejecuciones del motor (con los
-- tipos «no evaluado: motivo») y referencias cargables por archivo (interacciones, indicaciones, enfermedades huérfanas; datos de ejemplo
-- marcados como ficticios). Las cohortes predefinidas y los parámetros por defecto los siembra la aplicación al arrancar desde las reglas
-- puras (una sola fuente); aquí van las 12 cohortes del A4 y el tipo de aviso «analitica_alerta_alta» (operativo: solo correo).

-- ---------- Cohortes ----------
CREATE TABLE ana_cohortes (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo      text NOT NULL UNIQUE CHECK (codigo ~ '^[a-z_]{3,40}$'),
    nombre      text NOT NULL CHECK (char_length(nombre) BETWEEN 3 AND 120),
    descripcion text CHECK (char_length(descripcion) <= 500),
    predefinida boolean NOT NULL DEFAULT false,
    activa      boolean NOT NULL DEFAULT true,
    orden       smallint NOT NULL DEFAULT 100,
    creada      timestamptz NOT NULL DEFAULT now(),
    creada_por  bigint REFERENCES usuarios (id) ON DELETE RESTRICT
);
COMMENT ON TABLE ana_cohortes IS 'Cohortes de la analítica (A5 14.6): objeto persistente; las predefinidas del A4 no se eliminan (solo se editan sus parámetros)';
COMMENT ON COLUMN ana_cohortes.codigo IS 'Código estable (alto_costo, polimedicados…)';
COMMENT ON COLUMN ana_cohortes.nombre IS 'Nombre visible';
COMMENT ON COLUMN ana_cohortes.descripcion IS 'Descripción';
COMMENT ON COLUMN ana_cohortes.predefinida IS 'Una de las del A4 §11.1 e y 11.2.4 (no se elimina)';
COMMENT ON COLUMN ana_cohortes.activa IS 'Se recalcula con cada cargue y sale en las tarjetas';
COMMENT ON COLUMN ana_cohortes.orden IS 'Orden de las tarjetas (las seis del mockup primero)';
COMMENT ON COLUMN ana_cohortes.creada IS 'Fecha de creación';
COMMENT ON COLUMN ana_cohortes.creada_por IS 'Quién la creó (nulo: predefinida)';
CREATE INDEX ix_ana_cohortes_creada_por ON ana_cohortes (creada_por) WHERE creada_por IS NOT NULL;

CREATE TABLE ana_cohortes_versiones (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cohorte_id     bigint NOT NULL REFERENCES ana_cohortes (id) ON DELETE RESTRICT,
    version        integer NOT NULL CHECK (version >= 1),
    criterios      jsonb NOT NULL CHECK (jsonb_typeof(criterios) = 'object' AND octet_length(criterios::text) <= 32000),
    criterio_texto text NOT NULL CHECK (char_length(criterio_texto) BETWEEN 1 AND 2000),
    vigente        boolean NOT NULL DEFAULT true,
    creada         timestamptz NOT NULL DEFAULT now(),
    creada_por     bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    UNIQUE (cohorte_id, version)
);
COMMENT ON TABLE ana_cohortes_versiones IS 'Versiones de los criterios de una cohorte: editar crea una versión nueva; la anterior queda con sus resultados por corte';
COMMENT ON COLUMN ana_cohortes_versiones.cohorte_id IS 'Cohorte';
COMMENT ON COLUMN ana_cohortes_versiones.version IS 'Número de versión (1, 2…)';
COMMENT ON COLUMN ana_cohortes_versiones.criterios IS 'Árbol de criterios (reglas/AnaliticaCohortes: grupo Y/O, códigos con ventana, patrones, partición)';
COMMENT ON COLUMN ana_cohortes_versiones.criterio_texto IS 'Criterio en texto legible (el de la tarjeta)';
COMMENT ON COLUMN ana_cohortes_versiones.vigente IS 'Versión vigente (una por cohorte)';
COMMENT ON COLUMN ana_cohortes_versiones.creada IS 'Fecha de la versión';
COMMENT ON COLUMN ana_cohortes_versiones.creada_por IS 'Quién la creó (nulo: siembra)';
CREATE UNIQUE INDEX uq_ana_cohortes_versiones_vigente ON ana_cohortes_versiones (cohorte_id) WHERE vigente;
CREATE INDEX ix_ana_cohortes_versiones_creada_por ON ana_cohortes_versiones (creada_por) WHERE creada_por IS NOT NULL;

CREATE TABLE ana_cohortes_ejecuciones (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    origen          text NOT NULL CHECK (origen IN ('cargue', 'usuario', 'version', 'reinicio')),
    corte_cargue_id bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    usuario_id      bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    inicio          timestamptz NOT NULL,
    fin             timestamptz,
    estado          text NOT NULL DEFAULT 'en_curso' CHECK (estado IN ('en_curso', 'ok', 'con_fallos', 'error', 'interrumpida')),
    versiones       integer NOT NULL DEFAULT 0 CHECK (versiones >= 0),
    filas           bigint CHECK (filas >= 0),
    duracion_ms     bigint CHECK (duracion_ms >= 0),
    etapas_ms       jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(etapas_ms) = 'object'),
    error           text CHECK (char_length(error) <= 500),
    CHECK ((estado = 'en_curso') = (fin IS NULL))
);
COMMENT ON TABLE ana_cohortes_ejecuciones IS 'Recálculos de cohortes (al aplicar cada cargue, bajo demanda o al crear una versión) con duración y etapas';
COMMENT ON COLUMN ana_cohortes_ejecuciones.origen IS 'cargue (automático al aplicar), usuario (bajo demanda), version (nueva versión) o reinicio (re-encolada al arrancar)';
COMMENT ON COLUMN ana_cohortes_ejecuciones.corte_cargue_id IS 'Cargue que fija el corte calculado';
COMMENT ON COLUMN ana_cohortes_ejecuciones.usuario_id IS 'Quién lo pidió (nulo: automático)';
COMMENT ON COLUMN ana_cohortes_ejecuciones.inicio IS 'Inicio';
COMMENT ON COLUMN ana_cohortes_ejecuciones.fin IS 'Fin';
COMMENT ON COLUMN ana_cohortes_ejecuciones.estado IS 'en_curso, ok, con_fallos (alguna versión no evaluada por error), error o interrumpida (reinicio de la aplicación)';
COMMENT ON COLUMN ana_cohortes_ejecuciones.versiones IS 'Versiones calculadas';
COMMENT ON COLUMN ana_cohortes_ejecuciones.filas IS 'Hechos leídos del corte';
COMMENT ON COLUMN ana_cohortes_ejecuciones.duracion_ms IS 'Duración total en milisegundos (cohortes y alertas)';
COMMENT ON COLUMN ana_cohortes_ejecuciones.etapas_ms IS 'Duración por etapa (umbrales, recorrido, escritura, alertas)';
COMMENT ON COLUMN ana_cohortes_ejecuciones.error IS 'Error técnico resumido (sin datos personales)';
CREATE INDEX ix_ana_cohortes_ejecuciones_corte ON ana_cohortes_ejecuciones (corte_cargue_id);
CREATE INDEX ix_ana_cohortes_ejecuciones_en_curso ON ana_cohortes_ejecuciones (corte_cargue_id) WHERE fin IS NULL;
CREATE INDEX ix_ana_cohortes_ejecuciones_usuario ON ana_cohortes_ejecuciones (usuario_id) WHERE usuario_id IS NOT NULL;

CREATE TABLE ana_cohortes_resultados (
    version_id      bigint NOT NULL REFERENCES ana_cohortes_versiones (id) ON DELETE RESTRICT,
    corte_cargue_id bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    ejecucion_id    bigint NOT NULL REFERENCES ana_cohortes_ejecuciones (id) ON DELETE RESTRICT,
    estado          text NOT NULL CHECK (estado IN ('evaluada', 'no_evaluada')),
    motivo          text CHECK (char_length(motivo) <= 300),
    umbrales        jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(umbrales) = 'object'),
    fuentes         text CHECK (char_length(fuentes) <= 500),
    miembros        boolean NOT NULL DEFAULT true,
    calculado       timestamptz NOT NULL,
    PRIMARY KEY (version_id, corte_cargue_id),
    CHECK ((estado = 'no_evaluada') = (motivo IS NOT NULL))
);
COMMENT ON TABLE ana_cohortes_resultados IS 'Resultado de una versión de cohorte en un corte: evaluada o «no evaluada: motivo» (falta su lista de referencia)';
COMMENT ON COLUMN ana_cohortes_resultados.version_id IS 'Versión de la cohorte';
COMMENT ON COLUMN ana_cohortes_resultados.corte_cargue_id IS 'Corte';
COMMENT ON COLUMN ana_cohortes_resultados.ejecucion_id IS 'Ejecución que lo calculó';
COMMENT ON COLUMN ana_cohortes_resultados.estado IS 'evaluada o no_evaluada';
COMMENT ON COLUMN ana_cohortes_resultados.motivo IS 'Motivo de «no evaluado» (p. ej. sin lista de huérfanas cargada)';
COMMENT ON COLUMN ana_cohortes_resultados.umbrales IS 'Umbral del gasto móvil por mes (alto costo, en pesos) con que se calculó';
COMMENT ON COLUMN ana_cohortes_resultados.fuentes IS 'Listas de referencia usadas con su versión';
COMMENT ON COLUMN ana_cohortes_resultados.miembros IS 'La membresía de este corte se conserva (los últimos cortes de la versión; los anteriores la purgan y sus tarjetas usan la serie)';
COMMENT ON COLUMN ana_cohortes_resultados.calculado IS 'Cuándo se calculó';
CREATE INDEX ix_ana_cohortes_resultados_corte ON ana_cohortes_resultados (corte_cargue_id);
CREATE INDEX ix_ana_cohortes_resultados_ejecucion ON ana_cohortes_resultados (ejecucion_id);

-- Serie agregada por corte: reproducible (el mismo corte y versión dan siempre las mismas cifras, aunque lleguen otros cargues). Los
-- pacientes son distintos en cada celda (nunca la suma de las de otro nivel). Grupo: el de la partición (GCF, GRC); vacío en las demás.
CREATE TABLE ana_cohortes_serie (
    version_id         bigint NOT NULL REFERENCES ana_cohortes_versiones (id) ON DELETE RESTRICT,
    corte_cargue_id    bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    mes                date NOT NULL CHECK (extract(day FROM mes) = 1),
    nivel              text NOT NULL CHECK (nivel IN ('nacional', 'fuerza', 'esm')),
    fuerza             text COLLATE "C" NOT NULL DEFAULT '',
    codigo             text COLLATE "C" NOT NULL DEFAULT '',
    grupo              text COLLATE "C" NOT NULL DEFAULT '',
    pacientes          bigint NOT NULL CHECK (pacientes >= 0),
    ingresos           bigint NOT NULL CHECK (ingresos >= 0),
    salidas            bigint NOT NULL CHECK (salidas >= 0),
    dispensaciones     bigint NOT NULL CHECK (dispensaciones >= 0),
    unidades_milesimas bigint NOT NULL CHECK (unidades_milesimas >= 0),
    gasto_centavos     bigint NOT NULL CHECK (gasto_centavos >= 0),
    PRIMARY KEY (version_id, corte_cargue_id, nivel, fuerza, codigo, grupo, mes),
    CHECK ((nivel = 'esm') = (fuerza <> ''))
);
COMMENT ON TABLE ana_cohortes_serie IS 'Serie mensual agregada de cada versión de cohorte por corte y nivel (sin datos personales)';
COMMENT ON COLUMN ana_cohortes_serie.version_id IS 'Versión de la cohorte';
COMMENT ON COLUMN ana_cohortes_serie.corte_cargue_id IS 'Corte';
COMMENT ON COLUMN ana_cohortes_serie.mes IS 'Mes (primer día)';
COMMENT ON COLUMN ana_cohortes_serie.nivel IS 'nacional, fuerza o esm';
COMMENT ON COLUMN ana_cohortes_serie.fuerza IS 'Fuerza de las dispensaciones de la celda ESM (un ESM compartido por dos Fuerzas tiene una celda por Fuerza: alcance de Sanidad); vacía en nacional y fuerza';
COMMENT ON COLUMN ana_cohortes_serie.codigo IS 'Código de la Fuerza o del ESM (vacío en nacional)';
COMMENT ON COLUMN ana_cohortes_serie.grupo IS 'Grupo de la partición (GCF: ATC nivel 2; GRC: GRC1–GRC4); vacío en las demás cohortes';
COMMENT ON COLUMN ana_cohortes_serie.pacientes IS 'Pacientes distintos miembros en el mes y el nivel';
COMMENT ON COLUMN ana_cohortes_serie.ingresos IS 'Miembros del mes sin membresía en la celda en los meses de gracia previos; 0 en los meses de calentamiento (historia insuficiente)';
COMMENT ON COLUMN ana_cohortes_serie.salidas IS 'Miembros cuya membresía en la celda terminó (no vuelven dentro de los meses de gracia), contados en el mes siguiente al último';
COMMENT ON COLUMN ana_cohortes_serie.dispensaciones IS 'Dispensaciones de los miembros en el mes y el nivel';
COMMENT ON COLUMN ana_cohortes_serie.unidades_milesimas IS 'Unidades dispensadas (milésimas)';
COMMENT ON COLUMN ana_cohortes_serie.gasto_centavos IS 'Gasto (centavos)';
CREATE INDEX ix_ana_cohortes_serie_corte ON ana_cohortes_serie (corte_cargue_id);

-- Membresía de los últimos cortes de cada versión (digsa.analitica.riesgo.cortes-con-membresia; número de paciente de ana_pacientes, nunca el
-- seudónimo ni el documento): pacientes distintos del periodo por nivel y composición de la cohorte, reproducibles para esos cortes. Técnica:
-- fuera de la exportación completa y de toda salida (solo conteos).
CREATE TABLE ana_cohortes_miembros (
    version_id      bigint NOT NULL REFERENCES ana_cohortes_versiones (id) ON DELETE RESTRICT,
    corte_cargue_id bigint NOT NULL,
    mes             date NOT NULL,
    paciente_id     integer NOT NULL,
    fuerzas         text[] NOT NULL,
    esms            text[] NOT NULL,
    fuerzas_esms    text[] NOT NULL,
    PRIMARY KEY (version_id, corte_cargue_id, mes, paciente_id)
);
COMMENT ON TABLE ana_cohortes_miembros IS 'Miembros de cada versión por mes en sus últimos cortes (paciente como número); técnica, solo se consulta como conteos';
COMMENT ON COLUMN ana_cohortes_miembros.version_id IS 'Versión de la cohorte';
COMMENT ON COLUMN ana_cohortes_miembros.corte_cargue_id IS 'Corte calculado (sin llave foránea: se reemplaza y se purga por corte)';
COMMENT ON COLUMN ana_cohortes_miembros.mes IS 'Mes (primer día)';
COMMENT ON COLUMN ana_cohortes_miembros.paciente_id IS 'Paciente (ana_pacientes)';
COMMENT ON COLUMN ana_cohortes_miembros.fuerzas IS 'Fuerzas en que dispensó en el mes';
COMMENT ON COLUMN ana_cohortes_miembros.esms IS 'ESM en que dispensó en el mes';
COMMENT ON COLUMN ana_cohortes_miembros.fuerzas_esms IS 'Pares «Fuerza|ESM» de sus dispensaciones del mes (alcance de Sanidad en un ESM compartido)';

-- Pacientes distintos del periodo por defecto (los 12 meses que terminan en el último mes con datos) por nivel, contados en el mismo
-- recorrido (sin consultar la membresía): las tarjetas del periodo por defecto los leen directo. Agregado: sin datos personales.
CREATE TABLE ana_cohortes_conteos (
    version_id      bigint NOT NULL REFERENCES ana_cohortes_versiones (id) ON DELETE RESTRICT,
    corte_cargue_id bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    desde           date NOT NULL,
    hasta           date NOT NULL,
    nivel           text NOT NULL CHECK (nivel IN ('nacional', 'fuerza', 'esm')),
    fuerza          text COLLATE "C" NOT NULL DEFAULT '',
    codigo          text COLLATE "C" NOT NULL DEFAULT '',
    pacientes       bigint NOT NULL CHECK (pacientes >= 0),
    PRIMARY KEY (version_id, corte_cargue_id, nivel, fuerza, codigo),
    CHECK (desde <= hasta)
);
COMMENT ON TABLE ana_cohortes_conteos IS 'Pacientes distintos miembros en el periodo por defecto de cada versión, corte y nivel (precalculados en el recálculo)';
COMMENT ON COLUMN ana_cohortes_conteos.version_id IS 'Versión de la cohorte';
COMMENT ON COLUMN ana_cohortes_conteos.corte_cargue_id IS 'Corte';
COMMENT ON COLUMN ana_cohortes_conteos.desde IS 'Primer mes del periodo por defecto';
COMMENT ON COLUMN ana_cohortes_conteos.hasta IS 'Último mes del periodo por defecto';
COMMENT ON COLUMN ana_cohortes_conteos.nivel IS 'nacional, fuerza o esm';
COMMENT ON COLUMN ana_cohortes_conteos.fuerza IS 'Fuerza de la celda ESM (vacía en nacional y fuerza)';
COMMENT ON COLUMN ana_cohortes_conteos.codigo IS 'Código de la Fuerza o del ESM (vacío en nacional)';
COMMENT ON COLUMN ana_cohortes_conteos.pacientes IS 'Pacientes distintos miembros en algún mes del periodo';
CREATE INDEX ix_ana_cohortes_conteos_corte ON ana_cohortes_conteos (corte_cargue_id);

-- ---------- Referencias cargables (interacciones, indicaciones, huérfanas) ----------
CREATE TABLE ana_ref_versiones (
    id           integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lista        text NOT NULL CHECK (lista IN ('interacciones', 'indicaciones', 'huerfanas')),
    version      integer NOT NULL CHECK (version >= 1),
    fuente       text NOT NULL CHECK (char_length(fuente) BETWEEN 3 AND 200),
    fecha_fuente date,
    sha256       char(64) NOT NULL,
    filas        integer NOT NULL CHECK (filas >= 0),
    ficticio     boolean NOT NULL DEFAULT false,
    vigente      boolean NOT NULL DEFAULT true,
    cargada      timestamptz NOT NULL DEFAULT now(),
    cargada_por  bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    UNIQUE (lista, version)
);
COMMENT ON TABLE ana_ref_versiones IS 'Versiones de las referencias de cohortes y alertas cargadas por archivo (RESP L2239: fuentes a cargo del contratista)';
COMMENT ON COLUMN ana_ref_versiones.lista IS 'interacciones (pares de principios), indicaciones (pares ATC–CIE-10) o huerfanas (CIE-10)';
COMMENT ON COLUMN ana_ref_versiones.version IS 'Número de versión';
COMMENT ON COLUMN ana_ref_versiones.fuente IS 'Fuente con su edición (p. ej. referencia técnica licenciada, listado de MinSalud)';
COMMENT ON COLUMN ana_ref_versiones.fecha_fuente IS 'Fecha de la fuente';
COMMENT ON COLUMN ana_ref_versiones.sha256 IS 'SHA-256 del archivo cargado';
COMMENT ON COLUMN ana_ref_versiones.filas IS 'Filas válidas';
COMMENT ON COLUMN ana_ref_versiones.ficticio IS 'Datos de ejemplo ficticios (pruebas; no se cargan en producción)';
COMMENT ON COLUMN ana_ref_versiones.vigente IS 'La versión que usan cohortes y alertas (una por lista)';
COMMENT ON COLUMN ana_ref_versiones.cargada IS 'Fecha de carga';
COMMENT ON COLUMN ana_ref_versiones.cargada_por IS 'Quién la cargó';
CREATE UNIQUE INDEX uq_ana_ref_versiones_vigente ON ana_ref_versiones (lista) WHERE vigente;
CREATE INDEX ix_ana_ref_versiones_cargada_por ON ana_ref_versiones (cargada_por) WHERE cargada_por IS NOT NULL;

CREATE TABLE ana_ref_filas (
    version_id integer NOT NULL REFERENCES ana_ref_versiones (id) ON DELETE RESTRICT,
    linea      integer NOT NULL CHECK (linea >= 1),
    codigo1    text NOT NULL CHECK (char_length(codigo1) BETWEEN 1 AND 200),
    codigo2    text CHECK (char_length(codigo2) <= 200),
    nota       text CHECK (char_length(nota) <= 300),
    PRIMARY KEY (version_id, linea)
);
COMMENT ON TABLE ana_ref_filas IS 'Filas de una versión de referencia: par de principios (interacciones), ATC y CIE-10 admitido (indicaciones) o CIE-10 (huérfanas)';
COMMENT ON COLUMN ana_ref_filas.version_id IS 'Versión';
COMMENT ON COLUMN ana_ref_filas.linea IS 'Línea del archivo';
COMMENT ON COLUMN ana_ref_filas.codigo1 IS 'Principio activo normalizado, ATC (prefijo) o CIE-10 (prefijo)';
COMMENT ON COLUMN ana_ref_filas.codigo2 IS 'Segundo principio (interacciones) o CIE-10 admitido (indicaciones); nulo en huérfanas';
COMMENT ON COLUMN ana_ref_filas.nota IS 'Nota de la fuente';

-- ---------- Alertas tempranas ----------
CREATE TABLE ana_alertas_parametros (
    tipo            text PRIMARY KEY CHECK (tipo IN ('incremento_gasto', 'incremento_consumo', 'alto_costo', 'polimedicados', 'duplicidad_terapeutica',
                                                     'interaccion', 'fuera_indicacion', 'marca_estrecho_margen', 'desabastecimiento',
                                                     'concentracion_prescripciones', 'formulacion_inusual', 'presentacion_inconsistente')),
    version         integer NOT NULL CHECK (version >= 1),
    parametros      jsonb NOT NULL CHECK (jsonb_typeof(parametros) = 'object' AND octet_length(parametros::text) <= 8000),
    actualizado     timestamptz NOT NULL DEFAULT now(),
    actualizado_por bigint REFERENCES usuarios (id) ON DELETE RESTRICT
);
COMMENT ON TABLE ana_alertas_parametros IS 'Parámetros vigentes de cada tipo de alerta (activo, niveles, ventana, umbrales de severidad, extras, exclusiones y plantilla)';
COMMENT ON COLUMN ana_alertas_parametros.tipo IS 'Tipo de alerta';
COMMENT ON COLUMN ana_alertas_parametros.version IS 'Versión de los parámetros (cada guardado suma uno)';
COMMENT ON COLUMN ana_alertas_parametros.parametros IS 'Parámetros (reglas/AnaliticaAlertas.Parametros en JSON)';
COMMENT ON COLUMN ana_alertas_parametros.actualizado IS 'Último cambio';
COMMENT ON COLUMN ana_alertas_parametros.actualizado_por IS 'Quién hizo el último cambio (nulo: siembra)';
CREATE INDEX ix_ana_alertas_parametros_por ON ana_alertas_parametros (actualizado_por) WHERE actualizado_por IS NOT NULL;

CREATE TABLE ana_alertas_parametros_historial (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo              text NOT NULL REFERENCES ana_alertas_parametros (tipo) ON DELETE RESTRICT,
    version           integer NOT NULL CHECK (version >= 1),
    antes             jsonb,
    despues           jsonb NOT NULL,
    usuario_id        bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    responsable_texto text NOT NULL CHECK (char_length(responsable_texto) <= 200),
    fecha             timestamptz NOT NULL,
    UNIQUE (tipo, version)
);
COMMENT ON TABLE ana_alertas_parametros_historial IS 'Traza de la parametrización (RESP L5671): usuario, fecha, valor anterior y nuevo de cada versión';
COMMENT ON COLUMN ana_alertas_parametros_historial.tipo IS 'Tipo de alerta';
COMMENT ON COLUMN ana_alertas_parametros_historial.version IS 'Versión resultante';
COMMENT ON COLUMN ana_alertas_parametros_historial.antes IS 'Parámetros anteriores (nulo en la siembra)';
COMMENT ON COLUMN ana_alertas_parametros_historial.despues IS 'Parámetros nuevos';
COMMENT ON COLUMN ana_alertas_parametros_historial.usuario_id IS 'Quién (nulo: siembra)';
COMMENT ON COLUMN ana_alertas_parametros_historial.responsable_texto IS 'Responsable legible con su perfil';
COMMENT ON COLUMN ana_alertas_parametros_historial.fecha IS 'Fecha y hora';
CREATE INDEX ix_ana_alertas_parametros_historial_usuario ON ana_alertas_parametros_historial (usuario_id) WHERE usuario_id IS NOT NULL;

CREATE TABLE ana_alertas_ejecuciones (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    origen          text NOT NULL CHECK (origen IN ('cargue', 'usuario', 'version', 'reinicio')),
    corte_cargue_id bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    usuario_id      bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    inicio          timestamptz NOT NULL,
    fin             timestamptz,
    estado          text NOT NULL DEFAULT 'en_curso' CHECK (estado IN ('en_curso', 'ok', 'con_fallos', 'error', 'interrumpida')),
    nuevas          integer NOT NULL DEFAULT 0 CHECK (nuevas >= 0),
    actualizadas    integer NOT NULL DEFAULT 0 CHECK (actualizadas >= 0),
    avisos          integer NOT NULL DEFAULT 0 CHECK (avisos >= 0),
    por_tipo        jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(por_tipo) = 'object'),
    duracion_ms     bigint CHECK (duracion_ms >= 0),
    error           text CHECK (char_length(error) <= 500),
    CHECK ((estado = 'en_curso') = (fin IS NULL))
);
COMMENT ON TABLE ana_alertas_ejecuciones IS 'Ejecuciones del motor de alertas (al aplicar cada cargue o bajo demanda) con el resultado por tipo';
COMMENT ON COLUMN ana_alertas_ejecuciones.origen IS 'cargue, usuario, version o reinicio';
COMMENT ON COLUMN ana_alertas_ejecuciones.corte_cargue_id IS 'Corte evaluado';
COMMENT ON COLUMN ana_alertas_ejecuciones.usuario_id IS 'Quién la pidió (nulo: automática)';
COMMENT ON COLUMN ana_alertas_ejecuciones.inicio IS 'Inicio';
COMMENT ON COLUMN ana_alertas_ejecuciones.fin IS 'Fin';
COMMENT ON COLUMN ana_alertas_ejecuciones.estado IS 'en_curso, ok, con_fallos (algún tipo con error o aviso pendiente), error o interrumpida';
COMMENT ON COLUMN ana_alertas_ejecuciones.nuevas IS 'Alertas nuevas';
COMMENT ON COLUMN ana_alertas_ejecuciones.actualizadas IS 'Alertas existentes reevaluadas';
COMMENT ON COLUMN ana_alertas_ejecuciones.avisos IS 'Avisos de severidad alta encolados (correo)';
COMMENT ON COLUMN ana_alertas_ejecuciones.por_tipo IS 'Por tipo: evaluado (n.º de alertas) o «no evaluado: motivo» o «error»';
COMMENT ON COLUMN ana_alertas_ejecuciones.duracion_ms IS 'Duración en milisegundos';
COMMENT ON COLUMN ana_alertas_ejecuciones.error IS 'Error técnico resumido';
CREATE INDEX ix_ana_alertas_ejecuciones_corte ON ana_alertas_ejecuciones (corte_cargue_id);
CREATE INDEX ix_ana_alertas_ejecuciones_en_curso ON ana_alertas_ejecuciones (corte_cargue_id) WHERE fin IS NULL;
CREATE INDEX ix_ana_alertas_ejecuciones_usuario ON ana_alertas_ejecuciones (usuario_id) WHERE usuario_id IS NOT NULL;

CREATE TABLE ana_alertas (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo                        text NOT NULL REFERENCES ana_alertas_parametros (tipo) ON DELETE RESTRICT,
    parametros_version          integer NOT NULL CHECK (parametros_version >= 1),
    llave                       char(64) NOT NULL UNIQUE,
    nivel                       text NOT NULL CHECK (nivel IN ('nacional', 'fuerza', 'esm')),
    fuerza                      text,
    esm                         text,
    punto_id                    bigint,
    dimension                   text NOT NULL CHECK (char_length(dimension) BETWEEN 1 AND 40),
    dimension_texto             text NOT NULL CHECK (char_length(dimension_texto) BETWEEN 1 AND 80),
    valor                       text NOT NULL CHECK (char_length(valor) BETWEEN 1 AND 200),
    valor_texto                 text NOT NULL CHECK (char_length(valor_texto) BETWEEN 1 AND 300),
    cohorte_id                  bigint REFERENCES ana_cohortes (id) ON DELETE RESTRICT,
    periodo_desde               date NOT NULL,
    periodo_hasta               date NOT NULL,
    severidad                   text NOT NULL CHECK (severidad IN ('baja', 'media', 'alta')),
    umbral                      numeric(18, 2) NOT NULL,
    valor_observado             numeric(18, 2) NOT NULL,
    valor_referencia            numeric(18, 2),
    unidad                      text NOT NULL CHECK (unidad IN ('%', 'pacientes', 'registros')),
    registros                   bigint NOT NULL CHECK (registros > 0),
    pacientes                   bigint NOT NULL CHECK (pacientes >= 0),
    gasto                       numeric(18, 2) NOT NULL CHECK (gasto >= 0),
    causa                       text NOT NULL CHECK (char_length(causa) BETWEEN 1 AND 1000),
    valoracion                  text NOT NULL CHECK (char_length(valoracion) BETWEEN 1 AND 1000),
    recomendacion               text NOT NULL CHECK (char_length(recomendacion) BETWEEN 1 AND 1000),
    fuente_referencia           text CHECK (char_length(fuente_referencia) <= 300),
    seleccion                   jsonb NOT NULL CHECK (jsonb_typeof(seleccion) = 'object' AND octet_length(seleccion::text) <= 8000),
    estado                      text NOT NULL DEFAULT 'nueva' CHECK (estado IN ('nueva', 'en_revision', 'validada', 'descartada')),
    corte_cargue_id             bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    ultima_evaluacion_cargue_id bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    ejecucion_id                bigint NOT NULL REFERENCES ana_alertas_ejecuciones (id) ON DELETE RESTRICT,
    generada                    timestamptz NOT NULL,
    actualizada                 timestamptz NOT NULL,
    evento_alerta_id            bigint REFERENCES alertas_eventos (id) ON DELETE RESTRICT,
    aviso                       text NOT NULL DEFAULT 'no_aplica' CHECK (aviso IN ('no_aplica', 'pendiente', 'enviado', 'agrupado')),
    CHECK (periodo_desde <= periodo_hasta),
    CHECK (aviso <> 'enviado' OR evento_alerta_id IS NOT NULL),
    CHECK (nivel = 'nacional' OR fuerza IS NOT NULL),
    CHECK (nivel <> 'esm' OR esm IS NOT NULL)
);
COMMENT ON TABLE ana_alertas IS 'Alertas tempranas (A5 14.7): contexto obligatorio, análisis técnico (P4), selección de los registros de origen y validación humana';
COMMENT ON COLUMN ana_alertas.tipo IS 'Tipo de alerta';
COMMENT ON COLUMN ana_alertas.parametros_version IS 'Versión de los parámetros con que nació (o con que subió de severidad)';
COMMENT ON COLUMN ana_alertas.llave IS 'SHA-256 de (tipo, nivel, código del nivel, dimensión, valor, periodo): reejecutar no duplica';
COMMENT ON COLUMN ana_alertas.nivel IS 'nacional, fuerza o esm';
COMMENT ON COLUMN ana_alertas.fuerza IS 'Fuerza del nivel (alcance Sanidad); nula en nacional';
COMMENT ON COLUMN ana_alertas.esm IS 'Código del ESM del nivel esm';
COMMENT ON COLUMN ana_alertas.punto_id IS 'ESM en puntos (alcance ESM), si tiene correspondencia';
COMMENT ON COLUMN ana_alertas.dimension IS 'Dimensión (atc1–atc5, principio, esm, fuerza, par, cum, medicamento)';
COMMENT ON COLUMN ana_alertas.dimension_texto IS 'Dimensión legible';
COMMENT ON COLUMN ana_alertas.valor IS 'Valor de la dimensión (código)';
COMMENT ON COLUMN ana_alertas.valor_texto IS 'Valor legible';
COMMENT ON COLUMN ana_alertas.cohorte_id IS 'Cohorte asociada (alto costo, polimedicados)';
COMMENT ON COLUMN ana_alertas.periodo_desde IS 'Inicio del periodo';
COMMENT ON COLUMN ana_alertas.periodo_hasta IS 'Fin del periodo';
COMMENT ON COLUMN ana_alertas.severidad IS 'baja, media o alta (banda más alta alcanzada)';
COMMENT ON COLUMN ana_alertas.umbral IS 'Umbral de la banda alcanzada';
COMMENT ON COLUMN ana_alertas.valor_observado IS 'Valor observado (en la unidad)';
COMMENT ON COLUMN ana_alertas.valor_referencia IS 'Línea base (media de los meses previos, mediana…)';
COMMENT ON COLUMN ana_alertas.unidad IS 'Unidad del observado y del umbral';
COMMENT ON COLUMN ana_alertas.registros IS 'Registros del insumo que sustentan la alerta';
COMMENT ON COLUMN ana_alertas.pacientes IS 'Pacientes distintos (agregado)';
COMMENT ON COLUMN ana_alertas.gasto IS 'Gasto involucrado';
COMMENT ON COLUMN ana_alertas.causa IS 'Análisis técnico: causa con las cifras y la fuente';
COMMENT ON COLUMN ana_alertas.valoracion IS 'Análisis técnico: valoración del riesgo o impacto';
COMMENT ON COLUMN ana_alertas.recomendacion IS 'Análisis técnico: acciones y recomendaciones';
COMMENT ON COLUMN ana_alertas.fuente_referencia IS 'Fuente de referencia usada con su versión (listas, interacciones)';
COMMENT ON COLUMN ana_alertas.seleccion IS 'Consulta de la trazabilidad (filtros y periodo) que lista los registros de origen';
COMMENT ON COLUMN ana_alertas.estado IS 'nueva, en_revision, validada o descartada (validación humana)';
COMMENT ON COLUMN ana_alertas.corte_cargue_id IS 'Corte en que nació';
COMMENT ON COLUMN ana_alertas.ultima_evaluacion_cargue_id IS 'Corte de su última evaluación';
COMMENT ON COLUMN ana_alertas.ejecucion_id IS 'Ejecución que la generó';
COMMENT ON COLUMN ana_alertas.generada IS 'Fecha de generación';
COMMENT ON COLUMN ana_alertas.actualizada IS 'Última reevaluación';
COMMENT ON COLUMN ana_alertas.evento_alerta_id IS 'Evento del aviso por correo (severidad alta)';
COMMENT ON COLUMN ana_alertas.aviso IS 'Aviso por correo de severidad alta: no_aplica, pendiente (falló; se reintenta), enviado o agrupado (periodo histórico: va en el resumen de la ejecución)';
CREATE INDEX ix_ana_alertas_lista ON ana_alertas (periodo_hasta DESC, id DESC);
CREATE INDEX ix_ana_alertas_fuerza ON ana_alertas (fuerza, periodo_hasta DESC) WHERE fuerza IS NOT NULL;
CREATE INDEX ix_ana_alertas_tipo ON ana_alertas (tipo, periodo_hasta DESC);
CREATE INDEX ix_ana_alertas_cohorte ON ana_alertas (cohorte_id) WHERE cohorte_id IS NOT NULL;
CREATE INDEX ix_ana_alertas_corte ON ana_alertas (corte_cargue_id);
CREATE INDEX ix_ana_alertas_ultima ON ana_alertas (ultima_evaluacion_cargue_id);
CREATE INDEX ix_ana_alertas_ejecucion ON ana_alertas (ejecucion_id);
CREATE INDEX ix_ana_alertas_evento ON ana_alertas (evento_alerta_id) WHERE evento_alerta_id IS NOT NULL;
CREATE INDEX ix_ana_alertas_aviso_pendiente ON ana_alertas (id) WHERE aviso = 'pendiente';

CREATE TABLE ana_alertas_historial (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alerta_id         bigint NOT NULL REFERENCES ana_alertas (id) ON DELETE RESTRICT,
    de                text NOT NULL CHECK (de IN ('nueva', 'en_revision', 'validada', 'descartada')),
    a                 text NOT NULL CHECK (a IN ('nueva', 'en_revision', 'validada', 'descartada')),
    nota              text CHECK (char_length(nota) <= 1000),
    usuario_id        bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    responsable_texto text NOT NULL CHECK (char_length(responsable_texto) <= 200),
    fecha             timestamptz NOT NULL
);
COMMENT ON TABLE ana_alertas_historial IS 'Validación humana de las alertas (RESP L5175): cada cambio de estado con nota, usuario y fecha';
COMMENT ON COLUMN ana_alertas_historial.alerta_id IS 'Alerta';
COMMENT ON COLUMN ana_alertas_historial.de IS 'Estado anterior';
COMMENT ON COLUMN ana_alertas_historial.a IS 'Estado nuevo';
COMMENT ON COLUMN ana_alertas_historial.nota IS 'Nota (obligatoria al validar o descartar)';
COMMENT ON COLUMN ana_alertas_historial.usuario_id IS 'Quién';
COMMENT ON COLUMN ana_alertas_historial.responsable_texto IS 'Responsable legible con su perfil';
COMMENT ON COLUMN ana_alertas_historial.fecha IS 'Fecha y hora';
CREATE INDEX ix_ana_alertas_historial_alerta ON ana_alertas_historial (alerta_id, id);
CREATE INDEX ix_ana_alertas_historial_usuario ON ana_alertas_historial (usuario_id);

-- ---------- Datos iniciales ----------
-- Las 12 cohortes del A4 §11.1 e y 11.2.4 (sus criterios y la versión 1 los siembra la aplicación desde reglas/AnaliticaCohortes)
INSERT INTO ana_cohortes (codigo, nombre, descripcion, predefinida, orden) VALUES
    ('alto_costo', 'Alto costo', 'Pacientes cuyo gasto en 12 meses móviles alcanza el percentil 99 del gasto por paciente (A4 L304)', true, 1),
    ('polimedicados', 'Polimedicados', 'Cinco o más medicamentos durante seis meses o más (A4 L275)', true, 2),
    ('renal', 'Enfermedad renal', 'Diagnóstico de enfermedad renal crónica, diálisis o trasplante renal', true, 3),
    ('vih', 'VIH', 'Diagnóstico de VIH o antirretrovirales', true, 4),
    ('biologicos', 'Biológicos', 'Pacientes con medicamentos biológicos (por defecto ATC L04AB, L04AC y L01F)', true, 5),
    ('huerfanas', 'Enfermedades huérfanas', 'Diagnóstico en la lista de enfermedades huérfanas cargada como referencia', true, 6),
    ('poliformulados', 'Poliformulados', 'Cinco o más principios activos distintos en el mismo mes (A4 11.2.4)', true, 7),
    ('pluripatologicos', 'Pluripatológicos', 'Dos o más diagnósticos crónicos distintos en 12 meses (A4 11.2.4)', true, 8),
    ('control_especial', 'Control especial', 'Medicamentos de la referencia de control especial (V61)', true, 9),
    ('estrecho_margen', 'Estrecho margen terapéutico', 'Principios activos de la referencia de estrecho margen (V61)', true, 10),
    ('gcf', 'Grupos de consumo farmacéutico (GCF)', 'Partición por el ATC de nivel 2 de mayor gasto del paciente en el mes', true, 11),
    ('grc', 'Grupos de riesgo clínico (GRC)', 'Partición por diagnósticos crónicos y alto costo', true, 12)
ON CONFLICT (codigo) DO NOTHING;

-- Aviso por correo de las alertas analíticas de severidad alta (RESP L2259: operativa, solo correo; no es de continuidad ni integridad)
INSERT INTO alertas_criticas (tipo, nombre, descripcion, critica, sms, perfiles) VALUES
    ('analitica_alerta_alta', 'Alerta analítica de severidad alta',
     'El motor de analítica generó una alerta de severidad alta (solo correo; sin datos personales; conteos pequeños suprimidos). No agregue Sanidad ni ESM: el aviso no filtra por Fuerza',
     false, false, '{COORDINADOR,DIGSA,ANALISTA}')
ON CONFLICT (tipo) DO NOTHING;

-- Privilegios mínimos del rol de la aplicación, sentencia por sentencia del código (IngestaPrivilegiosTest los verifica):
--   ana_cohortes, ana_cohortes_versiones, ana_alertas_parametros, ana_ref_versiones   SELECT, INSERT, UPDATE (activa, vigente, versión)
--   ana_cohortes_ejecuciones, ana_alertas_ejecuciones, ana_cohortes_resultados        SELECT, INSERT, UPDATE (fin y estado; recalcular un corte)
--   ana_cohortes_serie, ana_cohortes_miembros, ana_cohortes_conteos                    SELECT, INSERT, DELETE (recalcular reemplaza)
--   ana_alertas                                                                         SELECT, INSERT, UPDATE (reevaluación y estado; sin DELETE)
--   ana_alertas_historial, ana_alertas_parametros_historial, ana_ref_filas              SELECT, INSERT (traza: sin UPDATE ni DELETE)
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON ana_cohortes, ana_cohortes_versiones, ana_alertas_parametros, ana_ref_versiones, '
                   'ana_cohortes_ejecuciones, ana_alertas_ejecuciones, ana_cohortes_resultados, ana_alertas TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON ana_cohortes_serie, ana_cohortes_miembros, ana_cohortes_conteos TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT ON ana_alertas_historial, ana_alertas_parametros_historial, ana_ref_filas TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
