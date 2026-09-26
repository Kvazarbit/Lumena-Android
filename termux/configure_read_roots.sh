#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

STATE="$HOME/.lumena"
CONF="$STATE/read_roots.conf"
mkdir -p "$STATE"
touch "$CONF"
chmod 600 "$CONF"

usage() {
  cat <<'EOF'
Usage:
  configure_read_roots.sh list
  configure_read_roots.sh add ALIAS PATH
  configure_read_roots.sh remove ALIAS

Examples:
  configure_read_roots.sh add shared "$HOME/storage/shared"
  configure_read_roots.sh add downloads "$HOME/storage/downloads"
  configure_read_roots.sh add btc "$HOME/storage/shared/Trading/BTC"

After changing roots, restart the bridge:
  pkill -f "$HOME/.lumena/bridge.py" 2>/dev/null || true
Lumena will auto-start it on the next local tool call.
EOF
}

normalize_path() {
  python - "$1" <<'PY'
import sys
from pathlib import Path
p = Path(sys.argv[1]).expanduser().resolve()
print(p)
PY
}

cmd="${1:-list}"
case "$cmd" in
  list)
    echo "Configured Lumena read-only roots:"
    if [ ! -s "$CONF" ]; then
      echo "(none)"
    else
      cat "$CONF"
    fi
    ;;
  add)
    alias_name="${2:-}"
    raw_path="${3:-}"
    if ! printf '%s' "$alias_name" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'; then
      echo "Invalid alias. Use letters, numbers, dot, underscore or dash; max 64 chars." >&2
      exit 2
    fi
    if [ -z "$raw_path" ]; then
      echo "PATH is required." >&2
      usage >&2
      exit 2
    fi
    resolved="$(normalize_path "$raw_path")"
    if [ ! -d "$resolved" ]; then
      echo "Directory does not exist or is not visible to Termux: $resolved" >&2
      echo "For shared phone storage run: termux-setup-storage" >&2
      exit 3
    fi

    tmp="$CONF.tmp"
    grep -vE "^${alias_name}=" "$CONF" > "$tmp" || true
    printf '%s=%s\n' "$alias_name" "$resolved" >> "$tmp"
    mv "$tmp" "$CONF"
    chmod 600 "$CONF"
    echo "Added read-only root: @$alias_name -> $resolved"
    echo "Restart bridge to apply it."
    ;;
  remove)
    alias_name="${2:-}"
    if [ -z "$alias_name" ]; then
      echo "ALIAS is required." >&2
      exit 2
    fi
    tmp="$CONF.tmp"
    grep -vE "^${alias_name}=" "$CONF" > "$tmp" || true
    mv "$tmp" "$CONF"
    chmod 600 "$CONF"
    echo "Removed read-only root: @$alias_name"
    echo "Restart bridge to apply it."
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
