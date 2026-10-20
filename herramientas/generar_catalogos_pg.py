"""Genera la carga inicial de catálogos de la Suite DIGSA (PostgreSQL) desde el catálogo del mockup v6.

Es la misma fuente que alimentó la aplicación en PHP (OCR OSCAR/entregables/fuente_checklist_v6/generar_sql_v6.py):
departamentos, 1 122 municipios con coordenadas del DANE, unidades, fuerzas, modalidades, tipos de visita, parámetros
y el checklist v6 (21 componentes, 343 ítems, vacíos prioritarios y guía de obligaciones).

Salida (migraciones de Flyway del backend):
  R__catalogos.sql     repetible: inserta o actualiza sin borrar; no pisa el valor de los parámetros editables
  V4__checklist_v6.sql versionada: publica la versión v6 y la deja vigente; una versión publicada no se modifica
  V7__datos_iniciales_editables.sql
                       versionada: fuerzas, catálogos parametrizables, 8 regionales y los 185 puntos del anexo del OPL
                       (herramientas/datos/puntos_anexo3_opl.json); lo que el coordinador borre después no reaparece

Uso:  py herramientas/generar_catalogos_pg.py [carpeta_fuente_checklist_v6]
"""
import json
import sys
from datetime import date
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
FUENTE = Path(sys.argv[1]) if len(sys.argv) > 1 else (
    Path.home() / "OneDrive - AGS COLOMBIA" / "LUME2" / "SUITE DIGSA" / "OCR OSCAR" / "entregables" / "fuente_checklist_v6")
SALIDA = RAIZ / "backend" / "app" / "src" / "main" / "resources" / "db" / "migration"
VERSION = "v6"
LOTE = 200

sys.path.insert(0, str(FUENTE))
import generar_checklist_v6 as g  # noqa: E402
import generar_sql_v6 as fuente_sql  # noqa: E402  (coordenadas del DANE)


def q(v):
    """Literal SQL de PostgreSQL (standard_conforming_strings: solo se duplican las comillas)."""
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, (dict, list)):
        return q(json.dumps(v, ensure_ascii=False, separators=(",", ":"))) + "::jsonb"
    return "'" + str(v).replace("\r", "").replace("'", "''") + "'"


def arreglo(lista):
    return "ARRAY[" + ", ".join(q(x) for x in lista) + "]::text[]"


def inserts(tabla, columnas, filas, conflicto):
    out = []
    cols = ", ".join(columnas)
    for i in range(0, len(filas), LOTE):
        valores = ",\n  ".join("(" + ", ".join(v if isinstance(v, Crudo) else q(v) for v in f) + ")" for f in filas[i:i + LOTE])
        out.append(f"INSERT INTO {tabla} ({cols}) VALUES\n  {valores}\n{conflicto};")
    return "\n".join(out)


class Crudo(str):
    """Expresión SQL que va tal cual (no se cita)."""


def actualizar(pk, *columnas):
    return f"ON CONFLICT ({pk}) DO UPDATE SET " + ", ".join(f"{c} = EXCLUDED.{c}" for c in columnas)


