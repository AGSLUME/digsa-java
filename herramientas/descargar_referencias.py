#!/usr/bin/env python3
"""Descarga las referencias oficiales públicas de datos.gov.co a una carpeta local (analitica-homologacion; fuentes_oficiales.md §6).

La homologación NUNCA consulta internet: este script se corre aparte (en un equipo con salida a internet), deja los CSV en una carpeta y
luego se cargan por la pantalla «Homologación › Fuentes oficiales» con la fecha de actualización de la fuente que el script anota en
manifiesto.json. Usa la API SODA de Socrata, paginada (`$limit`/`$offset` ordenado por `:id`), y escribe un solo CSV por conjunto con el
encabezado de los fieldName (expediente, consecutivocum, …), que es el formato que valida la carga.

Conjuntos (fuentes_oficiales.md §1.1, §2.3 y §5.1):
    INVIMA_CUM_VIGENTES       i7cb-raxc    CUM vigentes (CC BY-SA 4.0, INVIMA)
    INVIMA_CUM_RENOVACION     vgr4-gemg    CUM en trámite de renovación
    INVIMA_CUM_VENCIDOS       vwwf-4ftk    CUM vencidos
    INVIMA_CUM_OTROS          spzp-dfuc    CUM en otros estados
    MINSALUD_PRECIO_REGULADO  nauz-qkjw    Precio máximo de venta regulado (CNPMDM)
    INVIMA_VITALES            sdmr-tfmf    Medicamentos vitales no disponibles
La CIE-10 de SISPRO no tiene API (se pide por correo desde la consulta de tablas de referencia) y las listas de estrecho margen y control
especial solo existen en PDF: se cargan por la pantalla tal como lleguen.

Solo biblioteca estándar. Un app token de Socrata (opcional, gratuito) evita el límite de peticiones: variable SOCRATA_APP_TOKEN (nunca en
el repositorio).

Uso:
    py herramientas/descargar_referencias.py CARPETA [--solo INVIMA_CUM_VIGENTES,INVIMA_CUM_OTROS] [--pagina 50000] [--reintentos 5]

Escribe CARPETA/<CODIGO>_<aaaa-mm-dd>.csv (la fecha es la de actualización de la fuente) y CARPETA/manifiesto.json con, por conjunto: el
dataset, la URL, la fecha de actualización de la fuente (rowsUpdatedAt, hora de Colombia), las filas, el SHA-256 del archivo y la fecha
de descarga.
"""

import argparse
import csv
import datetime as dt
import hashlib
import io
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DOMINIO = "https://www.datos.gov.co"
CONJUNTOS = {
    "INVIMA_CUM_VIGENTES": "i7cb-raxc",
    "INVIMA_CUM_RENOVACION": "vgr4-gemg",
    "INVIMA_CUM_VENCIDOS": "vwwf-4ftk",
    "INVIMA_CUM_OTROS": "spzp-dfuc",
    "MINSALUD_PRECIO_REGULADO": "nauz-qkjw",
    "INVIMA_VITALES": "sdmr-tfmf",
}
COLOMBIA = dt.timezone(dt.timedelta(hours=-5))  # sin horario de verano
AGENTE = "SuiteDIGSA-descargar-referencias/1.0 (+datos abiertos; uso institucional)"


def pedir(url: str, reintentos: int) -> bytes:
    """GET con reintentos y espera creciente ante 429 y 5xx (el límite de Socrata sin app token no está documentado: §7)."""
    cabeceras = {"User-Agent": AGENTE, "Accept": "*/*"}
    token = os.environ.get("SOCRATA_APP_TOKEN")
    if token:
        cabeceras["X-App-Token"] = token
    espera = 2.0
    for intento in range(1, reintentos + 1):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=cabeceras), timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 502, 503, 504) or intento == reintentos:
                raise
        except (urllib.error.URLError, TimeoutError):
            if intento == reintentos:
                raise
        print(f"  reintento {intento} en {espera:.0f} s…", file=sys.stderr)
        time.sleep(espera)
        espera *= 2
    raise RuntimeError("inalcanzable")


def metadatos(dataset: str, reintentos: int) -> dict:
    """Metadatos del conjunto (api/views/<id>.json): nombre, licencia y rowsUpdatedAt (época en segundos)."""
    return json.loads(pedir(f"{DOMINIO}/api/views/{dataset}.json", reintentos).decode("utf-8"))


