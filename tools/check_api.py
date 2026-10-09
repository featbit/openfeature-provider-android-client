"""Compare the published JVM surface with an explicitly reviewed baseline."""
import argparse
import difflib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "provider/api/jvm-api.txt"


def snapshot(aar):
    with zipfile.ZipFile(aar) as archive:
        jar_bytes = archive.read("classes.jar")
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        classes = []
        for name in sorted(jar.namelist()):
            if not name.endswith(".class"):
                continue
            data = jar.read(name)
            if int.from_bytes(data[6:8], "big") > 55:
                raise ValueError("Bytecode exceeds Java 11: " + name)
            if not name.startswith("co/featbit/openfeature/android/"):
                raise ValueError("Unexpected bundled class: " + name)
            classes.append(name[:-6].replace("/", "."))
    if not classes:
        raise ValueError("Empty provider AAR")
    fd, temporary = tempfile.mkstemp(suffix=".jar", prefix="provider-api-")
    os.close(fd)
    path = Path(temporary)
    try:
        path.write_bytes(jar_bytes)
        executable = Path(os.environ["JAVA_HOME"]) / "bin" / ("javap.exe" if os.name == "nt" else "javap")
        output = subprocess.check_output([str(executable), "-public", "-s", "-classpath", str(path), *classes], text=True, encoding="utf-8", timeout=120)
        # Include Kotlin-internal JVM-public types and synthetic members: changes must be reviewed,
        # but inclusion in this baseline does not make them supported application APIs.
        lines = []
        visible = False
        for line in output.splitlines():
            if line.startswith("Compiled from"):
                continue
            if line.endswith("{"):
                visible = line.startswith("public ")
            if visible:
                lines.append(line.rstrip())
            if line == "}":
                visible = False
        return "\n".join(lines).strip() + "\n"
    finally:
        path.unlink()  # Only the one explicitly created temporary file.


def verify(aar, baseline=BASELINE):
    actual = snapshot(aar)
    expected = baseline.read_text(encoding="utf-8")
    if actual != expected:
        diff = "".join(difflib.unified_diff(expected.splitlines(True), actual.splitlines(True), fromfile="baseline", tofile="published AAR"))
        raise ValueError("JVM API changed; review before updating the baseline.\n" + diff)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, default=ROOT / "provider/build/outputs/aar/provider-release.aar")
    parser.add_argument("--update", action="store_true", help="Explicitly replace the reviewed baseline")
    args = parser.parse_args()
    if args.update:
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text(snapshot(args.aar), encoding="utf-8", newline="\n")
        print("Updated JVM API baseline; review its diff.")
    else:
        verify(args.aar)
        print("JVM API and Java 11 bytecode verified.")


if __name__ == "__main__":
    main()
