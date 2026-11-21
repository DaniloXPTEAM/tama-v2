"""Run the private delivery protocol with simulated responses, without network or secrets."""
import hashlib
import importlib.util
from io import BytesIO
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("delivery", ROOT / 'scripts/android/deliver-private-drive.py')
DELIVERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DELIVERY)


class Response(BytesIO):
    def __init__(self, body, headers=None):
        super().__init__(json.dumps(body).encode())
        self.headers = headers or {}


class FakeHTTP:
    def __init__(self, public=False, upload_url=None):
        self.requests = []
        self.contents = b""
        self.public = public
        self.upload_url = upload_url or DELIVERY.UPLOAD + "?upload_id=fixture"

    def open(self, request, timeout):
        url, verb = request.full_url, request.method
        self.requests.append((verb, url))
        if (verb, url) == ("POST", DELIVERY.OAUTH):
            assert b"grant_type=refresh_token" in request.data
            return Response({"scope": DELIVERY.SCOPE, "access_token": "fixture-token"})
        assert request.headers["Authorization"] == "Bearer fixture-token"
        if verb == "POST" and url.startswith(DELIVERY.DRIVE + "/files?"):
            body = json.loads(request.data)
            assert body["mimeType"] == DELIVERY.FOLDER and "parents" not in body
            return Response({"id": "new-folder", "mimeType": DELIVERY.FOLDER, "ownedByMe": True, "trashed": False})
        if verb == "GET" and "/permissions?" in url:
            permissions = [{"role": "owner", "type": "user"}]
            if self.public:
                permissions.append({"role": "reader", "type": "anyone"})
            return Response({"permissions": permissions})
        if verb == "POST" and url.startswith(DELIVERY.UPLOAD + "?"):
            body = json.loads(request.data)
            assert body["parents"] == ["new-folder"]
            return Response({}, {"Location": self.upload_url})
        if verb == "PUT" and url == self.upload_url:
            self.contents = request.data.read()
            return Response({"id": "new-file", "parents": ["new-folder"], "size": str(len(self.contents)),
                             "sha256Checksum": hashlib.sha256(self.contents).hexdigest()})
        raise AssertionError("Unexpected simulated operation")


class PrivateDrive(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.apk = Path(folder.name) / "app-tamaTest.apk"
        self.apk.write_bytes(b"synthetic apk bytes")

    def run_delivery(self, http):
        token = DELIVERY.login(http, dict(client_id="fixture", client_secret="fixture", refresh_token="fixture"))
        return DELIVERY.deliver(DELIVERY.Drive(http, token), self.apk, "c" * 40, "1-1")

    def test_only_creates_in_a_new_verified_owner_only_folder(self):
        http = FakeHTTP()
        result = self.run_delivery(http)
        self.assertEqual(self.apk.read_bytes(), http.contents)
        self.assertEqual(hashlib.sha256(http.contents).hexdigest(), result)
        self.assertEqual(["POST", "POST", "GET", "POST", "GET", "PUT", "GET"], [r[0] for r in http.requests])

    def test_rejects_public_folder_before_upload(self):
        http = FakeHTTP(public=True)
        with self.assertRaises(ValueError):
            self.run_delivery(http)
        self.assertEqual(b"", http.contents)
        self.assertFalse(any(url.startswith(DELIVERY.UPLOAD) for _, url in http.requests))

    def test_rejects_unexpected_upload_host_before_sending(self):
        http = FakeHTTP(upload_url="https://example.invalid/upload/drive/v3/files?upload_id=fixture")
        with self.assertRaises(ValueError):
            self.run_delivery(http)
        self.assertEqual(b"", http.contents)
        self.assertFalse(any(verb == "PUT" for verb, _ in http.requests))

    def test_real_workflow_is_manual_for_delivery_and_has_no_public_upload(self):
        text = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertIn('"arena/a4704b56-tama-v2"', text)
        self.assertIn('workflow_dispatch:', text)
        self.assertIn("github.ref == 'refs/heads/main'", text)
        self.assertEqual(2, text.count("if: ${{ github.event_name == 'workflow_dispatch' && inputs.deliver_to_drive }}"))
        self.assertLess(text.index('Verify separate APK identities'), text.index('Check stable signature'))
        self.assertLess(text.index('Check stable signature'), text.index('Deliver only to a new restricted'))
        self.assertNotIn('upload-artifact', text)
        self.assertNotIn('gh release', text)
