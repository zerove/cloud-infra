#!/bin/bash
# Cloud-Infra 本地开发环境启动脚本
# 依赖: podman-compose, curl
# 构建: 使用项目内的 mvnw（无需预先安装 Maven）
#
# 用法:
#   ./up.sh                # 启动基础设施 + 同步配置 (不启动微服务, 用于开发)
#   ./up.sh --full         # 启动全部 (基础设施 + 微服务, 需要先构建镜像)
#   ./up.sh --check        # 检测本地配置与 Nacos 的漂移, 不执行变更
#   ./up.sh --sync         # 仅同步本地配置到 Nacos (不启动基础设施, 不重启容器)
#   ./up.sh --infra        # 仅启动基础设施 (不级联清理数据卷)
#   ./up.sh --self-test    # 仅运行兼容性自检 (不碰容器, 可在 CI 跑)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 默认仅基础设施; --full 时含微服务
COMPOSE_ARGS=()
FULL_SERVICES=()
FULL=0
CHECK_ONLY=0
SYNC_ONLY=0
INFRA_ONLY=0
SELF_TEST=0
for arg in "$@"; do
  case "$arg" in
    --full) FULL=1 ;;
    --check) CHECK_ONLY=1 ;;
    --sync) SYNC_ONLY=1 ;;
    --infra) INFRA_ONLY=1 ;;
    --self-test) SELF_TEST=1 ;;
    *) echo "未知参数: $arg (支持 --full --check --sync --infra --self-test)" >&2; exit 1 ;;
  esac
done

# compose.yaml 中 `profiles: ["full"]` 的服务名。
#
# ⚠️ 这里必须用 **compose 的服务名 (services 段下的 key)**, 不是镜像名, 也不是
# container_name。镜像名是 localhost/cloud-infra-gateway, container_name 是
# cloud-gateway, 但服务名就是 gateway。
# 548b9bc 误用了镜像名 (cloud-infra-gateway), 于是即便参数位置正确也会失败。
FULL_SERVICES=(gateway auth system file notification search)

# 探测 podman-compose 是否支持 --profile。
# CI 的 apt 包是 1.0.6 (实测其源码 grep -c profile = 0, 完全无此特性);
# 本地通常是 1.6.0。
compose_supports_profile() {
  podman-compose --help 2>&1 | grep -q -- '--profile'
}

# 拼出最终命令行。兼容两条路径:
#   支持 --profile: `podman-compose --profile full up -d`
#   不支持:        `podman-compose up -d gateway auth ...`
#                   实测 1.0.6 下显式点名确实会绕过 profile 过滤
#                   (最小复现: up -d web 启动 web, up -d infra 则 web/api 均被 skip),
#                   因此回退路径语义等价。
#
# ⚠️ 服务名必须放在 `up -d` **之后**。1.0.6 的 argparse 把第一个位置参数当 command,
# 放前面会得到 "invalid choice: 'gateway'" (2026-10-02 E2E 红灯的直接原因)。
resolve_compose_args() {
  COMPOSE_ARGS=()
  if [ "$FULL" -eq 1 ] && compose_supports_profile; then
    COMPOSE_ARGS+=(--profile full)
  fi
}

# 实际拼出的 `up` 参数列表。放服务名的分支与不放服务名的一律用数组返回,
# 避免未定义数组在 set -u 下展开出错。
compose_up_args() {
  if [ "$FULL" -eq 1 ] && ! compose_supports_profile; then
    printf '%s\n' up -d "${FULL_SERVICES[@]}"
  else
    printf '%s\n' up -d
  fi
}

resolve_compose_args

