"""Carga registros ficticios de Panorama en una empresa de demostración local."""
import argparse
from collections import defaultdict
from getpass import getpass
import http.client
from http.cookies import SimpleCookie
import json
from pathlib import Path
import ssl
import time
from urllib.parse import urlencode, urlsplit
from uuid import UUID

COMPANY = "Andina Servicios S.A.S."
EVALUATION = "Diagnóstico · noviembre 2026"
ASSETS = (
    ("Servidor de respaldos", "HARDWARE"), ("ERP corporativo", "SOFTWARE"),
    ("Base de datos de clientes", "INFORMACION"), ("Repositorio de contratos", "INFORMACION"),
    ("Portal de proveedores", "SERVICIO"), ("Directorio de usuarios", "SERVICIO"),
    ("Plataforma de correo", "SERVICIO"), ("Sistema de nómina", "SOFTWARE"),
    ("Archivo de compras", "INFORMACION"), ("Red de oficina", "RED"),
    ("Estación de tesorería", "HARDWARE"), ("Gestor documental", "SOFTWARE"),
)
PROCESSES = ("Tecnología", "Finanzas", "Servicio al cliente", "Jurídica", "Compras", "Talento humano")
OWNERS = ("Diego Ruiz", "Camila Ríos", "Laura Méndez")
# Por dominio: notas evaluadas, aplicables sin calificar, sin decidir y no aplica.
CONTROLS = {
    "ORGANIZACIONAL": ([0] * 2 + [80] * 18 + [60] * 4, 8, 2, 3),
    "PERSONAS": ([0] + [80] * 3 + [60] * 2, 1, 0, 1),
    "FISICO": ([0] + [80] * 3 + [60] * 6, 1, 1, 2),
    "TECNOLOGICO": ([0] * 2 + [80] * 6 + [60] * 12, 10, 2, 2),
}
CLAUSES = {"CLAUSULA_4": 60, "CLAUSULA_5": 80, "CLAUSULA_6": 40, "CLAUSULA_7": 60}
FUNDAMENTO = "Valor ilustrativo para la demostración Panorama."


class LocalApi:
    def __init__(self, origin, ca):
        url = urlsplit(origin)
        if url.scheme != "https" or url.hostname not in {"localhost", "127.0.0.1"} or (
            url.username or url.password or url.path not in {"", "/"} or url.query or url.fragment
        ):
            raise ValueError("Indica un origen HTTPS local del backend")
        self.conn = http.client.HTTPSConnection(url.hostname, url.port or 443, timeout=30,
                                                context=ssl.create_default_context(cafile=ca))
        self.cookies = SimpleCookie()
        self.csrf = {}

    def call(self, path, method="GET", payload=None, status=200):
        headers = {"Accept": "application/json"}
        if self.cookies:
            headers["Cookie"] = "; ".join(m.OutputString(attrs=[]) for m in self.cookies.values())
        body = None
        if payload is not None:
            body = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        if method != "GET":
            headers.update(self.csrf)
        for _ in range(6):
            self.conn.request(method, "/api/v1" + path, body, headers)
            response = self.conn.getresponse()
            content = response.read()
            for name, value in response.getheaders():
                if name.lower() == "set-cookie":
                    self.cookies.load(value)
            if response.status != 429:
                break
            delay = int(response.getheader("Retry-After") or "1")
            time.sleep(min(60, max(1, delay)))
        if response.status != status:
            problem = json.loads(content) if content and "json" in (response.getheader("Content-Type") or "") else {}
            raise RuntimeError(f"{method} {path}: HTTP {response.status} ({problem.get('code', 'sin código')})")
        return json.loads(content) if content else None

    def refresh_csrf(self):
        token = self.call("/identidad/csrf")
        self.csrf = {token["headerName"]: token["token"]}

    def login(self, email, password):
        self.refresh_csrf()
        self.call("/identidad/sesion", "POST", {"correo": email, "contrasena": password})
        self.refresh_csrf()

    def pages(self, path, **filters):
        page = 0
        while True:
            data = self.call(path + "?" + urlencode(dict(filters, pagina=page, tamano=100)))
            yield from data["elementos"]
            if not data["hayMas"]:
                break
            page += 1


