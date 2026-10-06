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

    # --- 容器内对照（2026-10-03, 依据 run 37079433594）----------------------
    # 上面全部是**宿主机**发起的请求。CI 实测:
    #     宿主机 curl HEAD /sys_oper_log  -> 404 （正常, 索引不存在）
    #     search 容器内 Java 客户端      -> 400 "Expecting a response body, but none was sent"
    # 同一个索引、同一个 ES, 两侧结果不同 => 差异在**客户端侧**, 不在 ES 服务端。
    # 只有同时拿到容器内的响应, 才能把这两件事区分开; 只看宿主机探针会得出
    # "ES 正常, 那问题在哪?" 的死局 —— 这正是本次差点误判的地方。
    # 故此处必须补一条容器内探针, 否则本诊断只有一半证据。
    if podman ps --format '{{.Names}}' 2>/dev/null | grep -qx cloud-search; then
      echo "--- 容器内对照: 从 cloud-search 内部访问同一索引 ---" >&2
      # ⚠️ search 镜像是 jre-alpine, **不保证有 curl**。
      #   没有就退到 wget; 都没有必须明确说"探针不可用",
      #   不能静默跳过 —— 否则本诊断又变成半截证据, 而这正是它存在的理由。
      podman exec cloud-search sh -c '
        if command -v curl >/dev/null 2>&1; then
          fetch_code() { curl -s -o /dev/null -w "%{http_code}" -I "$1" --max-time 10 2>&1; }
          fetch_raw() { curl -s -D - -o /dev/null -I "$1" --max-time 10 2>&1 | head -20; }
          fetch_get() { curl -s -w "\n  http_code=%{http_code}\n" "$1" --max-time 10 2>&1 | head -15; }
        elif command -v wget >/dev/null 2>&1; then
          fetch_code() { wget -q -S --spider --timeout=10 "$1" 2>&1 | grep -oE "HTTP/[0-9.]+ [0-9]+" | tail -1 | awk "{print \$2}"; }
          fetch_raw() { wget -q -S --spider --timeout=10 "$1" 2>&1 | head -20; }
          fetch_get() { wget -q -O - --timeout=10 "$1" 2>&1 | head -15; }
        else
          echo "  [in-container] curl 与 wget 均不存在, 探针不可用 —— 该容器缺少 HTTP 工具"
          exit 0
        fi
        for idx in sys_oper_log iot_telemetry; do
          echo "  [in-container] HEAD /$idx -> $(fetch_code http://cloud-elasticsearch:9200/$idx)"
        done
        echo "  [in-container] 完整响应头 (HEAD /sys_oper_log):"
        fetch_raw http://cloud-elasticsearch:9200/sys_oper_log
        echo "  [in-container] GET / 根路径:"
        fetch_get http://cloud-elasticsearch:9200/
      ' 2>&1 | sed 's/^/  /' >&2 || echo "  (容器内探针执行失败)" >&2

      echo "--- search 容器环境 (ELASTICSEARCH_URIS / 代理变量) ---" >&2
      podman exec cloud-search sh -c \
        'env | grep -iE "elastic|proxy|http_proxy|no_proxy" || echo "  (无相关环境变量)"' \
        2>&1 | sed 's/^/  /' >&2 || true
    else
      echo "--- cloud-search 未运行, 无法做容器内对照 ---" >&2
    fi
  else
    echo "  (未取到 ES 端口, 跳过 API 探测)" >&2
  fi
  echo "--- cloud-elasticsearch 服务端日志尾部 ---" >&2
  podman logs --tail 120 cloud-elasticsearch 2>&1 \
    | grep -iE "flood|read.only|reject|indices.exists|status.*400|illegal_argument|exception" | head -30 >&2 || true

  # --- 生产同路径 Java 客户端探针（2026-10-03 本地实测, 见 deploy/tools/EsClientProbe.java）---
  # ⚠️ 上面**所有**探针（含"容器内对照"）都是 curl/wget, 而 curl **不发送**
  #   Accept/Content-Type: application/vnd.elasticsearch+json; compatible-with=9
  # 所以 curl 探针**测不出**本项目真正踩到的故障。同一台 ES 上的对照结果:
  #   curl HEAD /sys_oper_log                        -> 404  (正常)
  #   Java 客户端 indices.exists -> ES 8.18.0        -> 400 media_type_header_exception
  #   Java 客户端 indices.exists -> ES 9.0.3         -> false (正常)
  # 只看 curl 会得出"ES 没问题"的假绿灯 —— 这正是本次差点误判的地方,
  # 也是容器内对照补了仍不够的原因: 它换了发起方, 没用客户端。
  echo "--- 生产同路径 Java 客户端探针 (真实 compatible-with 头) ---" >&2
  run_es_client_probe "${es_port:-9200}" || true
  echo "--- End ES 专项诊断 ---" >&2
}

