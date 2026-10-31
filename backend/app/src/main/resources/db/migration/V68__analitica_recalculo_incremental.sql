-- Suite DIGSA · Fase AN: analítica farmacéutica, A7.4 · recálculo incremental de cohortes y alertas (duda AN.23;
-- docs/especificaciones/analitica-cohortes-alertas «Recálculo incremental»).
--
-- Al aplicar un cargue, el motor recalcula solo los meses afectados (los de los hechos agregados o reemplazados más los que dependen de
-- ellos por las ventanas de cada cálculo) y copia lo demás de la versión anterior del corte; el resultado es idéntico al del recorrido
-- completo. Para eso cada recálculo completo y sin fallas deja su «versión utilizable»: la firma de lo que fija el resultado (versiones de
-- cohortes, parámetros, listas de referencia, versión del motor), los cargues del corte con la huella de la clasificación clínica de sus
-- medicamentos y diagnósticos, y lo que el recorrido acumula y no queda en otra tabla (alertas de secuencia por mes y puntos de cada ESM).
-- Se conservan solo las de los dos últimos cortes; se derivan de los hechos y se reconstruyen con un recálculo completo.

CREATE TABLE ana_riesgo_inc_cortes (
    corte_cargue_id bigint PRIMARY KEY REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    ejecucion_id    bigint NOT NULL REFERENCES ana_cohortes_ejecuciones (id) ON DELETE RESTRICT,
    firma           char(64) NOT NULL,
    cargues         bigint[] NOT NULL,
    catalogo        text[] NOT NULL,
    primer_mes      date NOT NULL,
    ultimo_mes      date NOT NULL,
    creado          timestamptz NOT NULL,
    CHECK (cardinality(cargues) = cardinality(catalogo))
);
COMMENT ON TABLE ana_riesgo_inc_cortes IS 'Versión utilizable de un corte para el recálculo incremental (A7.4, AN.23): firma, cargues y huella del catálogo por cargue';
COMMENT ON COLUMN ana_riesgo_inc_cortes.corte_cargue_id IS 'Corte (cargue que lo fija)';
COMMENT ON COLUMN ana_riesgo_inc_cortes.ejecucion_id IS 'Recálculo de cohortes que la dejó (completo o incremental, sin fallas)';
COMMENT ON COLUMN ana_riesgo_inc_cortes.firma IS 'SHA-256 de la versión del motor, la gracia, las versiones de cohortes, los tipos y versiones de parámetros de alertas y las listas de referencia';
COMMENT ON COLUMN ana_riesgo_inc_cortes.cargues IS 'Cargues visibles en el corte';
COMMENT ON COLUMN ana_riesgo_inc_cortes.catalogo IS 'Por cargue (mismo orden): SHA-256 de la clasificación clínica (ATC, principio, CUM; CIE-10 clínico) de sus medicamentos y diagnósticos';
COMMENT ON COLUMN ana_riesgo_inc_cortes.primer_mes IS 'Primer mes de datos del corte (fija el calentamiento de los ingresos)';
COMMENT ON COLUMN ana_riesgo_inc_cortes.ultimo_mes IS 'Último mes de datos del corte (fija la censura de las salidas)';
COMMENT ON COLUMN ana_riesgo_inc_cortes.creado IS 'Fecha y hora';
CREATE INDEX ix_ana_riesgo_inc_cortes_ejecucion ON ana_riesgo_inc_cortes (ejecucion_id);