def descargar(codigo: str, dataset: str, carpeta: pathlib.Path, pagina: int, reintentos: int) -> dict:
    meta = metadatos(dataset, reintentos)
    actualizado = meta.get("rowsUpdatedAt") or meta.get("viewLastModified")
    fecha_corte = dt.datetime.fromtimestamp(int(actualizado), COLOMBIA).date().isoformat() if actualizado else None
    destino = carpeta / f"{codigo}_{fecha_corte or 'sin_fecha'}.csv"
    temporal = destino.with_suffix(".csv.parcial")
    filas = 0
    encabezado = None
    with open(temporal, "w", encoding="utf-8", newline="") as salida:
        escritor = csv.writer(salida, lineterminator="\r\n")
        desplazamiento = 0
        while True:
            consulta = urllib.parse.urlencode({"$limit": pagina, "$offset": desplazamiento, "$order": ":id"})
            cuerpo = pedir(f"{DOMINIO}/resource/{dataset}.csv?{consulta}", reintentos).decode("utf-8-sig")
            lector = csv.reader(io.StringIO(cuerpo, newline=""))
            cabecera = next(lector, None)
            if cabecera is None:
                break
            if encabezado is None:
                encabezado = cabecera
                escritor.writerow(encabezado)
            elif cabecera != encabezado:
                raise RuntimeError(f"{codigo}: el encabezado cambió entre páginas ({cabecera} ≠ {encabezado})")
            en_pagina = 0
            for registro in lector:
                escritor.writerow(registro)
                en_pagina += 1
            filas += en_pagina
            print(f"  {codigo}: {filas} filas", file=sys.stderr)
            if en_pagina < pagina:
                break
            desplazamiento += pagina
    huella = hashlib.sha256()
    with open(temporal, "rb") as f:
        for bloque in iter(lambda: f.read(1 << 20), b""):
            huella.update(bloque)
    temporal.replace(destino)
    return {
        "codigo": codigo,
        "dataset": dataset,
        "url": f"{DOMINIO}/resource/{dataset}.csv",
        "nombre": meta.get("name"),
        "licencia": (meta.get("license") or {}).get("name"),
        "fechaCorte": fecha_corte,
        "filas": filas,
        "archivo": destino.name,
        "sha256": huella.hexdigest(),
        "descargado": dt.datetime.now(COLOMBIA).isoformat(timespec="seconds"),
    }


def main() -> int:
    p = argparse.ArgumentParser(description="Descarga las referencias oficiales de datos.gov.co para cargarlas por la pantalla.")
    p.add_argument("carpeta", help="carpeta de salida (se crea si no existe)")
    p.add_argument("--solo", help="códigos separados por coma (por defecto, todos)")
    p.add_argument("--pagina", type=int, default=50000, help="filas por petición (máximo de Socrata: 50000)")
    p.add_argument("--reintentos", type=int, default=5)
    a = p.parse_args()
    codigos = [c.strip() for c in a.solo.split(",")] if a.solo else list(CONJUNTOS)
    desconocidos = [c for c in codigos if c not in CONJUNTOS]
    if desconocidos:
        p.error(f"códigos desconocidos: {', '.join(desconocidos)} (válidos: {', '.join(CONJUNTOS)})")
    if not 1 <= a.pagina <= 50000:
        p.error("--pagina debe estar entre 1 y 50000")
    carpeta = pathlib.Path(a.carpeta)
    carpeta.mkdir(parents=True, exist_ok=True)
    manifiesto_ruta = carpeta / "manifiesto.json"
    manifiesto = json.loads(manifiesto_ruta.read_text(encoding="utf-8")) if manifiesto_ruta.exists() else {}
    fallas = 0
    for codigo in codigos:
        print(f"Descargando {codigo} ({CONJUNTOS[codigo]})…", file=sys.stderr)
        try:
            manifiesto[codigo] = descargar(codigo, CONJUNTOS[codigo], carpeta, a.pagina, a.reintentos)
        except Exception as e:  # noqa: BLE001 - se informa y se sigue con los demás conjuntos
            fallas += 1
            print(f"  ERROR en {codigo}: {e}", file=sys.stderr)
    manifiesto_ruta.write_text(json.dumps(manifiesto, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Manifiesto: {manifiesto_ruta}", file=sys.stderr)
    return 1 if fallas else 0


if __name__ == "__main__":
    sys.exit(main())
