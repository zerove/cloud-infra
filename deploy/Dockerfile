FROM eclipse-temurin:25-jre

WORKDIR /app

ENV TZ=Asia/Shanghai

ARG JAR_FILE
COPY ${JAR_FILE} app.jar

# 使用 sh -c 以支持环境变量扩展
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
