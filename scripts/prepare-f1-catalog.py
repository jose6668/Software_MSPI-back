"""Prepare the approved F1 edition as a local psql input. Uses only the Python standard library."""

import argparse
import hashlib
import json
import posixpath
import re
from pathlib import Path
from uuid import UUID, uuid5
from xml.etree import ElementTree
from zipfile import ZipFile


FINGERPRINT = "a015a14b885012d709ea18f3486bd6b6b9902f351c0a2ae7c041f19470cd6e20"
CATALOG = "MSPI_DIAGNOSTICO_F1"
VERSION = "F1-1"
NAME = "Diagnóstico MSPI F1"
SOURCE = "articles-401779_recurso_1.xlsx"
NAMESPACE = UUID("e6840306-33ca-4431-9073-530694da5327")
XML = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
CONTROLS = (
    ("ORGANIZACIONALES", "ORGANIZACIONAL", 52),
    ("PERSONAS", "PERSONAS", 23),
    ("FÍSICOS", "FISICO", 29),
    ("TECNOLÓGICOS ", "TECNOLOGICO", 49),
)
CLAUSES = ((4, 16, 19), (5, 22, 24), (6, 27, 29), (7, 32, 36), (8, 39, 41), (9, 44, 46), (10, 49, 50))


def sheet_cells(archive, path, strings):
    cells = {}
    root = ElementTree.fromstring(archive.read(path))
    for cell in root.findall(".//s:sheetData/s:row/s:c", XML):
        if cell.find("s:f", XML) is not None:
            continue
        value = cell.findtext("s:v", namespaces=XML)
        if cell.get("t") == "s":
            value = strings[int(value)]
        if cell.get("t") == "inlineStr":
            value = "".join(text.text or "" for text in cell.findall(".//s:t", XML))
        if value is not None:
            cells[cell.attrib["r"]] = value.strip().replace("\r\n", "\n").replace("\r", "\n")
    return cells


def required(cells, coordinate, sheet, limit=None):
    value = cells.get(coordinate)
    if not value or (limit is not None and len(value) > limit):
        raise ValueError(f"Celda de referencia inválida: {sheet}!{coordinate}")
    return value


def item(kind, code, title, description, group, reference):
    return {
        "id": str(uuid5(NAMESPACE, f"{VERSION}/{kind}/{code}")),
        "tipo": kind,
        "codigo": code,
        "titulo": title,
        "descripcion": description,
        "grupo": group,
        "referencia_origen": reference,
    }


def extract(source):
    if hashlib.sha256(source.read_bytes()).hexdigest() != FINGERPRINT:
        raise ValueError("La huella del archivo no coincide con la revisión F1 aprobada.")
    entries = []
    with ZipFile(source) as archive:
        workbook = ElementTree.fromstring(archive.read("xl/workbook.xml"))
        relations = ElementTree.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        targets = {
            rel.attrib["Id"]: rel.attrib["Target"].lstrip("/") if rel.attrib["Target"].startswith("/")
            else posixpath.join("xl", rel.attrib["Target"])
            for rel in relations
        }
        paths = {sheet.attrib["name"]: targets[sheet.attrib[REL]] for sheet in workbook.findall("s:sheets/s:sheet", XML)}
        shared = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
        strings = ["".join(text.text or "" for text in cell.findall(".//s:t", XML)) for cell in shared.findall("s:si", XML)]
        for sheet, group, last in CONTROLS:
            cells = sheet_cells(archive, paths[sheet], strings)
            for row in range(16, last + 1):
                code = required(cells, f"C{row}", sheet, 80)
                if not re.fullmatch(r"A\s*[5-8][.,]\d+", code):
                    raise ValueError(f"Código inesperado: {sheet}!C{row}")
                entries.append(item("CONTROL", code, required(cells, f"E{row}", sheet, 250),
                                    required(cells, f"F{row}", sheet), group, f"'{sheet}'!C{row},E{row}:F{row}"))
        sheet = "CLÁUSULAS"
        cells = sheet_cells(archive, paths[sheet], strings)
        for group, first, last in CLAUSES:
            for row in range(first, last + 1):
                code = required(cells, f"C{row}", sheet, 80)
                if not re.fullmatch(rf"{group}\.\d+", code):
                    raise ValueError(f"Numeral inesperado: {sheet}!C{row}")
                entries.append(item("CLAUSULA", code, required(cells, f"D{row}", sheet, 250),
                                    required(cells, f"E{row}", sheet), f"CLAUSULA_{group}", f"'{sheet}'!C{row}:E{row}"))
    if len(entries) != 116 or len({(entry["tipo"], entry["codigo"]) for entry in entries}) != len(entries):
        raise ValueError("Conteo o identificadores inconsistentes en la edición F1.")
    return entries


