-- Suite DIGSA · Fase AN: analítica farmacéutica, A5 · predicción (docs/especificaciones/analitica-cohortes-alertas, tablero 12 «Predictivo»).
-- Bitácora de entrenamientos (A5 L743–765 4.7.4 «ejecuciones y actualizaciones de modelos predictivos») y la proyección que dejó cada uno
-- (A4 §11.1 f «comparación permanente entre valores proyectados y observados»). Un entrenamiento es una serie mensual AGREGADA (gasto,
-- dispensaciones o pacientes distintos) de un nivel (nacional, Fuerza o ESM) y, opcionalmente, de un valor de dimensión (ATC, principio
-- activo o CIE-10), entrenada contra un corte (cargue aplicado) con reglas/AnaliticaPrediccion (Java puro, sin azar: misma serie + misma
-- versión del algoritmo = mismo resultado). Nunca hay pacientes: solo cifras agregadas. Las filas no se modifican ni se borran (traza).

CREATE TABLE ana_pred_entrenamientos (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    metrica           text NOT NULL CHECK (metrica IN ('gasto', 'dispensaciones', 'pacientes')),
    nivel             text NOT NULL CHECK (nivel IN ('nacional', 'fuerza', 'esm')),
    nivel_valor       text CHECK (nivel_valor ~ '^[A-Z0-9][A-Z0-9_.-]{0,39}$'),
    dimension         text CHECK (dimension IN ('atc', 'principio', 'cie10')),
    dimension_valor   text CHECK (char_length(dimension_valor) BETWEEN 1 AND 200),
    alcance           text NOT NULL DEFAULT 'nacional' CHECK (alcance ~ '^(nacional|fuerza:[A-Z]{2,5}|esm:[0-9]{1,18})$'),
    corte_cargue_id   bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    fecha_corte       date,
    origen            text NOT NULL CHECK (origen IN ('automatico', 'manual')),
    usuario_id        bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    horizonte         smallint NOT NULL CHECK (horizonte BETWEEN 6 AND 24),
    metodo            text CHECK (metodo IN ('regresion_lineal', 'holt', 'holt_winters')),
    parametros        jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(parametros) = 'object' AND octet_length(parametros::text) <= 4000),
    evaluados         jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(evaluados) = 'array' AND octet_length(evaluados::text) <= 8000),
    historia_desde    date CHECK (extract(day FROM historia_desde) = 1),
    historia_hasta    date CHECK (extract(day FROM historia_hasta) = 1),
    historia_meses    integer NOT NULL DEFAULT 0 CHECK (historia_meses >= 0),
    backtest_meses    smallint NOT NULL DEFAULT 0 CHECK (backtest_meses BETWEEN 0 AND 3),
    mape              numeric(14, 2) CHECK (mape >= 0),
    wmape             numeric(14, 2) CHECK (wmape >= 0),
    sigma             numeric(22, 2) CHECK (sigma >= 0),
    notas             text[] NOT NULL DEFAULT '{}',
    parcial_mes       date CHECK (extract(day FROM parcial_mes) = 1),
    parcial_valor     numeric(22, 2),
    version_algoritmo text NOT NULL CHECK (char_length(version_algoritmo) BETWEEN 1 AND 40),
    sha256            text CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    estado            text NOT NULL CHECK (estado IN ('ok', 'historia_insuficiente', 'error')),
    mensaje           text CHECK (char_length(mensaje) <= 2000),
    duracion_ms       integer NOT NULL CHECK (duracion_ms >= 0),
    creado            timestamptz NOT NULL DEFAULT now(),
    CHECK ((nivel = 'nacional') = (nivel_valor IS NULL)),
    CHECK ((dimension IS NULL) = (dimension_valor IS NULL)),
    CHECK (origen = 'automatico' OR usuario_id IS NOT NULL),
    CHECK (estado <> 'ok' OR (metodo IS NOT NULL AND historia_hasta IS NOT NULL AND sha256 IS NOT NULL)),
    CHECK (estado = 'error' OR sha256 IS NOT NULL),
    CHECK (historia_desde IS NULL OR historia_hasta IS NULL OR historia_desde <= historia_hasta)
);
COMMENT ON TABLE ana_pred_entrenamientos IS 'Bitácora de entrenamientos de la predicción (A5 4.7.4; tablero 12): una fila por entrenamiento automático (cargue que completa un mes) o manual («Reentrenar»); no se modifica ni se borra';
COMMENT ON COLUMN ana_pred_entrenamientos.metrica IS 'Serie proyectada: gasto, dispensaciones o pacientes distintos (count distinct por mes; nunca se suma entre celdas)';
COMMENT ON COLUMN ana_pred_entrenamientos.nivel IS 'Nivel de la serie: nacional, fuerza o esm (según el alcance de quien consulta)';
COMMENT ON COLUMN ana_pred_entrenamientos.nivel_valor IS 'Código de la Fuerza o sigla del ESM (nulo en el nacional)';
COMMENT ON COLUMN ana_pred_entrenamientos.dimension IS 'Dimensión opcional de la serie: atc (por prefijo, el nivel lo da el largo), principio o cie10 (por prefijo)';
COMMENT ON COLUMN ana_pred_entrenamientos.dimension_valor IS 'Valor de la dimensión (código ATC, principio activo o código CIE-10); nulo sin dimensión';
COMMENT ON COLUMN ana_pred_entrenamientos.alcance IS 'Alcance de datos con que se entrenó (ServicioConsultaAnalitica.alcanceCodigo: nacional, fuerza:EJC, esm:<punto>; los automáticos, nacional). Solo lo ven perfiles del mismo alcance (un ESM compartido da series distintas por Fuerza); la vista de su Fuerza ve también los nacionales (misma serie)';
COMMENT ON COLUMN ana_pred_entrenamientos.corte_cargue_id IS 'Cargue aplicado que fija el corte (cargues visibles y tabla maestra vigente en ese instante)';
COMMENT ON COLUMN ana_pred_entrenamientos.fecha_corte IS 'Último día con dispensaciones visible al corte: su mes, si está incompleto, no entra al entrenamiento (observado parcial)';
COMMENT ON COLUMN ana_pred_entrenamientos.origen IS 'automatico (al aplicar un cargue que completa un mes) o manual (Reentrenar: Administrador, Coordinador o Analista)';
COMMENT ON COLUMN ana_pred_entrenamientos.usuario_id IS 'Quién reentrenó (nulo en los automáticos)';
COMMENT ON COLUMN ana_pred_entrenamientos.horizonte IS 'Meses proyectados (6 a 24; por defecto 6, A4 §11.1 f)';
COMMENT ON COLUMN ana_pred_entrenamientos.metodo IS 'Método elegido por backtesting: regresion_lineal, holt o holt_winters (nulo si no se proyectó)';
COMMENT ON COLUMN ana_pred_entrenamientos.parametros IS 'Parámetros del método reentrenado con toda la serie (alfa, beta, gamma; o intercepto y pendiente)';
COMMENT ON COLUMN ana_pred_entrenamientos.evaluados IS 'Métodos evaluados en el backtesting: [{metodo, parametros, wmape, mape}]';
COMMENT ON COLUMN ana_pred_entrenamientos.historia_desde IS 'Primer mes de entrenamiento (día 1)';
COMMENT ON COLUMN ana_pred_entrenamientos.historia_hasta IS 'Último mes completo de entrenamiento (día 1)';
COMMENT ON COLUMN ana_pred_entrenamientos.historia_meses IS 'Meses completos de historia (con los huecos en 0)';
COMMENT ON COLUMN ana_pred_entrenamientos.backtest_meses IS 'Meses del backtesting: 3, o 1 con menos de 9 meses de historia (0 sin proyección)';
COMMENT ON COLUMN ana_pred_entrenamientos.mape IS 'MAPE del método elegido en el backtesting (%; nulo si ningún mes observado es distinto de 0); acotado a 999999999999,99 (con observados cercanos a 0 el error relativo se dispara)';
COMMENT ON COLUMN ana_pred_entrenamientos.wmape IS 'WMAPE del método elegido en el backtesting (%); acotado a 999999999999,99';
COMMENT ON COLUMN ana_pred_entrenamientos.sigma IS 'Desviación de los residuos a un paso (IC 95 % = proyección ± 1,96 · sigma · raíz de h)';
COMMENT ON COLUMN ana_pred_entrenamientos.notas IS 'Notas visibles: sin componente estacional, backtesting de 1 mes, meses sin datos tomados como 0';
COMMENT ON COLUMN ana_pred_entrenamientos.parcial_mes IS 'Mes del corte incompleto (no entra al entrenamiento), si lo hay';
COMMENT ON COLUMN ana_pred_entrenamientos.parcial_valor IS 'Valor observado del mes parcial hasta la fecha de corte';
COMMENT ON COLUMN ana_pred_entrenamientos.version_algoritmo IS 'Versión de reglas/AnaliticaPrediccion (cambia si cambia cualquier decisión del algoritmo)';
COMMENT ON COLUMN ana_pred_entrenamientos.sha256 IS 'Huella SHA-256 de la serie de entrenamiento («aaaa-mm;valor» por línea): misma huella + misma versión = misma proyección';
COMMENT ON COLUMN ana_pred_entrenamientos.estado IS 'ok, historia_insuficiente (menos de 6 meses completos) o error';
COMMENT ON COLUMN ana_pred_entrenamientos.mensaje IS '«Historia insuficiente: n meses (mínimo 6)» o el motivo del error';
COMMENT ON COLUMN ana_pred_entrenamientos.duracion_ms IS 'Duración del entrenamiento (consulta de la serie, backtesting y proyección)';
COMMENT ON COLUMN ana_pred_entrenamientos.creado IS 'Fecha del entrenamiento';
CREATE INDEX ix_ana_pred_entrenamientos_serie ON ana_pred_entrenamientos (metrica, nivel, coalesce(nivel_valor, ''), coalesce(dimension, ''),
    coalesce(dimension_valor, ''), alcance, id DESC);
