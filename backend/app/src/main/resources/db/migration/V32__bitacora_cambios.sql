-- Valores de antes y después de cada modificación, completos y estructurados (Anexo 5, prueba en sitio ítem 15 b: «la información
-- detallada de la transacción permite hacer la trazabilidad de las operaciones de eliminación, modificación e ingreso»; decisión del
-- 2026-10-20). Antes iban solo en el texto de la descripción, cortados a 40 caracteres por valor y 500 en total. Sesión 2, E3.
-- Formato: [{"campo": "...", "antes": "...", "despues": "...", "oculto": false}, ...]; los datos personales (Ley 1581) van con
-- "oculto": true y sin valores. La bitácora se conserva completa (decisión 7.5): el rol de la aplicación sigue con solo SELECT e INSERT.
ALTER TABLE log_transacciones
    ADD COLUMN cambios jsonb CHECK (cambios IS NULL OR (jsonb_typeof(cambios) = 'array' AND octet_length(cambios::text) <= 20000));

-- Filtro por módulo en la pantalla de logs (siempre con el rango de fechas) y lista de módulos por saltos (RepositorioLogs.modulos)
-- Filtros exactos por módulo y por tipo con el orden y la paginación por llave (fecha, id): no recorren todo el periodo
CREATE INDEX ix_log_modulo_fecha ON log_transacciones (modulo, fecha, id);
CREATE INDEX ix_log_tipo_fecha ON log_transacciones (tipo, fecha, id);
