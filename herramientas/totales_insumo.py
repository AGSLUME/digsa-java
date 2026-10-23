#!/usr/bin/env python3
"""Totales del insumo de dispensación del OPL calculados FUERA del sistema (hoja de ruta, preparación 13; A5 14.5).

Lee el archivo de la prueba en flujo (CSV o TXT UTF-8 con o sin BOM, «;» o «,» detectado; también .gz), aplica las MISMAS reglas de
rechazo de la ingesta (reglas/AnaliticaIngesta y ValidadorStaging) y escribe el mismo JSON que el generador sintético
(totales_esperados_<filas>.json): recibidos, procesados, rechazados, rechazos por causa, ESM sin correspondencia y gasto, unidades,
dispensaciones y pacientes distintos en total y por fuerza, ESM, modalidad, ATC nivel 1, mes, CUM y CIE-10.

Solo biblioteca estándar (sin pandas). No imprime ni guarda documentos: los pacientes distintos se cuentan con el documento
normalizado en memoria.

Uso:
    py herramientas/totales_insumo.py ARCHIVO [--salida totales.json] [--hoy AAAA-MM-DD] [--desde AAAA-MM-DD --hasta AAAA-MM-DD]
        [--esm-conocidos BAS02,BICOR,... | --esm-conocidos @siglas.txt] [--mapeo paciente=NUM_DOC,fecha=FECHA_DISPENSACION,...]

--mapeo asigna columnas a campos con las claves de la pantalla de mapeo (paciente, tipoDocumento, fuerza, esm, modalidad, fecha,
medicamento, cum, cantidad, valor, valorUnitario, principioActivo, atc, cie10, profesional, especialidad, tipoFormula, numeroFormula,
idDispensacion, item, regional, anioNacimiento, fechaNacimiento, grupoEdad, codigoSsfm). Sin --mapeo se usan los encabezados del
generador sintético. Las columnas no mapeadas no cuentan (como «No se usa» en la pantalla).
"""

import argparse
import csv
import datetime as dt
import gzip
import io
import json
import re
import sys
import unicodedata
from decimal import Decimal
from functools import lru_cache

MAPEO_GENERADOR = {
    "tipoDocumento": "TIPO_DOC", "paciente": "NUM_DOC", "fechaNacimiento": "FECHA_NAC", "fuerza": "FUERZA", "esm": "COD_ESM",
    "modalidad": "MODALIDAD", "fecha": "FECHA_DISPENSACION", "medicamento": "DESC_MEDICAMENTO", "cum": "CUM",
    "principioActivo": "PRINCIPIO_ACTIVO", "atc": "ATC", "cie10": "CIE10", "cantidad": "CANTIDAD", "valorUnitario": "VALOR_UNITARIO",
    "valor": "VALOR_TOTAL", "profesional": "PROFESIONAL", "tipoFormula": "TIPO_FORMULA", "numeroFormula": "NUM_FORMULA",
}

