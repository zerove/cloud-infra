# 使用预构建 Jar 包的镜像模式
FROM eclipse-temurin:25-jre-alpine AS base
WORKDIR /app
RUN addgroup -S cloudinfra && adduser -S cloudinfra -G cloudinfra 
RUN chown -R cloudinfra:cloudinfra /app 
USER cloudinfra
COPY cloud-infra-modules/search-service/target/*-exec.jar app.jar

# JVM 内存优化 + CDS 启动加速
ENV JAVA_OPTS="-XX:+UseG1GC -Xmx256m -Xms128m -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport"

# entrypoint.sh: 等 Nacos 上 notification-service 注册, 拉其 IP, 注入 SERVICE_HOST_MAP env
# 解决容器内 'notification-service' DNS 解析到 podman 内部错误 IP 198.18.0.30
# 让 FeignHostConfig 拿到实际 IP 替换 host header (search-service 通过 Feign 调 notification-service)
COPY --chmod=755 cloud-infra-modules/search-service/target/classes/entrypoint.sh /app/entrypoint.sh

# SkyWalking APM agent - 通过 SW_AGENT_ENABLED=true 启用 (默认 false 不影响现状)
COPY --from=apache/skywalking-java-agent:9.6.0-java17 /skywalking/agent /skywalking/agent
COPY deploy/skywalking/agent-entrypoint.sh /app/agent-entrypoint.sh
ENV SW_AGENT_SERVICE=search-service
ENV SW_AGENT_OAP=cloud-skywalking-oap:11800
ENV SW_AGENT_ENABLED=true

# 启用 SkyWalking agent: 设置 SW_AGENT_ENABLED=true (compose 配), agent-entrypoint.sh 内部 exec 原 entrypoint.sh
ENTRYPOINT ["/app/agent-entrypoint.sh"]
