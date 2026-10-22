# ecoz

Send links and text between a Linux laptop and an Android phone over the local network.

- `daemon/` — laptop service (Python, no dependencies). HTTPS with a self-signed
  certificate the phone pins, a bearer token, mDNS announcement through Avahi, and an
  acknowledged event stream for laptop → phone.
- `android/` — phone app (Kotlin, Jetpack Compose, Material 3).

## Laptop

    mkdir -p ~/.config/systemd/user
    cp daemon/ecoz.service ~/.config/systemd/user/
    systemctl --user enable --now ecoz
    python3 -I daemon/ecoz_daemon.py pair     # pairing code; copy it to the phone
    python3 -I daemon/ecoz_daemon.py send https://example.com

For everyday use, link the wrapper onto your PATH:

    ln -s ~/probes/ecoz/bin/ecoz ~/.local/bin/ecoz
    ecoz send https://example.com    # a link or text
    ecoz send                        # whatever is on the clipboard (needs GSConnect's clipboard bridge)

Bind `ecoz send` to a key in Settings > Keyboard > Custom Shortcuts (I use Super+Shift+P):
copy a link or text, press the key, and it appears on the phone.

The unit expects this repo at `~/probes/ecoz`; edit `ExecStart` if it lives elsewhere.

## Phone

    cd android && ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Open the app, paste the pairing code, then use Share → "Send to laptop". Turn on
"Receive from laptop" to get items pushed from the laptop.

## Security model

TLS with a self-signed certificate (the phone pins its SHA-256 fingerprint) plus a random
bearer token. Only `http`/`https` links are ever opened. The mDNS announcement carries a
fingerprint prefix only, never the token.
