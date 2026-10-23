-- Suite DIGSA · filas por tabla, para comparar un respaldo con su restauración (Anexo 5, 3.6: «se verifique la restauración»).
--
-- Uso (lo corren docker/respaldar.sh y docker/restaurar.sh; también a mano):
--   psql -X -q -At -F ';' -U <dueño> -d digsa -f herramientas/verificar_restauracion.sql
-- Salida: una línea «tabla;filas» por cada tabla del esquema public (incluidas las particiones de la bitácora), en orden
-- alfabético. Dos salidas iguales = mismas filas en cada tabla. Solo cuenta: no muestra datos personales.

SELECT format('%s;%s', c.relname,
              (xpath('/row/n/text()',
                     query_to_xml(format('SELECT count(*) AS n FROM ONLY %I.%I', n.nspname, c.relname), false, true, '')))[1]::text)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind = 'r'
ORDER BY c.relname;
