-- Suite DIGSA · módulo de reportes, fase R3 (docs/especificaciones/reportes): alerta operativa «reporte lento» (Anexo 5, 2.7).
-- Un reporte que pasa el 80 % de su tiempo máximo (3 minutos para ver, 10 para exportar) o que se corta avisa al Administrador:
-- 2.7 obliga a informar por escrito a la DIGSA, y la aplicación da la evidencia (reporte, filtros y tiempo). No es crítica: va por
-- correo, sin SMS (canales según los anexos). Se agrega al catálogo del módulo de alertas sin tocar su código.

INSERT INTO alertas_criticas (tipo, nombre, descripcion, critica, sms, perfiles) VALUES
  ('reporte_lento', 'Reporte lento', 'Un reporte pasó el 80 % de su tiempo máximo (3 min en pantalla, 10 min al exportar) o se cortó (Anexo 5, 2.7)',
   false, false, '{ADMINISTRADOR}')
ON CONFLICT (tipo) DO NOTHING;
