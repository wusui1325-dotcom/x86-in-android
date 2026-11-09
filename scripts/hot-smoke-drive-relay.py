#!/usr/bin/env python3
"""
HTTPS→Drive relay for smokeHot uploads.

Device POST/PUT raw log body here; we write into Drive folder x86-a-log, then
trash older smoke-*.log (keep latest only).

Auth (first match wins):
  1) GOOGLE_ACCESS_TOKEN env — ready Bearer token
  2) GOOGLE_SERVICE_ACCOUNT_JSON / GOOGLE_APPLICATION_CREDENTIALS — path to SA JSON
     (requires: pip install google-auth). Share folder x86-a-log with the SA email
     as Editor once.

Usage:
  export GOOGLE_ACCESS_TOKEN=ya29....
  # or: export GOOGLE_SERVICE_ACCOUNT_JSON=/path/to/sa.json
  export DRIVE_FOLDER_ID=1n2ZPm-GOQV66ikxfnPh6XRLbio5PE6fz
  python3 scripts/hot-smoke-drive-relay.py --port 8787

Device prefs (smokeHot):
  uploadMode=https
  uploadUrl=http://<host>:8787/upload/%s
  uploadMethod=POST
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

FOLDER_ID = os.environ.get("DRIVE_FOLDER_ID", "1n2ZPm-GOQV66ikxfnPh6XRLbio5PE6fz")
TOKEN = os.environ.get("GOOGLE_ACCESS_TOKEN", "").strip()
SA_JSON = (
    os.environ.get("GOOGLE_SERVICE_ACCOUNT_JSON", "").strip()
    or os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "").strip()
)
_cached_sa_token: tuple[str, float] | None = None


def resolve_token() -> str:
    global TOKEN, _cached_sa_token
    if TOKEN:
        return TOKEN
    if not SA_JSON:
        raise RuntimeError(
            "No credentials: set GOOGLE_ACCESS_TOKEN or GOOGLE_SERVICE_ACCOUNT_JSON"
        )
    now = time.time()
    if _cached_sa_token and _cached_sa_token[1] > now + 60:
        return _cached_sa_token[0]
    try:
        from google.oauth2 import service_account
        from google.auth.transport.requests import Request
    except ImportError as e:
        raise RuntimeError(
            "Service account JSON set but google-auth missing — pip install google-auth"
        ) from e
    scopes = ["https://www.googleapis.com/auth/drive"]
    creds = service_account.Credentials.from_service_account_file(SA_JSON, scopes=scopes)
    creds.refresh(Request())
    token = creds.token
    # cache ~50 minutes
    _cached_sa_token = (token, now + 3000)
    return token


def drive_request(method: str, url: str, data: bytes | None = None, content_type: str | None = None):
    token = resolve_token()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if content_type:
        req.add_header("Content-Type", content_type)
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.status, resp.read()


def upload_and_prune(name: str, body: bytes) -> dict:
    boundary = "relay_boundary"
    meta = json.dumps({"name": name, "parents": [FOLDER_ID]})
    parts = (
        f"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{meta}\r\n"
        f"--{boundary}\r\nContent-Type: text/plain\r\n\r\n"
    ).encode() + body + f"\r\n--{boundary}--\r\n".encode()
    status, raw = drive_request(
        "POST",
        "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name",
        data=parts,
        content_type=f"multipart/related; boundary={boundary}",
    )
    info = json.loads(raw.decode())
    keep_id = info["id"]
    q = urllib.parse.quote(f"'{FOLDER_ID}' in parents and trashed=false")
    _, listed = drive_request(
        "GET",
        f"https://www.googleapis.com/drive/v3/files?q={q}&fields=files(id,name,mimeType)&pageSize=100",
    )
    pruned = 0
    for f in json.loads(listed.decode()).get("files", []):
        fid, fname, mime = f.get("id"), f.get("name", ""), f.get("mimeType", "")
        if not fid or fid == keep_id:
            continue
        if mime == "application/vnd.google-apps.folder":
            continue
        if not (fname.startswith("smoke-") or fname.endswith(".log")):
            continue
        drive_request(
            "PATCH",
            f"https://www.googleapis.com/drive/v3/files/{fid}",
            data=b'{"trashed":true}',
            content_type="application/json; charset=UTF-8",
        )
        pruned += 1
    return {"ok": True, "id": keep_id, "name": name, "pruned": pruned, "http": status}


class Handler(BaseHTTPRequestHandler):
    def do_PUT(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def do_GET(self):
        if self.path.startswith("/health"):
            payload = json.dumps(
                {
                    "ok": True,
                    "folder": FOLDER_ID,
                    "token_env": bool(TOKEN),
                    "sa_json": bool(SA_JSON),
                }
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_response(404)
        self.end_headers()

    def _handle(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        name = self.headers.get("X-Smoke-Filename")
        if not name:
            m = re.search(r"/upload/([^/?]+)$", self.path)
            name = urllib.parse.unquote(m.group(1)) if m else "smoke-unknown.log"
        try:
            result = upload_and_prune(name, body)
            payload = json.dumps(result).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except Exception as e:
            msg = json.dumps({"ok": False, "error": str(e)}).encode()
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(msg)))
            self.end_headers()
            self.wfile.write(msg)

    def log_message(self, fmt, *args):
        sys.stderr.write("relay: " + (fmt % args) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--bind", default="0.0.0.0")
    args = ap.parse_args()
    print(
        f"Drive folder={FOLDER_ID} token_env={bool(TOKEN)} sa_json={bool(SA_JSON)} "
        f"listen={args.bind}:{args.port}"
    )
    if not TOKEN and not SA_JSON:
        print("WARNING: no credentials yet — /upload will 500 until env is set", file=sys.stderr)
    HTTPServer((args.bind, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
