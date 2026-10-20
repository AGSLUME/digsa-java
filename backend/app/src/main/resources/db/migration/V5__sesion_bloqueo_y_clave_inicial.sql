-- Suite DIGSA · decisiones del 2026-10-20 (docs/decisiones_2026-10-20.md)
-- 7.1: se bloquea el segundo ingreso mientras la sesión abierta tenga actividad dentro del plazo de inactividad.
-- Prueba en sitio, ítem 2 y condición 12: contraseña inicial asignada por el administrador, con cambio obligatorio en la aplicación.
ALTER TABLE usuarios
    ADD COLUMN sesion_ultimo timestamptz,                         -- última actividad de la sesión vigente (cada 30 s como máximo)
    ADD COLUMN clave_inicial boolean NOT NULL DEFAULT false;      -- la asignó el administrador: se cambia al primer ingreso, sin correo
