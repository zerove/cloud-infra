# 使用支持 Java 25 的官方基础镜像
FROM eclipse-temurin:25-jre-alpine AS builder
WORKDIR /app
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
# Spring Boot 4 分层解压
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# 复制分层文件
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/spring-boot-loader/ ./
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
COPY --from=builder /app/extracted/application/ ./

# 暴露端口，使用环境变量配置
EXPOSE 8080

# 优化 JVM 参数，启用 ZGC 和虚拟线程钉住监控
ENV JAVA_OPTS="-XX:+UseZGC -Djdk.tracePinnedThreads=short -Xms512m -Xmx512m -XX:+HeapDumpOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} org.springframework.boot.loader.launch.JarLauncher"]