# ---------------------------------------------------------------------------
# 兼容性自检 (--self-test): 不启动任何容器, 验证参数拼装与 compose.yaml 一致。
#
# ⚠️ 为什么不能"跑一次 podman-compose 看退出码"来自检:
#   ① podman-compose --dry-run 在 1.6.0 下**输出为空** (实测 stdout/stderr 均 0 行),
#      探测不到任何东西; 放在 up 之后还会直接 error: unrecognized arguments;
#   ② 本地版本与 CI 不同 (本地 1.6.0 / CI apt 包 1.0.6), 用本地二进制自检
#      等于**永远没演练过 CI 的那条路径** —— 这是 548b9bc 自检方案的根本缺陷。
#
# 因此自检改为两件真正能失败的事:
#   ① 直接解析 compose.yaml 的 services 键, 核对 FULL_SERVICES 集合;
#   ② 用一个 1.0.6 行为桩校验参数**顺序** —— 桩只复刻 argparse 的
#      "第一个位置参数即 command" 这条规则, 与真实 1.0.6 报错一致。
#
# ⚠️ ① 为什么不用 `podman-compose config --services`(2026-10-02 修正):
#   该子命令在 CI 的 1.0.6 上**不存在**, 命令整体报错。原先的 `|| true`
#   把失败吞成空串, 于是自检在 CI 上恒定报 FAIL —— 又一次"本地绿、CI 红"。
#   本地是 1.6.0(支持该子命令), 正是这个版本差让它逃过了本地验证。
#   改为解析 YAML 文本: 不依赖 podman-compose 版本, 两边行为必然一致。
#
# ⚠️ 本自检自身也是负向对照的对象: 把服务名挪回 `up` 之前, 或改回镜像名,
# ① / ② 都必须变红。
# ---------------------------------------------------------------------------
self_test() {
  local failures=0

  echo "自检 1/2: FULL_SERVICES 与 compose.yaml 的服务名一致"
  # 只取 services 块内的顶层键。compose.yaml 里 services 下缩进 2 空格。
  local svc_list svc
  svc_list="$(awk '
    /^services:[[:space:]]*$/ { in_services = 1; next }
    in_services && /^[^[:space:]#]/ { in_services = 0 }
    in_services && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ {
      line = $0
      sub(/^  /, "", line)
      sub(/:.*/, "", line)
      print line
    }
  ' "$SCRIPT_DIR/compose.yaml")"
  if [ -z "$svc_list" ]; then
    echo "  FAIL  无法从 compose.yaml 解析出 services 列表"
    failures=$((failures + 1))
  else
    for svc in "${FULL_SERVICES[@]}"; do
      if printf '%s\n' "$svc_list" | grep -qx "$svc"; then
        echo "  PASS  服务名 '$svc' 存在"
      else
        echo "  FAIL  服务名 '$svc' 在 compose.yaml 中不存在"
        failures=$((failures + 1))
      fi
    done
  fi

  echo "自检 2/2: 服务名位于 'up' 之后 (1.0.6 行为桩)"
  # 桩: 复刻 1.0.6 的 argparse —— 第一个位置参数必须是已知 command。
  # 真实 1.0.6 报错为 "argument command: invalid choice: 'gateway'"。
  local stub
  stub="$(mktemp)"
  cat > "$stub" <<'STUB'
#!/usr/bin/env bash
# 复刻 podman-compose 1.0.6: 第一个位置参数被当作 command, 不匹配即报错。
positional=()
while [ $# -gt 0 ]; do
  case "$1" in
    -f|--file|--env-file|-p|--podman-path|--podman-args) shift 2 ;;
    -*) shift ;;
    *) positional+=("$1"); shift ;;
  esac
done
if [ ${#positional[@]} -eq 0 ]; then
  echo "STUB: 缺少 command" >&2
  exit 1
fi
case "${positional[0]}" in
  help|version|wait|systemd|pull|push|build|up|down|ps|run|exec|start|stop|restart|logs|config|port|pause|unpause|kill) ;;
  *) echo "podman-compose: error: argument command: invalid choice: '${positional[0]}'" >&2; exit 2 ;;
esac
echo "STUB_OK command=${positional[0]}"
STUB
  chmod +x "$stub"

  # 在子 shell 里把 compose_supports_profile 打成"不支持", 以覆盖 CI 的 1.0.6 路径。
  if (
    compose_supports_profile() { return 1; }
    FULL=1
    mapfile -t up_args < <(compose_up_args)
    "$stub" "${up_args[@]}"
  ) >/dev/null 2>&1; then
    echo "  PASS  服务名位于 up 之后, 1.0.6 可解析"
  else
    echo "  FAIL  服务名位置或顺序错误, 1.0.6 无法解析 (会得到 invalid choice)"
    failures=$((failures + 1))
  fi

  # 负向对照: 若顺序被改回 "服务名 up -d", 桩必须拒绝。
  if "$stub" "${FULL_SERVICES[0]}" up -d >/dev/null 2>&1; then
    echo "  FAIL  桩未能拒绝错误顺序, 说明自检本身失效"
    failures=$((failures + 1))
  else
    echo "  PASS  桩正确拒绝错误顺序 (负向对照成立)"
  fi

  rm -f "$stub"

  if [ "$failures" -eq 0 ]; then
    echo "自检通过"
    return 0
  fi
  echo "自检失败: $failures 项"
  return 1
}

