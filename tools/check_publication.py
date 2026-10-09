"""Validate the selected Maven snapshot or release, including generated API documentation."""
import hashlib
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

GROUP = "co.featbit"
ARTIFACT = "featbit-openfeature-provider-android"
VERSION = "0.1.0-SNAPSHOT"
EXPECTED = {
    ("co.featbit", "featbit-client-android"): "0.2.0",
    ("dev.openfeature", "kotlin-sdk"): "0.9.0",
    ("org.jetbrains.kotlin", "kotlin-stdlib"): "2.2.10",
}
RUNTIME = {**EXPECTED, ("org.jetbrains.kotlinx", "kotlinx-coroutines-android"): "1.8.1"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(repository, built_aar, version=VERSION):
    directory = Path(repository) / GROUP.replace(".", "/") / ARTIFACT / version
    metadata = None
    if version.endswith("-SNAPSHOT"):
        metadata = ET.parse(directory / "maven-metadata.xml").getroot()
        if [metadata.findtext(k) for k in ("groupId", "artifactId", "version")] != [GROUP, ARTIFACT, version]:
            raise ValueError("Wrong Maven coordinates")
    artifacts = {}
    for extension, classifier in (("aar", ""), ("pom", ""), ("module", ""), ("jar", "sources"), ("jar", "javadoc")):
        value = version
        if metadata is not None:
            entries = [v for v in metadata.findall("./versioning/snapshotVersions/snapshotVersion")
                       if v.findtext("extension") == extension and (v.findtext("classifier") or "") == classifier]
            if len(entries) != 1:
                raise ValueError("Missing or ambiguous snapshot: " + extension + classifier)
            value = entries[0].findtext("value")
        filename = ARTIFACT + "-" + value + ("-" + classifier if classifier else "") + "." + extension
        path = directory / filename
        if path.parent.resolve() != directory.resolve() or not path.is_file() or not path.stat().st_size:
            raise ValueError("Missing/invalid publication artifact: " + filename)
        checksum = path.with_name(path.name + ".sha256")
        if not checksum.is_file() or checksum.read_text().strip() != digest(path):
            raise ValueError("Invalid SHA-256 sidecar: " + filename)
        artifacts[extension if not classifier else classifier] = path
    if digest(artifacts["aar"]) != digest(built_aar):
        raise ValueError("Published AAR differs from this build")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(artifacts["pom"]).getroot()
    if [pom.findtext("m:" + k, namespaces=ns) for k in ("groupId", "artifactId", "version", "packaging")] != [GROUP, ARTIFACT, version, "aar"]:
        raise ValueError("Invalid POM coordinates/packaging")
    deps = {}
    for d in pom.findall("m:dependencies/m:dependency", ns):
        key = (d.findtext("m:groupId", namespaces=ns), d.findtext("m:artifactId", namespaces=ns))
        deps[key] = (d.findtext("m:version", namespaces=ns), d.findtext("m:scope", namespaces=ns))
    wanted = {key: (v, "compile" if key in EXPECTED else "runtime") for key, v in RUNTIME.items()}
    if deps != wanted or len(pom.findall("m:dependencies/m:dependency", ns)) != len(wanted):
        raise ValueError("Unexpected POM dependency versions/scopes: " + repr(deps))
    module = json.loads(artifacts["module"].read_text(encoding="utf-8"))
    if any(module["component"].get(k) != v for k, v in {"group": GROUP, "module": ARTIFACT, "version": version}.items()):
        raise ValueError("Invalid Gradle module coordinates")
    for usage, expected in (("java-api", EXPECTED), ("java-runtime", RUNTIME)):
        variants = [v for v in module["variants"] if v["attributes"].get("org.gradle.usage") == usage and v["attributes"].get("org.gradle.category") == "library"]
        if len(variants) != 1:
            raise ValueError("Missing or ambiguous module variant: " + usage)
        variant = variants[0]
        actual = {(d["group"], d["module"]): d["version"]["requires"] for d in variant["dependencies"]}
        if actual != expected or len(variant["dependencies"]) != len(expected):
            raise ValueError("Unexpected module dependencies: " + usage)
        files = variant.get("files", [])
        if len(files) != 1 or files[0].get("url") != ARTIFACT + "-" + version + ".aar" or files[0].get("name") != ARTIFACT + "-" + version + ".aar" or files[0].get("sha256") != digest(artifacts["aar"]) or files[0].get("size") != artifacts["aar"].stat().st_size:
            raise ValueError("Invalid module AAR digest/size")
    source_variants = [v for v in module["variants"] if v["attributes"].get("org.gradle.docstype") == "sources"]
    source_files = source_variants[0].get("files", []) if len(source_variants) == 1 else []
    if len(source_files) != 1 or source_files[0].get("url") != ARTIFACT + "-" + version + "-sources.jar" or source_files[0].get("size") != artifacts["sources"].stat().st_size or source_files[0].get("sha256") != digest(artifacts["sources"]):
        raise ValueError("Invalid module sources variant")
    with zipfile.ZipFile(artifacts["aar"]) as aar:
        if not aar.read("AndroidManifest.xml"):
            raise ValueError("Empty Android manifest")
        with zipfile.ZipFile(io.BytesIO(aar.read("classes.jar"))) as classes:
            for name in ("FeatBitProvider", "FeatBitOptionsFactory", "IdentityPolicy"):
                if "co/featbit/openfeature/android/" + name + ".class" not in classes.namelist():
                    raise ValueError("Missing public class: " + name)
    with zipfile.ZipFile(artifacts["sources"]) as sources:
        if not any(n.endswith("/FeatBitProvider.kt") or n == "FeatBitProvider.kt" for n in sources.namelist()):
            raise ValueError("Missing provider sources")
    with zipfile.ZipFile(artifacts["javadoc"]) as docs:
        if "index.html" not in docs.namelist() or not docs.read("index.html"):
            raise ValueError("Missing generated API documentation")
    return artifacts
