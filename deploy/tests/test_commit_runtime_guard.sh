#!/usr/bin/env bash
# check_commit_runtime 守护测试
#
# 为什么需要（2026-10-04 实测缺口）:
#   commit 门禁只在 verify-e2e.sh **启动前**跑一次。实测启动时
#   Committed_AS 仅 64%、门禁 PASS, 十几分钟后 SkyWalking OAP 仍死于
#     Native memory allocation (malloc) failed to allocate 9104 bytes
#   即"通过启动门禁"不能推出"运行期不会耗尽"。
#
# 验证四件事（每件都有负向对照）:
#   1. 函数存在, 且在健康轮询循环内被调用（否则"每轮复查"退化为"只查一次"）
#   2. 峰值时确实警告, 健康时静默（不是恒绿也不是恒红）
#   3. 只警告**不阻断** —— 环境紧张不该把 E2E 变成失败结论
#   4. 取不到 meminfo 时 return 0 而非判红（探针失效 ≠ 结论）
#
# ⚠️ 沿用本项目反复踩到的规则: 被检字面量不得出现在断言自身附近,
#   否则断言会"匹配到自己"。这里靠 **抽出真实函数体到临时脚本** 来执行,
#   断言只看它的**输出与返回值**, 不去 grep 源码里的字符串。

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$REPO_ROOT/verify-e2e.sh"

PASS=0; FAIL=0
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }

echo "═══ check_commit_runtime 守护测试 ══"
echo

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# ── 0. 语法 ──
echo "[0] 语法检查"
if bash -n "$SCRIPT" 2>/dev/null; then
  green "  ✅ bash -n 通过"; PASS=$((PASS+1))
else
  red "  ❌ bash -n 不通过"; FAIL=$((FAIL+1))
fi
echo

# ── 1. 结构: 函数体可提取 + 在轮询循环内被调用 ──
echo "[1] 结构: 函数存在且在健康轮询循环内被调用"
# 把真实函数体(含定义行)抽出来, 单独放进可执行脚本 —— 这样测的是**真实实现**
FN="$WORK/fn.sh"
awk '/^check_commit_runtime\(\)/{p=1} p{print} p && /^}/{exit}' "$SCRIPT" > "$FN"
if [ -s "$FN" ] && bash -n "$FN" 2>/dev/null; then
  green "  ✅ 真实函数体可提取且语法正确"; PASS=$((PASS+1))
else
  red "  ❌ 函数体提取失败或语法错 —— 测试自身失效"; FAIL=$((FAIL+1))
  echo "════ 通过 0 项, 失败 $((FAIL+1)) 项"; exit 1
fi

# 调用点必须在健康轮询的 while 循环体内
LOOP="$WORK/loop.txt"
awk '/^while :; do/{inw=1} inw{print} inw && /^done/{exit}' "$SCRIPT" > "$LOOP"
if grep -q 'check_commit_runtime' "$LOOP"; then
  green "  ✅ 在健康轮询循环内被调用（运行期复查, 而非启动时一次）"; PASS=$((PASS+1))
else
  red "  ❌ 未在健康轮询循环内调用 —— 运行期复查形同虚设"; FAIL=$((FAIL+1))
fi
echo

# ── 造三组 meminfo ──
LOW="$WORK/low"; HIGH="$WORK/high"; BAD="$WORK/bad"
printf 'Committed_AS:  8000000 kB\nCommitLimit:  20643000 kB\n'  > "$LOW"   # 38%
printf 'Committed_AS: 18000000 kB\nCommitLimit:  20643000 kB\n'  > "$HIGH"  # 87%
printf 'this file has no meminfo fields at all\n'                  > "$BAD"   # 探针失效

# 调用真实函数 N 次, 打印每次的返回值; 警告走 stderr 一并捕获
drive() { # meminfo 路径 调用次数 -> "<rc1> <rc2> ..."  输出在 stdout
  local mi="$1" n="${2:-1}" i
  for ((i=0; i<n; i++)); do
    printf '%s\n' "$( MEMINFO_PATH="$mi" COMMIT_WARNED=0 bash -c "
        source '$FN'
        check_commit_runtime
        echo \"rc=\$?\"
      " 2>&1 )"
  done
}

echo "[2] 阈值行为: 健康静默 / 峰值警告"
d_low="$(drive "$LOW")"
if printf '%s' "$d_low" | grep -q '运行期'; then
  red "  ❌ 健康输入(38%)竟然产生了警告"; FAIL=$((FAIL+1))
else
  green "  ✅ 健康输入无警告"; PASS=$((PASS+1))
fi
if printf '%s' "$d_low" | grep -q 'rc=0'; then
  green "  ✅ 健康输入返回 0"; PASS=$((PASS+1))
else
  red "  ❌ 健康输入未返回 0, 输出: $(printf '%s' "$d_low"|tr '\n' ' ')"; FAIL=$((FAIL+1))
fi

d_high="$(drive "$HIGH")"
if printf '%s' "$d_high" | grep -q '运行期'; then
  green "  ✅ 峰值输入(87%)产生了警告 —— 不是静默的假绿"; PASS=$((PASS+1))
else
  red "  ❌ 峰值输入无任何警告 —— 这就是第 6 类假绿"; FAIL=$((FAIL+1))
fi
if printf '%s' "$d_high" | grep -q 'rc=0'; then
  green "  ✅ 峰值输入仍返回 0（只警告, 不阻断 E2E）"; PASS=$((PASS+1))
else
  red "  ❌ 峰值输入返回非 0 —— 阻断会把环境紧张变成 E2E 失败结论"; FAIL=$((FAIL+1))
fi
echo

echo "[3] 探针失效: 取不到 meminfo 时不得当结论"
d_bad="$(drive "$BAD")"
if printf '%s' "$d_bad" | grep -q 'rc=0'; then
  green "  ✅ 无数据输入返回 0（SKIP, 不是判绿也不是判红）"; PASS=$((PASS+1))
else
  red "  ❌ 无数据输入返回: $(printf '%s' "$d_bad"|tr '\n' ' ')"; FAIL=$((FAIL+1))
fi
if printf '%s' "$d_bad" | grep -q '运行期'; then
  red "  ❌ 无数据输入却发出了警告 —— 那是拿不存在的值下结论"; FAIL=$((FAIL+1))
else
  green "  ✅ 无数据输入不发警告（不把取不到值当成取到假值）"; PASS=$((PASS+1))
fi
echo

echo "[4] 连续多轮只警告一次（避免刷屏淹没真实错误）"
# 注意: COMMIT_WARNED 必须在**同一进程**内保持, 所以这里不重置它
warn_count="$(MEMINFO_PATH="$HIGH" COMMIT_WARNED=0 bash -c "
    source '$FN'
    check_commit_runtime 2>&1
    check_commit_runtime 2>&1
    check_commit_runtime 2>&1
  " 2>&1 | grep -c '运行期')"
if [ "$warn_count" = "1" ]; then
  green "  ✅ 三轮只输出 1 次警告"; PASS=$((PASS+1))
else
  red "  ❌ 三轮输出了 $warn_count 次警告, 期望 1"; FAIL=$((FAIL+1))
fi
echo

echo "══════════════════════════════════════"
echo "通过 $PASS 项，失败 $FAIL 项"
[[ "$FAIL" == "0" ]] || exit 1
green "守护测试全部通过"