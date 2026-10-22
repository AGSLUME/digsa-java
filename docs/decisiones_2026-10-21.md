# Decisiones del usuario del 2026-10-21 (dudas bloqueantes)

Responden las dudas bloqueantes de `docs/dudas_pendientes.md`. Mandan sobre el PHP y sobre las recomendaciones anteriores.

| # | Duda | Decisión | Qué cambia |
|---|---|---|---|
| D1 | 17.1 Código MFA en la prueba grabada | Consultar el correo para tomar el código **no es un cambio de pantalla** | Nada. Se cierra la duda |
| D2 | 17.2 Dos equipos a la vez | Se valida que **un mismo usuario no pueda abrir sesión en 2 equipos** | Ya está hecho (decisión 7.1: el segundo ingreso se rechaza). Se cierra |
| D3 | 17.11 Dominios de los reportes | Aún no se saben; **son configurables** | Ya lo son (`DIGSA_DOMINIOS_REPORTES`). Se cierra |
| D4 | 18.11 / 19.6 Hora militar | **Sí:** control propio que siempre muestra 24 h | Ajustes |
| D5 | 21.12 Habilitación en trámite | **Es hallazgo**, con la opción de marcar que la habilitación **está en trámite**; queda pendiente aportar el distintivo de habilitación o las actas de visita del ente habilitador | Ajustes |
| D6 | 22.6 Quién recalifica en el seguimiento | **El mismo auditor** que encontró el hallazgo | Ajustes |
| D7 | 22.3 La DIGSA aprueba prórrogas | Revisar la fuente de verdad; si no dice nada, **dejarlo como estaba** | Se deja como estaba (ver nota) |
| D8 | 22.1 Fecha con la prórroga solicitada | De acuerdo con la recomendación | Nada (ya es así) |
| D9 | 22.2 Cuándo se reporta el plan incumplido | **En cada seguimiento con la acción vencida** | Ajustes |
| D10 | 22.9 Firma tras el plan incumplido | De acuerdo con la recomendación | Nada (ya es así) |
| D11 | 21.4 Firma con objeciones sin decidir | **Definitivamente no** | Ajustes: el OPL no firma mientras haya objeciones sin decidir |
| D12 | 19.4 Firmas al reabrir | **Exigir firmas nuevas** en cada ronda | Ajustes |
| D13 | 19.2 / 21.19 Obligaciones de la minuta | **Sí o sí por OCR** | Fase propia (necesita las minutas reales) |
| D14 | 16.1 a 16.4 Despliegue | Desarrollo **en contenedores Docker**, para desplegar en cualquier servidor | Ajustes: Dockerfile y docker-compose |

## Nota sobre D7 (la DIGSA aprueba prórrogas)

En la fuente de verdad (Anexo 3, «Planteamiento del plan de mejoramiento», p. 11):
- La **interventoría** define, de manera motivada, el plazo mayor del plan cuando la naturaleza o complejidad del hallazgo lo justifica.
- A la DIGSA se le reporta el incumplimiento.
- La fuente de verdad no regula la aprobación de prórrogas por la DIGSA.

Por eso se deja como estaba: decisión 12.1 del 2026-10-20, con la aprobación del coordinador y de la DIGSA.
