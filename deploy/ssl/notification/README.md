# 通知服务 TLS 部署

## 直接终止 TLS（本次连通的部署路径）

在仓库根目录执行。先安全注入 `SSL_KEYSTORE_PASSWORD` 环境变量，至少 6 字符，无默认密码。

```bash
./deploy/ssl/generate-notification-cert.sh
podman-compose -f deploy/compose.yaml -f deploy/compose.notification-tls.yaml --profile full config
```

证书脚本要求 JDK 的 `keytool`，默认输出到 `deploy/ssl/notification/`，拒绝覆盖已有证书。
`notification.p12` 以只读方式挂载到 `/etc/ssl/notification/notification.p12`。
覆盖文件通过 Spring 标准 `SERVER_SSL_*` 环境变量启用 TLS，因此不依赖基础编排默认的 `dev` 配置切换为 `prod`。
业务端点为 `wss://localhost:8084/notification/ws`，管理检查仍为容器内 `http://localhost:9301/management/health/readiness`。
确认配置后由运维按既有发布流程更新通知服务。本次任务未启动、停止或重建现有基础设施。

开发自签证书仅供本机测试。正式发布应将受信任 CA 证书和对应私钥导入同名密钥库，并保持别名、密码与环境变量一致。
必须保证容器运行用户能够读取宿主机权限为 600 的密钥库，不要用放宽权限的方式修复挂载失败。
如使用 `application-prod.yml`，需提供 `SSL_KEYSTORE_PASSWORD`，可选 `SSL_KEYSTORE_PATH` 与 `SSL_KEY_ALIAS`。

## 可选 Nginx 终止

不与直接 TLS 覆盖文件同时使用。通知服务内部上游为 `http://notification:8084`，必须明确关闭上游 `server.ssl.enabled`。

- `deploy/ssl/notification/nginx.conf` 挂载到 `/etc/nginx/nginx.conf`。
- `deploy/nginx/notification-wss.conf` 挂载到 `/etc/nginx/conf.d/notification-wss.conf`。
- 另行提供 CA 证书和 PEM 私钥，分别挂载到 `/etc/nginx/ssl/notification/server.crt` 和 `server.key`。
- `generate-certs.sh` 只是统一密钥库生成入口，不生成 PEM 私钥。
- 代理必须加入通知服务所在网络。健康检查保留内部管理端口，不经公开代理。

该可选配置尚未执行 `nginx -t` 或运行时代理验证，当前环境无 Nginx。不是已验证的生产部署方案。

## 身份信任边界

TLS 只加密传输，不认证 `X-User-Id` 的来源。生产过滤器依赖可信代理注入身份，通知服务与可选 Nginx 入口都不能直接暴露给可伪造身份头的公网客户端。
需要部署侧限制来源并由可信网关完成鉴权。浏览器原生 WebSocket 不能自行添加这些头，完整浏览器到网关的升级链路不在本次验证范围。

## 验证边界

`WebSocketIntegrationTest` 使用上述部署证书脚本、Spring 随机 TLS 端口和真实的 `WebSocketConfig`、`WebSocketAuthFilter`、`WebSocketAuthConfigurator`、`NotificationWebSocketHandler`。
客户端只信任生成的证书，不关闭证书或主机名检查，断言缺失身份返回 401、合法头连接、心跳响应、用户推送及连接清理。
这是隔离组件集成测试，不是手工替代服务，也不是生产容器端点验收。完整安全过滤链、Nacos、网关和实际容器挂载尚未运行时验证。

```bash
mvn -pl cloud-infra-modules/notification-service test \
  -Dtest=WebSocketIntegrationTest,WebSocketAuthFilterTest,WebSocketAuthConfiguratorTest
```