def catalogos(D):
    partes = [f"-- Suite DIGSA · Catálogos. GENERADO por herramientas/generar_catalogos_pg.py el {date.today()} — no editar a mano.\n"
              "-- Migración repetible: inserta o actualiza sin borrar y sin pisar lo que edita el coordinador."]
    partes.append(inserts("par_departamentos", ["codigo", "nombre"], sorted(D["departamentos"].items()), actualizar("codigo", "nombre")))
    coords = fuente_sql.coordenadas_dane()
    municipios = [(cod, dep, nom, *coords.get(cod, (None, None))) for dep, lista in sorted(D["municipios"].items()) for cod, nom in lista]
    partes.append(inserts("par_municipios", ["codigo", "departamento", "nombre", "latitud", "longitud"], municipios,
                          actualizar("codigo", "departamento", "nombre", "latitud", "longitud")))
    partes.append(inserts("par_unidades", ["codigo", "nombre", "fuente", "orden"],
                          [(u["key"], u["nombre"], u["fuente"], i + 1) for i, u in enumerate(D["unidades"])],
                          actualizar("codigo", "nombre", "fuente", "orden")))
    # Fuerzas, catálogos parametrizables, regionales y puntos: datos iniciales editables (V7, versionada; ver semillas())
    partes.append(inserts("par_modalidades", ["nombre", "orden"], [(m, i + 1) for i, m in enumerate(D["modalidades"])],
                          actualizar("nombre", "orden")))
    partes.append(inserts("par_tipos_visita", ["codigo", "nombre", "unidad", "modalidad", "aviso", "periodicidad", "finalidad", "notificacion", "fuente", "orden"],
                          [(t["key"], t["nombre"], t["unidad"], t["modalidad"], t["aviso"], t["periodicidad"], t["finalidad"], t["notificacion"], t["fuente"], i + 1)
                           for i, t in enumerate(D["tiposVisita"])],
                          actualizar("codigo", "nombre", "unidad", "modalidad", "aviso", "periodicidad", "finalidad", "notificacion", "fuente", "orden")))
    R = D["agenda"]["reglas"]
    interna = "Definición interna DIGSA (sin respaldo en un anexo)"
    parametros = [
        ("notif_habiles_modulo", str(R["notifHabilesDefecto"]), "Antelación mínima (días hábiles) de la notificación en visitas por requerimiento y de seguimiento a plan", "Parámetro: el Anexo 3 no la regula", True),
        ("inicio_contrato", "", "Fecha de inicio del contrato (plazo de la visita inicial)", R["inicialFuente"], True),
        ("aviso_umbral_opl", "5", "Días antes del límite de notificación al OPL desde los que la alerta pasa a advertencia", interna, True),
        ("aviso_umbral_modulo", "1", "Días antes del límite de notificación (requerimiento y plan) desde los que la alerta pasa a advertencia", interna, True),
        ("notif_dias_opl", str(R["notifDias"]), "Antelación mínima (días calendario) de la notificación a las DISANES y al OPL; si cae en día no hábil se corre al hábil anterior", R["notifFuente"], False),
        ("inicial_meses", str(R["inicialMeses"]), "Meses para la visita inicial al 100 % de los ESM de dispensación", R["inicialFuente"], False),
        ("max_seguimientos", "3", "Máximo de visitas de seguimiento sobre un mismo plan 5W2H; después se escala al supervisor del contrato", "Definición interna DIGSA (llamada 2026-10-14)", True),
        ("correo_digsa", "", "Correo(s) de la DIGSA: reciben todas las actas, la notificación del nivel central y los escalamientos", "Decisiones 9 y 24 (2026-10-15)", True),
        ("necesidad_habiles", "5", "Días hábiles máximos entre la solicitud de una visita a necesidad y la visita", "Anexo 3 p.14", False),
        ("recordatorio_seguimiento_habiles", "5", "Días hábiles antes de la visita de seguimiento en que se recuerda al auditor por correo", "Definición interna DIGSA (llamada 2026-10-14)", True),
        ("visitas_por_dia", "2", "Propuesta de agenda: máximo de visitas por auditor en un mismo día", "Decisión 32 (2026-10-15)", True),
        ("km_mismo_dia", "40", "Propuesta de agenda: distancia máxima (km) entre los municipios visitados el mismo día por un auditor", "Decisión 32 (2026-10-15)", True),
    ]
    partes.append(inserts("par_parametros", ["clave", "valor", "descripcion", "fuente", "editable"], parametros,
                          "ON CONFLICT (clave) DO UPDATE SET valor = CASE WHEN par_parametros.editable AND EXCLUDED.editable "
                          "THEN par_parametros.valor ELSE EXCLUDED.valor END, descripcion = EXCLUDED.descripcion, fuente = EXCLUDED.fuente, "
                          "editable = EXCLUDED.editable"))
    return "\n\n".join(partes) + "\n", len(municipios)


