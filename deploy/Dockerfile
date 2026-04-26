FROM eclipse-temurin:25-jre

WORKDIR /app

ENV TZ=Asia/Shanghai

# 安装 curl 以支持健康检查
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:9000/actuator/health || exit 1

ARG JAR_FILE
COPY ${JAR_FILE} app.jar

# 使用 sh -c 以支持环境变量扩展
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
