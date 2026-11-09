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