FUERZAS_CATALOGO = {"EJC", "ARC", "FAC", "NA"}
FUERZAS = {
    "EJC": "EJC", "EJERCITO": "EJC", "EJERCITO NACIONAL": "EJC", "EJERCITO NACIONAL DE COLOMBIA": "EJC",
    "EJERCITO NACIONAL DE COLOMBIA EJC": "EJC", "EJE": "EJC", "ARC": "ARC", "ARMADA": "ARC", "ARMADA NACIONAL": "ARC",
    "ARMADA DE LA REPUBLICA DE COLOMBIA": "ARC", "ARMADA DE LA REPUBLICA DE COLOMBIA ARC": "ARC", "FAC": "FAC", "FUERZA AEREA": "FAC",
    "FUERZA AEREA COLOMBIANA": "FAC", "FUERZA AEROESPACIAL": "FAC", "FUERZA AEROESPACIAL COLOMBIANA": "FAC",
    "FUERZA AEROESPACIAL COLOMBIANA FAC": "FAC", "NA": "NA", "NO APLICA": "NA",
}
MODALIDADES_CATALOGO = {"Dispensación", "Suministro", "Consignación", "Satélite"}
MODALIDADES = {
    "DISPENSACION": "Dispensación", "DISP": "Dispensación", "D": "Dispensación", "SUMINISTRO": "Suministro", "SUM": "Suministro",
    "S": "Suministro", "CONSIGNACION": "Consignación", "CONS": "Consignación", "SATELITE": "Satélite", "SAT": "Satélite",
}
# Orden de los campos que se guardan (el mismo de AnaliticaIngesta.Campo); el tipo de documento va dentro del paciente
CAMPOS_GUARDADOS = ["paciente", "tipoDocumento", "fuerza", "esm", "modalidad", "fecha", "medicamento", "cum", "cantidad", "valor",
                    "principioActivo", "atc", "cie10", "valorUnitario", "codigoSsfm", "profesional", "especialidad", "tipoFormula",
                    "numeroFormula", "idDispensacion", "item", "regional", "anioNacimiento", "fechaNacimiento", "grupoEdad"]
OBLIGATORIOS = ["paciente", "fuerza", "esm", "modalidad", "fecha", "cantidad", "valor"]

RE_NUMERO = re.compile(r"^-?\d+(\.\d+)?$")
RE_MILES_PUNTO = re.compile(r"^-?\d{1,3}(\.\d{3})+$")
RE_MILES_COMA = re.compile(r"^-?\d{1,3}(,\d{3})+$")
RE_CANTIDAD = re.compile(r"^-?\d{1,11}(\.\d+)?$")
RE_VALOR = re.compile(r"^-?\d{1,14}(\.\d+)?$")
RE_CUM = re.compile(r"^\d{1,9}-\d{1,2}$")
RE_SERIAL = re.compile(r"^\d{5}(\.\d+)?$")
FECHA_MINIMA = dt.date(1990, 1, 1)


@lru_cache(maxsize=1 << 20)
def normalizar(texto):
    """Como AnaliticaIngesta.normalizarEncabezado: mayúsculas, sin tildes, signos como espacio."""
    if texto is None:
        return ""
    s = texto.lstrip("﻿")
    s = "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").upper()
    return re.sub(r"[^A-Z0-9]+", " ", s).strip()


def texto(t):
    if t is None:
        return None
    s = t.strip()
    return s or None


def numero(t, coma_decimal):
    """Como AnaliticaIngesta.numero: Decimal o None."""
    if t is None:
        return None
    s = t.strip().replace("$", "").replace(" ", "").replace(" ", "")
    if not s:
        return None
    if coma_decimal:
        if "," in s:
            s = s.replace(".", "").replace(",", ".")
        elif RE_MILES_PUNTO.match(s):
            s = s.replace(".", "")
    elif "," in s:
        if "." in s or RE_MILES_COMA.match(s):
            s = s.replace(",", "")
        else:
            s = s.replace(",", ".")
    if s.startswith("+"):
        s = s[1:]
    if not RE_NUMERO.match(s):
        return None
    return Decimal(s)


def canonico(d):
    """BigDecimal.toPlainString de Java para un Decimal leído del texto (conserva la escala)."""
    return format(d, "f")


FORMATOS = [("%d/%m/%Y", True), ("%d-%m-%Y", True), ("%Y-%m-%d", True), ("%Y/%m/%d", True), ("%d.%m.%Y", True), ("%Y%m%d", False)]


