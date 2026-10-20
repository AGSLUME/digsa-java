-- Suite DIGSA · permisos mínimos del rol de la aplicación (revisión de base de datos de S1).
-- Las migraciones corren con el rol dueño de las tablas (DIGSA_DB_MIGRACION_USUARIO) y la aplicación con otro rol
-- (DIGSA_DB_ROL_APP, por ejemplo digsa_app) que no puede borrar la bitácora ni cambiar la estructura.
-- Si el rol de la aplicación no está configurado (desarrollo local y pruebas, un solo rol) no se hace nada.
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RAISE NOTICE 'Sin rol de aplicación separado: no se asignan permisos';
        RETURN;
    END IF;
    EXECUTE format('REVOKE ALL ON SCHEMA public FROM PUBLIC');
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app);
    -- Bitácora: solo leer e insertar (se conserva toda, duda 7.5)
    EXECUTE format('GRANT SELECT, INSERT ON log_transacciones TO %I', app);
    EXECUTE format('GRANT USAGE ON SEQUENCE log_transacciones_id_seq TO %I', app);
    EXECUTE format('GRANT SELECT ON perfiles TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON usuarios, mfa_codigos TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON usuarios_perfiles TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
    -- Las tablas de las migraciones siguientes conceden sus permisos explícitamente en su propia migración
END $$;
