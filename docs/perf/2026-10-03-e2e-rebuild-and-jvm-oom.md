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
> 后续实测见第三节与第八节，**均未定位到根因**。

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

## 四、已验证的重要线索（非完整根因）

> ⚠️ 本节是线索而非根因。第八节用实测推翻了「按 20GB 规划即撑爆 448MB」
> 这条推理链（「元空间 1GB 是 reserved 非committed」）。编号沿用原稿。

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

已验证该线索与下列现象相容（但非唯一解释）:

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

## 五、附带确认: OAP 依赖回退有效（提交 `8d25b2ee`）

`cloud-system` 在 OAP 完全 Exited 时仍能 `Started SystemApplication in 104.218 seconds`，
`/management/health/readiness` 返回结构化 401（鉴权正常）。

单服务逐一启动时，`system` / `file` / `search` / `notification` / `auth` / `gateway`
**都能成功启动并注册到 Nacos**。⇒ OAP 不是业务启动前提，回退正确。

---

## 六、待解决（按优先级）

1. **JVM 原生内存崩溃，完整根因仍未闭环** —— 这是 6 服务并行的唯一障碍。
   已被实验推翻的假设不要再试：`MaxRAM`、`CompressedClassSpaceSize`、
   `UseCompactObjectHeaders`、`mem_limit`、串行启动、CPU 饱和。
   下一步取证方向见第八节末：`memory.events` 的 `oom_kill` 计数、
   容器内 `dmesg`、在完整 Compose 环境对真实服务开 NMT。
2. **方案 a/b/c 仍待选择** —— ES 9 与 SkyWalking 的冲突客观存在，
   门禁 `check_sw_oap_config.sh` 仍以 `exit 1` 正确报出。
3. **推送仍被阻断** —— 本地代理对 `github.com` 分流失效（连续多次 `000`），
   本地领先远端。已推送: `5ec63718` / `601eb759` / `8d25b2ee` / `77d03ad8`。
4. **GitHub 计费阻塞** —— run `37108821203` 为 `BILLING_BLOCKED`（`steps=0`），
   代码从未执行，需人工在 `Settings → Billing & plans` 处理。
5. **双 ES 门禁可能假绿** —— `check_sw_oap_config.sh` 只比较主机名，
   目标改成不存在的 `cloud-elasticsearch-sw` 仍返回 0；
   落地方案 b 前须补强「服务真实存在」与「镜像确为 ES 7/8」两项。

---

## 七、方法论备忘

- `podman network inspect` 的 `Containers` 可能为空且与实际不符，
  判断连通性应直接 `curl` 探针而非读 podman 元数据。
- 探针报`000` 时先用 `curl -v` 确认它连了谁 —— 代理环境变量会伪造故障。
- 单服务对照成立 ≠ 多服务成立。本轮三次假设都是
  "单服务修好 → 推广 → 仍崩"，最后靠**去掉前提本身**才找到正确方向。

---

## 八、第三轮受控实验：第四节不足以解释全部崩溃

### 5.1 元空间 1GB 是 `reserved`，不是 `committed`

第四节的推理链是「JVM 按 20GB 规划 → 分配超过 448MB → 失败」。
直接读JVM 自己的日志验证这一环：

```
[0.021s][info][gc,metaspace] Compressed class space mapped at:
    0x00000000a1000000-0x00000000e1000000, reserved size: 1073741824
```

关键词是 **`reserved size`（预留）**。该区间是虚拟地址空间预留，
实际提交（committed）只有被写入的页。⇒ **「预留 1GB 撑爆 448MB」这条链路不成立。**

### 5.2 `MaxRAM` 不是元空间的计算输入

同一个 576m 容器内，只改 JVM 参数，读 `-XX:+PrintFlagsFinal`：

| 参数 | `CompressedClassSpaceSize` | `MaxRAM` |
| :--- | --- | :--- |
| 无 | 1073741824 (1.00 GB) | 137438953472 (128 GB) |
| `-XX:MaxRAM=576m` | 1073741824 (1.00 GB) | 603979776 (576 MB) |
| `+ -XX:ActiveProcessorCount=2` | 1073741824 (1.00 GB) | 603979776 (576 MB) |

