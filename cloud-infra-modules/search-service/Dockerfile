# 使用预构建 Jar 包的镜像模式
FROM eclipse-temurin:25-jre-alpine AS base
WORKDIR /app
RUN addgroup -S cloudinfra && adduser -S cloudinfra -G cloudinfra 
RUN chown -R cloudinfra:cloudinfra /app 
USER cloudinfra
COPY target/*.jar app.jar
# 复制 CDS 存档（不存在时忽略）
RUN cp target/*.cds /app/application.cds 2>/dev/null || true

# JVM 内存优化 + CDS 启动加速
ENV JAVA_OPTS="-XX:+UseG1GC -Xmx320m -Xms128m -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport -XX:SharedArchiveFile=/app/application.cds"

# entrypoint.sh: 等 Nacos 上 notification-service 注册, 拉其 IP, 注入 SERVICE_HOST_MAP env
# 解决容器内 'notification-service' DNS 解析到 podman 内部错误 IP 198.18.0.30
# 让 FeignHostConfig 拿到实际 IP 替换 host header (search-service 通过 Feign 调 notification-service)
COPY --chmod=755 target/classes/entrypoint.sh /app/entrypoint.sh

ENTRYPOINT ["/bin/sh", "-c", "exec /app/entrypoint.sh"]

# SkyWalking APM agent (可选) - 通过 SW_AGENT_ENABLED=true 启用
# 详见 deploy/skywalking/README.md 启用说明
# 取消下面 5 行注释 + 改 ENTRYPOINT 为 ["/app/agent-entrypoint.sh"]:
# COPY --from=apache/skywalking-java-agent:9.6.0-java17 /skywalking/agent /skywalking/agent
# COPY deploy/skywalking/agent-entrypoint.sh /app/agent-entrypoint.sh
# RUN chmod +x /app/agent-entrypoint.sh
# ENV SW_AGENT_SERVICE=MODULENAME
# ENV SW_AGENT_OAP=cloud-skywalking-oap:11800
