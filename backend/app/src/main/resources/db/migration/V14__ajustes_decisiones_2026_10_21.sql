-- Suite DIGSA · Ajustes por las decisiones del 2026-10-21 (docs/decisiones_2026-10-21.md)

-- D12: firmas nuevas en cada ronda del acta (la reapertura exige volver a firmar; las de la ronda anterior se conservan)
ALTER TABLE visitas_firmas ADD COLUMN ronda smallint NOT NULL DEFAULT 1 CHECK (ronda >= 1);
ALTER TABLE visitas_firmas DROP CONSTRAINT visitas_firmas_pkey, ADD PRIMARY KEY (visita_id, ronda, rol);
-- Las firmas existentes eran las de la última ronda (el registro se sobrescribía)
UPDATE visitas_firmas f SET ronda = v.reaperturas + 1 FROM visitas v WHERE v.id = f.visita_id AND v.reaperturas > 0;

-- D5: el ítem de habilitación (C00) en «No cumple» sigue siendo hallazgo, pero puede marcarse «habilitación en trámite»: queda
-- pendiente aportar el distintivo de habilitación o las actas de visita del ente habilitador
ALTER TABLE visitas_respuestas ADD COLUMN en_tramite boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT visitas_respuestas_tramite_nc CHECK (NOT en_tramite OR calificacion = 'NC');
ALTER TABLE visitas_hallazgos ADD COLUMN en_tramite boolean NOT NULL DEFAULT false;

-- Un reporte a la DIGSA se reclama antes de enviarlo: dos procesos no mandan el mismo correo
ALTER TABLE reportes_digsa DROP CONSTRAINT reportes_digsa_estado_check,
    ADD CONSTRAINT reportes_digsa_estado_check CHECK (estado IN ('pendiente', 'enviando', 'enviado', 'fallido'));
