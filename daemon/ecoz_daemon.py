#!/usr/bin/env python3
"""ecoz laptop service.

Receives links/text from the phone over HTTPS on the LAN and opens them here.

  ecoz_daemon.py pair            print the pairing code (copy it to the phone)
  ecoz_daemon.py serve           run the service (default)
  ecoz_daemon.py send [link|text]   push a link or text to the phone
                                    (no argument: sends the current clipboard)
  ecoz_daemon.py pull               ask the phone to send its clipboard here
  ecoz_daemon.py sync [on|off]      show or switch automatic clipboard sync
  ecoz_daemon.py ring [stop]        make the phone ring (find my phone)
  ecoz_daemon.py notif-action <key> <index> [text]
                                    press a button on a phone notification (text = reply)

Security model: TLS with a self-signed certificate (the phone pins its SHA-256
fingerprint) plus a random bearer token. Only http/https URLs are ever opened.
"""
import base64
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
MAX_NOTIFICATION_BODY = 768 * 1024  # notifications may carry an app icon and a picture
MAX_IMAGE = 400 * 1024
CACHE = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "ecoz" / "notifications"
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


def open_in_browser(url, activation_token=None):
    """Open an http(s) link in the default browser. Never anything but http(s).

    On Wayland a window may only come to the front if it is given an activation token from
    the click that caused it. A notification button click provides one; without it the page
    opens in a tab behind everything else and looks like nothing happened.
    """
    if urlparse(url).scheme not in ("http", "https"):
        return
    token = activation_token if activation_token and re.fullmatch(r"[\w.:@/+=-]{1,300}", activation_token) else None
    env = [f"--setenv={name}={token}" for name in ("XDG_ACTIVATION_TOKEN", "DESKTOP_STARTUP_ID")] if token else []
    # Own transient unit, so the browser isn't part of this service's cgroup. xdg-open exits in
    # milliseconds while the browser launcher is still handing the link to the running browser;
    # with the default KillMode systemd would kill that handoff when xdg-open's unit ends, and
    # the page would never open. KillMode=process stops only xdg-open itself.
    launched = subprocess.run(
        ["systemd-run", "--user", "--collect", "--quiet", "--property=KillMode=process", *env, "xdg-open", url],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not launched:
        subprocess.Popen(["xdg-open", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True, env={**os.environ, **({"XDG_ACTIVATION_TOKEN": token} if token else {})})


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


# --- automatic clipboard sync -------------------------------------------------------
CLIP_OFF_FLAG = CONF / "clipboard-sync-off"   # file present = sync paused
SENSITIVE_MIMETYPES = ("x-kde-passwordManagerHint",)  # password managers mark their copies
_clip_lock = threading.Lock()
_last_clip = None  # last text synced in either direction; stops echoes and duplicates


def clip_sync_enabled():
    return not CLIP_OFF_FLAG.exists()


def remember_clip(text):
    global _last_clip
    with _clip_lock:
        _last_clip = text


def watch_clipboard():
    """Push every local clipboard change to the phone (laptop -> phone half of the sync)."""
    try:
        import gi
        gi.require_version("Gio", "2.0")
        from gi.repository import Gio, GLib
    except ImportError:
        return

    def call(bus, method, reply):
        return bus.call_sync(
            GSCONNECT_CLIPBOARD, GSCONNECT_CLIPBOARD_PATH, GSCONNECT_CLIPBOARD, method,
            None, GLib.VariantType(reply), Gio.DBusCallFlags.NONE, 3000, None).unpack()[0]

    def changed(bus):
        global _last_clip
        try:
            if not clip_sync_enabled() or any(m in SENSITIVE_MIMETYPES for m in call(bus, "GetMimetypes", "(as)")):
                return False
            text = call(bus, "GetText", "(s)")
        except GLib.Error:
            return False  # clipboard holds no text (an image, say) or the bridge is gone
        if not text or len(text.encode()) > MAX_BODY - 1024:
            return False
        with _clip_lock:
            if text == _last_clip:
                return False
            _last_clip = text
        HUB.publish({"type": "clip", "value": text}, queue_offline=False)
        return False

    def run():
        context = GLib.MainContext()
        context.push_thread_default()
        bus = Gio.bus_get_sync(Gio.BusType.SESSION)

        def on_signal(*_):
            # Wayland emits several signals per copy; settle briefly, then read once.
            # The timer must be attached to this thread's context, not GLib's default one.
            timer = GLib.timeout_source_new(150)
            timer.set_callback(changed, bus)
            timer.attach(context)

        bus.signal_subscribe(
            GSCONNECT_CLIPBOARD, GSCONNECT_CLIPBOARD, "OwnerChange", GSCONNECT_CLIPBOARD_PATH,
            None, Gio.DBusSignalFlags.NONE, on_signal)
        GLib.MainLoop(context).run()

    threading.Thread(target=run, daemon=True, name="clipboard-watch").start()


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

    def publish(self, event, queue_offline=True):
        event = {**event, "id": secrets.token_hex(6), "ts": time.time(), "queue": queue_offline}
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
            if e.get("type") != "clip":
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
            if not event.get("queue", True):
                return "dropped"  # e.g. clipboard changes: stale by the time a phone reconnects
            self._pending.append(event)
            del self._pending[:-self.MAX_PENDING]
            return "queued"


HUB = Hub()


# --- phone notifications mirrored onto the laptop -------------------------------------
MAX_FIELD = 500


def gmail_url(title, _text, subtext):
    """Gmail on the web, on the account the notification belongs to.

    Gmail's notification title is the sender (or "3 new messages") and its subtext is the
    account address. A search for recent mail from that sender lands on the message; when the
    title isn't a sender, the unread view does.
    """
    account = subtext if re.fullmatch(r"[^@\s]+@[^@\s]+", subtext or "") else None
    sender = (title or "").replace('"', "").strip()
    if sender.lower() == "me":  # Gmail names you "me" on mail you sent yourself; from:me is its own-account search
        query = "from:me newer_than:3d"
    elif sender and not re.match(r"\d+ new", sender):
        query = f'from:"{sender}" newer_than:3d'
    else:
        query = "is:unread"
    base = "https://mail.google.com/mail/" + (f"?authuser={quote(account)}" if account else "u/0/")
    return f"{base}#search/{quote(query, safe='')}"


# Apps whose "Reply" button only opens the phone app (no reply box): open the web app instead.
WEB_REPLY = {"com.google.android.gm": gmail_url}


def title_case(label):
    """'MARK AS READ' / 'mark as read' -> 'Mark As Read'. Words with digits are left alone."""
    return " ".join(w if any(c.isdigit() for c in w) else w[:1].upper() + w[1:].lower()
                    for w in label.split())


class PhoneNotifications:
    """Shows phone notifications through the desktop's notification service."""

    def __init__(self):
        self._lock = threading.Lock()
        self._ids = {}  # phone notification key -> desktop notification id
        self._actions = {}  # desktop id -> (phone key, title, routes, app, text, image path)
        self._tokens = {}  # desktop id -> Wayland activation token for the click in progress

    @staticmethod
    def save_image(encoded):
        """Decode a base64 image from the phone into the cache; returns its path or None.

        Only genuine PNG/JPEG data is accepted, and the file name comes from a hash of the
        content, so nothing the phone sends can choose a path or write anything else.
        """
        if not isinstance(encoded, str) or not encoded:
            return None
        try:
            data = base64.b64decode(encoded, validate=True)
        except ValueError:
            return None
        if not data or len(data) > MAX_IMAGE:
            return None
        if data.startswith(b"\x89PNG\r\n\x1a\n"):
            ext = "png"
        elif data.startswith(b"\xff\xd8\xff"):
            ext = "jpg"
        else:
            return None
        CACHE.mkdir(parents=True, exist_ok=True)
        os.chmod(CACHE, 0o700)
        path = CACHE / f"{hashlib.sha256(data).hexdigest()[:32]}.{ext}"
        if not path.exists():
            path.write_bytes(data)
            os.chmod(path, 0o600)
            PhoneNotifications._prune()
        return str(path)

    @staticmethod
    def _prune(keep=300):
        files = sorted(CACHE.iterdir(), key=lambda f: f.stat().st_mtime)
        for old in files[:-keep]:
            old.unlink(missing_ok=True)

    def _bus(self):
        import gi
        gi.require_version("Gio", "2.0")
        from gi.repository import Gio
        return Gio.bus_get_sync(Gio.BusType.SESSION)

    def _call(self, method, args, fmt, reply):
        from gi.repository import Gio, GLib
        return self._bus().call_sync(
            "org.freedesktop.Notifications", "/org/freedesktop/Notifications",
            "org.freedesktop.Notifications", method, GLib.Variant(fmt, args),
            GLib.VariantType(reply) if reply else None, Gio.DBusCallFlags.NONE, 3000, None)

    def show(self, key, app, title, text, app_icon=None, image=None, actions=(), package="", subtext=""):
        from gi.repository import GLib
        with self._lock:
            replaces = self._ids.get(key, 0)
        hints = {"image-path": GLib.Variant("s", image)} if image else {}
        # GNOME draws a few buttons: put Reply first, keep the rest in the phone's order.
        usable = sorted((a for a in actions if isinstance(a, dict) and a.get("title")),
                        key=lambda a: not a.get("reply"))[:3]
        buttons, routes = [], {}
        for a in usable:
            button_id = f"a{int(a['index'])}"
            buttons += [button_id, title_case(str(a["title"]))[:30]]
            web_url = None
            if not a.get("reply") and str(a["title"]).strip().lower() == "reply" and package in WEB_REPLY:
                web_url = WEB_REPLY[package](title, text, subtext)
            routes[button_id] = (int(a["index"]), bool(a.get("reply")), web_url)
        reply = self._call(
            "Notify",
            (app[:80], replaces, app_icon or "phone-symbolic", GLib.markup_escape_text(title[:MAX_FIELD]),
             GLib.markup_escape_text(text[:MAX_FIELD]), buttons, hints, -1),
            "(susssasa{sv}i)", "(u)")
        with self._lock:
            desktop_id = reply.unpack()[0]
            self._ids[key] = desktop_id
            self._actions[desktop_id] = (key, title, routes, app, text, image or app_icon or "")
            if len(self._actions) > 200:
                self._actions.pop(next(iter(self._actions)))
            if len(self._ids) > 200:  # bounded: forget the oldest entries
                self._ids.pop(next(iter(self._ids)))

    def remember_token(self, desktop_id, token):
        with self._lock:
            self._tokens[desktop_id] = token
            if len(self._tokens) > 50:
                self._tokens.pop(next(iter(self._tokens)))

    def action_invoked(self, desktop_id, button_id):
        """A button on one of our notifications was pressed: reply window or straight to the phone."""
        with self._lock:
            entry = self._actions.get(desktop_id)
            token = self._tokens.pop(desktop_id, None)
        route = entry[2].get(button_id) if entry else None
        if entry is None and re.fullmatch(r"a\d", button_id):
            # One of our buttons on a notification shown before the service last restarted:
            # the link to the phone's notification is gone, so say so instead of doing nothing.
            print(f"action pressed on an unknown notification id={desktop_id}", file=sys.stderr, flush=True)
            notify("ecoz", "That notification is out of date. Reply to the newest one instead.")
        if not route:
            return
        print(f"action pressed: button={button_id} reply={route[1]} web={bool(route[2])}", file=sys.stderr, flush=True)
        key, title, _, app, text, picture = entry
        index, is_reply, web_url = route
        if web_url:  # the phone's Reply only opens the phone app; open the web app here instead
            open_in_browser(web_url, token)
        elif is_reply:  # needs text: ask for it
            subprocess.Popen(
                [sys.executable, "-I", str(Path(__file__).with_name("ecoz_reply.py")),
                 key, str(index), title, app, text, picture],
                stdin=subprocess.DEVNULL, start_new_session=True)
        else:
            HUB.publish({"type": "notif-action", "value": json.dumps({"key": key, "index": index})},
                        queue_offline=False)

    def dismiss(self, key):
        with self._lock:
            desktop_id = self._ids.pop(key, None)
            self._actions.pop(desktop_id, None)
        if desktop_id:
            self._call("CloseNotification", (desktop_id,), "(u)", None)


PHONE_NOTIFICATIONS = PhoneNotifications()


def watch_notification_actions():
    """Route button presses on mirrored notifications back to the phone."""
    try:
        import gi
        gi.require_version("Gio", "2.0")
        from gi.repository import Gio, GLib
    except ImportError:
        return

    def run():
        context = GLib.MainContext()
        context.push_thread_default()
        bus = Gio.bus_get_sync(Gio.BusType.SESSION)

        def on_signal(_bus, _sender, _path, _iface, _signal, params):
            desktop_id, button_id = params.unpack()
            PHONE_NOTIFICATIONS.action_invoked(desktop_id, button_id)

        def on_token(_bus, _sender, _path, _iface, _signal, params):
            desktop_id, token = params.unpack()  # arrives just before ActionInvoked for the same click
            PHONE_NOTIFICATIONS.remember_token(desktop_id, token)

        for signal, handler in (("ActivationToken", on_token), ("ActionInvoked", on_signal)):
            bus.signal_subscribe(
                "org.freedesktop.Notifications", "org.freedesktop.Notifications", signal,
                "/org/freedesktop/Notifications", None, Gio.DBusSignalFlags.NONE, handler)
        GLib.MainLoop(context).run()

    threading.Thread(target=run, daemon=True, name="notification-actions").start()


def check_item(kind, value, allowed=("url", "text")):
    """Return an error string, or None if the item is safe to act on."""
    if kind not in allowed:
        return "unknown type"
    if kind == "clip" and not value:
        return "empty clipboard"
    if kind == "ring" and value not in ("start", "stop"):
        return "bad ring command"
    if kind == "notif-action":
        try:
            action = json.loads(value)
            ok = (isinstance(action.get("key"), str) and 0 < len(action["key"]) <= 300
                  and isinstance(action.get("index"), int) and 0 <= action["index"] < 10
                  and len(str(action.get("text", ""))) <= 2000)
        except (ValueError, AttributeError):
            ok = False
        if not ok:
            return "bad notification action"
    if kind == "url":
        parts = urlparse(value)
        if parts.scheme not in ("http", "https") or not parts.netloc:
            return "only http(s) urls"
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

    def _phone_notification(self, dismiss):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= MAX_NOTIFICATION_BODY:
                raise ValueError("bad length")
            msg = json.loads(self.rfile.read(length))
            key = str(msg["key"])[:300]
            if dismiss:
                PHONE_NOTIFICATIONS.dismiss(key)
            else:
                PHONE_NOTIFICATIONS.show(
                    key, str(msg.get("app", "Phone")), str(msg.get("title", "")), str(msg.get("text", "")),
                    app_icon=PhoneNotifications.save_image(msg.get("appIcon")),
                    image=PhoneNotifications.save_image(msg.get("image")),
                    actions=msg.get("actions") if isinstance(msg.get("actions"), list) else (),
                    package=str(msg.get("package", ""))[:200], subtext=str(msg.get("subtext", ""))[:200])
        except (ValueError, KeyError, TypeError):
            return self._json(400, {"ok": False, "error": "bad request"})
        except Exception as e:  # desktop notification service unavailable
            print(f"notification failed: {e}", file=sys.stderr, flush=True)
            return self._json(502, {"ok": False, "error": "desktop notifications unavailable"})
        self._json(200, {"ok": True})

    def do_POST(self):
        if self.path not in ("/v1/handoff", "/v1/push", "/v1/ack", "/v1/notification", "/v1/notification/dismiss"):
            return self._json(404, {"ok": False})
        if not self._authed():
            return self._json(401, {"ok": False, "error": "unauthorized"})
        if self.path.startswith("/v1/notification"):
            return self._phone_notification(dismiss=self.path.endswith("/dismiss"))
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

        # "pull" (ask the phone for its clipboard) only makes sense laptop -> phone
        allowed = ("url", "text", "pull", "clip", "ring", "notif-action") if self.path == "/v1/push" else ("url", "text", "clip")
        error = check_item(kind, value, allowed)
        if error:
            return self._json(400, {"ok": False, "error": error})

        if self.path == "/v1/push":  # laptop -> phone
            # a ring request is pointless minutes later, so it is never queued for a reconnect
            status = HUB.publish({"type": kind, "value": value}, queue_offline=(kind not in ("ring", "notif-action")))
            return self._json(200, {"ok": True, "status": status})

        if kind == "url":  # phone -> laptop
            open_in_browser(value)
            notify("Opened from phone", urlparse(value).netloc)
        elif kind == "clip":  # automatic sync from the phone: silent
            if clip_sync_enabled():
                remember_clip(value)
                set_clipboard(value)
        else:
            remember_clip(value)
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
    watch_clipboard()
    watch_notification_actions()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(CERT, KEY)
    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print(f"ecoz listening on :{PORT}"
          f"{' (announced via mDNS)' if mdns else ''}", flush=True)
    httpd.serve_forever()


def get_clipboard():
    """Current clipboard text via GSConnect's shell bridge, or None."""
    try:
        import gi
        gi.require_version("Gio", "2.0")
        from gi.repository import Gio

        bus = Gio.bus_get_sync(Gio.BusType.SESSION)
        proxy = Gio.DBusProxy.new_sync(
            bus, 0, None, GSCONNECT_CLIPBOARD, GSCONNECT_CLIPBOARD_PATH,
            GSCONNECT_CLIPBOARD, None)
        return proxy.call_sync("GetText", None, 0, 3000, None).unpack()[0]
    except Exception:
        return None


def report(message, failed=False):
    """Print for terminal use; raise a desktop notification when run from a shortcut."""
    if sys.stdout.isatty():
        print(message, file=sys.stderr if failed else sys.stdout)
    else:
        notify("ecoz: couldn't send" if failed else "ecoz", message)
    if failed:
        sys.exit(1)


def send_to_phone(words):
    """CLI: ask the running service to push a link/text to the phone."""
    value = " ".join(words).strip() or (get_clipboard() or "").strip()
    if not value:
        report("Nothing to send: give a link or text, or copy something first.", failed=True)
    kind = "url" if re.fullmatch(r"https?://\S+", value) else "text"
    push(kind, value)


def sync_setting(args):
    """CLI: show or change automatic clipboard sync (takes effect immediately)."""
    CONF.mkdir(parents=True, exist_ok=True)
    if args and args[0] == "off":
        CLIP_OFF_FLAG.touch()
    elif args and args[0] == "on":
        CLIP_OFF_FLAG.unlink(missing_ok=True)
    elif args:
        sys.exit("usage: ecoz_daemon.py sync [on|off]")
    report(f"Clipboard sync is {'on' if clip_sync_enabled() else 'off'}.")


def notification_action(args):
    """CLI: press a button on a phone notification; extra words are the reply text."""
    if len(args) < 2 or not args[1].isdigit():
        sys.exit("usage: ecoz_daemon.py notif-action <key> <index> [reply text]")
    action = {"key": args[0], "index": int(args[1])}
    if len(args) > 2:
        action["text"] = " ".join(args[2:])
    push("notif-action", json.dumps(action))


def ring_phone(args):
    """CLI: make the phone ring so you can find it; `ring stop` silences it."""
    if args and args[0] not in ("stop", "start"):
        sys.exit("usage: ecoz_daemon.py ring [stop]")
    push("ring", "stop" if args and args[0] == "stop" else "start")


def pull_from_phone():
    """CLI: ask the phone to send its clipboard; it needs one tap on the phone."""
    push("pull", "")


def push(kind, value):
    ctx = ssl.create_default_context(cafile=str(CERT))  # trust only our own certificate
    ctx.check_hostname = False
    conn = http.client.HTTPSConnection("127.0.0.1", PORT, context=ctx, timeout=5)
    try:
        conn.request("POST", "/v1/push", json.dumps({"type": kind, "value": value}),
                     {"Authorization": f"Bearer {ensure_identity()}"})
        resp = conn.getresponse()
        body = json.loads(resp.read() or b"{}")
    except OSError as e:
        report(f"service not reachable ({e}); is ecoz.service running?", failed=True)
    if resp.status != 200:
        report(f"refused: {body.get('error', resp.status)}", failed=True)
    if kind == "notif-action":
        if body["status"] != "delivered":
            report("Phone isn't connected, so that didn't go through.", failed=True)
        return
    if kind == "ring":
        return report(("Phone is ringing." if value == "start" else "Told the phone to stop.")
                      if body["status"] == "delivered" else "Phone isn't connected, so it can't ring.")
    if kind == "pull":
        return report("Asked the phone. Tap “Send clipboard” on it, then paste here."
                      if body["status"] == "delivered"
                      else "Phone not connected; it will ask when it reconnects.")
    preview = value if len(value) <= 60 else value[:57] + "..."
    report(f"Sent to phone: {preview}" if body["status"] == "delivered"
           else f"Phone not connected; queued for 10 minutes: {preview}")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "serve"
    if cmd == "send":
        send_to_phone(sys.argv[2:])
    elif cmd == "pull":
        pull_from_phone()
    elif cmd == "ring":
        ring_phone(sys.argv[2:])
    elif cmd == "notif-action":
        notification_action(sys.argv[2:])
    elif cmd == "sync":
        sync_setting(sys.argv[2:])
    elif cmd == "pair":
        print(pairing_code(ensure_identity()))
    elif cmd == "serve":
        serve()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
