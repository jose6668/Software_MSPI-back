"""Check resolved Maven dependencies and OS packages in the MSPI deployment images."""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib import error, request

REPO = Path(__file__).resolve().parents[2]
OSV = "https://api.osv.dev/v1/querybatch"
BATCH_SIZE = 25
ADVISORY = re.compile(r"[A-Za-z0-9._:-]{1,120}")
DIGEST = re.compile(r"[^@\s]+@sha256:[a-f0-9]{64}")
TARGETS = ("backend", "database", "clamav")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path, content):
    path.write_text(json.dumps(content, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def coordinates(tree):
    pending = list(tree["children"])
    packages = set()
    while pending:
        node = pending.pop()
        name = f"{node['groupId']}:{node['artifactId']}"
        version = node["version"]
        if not ADVISORY.fullmatch(name) or not version or any(c.isspace() for c in version):
            raise ValueError("Coordenada Maven inválida en el árbol resuelto.")
        packages.add((name, version))
        pending.extend(node.get("children", []))
    if not packages:
        raise ValueError("El árbol resuelto no contiene dependencias.")
    return sorted(packages)


def post_osv(queries):
    data = json.dumps({"queries": queries}).encode()
    req = request.Request(OSV, data=data, headers={"Content-Type": "application/json"})
    for attempt in range(3):
        try:
            with request.urlopen(req, timeout=15) as response:
                return json.load(response)
        except error.HTTPError as failure:
            if failure.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise
        except (error.URLError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(2 ** attempt)


def osv_findings(packages, fetch=post_osv):
    found = set()
    for offset in range(0, len(packages), BATCH_SIZE):
        pending = [(package, None) for package in packages[offset:offset + BATCH_SIZE]]
        seen = set()
        for _ in range(20):
            if not pending:
                break
            queries = []
            for (name, version), token in pending:
                query = {"package": {"name": name, "ecosystem": "Maven"}, "version": version}
                if token:
                    query["page_token"] = token
                queries.append(query)
            results = fetch(queries)["results"]
            if not isinstance(results, list) or len(results) != len(pending):
                raise ValueError("OSV devolvió una respuesta incompleta.")
            following = []
            for (package, _), row in zip(pending, results, strict=True):
                if not isinstance(row, dict) or row.keys() - {"vulns", "next_page_token"}:
                    raise ValueError("Respuesta OSV inesperada.")
                for item in row.get("vulns", []):
                    advisory = item["id"]
                    if not ADVISORY.fullmatch(advisory):
                        raise ValueError("Identificador OSV inválido.")
                    found.add((*package, advisory))
                token = row.get("next_page_token")
                if token:
                    page = (package, token)
                    if page in seen:
                        raise ValueError("OSV repitió un token de paginación.")
                    seen.add(page)
                    following.append(page)
            pending = following
        if pending:
            raise ValueError("OSV superó el límite de paginación.")
    return [{"package": name, "version": version, "advisory": advisory}
            for name, version, advisory in sorted(found)]


def image_report(report, image, image_id):
    if (report["SchemaVersion"] != 2 or report["ArtifactType"] != "container_image"
            or report["ArtifactName"] != image or report["Metadata"]["ImageID"] != image_id):
        raise ValueError("El reporte Trivy no corresponde a la imagen solicitada.")
    os_results = [result for result in report["Results"] if result.get("Class") == "os-pkgs"]
    packages = sum(len(result.get("Packages") or []) for result in os_results)
    if not packages or not report["Metadata"].get("OS"):
        raise ValueError("Trivy no pudo inventariar los paquetes del sistema operativo.")
    findings = [entry for result in os_results for entry in (result.get("Vulnerabilities") or [])]
    counts = Counter(f["Severity"] for f in findings)
    blockers = [{"advisory": f["VulnerabilityID"], "package": f["PkgName"],
                 "installed": f["InstalledVersion"], "severity": f["Severity"],
                 "fix": f.get("FixedVersion") or None}
                for f in findings if f["Severity"] in ("HIGH", "CRITICAL")]
    return {"image": image, "image_id": image_id, "packages": packages,
            "findings_by_severity": dict(counts), "blocking_findings": blockers}


def command(args, *, cwd=REPO, env=None, capture=False):
    return subprocess.run(args, cwd=cwd, env=env, capture_output=capture, text=True,
                          encoding="utf-8", check=True, timeout=390).stdout


def images(backend):
    # Compose needs these variables to parse the files. No service or secret file is opened.
    dummy = {key: "/unused/mspi-scan" for key in
             ("MSPI_TLS_KEYSTORE_FILE", "MSPI_TLS_PASSWORD_FILE", "MSPI_DB_ADMIN_PASSWORD_FILE",
              "MSPI_DB_RUNTIME_PASSWORD_FILE", "MSPI_EVIDENCE_VOLUME")}
    raw = command(["docker", "compose", "-f", "deploy/resources/compose.yaml", "-f",
                   "deploy/antimalware/compose.yaml", "config", "--format", "json"],
                  env=os.environ | dummy, capture=True)
    services = json.loads(raw)["services"]
    targets = {key: services[key]["image"] for key in TARGETS}
    if backend:
        targets["backend"] = backend
    if any(not DIGEST.fullmatch(targets[key]) for key in ("database", "clamav")):
        raise ValueError("Las imágenes externas del despliegue deben estar fijadas por digest.")
    return targets


def scan_image(key, image, trivy, reports, cache):
    if key != "backend":
        command(["docker", "pull", image])
    image_id = command(["docker", "image", "inspect", "--format", "{{.Id}}", image], capture=True).strip()
    raw = reports / f"{key}.json"
    command([str(trivy), "image", "--cache-dir", str(cache), "--disable-telemetry",
             "--skip-version-check", "--no-progress", "--image-src", "docker", "--scanners", "vuln",
             "--pkg-types", "os", "--list-all-pkgs", "--ignorefile", "", "--exit-on-eol", "2",
             "--timeout", "5m", "--format", "json", "--output", str(raw), image], cwd=reports)
    return image_report(read_json(raw), image, image_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-tree", required=True, type=Path)
    parser.add_argument("--trivy", required=True, type=Path)
    parser.add_argument("--reports", required=True, type=Path)
    parser.add_argument("--cache", required=True, type=Path)
    parser.add_argument("--backend-image", help="Etiqueta local opcional del backend construido para el ensayo.")
    args = parser.parse_args()
    reports = args.reports.resolve()
    reports.mkdir(parents=True, exist_ok=False)
    result = {"maven": None, "images": {}, "errors": {}}
    recoverable = (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError)
    try:
        packages = coordinates(read_json(args.maven_tree))
        result["maven"] = {"packages": len(packages), "advisories": osv_findings(packages)}
        write_json(reports / "maven.json", {"inventory": packages, **result["maven"]})
    except recoverable as failure:
        result["errors"]["maven"] = str(failure)
    try:
        targets = images(args.backend_image)
        for key, image in targets.items():
            try:
                result["images"][key] = scan_image(key, image, args.trivy.resolve(), reports, args.cache.resolve())
            except recoverable as failure:
                result["errors"][key] = str(failure)
    except recoverable as failure:
        result["errors"]["compose"] = str(failure)
    complete = result["maven"] is not None and set(result["images"]) == set(TARGETS) and not result["errors"]
    blocked = (result["maven"] or {}).get("advisories") or any(
        scan["blocking_findings"] for scan in result["images"].values())
    result["status"] = "incomplete" if not complete else "findings" if blocked else "passed"
    write_json(reports / "summary.json", result)
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 2 if not complete else 1 if blocked else 0


if __name__ == "__main__":
    raise SystemExit(main())
