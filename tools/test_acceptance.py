"""Failure-path tests; no Gradle, device, or network required."""
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import xml.etree.ElementTree as ET

import acceptance
import check_api
import check_publication as publication


class AcceptanceTests(unittest.TestCase):
    def setUp(self):
        parent = acceptance.ROOT / "build/script-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(dir=parent))

    def test_command_failure_is_not_success(self):
        with patch("acceptance.subprocess.run", return_value=subprocess.CompletedProcess([], 1)):
            with self.assertRaisesRegex(RuntimeError, "failed"):
                acceptance.run_command("broken", ["unused"], self.directory)

    def test_command_timeout_propagates(self):
        with patch("acceptance.subprocess.run", side_effect=subprocess.TimeoutExpired("unused", 1)):
            with self.assertRaises(subprocess.TimeoutExpired):
                acceptance.run_command("timeout", ["unused"], self.directory)

    def test_no_source_or_all_skipped_fails(self):
        with self.assertRaises(RuntimeError):
            acceptance.require_tests(self.directory)
        (self.directory / "TEST-x.xml").write_text('<testsuite tests="2" skipped="2"/>')
        with self.assertRaises(RuntimeError):
            acceptance.require_tests(self.directory)

    def test_failed_test_report_fails(self):
        (self.directory / "TEST-x.xml").write_text('<testsuite tests="1" failures="1"/>')
        with self.assertRaises(RuntimeError):
            acceptance.require_tests(self.directory)

    def test_executed_tests_are_counted(self):
        (self.directory / "TEST-x.xml").write_text('<testsuite tests="3" skipped="1" failures="0" errors="0"/>')
        self.assertEqual(2, acceptance.require_tests(self.directory))

    def test_old_or_partial_marker_is_not_success(self):
        self.assertFalse(acceptance.device_result("PROVIDER_SMOKE_PASS java old", "java", "new"))
        self.assertFalse(acceptance.device_result("PROVIDER_SMOKE_PASS java new-suffix", "java", "new"))
        self.assertTrue(acceptance.device_result("I/ProviderAcceptance(123): PROVIDER_SMOKE_PASS java new", "java", "new"))

    def test_failure_wins_over_pass(self):
        with self.assertRaises(RuntimeError):
            acceptance.device_result("PROVIDER_SMOKE_PASS java new\nPROVIDER_SMOKE_FAIL java new", "java", "new")

    def test_device_timeout(self):
        ticks = iter([0, 0, 2])
        with self.assertRaisesRegex(RuntimeError, "timed out"):
            acceptance.wait_device(lambda: "PROVIDER_SMOKE_PASS java old", self.directory / "device.log", "java", "new", timeout=1, clock=lambda: next(ticks), sleep=lambda _: None)

    def test_device_success(self):
        acceptance.wait_device(lambda: "PROVIDER_SMOKE_PASS java new", self.directory / "device.log", "java", "new")

    def test_changed_api_fails(self):
        baseline = self.directory / "api.txt"
        baseline.write_text("expected\n")
        with patch("check_api.snapshot", return_value="changed\n"):
            with self.assertRaisesRegex(ValueError, "JVM API changed"):
                check_api.verify(Path("unused"), baseline)

    def test_java_bytecode_ceiling(self):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("co/featbit/openfeature/android/Example.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x3d")
        aar = self.directory / "bad.aar"
        with zipfile.ZipFile(aar, "w") as z:
            z.writestr("classes.jar", jar.getvalue())
        with self.assertRaisesRegex(ValueError, "Java 11"):
            check_api.snapshot(aar)


