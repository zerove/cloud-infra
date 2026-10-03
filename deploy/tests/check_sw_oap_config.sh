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

# ---------- 3. 模块清单差集 (需镜像) ----------
if [[ "${SKIP_IMAGE_PROBE:-0}" == "1" ]]; then
  yellow "SKIP_IMAGE_PROBE=1 —— 跳过模块清单比对"
  [[ "$HEADER_OK" == "1" ]] && exit 0 || exit 1
fi

if ! command -v podman >/dev/null 2>&1 && ! command -v docker >/dev/null 2>&1; then
  yellow "⚠️  无 podman/docker —— 跳过模块清单比对 (仅完成版本声明检查)"
  [[ "$HEADER_OK" == "1" ]] && exit 0 || exit 1
fi

RUNNER="podman"; command -v podman >/dev/null 2>&1 || RUNNER="docker"

OFFICIAL_MODULES="$(mktemp)"; REPO_MODULES="$(mktemp)"
trap 'rm -f "$OFFICIAL_MODULES" "$REPO_MODULES"' EXIT

if ! $RUNNER run --rm --entrypoint sh "$IMAGE_REPO:$TAG" -c \
     'grep -oE "^[a-z][a-z0-9-]*:" /skywalking/config/application.yml | tr -d ":" | sort -u' \
     > "$OFFICIAL_MODULES" 2>/dev/null; then
  yellow "⚠️  无法拉取/读取镜像 $IMAGE_REPO:$TAG 的配置 —— 跳过模块清单比对"
  [[ "$HEADER_OK" == "1" ]] && exit 0 || exit 1
fi

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
fi

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
    yellow ""
    yellow "⚠️  compose 的 ES 为 $ES_IMAGE, 但 SkyWalking 官方仅支持 ES 7/8。"
    yellow "    若 search 服务使用 Spring Data ES 9.x 客户端 (需 ES 9),"
    yellow "    则 OAP 启动会抛 'Unsupported version: ElasticSearch 9.0',"
    yellow "    且**与 OAP 镜像版本无关** (9.7.0 / 10.1.0 均复现)。"
    yellow "    这不是配置问题 —— search 与 SkyWalking 无法共用同一个 ES 实例。"
    yellow "    解法三选一, 见 deploy/compose.yaml 中 skywalking-oap 的注释。"
  elif [[ -n "$ES_IMAGE" ]]; then
    green "✅ compose 的 ES 版本为 $ES_IMAGE, 在 SkyWalking 支持范围内 (7/8)"
  fi
fi

echo
echo "──────────────────────────────────────────────"
echo "静态检查到此为止。**它不能替代真实启动验证** ——"
echo "配置类问题只有真正跑一次 OAP 才会暴露 (本文件就是这么被发现的)。"
echo

exit 0
