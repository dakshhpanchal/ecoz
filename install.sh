#!/bin/sh
# Install the ecoz laptop side for the current user. Nothing here needs root.
#
#   ./install.sh              install and start the service
#   ./install.sh --no-start   install the files only
#
# It links the `ecoz` command, installs a systemd user service that points at THIS checkout
# (so keep the folder where it is), and adds the settings launcher and the Files right-click script.
set -eu

REPO=$(cd "$(dirname "$0")" && pwd)
START=1
[ "${1:-}" = "--no-start" ] && START=0

BIN="$HOME/.local/bin"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
UNITS="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

say() { printf '  %s\n' "$*"; }
warn() { printf '  ! %s\n' "$*" >&2; }

echo "Checking what ecoz needs..."
command -v python3 >/dev/null 2>&1 || { warn "python3 is required"; exit 1; }
python3 -c "import gi" 2>/dev/null || warn "missing python3-gi (PyGObject): the service and settings window need it"
python3 -c "import cryptography" 2>/dev/null || warn "missing python3-cryptography: needed only for unlock with the phone"
command -v openssl >/dev/null 2>&1 || warn "missing openssl: needed to create the service's certificate"
command -v notify-send >/dev/null 2>&1 || warn "missing notify-send (libnotify-bin): used for confirmations"
command -v xdg-open >/dev/null 2>&1 || warn "missing xdg-open (xdg-utils): used to open links from the phone"
case ":$PATH:" in *":$BIN:"*) ;; *) warn "$BIN is not on your PATH, so the 'ecoz' command won't be found until it is" ;; esac

echo "Installing..."
mkdir -p "$BIN" "$UNITS" "$DATA/applications" "$DATA/nautilus/scripts"

ln -sf "$REPO/bin/ecoz" "$BIN/ecoz"
say "command:   $BIN/ecoz"

# the unit in the repo assumes ~/probes/ecoz; point it at wherever this checkout really is
sed "s#%h/probes/ecoz#$REPO#" "$REPO/daemon/ecoz.service" > "$UNITS/ecoz.service"
say "service:   $UNITS/ecoz.service"

sed "s#@HOME@#$HOME#" "$REPO/integrations/ecoz.desktop.in" > "$DATA/applications/ecoz.desktop"
say "launcher:  $DATA/applications/ecoz.desktop  (opens the settings window)"

ln -sf "$REPO/integrations/nautilus/Send to Phone" "$DATA/nautilus/scripts/Send to Phone"
say "Files:     right-click a file > Scripts > Send to Phone"

if [ "$START" -eq 1 ]; then
  systemctl --user daemon-reload
  systemctl --user enable --now ecoz
  say "service started"
fi

cat <<NEXT

Done. Next:
  1. Build and install the phone app:   cd android && ./gradlew assembleDebug
                                        adb install -r app/build/outputs/apk/debug/app-debug.apk
  2. Run  ecoz pair  and get the code onto the phone, then paste it in the app.
  See the README for the full walkthrough.
NEXT
