# SkyWalking ES 冲突：方案 b 实测与两次自我更正

日期: 2026-10-03
状态: 方案 b 技术前提成立，但**受本机性能限制**，Compose 尚未改动（待用户确认）

---

## 结论速览

| 项 | 结论 | 证据 |
| :--- | :--- | :--- |
| ES 9 与 SkyWalking OAP 冲突 | **成立**，与 OAP 镜像版本无关 | OAP 连 ES9 时 `RestartCount=86`，每次 `UnsupportedOperationException: Unsupported version: ElasticSearch 9.0`（9.7.0 / 10.1.0 均复现） |
| 方案 b（独立 ES 8 给 OAP）能解决版本冲突 | **成立** | OAP 日志 `ElasticSearch version is: ElasticSearch 8.18`，**不再**出现 `Unsupported version` |
| 方案 b 能在本机持续 healthy | **未通过** | 卡在 `ResponseTimeoutException`，非版本问题 |

---

## 本轮两次自我更正（重要）

### 更正 1: "容器间网络不通" 是探针的错，不是环境的

我一度测出「从 nacos 容器访问 ES8 和 **ES9** 都返回 `000`」，并怀疑 podman 网络故障。

**真相**: 容器内继承了宿主机的 `http_proxy=127.0.0.1:7890`，而 `no_proxy` 里写的是
`10.*` —— curl 的 no_proxy 匹配的是**主机名**，`10.*` 这种通配对 `cloud-elasticsearch`
这类名字无效，于是请求全被甩给容器内不存在的代理。

修正后（清掉代理变量）实测:

```
nacos→ES9(cloud-elasticsearch): 200
nacos→ES8(elasticsearch-sw):    200
```

**教训**: `000` / `Connection refused` 先用 `curl -v` 看它到底连了谁。
代理污染会伪造出"网络故障"，且**已运行正常的服务也会中招**。

### 更正 2: "ES8 慢" 其实是整台机器慢

我测出 ES8 建索引要 10.4 / 3.8 / 11.7 秒，怀疑 ES 8.18 性能问题。

**同机 ES9 对照实验**推翻了这个假设:

| 目标 | 第1次 | 第2次 | 第3次 |
| :--- | :--- | :--- | :--- |
| ES 8.18.0 | 10.43s | 3.76s | 11.67s |
| ES 9.0.3 | 3.51s | 9.18s | 4.99s |

**两者一样慢**。根因是宿主机 `load average ≈ 7.3~8.6`，
其中一个属于**其它项目**的 `pytest` 进程长期占 62% CPU（`ai_developer_growth`，未动它）。

ES 日志佐证: `took [12809ms]` / `[14010ms]` / `[16813ms]`。

**推论**: OAP 的 ES 写入超时是**硬编码 15000ms**，
机器繁忙时 **ES9 同样会随机踩线** —— 这不是方案 b 引入的风险，
是本机环境的既有约束。因此不能据此否定方案 b，
但**也不能在本机上宣称它"验证通过"**。

---

## 方案 b 的真实状态

**技术前提已验证**: 版本冲突可由独立 ES 8 实例消除。

**未验证**: 持续 healthy。受本机 CPU 饱和影响，OAP 建索引超过其 15s 硬超时。

**复现命令**（供性能恢复后重测）:

```bash
# 1) 独立 ES 8（注意：podman run 起的容器只有 container_name，没有 compose 服务名）
podman run -d --name probe-es-sw --network cloud-infra --memory 1536m \
  -e discovery.type=single-node -e xpack.security.enabled=false \
  -e "ES_JAVA_OPTS=-Xms768m -Xmx768m" \
  docker.elastic.co/elasticsearch/elasticsearch:8.18.0

# 2) 等就绪（必须清代理，否则全是假 000）
podman exec cloud-nacos env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
  curl -s -o /dev/null -w "%{http_code}\n" http://probe-es-sw:9200/_cluster/health

# 3) OAP 指向它
podman run -d --name oap-probe-es8 --network cloud-infra --memory 1536m \
  -e SW_STORAGE=elasticsearch -e SW_STORAGE_ES_CLUSTER_NODES=probe-es-sw:9200 \
  -e SW_GRPC_HOST=0.0.0.0 \
  -v $PWD/deploy/skywalking/config/application.yml:/skywalking/config/application.yml:ro \
  apache/skywalking-oap-server:10.1.0
```

---

## 落地时必须注意的坑

1. **DNS 名**: compose 服务名（`elasticsearch-sw`）才是别名；
   `container_name` 不产生服务名。用 `podman-compose up` 才有服务名。
2. **镜像源**: `docker.elastic.co/elasticsearch/elasticsearch:8.18.0` 可用；
   `docker.io/elasticsearch:8.18.0` 被 DaoCloud 镜像源白名单拒绝
   （`this image is not in the allowlist`）。两者本地均已有缓存。
3. **内存预算**: 当前容器 `mem_limit` 合计 2.25 GB，本机可用约 11 GB。
   新增 ES 8（建议 1536m）后仍在范围内，但**余量不宽裕**。
4. **别再手动 `podman wait` 等 healthy**: 会留下空转进程持续吃 CPU
   （本次实测 44% CPU）。等待用轮询 + 真实 HTTP 探测。

---

## 尚未决定

方案 a / b / c 仍待用户选择。助手建议 b，但需明确：
**b 在本机无法完成端到端验证**（性能不足），只能证明"版本冲突被消除"。