def checklist(D):
    partes = [f"-- Suite DIGSA · Checklist {VERSION}. GENERADO por herramientas/generar_catalogos_pg.py el {date.today()} — no editar a mano.\n"
              f"-- Publica la versión {VERSION} y la deja vigente. Una versión publicada no se modifica: un cambio sale como versión nueva.\n"
              "-- (Flyway corre esta migración una sola vez y rechaza cambios en ella.)"]
    partes.append(f"INSERT INTO chk_versiones (version, descripcion, fecha_generacion, vigente) VALUES ({q(VERSION)}, "
                  f"{q('Checklist v6: Anexo 3, procesos de la v2, cruce Res. 3100 vs 1403/2955, habilitación y vacíos prioritarios')}, "
                  f"{q(date.today().isoformat())}, false);")
    partes.append("INSERT INTO chk_vacios (version, codigo, nombre) VALUES\n  " + ",\n  ".join(
        f"({q(VERSION)}, {int(k)}, {q(v)})" for k, v in sorted(D["vacios"].items(), key=lambda x: int(x[0]))) + ";")
    go = D["guiaObligacion"]
    partes.append(f"INSERT INTO chk_guia_obligacion (version, cumple, no_cumple, no_aplica, fuente) VALUES "
                  f"({q(VERSION)}, {q(go['C'])}, {q(go['NC'])}, {q(go['NA'])}, {q(go.get('fuente'))});")
    total = 0
    for orden, c in enumerate(D["componentes"], 1):
        comp = (f"INSERT INTO chk_componentes (version, codigo, nombre, descripcion, grupo, especial, escala, guia, unidades, fuente, orden) VALUES "
                f"({q(VERSION)}, {q(c['id'])}, {q(c['nombre'])}, {q(c.get('desc'))}, {q(c.get('grupo'))}, {q(c.get('especial'))}, "
                f"{q(c.get('escala'))}, {q(c.get('guia'))}, {arreglo(c['unidades'])}, {q(c.get('fuente'))}, {orden}) RETURNING id")
        items = c["items"]
        total += len(items)
        if not items:
            partes.append(comp + ";")
            continue
        filas = ",\n    ".join(
            f"({q(i['id'])}, {q(i['punto'])}, {q(i['criterio'])}, {q(i.get('fuente'))}, {q(i['origen'])}, {q(i.get('requisito'))}, "
            f"{q(i.get('evidencia'))}, {q(i.get('aplica'))}, {q(i.get('condicion'))}, {q(i.get('prioridad'))}, "
            f"{q(i.get('medicion')) if i.get('medicion') is not None else 'NULL::jsonb'}, "
            f"{q(i.get('subvalidacion')) if i.get('subvalidacion') is not None else 'NULL::jsonb'}, {n})"
            for n, i in enumerate(items, 1))
        partes.append(f"WITH c AS ({comp})\nINSERT INTO chk_items (componente_id, codigo, punto, criterio, fuente, origen, requisito, evidencia, "
                      f"aplica, condicion, prioridad, medicion, subvalidacion, orden)\nSELECT c.id, v.* FROM c CROSS JOIN (VALUES\n    {filas}\n) AS "
                      "v(codigo, punto, criterio, fuente, origen, requisito, evidencia, aplica, condicion, prioridad, medicion, subvalidacion, orden);")
    partes.append(f"UPDATE chk_versiones SET vigente = false WHERE version <> {q(VERSION)};")
    partes.append(f"UPDATE chk_versiones SET vigente = true WHERE version = {q(VERSION)};")
    return "\n\n".join(partes) + "\n", total


REGIONALES = ["Bogotá", "Medellín", "Cali", "Barranquilla", "Bucaramanga", "Cartagena", "Ibagué", "Neiva"]   # respuesta de la DIGSA del 19-oct
# Ciudades del anexo del OPL escritas de otra forma que en el DANE
ALIAS_CIUDAD = {"SANTA ROSA DECABAL": "SANTA ROSA DE CABAL", "CARTAGENA": "CARTAGENA DE INDIAS", "TUMACO": "SAN ANDRES DE TUMACO",
                "MARIQUITA": "SAN SEBASTIAN DE MARIQUITA", "TOLEMAIDA": "NILO", "CUCUTA": "SAN JOSE DE CUCUTA", "CALI": "SANTIAGO DE CALI",
                "SAN JUAN DE PASTO": "PASTO", "VILLA GARZON": "VILLAGARZON", "LEGUIZAMO": "PUERTO LEGUIZAMO",
                "SAN VICENTE DEL CHUCURI": "SAN VICENTE DE CHUCURI", "SALAZAR DE LAS PALMAS": "SALAZAR", "LA URIBE": "URIBE",
                "CARTAGENA DELCHAIRA": "CARTAGENA DEL CHAIRA", "PUERTO INIRIDA": "INIRIDA", "USME": "BOGOTA",
                # Lugares que no son municipios: se toma el municipio que los contiene (van en la lista de validación)
                "APIAY": "VILLAVICENCIO", "TRES ESQUINAS": "SOLANO", "SANTANA": "PUERTO ASIS"}