@lru_cache(maxsize=1 << 20)
def fecha(t):
    """Como AnaliticaIngesta.fecha (estricta; serial de Excel; ignora la hora)."""
    if t is None:
        return None
    s = t.strip()
    if not s:
        return None
    if RE_SERIAL.match(s):
        dias = int(float(s))
        return dt.date(1899, 12, 30) + dt.timedelta(days=dias) if 20000 <= dias <= 99999 else None
    corte = s.find("T") if s.find("T") > 0 else s.find(" ")
    if corte > 0:
        s = s[:corte]
    for f, flexible in FORMATOS:
        try:
            d = dt.datetime.strptime(s, f).date()
        except ValueError:
            continue
        if not flexible and len(s) != 8:
            continue
        # strptime acepta años de menos de 4 cifras; java.time «uuuu» exige 4 o más
        if not re.search(r"\d{4}", s):
            continue
        return d
    return None


@lru_cache(maxsize=1 << 20)
def documento(tipo, num):
    """Como AnaliticaIngesta.documento: «TIPO|NUMERO» normalizado (el sistema guarda su HMAC; aquí solo se compara)."""
    n = normalizar(num).replace(" ", "") if num else ""
    tp = normalizar(tipo).replace(" ", "") if tipo else ""
    if not tp:
        i = 0
        while i < len(n) and n[i].isalpha():
            i += 1
        if 0 < i < len(n):
            tp, n = n[:i], n[i:]
    return "" if not n else tp + "|" + n


@lru_cache(maxsize=1 << 20)
def codigo(t, sin_signos):
    if t is None:
        return None
    s = "".join(c for c in unicodedata.normalize("NFD", t.strip()) if unicodedata.category(c) != "Mn").upper().replace(" ", "")
    if sin_signos:
        s = s.replace(".", "").replace("-", "")
    return s or None


@lru_cache(maxsize=1 << 20)
def cum_normalizado(t):
    if t is None:
        return None
    s = t.strip().replace(" ", "").replace(".", "").upper()
    return s or None


@lru_cache(maxsize=1 << 20)
def fuerza(t):
    if t is None or not t.strip():
        return None
    n = normalizar(t)
    return FUERZAS.get(n, n.replace(" ", ""))


@lru_cache(maxsize=1 << 20)
def modalidad(t):
    if t is None or not t.strip():
        return None
    return MODALIDADES.get(normalizar(t), t.strip())


def detectar_delimitador(linea):
    cuentas = {d: 0 for d in ";,\t|"}
    comillas = False
    for ch in linea:
        if ch == '"':
            comillas = not comillas
        elif not comillas and ch in cuentas:
            cuentas[ch] += 1
    mejor = ";"
    for d in ";,\t|":
        if cuentas[d] > cuentas[mejor]:
            mejor = d
    return mejor


class Totales:
    __slots__ = ("centavos", "unidades", "dispensaciones", "pacientes")

    def __init__(self):
        self.centavos = Decimal(0)
        self.unidades = Decimal(0)
        self.dispensaciones = 0
        self.pacientes = set()

    def sumar(self, valor, cantidad, paciente):
        self.centavos += valor
        self.unidades += cantidad
        self.dispensaciones += 1
        self.pacientes.add(paciente)

    def json(self):
        u = self.unidades.normalize()
        return {"dispensaciones": self.dispensaciones, "gasto": str(self.centavos.quantize(Decimal("0.01"))),
                "pacientes": len(self.pacientes), "unidades": format(u, "f") if u != 0 else "0"}


def abrir(ruta):
    if ruta.lower().endswith(".gz"):
        return io.TextIOWrapper(gzip.open(ruta, "rb"), encoding="utf-8", errors="strict", newline="")
    return open(ruta, encoding="utf-8", errors="strict", newline="")


