-- Suite DIGSA · Fase 8: propuesta de agenda y cobertura (docs/especificaciones/propuesta-agenda; decisiones 13, 32 y 13.4 del
-- 2026-10-20: los ciclos anuales por año de contrato). Nada se programa ni se notifica hasta confirmar.

CREATE TABLE propuestas_agenda (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ciclo          text NOT NULL CHECK (ciclo IN ('linea_base', 'anual', 'farmacia', 'central')),
    anio_contrato  smallint CHECK (anio_contrato >= 1),             -- nulo en la línea base
    periodo_desde  date NOT NULL,
    hasta          date NOT NULL,
    estado         text NOT NULL DEFAULT 'borrador' CHECK (estado IN ('borrador', 'confirmada', 'descartada')),
    resumen        jsonb NOT NULL DEFAULT '{}',
    creado_por     bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    creado         timestamptz NOT NULL DEFAULT now(),
    confirmado_por bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    confirmado     timestamptz,
    CHECK (hasta >= periodo_desde),
    CHECK ((estado = 'confirmada') = (confirmado IS NOT NULL)),
    CHECK ((confirmado IS NULL) = (confirmado_por IS NULL)),
    CHECK ((ciclo = 'linea_base') = (anio_contrato IS NULL))
);
-- Un solo borrador por ciclo (y año de contrato)
CREATE UNIQUE INDEX ux_propuestas_borrador ON propuestas_agenda (ciclo, coalesce(anio_contrato, 0)) WHERE estado = 'borrador';
CREATE INDEX ix_propuestas_creado_por ON propuestas_agenda (creado_por);
CREATE INDEX ix_propuestas_confirmado_por ON propuestas_agenda (confirmado_por);

CREATE TABLE propuestas_agenda_items (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    propuesta_id bigint NOT NULL REFERENCES propuestas_agenda (id) ON DELETE RESTRICT,
    punto_id     bigint NOT NULL REFERENCES puntos (id) ON DELETE RESTRICT,
    auditor_id   bigint REFERENCES usuarios (id) ON DELETE RESTRICT,
    fecha        date,
    fijo         boolean NOT NULL DEFAULT false,
    km           integer CHECK (km >= 0),
    motivo       text CHECK (char_length(motivo) <= 200),
    alertas      jsonb NOT NULL DEFAULT '[]',
    visita_id    bigint REFERENCES visitas (id) ON DELETE RESTRICT,
    resultado    text CHECK (char_length(resultado) <= 500),
    UNIQUE (propuesta_id, punto_id)
);
CREATE INDEX ix_propuestas_items_auditor ON propuestas_agenda_items (auditor_id, fecha);
CREATE INDEX ix_propuestas_items_punto ON propuestas_agenda_items (punto_id);
CREATE UNIQUE INDEX ux_propuestas_items_visita ON propuestas_agenda_items (visita_id) WHERE visita_id IS NOT NULL;

-- Otros auditores de la fila (máximo 5, sin el líder)
CREATE TABLE propuestas_agenda_items_otros (
    item_id    bigint NOT NULL REFERENCES propuestas_agenda_items (id) ON DELETE RESTRICT,
    usuario_id bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    orden      smallint NOT NULL CHECK (orden BETWEEN 1 AND 5),
    PRIMARY KEY (item_id, usuario_id),
    UNIQUE (item_id, orden)
);
CREATE INDEX ix_propuestas_otros_usuario ON propuestas_agenda_items_otros (usuario_id);

-- Las filas de una propuesta confirmada o descartada son evidencia de qué se programó: solo se borran (al recalcular) las de un borrador
CREATE FUNCTION propuestas_solo_borrador() RETURNS trigger LANGUAGE plpgsql AS $f$
DECLARE
    prop bigint;
BEGIN
    IF TG_TABLE_NAME = 'propuestas_agenda_items_otros' THEN
        SELECT propuesta_id INTO prop FROM propuestas_agenda_items WHERE id = OLD.item_id;
    ELSE
        prop := OLD.propuesta_id;
    END IF;
    IF EXISTS (SELECT 1 FROM propuestas_agenda WHERE id = prop AND estado <> 'borrador') THEN
        RAISE EXCEPTION 'Las filas de una propuesta que no es borrador no se borran';
    END IF;
    RETURN OLD;
END
$f$;
CREATE TRIGGER tg_propuestas_items_sin_borrado BEFORE DELETE ON propuestas_agenda_items FOR EACH ROW EXECUTE FUNCTION propuestas_solo_borrador();
CREATE TRIGGER tg_propuestas_otros_sin_borrado BEFORE DELETE ON propuestas_agenda_items_otros FOR EACH ROW EXECUTE FUNCTION propuestas_solo_borrador();

DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON propuestas_agenda TO %I', app);
    -- Las filas de un borrador se reemplazan al recalcular; las de una propuesta confirmada son evidencia y no se borran (lo vigila el servicio)
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON propuestas_agenda_items, propuestas_agenda_items_otros TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
