#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
pkg install -y python git
SOURCE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE="${LUMENA_WORKSPACE:-$HOME/lumena-workspace}"
mkdir -p "$HOME/.lumena" "$WORKSPACE"
install -m 600 "$SOURCE/bridge.py" "$HOME/.lumena/bridge_base.py"
install -m 600 "$SOURCE/bridge_jobs.py" "$HOME/.lumena/bridge_jobs.py"
install -m 700 "$SOURCE/managed_bridge.py" "$HOME/.lumena/bridge.py"
printf '\nLumena bridge 0.8.1 installed. Existing token is unchanged.\nWorkspace: %s\n' "$WORKSPACE"
printf 'Stop the old bridge with Ctrl+C in its own terminal, then start:\n  python "%s/.lumena/bridge.py"\n' "$HOME"
