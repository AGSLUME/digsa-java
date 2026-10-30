-- Suite DIGSA · Fase AN: analítica farmacéutica, A7.2 · exportación de los hechos de dispensación por periodo (duda AN.14;
-- docs/especificaciones/analitica-ingesta «Exportación de los hechos por periodo»).
--
-- ana_dispensaciones (unos 2 M de filas por mes) está fuera de la exportación completa (una transacción con tope de 10 minutos, AGS-75).
-- Aquí se entregan aparte: el Administrador pide un periodo (uno o varios meses), un trabajo en segundo plano escribe un .csv.gz por mes
-- en el almacén, cada mes en su propia transacción de solo lectura y en flujo, con su SHA-256; el LEEME resume el periodo, el corte, las
-- filas, la suma del valor y la huella de cada mes. Los archivos vencen (datos de salud seudonimizados, Ley 1581) y se borran.

CREATE TABLE ana_exportaciones_hechos (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    solicitada_por  bigint NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    desde           date NOT NULL CHECK (extract(day FROM desde) = 1),
    hasta           date NOT NULL CHECK (extract(day FROM hasta) = 1),
    corte           timestamptz NOT NULL,
    corte_cargue_id bigint REFERENCES ana_cargues (id) ON DELETE RESTRICT,
    cargues         bigint[] NOT NULL CHECK (array_position(cargues, NULL) IS NULL),
    estado          text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'generando', 'lista', 'fallida', 'vencida')),
    meses           int NOT NULL CHECK (meses BETWEEN 1 AND 600),
    meses_listos    int NOT NULL DEFAULT 0 CHECK (meses_listos >= 0),
    meses_fallidos  int NOT NULL DEFAULT 0 CHECK (meses_fallidos >= 0),
    filas           bigint NOT NULL DEFAULT 0 CHECK (filas >= 0),
    tamano          bigint NOT NULL DEFAULT 0 CHECK (tamano >= 0),
    leeme_ruta      text CHECK (leeme_ruta ~ '^[A-Za-z0-9_/-]+\.txt$'),
    version_esquema text CHECK (char_length(version_esquema) <= 50),
    error           text CHECK (char_length(error) <= 500),
    creado          timestamptz NOT NULL DEFAULT now(),
    iniciado        timestamptz,
    terminado       timestamptz,
    vence           timestamptz,
    CHECK (desde <= hasta),
    CHECK (meses_listos + meses_fallidos <= meses),
    CHECK (estado NOT IN ('lista', 'fallida') OR (terminado IS NOT NULL AND vence IS NOT NULL))
);
COMMENT ON TABLE ana_exportaciones_hechos IS 'Solicitudes de exportación de los hechos de dispensación por periodo (duda AN.14): quién, qué periodo, corte, estado y vencimiento';
COMMENT ON COLUMN ana_exportaciones_hechos.solicitada_por IS 'Administrador que la pidió';
COMMENT ON COLUMN ana_exportaciones_hechos.desde IS 'Primer mes del periodo (día 1)';
COMMENT ON COLUMN ana_exportaciones_hechos.hasta IS 'Último mes del periodo (día 1)';
COMMENT ON COLUMN ana_exportaciones_hechos.corte IS 'Instante de corte (el de la solicitud); los cargues que salen están congelados en cargues';
COMMENT ON COLUMN ana_exportaciones_hechos.corte_cargue_id IS 'Último cargue visible al corte (nulo si no había ninguno)';
COMMENT ON COLUMN ana_exportaciones_hechos.cargues IS 'Cargues visibles y CONFIRMADOS al pedir la exportación (foto de ana_cargues_visibles en la solicitud): todos los meses filtran con esta lista, así un cargue que se confirma mientras corre no entra en ningún mes y una reanudación da lo mismo';
COMMENT ON COLUMN ana_exportaciones_hechos.estado IS 'pendiente, generando, lista (todos los meses), fallida (algún mes falló; los demás se descargan) o vencida (archivos borrados)';
COMMENT ON COLUMN ana_exportaciones_hechos.meses IS 'Meses del periodo (un archivo por mes)';
COMMENT ON COLUMN ana_exportaciones_hechos.meses_listos IS 'Meses con su archivo generado (progreso)';
COMMENT ON COLUMN ana_exportaciones_hechos.meses_fallidos IS 'Meses que fallaron';
COMMENT ON COLUMN ana_exportaciones_hechos.filas IS 'Filas exportadas en total (suma de los meses listos)';
COMMENT ON COLUMN ana_exportaciones_hechos.tamano IS 'Bytes de los archivos en total';
COMMENT ON COLUMN ana_exportaciones_hechos.leeme_ruta IS 'LEEME en el almacén (al terminar); nulo antes, o al vencer cuando se comprobó su borrado';
COMMENT ON COLUMN ana_exportaciones_hechos.version_esquema IS 'Versión de la última migración aplicada al generar (LEEME)';
COMMENT ON COLUMN ana_exportaciones_hechos.error IS 'Mensaje genérico de la falla (el detalle queda en el registro del servidor)';
COMMENT ON COLUMN ana_exportaciones_hechos.creado IS 'Fecha y hora de la solicitud';
COMMENT ON COLUMN ana_exportaciones_hechos.iniciado IS 'Inicio de la generación';
COMMENT ON COLUMN ana_exportaciones_hechos.terminado IS 'Fin de la generación';
COMMENT ON COLUMN ana_exportaciones_hechos.vence IS 'Desde aquí los archivos se borran (digsa.analitica.exportacion-hechos.vigencia-horas)';
-- Una sola en curso por administrador (otra pestaña o un doble clic no encolan dos)
CREATE UNIQUE INDEX ux_ana_exp_hechos_en_curso ON ana_exportaciones_hechos (solicitada_por) WHERE estado IN ('pendiente', 'generando');
CREATE INDEX ix_ana_exp_hechos_usuario ON ana_exportaciones_hechos (solicitada_por, id DESC);
CREATE INDEX ix_ana_exp_hechos_corte_cargue ON ana_exportaciones_hechos (corte_cargue_id);
CREATE INDEX ix_ana_exp_hechos_pendientes ON ana_exportaciones_hechos (id) WHERE estado IN ('pendiente', 'generando');