if [ "$SELF_TEST" -eq 1 ]; then
  ENV_FILE="$PROJECT_ROOT/.env"
  resolve_compose_args
  self_test
  exit $?
fi

if [ "$CHECK_ONLY" -eq 1 ]; then
  echo "=== 配置漂移检测模式 (仅比较, 不执行变更) ==="
fi

# 自动生成 .env（仅当不存在时，避免覆盖用户自定义值）
# compose.yaml 中基础设施凭据与微服务密钥均为 ${VAR:?必填} 强制模式
ENV_FILE="$PROJECT_ROOT/.env"
ensure_env() {
  if [ ! -f "$ENV_FILE" ]; then
    if [ -f "$PROJECT_ROOT/.env.example" ]; then
      echo "=== 未检测到 .env, 从 .env.example 生成本地开发配置 ==="
      cp "$PROJECT_ROOT/.env.example" "$ENV_FILE"
      # 随机生成 JWT 密钥避免开发环境使用已知值
      RANDOM_SECRET=$(head -c 48 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 48)
      sed -i "s|^AUTH_JWT_SECRET=.*|AUTH_JWT_SECRET=${RANDOM_SECRET}|" "$ENV_FILE"
      echo "  ✓ 已生成 $ENV_FILE (AUTH_JWT_SECRET 已随机化)"
    else
      echo "✗ 缺少 .env 且无 .env.example 模板, 请手动创建 .env" >&2
      exit 1
    fi
  else
    echo "=== 使用现有 .env (如需重置请删除后重跑) ==="
  fi
  # shellcheck disable=SC1090
  # JAVA_OPTS 含空格与引号, 直接 source 会触发 "command not found";
  # 用 process substitution 过滤掉, 仅导出其余变量
  set -a; source <(grep -v '^JAVA_OPTS=' "$ENV_FILE"); set +a
}
ensure_env

NACOS_URL="http://localhost:8848"

# 配置同步函数: 上传单个文件到 Nacos (v3 admin API)
# 返回 0=已同步, 1=需要更新
nacos_config_diff() {
  local file="$1"
  local filename
  filename=$(basename "$file")
  local remote
  remote=$(curl -sf "$NACOS_URL/nacos/v3/client/cs/config?dataId=$filename&groupName=DEFAULT_GROUP&namespaceId=public" 2>/dev/null || echo "")
  # v3 API 返回 JSON 包装 {data:{content:...}}, 提取 content 字段
  remote=$(echo "$remote" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('content',''))" 2>/dev/null || echo "$remote")
  local local_content
  local_content=$(cat "$file")
  if [ "$remote" == "$local_content" ]; then
    echo "  ✓ $filename 无漂移"
    return 0
  else
    if [ -z "$remote" ]; then
      echo "  ✗ $filename 不存在于 Nacos, 需要创建"
    else
      echo "  ✗ $filename 存在漂移"
    fi
    return 1
  fi
}

