#!/usr/bin/env bash
# check_sw_oap_config.sh 的守护测试（负向对照版）
#
# 为什么需要它（2026-10-03 实测教训）:
#   被测脚本本身是一道**门禁**。门禁最大的风险不是"误报"，而是**假绿**——
#   在真正该报错的配置上报 exit 0，那么它保护的东西等于没保护。
#   本项目已真实发生过:
#     · SKIP_IMAGE_PROBE=1 时脚本在第 87 行直接 exit 0，
#       根本没跑到第 228 行的 ES 检查 ⇒ 那个开关曾让 ES 冲突检查形同虚设;
#     · 只比较主机名字符串，把 SW_STORAGE_ES_CLUSTER_NODES 改成
#       不存在的 'cloud-elasticsearch-sw' 也能"骗过"检查而报绿。
#   两次都是**门禁自己放过了它本该拦住的东西**。
#
# 因此本脚本的判据是**退出码**，而不是输出文本：
#   每条路径都先断言退出码，再（可选）断言关键信息。
#   只看"跑完没报错"会把上述两类假绿全部放过。
#
# 用法: deploy/tests/test_check_sw_oap_config.sh
# 退出码: 0=全部符合预期  1=有路径不符（说明门禁退化了）

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$REPO_ROOT/deploy/tests/check_sw_oap_config.sh"
COMPOSE="$REPO_ROOT/deploy/compose.yaml"
CONFIG="$REPO_ROOT/deploy/skywalking/config/application.yml"

PASS=0; FAIL=0
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }

# 备份与还原 —— 任何一条路径失败都必须把仓库恢复原样
BAK_COMPOSE="$(mktemp)"; BAK_CONFIG="$(mktemp)"
cp "$COMPOSE" "$BAK_COMPOSE"; cp "$CONFIG" "$BAK_CONFIG"
cleanup() { cp "$BAK_COMPOSE" "$COMPOSE"; cp "$BAK_CONFIG" "$CONFIG"
            rm -f "$BAK_COMPOSE" "$BAK_CONFIG"; }
trap cleanup EXIT

# 跑一次被测脚本, 打印退出码
run_gate() {
  SKIP_IMAGE_PROBE=1 "$SCRIPT" >"$OUT" 2>&1
  echo $?
}

assert_rc() { # 名称 期望退出码
  local name="$1" want="$2" got
  got="$(run_gate)"
  if [[ "$got" == "$want" ]]; then
    green "  ✅ $name → exit $got"; PASS=$((PASS+1))
  else
    red   "  ❌ $name → exit $got, 期望 $want"
    sed 's/^/       | /' "$OUT" | tail -6
    FAIL=$((FAIL+1))
  fi
}

# 只允许「白名单」中的绿色结论通过。
# 为什么需要它（负向对照实测发现）: 把新校验删掉后, 路径 2 的 exit code
# 仍可能因为**别的原因**是 1（例如模块清单检查同时失败），于是
# "exit 1 == 1" 判据通过 —— 尽管 ES 检查其实又变回了假绿。
# ⇒ 必须额外断言**没有**出现"已通过双实例分离"这类放行结论。
assert_not_passing() { # 名称 不该出现的绿色结论片段
  local name="$1" frag="$2"
  if grep -qF "$frag" "$OUT"; then
    red   "  ❌ $name 不该报绿，却输出了「$frag」—— 门禁在此配置下形同虚设"
    FAIL=$((FAIL+1))
  else
    green "  ✅ $name 未误报绿"; PASS=$((PASS+1))
  fi
}

assert_output() { # 名称 期望出现的片段
  local name="$1" frag="$2"
  if grep -qF "$frag" "$OUT"; then
    green "  ✅ $name 的错误信息包含「$frag」"; PASS=$((PASS+1))
  else
    red   "  ❌ $name 的输出里找不到「$frag」—— 报错不可读等于半个 bug"; FAIL=$((FAIL+1))
  fi
}

OUT="$(mktemp)"; trap 'cleanup; rm -f "$OUT"' EXIT

echo "═══ check_sw_oap_config.sh 守护测试 ═══"
echo

# ---- 0. 语法 ----
echo "[0] 语法检查"
if bash -n "$SCRIPT" 2>"$OUT"; then
  green "  ✅ bash -n 通过"; PASS=$((PASS+1))
