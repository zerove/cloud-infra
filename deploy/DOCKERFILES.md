# 任务 1: Dockerfile 整合

## Dockerfile.native
```dockerfile
# Build Stage
FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /app
COPY . .
RUN mvn -B clean package -Pnative -DskipTests

# Final Stage
FROM debian:bookworm-slim
WORKDIR /app
COPY --from=builder /app/target/app-binary /app/app
ENTRYPOINT ["/app/app"]
```

## Dockerfile.jre
```dockerfile
# Build Stage
FROM maven:3.9.9-eclipse-temurin-25 AS builder
WORKDIR /app
COPY . .
RUN mvn -B clean package -DskipTests

# Final Stage
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"
ENTRYPOINT ["java", "-jar", "app.jar"]
```