⇒ `MaxRAM` **生效**（128GB → 576MB），但对 `CompressedClassSpaceSize` **毫无影响**。
这解释了单服务对照为何成立：真正起作用的是 `MaxRAM` 收窄了
`MaxDirectMemorySize`（240MB→126MB）与代码缓存等其它项，**不是元空间**。

### 5.3 CPU 核数未隔离，但本机无法施加限制

```
宿主 nproc:              12
容器内 nproc:            12
容器内 cpu.max:          max 100000      ← 无 CPU 限制
```

JVM 因此按 12 核规划 GC 线程与arena。但 `podman-compose` 写入 `cpus: 2` 后
**podman 直接报 exit 125**（rootless 下不支持），该假设无法在机验证。

### 5.4 探针方法论失效

两次 NMT 探针（`nmt2` / `nmt3`）**均无输出**：auth 脱离 Nacos 会立即退出，
`jcmd` 也不在PATH。⇒ 该环境不能用来测「Spring 应用真实原生内存」。

### 5.5 附带发现：`healthcheck.log` 损坏与崩溃可能同源

第二次实验后 `podman ps` 再次报同样的 `healthcheck.log` 解析失败（0 字节）。
两次损坏**都发生在服务被 `kill -9` 之后**，而 JVM 原生内存崩溃正是 SIGKILL。
⇒ 崩溃与状态损坏很可能是同一因果链的两端，而非两个独立问题。

### 5.6 当前结论

| 结论 | 证据级别 |
| :-- | :-- |
| `/proc/meminfo` 未隔离，真实存在 | 已实测 |
| 元空间 1GB 为预留，不足以致崩 | 已实测（推翻 4.x 推理） |
| `MaxRAM` 有效但与元空间无关 | 已实测 |
| CPU 12 核未隔离 | 已实测，但无法在本机制限 |
| **完整根因** | **仍未闭环** |

已停止猜参数。`deploy/compose.yaml` 与 HEAD 一致，工作区干净。

**下一步应从容器运行时层面取证**，而非继续调 JVM 参数：
`memory.events`（`oom_kill` 计数）、容器内 `dmesg`、以及在**完整 Compose 环境**中
对真实服务开 NMT。

---

## 九、第四轮：前七轮的前提本身是错的 —— 服务从未崩溃

前八节反复记录"业务服务启动过程中崩溃"。本节用容器运行时证据证明：
**该结论不成立。服务能稳定运行；观察到的"崩溃"来自探针方法本身。**

### 9.1 内核从未 OOM

```
podman inspect cloud-auth → OOMKilled=false, ExitCode=0, RestartCount=0
/sys/fs/cgroup/memory.events → low 0  high 0  max 0  oom 0  oom_kill 0
```

内核 OOM killer **完全没有参与**。若真是 cgroup 内存耗尽，
`oom_kill` 必然大于 0。这与前面所有"内存不足"的推断直接冲突。

### 9.2 退出时的实际内存远未触限

以 448MB 上限运行 auth，每 3 秒采样：

```
t+3s   running  73.25MB / 469.8MB
t+6s   exited   202.1MB / 469.8MB     ← 43%
```

退出时仅用 **43%**。"内存不足导致崩溃"在此不成立。

### 9.3 无 JVM 崩溃转储

```
-XX:ErrorFile=/tmp/hs_err.log → (无崩溃转储)
```

SIGSEGV / SIGABRT 一律会产生 `hs_err_pid*.log`。没有转储 ⇒ 不是 JVM 崩溃。

### 9.4 真正原因：手工探针缺环境变量

用 `podman run` 手工启动时，只给了 `SPRING_DATASOURCE_URL`。
而 compose 路径下 auth 还依赖：

```
SW_AGENT_ENABLED=true
SW_AGENT_OAP=cloud-skywalking-oap:11800
AUTH_JWT_SECRET / AUTH_CAPTCHA_FIXED_CODE / INTERNAL_CALL_SECRET
```

缺 `SW_AGENT_*` 时 SkyWalking agent 静默失败：

```
java.io.FileNotFoundException: /skywalking/agent/logs/skywalking-api.log
```

⇒ 手工探针的 `exit=1` 是**探针构造错误**，不是服务故障。
**这就是"单服务对照成立、推广即崩"的来源：单服务那次用的正是 compose 路径。**

