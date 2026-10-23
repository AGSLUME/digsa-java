-- Suite DIGSA · Presupuesto: tipos de alerta y parámetros (Anexo 2 L741: notificación «formal y electrónica» = correo, sin SMS).
-- Las alertas del catálogo llegan a los perfiles DIGSA y Coordinador; la notificación formal a la Sanidad de cada fuerza sale aparte, por
-- la bandeja de salida, al buzón de par_fuerzas.correos (el catálogo de alertas no filtra por fuerza: decisión del usuario 2026-10-22).
INSERT INTO alertas_criticas (tipo, nombre, descripcion, critica, sms, perfiles) VALUES
  ('presupuesto_umbral', 'Umbral de ejecución presupuestal', 'Un ESM alcanzó el 25, 50, 75 o 100 % del presupuesto de una modalidad', false, false,
   '{DIGSA,COORDINADOR}'),
  ('presupuesto_desviacion', 'Desviación frente a la proyección', 'La ejecución de un ESM, una fuerza o el país se apartó de la proyección de consumo',
   false, false, '{DIGSA,COORDINADOR}')
ON CONFLICT (tipo) DO NOTHING;

-- No editables desde Catálogos: se editan desde el módulo de presupuesto, que valida su formato (como los pesos del riesgo, V16).
-- El buzón de la DIGSA es el parámetro existente correo_digsa.
INSERT INTO par_parametros (clave, valor, descripcion, fuente, editable) VALUES
  ('presupuesto_base_umbral', 'facturado', 'Presupuesto: cifra sobre la que se calculan los umbrales del 25, 50, 75 y 100 % (facturado o reconocido)',
   'Decisión del usuario 2026-10-22; Anexo 2 L741', false),
  ('presupuesto_tolerancia_exceso', '10,00', 'Presupuesto: % sobre la proyección desde el que la ejecución es exceso',
   'Spec presupuesto (duda P.11); Anexo 5 §4.6', false),
  ('presupuesto_tolerancia_subejecucion', '20,00', 'Presupuesto: % bajo la proyección desde el que la ejecución es subejecución',
   'Spec presupuesto (duda P.11); Anexo 5 §4.6', false),
  ('presupuesto_consecutivo_prefijo', 'PRE', 'Presupuesto: prefijo del consecutivo de las notificaciones formales', 'Spec presupuesto', false)
ON CONFLICT (clave) DO NOTHING;