def by_key(items, key):
    indexed = {}
    for item in items:
        value = item[key]
        if value in indexed:
            raise RuntimeError(f"Hay registros duplicados para {value}")
        indexed[value] = item
    return indexed


def get_or_create(api, path, indexed, key, payload):
    value = payload[key]
    existing = indexed.get(value)
    if existing is not None:
        if any(existing.get(field) != field_value for field, field_value in payload.items()):
            raise RuntimeError(f"El registro existente {value} difiere de la muestra")
        return existing
    return api.call(path, "POST", payload, status=201)


def answer(applicability, score=None):
    return {
        "aplicabilidad": applicability,
        "justificacionAplicabilidad": "Fuera del alcance ficticio de Andina." if applicability == "NO_APLICA" else None,
        "calificacion": score,
        "fundamento": FUNDAMENTO if score is not None else None,
    }


def seed_evaluation(api, company, responsible):
    path = company + "/evaluaciones"
    catalog = next(row for row in api.pages(company + "/catalogos", codigo="MSPI_DIAGNOSTICO_F1")
                   if row["version"] == "F1-1")
    method = next(row for row in api.pages(company + "/metodologias", codigo="EVALUACION_CONTROLES")
                  if row["identificador"] == "EVALUACION_CONTROLES_V1")
    payload = {"nombre": EVALUATION, "fechaEvaluacion": "2026-11-06",
               "alcance": "Diagnóstico ficticio de controles y cláusulas para la demostración Panorama.",
               "responsableId": responsible, "versionCatalogoId": catalog["versionId"],
               "versionMetodologiaId": method["versionId"]}
    evaluation = get_or_create(api, path, by_key(api.pages(path), "nombre"), "nombre", payload)
    if evaluation["estado"] != "BORRADOR":
        raise RuntimeError("La evaluación de demostración debe estar en borrador")
    detail = path + "/" + evaluation["evaluacionId"]
    responses = defaultdict(list)
    for item in api.pages(detail + "/respuestas"):
        responses[item["grupo"]].append(item)
    revision = evaluation["revision"]

    def populate(group, target):
        nonlocal revision
        rows = responses[group]
        if len(rows) != len(target):
            raise RuntimeError(f"El número de elementos de {group} difiere del catálogo esperado")
        for row, expected in zip(rows, target):
            if row["respuesta"] == expected:
                continue
            if row["respuesta"] != answer("SIN_DECIDIR"):
                raise RuntimeError(f"La respuesta existente {row['codigo']} difiere de la muestra")
            result = api.call(detail + "/respuestas/" + row["respuestaId"], "PUT",
                              dict(expected, revisionEsperada=revision))
            revision = result["revisionEvaluacion"]

    for group, (scores, pending, undecided, excluded) in CONTROLS.items():
        populate(group, [answer("APLICA", score) for score in scores]
                 + [answer("APLICA")] * pending + [answer("SIN_DECIDIR")] * undecided
                 + [answer("NO_APLICA")] * excluded)
    for group, score in CLAUSES.items():
        populate(group, [answer("APLICA", score)] * len(responses[group]))
    results = api.call(detail + "/resultados")
    expected = {"total": 93, "aplicables": 85, "evaluados": 60, "pendientes": 20, "sinDecidir": 5,
                "noAplican": 8, "puntaje": 64, "avance": 70.59, "estado": "PROVISIONAL"}
    if results["controlesGlobal"] != expected:
        raise RuntimeError("El resultado del backend no concilia con el boceto")
    print("Evaluación verificada. 64,00 puntos provisionales y 70,59 % de avance.", flush=True)