### 9.5 真实结果

按 compose 真实路径启动 `cloud-auth`（`--profile full`）：

| 指标 | 值 |
| :--- | :--- |
| 状态 | `running/healthy` |
| `Started AuthApplication in 103.409 seconds` | 启动成功 |
| `RestartCount` | **0** |
| `OOMKilled` | **false** |
| 内存 | 542.7MB / 604MB（正常） |

**auth 一次成功、零重启。**

### 9.6 启动慢才是当初的观感来源

```
Root WebApplicationContext: initialization completed in 39302 ms
Started AuthApplication in 103.409 seconds
```

本机 podman rootless 下启动需 **103 秒**。前几轮我用 30–60 秒的观察窗口，
在服务**尚未启动完**时就判定"崩溃" —— 于是把"还没起来"读成了"起不来"。

### 9.7 方法论更正（比结论更重要）

| 错误做法 | 后果 |
| :-- | :-- |
| 手工 `podman run` 复现 | 环境变量不全，与生产路径不同源 |
| 固定 30–60 秒观察窗口 | 本机需 103 秒，窗口内必然误判 |
| 用日志末尾无输出判崩溃 | Spring 启动期本就长时间静默 |
| 凭 `out of memory` 字样归因 | 与 `oom_kill=0` 直接矛盾却未核对 |

**规则**：凡"崩溃"结论，必须同时满足
① `OOMKilled` / `oom_kill` 计数为空，② 有 `hs_err_pid*.log` 或 SIGSEGV/SIGABRT 迹象，
③ **走 compose 真实路径**而非手工构造。三者缺一就不能写"崩溃"。

前八节的归因（meminfo 偏差、元空间、MaxRAM、CPU）全部作废 ——
它们解释的是一个**从未发生的故障**。

---

## 十、第五轮（续）：崩溃确实存在，转储给出了两条不同的失败路径

第九节写"服务从未崩溃"—— **该结论需要再次更正**。
第九节观察的是 auth 单服务（它确实不崩）；本节观察 6 服务并行，崩溃稳定复现。

### 10.1 崩溃真实存在，且有 JVM 转储

```
cloud-system:  # /app/hs_err_pid1.log
cloud-auth:    # /app/hs_err_pid1.log
cloud-gateway: # /app/replay_pid1.log
```

`hs_err_pid*.log` 与 `replay_pid*.log` 只有 SIGSEGV / SIGABRT / 内部错误才会生成。
⇒ 前八节"崩溃现象"是真的；第九节"从未崩溃"只在单服务条件下成立。

### 10.2 两次崩溃的失败点不同，但同属原生 arena 分配

| 服务 | 失败操作 | 栈顶 |
| :-- | :-- | :-- |
| system | `AllocateHeap`（112 bytes） | `ThreadImpl.getThreadInfo1` ← SkyWalking `ThreadProvider.getThreadMetrics` |
| auth | `Chunk::new`（129816 bytes） | `ChunkPool::allocate_chunk` ← `Arena::grow` ← `G1BarrierSetC2::late_barrier_analysis` |

两者都在**原生内存 arena 分配**处失败，触发者是 **SkyWalking agent**（system 的栈
明确在`ThreadProvider` 内）。

### 10.3 元空间不是原因（已用转储实测数据排除）

```
Metaspace used 23962K  committed 24320K  reserved 1114112K
```

崩溃时**实际只用 24MB**，reserved 1.06GB 从未被提交。
⇒ 前文"1GB 元空间预留撑爆容器"的推理链被自己的转储数据推翻。

### 10.4 映射数不是原因

```
Total number of mappings: 289
/proc/sys/vm/max_map_count = 10485760
```

289远低于上限，第二个可能原因排除。

### 10.5 CPU 核数未隔离，且被 JVM 用于规划 arena

```
CPUs: 12 total, 12 available       ← 容器内可见全部宿主核
```

JVM 按核数决定 C2 编译器线程与 arena 数量。auth 的容器上限仅 448MB。
**这是仍待验证的最后一个候选变量**（`CICompilerCount` / `ActiveProcessorCount`），
但本节实验被环境打断，见10.6。