def publication_sql(entries):
    payload = json.dumps(entries, ensure_ascii=False)
    if "$datos_f1$" in payload or "$publicacion_f1$" in payload:
        raise ValueError("El contenido usa un delimitador reservado.")
    catalog_id = uuid5(NAMESPACE, "F1")
    version_id = uuid5(NAMESPACE, VERSION)
    record = "id uuid, tipo text, codigo text, titulo text, descripcion text, grupo text, referencia_origen text"
    return f"""\\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';
BEGIN;
SELECT pg_advisory_xact_lock(1297305673, 16);
DO $publicacion_f1$
DECLARE
    v_catalogo constant uuid := '{catalog_id}';
    v_version constant uuid := '{version_id}';
    v_datos constant jsonb := $datos_f1${payload}$datos_f1$::jsonb;
BEGIN
    IF current_user <> 'mspi_common_migrator' THEN
        RAISE EXCEPTION 'Use la identidad migradora común.';
    END IF;
    IF EXISTS (SELECT 1 FROM comun.versiones_catalogo WHERE id = v_version) THEN
        IF NOT EXISTS (
            SELECT 1 FROM comun.versiones_catalogo v JOIN comun.catalogos c ON c.id = v.catalogo_id
            WHERE v.id = v_version AND c.id = v_catalogo AND c.codigo = '{CATALOG}'
              AND v.version = '{VERSION}' AND v.nombre = '{NAME}' AND v.origen = '{SOURCE}'
              AND v.huella_origen = '{FINGERPRINT}' AND v.publicada_en IS NOT NULL
        ) OR (SELECT count(*) FROM comun.elementos_catalogo WHERE version_catalogo_id = v_version) <> {len(entries)}
          OR EXISTS (
            SELECT * FROM jsonb_to_recordset(v_datos) AS f({record})
            EXCEPT
            SELECT id, tipo, codigo, titulo, descripcion, grupo, referencia_origen
            FROM comun.elementos_catalogo WHERE version_catalogo_id = v_version
        ) THEN
            RAISE EXCEPTION 'La edición existente no coincide con el contenido aprobado.';
        END IF;
        RETURN;
    END IF;

    INSERT INTO comun.catalogos (id, codigo) VALUES (v_catalogo, '{CATALOG}') ON CONFLICT (id) DO NOTHING;
    IF NOT EXISTS (SELECT 1 FROM comun.catalogos WHERE id = v_catalogo AND codigo = '{CATALOG}') THEN
        RAISE EXCEPTION 'La identidad del catálogo no coincide.';
    END IF;
    INSERT INTO comun.versiones_catalogo (id, catalogo_id, version, nombre, origen, huella_origen)
    VALUES (v_version, v_catalogo, '{VERSION}', '{NAME}', '{SOURCE}', '{FINGERPRINT}');
    INSERT INTO comun.elementos_catalogo (id, version_catalogo_id, tipo, codigo, titulo, descripcion, grupo, referencia_origen)
    SELECT id, v_version, tipo, codigo, titulo, descripcion, grupo, referencia_origen
    FROM jsonb_to_recordset(v_datos) AS f({record});
    UPDATE comun.versiones_catalogo SET publicada_en = clock_timestamp() WHERE id = v_version;
END;
$publicacion_f1$;
COMMIT;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workbook", type=Path)
    parser.add_argument("output_sql", type=Path)
    args = parser.parse_args()
    entries = extract(args.workbook)
    with args.output_sql.open("x", encoding="utf-8", newline="\n") as output:
        output.write(publication_sql(entries))
    print(f"{VERSION}: {len(entries)} elementos preparados para publicación local.")


if __name__ == "__main__":
    main()
