-- Roles adicionales parametrizables (Anexo 5, 1.12; sesión 2, E2). Decisión del usuario del 2026-10-22: un rol adicional tiene un perfil
-- base (fija el alcance de los datos) y un subconjunto de las pantallas de ese perfil. Los perfiles base (1 a 10) no cambian: su id es el
-- del enum Perfil. Los adicionales toman ids desde 100 y su base no puede ser el Administrador (1), el Auditor (3) ni Consulta (4).
ALTER TABLE perfiles
    ADD COLUMN base_id smallint REFERENCES perfiles (id) ON DELETE RESTRICT,
    ADD COLUMN creado_por text CHECK (char_length(creado_por) <= 60),
    ADD COLUMN creado timestamptz,
    ADD CONSTRAINT ck_perfiles_base CHECK ((id <= 10 AND base_id IS NULL) OR (id >= 100 AND base_id IN (2, 5, 6, 7, 8, 9, 10))),
    ADD CONSTRAINT ck_perfiles_nombre CHECK (char_length(nombre) BETWEEN 3 AND 60);

-- El nombre se compara sin distinguir mayúsculas (dos altas simultáneas de «Rol A» y «rol a» no pasan las dos)
CREATE UNIQUE INDEX uq_perfiles_nombre_ci ON perfiles (lower(nombre));

CREATE SEQUENCE perfiles_adicionales_id_seq AS smallint START WITH 100 MINVALUE 100 MAXVALUE 32000;

-- Pantallas de cada rol adicional: el nombre de la constante de reglas/Pantallas (p. ej. RIESGO)
CREATE TABLE perfiles_pantallas (
    perfil_id smallint NOT NULL REFERENCES perfiles (id) ON DELETE RESTRICT,
    pantalla  text NOT NULL CHECK (pantalla ~ '^[A-Z_]{2,40}$'),
    PRIMARY KEY (perfil_id, pantalla)
);

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT INSERT ON perfiles TO %I', app);
    EXECUTE format('GRANT UPDATE (activo) ON perfiles TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON perfiles_pantallas TO %I', app);
    EXECUTE format('GRANT USAGE ON SEQUENCE perfiles_adicionales_id_seq TO %I', app);
END $$;
