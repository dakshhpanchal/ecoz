"""Lock the laptop when the paired phone walks away.

Decision logic only depends on four small functions (presence, lock state, idle time, lock), so
it can be tested with simulated phone movements. The rules are deliberately cautious:

  * it arms only after the phone has been seen near for a few seconds while the laptop was
    unlocked, so a dead or absent phone never locks you out of a session you started;
  * the phone must stay clearly away (weak signal) for LOCK_AFTER seconds, so a dip from your
    body shadowing the phone does not trigger it;
  * it never locks while you are actively using the machine (idle shorter than IDLE_BEFORE_LOCK);
  * after locking it disarms until the phone has been near again.
"""
import time

LOCK_DBM = -82            # weaker than this counts as away (the phone is still heard faintly in the next room)
RECOVER_DBM = -75         # stronger than this cancels an away countdown (hysteresis)
NEAR_DBM = -65            # stronger than this arms the lock
ARM_SECONDS = 5           # near for this long before auto-lock is armed
LOCK_AFTER = 12           # away for this long before locking
SILENCE_SECONDS = 30      # not heard at all for this long also counts as away
IDLE_BEFORE_LOCK = 10     # do not lock while the user has touched the machine within this many seconds


class AutoLock:
    def __init__(self, presence, is_locked, idle_seconds, lock, log=lambda *_: None):
        self._presence, self._is_locked, self._idle, self._lock, self._log = presence, is_locked, idle_seconds, lock, log
        self.armed = False
        self._near_since = None
        self._away_since = None

    def tick(self, now=None):
        """Call about once a second. Returns "locked" if it locked the screen this tick."""
        now = time.monotonic() if now is None else now
        snap = self._presence()
        if snap is None or self._is_locked():
            self.armed, self._near_since, self._away_since = False, None, None
            return None
        rssi, seen_ago = snap.get("rssi"), snap.get("seen_seconds_ago")

        # arming: the phone has to be close for a moment while we are unlocked
        if rssi is not None and rssi >= NEAR_DBM:
            self._near_since = self._near_since or now
            if not self.armed and now - self._near_since >= ARM_SECONDS:
                self.armed = True
                self._log("auto-lock armed: phone is near")
        else:
            self._near_since = None
        if not self.armed:
            return None

        weak = rssi is not None and rssi < LOCK_DBM
        silent = seen_ago is None or seen_ago > SILENCE_SECONDS
        if weak or silent:
            self._away_since = self._away_since or now
        elif rssi is not None and rssi > RECOVER_DBM:
            self._away_since = None
        if self._away_since is None or now - self._away_since < LOCK_AFTER:
            return None

        idle = self._idle()
        if idle is not None and idle < IDLE_BEFORE_LOCK:
            return None  # you are at the keyboard: whatever the phone is doing, you are here
        self._log(f"locking: phone away for {now - self._away_since:.0f}s (signal {rssi}, idle {idle}s)")
        self.armed, self._away_since = False, None
        self._lock()
        return "locked"


# ---- the real inputs (GNOME / logind) ----------------------------------------------------
def _session_bus_call(dest, path, iface, method, reply=None):
    import gi
    gi.require_version("Gio", "2.0")
    from gi.repository import Gio, GLib
    bus = Gio.bus_get_sync(Gio.BusType.SESSION)
    return bus.call_sync(dest, path, iface, method, None, GLib.VariantType(reply) if reply else None,
                         Gio.DBusCallFlags.NONE, 2000, None)


def screen_is_locked():
    try:
        return _session_bus_call("org.gnome.ScreenSaver", "/org/gnome/ScreenSaver",
                                 "org.gnome.ScreenSaver", "GetActive", "(b)").unpack()[0]
    except Exception:
        return True  # when unsure, behave as if locked: do nothing


def idle_seconds():
    try:
        return _session_bus_call("org.gnome.Mutter.IdleMonitor", "/org/gnome/Mutter/IdleMonitor/Core",
                                 "org.gnome.Mutter.IdleMonitor", "GetIdletime", "(t)").unpack()[0] / 1000
    except Exception:
        return None


def lock_screen():
    """Lock through GNOME itself; this needs no special permission, unlike loginctl from a service."""
    try:
        _session_bus_call("org.gnome.ScreenSaver", "/org/gnome/ScreenSaver", "org.gnome.ScreenSaver", "Lock")
    except Exception:
        import subprocess
        subprocess.run(["loginctl", "lock-session"], check=False)