else
  red "  ❌ 语法错误"; cat "$OUT"; FAIL=$((FAIL+1))
fi
echo

# ---- 1. 当前仓库状态: OAP 与 search 共用 ES 9 → 必须 exit 1 ----
echo "[1] 真实仓库状态（OAP 与 search 共用 ES 9）应硬失败"
cp "$BAK_COMPOSE" "$COMPOSE"; cp "$BAK_CONFIG" "$CONFIG"
assert_rc "共用 ES 9" 1
assert_output "共用 ES 9" "SkyWalking 仅支持 ES 7/8"
assert_not_passing "共用 ES 9" "已通过双实例分离"
echo

# ---- 2. 负向对照: 指向不存在的服务（旧版会假绿） ----
echo "[2] 指向不存在的服务名 → 必须 exit 1（旧版此处报绿）"
sed -i 's#SW_STORAGE_ES_CLUSTER_NODES=cloud-elasticsearch:#SW_STORAGE_ES_CLUSTER_NODES=cloud-elasticsearch-sw:#' "$COMPOSE"
assert_rc "不存在的服务" 1
assert_output "不存在的服务" "不存在对应服务"
assert_not_passing "不存在的服务" "已通过双实例分离"
cp "$BAK_COMPOSE" "$COMPOSE"
echo

# ---- 3. 正向: 独立 ES 8 实例 → 必须 exit 0 ----
echo "[3] OAP 指向独立 ES 8 实例 → 必须 exit 0"
python3 - "$COMPOSE" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
i = s.index('\n  elasticsearch:')
s = s[:i] + '''

  elasticsearch-sw:
    image: elasticsearch:8.18.0
    container_name: cloud-elasticsearch-sw
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
''' + s[i:]
s = s.replace('SW_STORAGE_ES_CLUSTER_NODES=cloud-elasticsearch:',
              'SW_STORAGE_ES_CLUSTER_NODES=cloud-elasticsearch-sw:')
open(p, 'w', encoding='utf-8').write(s)
PY
assert_rc "独立 ES 8" 0
assert_output "独立 ES 8" "已通过双实例分离"
echo

# ---- 4. 负向对照: 分离了但两边都是 ES 9 → 必须 exit 1 ----
echo "[4] 分离了但两边都是 ES 9 → 必须 exit 1（'分离'不等于'解决冲突'）"
sed -i 's#image: elasticsearch:8.18.0#image: elasticsearch:9.0.3#' "$COMPOSE"
assert_rc "双 ES 9" 1
assert_output "双 ES 9" "SkyWalking 仍不支持 9"
assert_not_passing "双 ES 9" "已通过双实例分离"
cp "$BAK_COMPOSE" "$COMPOSE"
echo

# ---- 5. 负向对照: 版本声明与 compose 错配 ----
echo "[5] 配置头部版本声明与 compose 错配 → 必须 exit 1"
sed -i 's#skywalking-oap-server:10.1.0#skywalking-oap-server:9.7.0#' "$CONFIG"
assert_rc "版本声明错配" 1
assert_not_passing "版本声明错配" "已通过双实例分离"
cp "$BAK_CONFIG" "$CONFIG"
echo

# ---- 6. 门禁自身不能被开关绕过 ----
echo "[6] SKIP_IMAGE_PROBE=1 不得跳过 ES 检查"
# 若实现回退成"跳过即 exit 0", 则第 1 条路径在开启该开关后会变绿 —— 那就是假绿。
# 这里显式再确认一次, 并要求脚本**说明**它跳过了什么。
if grep -q "第 4/5 节(ES 版本、UI 版本)仍会执行" "$SCRIPT"; then
  green "  ✅ 脚本明确声明跳过范围不含 ES/UI 检查"; PASS=$((PASS+1))
else
  red "  ❌ 缺少'跳过范围'声明 —— 无法确认 ES 检查没被一并跳过"; FAIL=$((FAIL+1))
fi
echo

echo "══════════════════════════════════════"
echo "通过 $PASS 项，失败 $FAIL 项"
[[ "$FAIL" == "0" ]] || exit 1
green "守护测试全部通过"
