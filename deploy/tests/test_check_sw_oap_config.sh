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
REAL_COMPOSE="$REPO_ROOT/deploy/compose.yaml"
REAL_CONFIG="$REPO_ROOT/deploy/skywalking/config/application.yml"

# ⚠️ 每个用例都拿到**自己的一份**副本, 且被测脚本通过路径参数读它。
#   绝不改真实 deploy/compose.yaml。
#   旧写法(改真实文件 + 用 cp 还原)的代价本次已经付过:
#     · 一次漏掉的 cp 就让下一个用例跑在被上一个用例改过的内容上,
#       本次真的发生了 —— "双 ES 9" 用例因此报了绿。
#   即**测试用例之间共享可变状态**, 会让守护测试自己变成假绿来源。
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
COMPOSE="$WORK/compose.yaml"
CONFIG="$WORK/application.yml"
fresh() { cp "$REAL_COMPOSE" "$COMPOSE"; cp "$REAL_CONFIG" "$CONFIG"; }

PASS=0; FAIL=0
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }

# 每个用例开始前重置副本 —— 真实文件从未被触碰
fresh

# 跑一次被测脚本, 打印退出码
run_gate() {
  SKIP_IMAGE_PROBE=1 COMPOSE_FILE="$COMPOSE" CONFIG_FILE="$CONFIG" \
    "$SCRIPT" >"$OUT" 2>&1
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

OUT="$(mktemp)"; trap 'rm -rf "$WORK" "$OUT"' EXIT

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
fresh
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
fresh
echo

# 往 compose 副本里注入一个「独立 ES 服务」并让 OAP 指向它。
# ⚠️ 抽成函数的原因: 这个注入**不是**一条 sed —— 它要先在
#    `  elasticsearch:` 服务块之前插入一个全新的服务块。
#    早期版本把它写成 `sed 's|image: elasticsearch:8.18.0|...|'`,
#    而该字面量只在**注入之后**才存在, 于是用例 4/7/8 的 sed 全部
#    静默无操作, 测试自己"通过"了但什么都没测。
#    这正是第 6 类假绿: **探针失效被读成了通过**(见第八课补记)。
#    凡是注入有前置条件的, 就不能靠 sed 替换一个当前不存在的字面量。
inject_separate_es() { # 版本标签
  # ⚠️ 必须先重置副本。若不重置, 而上一个用例已经注入过一个
  #    `elasticsearch-sw`, 这里会再插一个 → 同一 compose 里两个同名服务,
  #    下游 awk 取到**第一个**(旧版本) 而不是刚注入的 → 该红的不红。
  #    即"函数假定自己被调用的初始状态", 而调用方没保证。
  fresh
  python3 - "$COMPOSE" "$1" <<'PY'
import sys
p, tag = sys.argv[1], sys.argv[2]
s = open(p, encoding='utf-8').read()
i = s.index('\n  elasticsearch:')
s = s[:i] + f'''

  elasticsearch-sw:
    image: elasticsearch:{tag}
    container_name: cloud-elasticsearch-sw
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
''' + s[i:]
s = s.replace('SW_STORAGE_ES_CLUSTER_NODES=cloud-elasticsearch:',
              'SW_STORAGE_ES_CLUSTER_NODES=cloud-elasticsearch-sw:')
open(p, 'w', encoding='utf-8').write(s)
PY
  # 注入后自检: 注入失败就立刻失败, 不能带着未生效的副本去断言被测行为
  grep -q "image: elasticsearch:$1" "$COMPOSE" || {
    red "❌ inject_separate_es($1) 未生效 —— 测试自身失效"; exit 2; }
  grep -q "cloud-elasticsearch-sw:9200" "$COMPOSE" || {
    red "❌ OAP 未指向新实例 —— 测试自身失效"; exit 2; }
}

# ---- 3. 正向: 独立 ES 8 实例 → 必须 exit 0 ----
echo "[3] OAP 指向独立 ES 8 实例 → 必须 exit 0"
inject_separate_es 8.18.0
assert_rc "独立 ES 8" 0
assert_output "独立 ES 8" "已通过双实例分离"
fresh
echo

# ---- 4. 负向对照: 分离了但两边都是 ES 9 → 必须 exit 1 ----
echo "[4] 分离了但两边都是 ES 9 → 必须 exit 1（'分离'不等于'解决冲突'）"
inject_separate_es 9.0.3
assert_rc "双 ES 9" 1
# 断言消息而非退出码: 双 ES 9 现在由**白名单**分支拦下(而不是原来的 9.x 黑名单),
# 文案是"不在 SkyWalking 支持范围内"。只断言 exit 1 不够 ——
# 用例 7 的四个"非 7/8"配置走的是同一个分支, 需要区分二者。
assert_output "双 ES 9" "不在 SkyWalking 支持范围内"
assert_not_passing "双 ES 9" "已通过双实例分离"
fresh
echo

# ---- 5. 负向对照: 版本声明与 compose 错配 ----
echo "[5] 配置头部版本声明与 compose 错配 → 必须 exit 1"
sed -i 's#skywalking-oap-server:10.1.0#skywalking-oap-server:9.7.0#' "$CONFIG"
assert_rc "版本声明错配" 1
assert_not_passing "版本声明错配" "已通过双实例分离"
fresh
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

# ---- 7. 黑名单改白名单: 非 7/8 的镜像不得静默放行 ----
# ⚠️ 这是本次新增的第 6 类假绿（"不知道的东西被当成了对的东西"）:
#   原判据只排除 ^9\. 的**黑名单**, 于是 opensearch:2.11 / 无 tag 的
#   :latest / 任何拼写错误都会走进 green 分支。
# 逐个都要判红 —— 它们各自的失败理由不同, 缺一个就有漏网路径:
#   elasticseach:8.18.0 拼写错误  : 版本号合法但镜像名错, 黑名单完全看不见
#   es:8                        : tag 合法但镜像名错
#   elasticsearch:latest        : 无版本, 未来可能变成 9.x
#   opensearch:2.11.1           : 源自 ES 7.10.2 但协议不兼容
echo "[7] 白名单: 非 7/8 的 OAP ES 镜像必须 exit 1"
for bad in "opensearch:2.11.1" "elasticsearch:latest" "elasticseach:8.18.0" "es:8"; do
  fresh
  python3 - "$COMPOSE" "$bad" <<'PY'
import sys
p, img = sys.argv[1], sys.argv[2]
s = open(p, encoding='utf-8').read()
i = s.index('\n  elasticsearch:')
s = s[:i] + f'''

  elasticsearch-sw:
    image: {img}
    container_name: cloud-elasticsearch-sw
''' + s[i:]
s = s.replace('SW_STORAGE_ES_CLUSTER_NODES=cloud-elasticsearch:',
              'SW_STORAGE_ES_CLUSTER_NODES=cloud-elasticsearch-sw:')
open(p, 'w', encoding='utf-8').write(s)
PY
  # 注入未生效即判测试自身失效 —— 不得带着未生效的副本去断言被测行为
  if ! grep -q "image: $bad" "$COMPOSE"; then
    red "  ❌ 未能注入 '$bad' —— 测试自身失效, 不能当作被测行为"; FAIL=$((FAIL+1)); continue
  fi
  assert_rc "白名单 $bad" 1
  assert_not_passing "白名单 $bad" "已通过双实例分离"
done
fresh
echo

# ---- 8. 白名单的反向: 7.x 必须仍然放行 ----
# 白名单收紧可能写过头。若把合法的 7.x 也判红, 就是"修复引入新缺陷"。
echo "[8] ES 7.x 仍在官方支持范围内, 必须 exit 0（防白名单写过头）"
inject_separate_es 7.17.28
assert_rc "ES 7.x 放行" 0
assert_output "ES 7.x 放行" "已通过双实例分离"
fresh
echo

echo "══════════════════════════════════════"
echo "通过 $PASS 项，失败 $FAIL 项"
[[ "$FAIL" == "0" ]] || exit 1
green "守护测试全部通过"
