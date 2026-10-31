-- Suite DIGSA · Módulo Correos, fase C5: retención de la bitácora de envíos (decisión del usuario del 2026-10-31, duda CO-4).
-- Cada envío se conserva completo 1 año (fecha, tipo, referencia, destinatarios, asunto, estado, intentos, último error y el
-- contenido), para que la auditoría y la interventoría vean qué se envió. Después, la purga diaria borra el envío completo. El código
-- MFA y el enlace de recuperación nunca se guardan (enmascarado, V90).

UPDATE par_parametros
SET valor = '365',
    descripcion = 'Bandeja de salida: días que se conservan completos (contenido incluido) los correos y SMS ya enviados, fallidos u omitidos antes de purgarlos (0 = no purgar)',
    fuente = 'Spec exportacion-alertas (bandeja de salida); decisión del usuario 2026-10-31 (CO-4)'
WHERE clave = 'bandeja_retencion_dias';
