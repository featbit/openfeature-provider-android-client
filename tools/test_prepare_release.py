"""Release guards and bundle integrity tests; no signing credentials or upload."""
import hashlib
from pathlib import Path
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import prepare_release
import test_acceptance
import check_publication as publication


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        fixture = test_acceptance.PublicationTests()
        fixture.setUp()
        self.root = fixture.directory
        self.version = "0.1.0"
        relative = Path("co/featbit") / publication.ARTIFACT / self.version
        self.repository = self.root / "release"
        self.accepted = self.root / "accepted"
        target = self.repository / relative
        target.mkdir(parents=True)
        (self.accepted / relative).mkdir(parents=True)
        self.artifacts = {}
        for key, source in fixture.files.items():
            name = publication.ARTIFACT + "-" + self.version + {"aar": ".aar", "pom": ".pom", "module": ".module", "sources": "-sources.jar", "javadoc": "-javadoc.jar"}[key]
            content = source.read_bytes()
            if key in ("pom", "module"):
                content = content.replace(publication.VERSION.encode(), self.version.encode())
            path = target / name
            path.write_bytes(content)
            (self.accepted / relative / name).write_bytes(content)
            self.artifacts[key] = path
            signature = path.with_name(path.name + ".asc")
            signature.write_bytes(b"-----BEGIN PGP SIGNATURE-----\ncontrolled fixture")
            for data in (path, signature):
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    data.with_name(data.name + "." + algorithm).write_text(hashlib.new(algorithm, data.read_bytes()).hexdigest())
        self.destination = self.root / "central.zip"

    def package(self):
        # Real structure/checksum checks; the fixture contains no actual JVM bytecode.
        with patch("prepare_release.check_api.verify"):
            return prepare_release.bundle(self.repository, self.accepted, self.version, self.destination)

    def test_release_coordinates_and_bundle_layout(self):
        self.package()
        with ZipFile(self.destination) as archive:
            self.assertEqual(50, len(archive.namelist()))
            self.assertTrue(all(name.startswith("co/featbit/" + publication.ARTIFACT + "/0.1.0/") for name in archive.namelist()))
            self.assertFalse(any("maven-metadata" in name for name in archive.namelist()))

    def test_missing_signature_fails(self):
        signature = self.artifacts["aar"].with_name(self.artifacts["aar"].name + ".asc")
        signature.write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "signature"):
            self.package()

    def test_unsigned_vs_signed_drift_fails(self):
        path = self.accepted / self.artifacts["sources"].relative_to(self.repository)
        path.write_bytes(b"different accepted sources")
        with self.assertRaisesRegex(ValueError, "accepted bytes"):
            self.package()

    def test_invalid_signature_checksum_fails(self):
        path = self.artifacts["aar"]
        path.with_name(path.name + ".asc.sha1").write_text("wrong")
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.package()

    def test_matching_tag_only(self):
        prepare_release.validate_tag("0.1.0", "refs/tags/v0.1.0")
        for version, ref in [("0.1.0", "refs/heads/main"), ("0.1.0", "refs/tags/v0.2.0"), ("0.1.0-SNAPSHOT", "refs/tags/v0.1.0-SNAPSHOT"), ("../x", "refs/tags/v../x")]:
            with self.subTest(version=version, ref=ref), self.assertRaises(ValueError):
                prepare_release.validate_tag(version, ref)


if __name__ == "__main__":
    unittest.main()
