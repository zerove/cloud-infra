# Stage 1: Runtime
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

RUN groupadd -g 1000 appuser &&     useradd -u 1000 -g appuser -m appuser

# Copy the pre-built jar from the host target directory
ARG JAR_FILE
COPY ${JAR_FILE} app.jar

RUN chown appuser:appuser app.jar
USER appuser

ENV TZ=Asia/Shanghai
# Optimized JVM memory settings using MaxRAMPercentage
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
