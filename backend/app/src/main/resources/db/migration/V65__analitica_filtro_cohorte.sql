-- Suite DIGSA · Fase AN: analítica farmacéutica, A7.1 · cohorte, GCF y GRC como filtro de los tableros 1–12 y como dimensión de la
-- predicción (dudas AN.22 y AN.30; docs/especificaciones/analitica-tableros «Filtros» y analitica-cohortes-alertas «Serie en el tiempo,
-- comparación y composición (P5)»).
--
-- El filtro restringe los hechos a los pacientes miembros de la cohorte EN EL MES del hecho, con la membresía del MISMO corte
-- (ana_cohortes_miembros, paciente como entero): semijoin por (mes, paciente) contra ana_hechos_tablero, que el planificador resuelve por
-- hash sobre los miembros de UNA versión y UN corte (la llave primaria (version_id, corte_cargue_id, mes, paciente_id) los delimita por
-- rango; no hace falta otro índice). GCF y GRC son particiones: hasta A7 su membresía no se guardaba (solo la serie por grupo); desde aquí
-- se guarda con el grupo de cada paciente y mes para filtrar por grupo. Retención igual que la membresía de las cohortes (últimos
-- cortes de cada versión, `digsa.analitica.riesgo.cortes-con-membresia`, AN.19).

ALTER TABLE ana_cohortes_miembros ADD COLUMN grupo text COLLATE "C" NOT NULL DEFAULT '' CHECK (char_length(grupo) <= 20);
COMMENT ON COLUMN ana_cohortes_miembros.grupo IS 'Grupo del paciente en el mes en las particiones (GCF: ATC nivel 2, SIN_ATC o SIN_GASTO; GRC: GRC1 a GRC4); vacío en las cohortes';

-- Los cortes calculados antes de V65 marcaban la membresía de GCF y GRC como conservada (miembros = true) sin haber escrito sus filas: el
-- filtro saldría vacío (cifras en cero) en vez de avisar. Se marcan sin membresía; el filtro responde 422 «solo conserva su serie mensual»
-- hasta el próximo recálculo de ese corte («Recalcular» o un cargue nuevo), que la escribe y vuelve a marcarla.
UPDATE ana_cohortes_resultados r SET miembros = false
FROM ana_cohortes_versiones v
WHERE v.id = r.version_id AND v.criterios ->> 'tipo' = 'particion';

-- Predicción por cohorte, GCF y GRC (AN.30): la dimensión admite los tres y el entrenamiento guarda la versión de la cohorte con que se
-- armó la serie (una versión nueva es otra serie; la huella SHA-256 de la serie también cambia).
ALTER TABLE ana_pred_entrenamientos DROP CONSTRAINT ana_pred_entrenamientos_dimension_check;
ALTER TABLE ana_pred_entrenamientos ADD CONSTRAINT ana_pred_entrenamientos_dimension_check
    CHECK (dimension IN ('atc', 'principio', 'cie10', 'cohorte', 'gcf', 'grc'));
ALTER TABLE ana_pred_entrenamientos ADD COLUMN cohorte_version_id bigint REFERENCES ana_cohortes_versiones (id) ON DELETE RESTRICT;
ALTER TABLE ana_pred_entrenamientos ADD CONSTRAINT ck_ana_pred_entrenamientos_cohorte_version
    CHECK ((cohorte_version_id IS NOT NULL) = coalesce(dimension IN ('cohorte', 'gcf', 'grc'), false));
COMMENT ON COLUMN ana_pred_entrenamientos.dimension IS 'Dimensión opcional de la serie: atc (por prefijo, el nivel lo da el largo), principio, cie10 (por prefijo), cohorte (código), gcf o grc (grupo de la partición)';
COMMENT ON COLUMN ana_pred_entrenamientos.cohorte_version_id IS 'Versión de la cohorte (o de la partición GCF/GRC) con cuya membresía del corte se armó la serie; nulo en las demás dimensiones';
CREATE INDEX ix_ana_pred_entrenamientos_cohorte_version ON ana_pred_entrenamientos (cohorte_version_id) WHERE cohorte_version_id IS NOT NULL;

-- Privilegios mínimos del rol de la aplicación, los mismos de V63 y V64 (IngestaPrivilegiosTest los verifica): no se agrega ninguno.
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON ana_cohortes_miembros TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT ON ana_pred_entrenamientos TO %I', app);
END $$;
