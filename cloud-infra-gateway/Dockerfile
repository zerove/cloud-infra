# 使用预构建 Jar 包的镜像模式
FROM eclipse-temurin:25-jre-alpine AS base
WORKDIR /app
RUN addgroup -S cloudinfra && adduser -S cloudinfra -G cloudinfra 
RUN chown -R cloudinfra:cloudinfra /app 
USER cloudinfra
# 明确复制 repackaged fat jar，排除 .original 文件
# ⚠️ 必须用 *-exec.jar 而不是 *.jar (2026-10-02 修复)。
# spring-boot-maven-plugin 的 repackage 会在 target/ 留下**两个** jar:
#   cloud-infra-gateway-1.0.0-SNAPSHOT-exec.jar  106MB  Main-Class: JarLauncher (可执行)
#   cloud-infra-gateway-1.0.0-SNAPSHOT.jar      54KB  无 Main-Class            (普通库 jar)
# 原写法 `COPY target/*.jar app.jar` 匹配到两个源, Docker 复制多源到单目标时取**最后一个**,
# 于是打进镜像的是 54KB 的库 jar → 容器启动即
#   no main manifest attribute, in /app/app.jar
#   Exited (1)
# 证据: E2E run 37027922148 的 cloud-gateway 容器日志。
# 注意 cloud-infra-auth/Dockerfile 早已用 *-exec.jar —— 这个坑被踩过一次, 只是漏了 gateway。
COPY cloud-infra-gateway/target/*-exec.jar app.jar

# JVM 内存优化 + CDS 启动加速
# SerialGC: 小堆最省内存的 GC（~10MB 开销，远低于 ZGC 的 ~100MB）
# -Xmx256m: 微服务模式下足够处理并发请求
# UseContainerSupport: 自动感知容器内存限制
ENV JAVA_OPTS="-XX:+UseG1GC -Xmx256m -Xms128m -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport"

# shell 形式 + exec: 使 $JAVA_OPTS 生效，同时信号正确处理
# SkyWalking APM agent - 通过 SW_AGENT_ENABLED=true 启用 (默认 false 不影响现状)
COPY --from=apache/skywalking-java-agent:9.6.0-java17 /skywalking/agent /skywalking/agent
COPY deploy/skywalking/agent-entrypoint.sh /app/agent-entrypoint.sh
ENV SW_AGENT_SERVICE=cloud-infra-gateway
ENV SW_AGENT_OAP=cloud-skywalking-oap:11800
ENV SW_AGENT_ENABLED=true

# 启用 SkyWalking agent: 设置 SW_AGENT_ENABLED=true (compose 配), entrypoint.sh 自动启用字节码增强
ENTRYPOINT ["/app/agent-entrypoint.sh"]
