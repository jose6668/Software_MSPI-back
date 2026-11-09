import unittest

from check_vulnerabilities import coordinates, image_report, osv_findings


class VulnerabilityCheckTests(unittest.TestCase):
    def test_resolved_tree_includes_transitive_and_test_artifacts(self):
        leaf = {"groupId": "org.example", "artifactId": "test", "version": "2", "scope": "test"}
        parent = {"groupId": "org.example", "artifactId": "parent", "version": "1", "children": [leaf]}
        self.assertEqual(coordinates({"children": [parent, leaf]}),
                         [("org.example:parent", "1"), ("org.example:test", "2")])
        with self.assertRaisesRegex(ValueError, "no contiene"):
            coordinates({"children": []})

    def test_osv_pagination_preserves_package_association(self):
        def fetch(queries):
            if "page_token" not in queries[0]:
                return {"results": [{}, {"next_page_token": "next", "vulns": [{"id": "GHSA-one"}]}]}
            self.assertEqual(len(queries), 1)
            self.assertEqual(queries[0]["package"]["name"], "org.example:b")
            return {"results": [{"vulns": [{"id": "GHSA-two"}]}]}
        self.assertEqual(osv_findings([("org.example:a", "1"), ("org.example:b", "2")], fetch),
                         [{"package": "org.example:b", "version": "2", "advisory": "GHSA-one"},
                          {"package": "org.example:b", "version": "2", "advisory": "GHSA-two"}])

    def test_osv_incomplete_result_fails(self):
        with self.assertRaisesRegex(ValueError, "incompleta"):
            osv_findings([("org.example:a", "1")], lambda _: {"results": []})

    def test_trivy_requires_requested_image_and_os_inventory(self):
        r = {"SchemaVersion": 2, "ArtifactType": "container_image", "ArtifactName": "example",
             "Metadata": {"ImageID": "sha256:a", "OS": {"Family": "alpine"}},
             "Results": [{"Class": "os-pkgs", "Packages": [{"Name": "p"}], "Vulnerabilities": [
                 {"VulnerabilityID": "CVE-1", "PkgName": "p", "InstalledVersion": "1", "Severity": "HIGH"},
                 {"VulnerabilityID": "CVE-2", "PkgName": "p", "InstalledVersion": "1", "Severity": "LOW"}]}]}
        result = image_report(r, "example", "sha256:a")
        self.assertEqual(result["findings_by_severity"], {"HIGH": 1, "LOW": 1})
        self.assertEqual(result["blocking_findings"][0]["advisory"], "CVE-1")
        self.assertIsNone(result["blocking_findings"][0]["fix"])
        with self.assertRaisesRegex(ValueError, "no corresponde"):
            image_report(r, "example", "sha256:b")
        r["Results"] = []
        with self.assertRaisesRegex(ValueError, "no pudo inventariar"):
            image_report(r, "example", "sha256:a")