# 配置同步函数: 上传单个文件到 Nacos (v3 admin API, form-encoded)
# 使用 curl 的 --data-urlencode "content@$file" 让 curl 读文件原文, 保留多行/中文/转义
nacos_config_upload() {
  local file="$1"
  local filename
  filename=$(basename "$file")
  local resp
  resp=$(curl -sf -X POST "$NACOS_URL/nacos/v3/admin/cs/config" \
    --data-urlencode "dataId=$filename" \
    --data-urlencode "groupName=DEFAULT_GROUP" \
    --data-urlencode "namespaceId=public" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@${file}" 2>/dev/null) || { echo "  ⚠️  导入 $filename 失败 (curl error)"; return 1; }
  # v3 API 返回 {"code":0,"message":"success","data":true} 表示成功
  if echo "$resp" | grep -q '"code":0'; then
    echo "  ✓ 同步 $filename"
    return 0
  else
    echo "  ⚠️  同步 $filename 失败: $resp"
    return 1
  fi
}

# 若仅同步, 直接推 Nacos 后退出 (不起动基础设施, 不重启容器)
if [ "$SYNC_ONLY" -eq 1 ]; then
  NACOS_URL="http://localhost:8848"
  echo "=== Nacos 配置同步模式 (仅推送, 不启动基础设施) ==="
  # 先确认 Nacos 在跑
  if ! curl -sf "$NACOS_URL/nacos/v3/client/cs/config?dataId=health&groupName=DEFAULT_GROUP&namespaceId=public" > /dev/null 2>&1; then
    echo "✗ Nacos 未就绪 ($NACOS_URL), 请先 ./deploy/up.sh 启动基础设施" >&2
    exit 1
  fi
  CONFIG_DIR="$SCRIPT_DIR/config"
  SYNC_COUNT=0
  for file in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.json; do
    [ -e "$file" ] || continue
    nacos_config_upload "$file" && SYNC_COUNT=$((SYNC_COUNT+1))
  done
  echo "=== 同步完成: $SYNC_COUNT 个配置 ==="
  exit 0
fi

# 若仅检测, 直接比较配置 (跳过环境操作)
if [ "$CHECK_ONLY" -eq 1 ]; then
  NACOS_URL="http://localhost:8848"
  echo "=== Nacos 配置漂移检测 ==="
  CONFIG_DIR="$SCRIPT_DIR/config"
  DRIFT_COUNT=0
  for file in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.json; do
    [ -e "$file" ] || continue
    nacos_config_diff "$file" || DRIFT_COUNT=$((DRIFT_COUNT+1))
  done
  if [ "$DRIFT_COUNT" -eq 0 ]; then
    echo "✓ 所有配置无漂移"
    exit 0
  else
    echo "✗ 存在 $DRIFT_COUNT 个漂移配置, 可运行 ./up.sh --sync 同步"
    exit 2
  fi
fi

echo "=== 1. 停止并清理旧环境 ==="
podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" down 2>/dev/null || true
echo "  已停止旧环境 (保留数据卷)"

# ⚠️ 两阶段启动的原因 (2026-10-02, CI E2E run 37050526759 的根因):
#   原来只有一次 `podman-compose up -d`, 把基础设施和 6 个微服务一起拉起,
#   而配置同步放在最后 (原步骤 10)。CI 每次 down 后 deploy/nacos/data 是
#   全新空目录, 于是微服务必然先于配置存在而启动, 报
#   "Could not resolve placeholder 'spring.datasource.url'" 并 Exited (1)。
#   本地不复现是因为那个 bind mount 保留了上次运行的配置残留。
#   修法: 基础设施先起 -> 等 Nacos 就绪 -> 同步配置 -> 再起微服务。
echo "=== 2a. 启动基础设施 (Nacos, PostgreSQL, Redis, RabbitMQ, ES, 监控) ==="
if [ "$CHECK_ONLY" -ne 1 ]; then
  # 只起基础设施: 不带 --profile full, 微服务因此不会被连带拉起。
  podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" up -d
fi

# 等待 Nacos 就绪
echo "=== 3. 等待 Nacos 就绪 ==="
NACOS_URL="http://localhost:8848"
for i in $(seq 1 30); do
    # Nacos 3.2+ 移除 v1 HTTP API, 使用 v3 open API 探测
    if curl -sf "$NACOS_URL/nacos/v3/client/cs/config?dataId=health&groupName=DEFAULT_GROUP&namespaceId=public" > /dev/null 2>&1; then
        echo "Nacos 已就绪"
        break
    fi
    echo "等待 Nacos... ($i/30)"
    sleep 2
done

# 同步配置到 Nacos —— 必须在微服务容器启动之前 (见步骤 1 处的说明)。
# 用 v3 admin API, --data-urlencode content@$file 保证原文多行/中文/转义正确。
echo "=== 3b. 同步配置到 Nacos (微服务启动前) ==="
CONFIG_DIR="$SCRIPT_DIR/config"
SYNC_COUNT=0
for file in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.json; do
    [ -e "$file" ] || continue
    nacos_config_upload "$file" && SYNC_COUNT=$((SYNC_COUNT+1))
done
echo "=== 同步完成: $SYNC_COUNT 个配置 ==="

# ⚠️ 上传返回成功 ≠ 微服务能读到（CI run 37070818024, 2026-10-03）。
#
#   那次 6 个微服务容器全部创建成功, gateway/auth/system/file 四个都 healthy,
#   只有 notification 与 search 卡住。notification 的真实报错是:
#     WARN  NacosConfigDataLoader - config[dataId=notification-service.yaml] is empty
#     ERROR Could not resolve placeholder 'spring.datasource.url'
#
#   即它在 **22:15:48** 去拉配置时 Nacos 还是空的, 而配置同步在其后才完成。
#   Spring Cloud Nacos 的 ConfigDataLoader 在启动期只拉一次:
#   读到空就当"该 dataId 不存在"直接继续, **不会重试**。
#   于是它带着一个残缺的环境永久启动失败 —— 反复重启也读不到,
#   因为启动上下文里那份空结果已被固化。
#
#   为什么之前没被发现:
#     · 本地 deploy/nacos/data 有残留配置, 配置早就在 Nacos 里, 读多少次都非空;
#     · CI 每次都是全新 Nacos 目录, 才必然撞上。
#   也就是说"配置同步早于微服务启动"这个顺序要求**还不够** ——
#   顺序对了, 但中间缺了"同步确已落地"的确认。
#
# 这里显式回读每个 dataId, 非空才算通过。宁可多等几秒,
# 也不要让服务带着残缺配置反复重启 —— 那种失败在日志里伪装成
# "业务代码有 bug", 排查成本高一个量级。
echo "=== 3c. 回读验证配置已落地 (非空) ==="
CONFIG_READY=0
MISSING_CFG=()
for attempt in $(seq 1 15); do
  MISSING_CFG=()
  for file in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.json; do
    [ -e "$file" ] || continue
    filename=$(basename "$file")
    content=$(curl -sf "$NACOS_URL/nacos/v3/client/cs/config?dataId=$filename&groupName=DEFAULT_GROUP&namespaceId=public" 2>/dev/null)
    [ -n "$content" ] || MISSING_CFG+=("$filename")
  done
  if [ "${#MISSING_CFG[@]}" -eq 0 ]; then
    CONFIG_READY=1
    break
  fi
  echo "  配置尚未可读 ($attempt/15), 缺: ${MISSING_CFG[*]}"
  sleep 2
done
if [ "$CONFIG_READY" -eq 1 ]; then
  echo "  ✓ 全部配置可读, 微服务可以安全启动"
else
  # 这里必须 exit: 带着空配置启动服务, 失败会以"业务 bug"的面貌出现在
  # 服务日志里, 而不是以"配置没同步好"的面貌出现在这里。
  echo "❌ 配置在 Nacos 中不可读, 放弃启动微服务: ${MISSING_CFG[*]}" >&2
  echo "   否则服务会报 Could not resolve placeholder, 误导为业务缺陷。" >&2
  exit 1
fi

# 配置已在 Nacos 里, 此时才允许拉起微服务。
if [ "$FULL" -eq 1 ] && [ "$CHECK_ONLY" -ne 1 ]; then
  echo "=== 2b. 启动微服务 (配置已就绪) ==="
  mapfile -t UP_ARGS < <(compose_up_args)
  podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" \
    ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"} "${UP_ARGS[@]}"

  # ⚠️ podman-compose 返回 0 **不等于**微服务起来了（2026-10-03, CI run 37064502008）。
  #   那次 up.sh 报 PASS、verify-e2e.sh 也据此判 PASS, 而 6 个微服务容器
  #   全部 absent —— 压根没被创建。compose 1.0.6 在 profile 过滤等情形下
  #   会"安静地什么都不做"却仍然 exit 0。
  #
  #   不加断言的后果: 这是一条**假门禁** —— 上游报成功、下游才发现没起来,
  #   而两者之间的日志已被重定向丢弃, 排障只能靠猜。
  #   故这里必须验证"容器确实存在", 而不能只信退出码。
  #
  # 只判存在不判 healthy: 健康是另一回事, 由后续就绪等待与 E2E 门禁负责。
  # 也不打印 podman-compose 的输出(刷屏且无信息量), 有缺失才报。
  sleep 1
  MISSING_SVCS=()
  for svc in "${FULL_SERVICES[@]}"; do
    podman container exists "cloud-$svc" 2>/dev/null || MISSING_SVCS+=("$svc")
  done
  if [ "${#MISSING_SVCS[@]}" -gt 0 ]; then
    echo "❌ 微服务容器未创建: ${MISSING_SVCS[*]}" >&2
    echo "   compose 已 exit 0 但这些容器不存在 ⇒ 启动参数被静默忽略。" >&2
    echo "   排查: FULL=$FULL, compose_supports_profile=$(compose_supports_profile && echo yes || echo no)" >&2
    echo "   实际 up 参数: ${UP_ARGS[*]}" >&2
    podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" \
      ps -a >&2 2>&1 || true
    exit 1
  fi
  echo "  ✓ ${#FULL_SERVICES[@]} 个微服务容器已创建"
