#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

STATE="$HOME/.lumena/laya"
MODEL_DIR="${LUMENA_LAYA_MODEL_DIR:-$HOME/models/laya}"
BIN="$STATE/laya"
SRC="$STATE/laya.c"
LICENSE_FILE="$STATE/LICENSE.laya-cpp"
LOG="$STATE/laya.log"
PID_FILE="$STATE/laya.pid"
PORT="${LUMENA_LAYA_PORT:-29417}"
LAYA_CPP_COMMIT="941e64863193c1290bffece6c22f8ac828dffecd"
BASE="https://raw.githubusercontent.com/shpati/laya.cpp/$LAYA_CPP_COMMIT"

mkdir -p "$STATE" "$MODEL_DIR"

need_pkg() {
  command -v "$1" >/dev/null 2>&1 || pkg install -y "$2"
}

install_runtime() {
  need_pkg curl curl
  need_pkg clang clang

  echo "Downloading pinned laya.cpp source..."
  curl -fL --retry 3 "$BASE/laya.c" -o "$SRC"
  curl -fL --retry 3 "$BASE/LICENSE" -o "$LICENSE_FILE"

  grep -q "laya_load" "$SRC"
  grep -q "laya_predict" "$SRC"
  grep -q "Apache License" "$LICENSE_FILE"

  # Upstream laya.cpp uses __builtin_setjmp/__builtin_longjmp for every
  # Clang/GCC target. Android's AArch64 Clang rejects those builtins.
  # <setjmp.h> is already included upstream, so keep upstream behaviour on
  # other platforms and force the standard jmp_buf/setjmp/longjmp fallback
  # only when the compiler defines __ANDROID__.
  python - "$SRC" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = """#if defined(__GNUC__) || defined(__clang__)
/* The compiler builtins restore registers without unwinding, which is robust across threads and on 64-bit MinGW
 * (where longjmp goes through SEH unwinding). */
typedef void *laya_jmp[5];
#define LAYA_SETJMP(b) __builtin_setjmp(b)
#define LAYA_LONGJMP(b) __builtin_longjmp((b), 1)
#else
typedef jmp_buf laya_jmp;
#define LAYA_SETJMP(b) setjmp(b)
#define LAYA_LONGJMP(b) longjmp((b), 1)
#endif"""

new = """#if (defined(__GNUC__) || defined(__clang__)) && !defined(__ANDROID__)
/* The compiler builtins restore registers without unwinding, which is robust across threads and on 64-bit MinGW
 * (where longjmp goes through SEH unwinding). */
typedef void *laya_jmp[5];
#define LAYA_SETJMP(b) __builtin_setjmp(b)
#define LAYA_LONGJMP(b) __builtin_longjmp((b), 1)
#else
typedef jmp_buf laya_jmp;
#define LAYA_SETJMP(b) setjmp(b)
#define LAYA_LONGJMP(b) longjmp((b), 1)
#endif"""

if new not in text:
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"Refusing to patch laya.c: expected exactly one setjmp block, found {count}"
        )
    text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")

patched = path.read_text(encoding="utf-8")
if new not in patched:
    raise SystemExit("Android setjmp portability patch was not applied")
if "#include <setjmp.h>" not in patched:
    raise SystemExit("Pinned laya.c no longer includes <setjmp.h>")
PY

  echo "Compiling Laya System-1 runtime for this device..."
  clang -O3 -std=c11 "$SRC" -o "$BIN" -lm
  chmod 700 "$BIN"

  cat > "$STATE/source.json" <<EOF
{"repository":"shpati/laya.cpp","commit":"$LAYA_CPP_COMMIT","license":"Apache-2.0","build":"clang -O3 -std=c11 -lm"}
EOF
  chmod 600 "$STATE/source.json"

  echo "Laya runtime installed: $BIN"
}

download_model() {
  need_pkg curl curl
  echo "Downloading convaiinnovations/laya checkpoint files..."
  mkdir -p "$MODEL_DIR"

  curl -fL --retry 3     "https://huggingface.co/convaiinnovations/laya/resolve/main/model.safetensors"     -o "$MODEL_DIR/model.safetensors"

  curl -fL --retry 3     "https://huggingface.co/convaiinnovations/laya/resolve/main/rl_agent_config.json"     -o "$MODEL_DIR/rl_agent_config.json"

  curl -fL --retry 3     "https://huggingface.co/convaiinnovations/laya/resolve/main/tokenizer/tokenizer.json"     -o "$MODEL_DIR/tokenizer.json"

  curl -fL --retry 3     "https://huggingface.co/convaiinnovations/laya/resolve/main/encoder/config.json"     -o "$MODEL_DIR/config.json"

  for f in model.safetensors rl_agent_config.json tokenizer.json config.json; do
    test -s "$MODEL_DIR/$f" || {
      echo "Missing model artifact: $MODEL_DIR/$f" >&2
      exit 4
    }
  done

  echo "Laya model installed: $MODEL_DIR"
}

status() {
  echo "binary=$BIN"
  echo "binary_present=$([ -x "$BIN" ] && echo true || echo false)"
  echo "model_dir=$MODEL_DIR"
  for f in model.safetensors rl_agent_config.json tokenizer.json config.json; do
    echo "$f=$([ -s "$MODEL_DIR/$f" ] && echo present || echo missing)"
  done

  if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/status" >/dev/null 2>&1; then
    echo "running=true"
    curl -fsS --max-time 2 "http://127.0.0.1:$PORT/status"
    echo
  else
    echo "running=false"
  fi
}

start() {
  test -x "$BIN" || {
    echo "Laya runtime is not installed. Run: $0 runtime" >&2
    exit 5
  }

  for f in model.safetensors rl_agent_config.json tokenizer.json config.json; do
    test -s "$MODEL_DIR/$f" || {
      echo "Missing $MODEL_DIR/$f. Run: $0 model" >&2
      exit 6
    }
  done

  if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/status" >/dev/null 2>&1; then
    echo "Laya System-1 already running."
    status
    return
  fi

  echo "Starting Laya System-1 on 127.0.0.1:$PORT ..."
  nohup "$BIN" "$MODEL_DIR" --serve "$PORT" --bind 127.0.0.1     >> "$LOG" 2>&1 &
  echo $! > "$PID_FILE"

  for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/status" >/dev/null 2>&1; then
      echo "Laya System-1 ready."
      status
      return
    fi
    sleep 1
  done

  echo "Laya did not become ready within 60 seconds." >&2
  tail -80 "$LOG" >&2 || true
  exit 7
}

stop() {
  if [ -f "$PID_FILE" ]; then
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$pid" ]; then
      kill "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
  pkill -f "$BIN .*--serve" 2>/dev/null || true
  echo "Laya System-1 stopped."
}

case "${1:-status}" in
  runtime)
    install_runtime
    ;;
  model)
    download_model
    ;;
  all)
    install_runtime
    download_model
    start
    ;;
  start)
    start
    ;;
  stop)
    stop
    ;;
  status)
    status
    ;;
  *)
    cat <<EOF
Usage:
  $0 runtime   # compile pinned laya.cpp runtime
  $0 model     # download convaiinnovations/laya weights
  $0 all       # runtime + model + start
  $0 start
  $0 stop
  $0 status

Environment:
  LUMENA_LAYA_MODEL_DIR=/custom/model/dir
  LUMENA_LAYA_PORT=29417
EOF
    exit 2
    ;;
esac
