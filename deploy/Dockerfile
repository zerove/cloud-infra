# Stage: Run
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

# Create a non-root user
RUN groupadd -g 1000 appuser && \
    useradd -u 1000 -g appuser -m appuser

# Copy the jar from the host target directory
ARG JAR_FILE
COPY ${JAR_FILE} app.jar

RUN chown appuser:appuser app.jar
USER appuser

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-Xms512m -Xmx512m -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
