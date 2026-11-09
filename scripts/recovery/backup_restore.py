"""HU-029: cold backup and recovery for the bounded MSPI Docker Compose deployment."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import time
import uuid

IMAGE = "postgres@sha256:3540c0362c809df084ffac0ae69fe794515f9de92692f02178bcdce0ef0302ac"
DATA = "/var/lib/postgresql/data"
EVIDENCE = "/var/lib/mspi/evidence"
PROJECT = re.compile(r"[a-z0-9][a-z0-9_-]{0,48}")
OWNER = "com.docker.compose.project"
DB_VOLUME = "database-data"


def docker(*args, input_file=None, output_file=None, check=True):
    process = subprocess.run(["docker", *args], stdin=input_file,
                             stdout=output_file if output_file else subprocess.PIPE,
                             stderr=subprocess.PIPE, timeout=1800)
    if check and process.returncode:
        # Never copy PostgreSQL input or deployment credentials into the console.
        detail = process.stderr.decode("utf-8", errors="replace").strip().splitlines()
        raise RuntimeError(f"Docker {args[0]} falló: {detail[-1] if detail else process.returncode}")
    return process


def inspect(container):
    return json.loads(docker("inspect", container).stdout)[0]


def container(project, service):
    output = docker("ps", "-aq", "--filter", f"label={OWNER}={project}",
                    "--filter", f"label=com.docker.compose.service={service}").stdout.decode().split()
    if len(output) != 1:
        raise ValueError(f"Se esperaba exactamente un contenedor {service} en {project}.")
    return output[0]


def deployment(project):
    db, backend = (inspect(container(project, name)) for name in ("database", "backend"))
    volume = f"{project}_{DB_VOLUME}"
    db_mount = next((m for m in db["Mounts"] if m["Destination"] == DATA), {})
    files = next((m for m in backend["Mounts"] if m["Destination"] == EVIDENCE), {})
    if (db["Config"]["Image"] != IMAGE or db_mount.get("Name") != volume or files.get("Type") != "bind"
            or db["HostConfig"].get("PortBindings")):
        raise ValueError("Se requiere el despliegue MSPI con volumen PostgreSQL dedicado y evidencias en bind privado.")
    for binding in (backend["HostConfig"].get("PortBindings") or {}).values():
        if any(b["HostIp"] not in ("127.0.0.1", "::1") for b in binding):
            raise ValueError("Limite la API a loopback antes del ensayo de recuperación.")
    return db, backend, external_path(files["Source"]), volume


def external_path(value):
    path = Path(value).resolve()
    if any((parent / ".git").exists() for parent in (path, *path.parents)):
        raise ValueError("Use una ruta fuera de cualquier repositorio Git.")
    return path


def private_directory(path):
    path.mkdir(mode=0o700, exist_ok=False)
    if os.name == "nt":
        user = subprocess.check_output(["whoami", "/user", "/fo", "csv", "/nh"], text=True)
        sid = next(csv.reader(user.splitlines()))[1]
        if not re.fullmatch(r"S-\d+(?:-\d+)+", sid):
            raise ValueError("No se pudo identificar al operador Windows.")
        subprocess.run(["icacls", str(path), "/inheritance:r", "/grant:r", f"*{sid}:(OI)(CI)F"],
                       check=True, capture_output=True)


def sha256(path):
    with path.open("rb") as data:
        return hashlib.file_digest(data, "sha256").hexdigest()


def plain_path(path, directory=False):
    return not (path.is_symlink() or path.is_junction()) and (path.is_dir() if directory else path.is_file())


def evidence_index(root):
    if not plain_path(root, directory=True):
        raise ValueError("Volumen de evidencias inexistente o no seguro.")
    index = {}
    for company in sorted(root.iterdir()):
        if not plain_path(company, directory=True) or str(uuid.UUID(company.name)) != company.name:
            raise ValueError("Directorio de empresa inesperado en el volumen.")
        for item in sorted(company.iterdir()):
            if not plain_path(item, directory=True) or str(uuid.UUID(item.name)) != item.name:
                raise ValueError("Directorio de evidencia inesperado en el volumen.")
            content = item / "contenido"
            if list(item.iterdir()) != [content] or not plain_path(content):
                raise ValueError("Archivo de evidencia ausente o inesperado.")
            index[f"{company.name}/{item.name}/contenido"] = [content.stat().st_size, sha256(content)]
    return index


def sql(db, statement):
    output = docker("exec", db, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                    "-U", "mspi_operator", "-d", "mspi", "-c", statement).stdout.decode()
    return json.loads(output)


def inventory(db):
    common = sql(db, """SELECT jsonb_build_object(
      'empresas', COALESCE((SELECT jsonb_agg(jsonb_build_array(id, esquema_interno, estado, version_estructura) ORDER BY id)
          FROM comun.empresas), '[]'),
      'versiones_catalogo', COALESCE((SELECT jsonb_agg(jsonb_build_array(id, catalogo_id, version) ORDER BY id)
          FROM comun.versiones_catalogo), '[]'),
      'membresias', (SELECT count(*) FROM comun.membresias),
      'roles_membresias', (SELECT count(*) FROM comun.membresia_roles),
      'elementos_catalogo', (SELECT count(*) FROM comun.elementos_catalogo));""")
    files, companies = {}, {}
    for company, schema, state, version in common["empresas"]:
        if schema != "emp_" + uuid.UUID(company).hex:
            raise ValueError("El registro contiene un esquema inesperado.")
        installed = sql(db, f"SELECT to_json(to_regclass('{schema}.evidencias') IS NOT NULL);")
        if not installed:
            if state == "ACTIVA":
                raise ValueError("Empresa activa sin esquema de evidencias.")
            continue
        if version not in ("8", "9"):
            raise ValueError("El inventario de evidencias admite esquemas empresariales V8 y V9.")
        record = sql(db, f"""SELECT jsonb_build_object(
          'evaluaciones', (SELECT count(*) FROM "{schema}".evaluaciones),
          'cuota_ocupada', (SELECT bytes_ocupados FROM "{schema}".uso_evidencias),
          'evidencias', COALESCE((SELECT jsonb_agg(jsonb_build_array(empresa_id,id,tamano_bytes,
            huella_contenido,estado_inspeccion) ORDER BY id) FROM "{schema}".evidencias
            WHERE estado_carga = 'DISPONIBLE'), '[]'));""")
        if version == "9":
            record["controles"] = sql(db, f"""SELECT jsonb_build_object(
              'implementaciones', (SELECT count(*) FROM "{schema}".implementaciones_control),
              'versiones', (SELECT count(*) FROM "{schema}".versiones_implementacion_control),
              'aplicaciones', (SELECT count(*) FROM "{schema}".aplicaciones_control),
              'verificaciones', (SELECT count(*) FROM "{schema}".verificaciones_control));""")
        occupied = 0
        for owner, item, length, digest, _ in record["evidencias"]:
            if owner != company:
                raise ValueError("Evidencia con una empresa incorrecta.")
            files[f"{company}/{item}/contenido"] = [length, digest]
            occupied += length
        if occupied != record["cuota_ocupada"]:
            raise ValueError("El uso de cuota no coincide con las evidencias publicadas.")
        companies[company] = record
    return {"common": common, "companies": companies, "files": files}


def helper(volume, entry, *args, writable=False, **kwargs):
    mount = f"type=volume,source={volume},target=/data" + ("" if writable else ",readonly")
    stdin = ("-i",) if kwargs.get("input_file") is not None else ()
    return docker("run", "--rm", *stdin, "--network", "none", "--read-only", "--memory", "256m", "--pids-limit", "32",
                  "--security-opt", "no-new-privileges:true", "--mount", mount,
                  "--entrypoint", entry, IMAGE, *args, **kwargs)


def clean_cluster(volume):
    data = helper(volume, "pg_controldata", "/data").stdout.decode()
    if not re.search(r"^Database cluster state:\s+shut down\s*$", data, re.MULTILINE):
        raise ValueError("El clúster no quedó apagado limpiamente.")


def validate_archive(path):
    seen = set()
    with tarfile.open(path, mode="r:") as archive:
        for item in archive:
            name = PurePosixPath(item.name)
            if (name.is_absolute() or any(not re.fullmatch(r"[A-Za-z0-9_.-]+", part) or part == ".."
                                          for part in name.parts)
                    or name in seen or item.type not in (tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE)
                    or item.sparse or item.mode & 0o7000):
                raise ValueError("El archivo PostgreSQL contiene una entrada no segura.")
            seen.add(name)
        if not {PurePosixPath("PG_VERSION"), PurePosixPath("global/pg_control")}.issubset(seen):
            raise ValueError("El archivo PostgreSQL está incompleto.")


def copy_evidence(source, destination, files):
    for relative in files:
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / relative, target)
    if evidence_index(destination) != files:
        raise ValueError("Los archivos copiados no conservan sus huellas.")


def wait_database(db):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if docker("exec", db, "pg_isready", "-h", "127.0.0.1", "-U", "mspi_operator",
                  "-d", "mspi", check=False).returncode == 0:
            return
        time.sleep(1)
    raise RuntimeError("PostgreSQL no volvió a estar disponible.")


def backup(project, target):
    db, backend, source, volume = deployment(project)
    if not db["State"]["Running"]:
        raise ValueError("Arranque PostgreSQL antes de iniciar la copia.")
    target = external_path(target)
    if target.is_relative_to(source) or source.is_relative_to(target):
        raise ValueError("El respaldo y el volumen de evidencias deben estar separados.")
    private_directory(target)
    backend_running = backend["State"]["Running"]
    try:
        if backend_running:
            docker("stop", "-t", "45", backend["Id"])
        snapshot = inventory(db["Id"])
        if evidence_index(source) != snapshot["files"]:
            raise ValueError("Las evidencias y sus metadatos no coinciden. Concilie antes de copiar.")
        docker("stop", "-t", "60", db["Id"])
        if docker("ps", "-q", "--filter", f"volume={volume}").stdout.strip():
            raise ValueError("Otro contenedor mantiene abierto el volumen de PostgreSQL.")
        clean_cluster(volume)
        archive = target / "postgres.tar"
        with archive.open("xb") as out:
            helper(volume, "tar", "-C", "/data", "-cf", "-", ".", output_file=out)
        validate_archive(archive)
        private_directory(target / "evidencias")
        copy_evidence(source, target / "evidencias", snapshot["files"])
        manifest = {"format": 1, "source": project, "postgres_image": IMAGE, "image_id": db["Image"],
                    "database_sha256": sha256(archive), "inventory": snapshot}
        (target / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    finally:
        if not inspect(db["Id"])["State"]["Running"]:
            docker("start", db["Id"])
        wait_database(db["Id"])
        if backend_running and not inspect(backend["Id"])["State"]["Running"]:
            docker("start", backend["Id"])


def read_bundle(bundle):
    bundle = external_path(bundle)
    if not plain_path(bundle / "manifest.json") or not plain_path(bundle / "postgres.tar"):
        raise ValueError("Respaldo incompleto.")
    manifest = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
    if manifest["format"] != 1 or manifest["postgres_image"] != IMAGE:
        raise ValueError("Formato de respaldo o imagen PostgreSQL incompatible.")
    archive = bundle / "postgres.tar"
    if sha256(archive) != manifest["database_sha256"]:
        raise ValueError("La huella del archivo PostgreSQL no coincide.")
    validate_archive(archive)
    if evidence_index(bundle / "evidencias") != manifest["inventory"]["files"]:
        raise ValueError("Las huellas de las evidencias no coinciden.")
    return bundle, manifest


def restore(project, bundle, evidence):
    bundle, manifest = read_bundle(bundle)
    if not project.startswith("mspi-restore-") or project == manifest["source"]:
        raise ValueError("Use un proyecto nuevo con prefijo mspi-restore-.")
    image_id = json.loads(docker("image", "inspect", IMAGE).stdout)[0]["Id"]
    if image_id != manifest["image_id"]:
        raise ValueError("El destino requiere la misma variante de la imagen PostgreSQL.")
    volume = f"{project}_{DB_VOLUME}"
    evidence = external_path(evidence)
    if evidence.exists() or evidence.is_relative_to(bundle):
        raise ValueError("La carpeta de destino debe ser nueva y estar fuera del respaldo.")
    if (docker("ps", "-aq", "--filter", f"label={OWNER}={project}").stdout.strip()
            or docker("volume", "inspect", volume, check=False).returncode == 0):
        raise ValueError("El destino Docker ya existe.")
    private_directory(evidence)
    copy_evidence(bundle / "evidencias", evidence, manifest["inventory"]["files"])
    docker("volume", "create", "--label", f"{OWNER}={project}",
           "--label", f"com.docker.compose.volume={DB_VOLUME}", volume)
    with (bundle / "postgres.tar").open("rb") as archive:
        helper(volume, "tar", "-C", "/data", "--numeric-owner", "-xpf", "-", writable=True,
               input_file=archive)
    clean_cluster(volume)


def verify(project, bundle):
    _, manifest = read_bundle(bundle)
    db, _, source, _ = deployment(project)
    if inventory(db["Id"]) != manifest["inventory"] or evidence_index(source) != manifest["inventory"]["files"]:
        raise ValueError("El destino difiere del respaldo en datos o evidencias.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("backup", "restore", "verify"))
    parser.add_argument("--project", required=True)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--evidence", type=Path, help="Directorio nuevo, requerido para restore.")
    args = parser.parse_args()
    if not PROJECT.fullmatch(args.project) or (args.action == "restore") != bool(args.evidence):
        parser.error("Proyecto inválido o uso incorrecto de --evidence.")
    start = time.monotonic()
    try:
        if args.action == "backup":
            backup(args.project, args.bundle)
        elif args.action == "restore":
            restore(args.project, args.bundle, args.evidence)
        else:
            verify(args.project, args.bundle)
    except (KeyError, OSError, RuntimeError, ValueError, tarfile.TarError, subprocess.SubprocessError) as error:
        parser.exit(1, f"{error}\n")
    print(f"{args.action}: completado en {time.monotonic() - start:.2f} s.")


if __name__ == "__main__":
    main()
