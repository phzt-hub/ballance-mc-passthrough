#!/usr/bin/env bash
# Push the current branch to Gitee without ever writing the token into the repo,
# .git/config, or the shell history.
#
# Token lookup order:
#   1) $GITEE_TOKEN
#   2) $GITEE_TOKEN_FILE   (default: ~/.config/gitee/token, chmod 600)
#
# Usage:
#   tools/gitee-push.sh              # push current branch to origin
#   tools/gitee-push.sh origin master
set -euo pipefail

REPO_ROOT=$(git rev-parse --show-toplevel)
cd "$REPO_ROOT"

TOKEN="${GITEE_TOKEN:-}"
TOKEN_FILE="${GITEE_TOKEN_FILE:-$HOME/.config/gitee/token}"
if [ -z "$TOKEN" ] && [ -f "$TOKEN_FILE" ]; then
    TOKEN=$(tr -d '\r\n' < "$TOKEN_FILE")
fi
if [ -z "$TOKEN" ]; then
    cat >&2 <<'EOF'
缺少 Gitee 令牌。二选一：

  1) 写进文件（推荐，一次就好，以后我每次自动读）
       mkdir -p ~/.config/gitee
       printf '%s' 'GITEE_TOKEN_内容' > ~/.config/gitee/token
       chmod 600 ~/.config/gitee/token

  2) 临时用一次
       GITEE_TOKEN=xxxx tools/gitee-push.sh

令牌文件必须在仓库之外，脚本不会把它写进 .git/config 或任何被跟踪的文件。
EOF
    exit 1
fi

# 令牌文件落在仓库里就拒绝跑，防止误提交
case "$TOKEN_FILE" in
    "$REPO_ROOT"/*) echo "拒绝：令牌文件在仓库内（$TOKEN_FILE）" >&2; exit 2 ;;
esac

REMOTE="${1:-origin}"
BRANCH="${2:-$(git rev-parse --abbrev-ref HEAD)}"
REMOTE_URL=$(git remote get-url "$REMOTE")
OWNER=$(printf '%s' "$REMOTE_URL" | sed -E 's#^https://([^@]+@)?gitee\.com[:/]([^/]+)/.*#\2#')
if [ -z "$OWNER" ] || [ "$OWNER" = "$REMOTE_URL" ]; then
    OWNER="oauth2"
fi

ASKPASS=$(mktemp)
chmod 700 "$ASKPASS"
cat > "$ASKPASS" <<'EOS'
#!/bin/sh
case "$1" in
    *sername*|*ser*) printf '%s\n' "${GITEE_ASK_USER}" ;;
    *)               printf '%s\n' "${GITEE_ASK_PASS}" ;;
esac
EOS
trap 'rm -f "$ASKPASS"' EXIT

echo "== push $BRANCH -> $REMOTE ($OWNER) =="
GITEE_ASK_USER="$OWNER" GITEE_ASK_PASS="$TOKEN" \
GIT_ASKPASS="$ASKPASS" GIT_TERMINAL_PROMPT=0 \
    git push "$REMOTE" "$BRANCH"

echo "== 结果 =="
git fetch -q "$REMOTE" 2>/dev/null || true
git status -sb | head -1
