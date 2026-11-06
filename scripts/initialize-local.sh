#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
uv_bin="${UV_BIN:-uv}"
"$uv_bin" sync --project kangban-agent --locked --python 3.12.14
"$uv_bin" run --project kangban-agent --locked python scripts/local_runtime.py init
(cd kangban-web && npm ci)
(cd kangban-server && mvn --batch-mode clean package -DskipTests)
echo 'Initialization complete. Fill kangban.local.env, then run scripts/start-local.sh.'