ALIAS_DEPARTAMENTO = {"GUAJIRA": "LA GUAJIRA", "BOGOTA": "BOGOTA D C", "VALLE": "VALLE DEL CAUCA", "SAN ANDRES": "ARCHIPIELAGO DE SAN ANDRES"}
EXIGEN_QF = ("HOMIL", "HONAC")   # Hospital Militar Central y Hospital Naval de Cartagena (Anexo 3 p.9)


def normal(s):
    import unicodedata
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().upper()
    return " ".join(s.replace(".", " ").replace(",", " ").split())


def municipio_de(D, ciudad, departamento):
    """Código DANE del municipio por nombre (y departamento si se da). None si no se encuentra o es ambiguo."""
    c = normal(ciudad)
    c = normal(ALIAS_CIUDAD.get(c, c))
    deps = {normal(n): cod for cod, n in D["departamentos"].items()}
    dep = deps.get(normal(ALIAS_DEPARTAMENTO.get(normal(departamento), departamento))) if departamento else None
    if c.startswith("BOGOTA"):
        return "11001"
    candidatos = [(cod, normal(nom)) for d, lista in D["municipios"].items() if dep is None or d == dep for cod, nom in lista]
    hallados = [cod for cod, nom in candidatos if nom == c] or [cod for cod, nom in candidatos if nom.startswith(c + " ")]
    if len(hallados) > 1 and dep is None:
        capitales = [h for h in hallados if h.endswith("001")]
        hallados = capitales if len(capitales) == 1 else hallados
    return hallados[0] if len(hallados) == 1 else None