# 用 search-service 的真实依赖跑 EsClientProbe, 走与 search-service 启动时
# createIndexAndMappingIfNeeded -> IndicesTemplate.exists 完全相同的一组类。
#
# 退出码语义: 函数本身恒返回 0, 探针结论打在输出里并单列退出码。
# 之所以不让它影响 E2E 判定: 本函数只在 search **已经失败**时被调用 (诊断用),
# 此时 E2E 无论成败都不该由探针二次决定; 探针的价值是给出根因, 不是充当门禁。
# 但**绝不能**因此省略退出码 —— 无差别 exit 0 会让读日志的人无法区分
# "探针通过" 与 "探针没跑成", 这类歧义本项目已吃过多次。
run_es_client_probe() {
  local es_port="$1"
  local probe_src="$SCRIPT_DIR/deploy/tools/EsClientProbe.java"

  # ⚠️ 模块目录名**不靠猜**。第一版硬写 cloud-infra-search-service, 实际是
  # search-service, 结果探针每次都走"找不到模块"分支 —— 而那分支返回 0,
  # 在 CI 上就表现为"诊断跑过了但没输出", 又是一个假绿灯。
  # 这里改为按内容定位: 哪个模块的 pom 里含 spring-data-elasticsearch, 就用哪个。
  local search_dir
  search_dir=$(for p in "$SCRIPT_DIR"/cloud-infra-modules/*/pom.xml; do
    [ -f "$p" ] || continue
    grep -qi 'spring-boot-starter-data-elasticsearch\|spring-data-elasticsearch' "$p" \
      && dirname "$p" && break
  done)
  if [ -z "$search_dir" ]; then
    echo "  [client-probe] 不可用: 在 cloud-infra-modules/ 下找不到依赖 elasticsearch 的模块" >&2
    return 0
  fi
  echo "  [client-probe] 客户端依赖来源: ${search_dir#"$SCRIPT_DIR"/}" >&2

  local work cp_file
  work="$(mktemp -d -t es-probe.XXXXXX)"
  cp_file="$work/cp.txt"
  if ! mvn -o -q -f "$search_dir/pom.xml" dependency:build-classpath \
        -Dmdep.outputFile="$cp_file" >/dev/null 2>&1; then
    echo "  [client-probe] 不可用: 无法解析 search-service 依赖 classpath (mvn 离线或模块缺失)" >&2
    rm -rf "$work"
    return 0
  fi
  # ⚠️ 不能写成 `if ! javac ... | sed ...; then` —— 管道的退出码取自最后一个命令(sed, 恒 0),
  #   javac 失败也会被当成成功, 探针随后以"类不存在"报出一堆无关异常。
  #   这正是本项目反复出现的假门禁形态, 自己刚写出来就得当场拆掉。
  javac -nowarn -cp "$(cat "$cp_file")" -d "$work" "$probe_src" 2>&1 \
    | sed 's/^/  [client-probe] /' >&2
  local jrc=${PIPESTATUS[0]}
  if [ "$jrc" -ne 0 ]; then
    echo "  [client-probe] 不可用: 探针编译失败 (退出码 $jrc, 依赖版本可能已变)" >&2
    rm -rf "$work"
    return 0
  fi

  # 参数是 host:port, **不带 scheme**。带 scheme 会被 ClientConfiguration 拼成
  # http://http//host:port, 报 "Name or service not known" —— 看起来像连不上 ES,
  # 实则是参数用错, 这个坑本机已踩过一次。
  # 同时清掉代理变量: 宿主机 curl 可能走代理, 而探针必须直连,
  # 否则报出来的是代理错误而非 ES 错误, 指向错误的方向。
  local out_file="$work/probe.out"
  timeout 120 env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
    -u no_proxy -u NO_PROXY \
    java -cp "$(cat "$cp_file"):$work" EsClientProbe "127.0.0.1:${es_port}" \
    > "$out_file" 2>&1
  local rc=$?
  sed 's/^/  /' "$out_file" >&2

  # 判定读的是**探针自己打的 RESULT 行**, 不是退出码。
  # 退出码无法区分"探针正常但发现 ES 有问题"与"探针自身崩了(JVM 起不来/classpath 缺件)",
  # 而这两种情况的处置完全不同 —— 前者说明 search 的根因, 后者说明这段诊断不可信。
  #
  # ⚠️ 顺序必须是"先看有没有结论, 再看是否超时"。
  # 第一版把 rc==124 放在最前面, 于是探针**已经打出 RESULT = OK**、
  # 只是关闭连接池时慢了几秒的情况, 会被改写成"未完成: 探针超时 —— 不等于通过",
  # 覆盖掉真实结论。超时只是"拿不到结论"的一种原因, 不是结论本身。
  if grep -q "RESULT = OK" "$out_file"; then
    echo "  [client-probe] 结论: 客户端与 ES 版本兼容 (RESULT = OK)$([ "$rc" -eq 124 ] \
      && echo ' [注: 探针在关闭连接后未及时退出, 已被 timeout 终止, 但结论已产出]')" >&2
  elif grep -q "RESULT = FAILED" "$out_file"; then
    echo "  [client-probe] 结论: 客户端与 ES **不兼容** (RESULT = FAILED) —— 上方异常即根因" >&2
  elif [ "$rc" -eq 124 ]; then
    echo "  [client-probe] 未完成: 探针超时(120s) 且未产出 RESULT 行 —— 不等于通过" >&2
  else
    echo "  [client-probe] 未完成: 探针未产出 RESULT 行 (退出码 $rc), 本段诊断**不可信**" >&2
    tail -5 "$out_file" | sed 's/^/  /' >&2
  fi
  rm -rf "$work"
  return 0
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
    # ⚠️ 必须加 timeout（2026-10-04）：实测 inspect 单次 1.4–2.0s，23 个容器
    #   串行无超时可达数十秒。而本函数只在**失败路径**调用，它多耗的每一秒
    #   都在拖慢已经失败的现场取证；Podman 状态损坏时单次可到数十秒，
    #   整轮诊断会直接卡死 —— 拿不到任何日志的诊断等于没有诊断。
    state=$(timeout 15 podman inspect -f '{{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}}' "$c" 2>/dev/null)
    # 取不到值时必须**明说**，不能把空串当成 "exit=0" 而静默跳过该容器。
    if [ -z "$state" ]; then
      state="probe-timeout(无法读取状态, 该容器诊断缺失)"
    fi
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
        | grep -vE 'RunnableWithExceptionProtection' \
        | sed -n '1,40p' >&2 || true
      # ⚠️ 这里曾长期grep -v 'skywalking-api\.log' —— 那是**掩盖未修缺陷**。
      #   2026-10-04 实测: 过滤掉之后底下的真实情况是每个业务服务仍在抛
      #   `FileNotFoundException: /skywalking/agent/logs/skywalking-api.log`
      #   360~815 次。过滤让日志"看起来干净", 于是缺陷被当成已修。
      #   现已改为**显式断言**: agent 日志写不出去就是故障, 必须报出来,
      #   而不是把它从视野里删掉。
      SW_LOG_ERR="$(podman logs --tail -1 "$c" 2>&1 \
        | grep -c 'skywalking-api\.log' || true)"
      if [ "${SW_LOG_ERR:-0}" -gt 0 ]; then
        echo "--- $c 【异常】agent 日志写不出去 ${SW_LOG_ERR} 次 ---" >&2
        echo "    skywalking agent 默认 logging.output=FILE, 而 /skywalking/agent/logs" >&2
        echo "    在非 root 容器里不存在且无法创建。检查 agent-entrypoint.sh 是否" >&2
        echo "    传入了 -Dskywalking.logging.output=CONSOLE。" >&2
        podman logs --tail -1 "$c" 2>&1 \
          | grep 'skywalking-api\.log' | head -3 >&2 || true
      fi
      # 首部窗口。⚠️ 过滤名单必须区分"agent 噪音"与"可能的真实异常":
      # FileNotFoundException 上一版也在名单里, 但它完全可能是真根因
      # (例如挂载卷缺失), 滤掉就等于把证据销毁 —— 只滤掉确定属于
      # SkyWalking agent 自身噪声的那几行, 且不滤异常类型行本身。
      echo "--- $c 日志首 25 行有效内容（已滤 SkyWalking agent 加载噪音）---" >&2
      podman logs --tail -1 "$c" 2>&1 \
        | grep -vE "$NOISE|RunnableWithExceptionProtection|sun\.misc\.Unsafe|Picked up JAVA_TOOL" \
        | grep -vE '^\s+at |^\s*\.\.\. [0-9]+ more' \
        | sed -n '1,25p' >&2 || true
      # 尾部有效内容: Spring 的失败堆栈在末尾, 但末尾同样常被 agent 噪音污染
      echo "--- $c 日志尾 25 行有效内容 ---" >&2
      podman logs --tail -1 "$c" 2>&1 \
        | grep -vE "$NOISE|RunnableWithExceptionProtection|sun\.misc\.Unsafe" \
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
  local tmp rc failures=0
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
    failures=$((failures+1))
  else
    echo "  PASS 镜像构建失败被正确识别 (build_images 返回 $rc)"
  fi

  # ---- 覆盖 2: SERVICE_IMAGES 格式漂移必须被识别 --------------------------
  # 2026-10-02 实测: entry 用 ${entry##*:} 时会把整段吃掉, 6 个镜像全塌成
  # "latest" 且**打印 ok** —— 即 build 成功、tag 却全错, 互相覆盖。
  # 现在的拆解是 ${entry#*:}(最短匹配), 故只有当 entry 恰好是
  # "<x>:latest"、即 tag 被吃成裸 "latest" 时才会命中兜底分支。
  # 用这个形态作为负向输入。
  local probe_out
  probe_out="$(
    SERVICE_IMAGES=("Dockerfile.cloud-infra-gateway:latest")
    build_images 2>&1
  )"
  if printf '%s' "$probe_out" | grep -q "拆解异常"; then
    echo "  PASS SERVICE_IMAGES 格式漂移被识别 (tag 塌成 latest 时报错)"
  else
    echo "  FAIL tag 塌成 latest 未被识别 —— 6 个镜像会静默共用一个 tag" >&2
    echo "        实际输出: $probe_out" >&2
    failures=$((failures+1))
  fi

  # 反向对照: 正常 "file:tag" 格式**不该**被误报为拆解异常。
  # 否则这条自检就成了"永远失败"的噪音, 久而久之没人看 —— 另一种假门禁。
  local norm_out
  norm_out="$(
    SERVICE_IMAGES=("Dockerfile.gateway:localhost/cloud-infra-gateway:latest")
    build_images 2>&1
  )"
  if printf '%s' "$norm_out" | grep -q "拆解异常"; then
    echo "  FAIL 正常 'file:tag' 格式被误报为拆解异常 —— 自检本身是假门禁" >&2
    failures=$((failures+1))
  else
    echo "  PASS 正常 'file:tag' 格式不误报 (自检无误报)"
  fi

  # ---- 覆盖 3: ok/fail 计数必须驱动退出码 --------------------------------
  # 若 ok/fail 只打印不计数, 或结尾漏掉 exit $FAIL, 则全 FAIL 也可能 exit 0
  # —— 正是本仓库反复出现的"控制台绿灯但实际失败"。
  local count_out
  count_out="$(
    PASS=0; FAIL=0
    ok "selftest-should-pass"; ok "selftest-should-pass-2"
    fail "selftest-should-fail"
    echo "COUNTS PASS=$PASS FAIL=$FAIL"
  )"
  if printf '%s' "$count_out" | grep -q "COUNTS PASS=2 FAIL=1"; then
    echo "  PASS ok/fail 计数正确 (2 PASS / 1 FAIL)"
  else
    echo "  FAIL ok/fail 计数异常, 期望 'PASS=2 FAIL=1', 实得: $count_out" >&2
    failures=$((failures+1))
  fi

  # ---- 覆盖 4: 脚本末尾确实以 FAIL 数作为退出码 -------------------------
  # 直接静态检查结尾那行, 防止后续重构把 exit $FAIL 改掉。
  if tail -5 "$SCRIPT_DIR/verify-e2e.sh" | grep -qE '^exit \$FAIL$'; then
    echo "  PASS 脚本以 'exit \$FAIL' 收尾 (FAIL 数即退出码)"
  else
    echo "  FAIL 脚本结尾不是 'exit \$FAIL' —— 全 FAIL 也可能 exit 0" >&2
    failures=$((failures+1))
  fi

  # ---- 覆盖 7: JVM 内存边界门禁必须真的被调用 (2026-10-05) --------------
  # 起因: check_jvm_native_memory_bounds.py 自己长期 9/9 全绿, 但
  # `grep -c` 显示它在本脚本里被调用 **0 次** —— 没人跑的门禁等于没有门禁。
  # 而它的假绿已造成真实后果 (nacos 的边界写在无人读取的 JVM_ADJUST 上)。
  #
  # 这里断言两件事:
  #   a) 两个门禁脚本都被**函数定义之外**的地方调用（即真的被调用）
  #   b) 调用发生在 build_images / compose up 之前
  #
  # ⚠️ 搜索必须排除 run_gate_selftest 函数体 —— 否则本函数里
  #   打印这些文件名的 echo 行会把自己当成被检对象。
  #   这已是本函数第 7 次踩同一类坑（"被检字面量污染自己的断言"）。
  local self_start7 self_end7
  self_start7="$(grep -n '^run_gate_selftest()' "$SCRIPT_DIR/verify-e2e.sh" | head -1 | cut -d: -f1)"
  self_end7="$(awk -v s="$self_start7" 'NR>s && /^}/ {print NR; exit}' "$SCRIPT_DIR/verify-e2e.sh")"

  # check_resource_optimization 于 2026-10-05 新增, 一并登记:
  #   它查的是"优化项没被回退 + 链路没断", 与前两个同属"没人跑就等于没有"。
  #
  # ⚠️ 2026-10-05 负向对照**连续两次推翻了这段断言**, 记录如下:
  #
  #   (一) 原断言找 `tests/<脚本名>.py` 字面量, 而它出现在**被检函数自己的
  #       定义体**内 —— 注释掉调用点后自检仍判绿。即函数名与脚本名不同时,
  #       "定义了"就足以满足"被调用了"。第三课第六类假门禁, 三个门禁全中招。
  #       改为断言**裸调用**: 必须有 `函数名 ...` 开头的真实执行行。
  #
  #   (二) 改完后前两个门禁**恒红** —— 因为 gate 列表里填的是**脚本名**
  #       (check_jvm_native_memory_bounds.py) 而真实**函数名**是
  #       check_jvm_native_bounds。判据指向了错误的标识, 找不到就报缺失。
  #       即第五课补记那条"判据必须指向被检对象的真实标识", 同一处认知,
  #       写下注释时用对了, 动手时又用错了。**下面一律用函数名**。
  for gate in check_jvm_native_bounds check_jvm_opts_keys check_resource_optimization check_schema_init_wiring check_docs_probe_scripts check_proxy_env check_start_period_covers_startup; do
    local call_line
    call_line="$(awk -v s="$self_start7" -v e="$self_end7" -v g="$gate" '
      NR >= s && NR <= e { next }
      /^[ \t]*#/ { next }
      { line=$0; sub(/^[ \t]+/, "", line) }
      index(line, g) == 1 && line !~ (g "\\(\\)") { print NR; exit }
    ' "$SCRIPT_DIR/verify-e2e.sh")"
    if [ -z "$call_line" ]; then
      echo "  FAIL $gate 裸调用缺失 —— 函数定义了但没人跑, 等于没有守护" >&2
      failures=$((failures+1))
    else
      echo "  PASS $gate 在第 $call_line 行被真实调用"
    fi
  done

  # b) 位置: 必须早于首个 build_images 调用
  local jvm_line build_line2
  jvm_line="$(awk -v s="$self_start7" -v e="$self_end7" '
    NR >= s && NR <= e { next }
    /tests\/check_jvm_native_memory_bounds\.py/ && $0 !~ /^[ \t]*#/ { print NR; exit }
  ' "$SCRIPT_DIR/verify-e2e.sh")"
  build_line2="$(awk -v s="$self_start7" -v e="$self_end7" '
    NR >= s && NR <= e { next }
    { line=$0; sub(/^[ \t]+/, "", line) }
    line ~ /^if build_images; then$/ { print NR; exit }
  ' "$SCRIPT_DIR/verify-e2e.sh")"
  if [ -n "$jvm_line" ] && [ -n "$build_line2" ] && [ "$jvm_line" -lt "$build_line2" ]; then
    echo "  PASS JVM 边界门禁前置 (行 $jvm_line < build_images 行 $build_line2)"
  else
    echo "  FAIL JVM 边界门禁位置不对 (jvm=$jvm_line build=$build_line2)" >&2
    failures=$((failures+1))
  fi

  # ---- 覆盖 5: 关键判定不能被管道吞掉退出码 -----------------------------
  # bash 经典陷阱: cmd | head 会让 $? 变成 head 的退出码。
  # 本脚本大量使用 `curl ... | head`, 故显式断言关键 build 判定不走管道。
  #
  # ⚠️ grep 必须排除 run_gate_selftest 函数体, 否则会匹配到本函数里
  #   打印该字符串的 echo 行 —— 自检把自己当成被检对象, 恒失败。
  #   第一版就踩了这个坑(实测 FAIL: 存在 'podman build ... | head')。
  local piped_build
  piped_build="$(awk '
    /^run_gate_selftest\(\)/ {inself=1; next}
    inself && /^}/ {inself=0; next}
    !inself && /podman build/ && /\|/ {print NR": "$0}
  ' "$SCRIPT_DIR/verify-e2e.sh")"
  if [ -n "$piped_build" ]; then
    echo "  FAIL podman build 判定被管道截断 —— 退出码会被下游吞掉:" >&2
    printf '%s\n' "$piped_build" | sed 's/^/        /' >&2
    failures=$((failures+1))
  else
    echo "  PASS build 判定未被管道截断 (自检函数外无 'podman build | ...')"
  fi

  # ---- 覆盖 6: commit 预算门禁必须真的拦住 (2026-10-04) ----------------
  # 只静态检查“在不在”不够: 该函数第一版写成 `out=$(...); rc=$?` 就已是错的
  # —— 那样 rc 永远是 0, 门禁形同虚设。这里断言三件事:
  #   a) 函数确实调用了守护脚本, 且用的是它的退出码
  #   b) rc=1 时 return 非 0（否则 ok/fail 计数会把失败记成通过）
  #   c) 前置门禁出现在任何 podman build / compose up 之前
  if ! grep -q 'check_commit_budget.py' "$SCRIPT_DIR/verify-e2e.sh"; then
    echo "  FAIL 未调用 check_commit_budget.py —— commit 预算门禁缺失" >&2
    failures=$((failures+1))
  else
    echo "  PASS 已调用 check_commit_budget.py"
  fi

  # b) 退出码必须来自守护脚本本身, 不能被中间命令覆盖
  local cc_line
  cc_line="$(awk '/^check_commit_budget\(\)/{inself=1} inself && /rc=\$?/{print; exit} inself && /^}/{exit}' \
    "$SCRIPT_DIR/verify-e2e.sh")"
  case "$cc_line" in
    *'rc=$?'*) echo "  PASS commit 门禁读取守护脚本退出码: ${cc_line//  /}" ;;
    *) echo "  FAIL commit 门禁未正确读取退出码 (期望含 'rc=\$?'), 实得: ${cc_line:-未找到}" >&2
       failures=$((failures+1)) ;;
  esac

  # c) 位置: 前置门禁必须在**首个** build_images / compose up 调用之前
  #
  # ⚠️ 这里连踩**四次**坑, 都是同一类: 探针取错了对象（AGENTS.md 第五课）。
  #   第 1 次: `awk '/^build_images\(\)/'` 匹配到**函数定义**(322 行)而非调用
  #            —— 一个真正前置的门禁被判成"位于 build 之后"。
  #   第 2 次: 改成只认**裸调用** `^build_images$`, 又匹配不到 —— 真实调用
  #            是 `if build_images; then`(610 行), 带 if 前缀。断言永远 SKIP,
  #            而"永远 SKIP 的断言"等于没有断言。
  #   第 3 次: 改用宽松匹配后, 命中 337 行的**缩进注释**
  #            `#   而 build_images 打印的是 ...` —— `!/^#/` 只能滤掉行首 #,
  #            滤不掉缩进注释。
  #   第 4 次: 滤掉注释后, 命中 386 行的 `PATH="$tmp:$PATH" build_images`
  #            —— 那是**自检函数体内**用假 podman 的探测调用。
  #            即: 断言把自己当成了被检对象。这与本函数覆盖 5 踩过的坑
  #            **完全同型**, 而那条注释就写在下方十几行处 —— 教训没被读到。
  # 正解: 三重排除 —— 缩进注释 / 函数定义 / 自检函数体, 再取首次调用。
  # 正解: 只在自检函数体**之外**搜索两个行号。
  # ⚠️ 这里连踩六次, 全部是同一类"被检字面量污染自己的断言":
  #   1) 匹配到 build_images **函数定义**而非调用
  #   2) 只认裸调用 → 真实调用是 `if build_images; then` → 永远 SKIP
  #   3) 宽松匹配 → 命中**缩进注释**里的 build_images
  #   4) 滤注释后 → 命中**自检体自身**的假 podman 调用
  #   5) 滤自检体后 → 命中**注释里写的行号示例** `if build_images; then`(610 行)
  #   6) 门禁的 grep 模式**本身就含** `check_commit_budget; }; then`,
  #      命中的是这行 grep 自己 → gate_line 恒小于 build_line → 断言恒绿
  # 第 6 次最隐蔽: 污染源不是注释, 而是**断言代码自身**。
  # 通用解法: 先定出自检体的行区间, 两个搜索都限定在该区间之外。
  local self_start self_end
  self_start="$(grep -n '^run_gate_selftest()' "$SCRIPT_DIR/verify-e2e.sh" | head -1 | cut -d: -f1)"
  self_end="$(awk -v s="$self_start" 'NR>s && /^}/ {print NR; exit}' "$SCRIPT_DIR/verify-e2e.sh")"
  gate_line="$(awk -v s="$self_start" -v e="$self_end" '
    NR >= s && NR <= e { next }
    /check_commit_budget; \}; then/ { print NR; exit }
  ' "$SCRIPT_DIR/verify-e2e.sh")"
  build_line="$(awk -v s="$self_start" -v e="$self_end" '
    NR >= s && NR <= e { next }
    { line=$0; sub(/^[[:space:]]+/, "", line) }
    line ~ /^#/ { next }
    /build_images\(\)[ \t]*\{/ { next }
    /build_images/ && line !~ /^echo/ { print NR; exit }
  ' "$SCRIPT_DIR/verify-e2e.sh")"
  if [ -z "$gate_line" ]; then
    echo "  FAIL 找不到 commit 前置门禁的调用点" >&2
    failures=$((failures+1))
  elif [ -z "$build_line" ]; then
    # 这里**不能** SKIP: 找不到调用点意味着脚本结构已变, 断言失效。
    # 按 AGENTS.md, 扫不到目标必须判红 —— 扫描器坏了 != 通过。
    echo "  FAIL 找不到 build_images 的调用点 —— 位置断言已失效" >&2
    echo "        (脚本结构可能已变; 此时不能假定门禁仍然前置)" >&2
    failures=$((failures+1))
  elif [ "$gate_line" -gt "$build_line" ]; then
    echo "  FAIL commit 门禁不在 build_images 调用(行 $build_line)之前 (门禁在 $gate_line)" >&2
    echo "        必须前置 —— 否则镜像构建已吃掉预算, 检查只剩事后诸葛" >&2
    failures=$((failures+1))
  else
    echo "  PASS commit 门禁前置 (门禁行 $gate_line < build_images 调用行 $build_line)"
  fi

  # ---- 2026-10-05 新增: 守护本轮接线的两个 check 门禁 ----
  #
  # 为什么需要: 本轮查出 check_commit_runtime_guard.py 与
  # check_sw_oap_config.sh **全仓无任何调用点**, 而它们各自都有守护测试且全绿。
  # 接线之后若无自检, 下一个人删掉调用行时同样会静默失效 ——
  # 那正是本轮修复的同一个缺陷, 不能只修一次。
  #
  # ⚠️ 搜索一律排除本函数区间(第 6 次污染: 断言代码自身含被检字面量)。
  # ⚠️ 同样排除**注释行**: 本函数上方那段说明里就写着这两个文件名。
  #
  # ⚠️⚠️ 必须匹配**调用形态**而不是"名字出现过" —— 第 7 次同型缺陷:
  #   第一版用 index($0, g), 于是 `check_commit_runtime_guard() {` 这个
  #   **函数定义**也会命中。负向对照实测: 删掉调用行 `check_... || true` 后,
  #   自检仍报 PASS —— 定义还在, 机制"存在", 但**从未被调用**。
  #   这正是本轮修的那个缺陷在自检层的重演。
  #
  #   两种真实调用形态都要认(实测二者并存):
  #     check_commit_runtime_guard || true            # 裸调用
  #     { ...; check_sw_oap_config; } then            # 嵌在 if 条件里
  #   排除的: 函数定义 `name() {`、变量赋值 `name=...`、注释。
  for _gate in check_sw_oap_config check_commit_runtime_guard; do
    _hit="$(awk -v s="$self_start" -v e="$self_end" -v g="$_gate" '
      NR >= s && NR <= e { next }
      { line=$0; sub(/^[[:space:]]+/, "", line) }
      line ~ /^#/ { next }
      line ~ /\(\)[ \t]*\{/ { next }          # 函数定义, 不是调用
      line ~ /^[a-zA-Z_]+=/ { next }          # 变量赋值
      line == g || line == g " || true" { print NR; nextfile }
      # 嵌在 if 条件里: `...; g; } then`
      line ~ ("[;[:space:]]" g "[;[:space:]]") { print NR; nextfile }
    ' "$SCRIPT_DIR/verify-e2e.sh")"
    if [ -z "$_hit" ]; then
      # 扫不到 = 调用被删或形态变了, 不能假定它仍被调用(AGENTS.md: 扫到 0 个必须判红)
      echo "  FAIL 找不到 $_gate 的**调用点** —— 定义还在但从未被调用, 门禁会静默失效" >&2
      failures=$((failures+1))
    else
      echo "  PASS $_gate 在第 $_hit 行被调用"
    fi
  done

  # OAP 门禁是**阻断**型, 必须真被执行(不能被 || true 吞掉)。
  if awk -v s="$self_start" -v e="$self_end" '
      NR >= s && NR <= e { next }
      /check_sw_oap_config; \};/ { found=1 }
      END { exit found ? 0 : 1 }
    ' "$SCRIPT_DIR/verify-e2e.sh"; then
    echo "  PASS check_sw_oap_config 以阻断方式执行 (未被 || true 吞掉)"
  else
    echo "  FAIL check_sw_oap_config 不是阻断执行 —— OAP 错配将不再中止 E2E" >&2
    failures=$((failures+1))
  fi

  if [ "$failures" -ne 0 ]; then
    echo "  => $failures 项自检未通过" >&2
    return 1
  fi
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

# ── 前置门禁: 全局 commit 预算（2026-10-04）────────────────────────────
# 必须放在**任何重资源操作之前**。若放在后面, 镜像构建 + 6 个服务启动
# 已经把预算吃掉, 再检查就只剩"事后诸葛", 且探针本身也会受影响。
#
# 为什么这道门禁是必需的: 宿主 vm.overcommit_memory=2 (严格模式) 下,
# 所有进程共享 CommitLimit = MemTotal + SwapTotal = 20.63 GB。
# 预算耗尽时内核拒绝任何新 commit, JVM 报的是:
#     Native memory allocation (malloc) failed to allocate 112 bytes
#     pthread_create failed (EAGAIN) ... stacksize: 1024k
# 而 cgroup 侧 memory.events oom=0 / OOMKilled=false —— **完全不像内存问题**。
# 最坑的是: 提高 mem_limit 或 -Xmx 只会让它**更快**耗尽全局预算。
check_commit_budget() {
  local out rc
  out=$(python3 "$SCRIPT_DIR/deploy/tests/check_commit_budget.py" 2>&1); rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  if [ "$rc" -eq 1 ]; then
    fail "全局 commit 预算不足 —— 6 服务必然崩溃 (详见上方排查指引)"
    return 1
  fi
  ok "全局 commit 预算充足"
  return 0
}

if ! { echo "[前置] 检查全局 commit 预算 ..."; check_commit_budget; }; then
  echo
  echo "!! commit 预算不足, 继续跑只会得到 6 个服务随机崩溃的假象。" >&2
  echo "!! 请先清理宿主上的孤儿进程 (不属于任何容器, podman ps 看不到)。" >&2
  exit 1
fi
echo

# ---- JVM native 内存边界 (静态, 不需容器运行) ---------------------------
#
# ⚠️ 这两个门禁此前**从未被本脚本调用** (2026-10-05 实测):
#   grep 统计显示 check_jvm_native_memory_bounds.py 被调用 0 次。
#   它们自己全绿, 但没人跑 —— 与"上游坏掉 → 自己伪装成从未验证过"同型。
#   而 check_jvm_native_memory_bounds 的假绿已经被证实造成了真实后果:
#   nacos 的四个 -XX 边界写在 JVM_ADJUST 上, 镜像脚本从不读它,
#   VmSize 实测 10.51GB, 而该守护报 9/9 PASS。
check_jvm_native_bounds() {
  local out rc
  out=$(python3 "$SCRIPT_DIR/deploy/tests/check_jvm_native_memory_bounds.py" 2>&1); rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  if [ "$rc" -eq 1 ]; then
    fail "有 JVM 未设置 -XX:MaxMetaspaceSize —— 未受限的 Metaspace/CompressedClassSpace 预留会吃满全局 commit 预算"
    return 1
  fi
  ok "全部 JVM 已设置 -XX:MaxMetaspaceSize"
  return 0
}

# ---- 文档判据脚本可执行性 (静态, 不需容器运行) ----------------------------
#
# ⚠️ 为什么需要这个门禁: AGENTS.md 第八课补记六把"两本账"写成了可直接
# 复制的 shell 片段, 并用它得出"预警非故障"的定性。但**文档里的脚本坏了,
# 结论仍会被当作成立** —— 这是第五课最隐蔽的一种形态: 全绿、自洽、只是错了。
#
# 该守护把文档当被测对象: 从 AGENTS.md 抽取代码块并**原样执行**,
# 断言退出码 0、无 stderr、输出格式与文档承诺一致。
# 抽取而非复制是关键 —— 若复制, 文档改了守护不改就会形成漂移。
check_docs_probe_scripts() {
  local out rc
  out=$(python3 "$SCRIPT_DIR/deploy/tests/test_docs_probe_scripts.py" 2>&1); rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  if [ "$rc" -eq 1 ]; then
    fail "文档里的 commit/meminfo 判据脚本已不可执行或输出格式漂移（详见上方）"
    return 1
  fi
  ok "文档判据脚本可原样执行且格式一致"
  return 0
}

# ---- 容器代理变量泄漏 (运行时, 不需重启任何容器) ------------------------
#
# ⚠️ 判据必须是**运行时**而非 compose 文件, 也不是 `podman-compose config`
# 的渲染结果 —— 2026-10-06 实测: 改完 compose 后渲染层已全部正确,
# 而运行中的容器仍带着宿主那个无监听的 http_proxy=http://127.0.0.1:7890。
# 即"配置已修好"与"已生效"之间隔着一次重启。
#
# 只警告不阻断: 泄漏的实际影响取决于各客户端是否读 http_proxy,
# 而"当前日志无代理报错"不等于配置正确 —— 阻断会把环境问题
# 变成 E2E 的失败结论, 反而掩盖真实状态。
check_proxy_env() {
  local out rc
  out=$(python3 "$SCRIPT_DIR/deploy/tests/check_proxy_env.py" 2>&1); rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  if [ "$rc" -eq 1 ]; then
    fail "有容器继承了宿主的 http_proxy 等代理变量（宿主该端口无监听）"
    return 1
  fi
  ok "无容器继承宿主代理变量"
  return 0
}

# ---- 资源优化守护 (静态, 不需容器运行) ---------------------------------
#
# 2026-10-05 新增。守护 PostgreSQL 刷盘调优与 mem_limit 不被静默回退。
#
# ⚠️ 这个门禁查的**不止 compose.yaml**, 因为调优参数根本不在 compose 里:
#   compose 的 command 段被 podman-compose 丢弃成 ["postgres"],
#   PG_* 环境变量该镜像 entrypoint 不转换 —— 两者都实测无效。
#   唯一可靠入口是 ALTER SYSTEM, 由 deploy/apply-postgres-tuning.sh 执行,
#   而 up.sh 必须调用它, 否则机制存在但链路未接(第三课)。
# ⇒ 所以门禁同时检查 conf / 脚本 / up.sh 三处, 少一处就会出现"看似有守护"。
check_resource_optimization() {
  local out rc
  out=$(python3 "$SCRIPT_DIR/deploy/tests/check_resource_optimization.py" 2>&1); rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  if [ "$rc" -eq 1 ]; then
    fail "资源优化项被回退或链路断开（详见上方具体项）"
    return 1
  fi
  ok "资源优化项完整且已接线"
  return 0
}

# 判定各服务的 schema.sql **真的会被执行**, 而不是"文件存在但无人引用"。
# 2026-10-06 实测: notification-service 与 file-service 的 schema.sql 从未执行,
# 前者导致 @Scheduled 定时扫描每轮抛 `relation ... does not exist`(实测 39 次),
# 后者 4 张表全缺但容器 healthcheck 仍报 healthy —— 第三课同型, 在 DDL 层重演。
check_schema_init_wiring() {
  local out rc
  out=$(python3 "$SCRIPT_DIR/deploy/tests/check_schema_init_wiring.py" 2>&1); rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  if [ "$rc" -eq 1 ]; then
    fail "存在 schema.sql 从未被执行的服务（详见上方具体项）"
    return 1
  fi
  ok "各服务 schema.sql 均有执行机制"
  return 0
}

# start_period 必须覆盖实测启动耗时（2026-10-06 实测缺陷）。
#
#   机制: Docker healthcheck 在 start_period 期间**不计数**失败, 之后连续失败
#   retries 次即锁定 unhealthy 且**不会**因应用就绪而回 healthy。
#   实测 6 个业务服务里**5 个**的 start_period(20s) + interval(5s)*retries(5)
#   = 45s 判定窗口, 远小于实测启动 108s~304s —— 即服务完全可用却永远 unhealthy。
#
#   为什么这不只是"状态显示难看": verify-e2e.sh 的等待预算只有 600s
#   (第 1135 行 DEADLINE), 而最慢服务实测启动 304s —— 两者叠加会把
#   "服务正常" 报成 E2E 失败。
check_start_period_covers_startup() {
  local out rc
  out=$(python3 "$SCRIPT_DIR/deploy/tests/check_start_period_covers_startup.py" 2>&1); rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  if [ "$rc" -eq 1 ]; then
    fail "业务服务 start_period 覆盖不住实测启动耗时（详见上方具体项）"
    return 1
  fi
  ok "各业务服务 start_period 均覆盖实测启动耗时"
  return 0
}

# 判定 compose 里的 JVM 参数键**确实是镜像启动脚本会读的**。
# 静态解析 compose 的守护无法发现"配置写在没人读的键上"(第三课在守护层的重演)。
#
# 需要 podman 与镜像, 所以**失败时只记录不阻断**（调用处用 `|| true`）:
# 它要跑 podman, 而 podman 在 commit 紧张时自身就会崩 —— 那时它返回 1
# 是**环境问题**, 不是配置问题。阻断会把"环境紧张"变成"E2E 配置错误",
# 反而掩盖真实状态（同 check_commit_runtime 的处理理由）。
# ⚠️ 用 fail 而非 warn: 本脚本只定义了 ok/fail（见第 31-32 行）,
#   写 warn 会在 set -e 下变成 "command not found" 而中断整个脚本。
check_jvm_opts_keys() {
  local out rc
  out=$(python3 "$SCRIPT_DIR/deploy/tests/check_jvm_opts_keys_match_image.py" 2>&1); rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  if [ "$rc" -eq 1 ]; then
    fail "JVM 参数键未被镜像启动脚本读取 —— 那些参数不会生效(假绿)"
    return 1
  fi
  ok "JVM 参数键均被镜像启动脚本读取"
  return 0
}

echo "[前置] 检查 JVM native 内存边界 ..."
check_jvm_native_bounds || true
check_jvm_opts_keys || true
echo

echo "[前置] 检查资源优化项未被回退 ..."
check_resource_optimization || true

# 文档判据脚本可执行性（2026-10-06 新增）。
# 与 check_resource_optimization 一样是 `|| true` 只警告不阻断：
# 它守护的是**文档可信度**而非运行时健康，不该成为 E2E 的失败原因。
# 但登记进 run_gate_selftest —— 不登记就等于没人跑（第三课）。
check_docs_probe_scripts || true

# 容器代理变量泄漏（2026-10-06 新增）。
# ⚠️ 只警告不阻断, 且**必须在 compose 同步前跑** —— 它的作用是
#    告诉你"哪些容器需要重启才能生效", 而不是判定环境可用。
echo "[前置] 检查容器是否继承宿主代理变量 ..."
check_proxy_env || true
echo

# ---- schema.sql 执行机制 (2026-10-06 接线) ------------------------------
# 位置放在资源门禁之后、业务服务启动之前: 它是纯静态检查, 不依赖运行中的容器,
# 但必须在服务起来之前判红 —— 否则缺表会被 healthcheck 掩盖(实测 file-service
# 4 张表全缺, 容器仍是 healthy)。
echo "[前置] 检查各服务 schema.sql 有执行机制 ..."
check_schema_init_wiring || true
echo "[前置] 检查业务服务 start_period 覆盖实测启动耗时 ..."
check_start_period_covers_startup || true
echo

# ---- SkyWalking OAP 配置/镜像版本对齐 (2026-10-05 接线) -------------------
#
# ⚠️ 本门禁此前**从未被调用**, 与 2026-10-05 修好的那两个 JVM 门禁同型
#   ("守护全绿, 但没人跑") —— 且更隐蔽: 它有自己的守护测试
#   test_check_sw_oap_config.sh, 守护测试只测脚本本身, 不证明它被接线。
#
#   真实后果已经有记录 (2026-10-03 实测): compose 用 OAP 9.7.0,
#   而 deploy/skywalking/config/application.yml 是从 10.1.0/11.x 抄来手工删减的
#   (官方 9.7.0 有 37 个模块块, 本文件曾只剩 23 个), 于是 OAP **从未成功启动过**,
#   崩溃循环 restarts=51, 却因 depends_on 只等 service_started 被当成"已就绪"。
#
#   为什么放在这里**阻断**: 它是纯静态比对(compose tag vs 仓库内 yml vs 镜像内官方
#   yml), 不碰容器、不依赖网络 —— 与 check_jvm_opts_keys 不同, 它的 rc=1 是
#   **配置错配**而非环境紧张, 阻断是对的。
#   (镜像未拉取时第 2 项会 SKIP, 此时只比对前两项, 不影响阻断语义。)
check_sw_oap_config() {
  local out rc
  out=$(bash "$SCRIPT_DIR/deploy/tests/check_sw_oap_config.sh" 2>&1); rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  # rc=2 是用法/环境错误(如 python3 缺失), 不是错配 —— 不该按错配阻断。
  if [ "$rc" -eq 1 ]; then
    fail "SkyWalking OAP 的 application.yml 与 compose 镜像 tag 错配 —— OAP 会崩溃循环"
    return 1
  fi
  if [ "$rc" -eq 2 ]; then
    # ⚠️ 不静默: 探针自身失效与"通过"必须区分(第五课补记一)。
    fail "check_sw_oap_config.sh 以 rc=2 退出(用法/环境错误) —— 本次未判定, 不是通过"
    return 1
  fi
  ok "SkyWalking OAP 配置与镜像 tag 对齐"
  return 0
}

if ! { echo "[前置] 检查 SkyWalking OAP 配置对齐 ..."; check_sw_oap_config; }; then
  echo
  echo "!! OAP 配置与镜像错配, 继续跑只会得到 SkyWalking 崩溃循环的假象。" >&2
  echo "!! 详见 deploy/skywalking/config/application.yml 与 deploy/compose.yaml 的 OAP tag。" >&2
  exit 1
fi

# ---- 运行期 commit 预算 (2026-10-05 接线) ---------------------------------
#
# ⚠️ 同样**从未被调用**。check_commit_budget 只在启动前查一次, 而实测崩溃发生在
#   运行期 (启动前 81% PASS → 运行期 99.6% → system/notification malloc 失败)。
#
#   为什么**只警告不阻断**: 与 check_jvm_opts_keys 同一个理由 ——
#   commit 高不等于服务已坏, 阻断会把"环境紧张"这个次要事实变成 E2E 的失败结论,
#   反而掩盖真实状态。阈值 92% 落在实测正常区间 (82%~91%) 与崩溃点 (99.6%) 之间。
check_commit_runtime_guard() {
  local out rc
  out=$(python3 "$SCRIPT_DIR/deploy/tests/check_commit_runtime_guard.py" 2>&1); rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  if [ "$rc" -eq 1 ]; then
    fail "运行期 commit 预算吃紧 —— 6 服务可能随机崩溃(非阻断, 仅记录)"
    return 1
  fi
  ok "运行期 commit 预算有余量"
  return 0
}

echo "[前置] 检查运行期 commit 预算 (仅告警) ..."
check_commit_runtime_guard || true
echo

# ⚠️ 已删除的“堆外余量门禁” (2026-10-05) —— 记录它的错误与正确结论,
#    避免下一个人把它重新加回来。
#
# 当初的假设: skywalking-oap 的 mem_limit(1024m) 恰好等于 -Xmx(1024m),
# 堆外余量为 0 ⇒ 必然 malloc 失败。因此写了 check_heap_headroom.py,
# 要求每个容器留 >=256MB 堆外余量, 并抬高了多个服务的 mem_limit。
#
# 实测证明这个假设**不成立**:
#   · cloud-auth 的 mem_limit=768m、-Xmx=384m, 堆外余量 384MB (远超 256MB 阈值),
#     门禁通过 —— 而它**照样** `Native memory allocation (malloc) failed`。
#   · 反之, 逐个启动(间隔 45s)时commit 只到 73-84%, 同样的配置**不崩溃**。
#   · 两者的报错文案完全一样(OOMKilled=false), 靠文案无法区分,
#     靠“堆外余量”这个量也区分不了。
#
# 真正的判据是**上面的 check_commit_budget**: 宿主机 vm.overcommit_memory=2 下,
# 全局 commit 额度(MemTotal+SwapTotal=20.63GB)被多个 JVM 的虚拟内存预留占满;
# 连续启动多个服务时瞬时冲破, **已经在运行**的服务就会 malloc 失败而崩溃
# (即“给更多资源反而更早失败”)。cgroup 限额管不到这件事, 所以任何基于
# mem_limit/Xmx 的静态检查都无法预测它。
#
# 结论: 静态配置检查**无法**预测这类故障, 只能靠“降低启动并发 + 观察”的方式规避。
# 因此保留 commit 门禁(它查的是真实配额), 删除堆外余量门禁(它查的是一个
# 与故障无关的量, 留着会给人虚假的安全感)。

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
PROBE_FAILS=0
# 运行期 commit 复查（2026-10-04）。
#   为什么必须有: 启动前那一次门禁只反映**启动时刻**。实测启动时
#   Committed_AS 仅 64%、门禁 PASS, 十几分钟后 OAP 仍死于
#   `Native memory allocation (malloc) failed to allocate 9104 bytes`。
#   即**通过启动门禁 ≠ 运行期不会耗尽** —— 峰值出现在
#   mvn + 镜像构建 + 20 个容器同时起的阶段。
#   为什么只警告不阻断: commit 高**不等于**服务已经坏了, 阻断会把
#   “环境紧张”这个真实但次要的事实变成 E2E 的失败结论, 反而掩盖
#   服务自身的真实状态。证据留下, 判定交给下一步。
COMMIT_WARNED=0
COMMIT_PEAK=0
check_commit_runtime() {
  local pct
  # ⚠️ 这里读的是真实 /proc/meminfo, 不接受路径注入。
  #   前一版支持 ${MEMINFO_PATH:-/proc/meminfo}, 但本次实测发现:
  #   那条注入路径让"门禁脚本"与"被门禁的对象"共用一个可变量 ——
  #   一旦注入, 判据就不再对真实系统生效, 而**输出格式完全合法**,
  #   看起来像在工作。真实运行不需要可测性, 判据就该钉死在真实文件上。
  pct=$(awk '/^Committed_AS:/{c=$2}/^CommitLimit:/{l=$2}END{if(l>0)printf "%d", c*100/l; else print ""}' /proc/meminfo)
  # 取不到值 → 显式 SKIP 且**不**当通过(第八课补记一)
  [ -n "$pct" ] || { echo "  ⚠️  [运行期] /proc/meminfo 读不到 commit 账本, 本次不判定" >&2; return 0; }
  [ "$pct" -gt "$COMMIT_PEAK" ] && COMMIT_PEAK=$pct
  # 阈值从 85 提到 92（2026-10-05 实测校准）。
  #   实测 6 服务运行期 commit 在 82%~91% 反复震荡且全部存活,
  #   而崩溃发生在 99.6%（system: AllocateHeap, notification: Chunk::new）。
  #   85% 在实测里是**正常区间**, 拿它当阈值会天天误报;
  #   92% 才落在"稳定"与"崩溃"之间的空档。
  [ "$pct" -ge 92 ] 2>/dev/null || return 0
  if [ "$COMMIT_WARNED" -eq 0 ]; then
    COMMIT_WARNED=1
    echo "  ⚠️  [运行期] 全局 commit 预算 $pct% ≥ 92% —— 已进入实测崩溃区间(99.6% 时必崩)。" >&2
    echo "      宿主机 vm.overcommit_memory=2 下, 超额 commit 会被内核直接拒绝(malloc 返回 ENOMEM)," >&2
    echo "      与容器 mem_limit 无关(cgroup 的 oom 计数会全为 0)。此刻尚未导致服务失败," >&2
    echo "      但请留意下面的健康结果是否与之相关。" >&2
    echo "      ⚠️ 不要去 kill 单个进程: 实测形态是 9 个 JVM 的**合计 + 波动**贴近上限," >&2
    echo "         没有任何单个进程值得杀。有效方向是降低 -Xms/-Xmx 或减少同时运行的 JVM 数。" >&2
  fi
}
while :; do
  check_commit_runtime
  MISSING=""
  round_fail=0
  for svc in "${MICROSERVICES[@]}"; do
    # ⚠️ 必须加 timeout（2026-10-04）。
    #   实测 `podman inspect --format '{{.State.Health.Status}}'` 单次耗时
    #   **1.4–2.0 秒**; 6 个服务串行 = 约 10 秒/轮, 而循环 sleep 只有 6 秒。
    #   也就是说循环体本身就超过间隔 —— 一旦 podman 变慢(Podman 状态损坏时
    #   单次可到数十秒), 这个循环会**长时间不返回**, 600s 预算形同虚设。
    #
    #   更危险的是: `|| echo absent` 把"探针取不到值"与"容器真的不存在"
    #   混为一谈。前者应重试, 后者才是故障。混为一谈会让预算在 podman
    #   响应变慢时被提前耗尽, 报出一个**与真实原因无关**的失败。
    state=$(timeout 15 podman inspect --format '{{.State.Health.Status}}' "cloud-$svc" 2>/dev/null)
    rc=$?
    if [ "$rc" -ne 0 ] || [ -z "$state" ]; then
      state="probe-timeout"
      round_fail=1
      PROBE_FAILS=$((PROBE_FAILS+1))
    fi
    [ "$state" = "healthy" ] || MISSING="$MISSING $svc=$state"
  done
  [ "$round_fail" -eq 0 ] && PROBE_FAILS=0
  [ -z "$MISSING" ] && break
  # 探针不可用时**不**消耗判定预算: 继续等, 直到能真正读到状态。
  # 否则报出的 "未 healthy" 是在说探针坏了, 不是在说服务坏了。
  if [ "$round_fail" -eq 1 ]; then
    if [ "$(date +%s)" -ge "$DEADLINE" ]; then
      echo "  (最后一次探测: $PROBE_FAILS 轮 podman 探针超时, 非服务健康问题)" >&2
      break
    fi
    sleep 6
    continue
  fi
  [ "$(date +%s)" -ge "$DEADLINE" ] && break
  sleep 6
done
# 打印本轮运行期 commit 峰值。
#   为什么必须留下: 崩溃现场极难还原(落点随机: AllocateHeap / Chunk::new /
#   nmethod / pthread_create EAGAIN)。若只输出"当时没超阈值", 事后无法区分
#   "真的没超" 与 "没采到"; 有峰值数字, 至少能定量回答"这轮余量到底多少"。
#   下次崩溃时, 拿这个数字与 99.6%(实测崩溃值)对照即可。
echo "  [运行期 commit 峰值] ${COMMIT_PEAK}%"
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
# ⚠️ 判据**不得**写死 PASS 的个数 (2026-10-04 实测踩到的自身回归)。
#   背景: e2e-test.sh 新增了 2 项检查(输出变成 PASS=8), 而这里曾写死
#   `grep -qE "结果: PASS=6 FAIL=0"` —— 于是整条 E2E 会把 8/8 全绿**判成红**,
#   且日志里看不出原因(只说"e2e 未全过")。
#   这正是本项目记录的"改了一处, 忘了另一处依赖它的地方"——
#   被依赖方是**另一个脚本**, 不是编译期能发现的东西, 编译器不会报错。
#
#   正解: 只锚定 FAIL=0, 不锚定 PASS 的绝对值。
#   · PASS 个数是"这轮加了几项检查"的函数, 本就会变;
#   · FAIL=0 才是"全部检查都没红"这个不变量的语义;
#   · 同时打印实际 PASS 值, 便于人工确认检查项没被误删(见下方fail 分支)。
#   ⚠️ 也不能反向写成 "PASS>0" 就算过 —— 若e2e-test.sh 自身崩在第1 步,
#   它不会输出 FAIL=0, 但极端情况下"检查项被删到只剩0 项且全过"同样该被发现。
#   故此处同时要求 PASS>=1: 至少真的跑了一项。
if bash e2e-test.sh 2>&1 | tee "$SCRIPT_DIR/.e2e-acceptance.log" \
     | tail -1 | grep -qE "结果: PASS=([1-9][0-9]*) FAIL=0"; then
  ok "e2e 全过 ($(tail -1 "$SCRIPT_DIR/.e2e-acceptance.log" | grep -oE 'PASS=[0-9]+' | head -1))"
else
  fail "e2e 未全过, 末尾 10 行:"
  tail -10 "$SCRIPT_DIR/.e2e-acceptance.log" >&2
  echo "  (若末行显示 PASS=8 FAIL=0 却仍报未全过, 说明本脚本的判据写死了检查项个数)" >&2
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
