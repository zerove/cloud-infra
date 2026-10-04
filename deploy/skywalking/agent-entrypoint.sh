#!/bin/sh
# SkyWalking 通用 entrypoint 包装
#
# 用法 (在 docker run / compose env):
#   SW_AGENT_ENABLED=true
#   SW_AGENT_OAP=skywalking-oap:11800
#   SW_AGENT_SERVICE=notification-service
#
# Dockerfile 必须挂载 skywalking-agent 目录到 /skywalking/agent
# 若 SW_AGENT_ENABLED=false 或未设置, JVM 不带 agent 启动 (兼容现有行为)
#
# 两种启动模式自动适配:
# 1. 标准 java -jar: 有 /app/app.jar, 无 /app/entrypoint.sh
# 2. 自定义 entrypoint: 有 /app/entrypoint.sh (如 search-service 的 SERVICE_HOST_MAP)
#
# ⚠️ 关于 agent 自身日志 (2026-10-04 实测修复):
#   agent.config 的 `logging.dir` 默认空 ⇒ 日志落 /skywalking/agent/logs。
#   该目录**镜像里不存在**, 且容器以非 root (uid=100 cloudinfra) 运行, 无法创建。
#   后果: agent 的日志线程每 5s 抛一次
#     java.io.FileNotFoundException: /skywalking/agent/logs/skywalking-api.log
#     (No such file or directory)
#   实测 45s 内刷 255 行, 持续了整个容器生命周期。业务不受影响
#   (agent 日志线程是 daemon), 但**日志会把真正的错误淹没在里面**。
#
#   修法选 CONSOLE 而非"创建目录", 因为:
#     · 日志已由 podman 收集到 stdout/stderr, 再落一份文件是重复;
#     · /skywalking/agent 属 root, 非 root 进程无法在里面建目录,
#       要么改 COPY 的属主, 要么挂可写卷 —— 两者都要动 Dockerfile 的用户模型。
#   CONSOLE 的合法性不是猜的: 反编译 LogOutput.class 确认枚举只有
#   {FILE, CONSOLE} 两个值(2026-10-04 实测), 写别的值会
#   IllegalArgumentException: No enum constant。
#   负向对照: 不设 → 45s 255 行异常; 设 CONSOLE → 0 行。
#
# ⚠️ 上面那句"负向对照 … → 0 行"**当时是无效的**, 详见下方第 57 行起的注释。
#   它测的是 entrypoint 自己的 export, 而 agent 读的是进程环境变量 / JVM 属性,
#   两者不是一回事。真实异常只是从"255 行/45s"变成了"360~815 行/容器整个生命周期"
#   (异常类型从agent 写文件失败变成 FileNotFoundException), 即**没有修好**。
#   真正的修复与对照见下。

# 检测: 有自定义 entrypoint.sh 用它, 否则 java -jar
if [ -x /app/entrypoint.sh ]; then
  START_CMD="/app/entrypoint.sh"
else
  START_CMD="java \$JAVA_OPTS -jar /app/app.jar"
fi

if [ -z "${SW_AGENT_ENABLED}" ] || [ "${SW_AGENT_ENABLED}" != "true" ]; then
  echo "[skywalking] agent disabled (SW_AGENT_ENABLED=${SW_AGENT_ENABLED:-<unset>})"
  exec sh -c "$START_CMD"
fi

# 启用: 拼装 agent 路径
SW_AGENT_NAME="${SW_AGENT_SERVICE:-unknown-service}"
SW_OAP="${SW_AGENT_OAP:-skywalking-oap:11800}"
SW_AUTHENTICATION="${SW_AGENT_AUTHENTICATION:-}"
SW_AGENT_PATH="/skywalking/agent/skywalking-agent.jar"

if [ ! -f "$SW_AGENT_PATH" ]; then
  echo "[skywalking] WARN: agent jar not found at $SW_AGENT_PATH, falling back to no-agent"
  exec sh -c "$START_CMD"
