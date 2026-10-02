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

# ES 专项诊断。抽成函数是因为 search 有**两条**失败路径 (unhealthy / absent),
# 而 bash 的 case 只能走第一个命中分支, 内联写会漏掉其中一条 (实测过 fallthrough 不合法)。
#
# 用途: run 37070818024 里 search 的 ES 400 至今无法从客户端侧定位。栈是:
#     SimpleElasticsearchRepository.<init>
#       -> IndicesTemplate.exists -> HEAD /<index>
#       -> TransportException: status 400, "Expecting a response body, but none was sent"
# 客户端只说"400 且无 body", 不说**为什么**; 本地同一条 HEAD /oper_log 返回 404
# (索引不存在, 正常), 同版本 ES 8.18, 配置无差异 —— 本地复现不了。
#
# 在拿到 ES 服务端证据之前不猜。故把两边的原始响应都打出来:
# 复刻 search 实际发出的 HEAD 请求 + ES 侧 read_only/flood_stage/磁盘水位。
es_diagnostics() {
  echo >&2
  echo "--- ES 专项诊断 (search 相关失败) ---" >&2
  local es_port es
  es_port=$(podman port cloud-elasticsearch 9200/tcp 2>/dev/null | head -1 | sed 's/.*://')
  if [ -n "$es_port" ]; then
    es="http://127.0.0.1:${es_port}"
    # ⚠️ 索引名必须与 @Document(indexName=...) 一致, 否则探的是一个不存在的索引。
    #   实测只有两个: OperLogDocument -> sys_oper_log, DeviceTelemetryDocument -> iot_telemetry。
    #   我第一版写的是 oper_log/search_log, 第二版按**类名**猜成 device_telemetry ——
    #   两个都不存在, 探它们恒返回 404, 会得出"ES 正常"的错误结论。
    #   守护: test_es_diagnostics_index_names.py 直接比对 Java 侧, 防再次漂移。
    for idx in sys_oper_log iot_telemetry; do
      printf 'HEAD /%s -> %s\n' "$idx" \
        "$(curl -s -o /dev/null -w '%{http_code}' -I "$es/$idx" --max-time 10 2>&1)" >&2
    done
    echo "GET _cluster/health:" >&2
    curl -s "$es/_cluster/health" --max-time 10 2>&1 | head -20 >&2
    echo "GET _cluster/settings?flat_settings=true (水位/只读):" >&2
    curl -s "$es/_cluster/settings?flat_settings=true" --max-time 10 2>&1 | head -30 >&2
    echo "GET _nodes/fs (磁盘水位依据):" >&2
    curl -s "$es/_nodes/fs" --max-time 10 2>&1 | head -25 >&2
  else
    echo "  (未取到 ES 端口, 跳过 API 探测)" >&2
  fi
  echo "--- cloud-elasticsearch 服务端日志尾部 ---" >&2
  podman logs --tail 120 cloud-elasticsearch 2>&1 \
    | grep -iE "flood|read.only|reject|indices.exists|status.*400|illegal_argument|exception" | head -30 >&2 || true
  echo "--- End ES 专项诊断 ---" >&2
}