CREATE TABLE ana_riesgo_inc_secuencia (
    corte_cargue_id bigint NOT NULL REFERENCES ana_riesgo_inc_cortes (corte_cargue_id) ON DELETE CASCADE,
    tipo            text COLLATE "C" NOT NULL,
    nivel           text COLLATE "C" NOT NULL,
    codigo_nivel    text COLLATE "C" NOT NULL,
    dimension       text COLLATE "C" NOT NULL,
    valor           text COLLATE "C" NOT NULL,
    mes             date NOT NULL,
    pacientes       bigint NOT NULL CHECK (pacientes >= 0),
    registros       bigint NOT NULL CHECK (registros >= 0),
    gasto_centavos  bigint NOT NULL,
    esms            text[] NOT NULL,
    fuerzas         text[] NOT NULL,
    cums            text[] NOT NULL,
    PRIMARY KEY (corte_cargue_id, tipo, nivel, codigo_nivel, dimension, valor, mes)
);
COMMENT ON TABLE ana_riesgo_inc_secuencia IS 'Lo que el recorrido acumuló de las alertas de secuencia (duplicidad, interacción, marca, fuera de indicación) por llave y mes; conteos, sin pacientes';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.corte_cargue_id IS 'Corte';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.tipo IS 'Tipo de alerta de secuencia';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.nivel IS 'nacional, fuerza o esm';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.codigo_nivel IS 'Fuerza o «Fuerza|ESM» (vacío en nacional)';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.dimension IS 'Dimensión de la alerta (atc4, par, principio, atc5)';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.valor IS 'Valor de la dimensión';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.mes IS 'Mes (primer día) de la dispensación que dispara el evento';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.pacientes IS 'Pacientes distintos del mes (conteo)';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.registros IS 'Registros involucrados';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.gasto_centavos IS 'Gasto de los registros involucrados';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.esms IS 'ESM involucrados';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.fuerzas IS 'Fuerzas involucradas';
COMMENT ON COLUMN ana_riesgo_inc_secuencia.cums IS 'CUM involucrados';

CREATE TABLE ana_riesgo_inc_puntos (
    corte_cargue_id bigint NOT NULL REFERENCES ana_riesgo_inc_cortes (corte_cargue_id) ON DELETE CASCADE,
    mes             date NOT NULL,
    esm             text COLLATE "C" NOT NULL,
    puntos          bigint[] NOT NULL,
    PRIMARY KEY (corte_cargue_id, mes, esm)
);
COMMENT ON TABLE ana_riesgo_inc_puntos IS 'Puntos (ESM en puntos) distintos con que llegó cada ESM en cada mes: ubican las alertas de nivel ESM';
COMMENT ON COLUMN ana_riesgo_inc_puntos.corte_cargue_id IS 'Corte';
COMMENT ON COLUMN ana_riesgo_inc_puntos.mes IS 'Mes (primer día)';
COMMENT ON COLUMN ana_riesgo_inc_puntos.esm IS 'Código del ESM del insumo';
COMMENT ON COLUMN ana_riesgo_inc_puntos.puntos IS 'Puntos distintos del ESM en el mes (normalmente uno)';

-- El registro de cada ejecución dice si fue incremental o completa, qué meses recalculó y por qué fue completa
ALTER TABLE ana_cohortes_ejecuciones ADD COLUMN modo text CHECK (modo IN ('completo', 'incremental'));
ALTER TABLE ana_cohortes_ejecuciones ADD COLUMN meses_recalculados text CHECK (char_length(meses_recalculados) <= 60);
ALTER TABLE ana_cohortes_ejecuciones ADD COLUMN motivo_modo text CHECK (char_length(motivo_modo) <= 300);
COMMENT ON COLUMN ana_cohortes_ejecuciones.modo IS 'completo (recorre todos los hechos del corte) o incremental (solo los meses afectados; A7.4); nulo en las anteriores a V68';
COMMENT ON COLUMN ana_cohortes_ejecuciones.meses_recalculados IS 'Meses recalculados en el modo incremental («2025-11 a 2026-01»; «ninguno»)';
COMMENT ON COLUMN ana_cohortes_ejecuciones.motivo_modo IS 'Por qué fue completo (primer corte, apagado, cambió la configuración…) o desde qué corte fue incremental';

-- Privilegios mínimos del rol de la aplicación (se reescriben en cada recálculo y se purgan los de cortes anteriores)
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON ana_riesgo_inc_cortes, ana_riesgo_inc_secuencia, ana_riesgo_inc_puntos TO %I', app);
END $$;
