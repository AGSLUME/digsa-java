-- Suite DIGSA · Fase AN: analítica farmacéutica, A7.3 · descarga diferida (duda AN.16; docs/especificaciones/analitica-tableros
-- «Descarga diferida cuando no cabe en la petición»).
--
-- El CSV de registros de origen de la trazabilidad (lo único cuya salida crece con los registros: las descargas de los tableros son
-- agregadas y siguen en la petición) con más registros de salida que el umbral (digsa.analitica.descargas-diferidas.umbral-filas,
-- 100 000) no se genera en la petición: se responde 202 con el id y un ejecutor en segundo plano lo genera con el mismo corte (congelado
-- al pedirlo), filtros, alcance y perfil, en una transacción READ ONLY REPEATABLE READ, y lo guarda en el almacén con su SHA-256. Queda en
-- «Mis descargas del día» del usuario hasta que vence (24 h) y se borra.

CREATE TABLE ana_descargas_diferidas (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    solicitada_por  bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    usuario_texto   text NOT NULL CHECK (char_length(usuario_texto) <= 200),
    perfil          text NOT NULL CHECK (char_length(perfil) <= 200),
    alcance         text NOT NULL CHECK (alcance ~ '^(nacional|fuerza:[A-Z0-9_]{1,20}|esm:[1-9][0-9]{0,17})$'),
    tablero         text NOT NULL CHECK (tablero ~ '^[a-z0-9_-]{1,40}$'),
    parametros      jsonb NOT NULL CHECK (jsonb_typeof(parametros) = 'object' AND length(parametros::text) <= 8000),
    corte_cargue_id bigint NOT NULL REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    registros       bigint NOT NULL CHECK (registros >= 0),
    estado          text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'generando', 'lista', 'fallida', 'vencida')),
    intentos        int NOT NULL DEFAULT 0 CHECK (intentos >= 0),
    ruta            text CHECK (ruta ~ '^[A-Za-z0-9_/-]+\.csv$'),
    tamano          bigint CHECK (tamano >= 0),
    sha256          char(64),
    huella          char(64),
    error           text CHECK (char_length(error) <= 500),
    creado          timestamptz NOT NULL DEFAULT now(),
    iniciado        timestamptz,
    terminado       timestamptz,
    vence           timestamptz,
    duracion_ms     bigint CHECK (duracion_ms >= 0),
    CHECK (estado <> 'lista' OR (ruta IS NOT NULL AND tamano IS NOT NULL AND sha256 IS NOT NULL AND vence IS NOT NULL)),
    CHECK (estado NOT IN ('lista', 'fallida') OR (terminado IS NOT NULL AND vence IS NOT NULL))
);
COMMENT ON TABLE ana_descargas_diferidas IS 'CSV de registros de origen que superan el umbral de registros y se generan en segundo plano (duda AN.16): quién, qué cifra, con qué corte, alcance y perfil, estado, archivo y vencimiento';
COMMENT ON COLUMN ana_descargas_diferidas.solicitada_por IS 'Usuario que la pidió (solo él la ve y la descarga)';
COMMENT ON COLUMN ana_descargas_diferidas.usuario_texto IS 'Nombre y usuario al pedirla (metadatos del archivo, como en la descarga en la petición)';
COMMENT ON COLUMN ana_descargas_diferidas.perfil IS 'Perfiles y roles al pedirla (metadatos 14.8 «mismo perfil»)';
COMMENT ON COLUMN ana_descargas_diferidas.alcance IS 'Alcance de datos congelado al pedirla (nacional, fuerza:EJC, esm:id); al generar y al descargar debe seguir siendo el del usuario';
COMMENT ON COLUMN ana_descargas_diferidas.tablero IS 'Código del tablero de la cifra rastreada';
COMMENT ON COLUMN ana_descargas_diferidas.parametros IS 'Parámetros de la consulta tal como llegaron en la URL, por lista blanca (generales, filtros y la celda), sin el corte; códigos, sin datos personales ni texto libre';
COMMENT ON COLUMN ana_descargas_diferidas.corte_cargue_id IS 'Corte congelado al pedirla: el diferido da lo mismo que la descarga en la petición en ese instante';
COMMENT ON COLUMN ana_descargas_diferidas.registros IS 'Registros de origen contados al pedirla (los que superaron el umbral)';
COMMENT ON COLUMN ana_descargas_diferidas.estado IS 'pendiente, generando, lista, fallida o vencida (archivo borrado)';
COMMENT ON COLUMN ana_descargas_diferidas.intentos IS 'Veces que el ejecutor la tomó; al pasar de 3 (una caída a mitad la devuelve a la cola) queda fallida';
COMMENT ON COLUMN ana_descargas_diferidas.ruta IS 'Archivo en el almacén; se anula solo cuando se comprobó su borrado (una fallida o vencida con ruta es un archivo por borrar)';
COMMENT ON COLUMN ana_descargas_diferidas.tamano IS 'Bytes del archivo';
COMMENT ON COLUMN ana_descargas_diferidas.sha256 IS 'SHA-256 del archivo tal como se descarga';
COMMENT ON COLUMN ana_descargas_diferidas.huella IS 'Huella de la cifra rastreada (la misma del rastreo y de la descarga en la petición con el mismo corte, filtros y alcance)';
COMMENT ON COLUMN ana_descargas_diferidas.error IS 'Mensaje genérico de la falla (el detalle queda en el registro del servidor)';
COMMENT ON COLUMN ana_descargas_diferidas.creado IS 'Fecha y hora de la solicitud';
COMMENT ON COLUMN ana_descargas_diferidas.iniciado IS 'Inicio de la última generación';
COMMENT ON COLUMN ana_descargas_diferidas.terminado IS 'Fin de la generación';
COMMENT ON COLUMN ana_descargas_diferidas.vence IS 'Desde aquí el archivo se borra (digsa.analitica.descargas-diferidas.vigencia-horas)';
COMMENT ON COLUMN ana_descargas_diferidas.duracion_ms IS 'Duración de la generación (medición de rendimiento)';
-- Una sola en curso por usuario (otra pestaña o un doble clic no encolan dos)
CREATE UNIQUE INDEX ux_ana_desc_dif_en_curso ON ana_descargas_diferidas (solicitada_por) WHERE estado IN ('pendiente', 'generando');
CREATE INDEX ix_ana_desc_dif_usuario ON ana_descargas_diferidas (solicitada_por, id DESC);
CREATE INDEX ix_ana_desc_dif_corte ON ana_descargas_diferidas (corte_cargue_id);
CREATE INDEX ix_ana_desc_dif_pendientes ON ana_descargas_diferidas (id) WHERE estado IN ('pendiente', 'generando');
CREATE INDEX ix_ana_desc_dif_por_borrar ON ana_descargas_diferidas (vence) WHERE ruta IS NOT NULL;

-- Privilegios mínimos del rol de la aplicación (sin DELETE: son la traza de quién descargó qué y cuándo; al vencer solo se borra el archivo)
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON ana_descargas_diferidas TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
