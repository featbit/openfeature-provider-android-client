"""Upload a signed bundle once, then wait for Central AUTOMATIC publication."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = "https://central.sonatype.com/api/v1/publisher"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Central:
    def __init__(self, username, password):
        if not username or not password:
            raise RuntimeError("Set CENTRAL_TOKEN_USERNAME and CENTRAL_TOKEN_PASSWORD in release environment secrets.")
        token = base64.b64encode((username + ":" + password).encode()).decode()
        self.authorization = "Bearer " + token
        self.secrets = (token, username, password)
        self.opener = urllib.request.build_opener(NoRedirect())

    def redact(self, value):
        text = json.dumps(value, ensure_ascii=True)
        for secret in self.secrets:
            text = text.replace(json.dumps(secret)[1:-1], "[REDACTED]")
        return json.loads(text)

    def post(self, path, body=b"", content_type="application/octet-stream"):
        request = urllib.request.Request(API + path, data=body, method="POST", headers={
            "Authorization": self.authorization, "Content-Type": content_type,
        })
        with self.opener.open(request, timeout=60) as response:
            return response.read().decode("utf-8")

    def upload(self, bundle, name):
        boundary = "featbit-" + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; '
                f'filename="central-bundle.zip"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()
        body += bundle.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        query = urllib.parse.urlencode({"name": name, "publishingType": "AUTOMATIC"})
        try:
            result = self.post("/upload?" + query, body, "multipart/form-data; boundary=" + boundary)
        except (OSError, urllib.error.URLError) as error:
            # A lost response can follow a successful upload; never retry this POST blindly.
            code = getattr(error, "code", "network error")
            raise RuntimeError(f"Central upload failed ({code}); inspect Portal deployments before retrying.") from None
        try:
            return str(uuid.UUID(result.strip()))
        except ValueError:
            raise RuntimeError("Unexpected upload response; inspect Portal deployments before retrying.") from None

    def status(self, deployment_id):
        return json.loads(self.post("/status?" + urllib.parse.urlencode({"id": deployment_id})))


def wait_for_publication(client, deployment_id, report, save, timeout=1800, interval=15):
    deadline = time.monotonic() + timeout
    previous = None
    while time.monotonic() < deadline:
        try:
            status = client.status(deployment_id)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504):
                raise RuntimeError(f"Central status HTTP {error.code}; deployment ID: {deployment_id}") from None
            time.sleep(interval)
            continue
        except (OSError, urllib.error.URLError):
            time.sleep(interval)
            continue
        if status.get("deploymentId") != deployment_id:
            raise RuntimeError("Central returned a different deployment ID.")
        state = status.get("deploymentState")
        report["state"] = state
        report["errors"] = client.redact(status.get("errors", {}))
        save()
        if state != previous:
            print("Central state: " + str(state), flush=True)
            previous = state
        if state == "PUBLISHED":
            return
        if state == "FAILED":
            raise RuntimeError("Central validation/publication failed: " + json.dumps(report["errors"]))
        if state not in ("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING"):
            raise RuntimeError("Unexpected Central deployment state.")
        time.sleep(interval)
    raise RuntimeError(f"Timed out waiting for PUBLISHED; deployment {deployment_id} may still publish. Inspect Portal before retrying.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?", args.version) or "SNAPSHOT" in args.version:
        parser.error("Invalid release version")
    if not args.bundle.is_file():
        parser.error("Bundle does not exist")
    client = Central(os.environ.get("CENTRAL_TOKEN_USERNAME"), os.environ.get("CENTRAL_TOKEN_PASSWORD"))
    report = {"version": args.version, "publishingType": "AUTOMATIC", "state": "NOT_UPLOADED",
              "bundle_sha256": hashlib.sha256(args.bundle.read_bytes()).hexdigest()}
    args.report.parent.mkdir(parents=True, exist_ok=True)

    def save():
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    save()
    try:
        deployment_id = client.upload(args.bundle, "co.featbit:featbit-openfeature-provider-android:" + args.version)
        report.update(deploymentId=deployment_id, state="UPLOADED")
        save()
        print("Central deployment ID: " + deployment_id, flush=True)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
                summary.write(f"Central deployment: `{deployment_id}` (automatic publication).\n\n")
        wait_for_publication(client, deployment_id, report, save)
    except Exception as error:
        report["error"] = client.redact(str(error))
        save()
        raise RuntimeError(report["error"]) from None
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(f"Maven Central confirmed **PUBLISHED** for `{args.version}`.\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