CREATE INDEX ix_ana_pred_entrenamientos_corte ON ana_pred_entrenamientos (corte_cargue_id);
CREATE INDEX ix_ana_pred_entrenamientos_usuario ON ana_pred_entrenamientos (usuario_id) WHERE usuario_id IS NOT NULL;

CREATE TABLE ana_pred_proyecciones (
    entrenamiento_id bigint NOT NULL REFERENCES ana_pred_entrenamientos (id) ON DELETE RESTRICT,
    mes              date NOT NULL CHECK (extract(day FROM mes) = 1),
    h                smallint NOT NULL CHECK (h BETWEEN 1 AND 24),
    valor            numeric(22, 2) NOT NULL CHECK (valor >= 0),
    inferior         numeric(22, 2) NOT NULL CHECK (inferior >= 0),
    superior         numeric(22, 2) NOT NULL CHECK (superior >= 0),
    PRIMARY KEY (entrenamiento_id, mes),
    UNIQUE (entrenamiento_id, h),
    CHECK (inferior <= valor AND valor <= superior)
);
COMMENT ON TABLE ana_pred_proyecciones IS 'Proyección que dejó cada entrenamiento: cuando llega el mes real, la pantalla la compara con lo observado (error real)';
COMMENT ON COLUMN ana_pred_proyecciones.entrenamiento_id IS 'Entrenamiento';
COMMENT ON COLUMN ana_pred_proyecciones.mes IS 'Mes proyectado (día 1)';
COMMENT ON COLUMN ana_pred_proyecciones.h IS 'Meses adelante del último mes de entrenamiento (1 a horizonte)';
COMMENT ON COLUMN ana_pred_proyecciones.valor IS 'Proyección (escenario base), acotada en 0';
COMMENT ON COLUMN ana_pred_proyecciones.inferior IS 'Límite inferior del IC 95 % (escenario inferior), acotado en 0';
COMMENT ON COLUMN ana_pred_proyecciones.superior IS 'Límite superior del IC 95 % (escenario superior)';

-- Privilegios mínimos del rol de la aplicación (IngestaPrivilegiosTest los verifica): la bitácora de entrenamientos y sus proyecciones
-- solo se consultan y se agregan (SELECT, INSERT; sin UPDATE ni DELETE: son traza).
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT ON ana_pred_entrenamientos, ana_pred_proyecciones TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
