#!/usr/bin/env python3
"""守护: E2E **运行期**的 commit 预算必须留有余量(补 check_commit_budget.py 的覆盖缺口)。

## 为什么要有第二个门禁（2026-10-05 实测）

`check_commit_budget.py` 只在 `verify-e2e.sh` **启动前**查一次, 而实测崩溃
发生在**运行期**:

    启动前  commit = 16.7GB (81%)  门禁 PASS
    运行期  commit = 20.54GB (99.6%)  ← 差 0.09GB 就是硬上限
    结果    system / notification 崩溃:
            Native memory allocation (malloc) failed to allocate 112 bytes.
            Error detail: AllocateHeap

即**通过启动前的门禁 ≠ 运行期不会耗尽**。本守护在运行期持续采样。

## 一个必须纠正的旧结论

原 `check_commit_budget.py` 文档把根因归给"13 小时前遗留的孤儿 JVM"
(`VmSize=40.62GB / RSS=0.07GB`)。本轮实测**没能复现那个形态**:

- 扫全机 `/proc`, 单进程 RSS 最高仅 1.48GiB, 无 40GB 级孤儿。
- 容器 RSS 合计 8.3GB, 宿主非容器进程 RSS 合计 1.6GB, 合计 9.95GB。
- 而 `Committed_AS` 在 82%~99.6% 之间**反复震荡** —— 这是 9 个 JVM
  (nacos 1.4G / oap 1.2G / system 0.9G / es 0.86G / 其余各 0.6G) 各自的
  分配-释放周期叠加, 不是某个固定进程占着不放。

**这两个形态的处置完全不同**:

| 形态 | 特征 | 处置 |
| :--- | :--- | :--- |
| 孤儿进程占额 | 某进程 RSS 长期不降 | 结束该进程 |
| 本次: 总量贴近上限 | 各进程都不大, 但**合计 + 波动**逼近上限 | 降低并发/堆上限, 不可结束任何进程 |

所以本守护**不会**建议"找并杀掉大进程"—— 那在本次形态下是有害建议:
没有任何单个进程值得杀, 杀了还会由别的补上。

## 判据

连续采样 N 次, 任一次 `Committed_AS / CommitLimit` 超过阈值即判红。
阈值取 92%: 实测稳定运行在 82%~91%, 崩溃发生在 99.6%;
92% 既能盖住"贴着上限震荡"这个真实风险, 又留出区分抖动与真故障的空间。

`--watch SECONDS` 模式用于运行期长观测; 无参则做一次快速判定。
"""

from __future__ import annotations

import argparse
import sys
import time

MAX_COMMIT_RATIO = 0.92
SAMPLES = 5
INTERVAL_SECONDS = 2


def read_meminfo() -> dict[str, int]:
    """解析 /proc/meminfo, 单位统一为 KB。

    ⚠️ 单位只在这里换算一次(kB)。下游一律用 KB 比较, 不要在别处再乘除 1024 ——
    本轮实测正是在这里踩过: 先 *1024 再 /1048576, 等于只除了一次,
    18447032 kB 被报成 "18000GB"(真实值 17.6GiB), 而格式完全合法。
    """
    values: dict[str, int] = {}
    with open("/proc/meminfo", encoding="utf-8") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) >= 2 and parts[0].endswith(":") and parts[1].isdigit():
                values[parts[0].rstrip(":")] = int(parts[1])
    return values


def read_overcommit_mode() -> str | None:
    try:
        with open("/proc/sys/vm/overcommit_memory", encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return None


def sample_once() -> tuple[float, float, float] | None:
    """返回 (committed_GiB, limit_GiB, ratio); 取不到值时返回 None。"""
    meminfo = read_meminfo()
    committed = meminfo.get("Committed_AS")
    limit = meminfo.get("CommitLimit")
    if committed is None or limit is None or limit <= 0:
        return None
    return committed / 1048576, limit / 1048576, committed / limit


def check(watch_seconds: int) -> int:
    overcommit = read_overcommit_mode()
    if overcommit == "2":
        print("vm.overcommit_memory = 2 (严格模式): 超额 commit 会被内核直接拒绝")
        print("  此时 cgroup 计数 oom=0 / memory.events 全 0, 但 JVM 仍会 malloc 失败 ——")
        print("  这类失败极易被误判为'容器内存不足'。")
        print()

    deadline = time.time() + watch_seconds
    worst = 0.0
    worst_gb = (0.0, 0.0)
    count = 0
    while True:
        result = sample_once()
        if result is None:
            # 探针失效必须显式失败, 不能静默当成通过(第五课)。
            print("PROBE_FAILED: /proc/meminfo 缺少 Committed_AS 或 CommitLimit")
            return 3
        committed, limit, ratio = result
        count += 1
        marker = ""
        if ratio > worst:
            worst = ratio
            worst_gb = (committed, limit)
            marker = "  <== 本轮峰值"
        print(f"  采样 {count}: commit={committed:6.2f} / {limit:.2f} GiB ({ratio:.0%}){marker}")
        if ratio > MAX_COMMIT_RATIO:
            print()
            print(f"FAIL: 运行期 commit 占用 {ratio:.0%} 超过阈值 {MAX_COMMIT_RATIO:.0%}")
            print(f"      峰值 {worst_gb[0]:.2f} / {worst_gb[1]:.2f} GiB, 仅剩 "
                  f"{worst_gb[1] - worst_gb[0]:.2f} GiB 余量。")
            print()
            print("      此时的形态是**总量贴近上限**(不是某个进程占额):")
            print("      各 JVM 单独看都不大, 但合计 + 分配/释放的波动峰值会撞线。")
            print("      实测 9 个 JVM 在峰值时刻: nacos 1.4G / oap 1.2G / system 0.9G /")
            print("      elasticsearch 0.86G / 其余各 0.6G, 合计约 5.7GiB。")
            print()
            print("      处置方向(不要走错):")
            print("        - 不要去 kill 单个进程 —— 没有任何一个值得杀, 杀了会被别的补上")
            print("        - 降低同时运行的 JVM 数, 或调低 -Xms/-Xmx")
            print("          (注意 -Xms 是启动即预留, 在严格模式下**预留即计费**)")
            print("        - 提高单容器 mem_limit 无效: 那只是让该容器更快耗尽全局预算")
            return 1
        if time.time() >= deadline:
            break
        time.sleep(INTERVAL_SECONDS)

    print(f"PASS: {count} 次采样, 最高 {worst:.0%} (阈值 {MAX_COMMIT_RATIO:.0%}), "
          f"余量 {worst_gb[1] - worst_gb[0]:.2f} GiB")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="E2E 运行期 commit 预算守护")
    parser.add_argument("--watch", type=int, default=0,
                        help="持续监控的秒数 (默认 0 = 快速采样数次即返回)")
    args = parser.parse_args()
    if args.watch > 0:
        return check(args.watch)
    return check(SAMPLES * INTERVAL_SECONDS)


if __name__ == "__main__":
    sys.exit(main())