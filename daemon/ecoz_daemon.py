#!/usr/bin/env python3
"""ecoz laptop service.

Receives links/text from the phone over HTTPS on the LAN and opens them here.

  ecoz_daemon.py pair            print the pairing code (copy it to the phone)
  ecoz_daemon.py serve           run the service (default)
  ecoz_daemon.py send <link|text>  push a link or text to the phone

Security model: TLS with a self-signed certificate (the phone pins its SHA-256
fingerprint) plus a random bearer token. Only http/https URLs are ever opened.
"""
import hashlib
import hmac
import http.client
import json
import os
import queue
import re
import secrets
import socket
import ssl
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, urlparse

PORT = int(os.environ.get("ECOZ_PORT", "8765"))
CONF = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "ecoz"
CERT, KEY, TOKEN_FILE = CONF / "cert.pem", CONF / "key.pem", CONF / "token"
MAX_BODY = 64 * 1024
GSCONNECT_CLIPBOARD = "org.gnome.Shell.Extensions.GSConnect.Clipboard"
GSCONNECT_CLIPBOARD_PATH = "/org/gnome/Shell/Extensions/GSConnect/Clipboard"


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))  # no packets sent; just picks the LAN route
        return s.getsockname()[0]
    finally:
        s.close()


def ensure_identity():
    CONF.mkdir(parents=True, exist_ok=True)
    os.chmod(CONF, 0o700)
    if not (CERT.exists() and KEY.exists()):
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt",
             "ec_paramgen_curve:prime256v1", "-nodes", "-days", "3650",
             "-subj", f"/CN={socket.gethostname()}", "-keyout", str(KEY),
             "-out", str(CERT)],
            check=True, capture_output=True)
        os.chmod(KEY, 0o600)
    if not TOKEN_FILE.exists():
        TOKEN_FILE.write_text(secrets.token_urlsafe(32))
        os.chmod(TOKEN_FILE, 0o600)
    return TOKEN_FILE.read_text().strip()


def fingerprint():
    der = ssl.PEM_cert_to_DER_cert(CERT.read_text())
    return hashlib.sha256(der).hexdigest()


def pairing_code(token):
    name = quote(socket.gethostname())
    return f"ecoz://{lan_ip()}:{PORT}/{token}/{fingerprint()}?name={name}"


def notify(title, body=""):
    subprocess.run(["notify-send", "-a", "ecoz", title, body], check=False)


def set_clipboard(text):
    # GSConnect's shell bridge is the only clipboard access that works on Wayland
    # without a focused window.
    subprocess.run(
        ["gdbus", "call", "--session", "--dest", GSCONNECT_CLIPBOARD,
         "--object-path", GSCONNECT_CLIPBOARD_PATH,
         "--method", f"{GSCONNECT_CLIPBOARD}.SetText", text],
        check=False, capture_output=True, timeout=5)


class Hub:
    """Fan-out of laptop->phone events with acknowledgement and retry.

    TCP can't tell us a phone has gone until a later write fails, so every event carries
    an id; the phone acks it, and anything unacked after RETRY seconds is sent again.
    The phone drops duplicates by id.
    """
    TTL = 600     # seconds an event stays deliverable
    RETRY = 4     # seconds to wait for an ack before re-sending
    MAX_PENDING = 20

    def __init__(self):
        self._lock = threading.Lock()
        self._listeners = set()
        self._pending = []   # events waiting for a phone to connect
        self._inflight = {}  # id -> (sent_at, event)

    def subscribe(self):
        q = queue.Queue()
        with self._lock:
            self._listeners.add(q)
            queued, self._pending = self._pending, []
        for event in queued:
            self._deliver(event)
        return q

    def unsubscribe(self, q):
        with self._lock:
            self._listeners.discard(q)

    def publish(self, event):
        event = {**event, "id": secrets.token_hex(6), "ts": time.time()}
        return self._deliver(event)

    def ack(self, event_id):
        with self._lock:
            self._inflight.pop(event_id, None)

    def reap(self):
        """Re-send events the phone never confirmed."""
        now = time.time()
        with self._lock:
            stale = [e for sent, e in self._inflight.values() if now - sent > self.RETRY]
            for e in stale:
                del self._inflight[e["id"]]
        for e in stale:
            self._deliver(e)

    def _deliver(self, event):
        with self._lock:
            if time.time() - event["ts"] > self.TTL:
                return "expired"
            if self._listeners:
                self._inflight[event["id"]] = (time.time(), event)
                for q in self._listeners:
                    q.put(event)
                return "delivered"
            self._pending.append(event)
            del self._pending[:-self.MAX_PENDING]
            return "queued"


HUB = Hub()


def check_item(kind, value):
    """Return an error string, or None if the item is safe to act on."""
    if kind == "url":
        parts = urlparse(value)
        if parts.scheme not in ("http", "https") or not parts.netloc:
            return "only http(s) urls"
    elif kind != "text":
        return "unknown type"
    return None


