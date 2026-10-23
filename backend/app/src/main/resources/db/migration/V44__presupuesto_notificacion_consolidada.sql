-- Suite DIGSA · Presupuesto: notificación formal consolidada (F6, rendimiento). Cuando un mismo cambio (p. ej. la carga del mes) hace que
-- varias líneas de una fuerza crucen un umbral o se desvíen, sale una sola notificación con todas ellas en vez de una por línea (una carga
-- de 5.000 filas generaba miles de correos). lineas: las líneas que consolida; con una sola, sigue en linea_id.
ALTER TABLE presupuesto_notificaciones ADD COLUMN lineas bigint[] NOT NULL DEFAULT '{}' CHECK (cardinality(lineas) <= 5000),
    DROP CONSTRAINT presupuesto_notificaciones_check,
    ADD CONSTRAINT presupuesto_notificaciones_esm_linea CHECK (nivel <> 'esm' OR linea_id IS NOT NULL OR cardinality(lineas) > 0);
CREATE INDEX ix_presupuesto_notificaciones_lineas ON presupuesto_notificaciones USING gin (lineas);

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT ON presupuesto_notificaciones TO %I', app);
END $$;
