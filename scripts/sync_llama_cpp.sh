#!/usr/bin/env bash
set -euo pipefail

# Exact revision used by the previous CI run. Never track moving master silently.
readonly LLAMA_CPP_REF="b23efaa2ef147f547ee75cbf0c621d61904de80e"
readonly UPSTREAM="https://github.com/ggml-org/llama.cpp.git"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/llama.cpp"

if [[ ! -d "$DEST/.git" ]]; then
  if [[ -d "$DEST" && -n "$(ls -A "$DEST")" ]]; then
    echo "Refusing to replace non-Git directory: $DEST" >&2
    exit 1
  fi
  mkdir -p "$DEST"
  git -C "$DEST" init
  git -C "$DEST" remote add origin "$UPSTREAM"
fi

if [[ -n "$(git -C "$DEST" status --porcelain)" ]]; then
  echo "llama.cpp has local changes. Commit or stash them before syncing." >&2
  exit 1
fi
if [[ "$(git -C "$DEST" rev-parse HEAD 2>/dev/null || true)" != "$LLAMA_CPP_REF" ]]; then
  git -C "$DEST" fetch --depth 1 "$UPSTREAM" "$LLAMA_CPP_REF"
  git -C "$DEST" checkout --detach "$LLAMA_CPP_REF"
fi
test "$(git -C "$DEST" rev-parse HEAD)" = "$LLAMA_CPP_REF"
test -f "$DEST/CMakeLists.txt"
printf 'llama.cpp source: %s\nllama.cpp revision: %s\n' "$DEST" "$LLAMA_CPP_REF"