fi

# 等待 PostgreSQL 就绪
echo "=== 4. 等待 PostgreSQL 就绪 ==="
for i in $(seq 1 15); do
    if pg_isready -h localhost -p 5432 -U postgres > /dev/null 2>&1; then
        echo "PostgreSQL 已就绪"
        break
    fi
    echo "等待 PostgreSQL... ($i/15)"
    sleep 2
done

# 等待 Redis 就绪
echo "=== 5. 等待 Redis 就绪 ==="
for i in $(seq 1 10); do
    if podman exec cloud-redis redis-cli ping > /dev/null 2>&1; then
        echo "Redis 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "❌ Redis 启动超时"; exit 1; }
    echo "等待 Redis... ($i/10)"
    sleep 2
done

# 等待 RabbitMQ 就绪
echo "=== 6. 等待 RabbitMQ 就绪 ==="
for i in $(seq 1 10); do
    if curl -sf http://localhost:15672 > /dev/null 2>&1; then
        echo "RabbitMQ 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "❌ RabbitMQ 启动超时"; exit 1; }
    echo "等待 RabbitMQ... ($i/10)"
    sleep 2
done

# 等待 Elasticsearch 就绪
echo "=== 7. 等待 Elasticsearch 就绪 ==="
# ⚠️ 预算从 15 次(30s) 提到 90 次(180s)。2026-10-02 实测: 干净环境下 ES 8.18.0
# 冷启动到 / 接受请求需 **48 秒** (mem_limit=1g, ES_JAVA_OPTS=-Xms512m -Xmx512m,
# oom=false)。原先 30s 预算必然超时 —— 这正是 E2E run 37023488729 / 37027922148
# 连续两次红灯的真因, 而失败消息又被 'compose 启动失败' 的措辞掩盖成另一个问题。
# 180s 是首启预算; 之后重启因数据卷已存在会快得多。
for i in $(seq 1 90); do
    if curl -sf http://localhost:9200 > /dev/null 2>&1; then
        echo "Elasticsearch 已就绪 (等待 $((i*2))s)"
        break
    fi
    # ES 首启阶段会反复 OOM 重启, 提前把真实原因打出来, 不要让它静默耗尽预算
    if [ $((i % 15)) -eq 0 ]; then
        ES_STATE=$(podman inspect -f '{{.State.Status}} oom={{.State.OOMKilled}} exit={{.State.ExitCode}}' cloud-elasticsearch 2>/dev/null || echo "容器不存在")
        echo "  等待 Elasticsearch... ($i/90, ${i}s) [$ES_STATE]"
    fi
    [ $i -eq 90 ] && { echo "❌ Elasticsearch 启动超时 (180s) [$ES_STATE]"; podman logs --tail 20 cloud-elasticsearch 2>&1 || true; exit 1; }
    sleep 2
