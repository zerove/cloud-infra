#!/usr/bin/env bash
# repair_healthcheck_logs 的守护测试。
#
# ## 为什么值得单独测
#
# 这个函数修的是**环境级故障**: 某个容器被强停后留下 0 字节
# healthcheck.log, 之后**所有** podman ps / podman-compose 调用都失败:
#
#     Error: unable to get healthcheck log for <id>: failed to unmarshal ...
#     rc=125, stdout 完全为空
#
# 实测中 podman-compose 因此直接抛 CalledProcessError, 整个 E2E 起不来。
# 而"容器被强停"在本项目是**常态**(malloc 失败崩溃), 不是意外。
#
# 因此这个自愈必须:
#   1. 真的能把 podman ps 从 rc=125 救回 rc=0   ← 用**真实 podman** 验证,
#                                                不能只断言文件内容变了
#   2. 不碰非空文件(那是有效 healthcheck 记录)
#   3. 能通过路径覆盖在**临时目录**里测试, 不动真实容器状态
#
# ## 第五课: 判据必须打在探针本身
#
# 若只断言"文件非空了", 那守护不到真实故障 —— 因为 podman 可能因为
# **别的**原因失败。所以这里真的去跑 podman ps, 看退出码。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UP_SH="$SCRIPT_DIR/../up.sh"
[ -f "$UP_SH" ] || UP_SH="$SCRIPT_DIR/up.sh"
FAILURES=0
CHECKS=0

check() {
  local name="$1" cond="$2" detail="${3:-}"
  CHECKS=$((CHECKS + 1))
  if [ "$cond" -eq 1 ]; then
    printf '  PASS  %s\n' "$name"
  else
    FAILURES=$((FAILURES + 1))
    printf '  FAIL  %s%s\n' "$name" "${detail:+ — $detail}"
  fi
}

# 取出被测函数体(只 source 函数, 不执行整个脚本)。
FN_SRC=$(mktemp)
WORK=$(mktemp -d)
trap 'rm -f "$FN_SRC"; rm -rf "$WORK"' EXIT

awk '/^repair_healthcheck_logs\(\) \{/,/^\}/' "$UP_SH" >"$FN_SRC"
if [ ! -s "$FN_SRC" ]; then
  echo "  FAIL  无法从 $UP_SH 提取 repair_healthcheck_logs"
  echo "自检失败: 1 / 1 项"
  exit 1
fi
# shellcheck disable=SC1090
. "$FN_SRC"

echo "== repair_healthcheck_logs 守护测试 =="

# ---- 场景 1: 空文件被修复, 非空文件被保留 ----
FAKE="$WORK/root/containers/overlay-containers"
mkdir -p "$FAKE/aaa" "$FAKE/bbb" "$FAKE/ccc"
: >"$FAKE/aaa/healthcheck.log"                                   # 空 → 应修
printf '{"Status":"healthy","FailingStreak":0,"Log":[]}' >"$FAKE/bbb/healthcheck.log"  # 有效 → 不碰
: >"$FAKE/ccc/healthcheck.log"                                   # 空 → 应修

before_b=$(cat "$FAKE/bbb/healthcheck.log")
XDG_RUNTIME_DIR="$WORK/root" repair_healthcheck_logs >/dev/null
after_b=$(cat "$FAKE/bbb/healthcheck.log")

check "空的 healthcheck.log 已被写入合法 JSON" \
  "$(grep -q '^{.*}$' "$FAKE/aaa/healthcheck.log" && echo 1 || echo 0)" \
  "实际内容: $(cat "$FAKE/aaa/healthcheck.log")"
check "非空的 healthcheck.log 保持原样" \
  "$([ "$before_b" = "$after_b" ] && echo 1 || echo 0)" \
  "前=$before_b 后=$after_b"
check "第二个空文件也被修复" \
  "$(grep -q '^{.*}$' "$FAKE/ccc/healthcheck.log" && echo 1 || echo 0)"