class Handler(BaseHTTPRequestHandler):
    server_version = "ecoz/0.1"
    token = ""

    def log_message(self, fmt, *args):  # keep journal quiet; never log bodies
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def _json(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authed(self):
        got = self.headers.get("Authorization", "")
        want = f"Bearer {self.token}"
        return hmac.compare_digest(got.encode(), want.encode())

    def _stream_events(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.flush()
        q = HUB.subscribe()
        try:
            while True:
                try:
                    chunk = f"data: {json.dumps(q.get(timeout=15))}\n\n"
                except queue.Empty:
                    chunk = ": keepalive\n\n"
                self.wfile.write(chunk.encode())
                self.wfile.flush()
        except OSError:  # phone went away (includes broken pipe / TLS reset)
            pass
        finally:
            HUB.unsubscribe(q)  # unacked events are re-sent by Hub.reap()

    def do_GET(self):
        if self.path not in ("/v1/ping", "/v1/events"):
            return self._json(404, {"ok": False})
        if not self._authed():
            return self._json(401, {"ok": False})
        if self.path == "/v1/events":
            return self._stream_events()
        self._json(200, {"ok": True, "name": socket.gethostname()})

    def do_POST(self):
        if self.path not in ("/v1/handoff", "/v1/push", "/v1/ack"):
            return self._json(404, {"ok": False})
        if not self._authed():
            return self._json(401, {"ok": False, "error": "unauthorized"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= MAX_BODY:
                raise ValueError("bad length")
            msg = json.loads(self.rfile.read(length))
            if self.path == "/v1/ack":
                HUB.ack(str(msg["id"]))
                return self._json(200, {"ok": True})
            kind, value = msg["type"], str(msg["value"])
        except (ValueError, KeyError, TypeError):
            return self._json(400, {"ok": False, "error": "bad request"})

        error = check_item(kind, value)
        if error:
            return self._json(400, {"ok": False, "error": error})

        if self.path == "/v1/push":  # laptop -> phone
            return self._json(200, {"ok": True, "status": HUB.publish({"type": kind, "value": value})})

        if kind == "url":  # phone -> laptop
            # Own transient unit, so the browser isn't part of this service's cgroup.
            launched = subprocess.run(
                ["systemd-run", "--user", "--collect", "--quiet", "xdg-open", value],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
            if not launched:
                subprocess.Popen(["xdg-open", value], stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, start_new_session=True)
            notify("Opened from phone", urlparse(value).netloc)
        else:
            set_clipboard(value)
            notify("Text from phone copied", value[:80])
        self._json(200, {"ok": True})


def publish_mdns():
    """Announce _ecoz._tcp via Avahi so the phone can find us after an IP change.

    The TXT record carries only a fingerprint prefix; the phone still pins the full
    certificate, so a spoofed announcement can't capture the token.
    """
    try:
        import gi
        gi.require_version("Gio", "2.0")
        from gi.repository import Gio, GLib

        bus = Gio.bus_get_sync(Gio.BusType.SYSTEM)

        def call(proxy, method, args=None):
            return proxy.call_sync(method, args, 0, -1, None)

        server = Gio.DBusProxy.new_sync(
            bus, 0, None, "org.freedesktop.Avahi", "/",
            "org.freedesktop.Avahi.Server", None)
        path = call(server, "EntryGroupNew").unpack()[0]
        group = Gio.DBusProxy.new_sync(
            bus, 0, None, "org.freedesktop.Avahi", path,
            "org.freedesktop.Avahi.EntryGroup", None)
        txt = [list(f"fp={fingerprint()[:16]}".encode())]
        call(group, "AddService", GLib.Variant(
            "(iiussssqaay)",
            (-1, -1, 0, socket.gethostname(), "_ecoz._tcp", "", "", PORT, txt)))
        call(group, "Commit")
        return group  # must stay referenced: Avahi drops the record when we disconnect
    except Exception as e:  # discovery is a convenience; pairing by IP still works
        print(f"mDNS announce unavailable: {e}", file=sys.stderr, flush=True)
        return None


def serve():
    token = ensure_identity()
    Handler.token = token
    mdns = publish_mdns()  # noqa: F841  (keep the reference alive)

    def reaper():
        while True:
            time.sleep(1)
            HUB.reap()

    threading.Thread(target=reaper, daemon=True).start()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(CERT, KEY)
    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print(f"ecoz listening on :{PORT}"
          f"{' (announced via mDNS)' if mdns else ''}", flush=True)
    httpd.serve_forever()


def send_to_phone(words):
    """CLI: ask the running service to push a link/text to the phone."""
    value = " ".join(words).strip()
    if not value:
        sys.exit("usage: ecoz_daemon.py send <link or text>")
    kind = "url" if re.fullmatch(r"https?://\S+", value) else "text"
    ctx = ssl.create_default_context(cafile=str(CERT))  # trust only our own certificate
    ctx.check_hostname = False
    conn = http.client.HTTPSConnection("127.0.0.1", PORT, context=ctx, timeout=5)
    try:
        conn.request("POST", "/v1/push", json.dumps({"type": kind, "value": value}),
                     {"Authorization": f"Bearer {ensure_identity()}"})
        resp = conn.getresponse()
        body = json.loads(resp.read() or b"{}")
    except OSError as e:
        sys.exit(f"service not reachable ({e}); is ecoz.service running?")
    if resp.status != 200:
        sys.exit(f"refused: {body.get('error', resp.status)}")
    print("sent to phone" if body["status"] == "delivered"
          else "phone not connected; queued for 10 minutes")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "serve"
    if cmd == "send":
        send_to_phone(sys.argv[2:])
    elif cmd == "pair":
        print(pairing_code(ensure_identity()))
    elif cmd == "serve":
        serve()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
