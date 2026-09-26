#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STATE="$HOME/.lumena"
WORKSPACE="${LUMENA_WORKSPACE:-$HOME/lumena-workspace}"

MISSING=()
command -v python >/dev/null 2>&1 || MISSING+=(python)
command -v git >/dev/null 2>&1 || MISSING+=(git)
if [ "${#MISSING[@]}" -gt 0 ]; then
  pkg install -y "${MISSING[@]}"
fi

mkdir -p "$STATE" "$WORKSPACE"
cp "$HERE/bridge.py" "$STATE/bridge.py"
chmod 700 "$STATE/bridge.py"

if [ -f "$HERE/configure_read_roots.sh" ]; then
  cp "$HERE/configure_read_roots.sh" "$STATE/configure_read_roots.sh"
  chmod 700 "$STATE/configure_read_roots.sh"
fi
touch "$STATE/read_roots.conf"
chmod 600 "$STATE/read_roots.conf"

# Allow Lumena Android to invoke Termux's documented RUN_COMMAND service.
# The APK can only start the fixed ~/.lumena/bridge.py path; tool access remains
# restricted by bridge.py's allow-list and 127.0.0.1 binding.
mkdir -p "$HOME/.termux"
PROPS="$HOME/.termux/termux.properties"
touch "$PROPS"
if grep -qE '^[[:space:]]*allow-external-apps=' "$PROPS"; then
  sed -i 's/^[[:space:]]*allow-external-apps=.*/allow-external-apps=true/' "$PROPS"
else
  printf '\nallow-external-apps=true\n' >> "$PROPS"
fi
command -v termux-reload-settings >/dev/null 2>&1 && termux-reload-settings || true

cat <<MSG

Lumena bridge installed.
Workspace (read/write): $WORKSPACE
Persistent read-only roots config: $STATE/read_roots.conf
Configure allowed phone folders with:
  $STATE/configure_read_roots.sh list
  $STATE/configure_read_roots.sh add shared "$HOME/storage/shared"

For Android shared storage, run termux-setup-storage once first.
Lumena can now start the bridge automatically when a local tool is first used.
Manual start still works with:
  python "$STATE/bridge.py"

The bridge keeps one token in $STATE/bridge_token. Paste that token into Lumena Android once.
It binds only to 127.0.0.1:8765, so devices on your Wi-Fi cannot connect to it.
MSG
