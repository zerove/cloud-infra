#!/usr/bin/env bash
# Cloud-Infra 端到端复现脚本
# 完整闭环: 构建镜像 -> 部署 -> 漂移检测 -> 健康 -> e2e 验收 -> 容器 env 一致性
#
# 前置条件:
#   - podman / podman-compose 已装
#   - mvn (或 mvnw) 可用
#   - 端口 8081/8082/8083/8084/8085/9000/9301 未占用
#
# 用法:
#   ./verify-e2e.sh                 # 完整流程
#   ./verify-e2e.sh --skip-build    # 跳过 mvn 构建 (用现有镜像)
#   ./verify-e2e.sh --self-test     # 门禁自检 (负向对照, 见 run_gate_selftest)

set -uo pipefail
# 脚本位于 repo 根, cd 到自身所在目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR" || exit 1

SKIP_BUILD=0
SELFTEST=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    --self-test)  SELFTEST=1 ;;
    *) echo "未知参数: $arg (支持 --skip-build --self-test)"; exit 1 ;;
  esac
done

PASS=0; FAIL=0
ok()   { printf "  \033[32mPASS\033[0m %s\n" "$1"; PASS=$((PASS+1)); }
fail() { printf "  \033[31mFAIL\033[0m %s\n" "$1"; FAIL=$((FAIL+1)); }

# 采集容器状态 + 日志尾部。两个失败分支共用, 避免"只在一处接了诊断"。
# 不只查 exited: 就绪等待超时通常是容器仍在 running 但服务没起来
# (Elasticsearch 首启要 30s+, 且 mem_limit=1g 在 runner 上可能直接被 OOM kill 后重启)。
dump_container_diagnostics() {
  echo "--- 未就绪容器的状态与日志 ---" >&2
  local c
  for c in $(podman ps -a --format '{{.Names}}' 2>/dev/null | head -12); do
    [ -n "$c" ] || continue
    echo "### $c  [$(podman inspect -f '{{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}}' "$c" 2>/dev/null)]" >&2
    podman logs --tail 12 "$c" >&2 2>&1 || true
  done
}

# 服务镜像: "<Dockerfile 仓库根相对路径>:<tag>"
# 与 deploy/compose.yaml 中每个服务的 image 名一一对应。
SERVICE_IMAGES=(
  "cloud-infra-gateway/Dockerfile:localhost/cloud-infra-gateway:latest"
  "cloud-infra-auth/Dockerfile:localhost/cloud-infra-auth:latest"
  "cloud-infra-modules/system-module/Dockerfile:localhost/cloud-infra-system:latest"
  "cloud-infra-modules/file-service/Dockerfile:localhost/cloud-infra-file:latest"
  "cloud-infra-modules/notification-service/Dockerfile:localhost/cloud-infra-notification:latest"
  "cloud-infra-modules/search-service/Dockerfile:localhost/cloud-infra-search:latest"
)
MICROSERVICES=(gateway auth system file notification search)

