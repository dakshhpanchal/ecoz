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
    ecoz send-file photo.jpg doc.pdf # offer files; the phone asks, then pulls them into Download/laptop
    ecoz confirm [on|off]            # ask before accepting files from the phone (default on)
    ecoz settings                    # settings window (also "ecoz" in the app grid)

Clipboard sync needs the GSConnect GNOME extension, whose clipboard bridge ecoz uses to read
and write the Wayland clipboard. Anything you copy on the laptop lands on the phone's
clipboard automatically (password-manager copies and anything over 64 KB are skipped).

## Phone

    cd android && ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Open the app, paste the pairing code, then use Share → "Send to laptop". Turn on
"Receive from laptop" to get items and clipboard changes pushed from the laptop.

To send files from Files (Nautilus), link the script in and right-click → Scripts → Send to Phone,
and add the settings launcher to the app grid:

    ln -s "$PWD/integrations/nautilus/Send to Phone" ~/.local/share/nautilus/scripts/
    sed "s#@HOME@#$HOME#" integrations/ecoz.desktop.in > ~/.local/share/applications/ecoz.desktop

Both directions ask first, and each device decides for itself: the laptop's "ask before accepting
files from the phone" is in `ecoz settings`, the phone's "ask before receiving files" is a switch in
the app (both default to on). Asking happens before any data moves: the sender describes the files,
the receiver accepts or declines (a notification with Accept and Decline), and only an accepted
upload gets a one-time ticket. A batch is one question. Unanswered or swiped-away counts as no.

Files from the phone arrive in `~/Downloads/phone` (set `ECOZ_RECEIVE_DIR` to change it), and files from the laptop in `Download/laptop` on the phone: share any
file from any app to "Send to laptop". Transfers stream over the same pinned-TLS link, so size is
limited only by free disk space (512 MiB is always kept free). A file is written under a hidden
temporary name and renamed when complete, names are reduced to a plain base name (duplicates get
"(1)"), and only a process on the laptop itself can offer a file to the phone. Folders aren't
supported yet.

## Phone proximity

The phone can broadcast a Bluetooth beacon that only your laptop recognises (a tag derived from
the pairing token, rotating every 30 seconds, no name or location). Turn it on in the app
("Tell the laptop when I'm close") and on the laptop:

    ecoz proximity on      # listen for the beacon
    ecoz presence          # Phone is NEAR  (-50 dBm, last heard 0.2s ago)
    ecoz autolock on       # lock the laptop when the phone walks away (also in `ecoz settings`)

Auto-lock arms only after it has seen the phone near, locks after the signal has been clearly
weak (below -82 dBm) for 12 seconds, never while you are using the machine (idle under 10 s), and
re-arms only once the phone has been near again, so a dead phone or a hand-unlocked session is
never locked out from under you. All of this is off by default.

### Unlock with the phone

    ecoz unlock status                 # on/off and whether a phone key is registered
    ecoz unlock off | on | forget      # pause, resume, or remove the phone's key

Turn on "Let my phone unlock the laptop" in the app, then accept the question that appears on the
(unlocked) laptop. The phone creates an EC P-256 key in its secure hardware, marked usable only
while the phone is unlocked, and gives the laptop just the public half. When the laptop is locked
it unlocks only if all of these hold, checked again when the phone answers: the phone's beacon is
strong (about a metre or two) and fresh; someone touched the laptop in the last 15 seconds; and the
phone signs a fresh random challenge, which also names this laptop's certificate fingerprint, so an
answer is good once, for one laptop, for a few seconds. A locked phone cannot answer.

Limits: relaying the phone's Bluetooth beacon plus an unlocked phone answering would unlock it, and
anyone who touches the laptop while you stand next to it with an unlocked phone finds it unlocked.
A phone in your pocket is normally locked, so it only works while the phone is unlocked (Android's
Extend Unlock keeps it unlocked while carried).

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
