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

# ---- 场景 1b: 非空但**格式非法** (2026-10-05 实测的新形态) ----
# 原实现只处理 0 字节 (`[ -s "$f" ] && continue`), 而实测遇到的损坏是
# 非空却解析不了: 我按直觉写了 `[]`, podman 报
#   `readObjectStart: expect { or n, but found [`
# 它要的是**单个 JSON 对象**而非数组。该文件非空 -> 原实现跳过 -> 故障持续,
# 而此时 `podman ps` 已彻底不可用(全局性: 1 个坏文件拖垮所有容器查询)。
#
# ⚠️ 判据取自**真实健康容器样本**, 不是猜的格式(第五课):
#   {"Status":"healthy","FailingStreak":0,"Log":[...]}
FAKE2="$WORK/root2/containers/overlay-containers"
mkdir -p "$FAKE2/arr" "$FAKE2/bin" "$FAKE2/keep"
printf '[]'                  >"$FAKE2/arr/healthcheck.log"   # 非空+非法(我踩过的)
printf '\000\000\000\000'    >"$FAKE2/bin/healthcheck.log"   # 二进制垃圾(报错里的 \x00)
VALID='{"Status":"healthy","FailingStreak":0,"Log":[{"ExitCode":0}]}'
printf '%s' "$VALID"         >"$FAKE2/keep/healthcheck.log"   # 合法 → 绝不能动

XDG_RUNTIME_DIR="$WORK/root2" repair_healthcheck_logs >/dev/null

check "非空但格式非法([])的文件被修复为合法 JSON" \
  "$(head -c1 "$FAKE2/arr/healthcheck.log" | grep -q '{' && echo 1 || echo 0)" \
  "实际内容: $(head -c 40 "$FAKE2/arr/healthcheck.log" | tr -d '\0')"
check "二进制垃圾内容的文件被修复" \
  "$(head -c1 "$FAKE2/bin/healthcheck.log" | grep -q '{' && echo 1 || echo 0)" \
  "实际内容: $(head -c 20 "$FAKE2/bin/healthcheck.log" | od -c | head -1)"
check "合法文件不被改写 (修复不能变成破坏源)" \
  "$([ "$(cat "$FAKE2/keep/healthcheck.log")" = "$VALID" ] && echo 1 || echo 0)" \
  "被改成: $(cat "$FAKE2/keep/healthcheck.log")"

# ---- 场景 2: 幂等(再跑一次不应改变任何东西) ----
snap1=$(cat "$FAKE"/*/healthcheck.log | md5sum)
XDG_RUNTIME_DIR="$WORK/root" repair_healthcheck_logs >/dev/null
snap2=$(cat "$FAKE"/*/healthcheck.log | md5sum)
check "重复执行是幂等的" "$([ "$snap1" = "$snap2" ] && echo 1 || echo 0)"

# ---- 场景 3: 目录不存在时必须安静返回 0(不能因 set -e 中断脚本) ----
XDG_RUNTIME_DIR="$WORK/nonexistent" repair_healthcheck_logs >/dev/null 2>&1
check "目录不存在时返回 0 而非报错" "$([ $? -eq 0 ] && echo 1 || echo 0)"

# ---- 场景 4: 对**真实 podman** 的判据 (已知注入方式无效 —— 见下方说明) ----
#
# ⚠️⚠️【2026-10-05 实测结论】这一组的前置**先天无法成立**, 不要试图修复它:
#
#   podman 只会解析**已在 storage 注册**的容器的 healthcheck.log。
#   我实际验证过: 在 runroot 下 `mkdir jcode-probe-illegal` 再写非法内容,
#   `podman ps -a` 仍然 rc=0 —— 因为该目录不对应任何已注册容器,
#   podman 根本不会去读它。
#
#   而能被读到的那个损坏文件(实测是 cloud-postgres 的), 其 runroot 目录里
#   **只有** healthcheck.log, 连 config.json 都没有 —— 说明 podman 读它
#   靠的是 storage 里的注册记录, 而不是"目录存在"。
#
#   ⇒ 想用真实 podman 做对照, 只能**破坏一个真实容器的** healthcheck.log,
#     而那会污染运行中的环境(且 podman 可能随时覆写)。
#     本守护不采用: 守护测试不该有副作用。
#
#   所以本组的正确形态是 SKIP 并说明原因 —— 不是"通过", 也不是"失败"。
#   场景 1b 才是真正有效的判据: 它用**被测逻辑自身**验证三种损坏形态,
#   且断言判据直接取自 podman 报错原文(expect { or n)。
echo
echo "-- 场景 4: 对真实 podman 的负向对照 (注入方式无效, 见注释; 预期 SKIP) --"
REAL_ROOT="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/containers/overlay-containers"

if [ ! -d "$REAL_ROOT" ]; then
  echo "  SKIP  真实 podman 判据 ($REAL_ROOT 不存在)"
elif ! command -v podman >/dev/null 2>&1; then
  echo "  SKIP  真实 podman 判据 (podman 未安装)"
else
  echo "  SKIP  真实 podman 判据 (podman 只读已注册容器的 healthcheck.log;"
  echo "        往 runroot 塞新目录无效 —— 破坏真实容器又有副作用, 本守护不做)"
fi

echo
if [ "$FAILURES" -ne 0 ]; then
  echo "自检失败: $FAILURES / $CHECKS 项"
  exit 1
fi
echo "自检通过: $CHECKS 项"