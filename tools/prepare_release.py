"""Validate a release tag and package the signed publication without uploading it."""
import argparse
import hashlib
import os
from pathlib import Path
import re
from zipfile import ZIP_DEFLATED, ZipFile

import check_api
import check_publication as publication


def validate_tag(version, ref):
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?", version) or "SNAPSHOT" in version:
        raise ValueError("Invalid release version")
    if ref != "refs/tags/v" + version:
        raise ValueError("Select the matching v-prefixed release tag")


def bundle(repository, accepted_repository, version, destination):
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?", version) or "SNAPSHOT" in version:
        raise ValueError("Invalid release version")
    relative = Path("co/featbit") / publication.ARTIFACT / version
    stem = publication.ARTIFACT + "-" + version
    accepted_aar = accepted_repository / relative / (stem + ".aar")
    artifacts = publication.verify(repository, accepted_aar, version)
    check_api.verify(artifacts["aar"])
    files = []
    for artifact in artifacts.values():
        accepted = accepted_repository / relative / artifact.name
        if artifact.read_bytes() != accepted.read_bytes():
            raise ValueError("Signed publication differs from accepted bytes: " + artifact.name)
        signature = artifact.with_name(artifact.name + ".asc")
        if not signature.is_file() or not signature.read_bytes().startswith(b"-----BEGIN PGP SIGNATURE-----"):
            raise ValueError("Missing armored signature: " + artifact.name)
        for content in (artifact, signature):
            files.append(content)
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                checksum = content.with_name(content.name + "." + algorithm)
                if not checksum.is_file():
                    raise ValueError("Missing checksum: " + checksum.name)
                if checksum.read_text().strip() != hashlib.new(algorithm, content.read_bytes()).hexdigest():
                    raise ValueError("Invalid checksum: " + checksum.name)
                files.append(checksum)
    # Only allowlisted files for this version; no repository metadata or unrelated releases.
    with ZipFile(destination, "x", compression=ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, path.relative_to(repository).as_posix())
    with ZipFile(destination) as archive:
        if archive.testzip() is not None:
            raise ValueError("Corrupt bundle")
        for path in files:
            if archive.read(path.relative_to(repository).as_posix()) != path.read_bytes():
                raise ValueError("Bundle differs from staged files")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--check-tag", action="store_true")
    parser.add_argument("--repository", type=Path)
    parser.add_argument("--accepted-repository", type=Path)
    parser.add_argument("--bundle", type=Path)
    args = parser.parse_args()
    if args.check_tag:
        validate_tag(args.version, os.environ.get("GITHUB_REF", ""))
        return
    if not all((args.repository, args.accepted_repository, args.bundle)):
        parser.error("Packaging requires both repositories and --bundle")
    print(bundle(args.repository, args.accepted_repository, args.version, args.bundle))


if __name__ == "__main__":
    main()