# ---- 场景 2: 幂等(再跑一次不应改变任何东西) ----
snap1=$(cat "$FAKE"/*/healthcheck.log | md5sum)
XDG_RUNTIME_DIR="$WORK/root" repair_healthcheck_logs >/dev/null
snap2=$(cat "$FAKE"/*/healthcheck.log | md5sum)
check "重复执行是幂等的" "$([ "$snap1" = "$snap2" ] && echo 1 || echo 0)"

# ---- 场景 3: 目录不存在时必须安静返回 0(不能因 set -e 中断脚本) ----
XDG_RUNTIME_DIR="$WORK/nonexistent" repair_healthcheck_logs >/dev/null 2>&1
check "目录不存在时返回 0 而非报错" "$([ $? -eq 0 ] && echo 1 || echo 0)"

# ---- 场景 4: 负向对照 —— 对真实 podman 的判据 ----
# 只有这一项真正打在探针上: 造一个空 healthcheck.log, 确认 podman ps 会失败;
# 再调用被测函数, 确认 podman ps 恢复。前半段若不能复现失败, 说明这组断言
# 在验证一个不存在的问题 —— 必须显式报出来, 而不是静默跳过。
echo
echo "-- 场景 4: 对真实 podman 的负向对照 --"
REAL_ROOT="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/containers/overlay-containers"

if [ ! -d "$REAL_ROOT" ]; then
  check "真实 podman 判据 (跳过: $REAL_ROOT 不存在)" 0 "环境无 podman 容器目录"
elif ! command -v podman >/dev/null 2>&1; then
  check "真实 podman 判据 (跳过: podman 未安装)" 0 ""
else
  # 故意造一个空文件, 看 podman 是否真的会因此失败。
  # ⚠️ 必须**验证注入生效**(第八课补记一): 若 podman 本来就 rc=0,
  #   那这组对照就无从谈起, 此时不能宣称"对照通过"。
  SAB=$(mktemp -d)
  mkdir -p "$SAB/healthcheck.log" 2>/dev/null || true
  VICTIM="$REAL_ROOT/selftest-probe-$$"
  if mkdir -p "$VICTIM" 2>/dev/null; then
    : >"$VICTIM/healthcheck.log"
    # 验证注入确实生效: 该目录确实存在且文件为空
    if [ -f "$VICTIM/healthcheck.log" ] && [ ! -s "$VICTIM/healthcheck.log" ]; then
      before_rc=$(timeout 30 podman ps -a --format '{{.Names}}' >/dev/null 2>&1; echo $?)
      if [ "$before_rc" -eq 0 ]; then
        check "负向对照前置: 注入空文件后 podman ps 应失败" 0 \
          "podman ps rc=$before_rc, 无法复现故障 —— 本组对照不成立"
        check "被测函数能把 podman ps 从失败救回" 0 "前置不成立, 跳过"
      else
        check "负向对照前置: 注入空文件后 podman ps 确实失败" 1 \
          "rc=$before_rc (预期非 0)"
        XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}" repair_healthcheck_logs >/dev/null
        after_rc=$(timeout 30 podman ps -a --format '{{.Names}}' >/dev/null 2>&1; echo $?)
        check "被测函数能把 podman ps 从 rc=$before_rc 救回" \
          "$([ "$after_rc" -eq 0 ] && echo 1 || echo 0)" "after_rc=$after_rc"
      fi
    else
      check "负向对照前置: 注入生效(文件存在且为空)" 0 "注入未生效"
      check "被测函数能把 podman ps 从失败救回" 0 "注入未生效, 跳过"
    fi
    rm -rf "$VICTIM"
  else
    check "负向对照前置: 能创建探针目录" 0 "无法在 $REAL_ROOT 下建目录"
    check "被测函数能把 podman ps 从失败救回" 0 "无法注入, 跳过"
  fi
  rm -rf "$SAB"
fi

echo
if [ "$FAILURES" -ne 0 ]; then
  echo "自检失败: $FAILURES / $CHECKS 项"
  exit 1
fi
echo "自检通过: $CHECKS 项"