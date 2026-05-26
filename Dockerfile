# Stage 1: Runtime Stage
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

# 复制编译好的 JAR (需在宿主机提前执行 mvn clean package)
COPY target/*.jar app.jar

# JVM 配置优化：启用 ZGC，支持高吞吐虚拟线程
# -XX:+UseZGC: 低延迟，高吞吐
# -XX:+UseVirtualThreads: 明确钉住监控
ENV JAVA_OPTS="-XX:+UseZGC -XX:+UseVirtualThreads -Xms512m -Xmx1024m -XX:+HeapDumpOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
