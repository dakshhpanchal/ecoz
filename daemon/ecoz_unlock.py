"""Unlock the laptop from the paired phone: near, unlocked, and cryptographically proven.

Unlocking needs ALL of these, checked again when the phone answers:
  1. the screen is locked, and the feature is on with a registered phone key;
  2. the phone's beacon was heard moments ago and is strong (the phone is within a metre or two);
  3. somebody touched this laptop recently (it was woken on purpose, not unlocked in an empty room);
  4. the phone signs a fresh random challenge with a key that never leaves its secure hardware and
     that Android only lets it use while the phone itself is unlocked.

The signed message also contains this laptop's certificate fingerprint, so an answer made for one
laptop is useless on another, and each challenge works once and expires in seconds.

Caveat that no software can remove: someone who can relay your phone's Bluetooth beacon to the
laptop AND get your unlocked phone to answer could unlock it, and anyone who touches the laptop
while you stand next to it with an unlocked phone will find it unlocked (the same as Apple's
Auto Unlock with a watch).
"""
import base64
import secrets
import time
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

UNLOCK_DBM = -68            # the phone must read stronger than this (within a few metres, in the same room)
FRESH_SIGNAL_SECONDS = 3    # ...and the beacon must have been heard this recently
ACTIVITY_SECONDS = 15       # the laptop must have been touched this recently to start a challenge (time to unlock the phone)
ANSWER_ACTIVITY_SECONDS = 20  # and recently enough when the answer arrives
CHALLENGE_SECONDS = 15      # a challenge is answerable for this long, once
RESEND_SECONDS = 4          # at most one new challenge this often (the phone may be locked, so try again)
MAX_PENDING = 5
MESSAGE_PREFIX = b"ecoz-unlock-v1\0"


def signed_message(nonce, laptop_fingerprint):
    """Exactly what the phone must sign."""
    return MESSAGE_PREFIX + nonce + laptop_fingerprint.encode("ascii")


class Unlocker:
    def __init__(self, conf, laptop_fingerprint, presence, is_locked, idle_seconds, do_unlock, send_challenge,
                 log=lambda *_: None):
        self._key_path = Path(conf) / "unlock-key.der"
        self._flag = Path(conf) / "unlock-on"
        self._fingerprint = laptop_fingerprint
        self._presence, self._is_locked, self._idle = presence, is_locked, idle_seconds
        self._do_unlock, self._send, self._log = do_unlock, send_challenge, log
        self._pending = {}   # nonce bytes -> issued-at
        self._last_sent = None
        self._last_reason = None  # the last reason logged for declining, so the log isn't repeated every second

    # ---- the registered phone key --------------------------------------------------------
    def registered(self):
        return self._key_path.exists()

    def enabled(self):
        return self._flag.exists() and self.registered()

    @staticmethod
    def _registration_check(public_key_der):
        """Raises ValueError unless this is an EC P-256 public key."""
        key = serialization.load_der_public_key(public_key_der)
        if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
            raise ValueError("expected an EC P-256 public key")

    def register(self, public_key_der):
        """Store the phone's public key. Only an EC P-256 key is accepted."""
        self._registration_check(public_key_der)
        self._key_path.parent.mkdir(parents=True, exist_ok=True)
        self._key_path.write_bytes(public_key_der)
        self._key_path.chmod(0o600)
        self._flag.touch()
        self._pending.clear()

    def forget(self):
        self._key_path.unlink(missing_ok=True)
        self._flag.unlink(missing_ok=True)
        self._pending.clear()

    # ---- the conditions -----------------------------------------------------------------
    def _blocked(self, activity_limit):
        """Why unlocking must not happen right now, or None if everything checks out."""
        if not self.enabled():
            return "off or no phone registered"
        if not self._is_locked():
            return "not locked"
        snap = self._presence()
        if not snap or snap.get("rssi") is None or snap["rssi"] < UNLOCK_DBM:
            return "phone not close enough"
        if snap.get("seen_seconds_ago") is None or snap["seen_seconds_ago"] > FRESH_SIGNAL_SECONDS:
            return "phone beacon not fresh"
        idle = self._idle()
        if idle is None or idle > activity_limit:
            return "laptop not touched recently"
        return None

    # ---- step 1: ask the phone ------------------------------------------------------------
    def tick(self, now=None):
        """Call about once a second. Sends a challenge when the conditions hold. Returns it, or None."""
        now = time.monotonic() if now is None else now
        for nonce in [n for n, t in self._pending.items() if now - t > CHALLENGE_SECONDS]:
            del self._pending[nonce]
        reason = self._blocked(ACTIVITY_SECONDS)
        if reason is not None:
            # say why, once per change, while the laptop is locked and unlocking is switched on
            if reason not in ("off or no phone registered", "not locked") and reason != self._last_reason:
                self._log(f"not unlocking: {reason}")
            self._last_reason = reason
            return None
        self._last_reason = None
        if self._last_sent is not None and now - self._last_sent < RESEND_SECONDS:
            return None
        if len(self._pending) >= MAX_PENDING:
            return None
        nonce = secrets.token_bytes(32)
        self._pending[nonce] = now
        self._last_sent = now
        self._send(base64.b64encode(nonce).decode())
        return nonce

    # ---- step 2: check the answer ---------------------------------------------------------
    def answer(self, nonce_b64, signature_b64, now=None):
        """Returns "unlocked", or the reason it was refused (for the log only)."""
        now = time.monotonic() if now is None else now
        try:
            nonce, signature = base64.b64decode(nonce_b64, validate=True), base64.b64decode(signature_b64, validate=True)
        except (ValueError, TypeError):
            return "malformed answer"
        issued = self._pending.pop(nonce, None)  # popped first: a challenge can never be answered twice
        if issued is None or now - issued > CHALLENGE_SECONDS:
            return "unknown or expired challenge"
        try:
            public_key = serialization.load_der_public_key(self._key_path.read_bytes())
            public_key.verify(signature, signed_message(nonce, self._fingerprint), ec.ECDSA(hashes.SHA256()))
        except (InvalidSignature, ValueError, OSError):
            return "bad signature"
        reason = self._blocked(ANSWER_ACTIVITY_SECONDS)  # conditions may have changed while the phone answered
        if reason:
            return f"conditions changed: {reason}"
        self._log("unlocking: phone is near, unlocked and proved itself")
        self._do_unlock()
        return "unlocked"
