"""CI-only disposable deployment: validates the actual kernel bounds and TLS recovery."""
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess
import tempfile
import time
import urllib.request
import uuid

REPO = Path(__file__).resolve().parents[2]
PROJECT = "mspi-rsrc-test-" + uuid.uuid4().hex[:10]
COMPOSE = ["docker", "compose", "-f", str(REPO / "deploy/resources/compose.yaml"), "-p", PROJECT]
PG = "postgres@sha256:3540c0362c809df084ffac0ae69fe794515f9de92692f02178bcdce0ef0302ac"
IMAGE = "mspi-runtime:hu-seg-007"
REDACT = []
ENV = os.environ.copy()


def sanitized(value):
    for secret in REDACT:
        value = value.replace(secret, "[redacted]")
    return value[-12000:]


def run(arguments, *, text_input=None, allow_failure=False, timeout=180, environment=None):
    result = subprocess.run(arguments, input=text_input, text=True, capture_output=True,
                            env=environment or ENV, cwd=REPO, timeout=timeout)
    if result.returncode and not allow_failure:
        raise RuntimeError("Fallo en prueba de recursos: " + sanitized(result.stdout + result.stderr))
    return result


def compose(*arguments, **options):
    return run(COMPOSE + list(arguments), **options)


def sql(text):
    return compose("exec", "-T", "database", "psql", "-U", "mspi_operator", "-d", "mspi",
                   "-v", "ON_ERROR_STOP=1", text_input=text).stdout


def container(service):
    return compose("ps", "-q", service).stdout.strip()


def wait_until(check, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, RuntimeError):
            pass
        time.sleep(1)
    raise RuntimeError("No se recupero el servicio dentro del plazo de prueba.")


