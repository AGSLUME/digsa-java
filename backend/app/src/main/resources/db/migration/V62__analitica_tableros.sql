-- Suite DIGSA · Fase AN: analítica farmacéutica, A3 · tableros, descargas y trazabilidad (docs/especificaciones/analitica-tableros).
-- Tablas de apoyo de los tableros por cargue (para que un corte anterior dé el mismo resultado después de nuevos cargues: la consulta
-- lee solo los cargues visibles al corte, ana_cargues_visibles) y bitácora de consultas, descargas y rastreos. Los datos finales de
-- medicamento, principio activo, ATC y CIE-10 NO se copian aquí: se leen de la tabla maestra de la versión vigente al corte
-- (hom_tabla_maestra_version, hom_tabla_cie10_version), así una aprobación de Revisión no obliga a recalcular nada.
-- No inserta datos.
--
-- Rendimiento (revisión A3 2026-10-23, medido sobre 2 M filas): los pacientes distintos exactos por celda son la parte cara. Contar
-- count(DISTINCT seudónimo char(64)) ordena con la intercalación de la base (22 s por recorrido de 2 M); agruparlo por hash sobre un
-- entero y en paralelo cuesta ~1 s. Por eso los tableros leen una copia angosta de los hechos con el paciente como entero
-- (ana_hechos_tablero) y no los hechos completos (925 MB con textos originales, seudónimos y llaves). Los agregados mensuales sin
-- paciente de la primera versión (ana_agregado_mes) se retiraron: reducían solo 4 veces, tardaban 36–68 s por cargue y obligaban a dos
-- caminos de cálculo que debían cuadrar entre sí; ahora todas las cifras salen de una sola tabla.

-- ---------- Paciente como entero ----------
-- Un id por seudónimo (estable entre cargues: el mismo paciente de dos cargues cuenta una vez). No es un dato nuevo: el seudónimo ya
-- está en ana_dispensaciones; aquí solo se numera. Intercalación «C»: la búsqueda y el índice comparan bytes (el seudónimo es hex).
CREATE TABLE ana_pacientes (
    id    integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seudo char(64) COLLATE "C" NOT NULL UNIQUE
);
COMMENT ON TABLE ana_pacientes IS 'Número entero de cada seudónimo de paciente (tablas de apoyo de los tableros; técnica, se reconstruye de ana_dispensaciones)';
COMMENT ON COLUMN ana_pacientes.id IS 'Número del paciente en las tablas de apoyo';
COMMENT ON COLUMN ana_pacientes.seudo IS 'Seudónimo HMAC-SHA256 del paciente (el mismo de ana_dispensaciones)';

