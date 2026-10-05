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
# ⚠️【2026-10-05 重写】本守护先前用 `MEMINFO_PATH` 造桩, 而真实实现已在
#   2026-10-05 **故意删掉**该注入点（verify-e2e.sh:911 的注释写明理由:
#   "判据就该钉死在真实文件上"）。于是桩数据完全失效, 函数读的是**真实**
#   /proc/meminfo —— 而它当时恰好 <92%, 于是"峰值输入"场景恒不警告,
#   守护报"通过 8 项, 失败 2 项"却**从未测到阈值行为**（第五课: 判据恒红,
#   而真相是它本该绿; 又一次桩数据把"没测到"说成"测了不过"）。
#
#   本次改用 **PATH 注入的 awk 桩**: 被测函数体一字不改, 只把它内部调用的
#   awk 换成可控版本, 由桩返回指定的 Committed_AS/CommitLimit。
#   这样既不污染被测实现（无 MEMINFO_PATH 这类后门）, 又保留了
#   "跑真实函数体" 的关键属性。
#
#   ⚠️ 用 awk 而非直接写路径: 因为被测函数是
#     awk '/^Committed_AS:/{...}END{...}' /proc/meminfo
#   —— 它调用 PATH 里的 awk, 换掉 PATH 里的 awk 即换掉数据源。
#   若改成 sed 之类的其它工具, 桩的**参数形态**会与被测函数传的不一致,
#   那正是"注入未生效/静默失效"的来源（第八课补记一第 4 条）。

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

# ── awk 桩: 替换 PATH 里的 awk, 精确返回指定的 commit 比例 ──
# 用法: COMMIT_PCT_STUB=95 bash test_commit_runtime_guard.sh
#   未设置时不注入桩, 函数读真实 /proc/meminfo。
#
# ⚠️ 桩必须**验证注入生效**: 否则会重现"sed 没匹配上却静默通过"那类恒假绿。
make_awk_stub() { # 目录 百分比
  local d="$1" pct="$2"
  # ⚠️ 桩内部**不能调用 awk**: PATH 里此时已是桩自己, 会自举。
  #   这与第八课补记一的 sed 注入同源 —— 注入后污染源变成了被检对象本身。
  #
  # ⚠️【2026-10-05 修正】第一版桩**完全忽略传入的 awk 程序**, 直接输出固定
  #   meminfo 文本。后果是自检 `awk '{c=$2}END{print c}' /proc/meminfo` 拿到的
  #   是整段文本而不是数字 —— 看起来像"注入未生效", 实际是桩不执行程序。
  #   那是**桩自己的假绿**: 若自检恰好比较了别的形式, 就会静默通过。
  #
  #   正解: 桩只**替换数据源**(把 /proc/meminfo 换成临时文件), 程序照常交给
  #   真 awk 执行。这样"被测逻辑"完全真实, 可控的只有输入。
  local data="$d/meminfo.stub"
  printf 'Committed_AS:  %s kB\nCommitLimit:  10000000 kB\n' "$(( pct * 100000 ))" >"$data"
  cat >"$d/awk" <<STUB
#!/usr/bin/env bash
# 桩: 把 /proc/meminfo 重定向到桩数据, 其余原样执行**传入的真实 awk 程序**。
for a in "\$@"; do
  if [ "\$a" = "/proc/meminfo" ]; then
    args=()
    for b in "\$@"; do args+=("\${b//\/proc\/meminfo/$data}"); done
    exec /usr/bin/awk "\${args[@]}"
  fi
done
exec /usr/bin/awk "\$@"
STUB
  chmod +x "$d/awk"
  # 注入生效自检: 桩必须真的被用上, 且**真实 awk 程序**能跑出预期值。
  # 只断言"输出非空"是不够的 —— 那分不清"桩生效"与"桩不执行程序"。
  local got expect
  expect="$(( pct * 100000 ))"
  got="$(PATH="$d:$PATH" bash -c 'awk "/^Committed_AS:/{c=\$2}END{print c}" /proc/meminfo')"
  if [ "$got" != "$expect" ]; then
    red "  ❌ awk 桩注入未生效 (期望 $expect, 实得 ${got:-<空>}) —— 测试自身失效"; exit 1
  fi
}

