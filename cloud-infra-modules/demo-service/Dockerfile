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
ENTRYPOINT ["java", "-XX:+UseZGC", "-Xmx512m", "-XX:+UseContainerSupport", "-jar", "app.jar"]