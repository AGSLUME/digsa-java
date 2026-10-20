# puntos_anexo3_opl.json

Listado de puntos de dispensación del OPL según el Anexo No. 3 Técnico, proceso 173-MDN-COGFM-JEMCO-DIGSA-2026, numeral 6 "Ubicación y horarios de atención de los Establecimientos de Sanidad Militar".

## Fuente

- Texto: `LUME2\SUITE DIGSA\FUENTE DE VERDAD\md\ANEXO No. 3 TECNICO PROCESO PRINCIPAL.md`
- Cotejo: `FUENTE DE VERDAD\Respuestas Ante Duda\ANEXO No. 3 TECNICO PROCESO PRINCIPAL (1).pdf`, págs. 25-46 (con `pdftotext -layout`)
- Páginas:
  - ESM modalidad dispensación: págs. 25-32
  - Puntos alternos (FA): pág. 33
  - ESM modalidad suministro: págs. 34-39
  - Satélites: págs. 40-46

## Criterios

- `regional`: el número de la tabla se guarda como "Regional N". "N/A" (ARC, FAC y HOMIL) se guarda como null.
- `fuerza`: HOMIL figura como "N/A" en la tabla, así que se guarda como null.
- Las palabras partidas por salto de línea en las celdas se unieron (por ejemplo, BARRANQUILL/A pasa a BARRANQUILLA). Los errores de ortografía de la fuente se dejaron tal cual (NAVCIONAL, AEREPUERTO, ESCULEA, VIVTORIA, INFANTERÍIA, TRUJILLA, etc.).
- Satélites:
  - La tabla no trae dirección, así que `direccion` es null.
  - El `nivel` es "ENFERMERIA" (salvo BAEEV03, que trae "1").
  - La columna "sigla centralizador" está en celdas combinadas. Solo aparece en algunas filas y va en `observacion`.
- FA: el anexo solo exige ciudad y cantidad (más el requisito de que el punto Centro de Bogotá quede a 1 km o menos del HOMIL). El `nombre` es descriptivo porque el anexo no lo trae. Departamento, dirección y horario quedan null.

## Conteos (185 registros)

| Tipo / modalidad | EJC | ARC | FAC | sin fuerza | Total |
|---|---|---|---|---|---|
| ESM Dispensación | 54 | 11 | 8 | 1 (HOMIL) | 74 |
| FA (punto alterno) | - | - | - | 10 | 10 |
| ESM Suministro | 32 | 12 | 6 | - | 50 |
| ESM Satélite | 51 | - | - | - | 51 |
| **Total** | 137 | 23 | 14 | 11 | **185** |

Puntos alternos (FA): 3 en Bogotá (Norte, Centro y Sur) y 1 en cada una de estas ciudades: Medellín, Cali, Cartagena, Ibagué, Villavicencio, Barranquilla y Sincelejo.

## Filas dudosas

### Datos truncados o incompletos en la fuente (el PDF dice lo mismo)

- **BIVAR** (dispensación, p.27): el horario termina en "06:00 -" y no trae hora de cierre.
- **BAACA08** (dispensación, p.30): el horario termina en "SABADOS Y DOMINGOS 7:00 A" y no trae hora de cierre.
- **BIMAG** (dispensación, p.30): el horario dice solo "24 HORAS DOMINGO".
- **BAMAR** (satélite, p.46): el nombre está truncado y termina en "TC. ANTONIO".
- **BIROR** (dispensación, p.27): la dirección es solo "PUTUMAYO" y el nivel está vacío.
- **DMBUG** (dispensación, p.25) y **CACOM06** (suministro, p.39): nivel vacío.

### Posibles errores de copiado en el anexo

- **BIVEN** (dispensación, Cartago, p.30): tiene la misma dirección que BAS15 Quibdó ("KM 4 VIA PACURITA BARRIO OBAPO").
- **DMEFA** (FAC) y **DMBOG** (ARC) (dispensación, p.31): misma dirección y horario (Carrera 58 #9-67 Puente Aranda).
- **REGFE2** (satélite, p.45): la sigla dice 2, pero el nombre dice "No. 3".
- **BIVEL** (satélite, Regional 8, centralizador BAS17) y **BAACA18** (satélite, Regional 9, centralizador BAS18): la regional no coincide con la del centralizador en la tabla de dispensación.

### Ciudad y departamento invertidos en la tabla (se corrigieron)

- BITER09 (suministro, p.37)
- BITER28 (satélite, p.42)
- BIROJ (satélite, p.45)
- BIPIN (satélite, p.45)

### Horarios partidos entre dos filas, probablemente por celdas combinadas

Se reconstruyeron como "24 HORAS DOMINGO A DOMINGO" en cuatro pares de satélites:

- BAACA18 / BIGUP
- ESDEGUE / LIBERTADORES
- DIVFE / COEJC
- BAMGU / BITER01

### Filas partidas entre páginas o con encabezado mezclado

- **BAS02** (p.25): "BATALLON DE" quedó en una fila suelta.
- **BIMEJ** (p.34-35): la fila sigue en la página 35.
- **UFABA** (p.38): el encabezado quedó mezclado dentro de la fila.

### Siglas partidas en dos líneas

BAEEV13, CASPC1, BITER09, CACOM06 y LIBERTADORES. Se leyeron unidas.

### Otros

- **HONAC** aparece dos veces: "HONAC AMBULATORIA" y "HONAC HOSPITALARIA". Es el mismo hospital con dos puntos.
- **BAEEV03** (satélite): nivel "1" y horario con franja diurna en lugar de 24 h.
