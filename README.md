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
    ecoz send https://example.com    # a link or text to the phone
    ecoz send                        # whatever is on the clipboard
    ecoz sync off                    # pause automatic clipboard sync (on to resume)
    ecoz ring                        # find my phone: alarm at full volume (ecoz ring stop)
    ecoz send-file photo.jpg doc.pdf # offer files; the phone pulls them into its Downloads

Clipboard sync needs the GSConnect GNOME extension, whose clipboard bridge ecoz uses to read
and write the Wayland clipboard. Anything you copy on the laptop lands on the phone's
clipboard automatically (password-manager copies and anything over 64 KB are skipped).

## Phone

    cd android && ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Open the app, paste the pairing code, then use Share → "Send to laptop". Turn on
"Receive from laptop" to get items and clipboard changes pushed from the laptop.

To send files from Files (Nautilus), link the script in and right-click → Scripts → Send to Phone:

    ln -s "$PWD/integrations/nautilus/Send to Phone" ~/.local/share/nautilus/scripts/

Files from the phone arrive in `~/Downloads` (set `ECOZ_RECEIVE_DIR` to change it): share any
file from any app to "Send to laptop". Transfers stream over the same pinned-TLS link, so size is
limited only by free disk space (512 MiB is always kept free). A file is written under a hidden
temporary name and renamed when complete, names are reduced to a plain base name (duplicates get
"(1)"), and only a process on the laptop itself can offer a file to the phone. Folders aren't
supported yet.

Phone notifications are mirrored to the laptop when "Mirror notifications" is on and
notification access is granted (Settings, or `adb shell cmd notification allow_listener
app.ecoz/app.ecoz.NotificationMirrorService`). Ongoing, silent and private notifications are
never sent; dismissing one on the phone closes it on the laptop.

Phone → laptop clipboard is one tap: "Send clipboard" in the ecoz notification. Android
stops background apps from reading the clipboard, so it can't be automatic.

## Security model

TLS with a self-signed certificate (the phone pins its SHA-256 fingerprint) plus a random
bearer token. Only `http`/`https` links are ever opened. The mDNS announcement carries a
fingerprint prefix only, never the token.
