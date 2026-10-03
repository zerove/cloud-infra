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

## 二、JVM 崩溃归因：四次尝试，最终仍未定位

现象: 业务服务启动过程中崩溃，日志为

```
# There is insufficient memory for the Java Runtime Environment to continue.
# Native memory allocation (malloc) failed to allocate 65560 bytes
# Out of Memory Error (os_linux.cpp:3000), pid=1, tid=4
```

> ⚠️ **本节结论已被后续实测部分推翻，保留在此是为了记录"我错在哪"。**
> 真正的定位见第三节。

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

**结论（当时）: 崩溃由本机 CPU 饱和导致。**
因此未对 `deploy/compose.yaml` 做任何 JVM 参数改动（已 `git checkout` 还原）。

---

## 三、后续实测: "CPU 饱和" 同样不成立

CPU 负载从 14.9 降到 ~7 后重跑，**崩溃依旧**，且呈现稳定规律。

### 关键反证: `cloud-system` 的内存曲线完全健康

单独启动 `cloud-system`（`mem_limit=2048MB`），每 15 秒采样:

```
t+15s   511.9MB / 2.147GB
t+60s   713.6MB
t+105s  891.6MB
t+120s  897.3MB
t+180s  901.1MB
t+240s  902.9MB      ← 4 分钟内零波动, 进程始终 running
```

**稳态内存只用到 limit 的 44%，且完全平稳。**
若是内存问题，不可能出现这种"稳定在 900MB 却不崩"的曲线。

### 真正的规律: 先启动的先崩溃

无论并行还是串行，规律一致 —— **启动顺序靠前的服务崩溃，靠后的存活**:

| 启动顺序 | 服务 | 结果 |
| :--- | :--- | :--- |
| 1 | system | exited（内存崩溃 ×1） |
| 2 | file | exited（内存崩溃 ×1） |
| 3 | search | exited（内存崩溃 ×1） |
| 4 | notification | **running / healthy** |
| 5 | auth | **running / healthy** |
| 6 | gateway | running |

串行启动（每次等 125 秒）**并未解决问题** —— 只是把崩溃顺序往后推。

### 当前状态

**6 个服务从未同时健康过。** 已尝试并失败的路径:

| 尝试 | 结果 |
| :--- | :--- |
| 6 服务并行 | 4 崩 2 活 |
| 加 `-XX:MaxRAM` | 单服务成立，6 并行仍崩 |
| 关 `UseCompactObjectHeaders` | 仍崩 |
| 完全移除 `mem_limit` | 仍崩 |
| 串行启动 | 仍崩（仅顺序变化） |
| 等 CPU 负载下降 | 仍崩 |

**未定位。** 已停止试错 —— 继续猜参数缺乏依据。
仓库配置未做任何改动（`deploy/compose.yaml` 与 HEAD 一致）。

---

## 四、根因: podman rootless 下 `/proc/meminfo` 未隔离

在运行中的容器里直接读:

```
容器内 MemTotal:      20,480,648 kB      ← 20GB, 这是**宿主的值**
容器内 MemAvailable:  11,997,904 kB
容器内 cgroup max:      469,762,048       ← 448MB, 这是**真实限制**
```

**JVM 看到的是 20GB，按 20GB 规划**线程栈、元空间、代码缓存、
arena 数量 —— 这些都远超 448MB 的真实上限，于是
`Native memory allocation (malloc) failed` 并最终
`os_linux.cpp:3000` (OS 层线程栈分配失败)。

`-XX:+UseContainerSupport` 本应修正这个偏差，但实测无效
（它依赖的 `/proc/meminfo` 或 cgroup 接口在 podman rootless 下不可靠)。

这解释了此前所有现象:

| 现象 | 解释 |
| :--- | :--- |
| 单服务能跑 | 20GB 规划出来的元空间暂时没撑爆 448MB |
| 多服务时先启动的先崩 | 越早启动的 JVM 越早进入"按 20GB 规划"状态, 越早撑爆 |
| `system` 稳态只用 903MB 却仍崩 | 崩溃发生在**启动期规划阶段**, 不是稳态 RSS |
| 崩溃信息说"memory"但宿主机有 11GB 可用 | 报错基于错误的内存视图 |

### 修复方向（未实施）

显式告诉 JVM 真实上限, 即加 `-XX:MaxRAM=<mem_limit>`。
实测**单服务成立**（auth 加 `-XX:MaxRAM=448m` 后稳定 3 分钟,
去掉则 60 秒崩溃），但**多服务并行时仍崩** ——
说明还有第二个因素未被隔离, 因此未写入仓库。

**正确的修法应从容器运行时层面解决**（如给容器注入正确的
`/proc/meminfo` 视图, 或统一在 JAVA_OPTS 中显式声明 MaxRAM），
而不是逐个服务调参 —— 后者在 6 服务场景下已被证明不充分。

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