drive() { # commit 百分比 调用次数 -> "<rc1> <rc2> ..."
  local pct="${1:-}" n="${2:-1}" i stubdir=""
  if [ -n "$pct" ]; then
    stubdir="$(mktemp -d)"
    make_awk_stub "$stubdir" "$pct"
  fi
  for ((i=0;i<n;i++)); do
    printf '%s\n' "$( PATH="${stubdir:+$stubdir:}$PATH" COMMIT_WARNED=0 bash -c "
        source '$FN'
        check_commit_runtime
        echo \"rc=\$?\"
      " 2>&1 )"
  done
  [ -n "$stubdir" ] && rm -rf "$stubdir"
  return 0
}

echo "[2] 阈值行为: 健康静默 / 峰值警告"
# 健康 38% / 峰值 95% (真实实现阈值 92%)
d_low="$(drive 38)"
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

d_high="$(drive 95)"
if printf '%s' "$d_high" | grep -q '运行期'; then
  green "  ✅ 峰值输入(95%)产生了警告 —— 不是静默的假绿"; PASS=$((PASS+1))
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
# 让 awk 桩对 /proc/meminfo 返回空输出 -> 函数读不到 commit 账本
BADDIR="$WORK/badstub"; mkdir -p "$BADDIR"
cat >"$BADDIR/awk" <<'STUB'
#!/usr/bin/env bash
last="${@: -1}"
case "$last" in
  /proc/meminfo) exit 0 ;;   # 空输出: Committed_AS/CommitLimit 都取不到
  *) exec /usr/bin/awk "$@" ;;
esac
STUB
chmod +x "$BADDIR/awk"
d_bad="$(PATH="$BADDIR:$PATH" COMMIT_WARNED=0 bash -c "
    source '$FN'
    check_commit_runtime
    echo \"rc=\$?\"
  " 2>&1)"
if printf '%s' "$d_bad" | grep -q 'rc=0'; then
  green "  ✅ 无数据输入返回 0（SKIP, 不是判绿也不是判红）"; PASS=$((PASS+1))
else
  red "  ❌ 无数据输入返回: $(printf '%s' "$d_bad"|tr '\n' ' ')"; FAIL=$((FAIL+1))
fi
if printf '%s' "$d_bad" | grep -q '读不到 commit 账本'; then
  green "  ✅ 无数据输入发出的是**SKIP** 提示（不是阈值警告）"; PASS=$((PASS+1))
else
  red "  ❌ 无数据输入未发 SKIP 提示, 输出: $(printf '%s' "$d_bad"|tr '\n' ' ')"; FAIL=$((FAIL+1))
fi
# ⚠️ 这里**不能** grep '运行期' 来判定"是否发出警告"。
#   先前就是这么写的, 结果把 SKIP 消息也当成警告 —— 因为两条消息都含
#   `[运行期]` 前缀:
#     阈值警告: "⚠️  [运行期] 全局 commit 预算 95% ≥ 92% ..."
#     SKIP 提示: "⚠️  [运行期] /proc/meminfo 读不到 commit 账本, 本次不判定"
#   两者语义相反（一个说"超限了", 一个说"没测到"）, 却共享关键词。
#   ⇒ 判据必须选**能区分二者**的字符串, 不能选它们共有的那部分。
#   这与第五课「区分『没有』与『查不到』」同源。
echo

echo "[4] 连续多轮只警告一次（避免刷屏淹没真实错误）"
# 注意: COMMIT_WARNED 必须在**同一进程**内保持, 所以这里不重置它
STUB4="$(mktemp -d)"; make_awk_stub "$STUB4" 95
warn_count="$(PATH="$STUB4:$PATH" COMMIT_WARNED=0 bash -c "
    source '$FN'
    check_commit_runtime 2>&1
    check_commit_runtime 2>&1
    check_commit_runtime 2>&1
  " 2>&1 | grep -c '运行期')"
rm -rf "$STUB4"
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