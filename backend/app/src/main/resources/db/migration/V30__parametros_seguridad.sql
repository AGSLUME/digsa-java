-- Parámetros de seguridad que edita el Administrador desde Usuarios (sesión 2: usuarios, roles y logs).
-- Anexo 5, 3.2: «TIME OUT (tiempo de inactividad) de diez (10) minutos parametrizable y ajustable». Sin fila, vale la variable de
-- entorno DIGSA_SESION_MINUTOS (10 por defecto). Aparte de par_parametros: esos los edita la coordinación en Catálogos.
CREATE TABLE seg_parametros (
    clave           text PRIMARY KEY CHECK (clave ~ '^[a-z_]{3,40}$'),
    valor           text NOT NULL CHECK (char_length(valor) <= 100),
    actualizado_por text CHECK (char_length(actualizado_por) <= 60),
    actualizado     timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON seg_parametros TO %I', app);
END $$;