CREATE TABLE ana_exportaciones_hechos_meses (
    exportacion_id bigint NOT NULL REFERENCES ana_exportaciones_hechos (id) ON DELETE RESTRICT,
    mes            date NOT NULL CHECK (extract(day FROM mes) = 1),
    estado         text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'generando', 'lista', 'fallida', 'vencida')),
    filas          bigint CHECK (filas >= 0),
    valor_total    numeric(22, 2) CHECK (valor_total >= 0),
    tamano         bigint CHECK (tamano >= 0),
    sha256         char(64),
    ruta           text CHECK (ruta ~ '^[A-Za-z0-9_/-]+_csv\.gz$'),
    error          text CHECK (char_length(error) <= 500),
    iniciado       timestamptz,
    terminado      timestamptz,
    duracion_ms    bigint CHECK (duracion_ms >= 0),
    PRIMARY KEY (exportacion_id, mes),
    CHECK (estado <> 'lista' OR (filas IS NOT NULL AND valor_total IS NOT NULL AND tamano IS NOT NULL AND sha256 IS NOT NULL AND ruta IS NOT NULL))
);
COMMENT ON TABLE ana_exportaciones_hechos_meses IS 'Un archivo por mes de cada exportación de hechos: estado (progreso), filas, suma del valor, tamaño y SHA-256';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.exportacion_id IS 'Exportación';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.mes IS 'Mes (día 1)';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.estado IS 'pendiente, generando, lista, fallida o vencida (archivo borrado)';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.filas IS 'Filas del CSV (sin el encabezado)';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.valor_total IS 'Suma de valor_total de las filas (cuadre con la base y los tableros)';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.tamano IS 'Bytes del .csv.gz';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.sha256 IS 'SHA-256 del .csv.gz tal como se descarga';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.ruta IS 'Archivo en el almacén; se anula solo cuando se comprobó su borrado (un mes fallido o vencido con ruta es un archivo por borrar)';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.error IS 'Mensaje genérico de la falla del mes';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.iniciado IS 'Inicio de la lectura del mes';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.terminado IS 'Fin de la lectura del mes';
COMMENT ON COLUMN ana_exportaciones_hechos_meses.duracion_ms IS 'Duración de la generación del mes (medición de rendimiento)';

-- Privilegios mínimos del rol de la aplicación (IngestaPrivilegiosTest los verifica): las solicitudes y sus meses se crean y cambian de
-- estado (sin DELETE: son la traza de quién exportó qué y cuándo; al vencer solo se borran los archivos).
DO $$
DECLARE
    app text := '${rol_app}';
BEGIN
    IF app = '' OR app = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON ana_exportaciones_hechos, ana_exportaciones_hechos_meses TO %I', app);
    EXECUTE format('GRANT SELECT ON flyway_schema_history TO %I', app);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
END $$;
