#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
python_bin="$repo_root/kangban-agent/.venv/bin/python"
if [[ ! -x "$python_bin" ]]; then
  python_bin="$repo_root/kangban-agent/.venv/Scripts/python.exe"
fi
if [[ ! -f "$python_bin" ]]; then
  echo 'Run scripts/initialize-local.sh first.' >&2
  exit 1
fi
exec "$python_bin" "$repo_root/scripts/local_runtime.py" start "$@"