def main():
    if os.name != "posix":
        raise RuntimeError("Este arnes desechable requiere el runner Linux de CI.")
    tls = Path(os.environ["MSPI_TEST_TLS_DIRECTORY"]).resolve()
    REDACT.append((tls / "server-password.txt").read_text().strip())
    with tempfile.TemporaryDirectory(prefix="mspi-resource-secrets-") as temporary:
        private = Path(temporary)
        operator = secrets.token_hex(32)
        runtime = secrets.token_hex(32)
        migrator = secrets.token_hex(32)
        REDACT.extend([operator, runtime, migrator])
        (private / "db-admin-password").write_text(operator)
        (private / "db-runtime-password").write_text(runtime)
        # Mount only dedicated runtime copies, owned by the non-root service identity.
        shutil.copyfile(tls / "server.p12", private / "server.p12")
        shutil.copyfile(tls / "server-password.txt", private / "server-password.txt")
        service_files = [private / name for name in ("server.p12", "server-password.txt", "db-admin-password", "db-runtime-password")]
        for file in service_files:
            file.chmod(0o600)
        run(["sudo", "-n", "chown", "10001:10001"] + [str(file) for file in service_files])
        ENV.update({"MSPI_TLS_KEYSTORE_FILE": str(private / "server.p12"),
                    "MSPI_TLS_PASSWORD_FILE": str(private / "server-password.txt"),
                    "MSPI_DB_ADMIN_PASSWORD_FILE": str(private / "db-admin-password"),
                    "MSPI_DB_RUNTIME_PASSWORD_FILE": str(private / "db-runtime-password"), "MSPI_HTTPS_PORT": "0"})
        try:
            config = json.loads(compose("config", "--format", "json").stdout)
            assert not config["services"]["database"].get("ports"), "La base no debe publicar puertos"
            compose("build", "backend", timeout=300)
            compose("up", "-d", "database")
            wait_until(lambda: compose("exec", "-T", "database", "pg_isready", "-h", "127.0.0.1", "-U", "mspi_operator", "-d", "mspi",
                                      allow_failure=True).returncode == 0)
            sql((REPO / "mspi/src/main/resources/db/provisioning/common.sql").read_text())
            sql("ALTER ROLE mspi_common_runtime PASSWORD '" + runtime + "';\n"
                "ALTER ROLE mspi_common_migrator PASSWORD '" + migrator + "';\n")
            migration_environment = ENV | {"MSPI_MIGRATION_JDBC_URL": "jdbc:postgresql://database:5432/mspi",
                                           "MSPI_MIGRATION_USER": "mspi_common_migrator", "MSPI_MIGRATION_PASSWORD": migrator}
            run(["docker", "run", "--rm", "--network", PROJECT + "_default", "--memory", "256m", "--pids-limit", "64",
                 "--entrypoint", "java", "-e", "MSPI_MIGRATION_JDBC_URL", "-e", "MSPI_MIGRATION_USER", "-e", "MSPI_MIGRATION_PASSWORD",
                 IMAGE, "-Xmx128m", "-Dloader.main=software.mspi.empresas.internal.migration.MigrationCli", "-cp", "/opt/mspi/app.jar",
                 "org.springframework.boot.loader.launch.PropertiesLauncher", "common"], environment=migration_environment)
            # Idempotent operational update for already provisioned databases, without DDL/data migration.
            sql((REPO / "mspi/src/main/resources/db/provisioning/runtime-budgets.sql").read_text())
            compose("up", "-d", "backend")
            address = compose("port", "backend", "8443").stdout.strip()
            assert address.startswith("127.0.0.1:"), "No se permite publicar en todas las interfaces"
            url = "https://" + address + "/api/v1"
            context = ssl.create_default_context(cafile=str(tls / "ca.crt"))

            def healthy():
                with urllib.request.urlopen(url, context=context, timeout=5) as response:
                    return response.status == 200

            wait_until(healthy)
            app = json.loads(run(["docker", "inspect", container("backend")]).stdout)[0]
            host = app["HostConfig"]
            assert host["Memory"] == 1073741824 and host["MemorySwap"] == 1073741824
            assert host["NanoCpus"] == 2000000000 and host["PidsLimit"] == 128 and host["ReadonlyRootfs"]
            assert app["Config"]["User"] == "10001:10001"
            assert not any("PASSWORD=" in value for value in app["Config"]["Env"]), "Las claves deben llegar como archivos"
            db = json.loads(run(["docker", "inspect", container("database")]).stdout)[0]["HostConfig"]
            assert db["Memory"] == 536870912 and db["NanoCpus"] == 1000000000
            assert db["PidsLimit"] == 96 and not db["PortBindings"]
            denied = compose("exec", "-T", "backend", "sh", "-c", "echo probe > /opt/mspi/write-probe", allow_failure=True)
            assert denied.returncode != 0, "La raiz debe estar protegida contra escritura"
            overflow = compose("exec", "-T", "backend", "sh", "-c",
                               "LC_ALL=C dd if=/dev/zero of=/tmp/mspi-budget-probe bs=1048576 count=140", allow_failure=True)
            assert overflow.returncode != 0, "El temporal debe impedir superar 128 MiB"
            assert "No space left on device" in overflow.stderr, "El rechazo debe deberse al espacio acotado"
            written = int(compose("exec", "-T", "backend", "stat", "-c", "%s", "/tmp/mspi-budget-probe").stdout.strip())
            assert 120 * 1048576 < written <= 128 * 1048576, "Debe comprobarse el agotamiento real del tmpfs"
            # Only this named test file is removed inside the disposable container.
            compose("exec", "-T", "backend", "rm", "-f", "/tmp/mspi-budget-probe")
            wait_until(healthy, seconds=20)
            print("Recursos reales: HTTPS validado, CPU/RAM/PID acotados, raiz protegida, base sin puertos y tmpfs agotado/recuperado.")
        except Exception:
            print(sanitized(compose("logs", "--no-color", "--tail", "80", "backend", "database", allow_failure=True).stdout))
            raise
        finally:
            if not re.fullmatch(r"mspi-rsrc-test-[0-9a-f]{10}", PROJECT):
                raise RuntimeError("Nombre de proyecto de prueba invalido para la limpieza")
            compose("down", "--volumes", "--remove-orphans", allow_failure=True)


if __name__ == "__main__":
    main()
