"""Serves a phone version of the app (mobile.html) over your Wi-Fi.

The desktop app keeps doing the fetching; your iPhone opens this page in
Safari, and "Add to Home Screen" makes it launch like an app. Every API call
needs the secret key from mobile.json, which is part of the link / QR code the
desktop app shows, so other people on the network can't use it.
"""

import hmac
import json
import secrets
import socket
import struct
import sys
import threading
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from linkedin_jobs import HERE

CONFIG_FILE = HERE / "mobile.json"
PAGE_FILE = HERE / "mobile.html"
HOST = "0.0.0.0"  # listen on the local network, not just this PC
DEFAULT_PORT = 8765


def load_config():
    try:
        return json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    except FileNotFoundError:
        config = {"port": DEFAULT_PORT, "key": secrets.token_urlsafe(12)}
        CONFIG_FILE.write_text(json.dumps(config, indent=2), encoding="utf-8")
        return config


def lan_ip():
    """This PC's address on the local network (connecting a UDP socket sends nothing)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def make_icon(size=180):
    """Home-screen icon: a white briefcase on blue, as a PNG built without any imaging library."""
    bg, fg = (37, 99, 235), (255, 255, 255)
    k = size / 180

    def pixel(x, y):
        body = 36 * k <= x < 144 * k and 68 * k <= y < 138 * k
        handle = 66 * k <= x < 114 * k and 46 * k <= y < 68 * k and not (78 * k <= x < 102 * k and 56 * k <= y)
        seam = 94 * k <= y < 100 * k
        return fg if (body and not seam) or handle else bg

    rows = (b"\x00" + bytes(c for x in range(size) for c in pixel(x, y)) for y in range(size))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
            + chunk(b"IEND", b""))


class Server(ThreadingHTTPServer):
    allow_reuse_address = False  # on Windows, reuse would let two apps share the port silently
    daemon_threads = True

    def handle_error(self, request, client_address):
        # Phones drop connections all the time (screen locks, app switches); that's not an error.
        if not isinstance(sys.exc_info()[1], ConnectionError):
            super().handle_error(request, client_address)


class Handler(BaseHTTPRequestHandler):
    app = None  # the JobsApp, set by start()
    key = ""
    icon = b""

    def log_message(self, *args):
        pass  # no console under pythonw

    def reply(self, status, body, content_type="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/":
            self.reply(200, PAGE_FILE.read_bytes(), "text/html; charset=utf-8")
        elif path in ("/icon.png", "/apple-touch-icon.png"):
            self.reply(200, self.icon, "image/png")
        elif path == "/api/state":
            self.api(lambda body: self.app.web_state())
        else:
            self.reply(404, {"error": "Not found"})

    def do_POST(self):
        actions = {
            "/api/searches": lambda body: {"error": self.app.create_search(body.get("keywords", ""),
                                                                            body.get("location", ""))},
            "/api/searches/delete": lambda body: self.app.delete_searches([body.get("label", "")]),
            "/api/check": lambda body: self.app.check_all(),
        }
        action = actions.get(urlsplit(self.path).path)
        if action:
            self.api(action)
        else:
            self.reply(404, {"error": "Not found"})

    def api(self, action):
        if not hmac.compare_digest(self.headers.get("X-Key", "").encode(), self.key.encode()):
            self.reply(403, {"error": "This link's key is wrong. Scan the QR code in the desktop app again."})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length)) if length else {}
            result = self.app.run_on_ui(lambda: action(body))  # Tk may only be touched from its own thread
        except Exception as exc:
            self.reply(500, {"error": str(exc) or type(exc).__name__})
            return
        failed = isinstance(result, dict) and result.get("error")
        self.reply(400 if failed else 200, result or {"ok": True})


def start(app):
    """Start serving in the background; returns the link for the phone. Raises OSError if the port is taken."""
    config = load_config()
    handler = type("AppHandler", (Handler,), {"app": app, "key": config["key"], "icon": make_icon()})
    server = Server((HOST, config["port"]), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return f"http://{lan_ip()}:{config['port']}/?key={config['key']}"