fi

# agent 自身日志输出目标。
#
# ⚠️ 2026-10-04 第二次修正: 上一版修复**根本没生效**, 且形态正是本项目反复踩的
#   "机制存在 ≠ 机制在运行"。上一版写的是:
#       export SW_LOGGING_OUTPUT="${SW_LOGGING_OUTPUT:-CONSOLE}"
#   它只改了一个 **shell 变量**; 而 agent.config 第 115 行写的是
#       logging.output=${SW_LOGGING_OUTPUT:FILE}
#   读的是**进程环境变量**。两者同名但不是一回事, 实测两条通道都没拿到值:
#     · 容器内 `printenv | grep SW_LOGGING` 命中 0 条 —— export 的值没进环境;
#     · `/proc/1/environ` 的 JAVA_TOOL_OPTIONS 里也没有任何 logging 属性。
#   于是 agent 仍取默认 FILE, 而 /skywalking/agent/logs 在镜像里不存在、
#   容器又以非 root (uid=100) 运行无法创建, 实测每个业务服务持续抛
#   `FileNotFoundException: /skywalking/agent/logs/skywalking-api.log`
#   360~815 次, 刷屏整个生命周期。
#
# 正解: 把属性直接写进 JVM 参数 —— 这是 agent 能确定拿到的唯一通道。
#   `-Dskywalking.logging.output` 覆盖 agent.config 里的 ${...} 展开值;
#   属性名取自 agent.config 实际内容, 不是猜的。
SW_LOGGING_TARGET="${SW_LOGGING_OUTPUT:-CONSOLE}"

# 拼 agent opts
SW_OPTS="-javaagent:${SW_AGENT_PATH}"
SW_OPTS="${SW_OPTS} -Dskywalking.logging.output=${SW_LOGGING_TARGET}"
SW_OPTS="${SW_OPTS} -Dskywalking.agent.service_name=${SW_AGENT_NAME}"
SW_OPTS="${SW_OPTS} -Dskywalking.collector.backend_service=${SW_OAP}"
# 容器环境变量传播 (traceId 跨服务关联)
SW_OPTS="${SW_OPTS} -Dskywalking.agent.force_local_context=${SW_AGENT_FORCE_LOCAL:-true}"
# 慢 SQL / 慢方法采样阈值
SW_OPTS="${SW_OPTS} -Dskywalking.agent.slow_threshold_ms=${SW_AGENT_SLOW_THRESHOLD:-200}"

if [ -n "${SW_AUTHENTICATION}" ]; then
  SW_OPTS="${SW_OPTS} -Dskywalking.agent.authentication=${SW_AUTHENTICATION}"
fi

echo "[skywalking] agent enabled: service=${SW_AGENT_NAME} oap=${SW_OAP}"
# 关键: 把 SW_OPTS (含 -javaagent) 注入到 START_CMD 的 java 参数最前面
# 对 java -jar 形式: "java $SW_OPTS $JAVA_OPTS -jar app.jar"
# 对 entrypoint.sh 形式: 只能通过 JAVA_OPTS 注入 (但 -javaagent 要在主命令前)
# search-service 模式: 借 entrypoint.sh 设置 SERVICE_HOST_MAP 后, java -jar 时 SW_OPTS 需拼入
if echo "$START_CMD" | grep -q "java -jar"; then
  # 标准 java -jar 模式, 拼 SW_OPTS 到 java 命令前
  exec sh -c "$(echo "$START_CMD" | sed 's|java |java '"$SW_OPTS"' |')"
else
  # 自定义 entrypoint.sh 模式, 通过 JAVA_TOOL_OPTIONS 注入 (JVM 标准方式)
  export JAVA_TOOL_OPTIONS="${SW_OPTS} ${JAVA_TOOL_OPTIONS:-}"
  exec sh -c "$START_CMD"
fi
