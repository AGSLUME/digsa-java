-- Suite DIGSA · S2: ajustes de la revisión de los agentes (base de datos, seguridad y fallos silenciosos).

-- La notificación calculada junta los correos de la fuerza, del OPL, de la DIGSA y del punto: el tope de 15 es solo de la manual
-- (en el código). Se agrega el estado «parcial»: el servidor de correo rechazó a algunos destinatarios.
ALTER TABLE visitas_notificaciones
    DROP CONSTRAINT visitas_notificaciones_destinatarios_check,
    ADD CONSTRAINT visitas_notificaciones_destinatarios_check CHECK (cardinality(destinatarios) BETWEEN 1 AND 50),
    DROP CONSTRAINT visitas_notificaciones_estado_check,
    ADD CONSTRAINT visitas_notificaciones_estado_check CHECK (estado IN ('pendiente', 'enviada', 'parcial', 'fallida'));

-- Equipo: un orden por integrante (máximo 6: líder con 0 y hasta 5 de apoyo) y el líder siempre con el orden 0
ALTER TABLE visitas_auditores
    ADD CONSTRAINT uq_visitas_auditores_orden UNIQUE (visita_id, orden),
    ADD CONSTRAINT ck_visitas_auditores_lider_orden CHECK ((funcion = 'lider') = (orden = 0));

-- Número VS-AAAAMMDD-NNNN (se asigna en la misma transacción en que se crea la visita)
ALTER TABLE visitas ADD CONSTRAINT ck_visitas_numero CHECK (numero IS NULL OR numero ~ '^VS-[0-9]{8}-[0-9]{4,}$');

-- Borrar una fuerza revisa sus puntos (FK y «en uso»)
CREATE INDEX ix_puntos_fuerza ON puntos (fuerza);

-- La actividad de la sesión se escribe cada 30 s: espacio libre en las páginas para actualizaciones HOT
ALTER TABLE usuarios SET (fillfactor = 80);

-- Permisos del rol de la aplicación: renombrar perfiles (nombres configurables, respuesta de la DIGSA del 19-oct)
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT UPDATE (nombre, descripcion) ON perfiles TO %I', app);
END $$;
