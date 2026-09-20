#!/usr/bin/env bash
set -euo pipefail

# Pin this deliberately. Update only after Android build/inference tests pass.
LLAMA_CPP_REF="${LLAMA_CPP_REF:-master}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/llama.cpp"

if [[ -d "$DEST/.git" ]]; then
  git -C "$DEST" fetch --depth 1 origin "$LLAMA_CPP_REF"
  git -C "$DEST" checkout --detach FETCH_HEAD
else
  rm -rf "$DEST"
  mkdir -p "$(dirname "$DEST")"
  git clone --depth 1 --branch "$LLAMA_CPP_REF" https://github.com/ggml-org/llama.cpp.git "$DEST"
fi

echo "llama.cpp ready at: $DEST"
git -C "$DEST" rev-parse HEAD