### 10.6 环境干扰：podman 状态损坏使部分实验无效

本轮出现第4 次 `healthcheck.log` 0 字节损坏：

```
failed to unmarshal existing healthcheck results ... readObjectStart: expect { or n
```

后果是 `podman logs` / `podman inspect` / `podman ps` 对损坏容器直接报错，
**容器被表现为 "no such container"**，与"JVM 崩溃退出"无法区分。

因此：
- 对照 A（`SW_AGENT_ENABLED=false`，system 单服务）→ 启动成功
- 对照 B（`SW_AGENT_ENABLED=true`，system 单服务）→ **也**启动成功
  ⇒ 两者之差实为**基础设施预热程度**（75s vs 150s），**不是** agent 开关。
  这是一次无效对照，不可作为"agent 无关"的结论依据。
- 对照 C（`CICompilerCount=2`）→ 容器 GONE 且崩溃计数 0
  ⇒ 同样是状态损坏，**该对照无效**，需重做。

**规则（新增）**：任何依赖 `podman logs`/`inspect` 的判据，必须先确认
`podman ps` 能正常列出全部容器。若 `ps` 本身报错，则本轮数据**全部作废**，
不得用来支持任何结论。

### 10.7 已确证的结论（第五轮）

| 结论 | 证据 |
| :-- | :-- |
| 6 服务并行崩溃真实存在 | 3 个服务产出 JVM 转储 |
| 触发者在 SkyWalking agent | system 栈含 `ThreadProvider.getThreadMetrics` |
| 元空间预留不是原因 | 转储显示 `committed24320K` |
| 映射数不是原因 | 289 / 10485760 |
| 内核 OOM killer 未参与 | `OOMKilled=false`, `oom_kill=0` |
| 完整根因 | **仍未闭环**，候选剩 CPU 核数 / agent arena 规划 |

**未写入任何仓库配置** —— 唯一候选变量尚未通过有效对照实验。

---

## 十一、第六轮：拿到最硬的一次证据，但根因仍未闭环

本轮解决了观测可靠性（见 11.1），并首次让 **6 服务全部 healthy**（11.2），
但也证伪了"内存不足"这一整条主线（11.3–11.4）。

### 11.1 先解决观测手段：崩溃转储落宿主目录

podman 状态损坏会让容器表现为 `no such container`，与"JVM 崩溃退出"**无法区分**。
解法是让证据绕过 podman：为每个服务加 bind mount
`<scratch>/jvmprobe-r2/<svc>:/app/probe`，JVM 转储直接落宿主目录。

已验证可行（容器以 uid 100 运行，目录需 `chmod 777`）。

### 11.2 6 服务首次同时健康

基础设施**完全预热**后启动 6 业务服务：

```
t+30s   healthy=4
t+180s  healthy=6   ✅ 全部 healthy
```

而第五轮（基础设施未预热）同样 6 服务却崩了两个。
⇒ **预热程度是重要变量**，此前多轮实验未控制它。

### 11.3 但 system 随后仍崩溃——且是在启动成功之后

```
11:38:22.581  Started SystemApplication in 337.879 seconds
11:38:25.564  Native memory allocation (mmap) failed to map 65536 bytes
              Error detail: committing reserved memory
              exit=1  oom=false
```

**启动完成 3 秒后**才崩。这排除了"启动期按错误内存视图规划"的旧说法。

### 11.4 决定性反证：内存量与崩溃**反向相关**

三组受控实验，唯一变量为system 的内存配置：

| 配置 | 结果 |
| :--- | :--- |
| `mem_limit: 2g` / `-Xmx1536m`（原样） | 启动成功，**338 秒后**崩 |
| `mem_limit: 1200m` / `-Xmx640m`（收紧） | **75 秒**就崩（更早） |
| **完全移除 `mem_limit`**（20.97GB 上限） | **25 秒**内崩（最早） |

**给更多内存反而更早崩溃。** 这与"内存不足"假设直接矛盾。

崩溃时刻的环境快照：

```
宿主 available = 10454 MB          ← 还有 10GB 空闲
file / search / notification / auth / gateway = 全部 healthy
oom=false  exit=1
```

⇒ **不是内存不够，也不是系统内 OOM。**