# ---------------------------------------------------------------------------
# 镜像构建
#
# 构建上下文必须是**仓库根**, 不是服务目录: 各服务 Dockerfile 里的 COPY
# 都是仓库根相对路径 (如 `COPY cloud-infra-gateway/target/*.jar app.jar`),
# deploy/compose.yaml 里对应的也是 `context: ..`。
#
# 2026-10-02 事故: 本脚本当时传的是服务目录 (podman build ... cloud-infra-gateway),
# 6 个镜像**全部**构建失败, 报错长这样:
#   Error: building at STEP "COPY cloud-infra-modules/search-service/target/*-exec.jar app.jar":
#   copier: stat: ["/cloud-infra-modules/search-service/target/*-exec.jar"]: no such file or directory
# 而失败被 `| tail -3` 吞掉后, 脚本仍打印 "PASS 6 个镜像构建完成" ——
# 门禁假通过, 与本项目已记录的 6 例同类缺陷同源。
# 因此这里逐个服务判定并返回真实退出码。
# ---------------------------------------------------------------------------
build_images() {
  local entry dockerfile tag failed=()
  for entry in "${SERVICE_IMAGES[@]}"; do
    dockerfile="${entry%%:*}"
    tag="${entry##*:}"
    if podman build -f "$SCRIPT_DIR/$dockerfile" -t "$tag" "$SCRIPT_DIR"; then
      printf "    ok   %s\n" "$tag"
    else
      printf "    FAIL %s (dockerfile: %s)\n" "$tag" "$dockerfile" >&2
      failed+=("$tag")
    fi
  done
  if [ "${#failed[@]}" -ne 0 ]; then
    echo "构建失败: ${failed[*]}" >&2
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 门禁自检 (负向对照)
#
# AGENTS.md 第四课: "我加了守护"不等于"守护有效", 差的那一步恰好就是负向对照。
# 本函数用一个"build 一律失败"的假 podman 顶替真 podman, 断言 build_images
# **真的**返回非零 —— 即错误不会被吞掉。
#
# 修复前的脚本在这里必然全绿 (它压根不判定 build 的退出码), 这正是本次缺陷的形态。
# ---------------------------------------------------------------------------
run_gate_selftest() {
  local tmp rc
  tmp=$(mktemp -d)
  cat > "$tmp/podman" <<'STUB'
#!/usr/bin/env bash
# 假 podman: build 模拟 2026-10-02 的上下文错误; 其余子命令静默成功
if [ "${1:-}" = "build" ]; then
  echo "Error: building at STEP \"COPY .../target/*-exec.jar app.jar\": copier: stat: no such file or directory" >&2
  exit 125
fi
exit 0
STUB
  chmod +x "$tmp/podman"
  PATH="$tmp:$PATH" build_images >/dev/null 2>&1
  rc=$?
  rm -rf "$tmp"

  if [ "$rc" -eq 0 ]; then
    echo "  FAIL 镜像构建失败时 build_images 仍返回 0 —— 错误被吞掉, 门禁形同虚设" >&2
    return 1
  fi
  echo "  PASS 镜像构建失败被正确识别 (build_images 返回 $rc)"
  return 0
}

if [ "$SELFTEST" -eq 1 ]; then
  echo "=== E2E 门禁自检 (负向对照) ==="
  run_gate_selftest
  exit $?
fi

echo "=========================================="
echo "  Cloud-Infra E2E 复现 ($(git rev-parse --short HEAD))"
echo "=========================================="
echo

# 0. 准备 .env
echo "[0/7] 准备 .env ..."
if [ ! -f .env ]; then
  if [ -f .env.example ]; then
    cp .env.example .env
    sed -i "s|^AUTH_JWT_SECRET=.*|AUTH_JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 48)|" .env
    sed -i "s|^INTERNAL_CALL_SECRET=.*|INTERNAL_CALL_SECRET=$(head -c 48 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 48)|" .env
    sed -i "s|^AUTH_CAPTCHA_FIXED_CODE=.*|AUTH_CAPTCHA_FIXED_CODE=1234|" .env
    ok ".env 已生成 (密钥随机化)"
  else
    fail ".env 缺失且 .env.example 不存在"
    exit 1
  fi
else
  ok ".env 已存在"
fi
echo

# 1. 构建
if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[1/7] mvn clean install -DskipTests ..."
  if mvn clean install -DskipTests -q 2>&1 | tail -5; then
    ok "全模块编译完成"
  else
    fail "mvn 编译失败"
    exit 1
  fi
else
  ok "跳过 mvn 构建 (--skip-build)"
fi
echo

# 2. 服务镜像
echo "[2/7] 构建 ${#SERVICE_IMAGES[@]} 个服务镜像 (上下文 = 仓库根) ..."
unset JAVA_OPTS
set -a; source <(grep -v '^JAVA_OPTS=' .env); set +a
if [ "$SKIP_BUILD" -eq 0 ]; then
  if build_images; then
    ok "${#SERVICE_IMAGES[@]} 个镜像构建完成"
  else
    fail "镜像构建失败"
    exit 1
  fi
else
  ok "跳过镜像构建 (--skip-build)"
fi
echo

# 3. 启动基础设施 + 微服务
#
# 走项目统一入口 deploy/up.sh --full: compose 调用、profile 展开、Nacos 配置同步
# 都收敛在那里。本脚本不再另起一套 podman-compose 调用 —— 2026-10-02 之前这里是
# `podman-compose --profile full --env-file ../.env up -d 2>&1 | tail -3` 后无条件
# 打印 PASS, 而 CI 上该命令实际报
#   podman-compose: error: argument command: invalid choice: 'full'
# 同样是门禁假通过。
echo "[3/7] 启动基础设施 + 微服务 (deploy/up.sh --full) ..."
UP_LOG="$SCRIPT_DIR/.e2e-up.log"
if ./deploy/up.sh --full > "$UP_LOG" 2>&1; then
  ok "compose 启动 + Nacos 配置同步完成"
else
  # ⚠️ 措辞不要写成 "compose 启动失败": up.sh --full 后面还有 9 步等待
  # (Nacos / PostgreSQL / Redis / RabbitMQ / ES / Prometheus / Sentinel / 配置同步),
  # 失败点可能在任意一步。2026-10-02 的两次 E2E 红灯都源于此 —— 当时误以为是
  # compose 启动失败, 实际是第 7 步 Elasticsearch 15 次探测全部超时。
  # 失败消息位于日志**第一行**, 而 tail -N 取的是**末尾** N 行, 于是"末尾 25 行"
  # 里只剩后续步骤的等待输出, 真正的错误反被截掉 —— 这才是当时"看起来日志为空"的成因。
  fail "deploy/up.sh --full 未完成(可能在 compose 启动, 也可能在后续就绪等待), 末尾 40 行:"
  # 排障前提: 即使日志为空也要说清楚, 否则"末尾 N 行"后面接空白会被误读成
  # "脚本没输出任何东西"。
  if [ ! -s "$UP_LOG" ]; then
    echo "  (日志文件 $UP_LOG 不存在或为空 —— 脚本在产生任何输出前就失败了)" >&2
  fi
  echo "--- 日志首 5 行(失败消息通常在这里, 不在末尾) ---" >&2
  head -5 "$UP_LOG" >&2 || true
  echo "--- 日志末尾 40 行 ---" >&2
  tail -40 "$UP_LOG" >&2 || true
  echo "--- compose ps ---" >&2
  podman-compose -f "$SCRIPT_DIR/deploy/compose.yaml" --env-file "$SCRIPT_DIR/.env" ps >&2 2>&1 || true
  dump_container_diagnostics
  exit 1
fi
echo

# 4. 漂移检测 (必须在上一步之后: Nacos 没起来时所有配置都会被判"漂移")
echo "[4/7] Nacos 配置漂移检测 ..."
if ./deploy/up.sh --check 2>&1 | tail -1 | grep -q "无漂移"; then
  ok "Nacos 配置 0 漂移"
else
  fail "Nacos 配置存在漂移"
  ./deploy/up.sh --check 2>&1 | tail -3 >&2
fi
echo

# 5. 等健康
#
# 逐个按容器名判定, 而不是只数 `podman ps` 里 healthy 的总数:
# 后者会让"基础设施里有 6 个健康容器"冒充"6 个微服务都健康"。
echo "[5/7] 等待 ${#MICROSERVICES[@]} 个微服务 healthy ..."
DEADLINE=$(( $(date +%s) + 240 ))
MISSING=""
while :; do
  MISSING=""
  for svc in "${MICROSERVICES[@]}"; do
    state=$(podman inspect --format '{{.State.Health.Status}}' "cloud-$svc" 2>/dev/null || echo absent)
    [ "$state" = "healthy" ] || MISSING="$MISSING $svc=$state"
  done
  [ -z "$MISSING" ] && break
  [ "$(date +%s)" -ge "$DEADLINE" ] && break
  sleep 6
done
if [ -z "$MISSING" ]; then
  ok "${#MICROSERVICES[@]} 个微服务全部 healthy"
else
  fail "未 healthy:$MISSING"
  echo "  容器状态:" >&2
  podman ps -a --format '{{.Names}}\t{{.Status}}' 2>&1 | grep '^cloud-' >&2
  # 2026-10-02: 这个分支原先只打印状态, 不打印日志。
  # 结果 run 37033739185 上 4 个服务 Exited(1) 却拿不到任何异常,
  # 而采日志的代码只存在于 3/7 的失败分支 —— 即"诊断修好了却在下一层失效",
  # 与 issue 29 记录的"从未登记进 imports"同类: 机制在, 但没接到这条路径上。
  dump_container_diagnostics
  exit 1
fi
echo

# 6. e2e 验收
#
# 判据必须锚定 "FAIL=0": 原写法 `grep -q "PASS=6"` 会被
# "PASS=6 FAIL=2" 一并匹配上 —— 有失败也照样判过。
echo "[6/7] e2e-test.sh ..."
if bash e2e-test.sh 2>&1 | tee "$SCRIPT_DIR/.e2e-acceptance.log" | tail -1 | grep -qE "结果: PASS=6 FAIL=0"; then
  ok "e2e 6/6 PASS"
else
  fail "e2e 未全过, 末尾 10 行:"
  tail -10 "$SCRIPT_DIR/.e2e-acceptance.log" >&2
fi
echo

# 7. 容器 env 一致性
echo "[7/7] 容器 env 一致性 ..."
ALL_OK=1
for svc in "${MICROSERVICES[@]}"; do
  INT=$(podman exec "cloud-$svc" sh -c 'echo "${#INTERNAL_CALL_SECRET}"' 2>/dev/null)
  if [ "$INT" = "48" ]; then
    : # 静默 OK
  else
    fail "$svc INTERNAL_CALL_SECRET=$INT (期望 48)"
    ALL_OK=0
  fi
done
[ "$ALL_OK" -eq 1 ] && ok "${#MICROSERVICES[@]} 容器 INTERNAL_CALL_SECRET=48"
echo

echo "=========================================="
printf "  结果: %d PASS / %d FAIL\n" "$PASS" "$FAIL"
echo "=========================================="
exit $FAIL
