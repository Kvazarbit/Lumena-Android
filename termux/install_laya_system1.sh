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
THREADS="${LUMENA_LAYA_THREADS:-$(cat "$STATE/threads" 2>/dev/null || echo 2)}"
case "$THREADS" in 1|2|3|4|5|6|7|8) ;; *) echo "LUMENA_LAYA_THREADS must be 1..8" >&2; exit 2 ;; esac
STAGE=""
LOCKED=false
cleanup() {
  [ -z "$STAGE" ] || rm -rf -- "$STAGE"
  if $LOCKED; then rmdir "$STATE/install.lock"; fi
}
trap cleanup EXIT
case "${1:-status}" in
  runtime|all|upgrade|rollback|start|stop)
    mkdir "$STATE/install.lock" 2>/dev/null || { echo "Another Laya operation is in progress" >&2; exit 3; }
    LOCKED=true ;;
esac

need_pkg() {
  command -v "$1" >/dev/null 2>&1 || pkg install -y "$2"
}

install_runtime() {
  need_pkg curl curl
  need_pkg clang clang
  need_pkg python python
  if [ ! -f "${PREFIX:-/data/data/com.termux/files/usr}/lib/libomp.a" ]; then
    pkg install -y libomp
  fi
  STAGE="$(mktemp -d "$STATE/build-XXXXXXXX")"
  local source="$STAGE/laya.c"

  echo "Downloading pinned laya.cpp source..."
  curl -fL --retry 3 "$BASE/laya.c" -o "$source"
  curl -fL --retry 3 "$BASE/LICENSE" -o "$STAGE/LICENSE.laya-cpp"

  grep -q "laya_load" "$source"
  grep -q "laya_predict" "$source"
  grep -q "Apache License" "$STAGE/LICENSE.laya-cpp"

  # Upstream laya.cpp uses __builtin_setjmp/__builtin_longjmp for every
  # Clang/GCC target. Android's AArch64 Clang rejects those builtins.
  # <setjmp.h> is already included upstream, so keep upstream behaviour on
  # other platforms and force the standard jmp_buf/setjmp/longjmp fallback
  # only when the compiler defines __ANDROID__.
  python - "$source" <<'PY'
from pathlib import Path
import sys
import hashlib

path = Path(sys.argv[1])
if hashlib.sha256(path.read_bytes()).hexdigest() != "2cb905156d775f25c4a1b1403085a029392a71fd984e209cbaf73ad9d66f03a9":
    raise SystemExit("Pinned source SHA-256 mismatch")
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
  clang -O3 -std=c11 -fopenmp -static-openmp "$source" -o "$STAGE/laya" -lm
  chmod 700 "$STAGE/laya"
  cat > "$STAGE/source.json" <<EOF
{"repository":"shpati/laya.cpp","commit":"$LAYA_CPP_COMMIT","license":"Apache-2.0","build":"clang -O3 -std=c11 -fopenmp -static-openmp -lm","threads":$THREADS,"wait_policy":"PASSIVE"}
EOF
  printf '%s\n' "$THREADS" > "$STAGE/threads"
  if [ -f "$BIN" ]; then
    local backup
    backup="$(mktemp -d "$STATE/backup-XXXXXXXX")"
    for name in laya laya.c LICENSE.laya-cpp source.json threads; do
      [ ! -f "$STATE/$name" ] || cp -p "$STATE/$name" "$backup/$name"
    done
    cmp "$BIN" "$backup/laya"
    printf '%s\n' "$backup" > "$STATE/previous.tmp"
    mv "$STATE/previous.tmp" "$STATE/previous"
    echo "backup=$backup"
  fi
  # Rename the new inode; never truncate a binary used by a running service.
  for name in laya.c LICENSE.laya-cpp source.json threads laya; do
    mv "$STAGE/$name" "$STATE/$name"
  done
  echo "Laya OpenMP runtime installed; active service changes only after restart: $BIN"
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
  OMP_NUM_THREADS="$THREADS" OMP_WAIT_POLICY=PASSIVE nohup "$BIN" "$MODEL_DIR" --serve "$PORT" --bind 127.0.0.1     >> "$LOG" 2>&1 &
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
  stop
  return 7
}

stop() {
  # Match complete argv entries, including older bridge-started processes without a pid file.
  python - "$BIN" "$PORT" <<'PYTHON' || return $?
import os, signal, sys, time
from pathlib import Path
binary, port = sys.argv[1:]
targets = []
for proc in Path('/proc').glob('[0-9]*'):
    try:
        args = (proc / 'cmdline').read_bytes().split(b'\0')
        if args[0] == os.fsencode(binary) and b'--serve' in args:
            i = args.index(b'--serve')
            if args[i + 1] == port.encode():
                targets.append(int(proc.name))
    except (OSError, IndexError):
        pass
for pid in targets:
    try: os.kill(pid, signal.SIGTERM)
    except ProcessLookupError: pass
for _ in range(50):
    alive = []
    for pid in targets:
        try:
            state = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[0]
            if state != 'Z': alive.append(pid)
        except OSError: pass
    if not alive: break
    time.sleep(0.1)
else:
    raise SystemExit('Laya did not stop; refusing to replace/restart it')
PYTHON
  rm -f "$PID_FILE"
  echo "Laya System-1 stopped."
}

rollback() {
  local backup
  backup="$(cat "$STATE/previous")"
  case "$backup" in "$STATE"/backup-*) ;; *) echo "Invalid rollback path" >&2; return 8 ;; esac
  test -x "$backup/laya"
  stop
  for name in laya laya.c LICENSE.laya-cpp source.json threads; do
    if [ -f "$backup/$name" ]; then
      cp -p "$backup/$name" "$STATE/$name.restore"
      mv "$STATE/$name.restore" "$STATE/$name"
    else
      rm -f "$STATE/$name"
    fi
  done
  THREADS="$(cat "$STATE/threads" 2>/dev/null || echo 1)"
  start
}

upgrade() {
  test -x "$BIN" || { echo "Use runtime/model/start for first installation" >&2; return 5; }
  install_runtime
  stop
  if start && python - "$PORT" "$THREADS" <<'PYTHON'
import json, sys, urllib.request
with urllib.request.urlopen('http://127.0.0.1:' + sys.argv[1] + '/status', timeout=5) as r:
    data = json.load(r)
assert data.get('status') == 'ready'
assert data['engine']['openmp'] is True
assert data['engine']['threads'] == int(sys.argv[2])
print('OpenMP runtime configuration verified')
PYTHON
  then
    echo "Upgrade verified; rollback available: $0 rollback"
  else
    echo "Upgrade failed; restoring previous runtime" >&2
    rollback
    return 9
  fi
}

case "${1:-status}" in
  upgrade)
    upgrade
    ;;
  rollback)
    rollback
    ;;
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
  $0 runtime   # compile pinned OpenMP runtime, preserving previous binary
  $0 upgrade   # build, restart, check threads/OpenMP; rollback on startup failure
  $0 rollback  # restore previous runtime and restart
  $0 model     # download convaiinnovations/laya weights
  $0 all       # runtime + model + start
  $0 start
  $0 stop
  $0 status

Environment:
  LUMENA_LAYA_MODEL_DIR=/custom/model/dir
  LUMENA_LAYA_PORT=29417
  LUMENA_LAYA_THREADS=2  # persisted, 1..8; pilot default, not an accuracy setting
EOF
    exit 2
    ;;
esac