### 11.5 三次失败的失败点各不相同

| 场景 | `Error detail` |
| :-- | :-- |
| 第八节（单服务 448m） | `AllocateHeap` (112 B) |
| 第五节（auth 并行） | `Chunk::new` (129816 B) |
| 本节收紧后 | `Chunk::new` (252424 B) |
| 本节原样 | `mmap ... committing reserved memory` (65536 B) |

三次失败点不同，但都报`insufficient memory`。**该消息是症状，不是原因。**

### 11.6 已排除清单（累计）

| 假设 | 排除依据 |
| :-- | :-- |
| 内核 OOM killer | `OOMKilled=false`, `oom_kill=0` |
| 元空间 reserved 撑爆 | 转储 `committed=24320K` |
| 映射数耗尽 | 289 / 10485760 |
| 总量超限 | `-Xms` 合计 2176MB / available 12786MB |
| cgroup 内存不足 | 收紧/移除 `mem_limit` 后**更早**崩（11.4） |
| SkyWalking agent 开关 | 开与关 system 都能起（第五节，但该对照有预热变量干扰） |
| CPU 核数 / JIT arena | 对照实验两次被 podman 状态损坏打断，**未完成** |

### 11.7 当前判断

`insufficient memory` 出现在多种不同分配路径上，且**与实际可用内存反向相关**，
这不符合"资源耗尽"的任何已知形态。更可能的是**运行环境层面的限制**
（cgroup v2 在 podman rootless 下的某项未识别约束），而非应用或 JVM 配置问题。

**但本轮未能取得该限制的直接证据**，故仍不写入任何配置变更。

### 11.8 下一步（需要真实证据，不再猜参数）

1. 在崩溃**发生的同一瞬间**抓 `/sys/fs/cgroup/memory.events`、`memory.stat`、
   `memory.pressure`（本轮只拿到事后快照）
2. 用 `strace -f -e trace=mmap,madvise` 记录失败的那次系统调用及其参数
3. 对照 podman 版本 / cgroup 版本：同一镜像在 `cgroup v1` 与 `v2` 下各跑一轮
4. 先解决 podman 状态反复损坏（它已让至少3 轮实验作废）

---

## 第七节：根因闭环 —— `vm.overcommit_memory=2` + 全局 commit 预算耗尽

**结论：不是容器内存不足，不是 cgroup OOM，不是 CPU/JIT。**
根因在**宿主机全局**：`vm.overcommit_memory=2`（严格模式）使所有进程共享
`CommitLimit = MemTotal + SwapTotal = 20.63 GB` 的提交预算，该预算被耗尽后，
内核**拒绝任何新的 commit**，无需实际物理内存即失败。

### 12.1 关键证据

```text
vm/overcommit_memory = 2          # 严格模式
vm/overcommit_ratio  = 80
MemTotal  = 19.53 GB
SwapTotal =  5.00 GB
CommitLimit  = 20.63 GB           # 实测 = MemTotal + SwapTotal
Committed_AS = 18.14 GB           # 崩溃当时
→ 剩余仅 2.49 GB，但单进程 VmSize 之和仅 5.48 GB
```

**决定性线索：分配失败量级极小。**

| 日志原文 | 含义 |
| :--- | :--- |
| `Native memory allocation (malloc) failed to allocate 112 bytes` | 剩 112 字节都分不出 |
| `failed to allocate 336 bytes. Error detail: nmethod` | JVM 内部代码缓存 |
| `pthread_create failed (EAGAIN)` + `stacksize: 1024k` | 新线程栈需 1MB commit |

物理内存充足时不可能出现这种失败 —— 这**只能是提交预算耗尽**。

### 12.2 元凶：40.62 GB 的孤儿 Java 进程

```text
pid=1337788  VmSize=40.62 GB  RSS=0.07 GB
cmd: java -cp .../elasticsearch-java-8.12.1.jar ...
cgroup: /user.slice/user-1000.slice/session-c4.scope   ← 不属于任何容器
elapsed: 13:41:46                                        ← 13 小时前的实验遗留
```

它只申请虚拟内存不实际使用（RSS 0.07GB），却独占 40.62 GB 的 commit 额度，
**是宿主 CommitLimit 的 2 倍**。单它一个就让全局预算永久性超额。

