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

# shell 形式 + exec: 使 $JAVA_OPTS 生效，同时信号正确处理
# SkyWalking APM agent - 通过 SW_AGENT_ENABLED=true 启用 (默认 false 不影响现状)
COPY --from=apache/skywalking-java-agent:9.6.0-java17 /skywalking/agent /skywalking/agent
COPY deploy/skywalking/agent-entrypoint.sh /app/agent-entrypoint.sh
ENV SW_AGENT_SERVICE=file-service
ENV SW_AGENT_OAP=cloud-skywalking-oap:11800
ENV SW_AGENT_ENABLED=true

# 启用 SkyWalking agent: 设置 SW_AGENT_ENABLED=true (compose 配), entrypoint.sh 自动启用字节码增强
ENTRYPOINT ["/app/agent-entrypoint.sh"]