-- ---------- Copia angosta de los hechos para los tableros ----------
-- Una fila por hecho (las mismas filas de ana_dispensaciones del cargue: las cifras cuadran con el insumo por construcción), solo con
-- las columnas que consultan los tableros. Lo escribe solo el paso de aplicación de A3 (PasoTablas), en la misma transacción que
-- inserta los hechos y marca el cargue «aplicado»; un cargue fallido no deja filas (la transacción se revierte). Las columnas de
-- filtro tienen los nombres de ana_dispensaciones: la capa semántica arma los mismos predicados para las dos (la trazabilidad lee los
-- hechos completos); los importes van en enteros (centavos y milésimas), más livianos de sumar.
-- Sin llave foránea a ana_cargues por la misma razón que ana_dispensaciones (su revisión fila a fila pesa en la carga de 2 M).
-- Textos con intercalación «C» (son códigos): agrupar u ordenar compara bytes. Con la intercalación de la base (es_CO), un agrupamiento que
-- el planificador resuelve ordenando tardaba 20 s sobre 2 M filas (medición de la revisión A3); el orden que ve el usuario se da en Java.
CREATE TABLE ana_hechos_tablero (
    cargue_id      bigint NOT NULL,
    fecha          date NOT NULL,
    paciente_id    integer NOT NULL,
    fuerza         text COLLATE "C" NOT NULL,
    disan          text COLLATE "C",
    regional_id    bigint,
    punto_id       bigint,
    esm_codigo     text COLLATE "C" NOT NULL,
    modalidad      text COLLATE "C" NOT NULL,
    medicamento_id bigint,
    cum            text COLLATE "C",
    cie10          text COLLATE "C",
    tipo_formula   text COLLATE "C",
    especialidad   text COLLATE "C",
    numero_formula text COLLATE "C",
    cantidad_milesimas bigint NOT NULL CHECK (cantidad_milesimas > 0),
    valor_centavos     bigint NOT NULL CHECK (valor_centavos >= 0)
);
COMMENT ON TABLE ana_hechos_tablero IS 'Copia angosta de los hechos de cada cargue para los tableros de A3 (paciente como entero); técnica, se reconstruye de ana_dispensaciones';
COMMENT ON COLUMN ana_hechos_tablero.cargue_id IS 'Cargue (la consulta lee solo los visibles al corte)';
COMMENT ON COLUMN ana_hechos_tablero.fecha IS 'Fecha de dispensación';
COMMENT ON COLUMN ana_hechos_tablero.paciente_id IS 'Paciente (ana_pacientes): pacientes distintos exactos por celda';
COMMENT ON COLUMN ana_hechos_tablero.fuerza IS 'Fuerza del hecho';
COMMENT ON COLUMN ana_hechos_tablero.disan IS 'DISAN o JEFSA del hecho';
COMMENT ON COLUMN ana_hechos_tablero.regional_id IS 'Regional del ESM';
COMMENT ON COLUMN ana_hechos_tablero.punto_id IS 'ESM en puntos (nulo = sin correspondencia); alcance del perfil ESM';
COMMENT ON COLUMN ana_hechos_tablero.esm_codigo IS 'Código del ESM del insumo';
COMMENT ON COLUMN ana_hechos_tablero.modalidad IS 'Modalidad de dispensación o suministro';
COMMENT ON COLUMN ana_hechos_tablero.medicamento_id IS 'Dato original (hom_medicamento_origen); el dato final sale de la tabla maestra vigente al corte';
COMMENT ON COLUMN ana_hechos_tablero.cum IS 'CUM normalizado del insumo';
COMMENT ON COLUMN ana_hechos_tablero.cie10 IS 'CIE-10 normalizado del insumo (nulo = sin CIE-10)';
COMMENT ON COLUMN ana_hechos_tablero.tipo_formula IS 'Tipo de fórmula, si viene';
COMMENT ON COLUMN ana_hechos_tablero.especialidad IS 'Especialidad, si viene';
COMMENT ON COLUMN ana_hechos_tablero.numero_formula IS 'Número de fórmula, si viene (gasto promedio por fórmula)';
COMMENT ON COLUMN ana_hechos_tablero.cantidad_milesimas IS 'Unidades dispensadas en milésimas (exacto: la cantidad trae hasta 3 decimales); sumar enteros es más liviano que numeric';
COMMENT ON COLUMN ana_hechos_tablero.valor_centavos IS 'Valor total dispensado en centavos (exacto: el valor trae 2 decimales)';
-- Solo BRIN (mantenimiento casi nulo en la carga): cada cargue se inserta ordenado por fecha, así el BRIN de fecha poda el periodo y el
-- de cargue ubica los cargues visibles. Sin B-tree por dimensión: toda consulta recorre el periodo completo y agrupa por hash.
CREATE INDEX ix_ana_ht_fecha_brin ON ana_hechos_tablero USING brin (fecha);
CREATE INDEX ix_ana_ht_cargue_brin ON ana_hechos_tablero USING brin (cargue_id);

-- ---------- Valores de filtro por cargue ----------
-- Valores distintos de cada variable de filtro en cada cargue, con su Fuerza y ESM (alcance): opciones de la barra de filtros y
-- validación de cada valor pedido («no existe o está fuera de su alcance») sin recorrer los hechos en cada petición.
CREATE TABLE ana_cargue_valores (
    cargue_id bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    variable  text NOT NULL CHECK (variable IN ('fuerza', 'disan', 'regional', 'esm', 'modalidad', 'cum', 'medicamento', 'cie10')),
    valor     text COLLATE "C" NOT NULL,
    fuerza    text COLLATE "C" NOT NULL,
    punto_id  bigint
);
COMMENT ON TABLE ana_cargue_valores IS 'Valores distintos de cada variable de filtro por cargue, con la Fuerza y el ESM en que aparecen (opciones y validación de filtros de A3); técnica';
COMMENT ON COLUMN ana_cargue_valores.cargue_id IS 'Cargue';
COMMENT ON COLUMN ana_cargue_valores.variable IS 'Variable de filtro (medicamento = id del dato original)';
COMMENT ON COLUMN ana_cargue_valores.valor IS 'Valor tal como está en los hechos';
COMMENT ON COLUMN ana_cargue_valores.fuerza IS 'Fuerza en que aparece (alcance Sanidad)';
COMMENT ON COLUMN ana_cargue_valores.punto_id IS 'ESM en que aparece (alcance ESM)';
CREATE INDEX ix_ana_cargue_valores ON ana_cargue_valores (cargue_id, variable, valor);

-- ---------- Campos opcionales presentes por cargue ----------
-- Se calculan una vez al aplicar (antes: 4 EXISTS sobre los hechos del servicio web en cada tablero y en cada catálogo)
ALTER TABLE ana_cargues ADD COLUMN campos_presentes text[] NOT NULL DEFAULT '{}';
COMMENT ON COLUMN ana_cargues.campos_presentes IS 'Campos opcionales del insumo presentes en el cargue (archivo: por el mapeo; servicio web: por sus hechos), calculados al aplicar';

