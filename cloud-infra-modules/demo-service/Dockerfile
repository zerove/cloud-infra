# 构建阶段
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN addgroup -S cloudinfra && adduser -S cloudinfra -G cloudinfra
RUN chown -R cloudinfra:cloudinfra /app
USER cloudinfra
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8099
# JVM 参数走环境变量，部署方可按需覆盖（默认与 compose 其他服务对齐）
ENV JAVA_OPTS="-XX:+UseG1GC -Xmx256m -Xms128m -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport"
# shell 形式 + exec: 使 $JAVA_OPTS 生效，同时信号正确处理
ENTRYPOINT ["/bin/sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]