清理后立即回落：

```text
清理前: Committed_AS 18.22 GB / 20.63 GB (88%)
清理后: Committed_AS  6.74 GB / 20.63 GB (33%)   剩余 13.88 GB
```

### 12.3 正向验证（真实 Compose，全 agent）

清理后按**生产同路径**（`deploy/compose.yaml --profile full`，SkyWalking agent 真实启用）重跑：

```text
[infra] ✅ 就绪                     commit 6.24/20.63 (30%)
[t] 启动 6 服务 11:52:28
[skywalking] agent enabled: service=system-module oap=cloud-skywalking-oap:11800

cloud-auth          Up 24 minutes (healthy)   崩溃=0
cloud-file          Up 24 minutes (healthy)   崩溃=0
cloud-gateway       Up 24 minutes (healthy)   崩溃=0
cloud-notification  Up 23 minutes (healthy)   崩溃=0
cloud-search        Up 24 minutes (healthy)   崩溃=0
cloud-system        Up 24 minutes (healthy)   崩溃=0
```

**运行 24 分钟、0 崩溃**，远超历史最长 338 秒崩溃窗口。
这是本项目首次在真实 Compose + 全 SkyWalking 装配下六服务同时健康。

### 12.4 这一根因解释全部历史反常现象

| 历史现象 | 解释 |
| :--- | :--- |
| `mem_limit: 2g` + `-Xmx1536m` → 338s 崩 | 额度大 → 更快消耗全局 commit |
| `mem_limit: 1200m` + `-Xmx640m` → 75s 崩 | 额度小 → 崩得早是**巧合**，非因果 |
| 完全移除 `mem_limit` → 25s 崩 | 无上限约束 → 立即超额，最快崩 |
| **给更多内存反而更早崩溃** | commit 预算是**全局共享**的，与单容器上限无关 |
| `memory.events oom=0` / `OOMKilled=false` | 从未触发 cgroup OOM，与本问题正交 |
| 元空间 `reserved 1114112K` 但未耗尽 | reserved 不计入 commit，只有 commit 才受限 |
| 宿主机崩溃时仍有约 10GB available | `available` 是**物理内存**，与 commit 预算是两套账 |
| CPU/JIT 限制后仍六服务全崩 | 两者都不是 commit 消耗的主因 |

**「给更多内存反而更早崩溃」是本问题最强的判别特征** ——
普通内存不足永远表现相反。这也是此前所有排查方向被误导的原因。

### 12.5 被推翻的假设（不得复活）

| 假设 | 否定证据 |
| :--- | :--- |
| 容器/cgroup 内存不足 | `memory.events oom=0`，且 cgroup 内存合计仅 5.48GB |
| 内核 OOM killer | `OOMKilled=false` |
| 元空间耗尽 | `used 23962K` vs `reserved 1114112K`，差 45 倍 |
| 映射数耗尽 | `mappings=289` vs `max_map_count=10485760` |
| SkyWalking agent 耗尽分配 | 关 agent 后 390s 仍在 t=420s 崩溃（仅推迟） |
| CPU 核数 / C2 JIT arena | `-XX:ActiveProcessorCount=2 -XX:CICompilerCount=2` 下**六服务仍同时崩溃** |
| 宿主机 `/proc/meminfo` 异常 | 该现象真实但只是**症状的伴随物**，非根因 |

### 12.6 探针自身的错误（本轮两次自摆乌龙）

按 AGENTS.md 第五课，必须记录**取证手段本身出错**的情形：

1. **`podman inspect --format '{{.State.Status}}'` 在密集轮询下返回空**
   → 脚本判定 `存活=0/6`，但同一时刻单独执行返回 `running`，实为 6/6 存活。
   探针超时被当成"服务全死"。**结论**：轮询必须加 `timeout` 并区分"取不到值"与"取到非真值"。

2. **统计脚本的子 shell 变量丢失**
   → `VmSize 之和 = 0.00 GB`，因 `T` 在管道子 shell 中累加丢失且 `&&` 优先级误用。
   该错误一度让我误判"容器合计远小于 Committed_AS"。

