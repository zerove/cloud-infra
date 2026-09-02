# P3-05: 4 个业务模块缺 exec classifier (v1.0.0 hotfix)

Status: open (with 4 pom.xml fix ready)
Priority: P3
Owner: AI Agent
Module: cloud-infra-gateway, cloud-infra-modules/{file,notification,search}
Depends: 无

## 背景

2026-09-02 用户报告"微服务未全部启动"。诊断发现:
- 6 个业务模块 jar 进程未启动 (部署丢失)
- 容器化方式 (`docker-compose up`) 是正确方法, 但 Dockerfile 越界 (`COPY ../../deploy/...`) 失败
- 直接用 `java -jar xxx.jar` 启动需要 `*-exec.jar` (Spring Boot fat jar with main-class)
- 之前只有 `cloud-infra-auth` 和 `cloud-infra-modules/system-module` 有 `*-exec.jar` (子 pom 加了 `<classifier>exec</classifier>`)
- **4 个模块缺 exec classifier**:
  - `cloud-infra-gateway/pom.xml`
  - `cloud-infra-modules/file-service/pom.xml`
  - `cloud-infra-modules/notification-service/pom.xml`
  - `cloud-infra-modules/search-service/pom.xml`
- 它们生成 `*.jar` (thin jar) 而非 `*-exec.jar` (Spring Boot fat jar)
- `java -jar gateway-1.0.0-SNAPSHOT.jar` 找不到 main-class manifest, 启动失败

## 修复 (本会话已做, 4 个 pom.xml)

每个模块的 `spring-boot-maven-plugin` 加 `<classifier>exec</classifier>`:

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <!-- 2026-09-02 修复: 加 exec classifier 生成 *-exec.jar -->
        <classifier>exec</classifier>
        ...
    </configuration>
    ...
</plugin>
```

## 验证

| 模块 | 修复前 | 修复后 |
|---|---|---|
| cloud-infra-gateway | `gateway-1.0.0-SNAPSHOT.jar` (thin) | `gateway-1.0.0-SNAPSHOT-exec.jar` (109 MB) ✓ |
| cloud-infra-modules/file-service | `file-service-1.0.0-SNAPSHOT.jar` (thin) | `file-service-1.0.0-SNAPSHOT-exec.jar` (128 MB) ✓ |
| cloud-infra-modules/notification-service | `notification-service-1.0.0-SNAPSHOT.jar` (thin) | `notification-service-1.0.0-SNAPSHOT-exec.jar` (155 MB) ✓ |
| cloud-infra-modules/search-service | `search-service-1.0.0-SNAPSHOT.jar` (thin) | `search-service-1.0.0-SNAPSHOT-exec.jar` (141 MB) ✓ |

## 附加问题 (同时发现)

启动 6 个微服务时还遇到:
1. **JVM args 与程序 args 顺序错**: `--enable-native-access=ALL-UNNAMED` 等 JVM 参数必须在 `-jar` 之前, `--spring.profiles.active` 等程序参数必须在 `-jar` 之后
2. **Nacos config 覆盖问题**: Nacos 配置 `spring.cloud.nacos.discovery.server-addr=cloud-nacos:8848` (容器名) 在宿主直跑时 DNS 解析失败, 需用 `SPRING_CLOUD_NACOS_*` 环境变量覆盖为 `localhost:8848`
3. **Dockerfile build context 越界** (P3 已知): `COPY ../../deploy/...` 超出 build context (`cloud-infra-auth/`), 镜像 build 失败

## 不阻塞 v1.0.0

- v1.0.0 tag `e6b786d` 已发布 (immutable)
- Docker compose 部署方式不受影响 (容器间 DNS 正常)
- 直接 `java -jar` 部署需要这个 fix + 环境变量调整

## 建议

未来 session 可考虑:
1. 合并这个 4 pom 修复到 v1.0.0 (force-push v1.0.0 tag), 或发布 v1.0.1
2. 统一父 pom 的 spring-boot-maven-plugin 加 classifier=exec, 避免子模块各自配置
3. 修 Dockerfile 越界问题
4. 文档化"宿主直跑"部署模式 (而非容器化)
