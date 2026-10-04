#!/usr/bin/env bash
# 守护测试: verify-e2e.sh 的 podman 探针必须带 timeout, 且必须区分
#         "探针取不到值" 与 "容器真的不存在"。
#
# ## 背景（2026-10-04, 实测）
#
# `podman inspect --format '{{.State.Health.Status}}'` 单次耗时 **1.4–2.0 秒**。
# 6 个微服务串行 = 约 10 秒/轮, 而健康轮询的 sleep 只有 6 秒 ——
# **循环体本身就超过轮询间隔**。Podman 状态损坏时单次可到数十秒,
# 此时该循环会长时间不返回, 600s 预算形同虚设。
#
# 更危险的是原始写法:
#
#     state=$(podman inspect ... "$c" 2>/dev/null || echo absent)
#
# `|| echo absent` 把两件本质不同的事混为一谈:
#   - 探针超时 / Podman 卡住 → 应当**重试**
#   - 容器真的没被创建       → 才是故障 (问题在 podman-compose 那一步)
#
# 混为一谈的后果: Podman 响应变慢时, 预算被提前耗尽, 报出一个
# "6 个微服务未 healthy" 的失败 —— 而**真实原因是探针坏了**。
# 这与本项目已记录的形态完全同型: 探针失效被当成被测对象失败
# (见 docs/perf 第十二节 "存活=0/6 但崩溃=0" 的自摆乌龙)。
#
# ## 为什么用静态检查
#
# 这些行为无法在无 Podman 环境下单元测试; 而"探针坏了"这种故障
# 在真机上一年未必遇到一次。静态断言是唯一能在每次提交时执行的守门。
# 代价是静态断言可能因重构失效 —— 故本文件同时断言**语义**而非具体行号。

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET="$SCRIPT_DIR/verify-e2e.sh"

PASS=0
FAIL=0
ok()   { printf "  \033[32mPASS\033[0m %s\n" "$1"; PASS=$((PASS+1)); }
bad()  { printf "  \033[31mFAIL\033[0m %s\n" "$1"; FAIL=$((FAIL+1)); }

[ -f "$TARGET" ] || { echo "找不到 $TARGET"; exit 1; }

echo "═══ 1. 脚本语法 ═══"
if bash -n "$TARGET" 2>/dev/null; then ok "verify-e2e.sh 语法正确"; else bad "语法错误"; fi

echo "═══ 2. 所有 podman inspect 必须带 timeout ═══"
# 逐行检查: 出现 podman inspect 但同行没有 timeout 的, 即为缺陷。
missing="$(awk '
  /podman[[:space:]]+inspect/ && $0 !~ /timeout/ && $0 !~ /^[[:space:]]*#/ {print NR": "$0}
' "$TARGET")"
if [ -z "$missing" ]; then
  ok "无裸 podman inspect 调用"
else
  bad "存在未加 timeout 的 podman inspect:"
  printf '%s\n' "$missing" | sed 's/^/        /'
fi

echo "═══ 3. 探针失败必须与 absent 区分 ═══"
# 旧的 `|| echo absent` 必须消失; 必须出现显式的 probe-timeout 分支。
if grep -q 'podman inspect.*|| *echo *absent' "$TARGET"; then
  bad "仍用 '|| echo absent' —— 探针超时会被误报成容器不存在"
else
  ok "已移除 '|| echo absent'"
fi
if grep -q 'probe-timeout' "$TARGET"; then
  ok "存在 probe-timeout 标记"
else
  bad "缺少 probe-timeout 标记 —— 无法区分探针坏与容器不存在"
fi

echo "═══ 4. 探针不可用时不得消耗判定预算 ═══"
# 断言: round_fail 分支里存在 continue（即继续等待而非跳出）。
if awk '/round_fail.*-eq 1/{f=1} f&&/continue/{found=1} f&&/^  esac|^done/{f=0} END{exit !found}' "$TARGET"; then
  ok "探针失败分支会继续等待"
else
  bad "探针失败分支未 continue —— 会提前耗尽 600s 预算并误报"
fi

echo "═══ 5. 诊断函数对空状态必须明说 ═══"
# 空串若直接进入 `grep -q "exit=0"` 判断会被当成健康而跳过日志展开。
if awk '/probe-timeout\(无法读取状态/{found=1} END{exit !found}' "$TARGET"; then
  ok "诊断函数对空状态有显式提示"
else
  bad "诊断函数未处理空状态 —— 该容器会被静默跳过"
fi

echo "═══ 6. 负向对照: 插入一个裸 inspect, 规则 2 必须变红 ═══"
# ⚠️ 不能把规则 2 的检测逻辑**复制**一份到别处跑 —— 那等于自己测自己,
#   两份代码会一起漂移, 漂移后负向对照仍然“通过”, 却什么都没守住。
#   正确做法: 在临时副本上**重跑本文件的规则 2**, 用的是同一份实现。
TMP="$(mktemp)"
cp "$TARGET" "$TMP"
probe_rule2() {
  awk '
    /podman[[:space:]]+inspect/ && $0 !~ /timeout/ && $0 !~ /^[[:space:]]*#/ {print NR": "$0}
  ' "$1"
}
before="$(probe_rule2 "$TMP" | wc -l)"
awk '{print}
     /state=\$\(timeout 15 podman inspect -f/ && !done {
       print "  X=$(podman inspect -f \"{{.State.Status}}\" \"$c\" 2>/dev/null)";
       done=1
     }' "$TMP" > "$TMP.new" && mv "$TMP.new" "$TMP"
after="$(probe_rule2 "$TMP" | wc -l)"
if [ "$after" -gt "$before" ]; then
  ok "负向对照生效: 插入裸 inspect 后规则 2 命中数 $before -> $after"
else
  bad "负向对照失效: 插入裸 inspect 后命中数仍为 $before —— 规则 2 形同虚设"
fi
rm -f "$TMP"

echo
echo "═══ 结果: $PASS 通过 / $FAIL 失败 ═══"
[ "$FAIL" -eq 0 ] || exit 1