done

# 7b. 调低 ES 磁盘水位阈值
#
# 2026-10-03: CI E2E run 37059177909 里 cloud-search 启动失败, 根因不在业务代码:
#   cloud-elasticsearch 日志:
#     flood stage disk watermark [95%] exceeded ...
#     free: 1.3gb[1.8%], all indices on this node will be marked read-only
#   search-service 侧:
#     DataAccessResourceFailureException: status: 400, [es/indices.exists]
#
# 关键不只在百分比, 更在 ES 的 max_headroom 语义:
# 实际触发线 = max(百分比, 总容量 - max_headroom)。默认
# flood_stage.max_headroom=100GB / high.max_headroom=150GB,
# 而 runner 磁盘总共才十几 GB —— 对 CI 环境而言这套官方默认值
# 相当于"几乎立刻判定磁盘已满"。那是为大规模集群设计的, 用在小磁盘
# 测试环境必然误判。
#
# ⚠️ 为什么用 API 而非环境变量或 elasticsearch.yml（两者都实测无效）:
#   ① 环境变量 `cluster.routing.*` —— 实测容器内确实存在该变量, 但
#      _cluster/settings 读回仍是 95%/100GB, 即 ES 完全不认;
#   ② `elasticsearch.yml` 挂载 —— 文件确实进了容器, 集群也能正常起,
#      但把 flood_stage 设为 0% 时 ES 报错说的是 "high disk watermark
#      [90%]" 而非 yml 里写的 98%, 证明 yml 根本没被读取;
#   ③ `command: elasticsearch -E ...` —— ES 8.18 的 server-cli 会丢弃 -E,
#      容器内 ps 可见 -E 没进入 java 进程;
#   ④ `command: -E ...` —— 直接启动失败:
#      "exec: -E: invalid option"。
#   唯一实测有效的是 PUT _cluster/settings: 返回 acknowledged:true,
#   读回值与写入完全一致。
#   用 persistent 而非 transient —— transient 语义重启即失。
echo "=== 7b. 调整 ES 磁盘水位阈值 (小磁盘环境) ==="
ES_SETTINGS_PAYLOAD='{"persistent":{
  "cluster.routing.allocation.disk.watermark.low":"97%",
  "cluster.routing.allocation.disk.watermark.high":"98%",
  "cluster.routing.allocation.disk.watermark.flood_stage":"99%",
  "cluster.routing.allocation.disk.watermark.low.max_headroom":"1GB",
  "cluster.routing.allocation.disk.watermark.high.max_headroom":"1GB",
  "cluster.routing.allocation.disk.watermark.flood_stage.max_headroom":"1GB",
  "cluster.routing.allocation.disk.watermark.flood_stage.frozen":"99%",
  "cluster.routing.allocation.disk.watermark.flood_stage.frozen.max_headroom":"1GB"
}}'
if ES_RESP=$(curl -sf -X PUT http://localhost:9200/_cluster/settings \
      -H 'Content-Type: application/json' -d "$ES_SETTINGS_PAYLOAD" 2>/dev/null) \
   && echo "$ES_RESP" | grep -q '"acknowledged":true'; then
  echo "  ✓ ES 磁盘水位阈值已下调 (flood_stage=99%, max_headroom=1GB)"