def seed_assets(api, company):
    owner_path = company + "/responsables-negocio"
    process_path = company + "/procesos"
    asset_path = company + "/activos"
    owners = by_key(api.pages(owner_path), "nombre")
    processes = by_key(api.pages(process_path), "nombre")
    assets = by_key(api.pages(asset_path), "codigo")
    owner_ids = [get_or_create(api, owner_path, owners, "nombre",
                              {"nombre": name, "tipo": "PERSONA"})["responsableId"] for name in OWNERS]
    process_ids = [get_or_create(api, process_path, processes, "nombre",
                                {"nombre": name})["procesoId"] for name in PROCESSES]
    for i in range(48):
        name, kind = ASSETS[i % len(ASSETS)]
        site = i // len(ASSETS) + 1
        asset = get_or_create(api, asset_path, assets, "codigo", {
            "codigo": f"ACT-{i + 1:03}", "nombre": name if site == 1 else f"{name} · sede {site}",
            "descripcion": "Activo ficticio del inventario Panorama.", "tipo": kind,
            "procesoId": process_ids[i % len(process_ids)], "propietarioId": owner_ids[i % len(owner_ids)],
        })
        path = f"{asset_path}/{asset['activoId']}/clasificaciones"
        current = list(api.pages(path))
        pending = i >= 39
        personal = i % len(ASSETS) in {2, 5, 7}
        entries = {"confidencialidad": 5 if pending or i < 18 else 3 if i < 32 else 1,
                   "integridad": None if pending else 3 if i < 32 else 1,
                   "disponibilidad": 5 if i < 18 else 1, "fundamento": FUNDAMENTO,
                   "contieneDatosPersonales": personal}
        if personal:
            entries.update(contieneDatosMenores=False, tiposDatosPersonales=["IDENTIFICACION"],
                           finalidadTratamiento="Operación ficticia de la empresa de demostración.",
                           autorizacionTratamiento="SI")
        if len(current) > 1:
            raise RuntimeError(f"Historial de clasificación inesperado para {asset['codigo']}")
        valuation = current[0] if current else api.call(path, "POST", {"entradas": entries}, status=201)
        if any(valuation["entradas"].get(key) != value for key, value in entries.items()):
            raise RuntimeError(f"La clasificación existente de {asset['codigo']} difiere de la muestra")
        if not pending and not valuation["vigente"]:
            valuation = api.call(path + "/" + valuation["valoracionId"] + "/confirmar", "POST",
                                 {"revisionEsperada": valuation["revision"]})
        if valuation["vigente"] == pending:
            raise RuntimeError(f"Estado de clasificación inesperado para {asset['codigo']}")
    print("Inventario verificado. 48 activos, 39 clasificados y 9 pendientes.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True, help="Origen HTTPS local del backend, incluido el puerto")
    parser.add_argument("--ca", required=True, help="Certificado de la CA local")
    parser.add_argument("--company", required=True, type=UUID)
    parser.add_argument("--responsible", required=True, type=UUID, help="Membresía de Laura en Andina")
    parser.add_argument("--email", required=True)
    parser.add_argument("--password-file", type=Path, help="Archivo privado de credencial, fuera de Git")
    args = parser.parse_args()
    password = args.password_file.read_text(encoding="utf-8").rstrip("\r\n") if args.password_file else getpass()
    api = LocalApi(args.origin, args.ca)
    company = f"/empresas/{args.company}"
    signed_in = False
    try:
        api.login(args.email, password)
        signed_in = True
        if api.call(company + "/contexto")["nombre"] != COMPANY:
            raise RuntimeError("La carga requiere la empresa ficticia Andina Servicios S.A.S.")
        seed_evaluation(api, company, str(args.responsible))
        seed_assets(api, company)
    finally:
        try:
            if signed_in:
                api.call("/identidad/sesion", "DELETE", status=204)
        finally:
            api.conn.close()


if __name__ == "__main__":
    main()
