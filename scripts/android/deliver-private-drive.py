#!/usr/bin/env python3
"""Private, opt-in delivery. Run only after checking the test APK's identity and signer."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

DRIVE = "https://www.googleapis.com/drive/v3"
UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
OAUTH = "https://oauth2.googleapis.com/token"
FOLDER = "application/vnd.google-apps.folder"
APK = "application/vnd.android.package-archive"
SCOPE = "https://www.googleapis.com/auth/drive.file"
ID = re.compile(r"[A-Za-z0-9_-]{1,200}\Z")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def _object(response):
    data = json.loads(response.read(65_536))
    if not isinstance(data, dict):
        raise ValueError("Expected an object")
    return data


def login(opener, credentials):
    payload = {key: credentials[key] for key in ("client_id", "client_secret", "refresh_token")}
    if not all(isinstance(value, str) and value for value in payload.values()):
        raise ValueError("Incomplete OAuth credentials")
    payload["grant_type"] = "refresh_token"
    request = Request(OAUTH, urlencode(payload).encode(), method="POST")
    with opener.open(request, timeout=30) as response:
        token = _object(response)
    if "scope" in token and set(token["scope"].split()) != {SCOPE}:
        raise ValueError("Unexpected OAuth scope")
    return token["access_token"]


def validate_upload_url(value):
    uri = urlsplit(value)
    if (uri.scheme, uri.netloc, uri.path) != ("https", "www.googleapis.com", "/upload/drive/v3/files"):
        raise ValueError("Unexpected upload destination")
    if uri.fragment or not uri.query:
        raise ValueError("Invalid resumable session")
    return value


class Drive:
    def __init__(self, opener, token):
        self.opener = opener
        self.headers = {"Authorization": "Bearer " + token}

    def call(self, url, *, body=None, method="GET", headers=None):
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            headers = {"Content-Type": "application/json; charset=UTF-8", **(headers or {})}
        return self.opener.open(Request(url, body, {**self.headers, **(headers or {})}, method=method), timeout=180)

    def create_folder(self, name):
        with self.call(DRIVE + "/files?fields=id,ownedByMe,mimeType,trashed", method="POST",
                       body={"name": name, "mimeType": FOLDER}) as response:
            folder = _object(response)
        if folder.get("ownedByMe") is not True or folder.get("mimeType") != FOLDER or folder.get("trashed") is not False:
            raise ValueError("New folder not verified")
        folder_id = folder.get("id", "")
        if not ID.fullmatch(folder_id):
            raise ValueError("New folder ID not verified")
        self.require_owner_only(folder_id)
        return folder_id

    def require_owner_only(self, file_id):
        with self.call(f"{DRIVE}/files/{file_id}/permissions?fields=nextPageToken,permissions(type,role)") as response:
            info = _object(response)
        if info.get("nextPageToken") or info.get("permissions") != [{"type": "user", "role": "owner"}]:
            raise ValueError("Only the Drive owner may have access before delivery")

    def upload(self, folder_id, path, name, digest):
        size = path.stat().st_size
        fields = "id,parents,size,sha256Checksum"
        url = UPLOAD + "?" + urlencode({"uploadType": "resumable", "fields": fields})
        with self.call(url, method="POST", body={"name": name, "parents": [folder_id], "mimeType": APK},
                       headers={"X-Upload-Content-Type": APK, "X-Upload-Content-Length": str(size)}) as response:
            upload_url = validate_upload_url(response.headers.get("Location", ""))
        # A dedicated resumable session sends exactly one file. Do not repeat a PUT on an
        # ambiguous result or use a redirect that could receive the bearer token or APK.
        self.require_owner_only(folder_id)
        with path.open("rb") as src, self.call(upload_url, body=src, method="PUT",
                                               headers={"Content-Type": APK, "Content-Length": str(size)}) as response:
            output = _object(response)
        if (output.get("parents") != [folder_id] or output.get("size") != str(size) or
                output.get("sha256Checksum") != digest or not ID.fullmatch(output.get("id", ""))):
            raise ValueError("Uploaded file metadata did not match")
        self.require_owner_only(output["id"])


def deliver(drive, path, sha, run):
    if (path.name != "app-tamaTest.apk" or not path.is_file() or path.stat().st_size == 0 or
            not re.fullmatch(r"[0-9a-f]{40}", sha) or not re.fullmatch(r"[0-9]+-[0-9]+", run)):
        raise ValueError("Invalid verification inputs")
    with path.open("rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    folder_id = drive.create_folder(f"Tama V2 Test - Entrega privada - {sha[:12]} - {run}")
    drive.upload(folder_id, path, f"Tama-V2-Test-{sha[:12]}.apk", digest)
    return digest


def main():
    try:
        opener = build_opener(NoRedirect())
        token = login(opener, json.loads(os.environ["TAMA_TEST_DRIVE_OAUTH_JSON"]))
        # Derived credentials are not automatically masked by Actions. Never print them,
        # HTTP exceptions, the listing, Location headers or the new folder's ID.
        run = os.environ["GITHUB_RUN_ID"] + "-" + os.environ["GITHUB_RUN_ATTEMPT"]
        digest = deliver(Drive(opener, token), Path(sys.argv[1]), os.environ["GITHUB_SHA"], run)
    except Exception:
        print("::error::Private Drive delivery could not be confirmed. Check credentials and the new folder in Drive before running again.", file=sys.stderr)
        return 1
    print("Private Drive delivery verified. APK SHA-256: " + digest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
