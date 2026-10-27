"""Two tiny Stremio addons for testing source fallback and the quality menu on the emulator.

  /a/manifest.json  "Test A": one 1080p stream whose link is dead (404)
  /b/manifest.json  "Test B": working 720p and 1080p streams

Run: python3 -I scripts/test-addons/server.py, then install
http://10.0.2.2:8765/a/manifest.json and .../b/manifest.json in an emulator that is NOT signed in
(the emulator reaches this Mac at 10.0.2.2). Binds to localhost only. The two test videos are
generated with ffmpeg on first run (two minutes each, test pattern with a tone).
"""
import json
import os
import re
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8765
BASE = f"http://10.0.2.2:{PORT}"
MEDIA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media")

MANIFESTS = {
    "a": {"id": "local.test.a", "version": "1.0.0", "name": "Test A", "description": "Dead link",
          "resources": ["stream"], "types": ["movie", "series"], "idPrefixes": ["tt"], "catalogs": []},
    "b": {"id": "local.test.b", "version": "1.0.0", "name": "Test B", "description": "Working links",
          "resources": ["stream"], "types": ["movie", "series"], "idPrefixes": ["tt"], "catalogs": []},
}

STREAMS = {
    "a": [{"name": "Test A", "title": "Broken source 1080p", "url": f"{BASE}/media/missing1080.mp4"}],
    "b": [
        {"name": "Test B", "title": "Working source 720p", "url": f"{BASE}/media/working720.mp4"},
        {"name": "Test B", "title": "Working source 1080p", "url": f"{BASE}/media/working1080.mp4"},
    ],
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stdout.write("%s %s\n" % (self.command, self.path))
        sys.stdout.flush()

    def _json(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET(head_only=True)

    def do_GET(self, head_only=False):
        m = re.match(r"^/([ab])/manifest\.json$", self.path)
        if m:
            return self._json(MANIFESTS[m.group(1)])
        m = re.match(r"^/([ab])/stream/[^/]+/[^/]+\.json$", self.path)
        if m:
            return self._json({"streams": STREAMS[m.group(1)]})
        m = re.match(r"^/media/([\w.]+)$", self.path)
        if m and os.path.isfile(os.path.join(MEDIA, m.group(1))):
            return self._file(os.path.join(MEDIA, m.group(1)), head_only)
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _file(self, path, head_only):
        size = os.path.getsize(path)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        if head_only:
            return
        with open(path, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            try:
                while remaining > 0:
                    chunk = f.read(min(1 << 16, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass


def ensure_media():
    os.makedirs(MEDIA, exist_ok=True)
    for name, size, tone in (("working720.mp4", "1280x720", 440), ("working1080.mp4", "1920x1080", 660)):
        path = os.path.join(MEDIA, name)
        if not os.path.isfile(path):
            subprocess.run([
                "ffmpeg", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", f"testsrc2=size={size}:rate=24",
                "-f", "lavfi", "-i", f"sine=frequency={tone}:sample_rate=48000",
                "-t", "120", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", path,
            ], check=True)


if __name__ == "__main__":
    ensure_media()
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
