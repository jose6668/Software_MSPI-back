import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import backup_restore as recovery


class RestoreSafetyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.archive = self.root / "postgres.tar"

    def archive_with(self, extra=None):
        with tarfile.open(self.archive, "w") as archive:
            for path, content in (("./PG_VERSION", b"17\n"), ("./global/pg_control", b"test")):
                item = tarfile.TarInfo(path)
                item.size = len(content)
                archive.addfile(item, io.BytesIO(content))
            if extra:
                archive.addfile(extra)

    def test_rejects_traversal_symlinks_and_duplicate_paths(self):
        link = tarfile.TarInfo("./pg_wal")
        link.type = tarfile.SYMTYPE
        link.linkname = "/tmp"
        for entry in (tarfile.TarInfo("../escapes"), link, tarfile.TarInfo("PG_VERSION")):
            with self.subTest(entry=entry.name):
                self.archive_with(entry)
                with self.assertRaisesRegex(ValueError, "entrada no segura"):
                    recovery.validate_archive(self.archive)

    def test_rejects_tampered_archive_before_docker(self):
        self.archive_with()
        manifest = {"format": 1, "postgres_image": recovery.IMAGE, "database_sha256": recovery.sha256(self.archive)}
        (self.root / "manifest.json").write_text(json.dumps(manifest))
        with self.archive.open("ab") as data:
            data.write(b"tampered")
        with patch.object(recovery, "docker") as docker:
            with self.assertRaisesRegex(ValueError, "huella del archivo"):
                recovery.restore("mspi-restore-test", self.root, self.root.parent / "new-data")
            docker.assert_not_called()

    def test_rejects_evidence_that_differs_from_manifest(self):
        self.archive_with()
        (self.root / "evidencias").mkdir()
        manifest = {"format": 1, "postgres_image": recovery.IMAGE, "database_sha256": recovery.sha256(self.archive),
                    "inventory": {"files": {"a/b/contenido": [2, "missing"]}}}
        (self.root / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "huellas de las evidencias"):
            recovery.read_bundle(self.root)

    def test_inventory_preserves_old_manifests_and_counts_actions_since_v12(self):
        company = "00000000-0000-4000-8000-000000000001"
        schema = "emp_" + company.replace("-", "")
        evidence = {"evaluaciones": 2, "cuota_ocupada": 0, "evidencias": []}
        controls = {"implementaciones": 1, "versiones": 2, "aplicaciones": 1, "verificaciones": 1}
        risks = {"registros": 1, "inherentes": 2, "vinculos": 1}
        residuals = {"valoraciones": 2, "verificaciones": 1, "avisos": 1}
        actions = {"acciones": 2, "contextos": 1, "eventos": 3}
        treatment = {"aceptaciones": 2, "cierres": 1}
        for version in ("8", "9", "10", "11", "12", "13", "14", "15"):
            registry = {"empresas": [[company, schema, "ACTIVA", version]]}
            queries = [registry, True, dict(evidence)]
            if version in ("9", "10", "11", "12", "13", "14", "15"):
                queries.append(controls)
            if version in ("10", "11", "12", "13", "14", "15"):
                queries.append(risks)
            if version in ("11", "12", "13", "14", "15"):
                queries.append(residuals)
            if version in ("12", "13", "14", "15"):
                queries.append(actions)
            if version in ("14", "15"):
                queries.append(treatment)
            with patch.object(recovery, "sql", side_effect=queries) as sql:
                inventory = recovery.inventory("postgres")
                expected = dict(evidence)
                if version in ("9", "10", "11", "12", "13", "14", "15"):
                    expected["controles"] = controls
                if version in ("10", "11", "12", "13", "14", "15"):
                    expected["riesgos"] = risks
                    self.assertTrue(any("valoraciones_inherentes" in call.args[1] for call in sql.call_args_list))
                if version in ("11", "12", "13", "14", "15"):
                    expected["residuales"] = residuals
                    self.assertTrue(any("residual_verificaciones" in call.args[1] for call in sql.call_args_list))
                if version in ("12", "13", "14", "15"):
                    expected["acciones"] = actions
                    self.assertTrue(any("acciones_mejora" in call.args[1] for call in sql.call_args_list))
                if version in ("14", "15"):
                    expected["tratamiento"] = treatment
                    self.assertIn("aceptaciones_riesgo", sql.call_args.args[1])
                self.assertEqual({company: expected}, inventory["companies"])
