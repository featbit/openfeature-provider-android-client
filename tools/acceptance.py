"""Build and verify the provider's local AAR. No remote publishing or live server calls."""
import argparse
import datetime
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

import check_api
import check_publication

ROOT = Path(__file__).resolve().parents[1]

def run_command(label, argv, output):
    print(label, flush=True)
    log_path = output / (label + ".log")
    with log_path.open("w", encoding="utf-8") as log:
        result = subprocess.run(argv, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
    if result.returncode:
        raise RuntimeError(f"{label} failed; see {log_path}")


def require_tests(directory):
    reports = list(directory.glob("TEST-*.xml"))
    if not reports:
        raise RuntimeError("No executed test reports: " + str(directory))
    total = 0
    for report in reports:
        suite = ET.parse(report).getroot()
        if int(suite.get("failures", 0)) or int(suite.get("errors", 0)):
            raise RuntimeError("Failed tests: " + str(report))
        total += int(suite.get("tests", 0)) - int(suite.get("skipped", 0))
    if total <= 0:
        raise RuntimeError("No non-skipped tests: " + str(directory))
    return total


def device_result(log, language, marker):
    # Match complete messages and check failure first; old run identifiers never count.
    lines = [line.strip().split(": ", 1)[-1] for line in log.splitlines()]
    if f"PROVIDER_SMOKE_FAIL {language} {marker}" in lines:
        raise RuntimeError("Device smoke failed: " + marker)
    return f"PROVIDER_SMOKE_PASS {language} {marker}" in lines


def wait_device(read_log, log_path, language, marker, timeout=60, clock=time.monotonic, sleep=time.sleep):
    deadline = clock() + timeout
    while clock() < deadline:
        log = read_log()
        log_path.write_text(log, encoding="utf-8")
        if device_result(log, language, marker):
            return
        sleep(0.5)
    raise RuntimeError("Device smoke timed out: " + marker)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", help="Optional adb serial; installs only the two acceptance apps")
    parser.add_argument("--repository", type=Path, help="New, non-existing directory for the staged publication")
    parser.add_argument("--version", default="0.2.0")
    args = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?", args.version):
        parser.error("Invalid artifact version")
    run_id = datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
    output = ROOT / "build" / "acceptance" / run_id
    output.mkdir(parents=True)
    gradle = [str(ROOT / "gradlew.bat")] if os.name == "nt" else ["bash", str(ROOT / "gradlew")]
    repository = (args.repository or output / "repository").resolve()
    if repository.exists():
        raise RuntimeError("Use a new publication directory: " + str(repository))
    def command(label, argv):
        run_command(label, argv, output)
    command("script-tests", [sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"])
    command("provider", gradle + ["spotlessCheck", ":provider:testDebugUnitTest", ":provider:lintDebug", ":provider:lintRelease", ":provider:publishReleasePublicationToTestRepository", f"-PtestRepository={repository.as_posix()}", f"-PproviderVersion={args.version}", "--rerun-tasks", "--console=plain"])
    artifacts = check_publication.verify(repository, ROOT / "provider/build/outputs/aar/provider-release.aar", args.version)
    check_api.verify(artifacts["aar"])
    counts = {"provider": require_tests(ROOT / "provider/build/test-results/testDebugUnitTest")}
    tasks = [f":{language}:{task}" for language in ("java", "kotlin") for task in ("assembleDebug", "assembleRelease", "testDebugUnitTest", "lintDebug", "lintRelease")]
    command("consumers", gradle + ["-p", "consumer-tests", *tasks, f"-PtestRepository={repository.as_posix()}", f"-PproviderVersion={args.version}", "--rerun-tasks", "--console=plain"])
    for language in ("java", "kotlin"):
        counts[language] = require_tests(ROOT / "consumer-tests" / language / "build/test-results/testDebugUnitTest")
    if args.device:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise RuntimeError("Set ANDROID_HOME for device acceptance")
        adb = [str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")), "-s", args.device]
        for language in ("java", "kotlin"):
            for variant in ("debug", "release"):
                package = "co.featbit.provider.consumer." + language
                marker = run_id + "-" + language + "-" + variant
                apk = ROOT / "consumer-tests" / language / "build/outputs/apk" / variant / f"{language}-{variant}.apk"
                command(f"install-{language}-{variant}", adb + ["install", "-r", str(apk)])
                command(f"stop-{language}-{variant}", adb + ["shell", "am", "force-stop", package])
                command(f"start-{language}-{variant}", adb + ["shell", "am", "start", "-W", "-n", package + "/co.featbit.consumer." + language + ".SmokeActivity", "--es", "runId", marker])
                def read_log():
                    return subprocess.check_output(adb + ["logcat", "-d", "-v", "brief", "-s", "ProviderAcceptance:I", "*:S"], text=True, encoding="utf-8", errors="replace", timeout=30)
                wait_device(read_log, output / f"device-{language}-{variant}.log", language, marker)
    (output / "report.json").write_text(json.dumps({"tests": counts, "repository": str(repository), "artifacts": {k: {"path": str(v), "sha256": check_publication.digest(v)} for k, v in artifacts.items()}, "api": "passed", "device": "passed" if args.device else "not executed"}, indent=2), encoding="utf-8")
    print(f"Verification passed. Evidence: {output}")
    print("G02 remains blocked by the reproduced OpenFeature 0.9.0 status defect; this is not release approval.")

if __name__ == "__main__":
    main()