else
  # 不阻断: 这是兜底优化, 设不上会退回原默认值并再次触发 flood stage,
  # 所以必须显式告警 —— 静默失败等于把同一个坑留给下一个人。
  echo "  ⚠️  ES 磁盘水位阈值设置失败: ${ES_RESP:-无响应}" >&2
  echo "      小磁盘环境 (如 CI runner) 下 ES 可能再次进入 flood stage," >&2
  echo "      表现为 search-service 报 indices.exists status 400。" >&2
fi

# 等待 Prometheus 就绪
echo "=== 8. 等待 Prometheus 就绪 ==="
for i in $(seq 1 10); do
    if curl -sf http://localhost:9090/-/healthy > /dev/null 2>&1; then
        echo "Prometheus 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "⚠️ Prometheus 启动超时，请手动检查监控服务"; }
    echo "等待 Prometheus... ($i/10)"
    sleep 2
done

# 等待 Sentinel Dashboard 就绪
echo "=== 9. 等待 Sentinel Dashboard 就绪 ==="
for i in $(seq 1 10); do
    if curl -sf http://localhost:8858 > /dev/null 2>&1; then
        echo "Sentinel Dashboard 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "⚠️ Sentinel Dashboard 启动超时，请手动检查"; }
    echo "等待 Sentinel Dashboard... ($i/10)"
    sleep 2
done

echo ""
echo "=== 本地开发环境已就绪 ==="
echo "  Nacos:      控制台 http://localhost:8080 (API: $NACOS_URL)"
echo "  PostgreSQL: localhost:5432/cloud_infra"
echo "  Redis:      localhost:6379"
echo "  RabbitMQ:   localhost:15672 (管理界面)"
echo "  ES:         http://localhost:9200"
echo "  Prometheus: http://localhost:9090"
echo "  Alertmanager: http://localhost:9093"
echo "  Grafana:    http://localhost:3000 (admin/admin)"
echo "  Loki:       http://localhost:3100"
echo "  Jaeger:     http://localhost:16686"
echo "  Sentinel:   http://localhost:8858 (sentinel/sentinel)"
echo ""
echo "下一步: 在 IDE 中启动各微服务，或运行 mvn spring-boot:run"
