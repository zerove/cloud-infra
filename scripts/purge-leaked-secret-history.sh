#!/usr/bin/env bash
# 把"清除已泄露密钥的历史"这一步安全地推到远端。
#
# 背景: dev JWT 密钥 sNU5J... 曾在多个提交里作为**真实的配置默认值**生效
# (如 d2ca7296 "为 system/file/notification/search 注入 AUTH_JWT_SECRET")。
# 本地历史已用 git filter-repo 重写完毕, 但**未推送** —— 远端仍保有明文。
#
# 为什么单独写脚本而不是手动敲 git push --force:
#   force-push 会作废 master 上全部提交的 SHA, 并要求所有协作者重新克隆。
#   这是不可逆且影响他人的操作, 不该以一条裸命令的形式留在某个人脑子里。
#   本脚本把前置检查内建, 任一检查不过就中止。
#
# 用法:  bash scripts/purge-leaked-secret-history.sh [--yes]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

REMOTE="${REMOTE:-origin}"
BRANCH="${BRANCH:-master}"
SAFETY_REF="${SAFETY_REF:-safety-pre-filter}"
ASSUME_YES=0
[[ "${1:-}" == "--yes" ]] && ASSUME_YES=1

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$*"; }
die() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

say "1/6 前置条件"
git rev-parse --verify "$SAFETY_REF" >/dev/null 2>&1 \
  || die "缺少安全引用 $SAFETY_REF —— 无重写前状态可回退, 中止(勿盲目强推)"
ok "安全引用存在: $SAFETY_REF"

[[ -z "$(git status --porcelain)" ]] || die "工作区不干净, 先提交或暂存后再执行"
ok "工作区干净"

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  die "存在未提交改动"
fi

say "2/6 连通性"
git ls-remote --exit-code --heads "$REMOTE" "$BRANCH" >/dev/null 2>&1 \
  || die "无法访问 $REMOTE/$BRANCH —— 网络不可达或仓库不存在。**不要**改用裸 git push --force 绕过"
ok "远端可达"

say "3/6 抓取远端状态"
git fetch "$REMOTE" "$BRANCH" || die "fetch 失败"
REMOTE_SHA="$(git rev-parse "FETCH_HEAD")"
ok "origin/$BRANCH = ${REMOTE_SHA:0:12}"

say "4/6 关键检查: 远端不得含有他人的新提交"
# 重写前的本地 HEAD(safety-pre-filter)应包含远端 master。若不包含,
# 说明他人在这期间推过提交 —— 强推会**抹掉那些提交**, 必须停下。
if ! git merge-base --is-ancestor "$REMOTE_SHA" "$SAFETY_REF"; then
  die "远端 $BRANCH 含重写前本地历史之外的提交, 强推会抹掉他人工作。
       请先人工确认: git log --oneline $REMOTE_SHA ^$SAFETY_REF"
fi
ok "远端是重写前本地历史的祖先 —— 无他人新提交会被抹掉"

say "5/6 重写前快照(便于事后核对)"
echo "  重写前 HEAD: $(git rev-parse "$SAFETY_REF")"
echo "  将推送 HEAD: $(git rev-parse HEAD)"
echo "  两者 SHA 不同是预期的(filter-repo 重写了全部提交)"

say "6/6 推送"
if [[ $ASSUME_YES -ne 1 ]]; then
  echo
  echo "  这将作废 $REMOTE/$BRANCH 上全部提交的 SHA。"
  echo "  所有协作者必须: git clone 重新拉取, 或 git fetch && git reset --hard origin/$BRANCH"
  echo
  read -r -p "  确认继续? 输入 yes: " answer
  [[ "$answer" == "yes" ]] || die "已取消"
fi

# --force-with-lease 而非 --force: 若远端在检查后又被他人推送, 拒绝而非覆盖
git push --force-with-lease="$BRANCH:$REMOTE_SHA" "$REMOTE" "HEAD:$BRANCH" \
  || die "推送失败(若报 lease 过期, 说明期间有他人推送, 请重新评估)"

ok "已推送"
cat <<'EOF'

后续必做:
  1. 通知所有协作者重新克隆(或 fetch + reset --hard)
  2. 确认远端已无该明文:
       git clone --mirror <repo> /tmp/x && grep -r <泄露值> /tmp/x   # 应无结果
  3. 轮换任何曾真实使用过该值的凭据
EOF