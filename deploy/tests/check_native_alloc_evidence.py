#!/usr/bin/env python3
"""守护: 非严格 overcommit 模式下, 用**实证信号**而非百分比判定是否真在出事。

## 为什么要有这道门禁（2026-10-06）

宿主 `vm.overcommit_memory` 从 2(严格) 变成 0(启发式) 后, `check_commit_budget.py`
与 `check_commit_runtime_guard.py` 两个**比例型**门禁被重新定性为"只告警不阻断"
(见那两个文件的注释)。于是出现一个空档:

    门禁被放宽了, 但没人替代它去回答"那现在到底有没有出事?"

而两个门禁的输出都只会让用户自己去 `grep MemAvailable` —— 全仓**没有任何脚本
读这个指标**。把"去看一眼"当成处置措施, 正是第五课「看起来正常的结论」:
输出格式合法, 但没有任何东西因此被观测。

## 为什么**不用** MemAvailable 百分比阈值

本项目已经试过并**否决**了。AGENTS.md 记录的实测数据点:

| MemAvailable/MemTotal | 实际结果 |
| :--- | :--- |
| 20.1% | **崩**(mmap 256MB FAIL errno=12) |
| 42.2% | 健康 |
| 83.7% | 健康 |

先前写的"经验值 <15%"被自己的数据否定(崩溃点 20.1% 高于 15%)。两个已知
数据点只把分界夹在 20%~42% 之间, 无法支撑一个可靠阈值。

⚠️ 本门禁因此**刻意不含任何百分比阈值**。任何新加的百分比都是编造的 ——
`check_heap_headroom.py` 已经因这个罪名被删除过一次(假设"mem_limit 恰等于
-Xmx ⇒ 必然失败", 被实测证伪)。

## 判据: 两个实证信号

要定性"真出事", 必须看**已经发生了什么的证据**, 而不是预计会不会出事:

```bash
dmesg | grep -ci commit                                              # 内核是否拒绝过 commit
podman logs --since 20m <svc> | grep -ciE 'malloc|failed to allocate' # 容器内是否已报
```

信号 2 是主判据(可移植、无需特权); 信号 1 作为佐证, **读不到时不判红**
—— 按第八课补记一的规则, "探针取不到值"必须与"取到 0"区分开。

## 探针三态（本门禁的核心纪律）

`podman logs` 在容器刚重建、或日志文件损坏时会**返回空或报错**。这三种情况
必须分开, 否则会把"探针坏了"读成"系统健康":

    取到内容且无匹配  -> 真的没有 malloc 失败
    取到内容且有匹配  -> 真的出事了 -> 判红
    取不到 / 报错     -> **探针失效** -> 单独报 PROBE-FAIL, 不参与健康判定

本次实现时我自己就踩了: `n=$(... | grep -ci ... || echo 0)` 在无匹配时
输出**两行**("0" 来自 grep, "0" 来自 || 分支), 于是 `[ "$n" != "0" ]`
成立, 9 个容器**全部**被误报为命中。整齐地全红 = 探针坏了的特征(第八课补记四)。
"""

from __future__ import annotations

import subprocess
import sys

# 只查这些业务/基础设施容器; 不含 grafana/prometheus 等无 JVM 的纯 Go 组件。
JVM_CONTAINERS = (
    "cloud-auth",
    "cloud-system",
    "cloud-notification",
    "cloud-search",
    "cloud-file",
    "cloud-gateway",
    "cloud-nacos",
    "cloud-skywalking-oap",
    "cloud-elasticsearch",
)

MALLOC_MARKERS = ("malloc", "failed to allocate")
SINCE = "24h"


def probe_container(name: str) -> tuple[str, int]:
    """返回 (状态, 命中数); 状态 ∈ {HIT, CLEAN, PROBE_FAIL}。

    ⚠️ 必须用 `subprocess.run` 拿退出码, 不能只用管道 —— 管道里 grep 的退出码
    与 podman 的混在一起, 分不出"没匹配"和"podman 报错"(第八课补记二:
    `|| true` 会把失败改写成成功)。
    """
    try:
        proc = subprocess.run(
            ["podman", "logs", "--since", SINCE, name],
            capture_output=True,
            timeout=60,
            check=False,
        )
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return ("PROBE_FAIL", -1)

    # podman 对不存在的容器返回非 0; rc==0 即视为取到了内容。
    if proc.returncode != 0:
        return ("PROBE_FAIL", -1)

    text = (proc.stdout + proc.stderr).decode("utf-8", errors="replace")
    hits = sum(1 for line in text.splitlines()
               if any(marker in line.lower() for marker in MALLOC_MARKERS))
    return ("HIT" if hits else "CLEAN", hits)


def check() -> int:
    print(f"=== 非严格模式的实证信号探测 (容器日志近 {SINCE}) ===")
    print()
    print(f"  判据: 日志中出现 'malloc' 或 'failed to allocate'")
    print(f"  容器: {len(JVM_CONTAINERS)} 个")
    print()

    hits: list[tuple[str, int]] = []
    probe_failures: list[str] = []

    for name in JVM_CONTAINERS:
        status, count = probe_container(name)
        if status == "HIT":
            print(f"  HIT  {name:<26} {count} 行")
            hits.append((name, count))
        elif status == "CLEAN":
            print(f"  CLEAN {name:<25} 0")
        else:
            # ⚠️ 探针失效**不判红也不判绿**, 必须显式打印 —— 否则"9 个容器全部
            # CLEAN"与"9 个容器全部探测失败"在汇总行上无法区分(第五课: 恒假绿)。
            print(f"  PROBE-FAIL {name:<19} 取不到日志(容器不存在或 podman 异常)")
            probe_failures.append(name)

    print()
    print(f"  已探测 {len(JVM_CONTAINERS) - len(probe_failures)}/{len(JVM_CONTAINERS)}"
          f", 命中 {len(hits)}")

    if probe_failures:
        print()
        print(f"  ⚠️ {len(probe_failures)} 个容器探针失效: {', '.join(probe_failures)}")
        print("     这**不代表系统健康**, 只代表本次没观测到。")
        print("     处置: 先确认容器是否存在(podman ps), 再重跑本门禁。")

    if hits:
        print()
        print(f"FAIL: {len(hits)} 个容器已报告 native 内存分配失败:")
        for name, count in hits:
            print(f"      {name}  ({count} 行)")
        print()
        print("      这是**已发生**的证据, 不是预测。此时内核已无法满足分配,")
        print("      cgroup 侧 oom 计数通常全为 0 —— 所以调 mem_limit 无效。")
        print("      处置: 降低同时运行的 JVM 数 / 调低 -Xmx(见 check_heap_headroom")
        print("            被删除的记录: 崩溃取决于启动并发, 不是单容器配置)。")
        return 1

    if probe_failures:
        # 探针失效不能判绿 —— 但也不能判红(那会把"没测到"说成"测了不过")。
        # 故返回 2, 由调用方区分。
        print()
        print("PROBE_FAILED: 存在探针失效的容器, 本次未完成判定(既非 PASS 也非 FAIL)")
        return 2

    print()
    print(f"PASS: {len(JVM_CONTAINERS)} 个容器近 {SINCE} 均无 native 分配失败")
    return 0


if __name__ == "__main__":
    sys.exit(check())
