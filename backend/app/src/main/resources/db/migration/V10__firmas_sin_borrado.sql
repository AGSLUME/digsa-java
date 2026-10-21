-- Suite DIGSA · S3: las firmas del acta son evidencia. El código solo las crea o las reemplaza (la del auditor líder al corregir
-- su firma antes de enviar); el rol de la aplicación no las borra (revisión de base de datos de S3).
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('REVOKE DELETE ON visitas_firmas FROM %I', app);
END $$;
