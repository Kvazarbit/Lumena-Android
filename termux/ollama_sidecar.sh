#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

HOST="127.0.0.1:11434"
LOG="$HOME/.lumena/ollama.log"
mkdir -p "$HOME/.lumena"

usage() {
  echo "Usage: $0 {status|serve|pull MODEL}"
}

if ! command -v ollama >/dev/null 2>&1; then
  echo "Ollama executable is not installed in this Termux environment."
  echo "Install a compatible Android/Termux Ollama build first, then rerun this script."
  exit 2
fi

case "${1:-}" in
  status)
    if curl -fsS "http://$HOST/api/tags" >/dev/null 2>&1; then
      echo "Ollama is running on $HOST"
      curl -fsS "http://$HOST/api/tags"
    else
      echo "Ollama is installed but not running"
      exit 1
    fi
    ;;
  serve)
    if curl -fsS "http://$HOST/api/tags" >/dev/null 2>&1; then
      echo "Ollama already running on $HOST"
      exit 0
    fi
    export OLLAMA_HOST="$HOST"
    nohup ollama serve >>"$LOG" 2>&1 &
    echo "Started Ollama on $HOST"
    echo "Log: $LOG"
    ;;
  pull)
    model="${2:-}"
    if [ -z "$model" ]; then
      usage
      exit 2
    fi
    export OLLAMA_HOST="$HOST"
    ollama pull "$model"
    ;;
  *)
    usage
    exit 2
    ;;
esac
