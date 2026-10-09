"""Build and verify the provider's local AAR. No remote publishing or live server calls."""
import argparse
import datetime
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", help="Optional adb serial; installs only the two acceptance apps")
    args = parser.parse_args()
    run_id = datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
    output = ROOT / "build" / "acceptance" / run_id
    output.mkdir(parents=True)
    gradle = [str(ROOT / "gradlew.bat")] if os.name == "nt" else ["bash", str(ROOT / "gradlew")]
    def command(label, command):
        print(label, flush=True)
        with (output / (label + ".log")).open("w", encoding="utf-8") as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"{label} failed; see {output / (label + '.log')}")
    command("provider", gradle + ["spotlessCheck", ":provider:testDebugUnitTest", ":provider:lintDebug", ":provider:publishReleasePublicationToTestRepository", "--console=plain"])
    command("consumers", gradle + ["-p", "consumer-tests", ":java:assembleDebug", ":java:assembleRelease", ":kotlin:assembleDebug", ":kotlin:assembleRelease", "--console=plain"])
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
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    log = subprocess.check_output(adb + ["logcat", "-d", "-v", "brief", "-s", "ProviderAcceptance:I", "*:S"], text=True, encoding="utf-8", errors="replace")
                    (output / f"device-{language}-{variant}.log").write_text(log, encoding="utf-8")
                    if f"PROVIDER_SMOKE_PASS {language} {marker}" in log:
                        break
                    if f"PROVIDER_SMOKE_FAIL {language} {marker}" in log:
                        raise RuntimeError(f"Device smoke failed: {language}/{variant}; see {output}")
                    time.sleep(0.5)
                else:
                    raise RuntimeError(f"Device smoke timed out: {language}/{variant}; see {output}")
    print(f"Verification passed. Evidence: {output}")
    print("G02 remains blocked by the reproduced OpenFeature 0.9.0 status defect; this is not release approval.")

if __name__ == "__main__":
    main()
