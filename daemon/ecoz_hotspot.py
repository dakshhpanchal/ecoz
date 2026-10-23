"""Lets the laptop ask the phone for a hotspot, when it has no internet and the phone is close.

Android won't let an app switch the hotspot on, so the best the laptop can do is call out: it
broadcasts a Bluetooth beacon of its own (role 2, the same tagged format the phone uses, so only
your phone recognises it and a bystander can't follow it). The phone shows a notification that opens
the hotspot switch. Once the hotspot is up, NetworkManager joins it on its own.

Nothing is broadcast while the laptop is online, and nothing at all unless the user switched it on.
"""
import subprocess
import threading
import time

import ecoz_presence as presence

ADVERT_PATH = "/app/ecoz/advert0"
ADVERT_IFACE = "org.bluez.LEAdvertisement1"
OFFLINE_AFTER = 3     # seconds without internet before calling for a hotspot
REFRESH = 25          # re-register with a fresh tag this often (tags change every 30 s)

_XML = f"""<node><interface name="{ADVERT_IFACE}">
  <method name="Release"/>
  <property name="Type" type="s" access="read"/>
  <property name="ManufacturerData" type="a{{qv}}" access="read"/>
  <property name="Includes" type="as" access="read"/>
  <property name="MinInterval" type="u" access="read"/>
  <property name="MaxInterval" type="u" access="read"/>
</interface></node>"""


def nm(field):
    """One field of `nmcli general`, or "" if NetworkManager can't be asked."""
    try:
        return subprocess.run(["nmcli", "-t", "-g", field, "general"], capture_output=True, text=True,
                              timeout=5).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def has_internet():
    """False when the link is down or local-only (instant), or the internet check says so (slower, but catches a dead uplink)."""
    state, check = nm("STATE"), nm("CONNECTIVITY")
    if not state:
        return True  # can't tell: don't call for a hotspot on a guess
    if state in ("disconnected", "asleep") or "local only" in state:
        return False
    return check not in ("none", "limited")


def rescan_wifi():
    """Nudge NetworkManager to look for the hotspot now rather than at its next scan."""
    try:
        subprocess.run(["nmcli", "device", "wifi", "rescan"], capture_output=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        pass


class Advertiser:
    """Broadcasts the laptop's beacon through BlueZ while [wanted] says so. One background thread."""

    def __init__(self, token, wanted):
        self._key = presence.beacon_key(token)
        self._wanted = wanted
        self._stop = threading.Event()
        self._thread = None
        self.active = False
        self.error = None

    def start(self):
        self._thread = threading.Thread(target=self._run, daemon=True, name="hotspot-advert")
        self._thread.start()

    def stop(self):
        self._stop.set()

    def _run(self):
        try:
            import gi
            gi.require_version("Gio", "2.0")
            from gi.repository import Gio, GLib
        except ImportError as e:
            self.error = f"GLib unavailable: {e}"
            return
        ctx = GLib.MainContext()
        ctx.push_thread_default()
        payload = {"data": b""}
        registered = {"id": None, "at": 0.0}
        try:
            bus = Gio.bus_get_sync(Gio.BusType.SYSTEM)

            managed = bus.call_sync("org.bluez", "/", "org.freedesktop.DBus.ObjectManager", "GetManagedObjects", None,
                                    GLib.VariantType("(a{oa{sa{sv}}})"), Gio.DBusCallFlags.NONE, 4000, None).unpack()[0]
            adapter = next((p for p, i in managed.items() if "org.bluez.LEAdvertisingManager1" in i), None)
            if adapter is None:
                self.error = "Bluetooth can't advertise"
                return
            node = Gio.DBusNodeInfo.new_for_xml(_XML)

            def get_property(_c, _s, _p, _i, name):
                if name == "Type":
                    return GLib.Variant("s", "peripheral")
                if name in ("MinInterval", "MaxInterval"):
                    return GLib.Variant("u", 100 if name == "MinInterval" else 150)  # ms: be noticed quickly
                if name == "Includes":
                    return GLib.Variant("as", [])
                return GLib.Variant("a{qv}", {presence.COMPANY_ID: GLib.Variant("ay", payload["data"])})

            def on_method(_c, _s, _p, _i, method, _params, invocation):
                invocation.return_value(None)  # Release: BlueZ dropped us; the next tick registers again
                self.active = False

            def done(source, result, what):
                # BlueZ reads our properties while this call is open, so it must not block the loop
                try:
                    source.call_finish(result)
                    if what == "register":
                        self.active = True
                        self.error = None
                except GLib.Error as e:
                    self.error = str(e).split(": ")[-1][:120]
                    self.active = False

            def register():
                payload["data"] = presence.beacon_payload(self._key, presence.ROLE_LAPTOP)
                registered["at"] = time.monotonic()
                registered["id"] = bus.register_object(ADVERT_PATH, node.interfaces[0], on_method, get_property, None)
                bus.call("org.bluez", adapter, "org.bluez.LEAdvertisingManager1", "RegisterAdvertisement",
                         GLib.Variant("(oa{sv})", (ADVERT_PATH, {})), None, Gio.DBusCallFlags.NONE, 8000, None,
                         done, "register")

            def unregister():
                if registered["id"] is None:
                    return
                bus.call("org.bluez", adapter, "org.bluez.LEAdvertisingManager1", "UnregisterAdvertisement",
                         GLib.Variant("(o)", (ADVERT_PATH,)), None, Gio.DBusCallFlags.NONE, 4000, None,
                         lambda *_: None, "unregister")
                bus.unregister_object(registered["id"])
                registered["id"] = None
                self.active = False

            def tick():
                if self._stop.is_set():
                    unregister()
                    GLib.timeout_add(300, loop.quit)  # let the unregister call leave first
                    return False
                try:
                    if not self._wanted():
                        unregister()
                    elif registered["id"] is None:
                        register()
                    elif time.monotonic() - registered["at"] >= REFRESH:
                        unregister()  # BlueZ reads properties only when registering: refresh the tag
                        GLib.timeout_add(250, lambda: register() or False)
                except GLib.Error as e:
                    self.error = str(e).split(": ")[-1][:120]
                return True

            tick()
            timer = GLib.timeout_source_new_seconds(2)
            timer.set_callback(lambda *_: tick())
            timer.attach(ctx)
            loop = GLib.MainLoop(ctx)
            loop.run()
        except Exception as e:
            self.error = f"{type(e).__name__}: {e}"[:160]
            self.active = False


class OfflineWatch:
    """True once the laptop has been without internet for OFFLINE_AFTER seconds."""

    def __init__(self):
        self._since = None

    def offline(self):
        if has_internet():
            self._since = None
            return False
        if self._since is None:
            self._since = time.monotonic()
        return time.monotonic() - self._since >= OFFLINE_AFTER
