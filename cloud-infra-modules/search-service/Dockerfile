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
ENV JAVA_OPTS="-XX:+UseZGC -Xms512m -Xmx512m -XX:+UseContainerSupport -XX:SharedArchiveFile=/app/application.cds"

# shell 形式 + exec: 使 $JAVA_OPTS 生效，同时信号正确处理
ENTRYPOINT ["/bin/sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
