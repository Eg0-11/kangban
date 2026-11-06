#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

echo "[1/7] 检查工作区格式与本地密钥文件"
git diff --check -- . ':(exclude)agent/**'
git diff --cached --check -- . ':(exclude)agent/**'
git check-ignore -q kangban-server/.env
git check-ignore -q kangban-web/.env.local
git check-ignore -q kangban-agent/.env
git check-ignore -q kangban.local.env

echo "[2/7] 扫描当前版本与 Git 历史中的高风险密钥格式"
openai_prefix="s""k-"
google_prefix="AI""za"
aws_prefix="AK""IA"
key_word="KEY"
secret_pattern="${openai_prefix}[A-Za-z0-9_-]{20,}|${google_prefix}[A-Za-z0-9_-]{20,}|${aws_prefix}[A-Z0-9]{16}|-----BEGIN [A-Z ]*PRIVATE ${key_word}-----"
if git grep -Il -E "$secret_pattern" -- . ':(exclude)agent/**' >/dev/null; then
  echo "检测到疑似真实密钥格式，请先移除并轮换。" >&2
  exit 1
fi
history_hits="$(git log --all --format='%H' -G"$secret_pattern" -- . ':(exclude)agent/**')"
if [[ -n "$history_hits" ]]; then
  echo "Git 历史中检测到疑似真实密钥格式，请先清理历史并轮换。" >&2
  exit 1
fi

echo "[3/7] Python 锁定环境与测试"
uv_bin="${UV_BIN:-uv}"
"$uv_bin" sync --project kangban-agent --locked --python 3.12.14
(cd kangban-agent && "$uv_bin" run --locked python -m pytest -q)

echo "[4/7] 前端测试"
(cd kangban-web && npm test)

echo "[5/7] 前端生产构建"
(cd kangban-web && npm run build)

echo "[6/7] Java 干净构建与 Python 跨进程联调"
export KANGBAN_RUN_LIVE_RAG=false
(cd kangban-server && mvn --batch-mode "$@" clean verify -Dspring.profiles.active=test \
  -Dapp.ai.provider=mock -Dapp.agent.rag.embedding-provider=local \
  -Dapp.mcp.public.enabled=false -Dspring.ai.mcp.client.enabled=false \
  '-Dspring.datasource.url=jdbc:h2:mem:kangban_test;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE' \
  -Dspring.datasource.driver-class-name=org.h2.Driver -Dspring.datasource.username=sa '-Dspring.datasource.password=')

echo "[7/7] Java 回调响应与 Python 严格契约"
(cd kangban-agent && "$uv_bin" run --locked python scripts/check-java-contract.py)

echo "发布候选版本检查通过。"