-- ---------- Bitácora de consultas, descargas y rastreos (A5 4.7.4 y 14.9) ----------
-- Sin datos personales: filtros que pasan la lista blanca (códigos de catálogo), corte, n.º de registros, huella del resultado y
-- duración. Es bitácora: la aplicación no la modifica ni la borra.
CREATE TABLE ana_consultas (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id   bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    usuario      text NOT NULL CHECK (char_length(usuario) <= 60),
    perfil       text NOT NULL CHECK (char_length(perfil) <= 200),
    alcance      text NOT NULL CHECK (char_length(alcance) <= 120),
    accion       text NOT NULL CHECK (accion IN ('consulta', 'descarga', 'traza', 'asistente')),
    tablero      text CHECK (char_length(tablero) <= 40),
    objeto       text CHECK (char_length(objeto) <= 300),
    filtros      jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(filtros) = 'object' AND octet_length(filtros::text) <= 8000),
    corte_cargue_id bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    formato      text CHECK (formato IN ('xlsx', 'csv', 'pdf')),
    registros    bigint CHECK (registros >= 0),
    huella       char(64),
    duracion_ms  bigint CHECK (duracion_ms >= 0),
    resultado    text NOT NULL DEFAULT 'ok' CHECK (resultado IN ('ok', 'rechazada', 'tiempo_agotado', 'error')),
    advertencias text[] NOT NULL DEFAULT '{}',
    creado       timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE ana_consultas IS 'Bitácora de analítica: cada consulta de tablero, descarga y rastreo con usuario, perfil, alcance, filtros, corte, registros, huella y duración (sin datos personales)';
COMMENT ON COLUMN ana_consultas.usuario_id IS 'Usuario que consultó';
COMMENT ON COLUMN ana_consultas.usuario IS 'Usuario (login) al momento de la consulta';
COMMENT ON COLUMN ana_consultas.perfil IS 'Perfiles y roles del usuario al momento de la consulta';
COMMENT ON COLUMN ana_consultas.alcance IS 'Alcance aplicado (Nacional, Fuerza X o ESM n)';
COMMENT ON COLUMN ana_consultas.accion IS 'consulta, descarga, traza o asistente';
COMMENT ON COLUMN ana_consultas.tablero IS 'Código del tablero';
COMMENT ON COLUMN ana_consultas.objeto IS 'Objeto consultado (tablero y nivel, o la cifra rastreada)';
COMMENT ON COLUMN ana_consultas.filtros IS 'Consulta estructurada: filtros (solo valores de la lista blanca; en las rechazadas, solo cuántos), periodo, granularidad, comparación y nivel';
COMMENT ON COLUMN ana_consultas.corte_cargue_id IS 'Cargue que fija la fecha de corte';
COMMENT ON COLUMN ana_consultas.formato IS 'Formato de la descarga';
COMMENT ON COLUMN ana_consultas.registros IS 'Registros del insumo detrás de las cifras';
COMMENT ON COLUMN ana_consultas.huella IS 'SHA-256 del resultado canónico (igual para el mismo perfil, filtros y corte)';
COMMENT ON COLUMN ana_consultas.duracion_ms IS 'Duración en milisegundos';
COMMENT ON COLUMN ana_consultas.resultado IS 'ok, rechazada (filtro inexistente, fuera del alcance o consulta no válida), tiempo_agotado o error (falla técnica)';
COMMENT ON COLUMN ana_consultas.advertencias IS 'Advertencias de la consulta (sin datos personales ni valores escritos por el usuario)';
COMMENT ON COLUMN ana_consultas.creado IS 'Fecha y hora de la consulta';
-- Consulta de la bitácora por fecha (paginada por llave) y por usuario; llave foránea del corte
CREATE INDEX ix_ana_consultas_creado ON ana_consultas (creado DESC, id DESC);
CREATE INDEX ix_ana_consultas_usuario ON ana_consultas (usuario_id, creado DESC, id DESC);
CREATE INDEX ix_ana_consultas_corte ON ana_consultas (corte_cargue_id) WHERE corte_cargue_id IS NOT NULL;

-- Privilegios mínimos del rol de la aplicación, sentencia por sentencia del código (IngestaPrivilegiosTest los verifica):
--   ana_pacientes, ana_hechos_tablero, ana_cargue_valores  SELECT, INSERT (los escribe la aplicación del cargue; nunca se modifican ni se
--                                                          borran: un cargue fallido revierte)
--   ana_consultas      SELECT, INSERT (bitácora: sin UPDATE ni DELETE)
--   ana_cargues.campos_presentes: UPDATE (V60 ya da UPDATE sobre ana_cargues)
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT ON ana_pacientes, ana_hechos_tablero, ana_cargue_valores, ana_consultas TO %I', app);
    EXECUTE format('GRANT USAGE ON SEQUENCE ana_consultas_id_seq, ana_pacientes_id_seq TO %I', app);
END $$;
