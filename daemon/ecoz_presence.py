"""Is the paired phone close? Listens for the phone's Bluetooth beacon and reports near / mid / away.

The phone broadcasts a 10-byte manufacturer-data payload: version, role, and an 8-byte tag
HMAC(beacon_key, role || 30-second counter). The key is derived from the shared token, so only
this laptop can tell the beacon is the phone's, and because the tag changes every 30 seconds a
bystander cannot follow it around. Signal strength (RSSI) gives distance; it is smoothed over
a few samples because a single reading jumps around by 10 dB or more.

This module only measures. It never unlocks anything by itself: see ecoz_unlock.
"""
import collections
import hashlib
import hmac
import statistics
import threading
import time

COMPANY_ID = 0xFFFF      # Bluetooth SIG "reserved for internal use / testing"
VERSION = 1
ROLE_PHONE, ROLE_LAPTOP = 1, 2
WINDOW_SECONDS = 30

# Tunable: RSSI in dBm, higher is closer. A phone on the desk beside the laptop reads about -35 to -55.
NEAR_DBM = -65
FAR_DBM = -85
AWAY_SECONDS = 30        # nothing verified for this long counts as away
SAMPLE_SECONDS = 6       # only readings this recent decide near/mid


def beacon_key(token):
    return hmac.new(token.encode(), b"ecoz-beacon-v1", hashlib.sha256).digest()


def beacon_payload(key, role, now=None):
    counter = int((now if now is not None else time.time()) // WINDOW_SECONDS)
    tag = hmac.new(key, bytes([role]) + counter.to_bytes(8, "big"), hashlib.sha256).digest()[:8]
    return bytes([VERSION, role]) + tag


def verify_beacon(key, data, now=None):
    """Returns the role if [data] is a genuine beacon from the paired device, else None."""
    if len(data) != 10 or data[0] != VERSION or data[1] not in (ROLE_PHONE, ROLE_LAPTOP):
        return None
    counter = int((now if now is not None else time.time()) // WINDOW_SECONDS)
    for c in (counter - 1, counter, counter + 1):  # tolerate a little clock difference
        expected = hmac.new(key, bytes([data[1]]) + c.to_bytes(8, "big"), hashlib.sha256).digest()[:8]
        if hmac.compare_digest(expected, data[2:10]):
            return data[1]
    return None


class Presence:
    """Tracks the phone's beacon through BlueZ and keeps a smoothed distance estimate."""

    def __init__(self, token):
        self._key = beacon_key(token)
        self._lock = threading.Lock()
        self._samples = collections.deque(maxlen=60)   # (monotonic time, rssi)
        self._verified = {}                            # BlueZ device path -> monotonic time last verified
        self._rssi = {}                                # BlueZ device path -> latest rssi
        self._last_seen = None
        self._thread = None
        self._stop = threading.Event()
        self.error = None

    # ---- reading the state ----------------------------------------------------------
    def snapshot(self):
        now = time.monotonic()
        with self._lock:
            recent = [r for t, r in self._samples if now - t <= SAMPLE_SECONDS][-5:]
            seen_age = None if self._last_seen is None else now - self._last_seen
        rssi = statistics.median(recent) if recent else None
        if rssi is not None and rssi >= NEAR_DBM:
            state = "near"
        elif seen_age is not None and seen_age <= AWAY_SECONDS and (rssi is None or rssi > FAR_DBM):
            state = "mid"
        else:
            state = "away"
        return {"state": state, "rssi": rssi, "seen_seconds_ago": None if seen_age is None else round(seen_age, 1),
                "scanning": self._thread is not None and self._thread.is_alive() and self.error is None,
                "error": self.error}

    # ---- BlueZ plumbing -------------------------------------------------------------
    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True, name="presence")
        self._thread.start()

    def stop(self):
        self._stop.set()

    def _note(self, path, manufacturer, rssi):
        now = time.monotonic()
        with self._lock:
            if manufacturer is not None:
                data = manufacturer.get(COMPANY_ID)
                if data is not None and verify_beacon(self._key, bytes(data)) == ROLE_PHONE:
                    self._verified[path] = now
                    self._last_seen = now
            if rssi is not None:
                self._rssi[path] = rssi
            # a reading counts only for a device whose beacon verified recently
            if rssi is not None and now - self._verified.get(path, -1e9) <= 45:
                self._samples.append((now, rssi))
                self._last_seen = now

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
        try:
            bus = Gio.bus_get_sync(Gio.BusType.SYSTEM)

            def call(path, iface, method, params=None, reply=None):
                return bus.call_sync("org.bluez", path, iface, method, params,
                                     GLib.VariantType(reply) if reply else None, Gio.DBusCallFlags.NONE, 4000, None)

            managed = call("/", "org.freedesktop.DBus.ObjectManager", "GetManagedObjects", None, "(a{oa{sa{sv}}})").unpack()[0]
            adapter = next((p for p, ifaces in managed.items() if "org.bluez.Adapter1" in ifaces), None)
            if adapter is None:
                self.error = "no Bluetooth adapter"
                return

            def on_props(_bus, _sender, path, _iface, _signal, params):
                interface, changed, _ = params.unpack()
                if interface == "org.bluez.Device1":
                    self._note(path, changed.get("ManufacturerData"), changed.get("RSSI"))

            bus.signal_subscribe("org.bluez", "org.freedesktop.DBus.Properties", "PropertiesChanged", None,
                                 "org.bluez.Device1", Gio.DBusSignalFlags.NONE, on_props)
            for path, ifaces in managed.items():  # devices BlueZ already knows
                dev = ifaces.get("org.bluez.Device1")
                if dev:
                    self._note(path, dev.get("ManufacturerData"), dev.get("RSSI"))

            def ensure_scanning():
                if self._stop.is_set():
                    loop.quit()
                    return False
                try:
                    call(adapter, "org.bluez.Adapter1", "SetDiscoveryFilter", GLib.Variant(
                        "(a{sv})", ({"Transport": GLib.Variant("s", "le"), "DuplicateData": GLib.Variant("b", True)},)))
                    call(adapter, "org.bluez.Adapter1", "StartDiscovery")
                except GLib.Error as e:
                    if "InProgress" not in str(e):  # already scanning is fine
                        self.error = str(e).split(": ")[-1][:120]
                    return True
                self.error = None
                return True

            ensure_scanning()
            timer = GLib.timeout_source_new_seconds(15)  # BlueZ can drop discovery; make sure it keeps running
            timer.set_callback(lambda *_: ensure_scanning())
            timer.attach(ctx)
            loop = GLib.MainLoop(ctx)
            loop.run()
            try:
                call(adapter, "org.bluez.Adapter1", "StopDiscovery")
            except GLib.Error:
                pass
        except Exception as e:
            self.error = f"{type(e).__name__}: {e}"[:160]
