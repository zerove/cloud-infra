# Stage 1: Runtime Stage
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
RUN addgroup -S cloudinfra && adduser -S cloudinfra -G cloudinfra 
RUN chown -R cloudinfra:cloudinfra /app 
USER cloudinfra

# 复制编译好的 JAR (需在宿主机提前执行 mvn clean package)
#
# ⚠️ 本文件已失效, 保留仅为历史参考, 请勿用于构建 (2026-10-02 标注)。
#
# 两个原因:
#   ① 路径不成立: 构建上下文若为仓库根, 这里匹配不到任何文件 —— 各模块产物在
#      cloud-infra-<module>/target/ 下, 不在根 target/。build 必然在 COPY 处失败。
#   ② 即便路径修对, \`*.jar\` 本身也是错的写法: spring-boot-maven-plugin 的
#      repackage 会在 target/ 留下两个 jar, 多源 COPY 到单目标取**最后一个**,
#      打进镜像的会是那个无 Main-Class 的普通库 jar, 容器启动即
#      "no main manifest attribute" 后 Exited(1)。
#      这正是 cloud-infra-gateway/Dockerfile 长期存在的真实缺陷(已于 2026-10-02
#      改为 \`target/*-exec.jar\`)。注意 \`podman build\` 对两种写法都 exit=0,
#      构建期完全不觉察, 只有容器启动才暴露。
#
# 现役构建路径: 各模块自带 Dockerfile, 均已使用 \`*-exec.jar\` 精确匹配。
COPY target/*-exec.jar app.jar

# JVM 配置优化：启用 ZGC，支持高吞吐虚拟线程
# -XX:+UseZGC: 低延迟，高吞吐
# -XX:+UseVirtualThreads: 明确钉住监控
ENV JAVA_OPTS="-XX:+UseZGC -XX:+UseVirtualThreads -Xms512m -Xmx1024m -XX:+HeapDumpOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
