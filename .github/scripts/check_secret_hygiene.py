"""Reject sensitive tracked files and unsafe public environment templates."""

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


PRIVATE_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".dump", ".backup"}
PRIVATE_NAMES = {"id_rsa", "id_ed25519", ".pgpass", ".npmrc", ".pypirc", "credentials.json",
                 ".gitleaksignore", ".gitleaks.toml"}
SECRET_NAME = re.compile(r"PASSWORD|CONTRASENA|SECRET|TOKEN|PRIVATE_KEY|API_KEY|ACCESS_KEY|CREDENTIAL", re.I)
KEY = re.compile(r"[A-Z][A-Z0-9_]*")
EXAMPLES = [".env", ".env.local", ".env.production", "mspi/.env", "nested/.env.local",
            "credentials.pem", "nested/client.key", "backup.dump", "secrets/access.json",
            "nested/application-local.yaml"]


def sensitive_path(name):
    parts = name.replace("\\", "/").lower().split("/")
    base = parts[-1]
    return (any(p in {"secrets", ".secrets"} for p in parts)
            or (base.startswith(".env") and (base == ".env" or base.startswith(".env."))
                and base != ".env.example")
            or base in PRIVATE_NAMES or Path(base).suffix in PRIVATE_SUFFIXES
            or base in {"application-local.yml", "application-local.yaml",
                        "application-secrets.yml", "application-secrets.yaml"})


def template_errors(text):
    errors = []
    seen = set()
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not KEY.fullmatch(key) or key in seen:
            errors.append(f"linea {number}: formato o variable duplicada")
            continue
        seen.add(key)
        value = value.strip()
        if SECRET_NAME.search(key) and (value or key.startswith("VITE_")):
            errors.append(f"linea {number}: variable secreta en plantilla publica")
        if re.search(r"://[^/\s]*@|[?&](?:password|token|secret)=", value, re.I):
            errors.append(f"linea {number}: credenciales incorporadas en URL")
        if key.startswith("VITE_") and (key != "VITE_API_BASE_URL" or value != "/api"):
            errors.append(f"linea {number}: variable VITE no aprobada")
    return errors


def check(repo):
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=repo).decode("utf-8").split("\0")
    errors = [f"archivo sensible versionado: {name!r}" for name in paths if name and sensitive_path(name)]
    templates = {repo / name for name in paths if name and Path(name).name == ".env.example"}
    if (repo / ".env.example").exists():
        templates.add(repo / ".env.example")
    for template in templates:
        if template.is_symlink():
            errors.append(f"{template.relative_to(repo)}: plantilla no puede ser enlace simbolico")
            continue
        for error in template_errors(template.read_text(encoding="utf-8")):
            errors.append(f"{template.relative_to(repo)}: {error}")
    for name in EXAMPLES:
        result = subprocess.run(["git", "check-ignore", "--no-index", "--", name], cwd=repo,
                                capture_output=True)
        if result.returncode != 0:
            errors.append(f"falta exclusion: {name}")
    result = subprocess.run(["git", "check-ignore", "--no-index", "--", ".env.example"],
                            cwd=repo, capture_output=True)
    if result.returncode != 1:
        errors.append(".env.example debe poder versionarse")
    return errors


def self_test(repo, gitleaks):
    assert sensitive_path("nested/.env.local") and sensitive_path("secrets/account.json")
    assert not sensitive_path(".env.example") and not sensitive_path("db/migration/V1.sql")
    assert template_errors("SPRING_DATASOURCE_PASSWORD=short\n")
    assert template_errors("VITE_SECRET=\n")
    assert template_errors("VITE_API_BASE_URL=https://example.invalid\n")
    assert template_errors("NAME=a\nNAME=b\n")
    assert template_errors("SPRING_DATASOURCE_URL=jdbc:postgresql://user:pass@localhost/db\n")
    assert not template_errors("SPRING_DATASOURCE_PASSWORD=\nVITE_API_BASE_URL=/api\n")
    with tempfile.TemporaryDirectory(prefix="mspi-secret-check-") as temporary:
        sandbox = Path(temporary)
        subprocess.run(["git", "init", "-q"], cwd=sandbox, check=True, capture_output=True)
        shutil.copyfile(repo / ".gitignore", sandbox / ".gitignore")
        assert not check(sandbox), "exclusiones invalidas"
        (sandbox / ".env").write_text("LOCAL_ONLY=\n", encoding="utf-8")
        subprocess.run(["git", "add", "-f", ".env"], cwd=sandbox, check=True, capture_output=True)
        assert any("archivo sensible versionado" in error for error in check(sandbox))
        subprocess.run(["git", "rm", "--cached", "-q", ".env"], cwd=sandbox, check=True, capture_output=True)
        # Synthetic, never-valid token: created only in a disposable local repository.
        import secrets
        token = "gh" + "p_" + secrets.token_hex(20)
        (sandbox / "sample.txt").write_text("access_token = " + token + "\n", encoding="utf-8")
        report = sandbox / "report.json"
        common = ["--redact=100", "--no-banner", "--no-color", "--ignore-gitleaks-allow",
                  "--report-format=json", "--report-path=" + str(report)]
        result = subprocess.run([gitleaks, "dir", str(sandbox), *common], capture_output=True)
        assert result.returncode == 1, "el detector no rechazo el fixture sintetico"
        findings = json.loads(report.read_text(encoding="utf-8"))
        assert findings and token not in report.read_text(encoding="utf-8"), "reporte sin redactar"
        subprocess.run(["git", "add", "sample.txt"], cwd=sandbox, check=True, capture_output=True)
        commit = ["git", "-c", "user.name=Security fixture", "-c", "user.email=fixture@example.invalid",
                  "-c", "commit.gpgsign=false", "commit", "-qm"]
        subprocess.run([*commit, "synthetic fixture"], cwd=sandbox, check=True, capture_output=True)
        subprocess.run(["git", "rm", "-q", "sample.txt"], cwd=sandbox, check=True, capture_output=True)
        subprocess.run([*commit, "remove synthetic fixture"], cwd=sandbox, check=True, capture_output=True)
        result = subprocess.run([gitleaks, "git", str(sandbox), "--log-opts=--all --full-history", *common],
                                capture_output=True)
        assert result.returncode == 1, "el detector no encontro el secreto retirado del ultimo commit"
        assert token not in report.read_text(encoding="utf-8"), "historial sin redactar"
    print("Pruebas negativas: archivo forzado, plantilla, detector e historial correctos; sin valores expuestos.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--gitleaks", default="gitleaks")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    failures = check(root)
    if failures:
        for failure in failures:
            print(failure)
        raise SystemExit(1)
    if args.self_test:
        self_test(root, args.gitleaks)
    print("Higiene de secretos: correcta.")
