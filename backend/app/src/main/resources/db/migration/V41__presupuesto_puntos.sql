-- Suite DIGSA · Presupuesto: ESM centralizador de cada satélite (Anexo 3 del OPL §27.2, L3356: los satélites «dependen presupuestalmente
-- de un establecimiento centralizador modalidad dispensación»). La ejecución de un satélite se imputa a la línea de su centralizador.
-- Nadie ha entregado el mapa (duda P.6): se registra desde el módulo de presupuesto.
ALTER TABLE puntos ADD COLUMN centralizador_id bigint REFERENCES puntos (id) ON DELETE RESTRICT,
    ADD CONSTRAINT puntos_centralizador_distinto CHECK (centralizador_id IS NULL OR centralizador_id <> id);
CREATE INDEX ix_puntos_centralizador ON puntos (centralizador_id) WHERE centralizador_id IS NOT NULL;