**教训**：`存活=0/6` 与 `崩溃=0` 同时出现时，**先怀疑探针**，再怀疑被测系统。

### 12.7 后续动作

- [ ] 负向对照：重新制造 commit 耗尽，确认守护能复现崩溃（防止"从未失败"的假稳定）
- [ ] 新增 commit 预算守护测试，纳入 E2E 早期步骤
- [ ] 排查并清理其他潜在孤儿进程（`podman wait` / `rootlessport` 各占 1.7–2.2 GB）
- [ ] 完整 `verify-e2e.sh` 连续多轮通过

---

## 第八节：负向对照 —— 直接证明"物理内存充足但 mmap 被拒"

第七节证明了「清理孤儿进程后不再崩溃」，但那是**相关性**。
本节用受控实验补上**因果**：主动占满 commit 预算，观察内核行为。

### 13.1 实验装置

写一个 C 程序逐块 `mmap(256MB) + memset(写满)` 直到内核拒绝。
**必须写满** —— 见 13.3。

### 13.2 结果

```text
[hog] mmap 在第 42 块(10752 MB)失败: errno=12 (Cannot allocate memory)
```

同一时刻的系统状态:

| 指标 | 值 | 含义 |
| :--- | ---: | :--- |
| `Committed_AS` | 20.54 / 20.63 GB (**100%**) | 提交预算耗尽 |
| `MemAvailable` | **4.14 GB** | 物理内存**仍充足** |
| `mmap 256MB` | **FAIL errno=12 ENOMEM** | 内核拒绝分配 |

**这是本问题最干净的一次证明**：物理内存有 4GB，256MB 的 `mmap` 却被拒。
`errno=12` 正是 JVM 报 `insufficient memory` / `pthread_create EAGAIN` 的内核层原因。

### 13.3 装置本身踩了两次坑（都是"探针造错了现场"）

| 坑 | 现象 | 真因 |
| :--- | :--- | :--- |
| 用 `MAP_NORESERVE` 申请 14GB | `Committed_AS` 只涨 **1.15 GB** | `MAP_NORESERVE` 不计入 commit |
| 改用普通 mmap 但**不触碰** | `Committed_AS` 几乎不涨 | **`Committed_AS` 只统计被写过的页** |

第二条也解释了根因现场那个孤儿 JVM 的疑点: `VmSize=40.62 GB` 而 `RSS=0.07 GB`。
两者相差 580 倍, 正是因为**虚拟地址空间远大于实际触碰的页**。
所以排查时**不能只看 VmSize** —— 要看 `Committed_AS` 的实际增量。

### 13.4 门禁的端到端负向对照

在 commit 100% 的状态下运行 E2E:

```text
[前置] 检查全局 commit 预算 ...
commit 预算: 20.54 / 20.63 GB (100%)
FAIL: commit 预算占用 100% 超过阈值 85%
  ...排查指引...
  FAIL 全局 commit 预算不足 —— 6 服务必然崩溃 (详见上方排查指引)
!! commit 预算不足, 继续跑只会得到 6 个服务随机崩溃的假象。
E2E rc=1
```

**3 秒内拦截**，且给出可执行的排查步骤。这满足 AGENTS.md 要求的 L2.5 负向对照 ——
不是"我加了守护"，而是"改坏它，确认守护会红"。

守护测试层面的负向对照（改阈值 / 删失败分支）分别使 2 项 / 6 项断言变红。

### 13.5 新增守护

| 文件 | 作用 |
| :--- | :--- |
| `deploy/tests/check_commit_budget.py` | 阈值 85%, SKIP 时明说"不等于通过" |
| `deploy/tests/test_commit_budget.py` | 14 项断言, 含边界 85%/86% 与 SKIP 语义 |
| `verify-e2e.sh` 前置门禁 | 在 `build_images` 调用**之前**, 失败即 `exit 1` |

**自检位置断言连踩 6 次同一类坑**，全部记录在 `run_gate_selftest` 注释里:
函数定义 / 裸调用 / 缩进注释 / 自检体自身 / 注释里的行号示例 / **断言 grep 模式自身**。
第 6 次最隐蔽 —— 污染源不是注释, 而是断言代码自己。
通用解法: 先定出自检函数的行区间, 两个搜索都限定在该区间之外。
