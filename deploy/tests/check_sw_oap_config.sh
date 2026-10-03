#!/usr/bin/env bash
# 校验 SkyWalking OAP 的 application.yml 与 compose 镜像 tag 是否版本对齐。
#
# 背景 (2026-10-03 实测): 本仓库曾出现**双向版本错配**——
#   compose 用 apache/skywalking-oap-server:9.7.0,
#   而 deploy/skywalking/config/application.yml 是从 10.1.0/11.x 抄来后又手工删减的
#   (官方 9.7.0 有 37 个模块块, 本文件曾只剩 23 个), 于是 OAP **从未成功启动过**,
#   崩溃循环 restarts=51 却因为 depends_on 只等 service_started 而被当成"已就绪"。
#
#   错配表现为两类硬失败:
#     ProviderNotFoundException : 配置块引用了镜像没有的 provider (如 storage:h2)
#     ModuleNotFoundException   : 模块间依赖缺失 (如 receiver-event 依赖 event-analyzer)
#   两类都只在**真正重建容器**时才暴露, 所以必须有这个前置门禁。
#
# 本脚本做两件事 (都不依赖网络/容器, 可在 CI 与本地离线跑):
#   1. 从 compose 解析 OAP 镜像 tag, 与本仓 application.yml 的头部声明比对;
#   2. 用镜像内官方 application.yml 的模块清单做差集, 双向报出多出/缺失的模块。
#
# 用法:
#   deploy/tests/check_sw_oap_config.sh              # 用 compose 里的 tag
#   deploy/tests/check_sw_oap_config.sh 10.1.0       # 指定 tag
#   SKIP_IMAGE_PROBE=1 deploy/tests/check_sw_oap_config.sh   # 跳过第 2 项(无镜像时)
#
# 退出码: 0=对齐  1=错配(给出差异)  2=用法/环境错误

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/deploy/compose.yaml"
CONFIG_FILE="$REPO_ROOT/deploy/skywalking/config/application.yml"
IMAGE_REPO="apache/skywalking-oap-server"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }

