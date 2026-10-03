# 本机 E2E 环境重建与 JVM 崩溃归因（2026-10-03 第二轮）

承接 `2026-10-03-sw-es-conflict-and-plan-b.md`。本轮完成了环境重建，
并在 6 服务并行启动时遇到新的崩溃，逐项做了受控实验。

---

## 一、环境重建（已完成）

podman 的 `healthcheck.log` 出现 **0 字节截断**文件，导致 `podman ps` /
`podman rm` 全面报错（exit 125），且报`no space left on device` ——
但磁盘 32%、inode 1% 均空闲，是**误导性报错**。

修复顺序（每步都由podman 自身命令完成，不手工删文件）:

```bash
podman stop --all --time 5      # 必须先全停, 否则 podman-compose down 会因
podman pod rm -f --all          # "container state improper" 失败
podman rm -f --all
```

重建后基础设施真实可用（`curl --noproxy` 实测，非 healthcheck 标签）:

| 服务 | 探测 | 结果 |
| :--- | :--- | :--- |
| nacos | `GET /nacos` | 302 |
| postgres | `pg_isready` | accepting connections |
| redis | `redis-cli ping` | PONG |
| rabbitmq | `rabbitmq-diagnostics ping` | Ping succeeded |
| elasticsearch | `GET /_cluster/health` | 200 |

---

## 二、JVM 崩溃归因：三次假设被实测推翻

现象: 6 个业务服务并行启动时 4 个崩溃，日志为

```
# There is insufficient memory for the Java Runtime Environment to continue.
# Native memory allocation (malloc) failed to allocate 7127768 bytes
# Out of Memory Error (os_linux.cpp:3000), pid=1, tid=4
```

### 假设 1: `mem_limit` 太小 → 加 `-XX:MaxRAM`

依据:容器内 `free` 显示**宿主的 20GB**（podman rootless 未隔离
`/proc/meminfo`），而 cgroup 限制只有 448 MiB，`UseContainerSupport` 因此失效。

单服务对照（`cloud-auth`，其余不动）:

| 配置 | 结果 |
| :--- | :--- |
| `-Xmx384m -XX:MaxRAM=448m` | 持续 healthy 3 分钟，无崩溃 |
| 去掉 `-XX:MaxRAM=448m` | **60 秒内崩溃** |

⇒ 单服务成立。但推广到 6 服务并行 →仍崩。**假设不完整。**

### 假设 2: `CompressedClassSpaceSize` 固定 1GB 超限 → 关 `UseCompactObjectHeaders`

依据:崩溃报告实测

```
Compressed class space mapped ... reserved size: 1073741824   # 1.00 GB
Metaspace        reserved 1114112K                            # 1.06 GB
Class space: 1.00 GB reserved
```

1GB 的 class space 映射确实远超 448 MiB 的 `mem_limit`。
去掉 `-XX:+UseCompactObjectHeaders` 后 → 仍崩 `[eerree]`。

### 假设 3: 与 `mem_limit` 无关 → 全部去掉再测

**完全移除所有 `mem_limit`**（实测 `mem=0`）后 → **仍崩** `[eerree]`。

⇒ 这一步推翻了前两步的前提。`mem_limit` 不是根因。

### 真正的线索

同时观察到:

```
load average: 14.91, 13.55, 11.08     # 饱和
3 个 rustc 进程各占 100% CPU            # Rust 编译, 非本项目
```

而宿主机内存始终充裕（available ≈ 11.5 GB，容器 RSS 合计仅 4.8 GB）。

`os_linux.cpp:3000` 在**线程栈分配失败**时同样出现 ——
CPU 饱和下JVM 创建线程超时，是比"内存不足"更贴合全部证据的解释。

**结论: 当前崩溃由本机 CPU 饱和导致，不是仓库配置问题。**
因此**未对 `deploy/compose.yaml` 做任何 JVM 参数改动**（已 `git checkout` 还原）。

---

## 三、附带确认: OAP 依赖回退有效（提交 `8d25b2ee`）

`cloud-system` 在 OAP 完全 Exited 时仍能 `Started SystemApplication in 104.218 seconds`，
`/management/health/readiness` 返回结构化 401（鉴权正常）。

单服务逐一启动时，`system` / `file` / `search` / `notification` / `auth` / `gateway`
**都能成功启动并注册到 Nacos**。⇒ OAP 不是业务启动前提，回退正确。

---

## 四、待解决（按优先级）

1. **本机 CPU 饱和**（rustc 编译 + 早前的 pytest）——
   这是当前 6 服务并行的唯一障碍，属环境问题，需等负载下降。
2. **方案 a/b/c 仍待选择** —— ES 9 与 SkyWalking 的冲突客观存在，
   门禁 `check_sw_oap_config.sh` 仍以 `exit 1` 正确报出。
3. **推送仍被阻断** —— 本地代理对 `github.com` 分流失效，
   四个提交（`5ec63718` / `601eb759` / `8d25b2ee`）停留在本地。

---

## 五、方法论备忘

- `podman network inspect` 的 `Containers` 可能为空且与实际不符，
  判断连通性应直接 `curl` 探针而非读 podman 元数据。
- 探针报`000` 时先用 `curl -v` 确认它连了谁 —— 代理环境变量会伪造故障。
- 单服务对照成立 ≠ 多服务成立。本轮三次假设都是
  "单服务修好 → 推广 → 仍崩"，最后靠**去掉前提本身**才找到正确方向。