class PublicationTests(unittest.TestCase):
    def setUp(self):
        AcceptanceTests.setUp(self)
        self.repository = self.directory / "repo"
        self.artifact_dir = self.repository / "co/featbit" / publication.ARTIFACT / publication.VERSION
        self.artifact_dir.mkdir(parents=True)
        self.files = {}
        root = ET.Element("metadata")
        for k, v in (("groupId", publication.GROUP), ("artifactId", publication.ARTIFACT), ("version", publication.VERSION)):
            ET.SubElement(root, k).text = v
        versions = ET.SubElement(ET.SubElement(root, "versioning"), "snapshotVersions")
        for ext, classifier in (("aar", ""), ("pom", ""), ("module", ""), ("jar", "sources")):
            value = "0.1.0-20261009.120000-1"
            entry = ET.SubElement(versions, "snapshotVersion")
            for k, v in (("extension", ext), ("classifier", classifier), ("value", value)):
                ET.SubElement(entry, k).text = v
            name = publication.ARTIFACT + "-" + value + ("-" + classifier if classifier else "") + "." + ext
            self.files[classifier or ext] = self.artifact_dir / name
        ET.ElementTree(root).write(self.artifact_dir / "maven-metadata.xml")
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as z:
            for name in ("FeatBitProvider", "FeatBitOptionsFactory", "IdentityPolicy"):
                z.writestr("co/featbit/openfeature/android/" + name + ".class", b"fixture")
        with zipfile.ZipFile(self.files["aar"], "w") as z:
            z.writestr("classes.jar", jar.getvalue())
            z.writestr("AndroidManifest.xml", "manifest")
        with zipfile.ZipFile(self.files["sources"], "w") as z:
            z.writestr("co/featbit/openfeature/android/FeatBitProvider.kt", "class FeatBitProvider")
        pom = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
        for k, v in (("groupId", publication.GROUP), ("artifactId", publication.ARTIFACT), ("version", publication.VERSION), ("packaging", "aar")):
            ET.SubElement(pom, k).text = v
        deps = ET.SubElement(pom, "dependencies")
        for (group, name), version in publication.RUNTIME.items():
            dep = ET.SubElement(deps, "dependency")
            for k, v in (("groupId", group), ("artifactId", name), ("version", version), ("scope", "compile" if (group, name) in publication.EXPECTED else "runtime")):
                ET.SubElement(dep, k).text = v
        ET.ElementTree(pom).write(self.files["pom"])
        variants = []
        for usage, dependencies in (("java-api", publication.EXPECTED), ("java-runtime", publication.RUNTIME)):
            variants.append({"attributes": {"org.gradle.usage": usage, "org.gradle.category": "library"}, "dependencies": [{"group": g, "module": n, "version": {"requires": v}} for (g, n), v in dependencies.items()], "files": [{"name": publication.ARTIFACT + "-" + publication.VERSION + ".aar", "url": publication.ARTIFACT + "-" + publication.VERSION + ".aar", "sha256": publication.digest(self.files["aar"]), "size": self.files["aar"].stat().st_size}]})
        variants.append({"attributes": {"org.gradle.docstype": "sources"}, "files": [{"url": publication.ARTIFACT + "-" + publication.VERSION + "-sources.jar", "size": self.files["sources"].stat().st_size, "sha256": publication.digest(self.files["sources"])}]})
        self.module = {"component": {"group": publication.GROUP, "module": publication.ARTIFACT, "version": publication.VERSION}, "variants": variants}
        self.files["module"].write_text(json.dumps(self.module))
        self.built = self.directory / "built.aar"
        self.built.write_bytes(self.files["aar"].read_bytes())
        self.checksums()

    def checksums(self):
        for path in self.files.values():
            path.with_name(path.name + ".sha256").write_text(publication.digest(path))

    def verify(self):
        return publication.verify(self.repository, self.built)

    def test_valid_publication(self):
        self.assertEqual(4, len(self.verify()))

    def test_missing_sources_rejected_even_with_old_sources(self):
        self.files["sources"].rename(self.artifact_dir / "old-sources.jar")
        with self.assertRaisesRegex(ValueError, "Missing/invalid"):
            self.verify()

    def test_corruption_rejected(self):
        self.files["aar"].write_bytes(b"broken")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.verify()

    def test_stale_aar_rejected(self):
        self.built.write_bytes(b"new build")
        with self.assertRaisesRegex(ValueError, "differs"):
            self.verify()

    def test_wrong_pom_dependency_rejected(self):
        p = self.files["pom"]
        p.write_text(p.read_text().replace("0.2.0", "0.1.0"))
        self.checksums()
        with self.assertRaisesRegex(ValueError, "POM dependency"):
            self.verify()

    def test_wrong_module_dependency_rejected(self):
        self.module["variants"][0]["dependencies"][0]["version"]["requires"] = "wrong"
        self.files["module"].write_text(json.dumps(self.module))
        self.checksums()
        with self.assertRaisesRegex(ValueError, "module dependencies"):
            self.verify()

    def test_wrong_module_url_rejected(self):
        self.module["variants"][0]["files"][0]["url"] = "other.aar"
        self.files["module"].write_text(json.dumps(self.module))
        self.checksums()
        with self.assertRaisesRegex(ValueError, "digest/size"):
            self.verify()

    def test_wrong_module_digest_rejected(self):
        self.module["variants"][0]["files"][0]["sha256"] = "wrong"
        self.files["module"].write_text(json.dumps(self.module))
        self.checksums()
        with self.assertRaisesRegex(ValueError, "digest/size"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