def semillas(D):
    """Datos iniciales que el coordinador edita en vivo: van en una migración versionada (corre una vez y no se repite)."""
    partes = [f"-- Suite DIGSA · Datos iniciales editables. GENERADO por herramientas/generar_catalogos_pg.py el {date.today()} — no editar a mano.\n"
              "-- Versionada: corre una sola vez. Lo que el coordinador cree, modifique o elimine después (Anexo 5, prueba 4) no reaparece.\n"
              "-- Flyway corre las versionadas antes que las repetibles: los catálogos que usan los puntos se insertan aquí si faltan, y\n"
              "-- R__catalogos.sql los actualiza después."]
    coords = fuente_sql.coordenadas_dane()
    partes.append(inserts("par_departamentos", ["codigo", "nombre"], sorted(D["departamentos"].items()), "ON CONFLICT (codigo) DO NOTHING"))
    partes.append(inserts("par_municipios", ["codigo", "departamento", "nombre", "latitud", "longitud"],
                          [(cod, dep, nom, *coords.get(cod, (None, None))) for dep, lista in sorted(D["municipios"].items()) for cod, nom in lista],
                          "ON CONFLICT (codigo) DO NOTHING"))
    partes.append(inserts("par_unidades", ["codigo", "nombre", "fuente", "orden"],
                          [(u["key"], u["nombre"], u["fuente"], i + 1) for i, u in enumerate(D["unidades"])], "ON CONFLICT (codigo) DO NOTHING"))
    partes.append(inserts("par_modalidades", ["nombre", "orden"], [(m, i + 1) for i, m in enumerate(D["modalidades"])],
                          "ON CONFLICT (nombre) DO NOTHING"))
    partes.append(inserts("par_fuerzas", ["codigo", "nombre", "sanidad", "fuente", "orden"],
                          [(f["key"], f["nombre"], f["sanidad"] or None, f["fuente"] or None, i + 1) for i, f in enumerate(D["fuerzas"])],
                          "ON CONFLICT (codigo) DO NOTHING"))
    partes.append(inserts("catalogos", ["codigo", "nombre", "descripcion", "patron", "orden"], [
        ("tipo_documento", "Tipos de documento", "Tipo de documento de identificación (Anexo 5, prueba 4)", "^[A-Z]{1,3}$", 1),
        ("tipo_factura", "Tipos de factura", "Tipo de factura o cuenta (Anexo 5, prueba 4)", "^[0-9]{1,3}$", 2),
    ], "ON CONFLICT (codigo) DO NOTHING"))
    partes.append(inserts("catalogo_valores", ["catalogo", "codigo", "descripcion", "orden"], [
        ("tipo_documento", "C", "Cédula de ciudadanía", 1), ("tipo_documento", "T", "Tarjeta de identidad", 2),
        ("tipo_documento", "R", "Registro civil", 3), ("tipo_documento", "V", "Visa", 4),
        ("tipo_factura", "1", "Acuerdo", 1), ("tipo_factura", "2", "CTC", 2), ("tipo_factura", "3", "Tutela", 3),
        ("tipo_factura", "4", "Requisición", 4),
    ], "ON CONFLICT (catalogo, codigo) DO NOTHING"))
    partes.append(inserts("par_regionales", ["nombre"], [(r,) for r in REGIONALES], "ON CONFLICT (nombre) DO NOTHING"))

    datos = json.loads((RAIZ / "herramientas" / "datos" / "puntos_anexo3_opl.json").read_text(encoding="utf-8"))
    filas, sin_municipio = [], []
    for p in datos:
        nombre = p["nombre"].strip()
        sigla = (p.get("sigla") or "").strip() or None
        if sigla and sigla.startswith("HONAC "):
            nombre = f"{nombre} - {sigla.split(' ', 1)[1]}"   # dos puntos del mismo hospital (ambulatoria y hospitalaria)
        cod = municipio_de(D, p.get("ciudad"), p.get("departamento"))
        if cod is None:
            sin_municipio.append(f"{sigla or nombre} ({p.get('ciudad')}, {p.get('departamento')})")
        exige = bool(sigla) and sigla.split(" ")[0] in EXIGEN_QF
        nivel = {"1": "I"}.get(p.get("nivel"), p.get("nivel"))
        fuente = f"Anexo 3 del OPL, p.{p['pagina']}" if p.get("pagina") else "Anexo 3 del OPL"
        filas.append((sigla, nombre, p["tipo"], p.get("fuerza") or "NA", p.get("modalidad"), cod, p.get("direccion"), nivel,
                      p.get("horario"), p.get("regional"), exige, fuente, "carga inicial"))
    partes.append(inserts("puntos", ["sigla", "nombre", "unidad", "fuerza", "modalidad", "municipio", "direccion", "nivel", "horario",
                                     "regional_opl", "exige_qf", "fuente", "creado_por"], filas, "ON CONFLICT DO NOTHING"))
    return "\n\n".join(partes) + "\n", len(filas), sin_municipio


def main():
    D = g.construir_datos()
    destino = SALIDA / "V7__datos_iniciales_editables.sql"
    if destino.exists():
        print(f"{destino.name} ya existe: no se reescribe (ya pudo correr en una base).")
    else:
        sql0, n_puntos, sin_municipio = semillas(D)
        destino.write_text(sql0, encoding="utf-8", newline="\n")
        print(f"Puntos: {n_puntos} | sin municipio: {len(sin_municipio)}")
        for s in sin_municipio:
            print("  sin municipio:", s)
    sql1, n_mun = catalogos(D)
    (SALIDA / "R__catalogos.sql").write_text(sql1, encoding="utf-8", newline="\n")
    sql2, n_items = checklist(D)
    esperado = sum(len(c["items"]) for c in D["componentes"])
    if n_items != esperado:
        raise SystemExit(f"Ítems escritos {n_items} != catálogo {esperado}")
    destino = SALIDA / f"V4__checklist_{VERSION}.sql"
    if destino.exists():
        print(f"{destino.name} ya existe: no se reescribe (versión publicada).")
    else:
        destino.write_text(sql2, encoding="utf-8", newline="\n")
    print(f"Municipios: {n_mun} | Componentes: {len(D['componentes'])} | Ítems: {n_items}")


if __name__ == "__main__":
    main()
