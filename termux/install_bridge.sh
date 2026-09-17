#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STATE="$HOME/.lumena"
WORKSPACE="${LUMENA_WORKSPACE:-$HOME/lumena-workspace}"

pkg install -y python git
mkdir -p "$STATE" "$WORKSPACE"
cp "$HERE/bridge.py" "$STATE/bridge.py"
chmod 700 "$STATE/bridge.py"

cat <<MSG

Lumena bridge installed.
Workspace: $WORKSPACE

Start it with:
  python "$STATE/bridge.py"

The bridge prints a token on startup. Paste that token into Lumena Android.
It binds only to 127.0.0.1:8765, so devices on your Wi-Fi cannot connect to it.
MSG