# 采集容器状态 + 日志尾部。两个失败分支共用, 避免"只在一处接了诊断"。
# 不只查 exited: 就绪等待超时通常是容器仍在 running 但服务没起来
# (Elasticsearch 首启要 30s+, 且 mem_limit=1g 在 runner 上可能直接被 OOM kill 后重启)。
dump_container_diagnostics() {
  echo "--- 未就绪容器的状态与日志 ---" >&2
  local c state
  # ⚠️ 不得用 `head -N` 截断容器列表（2026-10-02 修复）。
  # compose 声明了 23 个容器，`podman ps -a` 的返回顺序按创建时间，
  # 实测 cloud-notification 排在**第 14 位** —— 而旧代码只取前 12 个,
  # 恰好把唯一失败的那个服务**整个跳过**，诊断输出里根本没有它的日志。
  # 这是比"只打tail"更隐蔽的假门禁: 看上去有诊断, 实际漏掉了目标。
  # 改为遍历全部容器, 不做数量截断。
  for c in $(podman ps -a --format '{{.Names}}' 2>/dev/null); do
    [ -n "$c" ] || continue
    state=$(podman inspect -f '{{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}}' "$c" 2>/dev/null)
    echo "### $c  [$state]" >&2
    # ⚠️ 不只给已退出的容器打详细日志（2026-10-03 修正, 依据 run 37057477434）。
    # 那次修完配置竞态后, 四个服务从 Exited(1) 变成 Up + unhealthy —— 竞态确实
    # 消失了, 但 5/7 仍超时失败。而本诊断只在 exit=1 时展开日志, 于是
    # "Up 但未就绪" 这种最需要看日志的情况反而一个字都没输出。
    # 现在对 running / unhealthy 也展开, 否则会误以为"没错误信息 = 没问题"。
    if ! echo "$state" | grep -q "exit=0"; then
      # 2026-10-02: 启动失败的真正原因在日志**首部**，不在尾部。
      # Spring 的 NoUniqueBeanDefinitionException / BeanCreationException 把 Bean 名与
      # "expected single matching bean but found N" 放在异常首段，尾部只剩
      # "Action:/Consider marking ... @Primary" 这段对任何多 Bean 场景都会打印的通用建议。
      # 此前只取tail 12，导致 run 37036943534 上 cloud-notification 的
      # [exited exit=1] 一个 Bean 名都没拿到 —— 而这恰恰是唯一的失败服务。
      # 故对已退出的容器同时打印首部（异常通常在开头 60 行内）。
      # ⚠️ 第三处假门禁（2026-10-03，依据 E2E run 37046848357 实测）:
      # 首 60 行里**100% 是 SkyWalking agent 的插件加载噪音**,
      # 实测 60/60 行均为 agent 类, 应用自己的日志一行都没有。
      # 原因是 SkyWalking agent 在 main 之前完成 300+ 个插件的类加载。
      # 噪音来源已用真实日志穷尽统计(按 "main <Class> :" 计数):
      #   AgentClassLoader 157 / PluginResourcesResolver 146 /
      #   SnifferConfigInitializer 3 / SkyWalkingAgent 1 / AgentPackagePath 1
      # 即恰好 5 个类, 下面按类名白名单反向排除, 而非逐条猜测。
      local NOISE='AgentClassLoader|PluginResourcesResolver|SnifferConfigInitializer|SkyWalkingAgent|AgentPackagePath'
      # 注意: 不能简单地“滤掉所有 at 开头的行” —— Spring 的失败堆栈**主体就是 at 行**,
      # 滤掉就等于把根因删了。真正的做法是把噪音源滤掉后, **优先保留含关键字的行**:
      #   ERROR/Exception/Caused by/FATAL/APPLICATION FAILED 等, 最多补 40 行上下文。
      # 这样无论噪音占多少, 根因一定在窗口内。
      # ⚠️ 不用 `| head -N` 收尾（2026-10-03 实测修正）。
      # head 读够 40 行就关闭管道, 上游 grep 随即收到 SIGPIPE, 打出
      # "grep: write error: Broken pipe"。在 run 37050526759 的日志里实测到,
      # 且它紧跟在关键错误行之后, 容易被误读成根因的一部分。
      # 改用 sed -n '1,40p': sed 会读完整个输入, 不产生 SIGPIPE。
      echo "--- $c 关键错误行（ERROR/Exception/Caused by/FAILED）---" >&2
      podman logs --tail -1 "$c" 2>&1 \
        | grep -E 'ERROR|Exception|Caused by|FATAL|APPLICATION FAILED|OutOfMemory|exited|Bind|placeholder' \
        | grep -vE 'RunnableWithExceptionProtection|skywalking-api\.log' \
        | sed -n '1,40p' >&2 || true
      # 首部窗口。⚠️ 过滤名单必须区分"agent 噪音"与"可能的真实异常":
      # FileNotFoundException 上一版也在名单里, 但它完全可能是真根因
      # (例如挂载卷缺失), 滤掉就等于把证据销毁 —— 只滤掉确定属于
      # SkyWalking agent 自身噪声的那几行, 且不滤异常类型行本身。
      echo "--- $c 日志首 25 行有效内容（已滤 SkyWalking agent 加载噪音）---" >&2
      podman logs --tail -1 "$c" 2>&1 \
        | grep -vE "$NOISE|skywalking-api\.log|RunnableWithExceptionProtection|sun\.misc\.Unsafe|Picked up JAVA_TOOL" \
        | grep -vE '^\s+at |^\s*\.\.\. [0-9]+ more' \
        | sed -n '1,25p' >&2 || true
      # 尾部有效内容: Spring 的失败堆栈在末尾, 但末尾同样常被 agent 噪音污染
      echo "--- $c 日志尾 25 行有效内容 ---" >&2
      podman logs --tail -1 "$c" 2>&1 \
        | grep -vE "$NOISE|skywalking-api\.log|RunnableWithExceptionProtection|sun\.misc\.Unsafe" \
        | tail -25 >&2 || true
      # 启动进度信号: 只看错误行会漏掉"没报错但也没起来"这一类。
      # run 37057477434 就是这种 —— 无任何异常, 但 5/7 仍超时。
      # 有了这几行才能区分"卡在某一步"与"已经起来只是健康检查没绿"。
      echo "--- $c 启动进度（Tomcat/Started/readiness/健康）---" >&2
      podman logs --tail -1 "$c" 2>&1 \
        | grep -E 'Tomcat (initialized|started)|Started [A-Za-z]+ in |readinessState|AvailabilityChangeEvent|LiveReload' \
        | tail -8 >&2 || echo "    (无启动完成信号 —— 服务尚未走到 Tomcat 启动)" >&2
    fi
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
    # ⚠️ 拆 tag 必须按**第一个**冒号切分, 而不是最后一个（2026-10-03）。
    #   SERVICE_IMAGES 的元素形如
    #     cloud-infra-gateway/Dockerfile:localhost/cloud-infra-gateway:latest
    #   即 "<Dockerfile 路径> : <镜像引用>"。而镜像引用自身**也含冒号**（":latest"），
    #   元素里因此有**两个**冒号，于是:
    #     ${entry%%:*}  → dockerfile ✅（左侧无冒号，最短匹配到第一个）
    #     ${entry##*:}  → tag       ❌ ## 是"删最长的 *: 前缀"，一路吃到**最后一个**冒号，
    #                               即 dockerfile 与镜像路径全被丢弃，只剩 "latest"
    #   实测（run 37068311894）: 6 个镜像全被打成 localhost/latest:latest,
    #   彼此互相覆盖；随后 compose 找 localhost/cloud-infra-gateway:latest 报
    #     Error: initializing source docker://localhost/cloud-infra-gateway:latest
    #     error code: 125
    #   而 build_images 打印的是 "ok latest" —— 6 个 tag 全是 latest,
    #   这一点当时就该被当成异常，只是没人看那一行。
    #
    # 正确拆法: 与 %% 配对的 # 也是最短匹配, 取**第一个**冒号之后的全部,
    # 完整保留 "localhost/<name>:latest"。
    dockerfile="${entry%%:*}"
    tag="${entry#*:}"
    # 双保险: 拆解失效的**特征**就是 tag 恰好等于 "latest"（镜像路径被整段吃掉）。
    # 显式判它, 好过让 6 个镜像再次静默塌成同一个 tag —— 那正是本次缺陷的形态。
    if [ "$tag" = "latest" ]; then
      echo "    拆解异常: entry='$entry' → tag='$tag'（SERVICE_IMAGES 格式已变）" >&2
      return 1
    fi
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

# 2b. 释放构建产物占用的磁盘
#
# 2026-10-03: run 37059177909 里cloud-search 启动失败, 真实根因不在业务代码:
#   cloud-elasticsearch 日志: flood stage disk watermark [95%] exceeded ...
#   free: 1.3gb[1.8%], all indices on this node will be marked read-only
# ES 进入 flood stage 后拒绝一切写操作, search-service 建索引时被拒:
#   DataAccessResourceFailureException: ... status: 400, [es/indices.exists]
#
# 磁盘为什么不够: mvn clean install 产出约 900MB target/, 6 个镜像又把
# 同一批 jar COPY 进各自镜像层 —— 同一份数据存了两遍, 而脚本全程不清除。
# 本机对照: 188 个镜像占 23.41GB。
#
# 镜像构建完成后 target/ 已无用途 (fat jar 已在镜像内), 此时删掉最安全。
# 用 --fail 之外的显式判断: 删除失败不应中断 E2E, 但要如实报告。
echo "[2b/7] 清理 target/ (fat jar 已入镜像, 释放磁盘) ..."
TARGET_FREED=$(du -sm --exclude=node_modules */target cloud-infra-*/*/target 2>/dev/null \
  | awk '{s+=$1} END {print s+0}')
rm -rf -- */target cloud-infra-*/*/target 2>/dev/null || true
LEFT=$(find . -maxdepth 3 -type d -name target -not -path './.git/*' 2>/dev/null | wc -l)
if [ "$LEFT" -eq 0 ]; then
  ok "已清理 target/, 释放约 ${TARGET_FREED}MB"
else
  echo "  ⚠️  仍有 $LEFT 个 target/ 残留 (未阻断, 但磁盘可能仍紧张)" >&2
fi

# 顺带清掉构建中间层: dangling 层在新镜像构建后必然产生,
# 它们不占 tag 但实打实占盘。--force 才会连带清理未 tag 的层。
podman image prune -f >/dev/null 2>&1 || true
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
  # ⚠️ 成功路径同样要留档（2026-10-03）。up.sh 返回 0 **不等于**微服务起来了:
  # CI run 37064502008 里 up.sh 报 PASS, 而 6 个微服务容器全部 absent
  # (不是 Exited, 是压根没创建) —— 失败点在步骤 [5/7], 于是成功路径下
  # $UP_LOG 被后续流程覆盖, artifact 里查不到 podman-compose 的真实输出,
  # 只能靠猜。保留一份不花钱, 省掉的是一整轮 CI。
  cp "$UP_LOG" "$SCRIPT_DIR/.e2e-up-ok.log"
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
# 预算从 240s 提到 600s（2026-10-03，依据 run 37057477434 的真实观测）。
#
# 证据不是"感觉慢", 而是那次 run 的容器状态:
#   cloud-auth Up 5 minutes (unhealthy) / cloud-file Up 4 minutes (unhealthy)
#   cloud-notification Up 4 minutes (unhealthy) / cloud-system Up 4 minutes (starting)
# 即 240s 预算耗尽时, 服务仍在正常启动途中, 且**没有任何异常日志**
# （该 run 全文 "Could not resolve placeholder" 命中数为 0）。
# 本地同配置实测启动跨度约 968s（但本机负载 9.12/12 核, 容器内存一度到
# 461.6/469.8MB, 不能直接外推到 runner）, 故取 600s 作为
# “比实测短、比旧预算宽”的折中, 而不是直接照抄 968。
#
# ⚠️ 同时修正一个诊断误导: 预算耗尽时服务可能只是在启动, 不是挂了。
#    所以下面失败分支会打印启动进度信号（Tomcat/Started/readiness）,
#    否则“没错误信息”会被误读成“没问题”。
DEADLINE=$(( $(date +%s) + 600 ))
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
  # ⚠️ "absent" 与 "unhealthy" 是两类完全不同的故障, 处理方式也不同（2026-10-03）。
  #    absent = 容器压根没被创建 ⇒ 问题在 **podman-compose 启动这一步**,
  #    而不在服务本身。此时 dump_container_diagnostics 只能打印基础设施的日志
  #    (它遍历存在的容器), 对"没创建的容器"一个字都吐不出来 ——
  #    run 37064502008 就是这样: 6 个微服务全 absent, 日志里零线索。
  #    故这里显式区分, 并把 compose 自己的输出摆出来。
  case "$MISSING" in
    *absent*)
      echo >&2
      echo "  ⚠️ 有容器状态为 absent —— 它们从未被创建, 故障在 podman-compose 启动," >&2
      echo "     而不在服务内部。compose 自己的输出:" >&2
      echo "--- podman-compose ps ---" >&2
      podman-compose -f "$SCRIPT_DIR/deploy/compose.yaml" --env-file "$SCRIPT_DIR/.env" ps -a >&2 2>&1 || true
      echo "--- up.sh 完整日志（成功路径留档）---" >&2
      if [ -s "$SCRIPT_DIR/.e2e-up-ok.log" ]; then
        # 只取 2b 段: 前面是基础设施 up 的刷屏, 与本故障无关。
        sed -n '/2b\. 启动微服务/,/=== 4\./p' "$SCRIPT_DIR/.e2e-up-ok.log" >&2 || true
        echo "--- up.sh 末尾 25 行 ---" >&2
        tail -25 "$SCRIPT_DIR/.e2e-up-ok.log" >&2
      else
        echo "  (无留档 —— 该 run 早于本次修复, up.sh 日志未被保留)" >&2
      fi
      # ⚠️ case 只走**第一个**命中分支, 且 bash 的 case 不支持跳 pattern 的
      #    fallthrough (删掉 `;;` 会直接语法报错, 已实测)。因此 search=absent
      #    会先命中上面那个 absent 分支, ES 诊断被跳过。
      #    两条路径都可能源于 ES 侧问题, 故 absent 分支内也直接调 es_diagnostics。
      case "$MISSING" in *search*) es_diagnostics ;; esac
      ;;
    *search*)
      es_diagnostics
  esac
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
