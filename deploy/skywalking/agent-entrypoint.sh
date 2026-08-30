# SkyWalking 共享配置 - 业务模块 JVM agent 挂载点
#
# 用法 (在 docker run / compose env):
#   SW_AGENT_ENABLED=true
#   SW_AGENT_OAP=skywalking-oap:11800
#   SW_AGENT_SERVICE=notification-service
#
# Dockerfile 必须挂载 skywalking-agent 目录到 /skywalking/agent
# 若 SW_AGENT_ENABLED=false 或未设置, JVM 不带 agent 启动 (兼容现有行为)

if [ -z "${SW_AGENT_ENABLED}" ] || [ "${SW_AGENT_ENABLED}" != "true" ]; then
  echo "[skywalking] agent disabled (SW_AGENT_ENABLED=${SW_AGENT_ENABLED:-<unset>})"
  exec java $JAVA_OPTS -jar app.jar
fi

# 启用: 拼装 agent 路径
SW_AGENT_NAME="${SW_AGENT_SERVICE:-unknown-service}"
SW_OAP="${SW_AGENT_OAP:-skywalking-oap:11800}"
SW_AUTHENTICATION="${SW_AGENT_AUTHENTICATION:-}"
SW_AGENT_PATH="/skywalking/agent/skywalking-agent.jar"

if [ ! -f "$SW_AGENT_PATH" ]; then
  echo "[skywalking] WARN: agent jar not found at $SW_AGENT_PATH, falling back to no-agent"
  exec java $JAVA_OPTS -jar app.jar
fi

# 拼 agent opts
SW_OPTS="-javaagent:${SW_AGENT_PATH}"
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
exec java $SW_OPTS $JAVA_OPTS -jar app.jar
