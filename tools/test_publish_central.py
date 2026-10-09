"""Central publisher contract tests; no real credentials or network requests."""
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch
import urllib.error
import uuid

import publish_central as publisher


class CentralPublicationTests(unittest.TestCase):
    def setUp(self):
        self.client = publisher.Central("test-user", "test-password")
        self.deployment = str(uuid.uuid4())

    def test_upload_uses_automatic_multipart_and_never_retries(self):
        bundle = Mock(spec=Path)
        bundle.read_bytes.return_value = b"signed zip bytes"
        with patch.object(self.client, "post", return_value=self.deployment) as post:
            self.assertEqual(self.client.upload(bundle, "co.featbit:artifact:0.1.0"), self.deployment)
            path, body, content_type = post.call_args.args
            self.assertIn("publishingType=AUTOMATIC", path)
            self.assertIn(b'name="bundle"', body)
            self.assertIn(b"signed zip bytes", body)
            self.assertIn("multipart/form-data; boundary=", content_type)
        with patch.object(self.client, "post", side_effect=urllib.error.URLError("lost response")) as post:
            with self.assertRaisesRegex(RuntimeError, "inspect Portal"):
                self.client.upload(bundle, "release")
            self.assertEqual(post.call_count, 1)

    def test_post_has_bearer_auth_and_bounded_request(self):
        response = Mock()
        response.read.return_value = b"response"
        self.client.opener = Mock()
        self.client.opener.open.return_value.__enter__ = Mock(return_value=response)
        self.client.opener.open.return_value.__exit__ = Mock(return_value=False)
        self.assertEqual(self.client.post("/status?id=test"), "response")
        call = self.client.opener.open.call_args
        self.assertEqual(call.kwargs["timeout"], 60)
        self.assertEqual(call.args[0].get_header("Authorization"),
                         "Bearer " + base64.b64encode(b"test-user:test-password").decode())

    def wait(self, states):
        report = {}
        save = Mock()
        with patch.object(self.client, "status", side_effect=states), \
                patch.object(publisher.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            publisher.wait_for_publication(self.client, self.deployment, report, save)
        return report, save

    def status(self, state, **extra):
        return dict(deploymentId=self.deployment, deploymentState=state, **extra)

    def test_only_published_is_success(self):
        report, save = self.wait([self.status(s) for s in
                                 ("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED")])
        self.assertEqual(report["state"], "PUBLISHED")
        self.assertEqual(save.call_count, 5)

    def test_failed_validation_is_an_error_and_redacts_secrets(self):
        with self.assertRaisesRegex(RuntimeError, "validation/publication failed") as error:
            self.wait([self.status("FAILED", errors={"pom": "test-password test-user"})])
        self.assertNotIn("test-password", str(error.exception))
        self.assertNotIn("test-user", str(error.exception))

    def test_transient_status_errors_retry_but_auth_does_not(self):
        retry = urllib.error.HTTPError("url", 503, "unavailable", {}, None)
        report, _ = self.wait([retry, self.status("PUBLISHED")])
        self.assertEqual(report["state"], "PUBLISHED")
        denied = urllib.error.HTTPError("url", 401, "unauthorized", {}, None)
        with self.assertRaisesRegex(RuntimeError, "HTTP 401"):
            self.wait([denied])

    def test_timeout_does_not_claim_failure_of_remote_publication(self):
        with patch.object(publisher.time, "monotonic", side_effect=[0, 1801]):
            with self.assertRaisesRegex(RuntimeError, "may still publish"):
                publisher.wait_for_publication(self.client, self.deployment, {}, Mock())

    def test_mismatched_deployment_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "different deployment"):
            self.wait([dict(deploymentId=str(uuid.uuid4()), deploymentState="PUBLISHED")])

    def test_missing_credentials_fail_before_requests(self):
        with self.assertRaisesRegex(RuntimeError, "environment secrets"):
            publisher.Central("", "")

    def test_main_retains_deployment_report_after_validation_failure(self):
        root = Path(__file__).resolve().parents[1] / "build/central-tests" / uuid.uuid4().hex
        root.mkdir(parents=True)
        bundle = root / "bundle.zip"
        bundle.write_bytes(b"controlled bundle")
        report = root / "report.json"
        with patch.object(sys, "argv", ["publish_central.py", "--bundle", str(bundle),
                "--version", "0.1.0", "--report", str(report)]), \
                patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(root / "summary.md")}), \
                patch.object(publisher, "Central", return_value=self.client), \
                patch.object(self.client, "upload", return_value=self.deployment), \
                patch.object(self.client, "status", return_value=self.status("FAILED", errors={"pom": "invalid"})), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "validation/publication failed"):
                publisher.main()
        saved = json.loads(report.read_text())
        self.assertEqual(saved["deploymentId"], self.deployment)
        self.assertEqual(saved["state"], "FAILED")
        self.assertEqual(saved["errors"], {"pom": "invalid"})
        self.assertIn(self.deployment, (root / "summary.md").read_text())

    def test_redirects_cannot_forward_credentials(self):
        self.assertIsNone(publisher.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://other.invalid"))


if __name__ == "__main__":
    unittest.main()
