# 使用预构建 Jar 包的镜像模式
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# 明确复制 repackaged fat jar，排除 .original 文件
COPY target/cloud-infra-gateway-*.jar app.jar
# 复制 CDS 存档（不存在时忽略）
RUN cp target/*.cds /app/application.cds 2>/dev/null || true

# JVM 内存优化 + CDS 启动加速
# SerialGC: 小堆最省内存的 GC（~10MB 开销，远低于 ZGC 的 ~100MB）
# -Xmx256m: 微服务模式下足够处理并发请求
# UseContainerSupport: 自动感知容器内存限制
ENV JAVA_OPTS="-XX:+UseSerialGC -Xmx256m -Xms128m -XX:+UseContainerSupport -XX:SharedArchiveFile=/app/application.cds"

# shell 形式 + exec: 使 $JAVA_OPTS 生效，同时信号正确处理
ENTRYPOINT ["/bin/sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