def calcular(ruta, mapeo, hoy, desde, hasta, esm_conocidos):
    with abrir(ruta) as f:
        primera = f.readline()
        while primera and not primera.strip():
            primera = f.readline()
    delim = detectar_delimitador(primera.lstrip("﻿"))
    coma = delim == ";"
    filas_leidas = 0
    procesados = 0
    rechazos = {}
    sin_esm = 0
    total = Totales()
    dims = {d: {} for d in ("fuerza", "esm", "modalidad", "atc1", "mes", "cum", "cie10")}
    vistos = set()
    validos = []  # (huella) solo para el duplicado exacto

    with abrir(ruta) as f:
        lector = csv.reader(f, delimiter=delim, quotechar='"')
        encabezado = None
        for celdas in lector:
            if not celdas or (len(celdas) == 1 and not celdas[0].strip()):
                continue
            if encabezado is None:
                encabezado = [c.lstrip("﻿") for c in celdas]
                indice = {}
                norm = [normalizar(h) for h in encabezado]
                for campo, columna in mapeo.items():
                    n = normalizar(columna)
                    if n not in norm:
                        sys.exit("No existe la columna %r en el encabezado" % columna)
                    indice[campo] = norm.index(n)
                orden = sorted((i, c) for c, i in indice.items())
                continue
            filas_leidas += 1
            n = len(celdas)
            while n > len(encabezado) and not celdas[n - 1].strip():
                n -= 1
            causas = set()
            if n != len(encabezado):
                causas.add("campos_distintos")
                rechazos["campos_distintos"] = rechazos.get("campos_distintos", 0) + 1
                continue

            def celda(c):
                i = indice.get(c)
                return None if i is None or i >= len(celdas) else texto(celdas[i])

            doc = documento(celda("tipoDocumento"), celda("paciente")) if celda("paciente") else ""
            paciente = doc or None
            fz = fuerza(celda("fuerza"))
            esm = codigo(celda("esm"), False)
            md = modalidad(celda("modalidad"))
            ftexto = celda("fecha")
            fch = fecha(ftexto)
            cum = cum_normalizado(celda("cum"))
            med = celda("medicamento")
            cant_t = celda("cantidad")
            val_t = celda("valor")
            vu_t = celda("valorUnitario")
            cant = numero(cant_t, coma) if cant_t is not None else None
            val = numero(val_t, coma) if val_t is not None else None
            vu = numero(vu_t, coma) if vu_t is not None else None
            cant_c = canonico(cant) if cant is not None else cant_t
            val_c = canonico(val) if val is not None else val_t
            vu_c = canonico(vu) if vu is not None else vu_t

            valores = {"paciente": paciente, "fuerza": fz, "esm": esm, "modalidad": md, "fecha": ftexto, "cantidad": cant_t, "valor": val_t}
            if any(valores[c] is None for c in OBLIGATORIOS) or (med is None and cum is None):
                causas.add("obligatorio_vacio")
            if ftexto is not None and fch is None:
                causas.add("fecha_invalida")
            if fch is not None and (fch > hoy or fch < FECHA_MINIMA or (desde and fch < desde) or (hasta and fch > hasta)):
                causas.add("fecha_fuera_periodo")
            if (cant_c is not None and not RE_CANTIDAD.match(cant_c)) or (val_c is not None and not RE_VALOR.match(val_c)) \
                    or (vu_c is not None and not RE_VALOR.match(vu_c)):
                causas.add("numero_invalido")
            if cant_c is not None and RE_CANTIDAD.match(cant_c) and Decimal(cant_c) <= 0:
                causas.add("cantidad_no_positiva")
            if val_c is not None and RE_VALOR.match(val_c) and Decimal(val_c) < 0:
                causas.add("valor_negativo")
            if cum is not None and not RE_CUM.match(cum):
                causas.add("cum_invalido")
            if fz is not None and fz not in FUERZAS_CATALOGO:
                causas.add("fuerza_no_reconocida")
            if md is not None and md not in MODALIDADES_CATALOGO:
                causas.add("modalidad_no_reconocida")

            # Duplicado exacto: todas las columnas mapeadas en su orden, con el paciente y el profesional normalizados
            huella = []
            for i, c in orden:
                if c == "tipoDocumento":
                    continue
                if c == "paciente":
                    huella.append(paciente)
                elif c == "profesional":
                    p = documento(None, celda("profesional")) if celda("profesional") else ""
                    huella.append(p or None)
                elif c in ("fechaNacimiento", "anioNacimiento"):
                    if c == "anioNacimiento":
                        a = celda(c)
                        huella.append(a if a and re.match(r"^\d{4}$", a) else None)
                    else:
                        fn = fecha(celda(c))
                        huella.append(str(fn.year) if fn else None)
                else:
                    huella.append(texto(celdas[i]) if i < len(celdas) else None)
            h = tuple(huella)
            if h in vistos:
                causas.add("duplicado_exacto")
            else:
                vistos.add(h)

            if causas:
                for c in causas:
                    rechazos[c] = rechazos.get(c, 0) + 1
                continue
            procesados += 1
            valor = val.quantize(Decimal("0.01"))
            cantidad = cant.quantize(Decimal("0.001"))
            atc = codigo(celda("atc"), True)
            cie = codigo(celda("cie10"), True)
            claves = {"fuerza": fz, "esm": esm, "modalidad": md, "atc1": atc[0] if atc else "(sin ATC)", "mes": fch.isoformat()[:7],
                      "cum": cum if cum else "(sin CUM)", "cie10": cie if cie else "(sin CIE-10)"}
            total.sumar(valor, cantidad, paciente)
            for d, k in claves.items():
                dims[d].setdefault(k, Totales()).sumar(valor, cantidad, paciente)
            if esm_conocidos is not None and esm not in esm_conocidos:
                sin_esm += 1

    salida = {
        "filas": {"recibidos": filas_leidas, "procesados": procesados, "rechazados": filas_leidas - procesados},
        "rechazos_por_causa": dict(sorted(rechazos.items())),
        "total": total.json(),
        "dimensiones": {d: {k: t.json() for k, t in sorted(v.items())} for d, v in dims.items()},
    }
    if esm_conocidos is not None:
        salida["esm_sin_correspondencia"] = sin_esm
    return salida


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("archivo")
    ap.add_argument("--salida", help="archivo JSON de salida (por defecto, la salida estándar)")
    ap.add_argument("--hoy", help="fecha de referencia para «fecha futura» (por defecto, hoy)")
    ap.add_argument("--desde", help="inicio del periodo declarado")
    ap.add_argument("--hasta", help="fin del periodo declarado")
    ap.add_argument("--esm-conocidos", help="siglas de los ESM de `puntos`, separadas por coma, o @archivo con una por línea")
    ap.add_argument("--mapeo", help="campo=COLUMNA separados por coma (por defecto, los encabezados del generador sintético)")
    a = ap.parse_args()
    mapeo = MAPEO_GENERADOR
    if a.mapeo:
        mapeo = dict(p.split("=", 1) for p in a.mapeo.split(","))
    esm = None
    if a.esm_conocidos:
        if a.esm_conocidos.startswith("@"):
            with open(a.esm_conocidos[1:], encoding="utf-8") as f:
                esm = {codigo(l, False) for l in f if l.strip()}
        else:
            esm = {codigo(s, False) for s in a.esm_conocidos.split(",") if s.strip()}
    hoy = dt.date.fromisoformat(a.hoy) if a.hoy else dt.date.today()
    desde = dt.date.fromisoformat(a.desde) if a.desde else None
    hasta = dt.date.fromisoformat(a.hasta) if a.hasta else None
    try:
        r = calcular(a.archivo, mapeo, hoy, desde, hasta, esm)
    except UnicodeDecodeError:
        sys.exit("El archivo no está en UTF-8: la ingesta lo rechaza completo (estructura).")
    texto_json = json.dumps(r, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    if a.salida:
        with open(a.salida, "w", encoding="utf-8", newline="\n") as f:
            f.write(texto_json)
    else:
        sys.stdout.buffer.write(texto_json.encode("utf-8"))


if __name__ == "__main__":
    main()