# ---------- 1. 解析 tag ----------
resolve_tag() {
  if [[ $# -ge 1 && -n "${1:-}" ]]; then
    echo "$1"; return 0
  fi
  [[ -f "$COMPOSE_FILE" ]] || { red "找不到 $COMPOSE_FILE"; return 2; }
  # 只在 skywalking-oap 服务块内取 image:, 避免匹配到别处的镜像行
  awk '
    /^  skywalking-oap:/ {inblk=1; next}
    inblk && /^  [a-zA-Z0-9_-]+:/ {inblk=0}
    inblk && $1=="image:" && $2 ~ /skywalking-oap-server/ {print $2; exit}
  ' "$COMPOSE_FILE" | sed 's/.*://'
}

TAG="$(resolve_tag "${1:-}")"
if [[ -z "$TAG" ]]; then
  red "未能从 compose 解析出 OAP 镜像 tag"; exit 2
fi

echo "compose 中的 OAP 镜像 tag : $TAG"
echo "配置文件                    : ${CONFIG_FILE#$REPO_ROOT/}"
echo

# ---------- 2. 配置头部声明的版本 ----------
DECLARED=""
if [[ -f "$CONFIG_FILE" ]]; then
  DECLARED="$(grep -oE 'skywalking-oap-server:[0-9]+\.[0-9]+\.[0-9]+' "$CONFIG_FILE" | head -1 | sed 's/.*://')"
else
  red "找不到 $CONFIG_FILE"; exit 2
fi

if [[ -z "$DECLARED" ]]; then
  yellow "⚠️  配置头部未声明镜像版本 —— 请在文件开头写明"
  yellow "    '基于官方 apache/skywalking-oap-server:X.Y.Z 的 application.yml 同步'"
  yellow "    否则下一个人无从判断该配置对应哪个镜像。"
  HEADER_OK=0
elif [[ "$DECLARED" != "$TAG" ]]; then
  red "❌ 版本声明与 compose 不一致"
  red "   配置声明 : $DECLARED"
  red "   compose  : $TAG"
  HEADER_OK=0
else
  green "✅ 版本声明一致 ($DECLARED)"
  HEADER_OK=1
fi
echo

ALL_OK=$HEADER_OK
# 本脚本用 `set -u`，而被 SKIP_IMAGE_PROBE 等条件跳过的分支仍可能在
# 末尾的提示语里被引用。统一预初始化，避免"某个开关组合下直接报错退出"
# ——那会被当成检查失败，掩盖真实结论 (2026-10-03 实测拄过两次)。
MODULES_OK=1
ES_OK=1
UI_OK=1
# 末尾的提示语会引用 $RUNNER，即便跳过镜像比对也不能让它未定义 (set -u)。
RUNNER="podman"; command -v podman >/dev/null 2>&1 || RUNNER="docker"

# ---------- 3. 模块清单差集 (需镜像) ----------
if [[ "${SKIP_IMAGE_PROBE:-0}" == "1" ]]; then
  yellow "SKIP_IMAGE_PROBE=1 —— 跳过模块清单比对"
  echo
  echo "──────────────────────────────────────────────"
  echo "跳过的是**模块清单**比对; 第 4/5 节(ES 版本、UI 版本)仍会执行。"
  echo "原因: ES 冲突会导致 OAP **必然**启动失败 (已实测 100%),"
  echo "      它不依赖镜像内配置, 不该被'跳过模块比对'一并带过。"
  echo "      本节 2026-10-03 修复过一个真实假绿: 早前这里直接 exit 0,"
  echo "      导致 SKIP_IMAGE_PROBE=1 时 ES 冲突检查从未被执行过。"
fi

if [[ "${SKIP_IMAGE_PROBE:-0}" != "1" ]]; then
if ! command -v podman >/dev/null 2>&1 && ! command -v docker >/dev/null 2>&1; then
  yellow "⚠️  无 podman/docker —— 跳过模块清单比对 (仅完成版本声明检查)"
fi

if command -v podman >/dev/null 2>&1 || command -v docker >/dev/null 2>&1; then
RUNNER="podman"; command -v podman >/dev/null 2>&1 || RUNNER="docker"

OFFICIAL_MODULES="$(mktemp)"; REPO_MODULES="$(mktemp)"
trap 'rm -f "$OFFICIAL_MODULES" "$REPO_MODULES"' EXIT

if ! $RUNNER run --rm --entrypoint sh "$IMAGE_REPO:$TAG" -c \
     'grep -oE "^[a-z][a-z0-9-]*:" /skywalking/config/application.yml | tr -d ":" | sort -u' \
     > "$OFFICIAL_MODULES" 2>/dev/null; then
  yellow "⚠️  无法拉取/读取镜像 $IMAGE_REPO:$TAG 的配置 —— 跳过模块清单比对"
else
  grep -oE '^[a-z][a-z0-9-]*:' "$CONFIG_FILE" | tr -d ':' | sort -u > "$REPO_MODULES"

EXTRA="$(comm -13 "$OFFICIAL_MODULES" "$REPO_MODULES")"   # 配置有、镜像官方无
MISSING="$(comm -23 "$OFFICIAL_MODULES" "$REPO_MODULES")" # 镜像官方有、配置无

MODULES_OK=1
if [[ -n "$EXTRA" ]]; then
  red "❌ 配置中存在镜像 $TAG 没有的模块 (启动会抛 ModuleNotFoundException):"
  echo "$EXTRA" | sed 's/^/     /'
  MODULES_OK=0
fi
if [[ -n "$MISSING" ]]; then
  red "❌ 配置缺失镜像 $TAG 官方自带的模块 (启动会抛 'X module is required by Y'):"
  echo "$MISSING" | sed 's/^/     /'
  MODULES_OK=0
fi

if [[ "$MODULES_OK" == "1" ]]; then
  green "✅ 模块清单与镜像 $TAG 完全对齐 (共 $(wc -l < "$OFFICIAL_MODULES") 个模块)"
else
  ALL_OK=0
fi
fi   # 关闭: if 镜像可读
fi   # 关闭: if RUNNER 存在
fi   # 关闭: if SKIP_IMAGE_PROBE != 1

echo
echo "──────────────────────────────────────────────"
echo "若确实需要增删模块, 请同步确认:"
echo "  1. 该模块的 provider jar 确实存在于镜像 oap-libs/"
echo "     $RUNNER run --rm --entrypoint sh $IMAGE_REPO:$TAG \\"
echo "       -c 'ls /skywalking/oap-libs/ | grep -i <module>'"
echo "  2. 依赖它的模块不会因此报 'module is required by'"
echo "  3. 用**同一份配置 + 同一套依赖**实测启动一次, 而不是只看配置"
echo

[[ "$HEADER_OK" == "1" && "$MODULES_OK" == "1" ]] || exit 1

# ---------- 4. ES 版本兼容 (SkyWalking 只支持 ES 7/8) ----------
# 默认"无冲突": 若 compose 里既没解析出 ES 版本, 也不构成冲突场景,
# 不应因此判红 —— 保持"查不到就不下结论", 而不是"查不到就报错"。
ES_OK=1
# 官方文档 main 分支 backend-storage 明确写 "ElasticSearch 7 and 8"。
# 而本仓库 ES 需与 search 服务的 Spring Data ES 9.x 客户端对齐 (ES 9)。
# 两者冲突时 OAP 启动抛:
#   java.lang.UnsupportedOperationException: Unsupported version: ElasticSearch 9.0
# 该检查只做**静态预警**, 不代替真实启动验证。
if [[ -f "$COMPOSE_FILE" ]]; then
  ES_IMAGE="$(awk '
    /^  elasticsearch:/ {inblk=1; next}
    inblk && /^  [a-zA-Z0-9_-]+:/ {inblk=0}
    inblk && $1=="image:" {print $2; exit}
  ' "$COMPOSE_FILE" | sed 's/.*://')"

  if [[ "$ES_IMAGE" =~ ^9\. ]]; then
    # OAP 实际连的是哪个 ES, 取决于 SW_STORAGE_ES_CLUSTER_NODES 指向的服务名。
    # 若该变量指向的就是这个 ES 9 实例, 则是真冲突; 若指向另一个 (ES 7/8)
    # 实例, 则是**已正确分离**的双 ES 布局 —— 不能一律报红。
    SW_ES_NODES="$(awk '
      /^  skywalking-oap:/ {inblk=1; next}
      inblk && /^  [a-zA-Z0-9_-]+:/ {inblk=0}
      inblk && /SW_STORAGE_ES_CLUSTER_NODES=/ {
        sub(/.*SW_STORAGE_ES_CLUSTER_NODES=/, ""); print; exit
      }
    ' "$COMPOSE_FILE")"

    # 取主机名部分(去掉 :9200), 与 ES 服务名比对
    SW_ES_HOST="${SW_ES_NODES%%:*}"

    # 1) 定位 OAP 目标对应的 compose 服务块
    #    不能只比字符串: 目标主机必须**真实对应一个 Compose 服务**, 否则
    #    把值改成任意不存在的名字同样能"骗过"检查 (2026-10-03 实测假绿)。
    SW_TARGET_SVC=""
    SW_TARGET_IMAGE=""
    SW_TARGET_CNAME=""
    if [[ -n "$SW_ES_HOST" ]]; then
      SW_TARGET_SVC="$(awk -v h="$SW_ES_HOST" '
        /^  [a-zA-Z0-9_-]+:/ {svc=$1; sub(/:$/, "", svc)}
        svc && /^    container_name:/ {cn=$2; if (cn==h) {print svc; exit}}
      ' "$COMPOSE_FILE")"
      if [[ -n "$SW_TARGET_SVC" ]]; then
        SW_TARGET_IMAGE="$(awk -v s="  $SW_TARGET_SVC:" '
          $0==s {inblk=1; next}
          inblk && /^  [a-zA-Z0-9_-]+:/ {inblk=0}
          inblk && $1=="image:" {print $2; exit}
        ' "$COMPOSE_FILE" | sed 's/.*://')"
      fi
    fi

    ES_SVC_NAME="$(awk '
      /^  [a-zA-Z0-9_-]+:/ {svc=$1; sub(/:$/, "", svc)}
      svc == "elasticsearch" && /^    container_name:/ {print $2; exit}
    ' "$COMPOSE_FILE")"

    if [[ -z "$SW_ES_HOST" ]]; then
      red "❌ 未解析出 SW_STORAGE_ES_CLUSTER_NODES, 无法确认 OAP 的 ES 目标。"
      ES_OK=0
    elif [[ -z "$SW_TARGET_SVC" ]]; then
      red "❌ OAP 的 ES 目标 '$SW_ES_HOST' 在 compose 里**不存在对应服务**。"
      red "   该主机名不是任何服务的 container_name。"
      red "   ⇒ 这种配置无法启动, 且旧版检查会误判为'已分离'而报绿。"
      ES_OK=0
    elif [[ "$SW_ES_HOST" == "$ES_SVC_NAME" ]]; then
      red "❌ OAP 与 search 指向同一个 ES($ES_IMAGE), 但 SkyWalking 仅支持 ES 7/8。"
      red "   实测: OAP 启动抛 'Unsupported version: ElasticSearch 9.0',"
      red "        且**与 OAP 镜像版本无关** (9.7.0 / 10.1.0 均复现)。"
      red "   ⇒ 两者无法共用同一实例。已实测可行的解法(2026-10-03):"
      red "        给 SkyWalking 单独一个 ES 8 实例, OAP 指向它 —— 日志出现"
      red "        'ElasticSearch version is: ElasticSearch 8.18' 且"
      red "        'listening on 11800', 无 Unsupported version。"
      #
      # 为什么这一项是**硬失败**而 UI 版本错开只是 yellow:
      #   - ES 冲突: 已实测 100% 必崩, 且 6 个业务服务都
      #     `condition: service_healthy` 依赖 OAP ⇒ OAP 崩则整个 full 环境起不来。
      #     属于"必然失败", 不该只提醒。
      #   - UI 版本错开: 只是**可能**有 GraphQL 字段变更, 未实测会坏,
      #     属于"需要留意", 报 yellow 恰当。
      # 门禁各检查项的严重级别应与"实测确定性"匹配, 而不是一律软失败。
      ES_OK=0
    elif [[ -z "$SW_TARGET_IMAGE" ]]; then
      red "❌ OAP 的 ES 目标服务 '$SW_TARGET_SVC' 没有 image 字段, 无法确认版本。"
      ES_OK=0
    elif [[ "$SW_TARGET_IMAGE" =~ ^9\. ]]; then
      red "❌ OAP 指向的独立实例用的是 ES $SW_TARGET_IMAGE —— SkyWalking 仍不支持 9.x。"
      red "   目标服务 : $SW_TARGET_SVC ($SW_ES_HOST)"
      red "   镜像     : $SW_TARGET_IMAGE"
      red "   ⇒ '分离'了, 但两边都是 ES 9, 版本冲突依旧存在。"
      ES_OK=0
    else
      green "✅ ES 版本冲突已通过双实例分离:"
      green "     search  → $ES_SVC_NAME ($ES_IMAGE)"
      green "     OAP     → $SW_ES_HOST [服务 $SW_TARGET_SVC, 镜像 ES $SW_TARGET_IMAGE]"
    fi
  elif [[ -n "$ES_IMAGE" ]]; then
    green "✅ compose 的 ES 版本为 $ES_IMAGE, 在 SkyWalking 支持范围内 (7/8)"
    ES_OK=1
  fi

  # ---------- 5. UI 与 OAP 版本一致 ----------
  # 二者通过 GraphQL 交互, 跨小版本可能有字段变更。
  # 本轮就出现过 OAP 升 10.1.0 而 UI 留在 10.2.0 的错开状态 —— 未实测即引入。
  UI_IMAGE="$(awk '
    /^  skywalking-ui:/ {inblk=1; next}
    inblk && /^  [a-zA-Z0-9_-]+:/ {inblk=0}
    inblk && $1=="image:" {print $2; exit}
  ' "$COMPOSE_FILE" | sed 's/.*://')"

  if [[ -n "$UI_IMAGE" ]]; then
    if [[ "$UI_IMAGE" == "$TAG" ]]; then
      green "✅ skywalking-ui($UI_IMAGE) 与 skywalking-oap($TAG) 版本一致"
    else
      yellow "⚠️  skywalking-ui 为 $UI_IMAGE, 但 skywalking-oap 为 $TAG —— 版本错开"
      yellow "    UI↔OAP 通过 GraphQL 交互, 跨小版本可能有字段变更。"
      yellow "    建议对齐后实测一次 (verify-e2e.sh 会覆盖 UI 可达性)。"
    fi
  fi
fi

echo
echo "──────────────────────────────────────────────"
echo "静态检查到此为止。**它不能替代真实启动验证** ——"
echo "配置类问题只有真正跑一次 OAP 才会暴露 (本文件就是这么被发现的)。"
echo

[[ "$ALL_OK" == "1" && "$ES_OK" == "1" ]] || exit 1
exit 0
