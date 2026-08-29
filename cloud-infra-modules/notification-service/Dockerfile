# 使用预构建 Jar 包的镜像模式
# Build context 期望为 notification-service 根目录 (含 target/)
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

# entrypoint.sh: 等 Nacos 上 system-module 注册, 拉其 IP, 注入 SERVICE_HOST_MAP env
# 解决容器内 'system-module' DNS 解析到 podman 内部错误 IP 198.18.0.30 的问题
# 让 FeignHostConfig 拿到 system 实际 IP 替换 host header
COPY --chmod=755 target/classes/entrypoint.sh /app/entrypoint.sh

ENTRYPOINT ["/bin/sh", "-c", "exec /app/entrypoint.sh"]
