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


# 2026-10-06 复核: 宿主 `vm.overcommit_memory` 已从 2 变为 0(启发式)。
# 受控实验证据 —— 在 Committed_AS 达到 CommitLimit 的 **109%** 时连续 8 次
# 256MB mmap 全部成功; 而同一操作在 overcommit=2 时曾返回 errno=12 (ENOMEM)。
#
# 本门禁的阈值只在**严格模式**下有意义: 严格模式里超额 commit 被内核直接拒绝,
# 比例是真实且唯一的约束; 非严格模式下内核不拒绝, 该比例不再预测分配失败。
# 若在非严格模式仍按 92% 判红, 那是误报源 —— 且它会阻断 E2E 并让用户去清理
# **当前并不存在**的孤儿进程(实测 13GB 孤儿已不在, 22 容器全 healthy)。
#
# 【为什么不给非严格模式编新阈值】本项目已因编造静态预测器踩坑:
# `check_heap_headroom.py` 假设"mem_limit 1024m 恰等于 -Xmx 1024m ⇒ 必然失败",
# 被实测证伪(堆外余量 384MB 时照样 malloc 失败), 该门禁已删除。
# 非严格模式下崩溃取决于启动并发等**动态**因素, 没有可靠的静态预测器。
STRICT_OVERCOMMIT = "2"


def is_strict_mode(overcommit: str | None) -> bool:
    """是否处于严格 overcommit 模式 —— 决定比例阈值是否还有判据意义。"""
    return overcommit == STRICT_OVERCOMMIT


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
    strict = is_strict_mode(overcommit)
    if strict:
        print("vm.overcommit_memory = 2 (严格模式): 超额 commit 会被内核直接拒绝")
        print("  此时 cgroup 计数 oom=0 / memory.events 全 0, 但 JVM 仍会 malloc 失败 ——")
        print("  这类失败极易被误判为'容器内存不足'。")
        print()
    else:
        # 非严格模式: 比例不再是有效预测指标(见上方注释的实测证据)。
        # 仍采样并陈述事实, 但不判红 —— 它只告警不阻断, 且不应假装能预测。
        print(f"vm.overcommit_memory = {overcommit}: 非严格模式, 内核不拒绝超额 commit")
        print(f"  实测依据: overcommit=0 时 Committed_AS 达 CommitLimit 的 109%,")
        print(f"            连续 8 次 256MB 分配仍全部成功; =2 时同一操作返回 ENOMEM。")
        print(f"  故 {MAX_COMMIT_RATIO:.0%} 阈值在当前模式下**不预测**分配失败, 仅作事实陈述。")
        print()

    deadline = time.time() + watch_seconds
    worst = 0.0
    worst_gb = (0.0, 0.0)
    count = 0
    peak_exceeded = False
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
            peak_exceeded = True
        if time.time() >= deadline:
            break
        time.sleep(INTERVAL_SECONDS)

    if peak_exceeded and not strict:
        print()
        print(f"WARN: 运行期 commit 峰值 {worst:.0%} 超过 {MAX_COMMIT_RATIO:.0%}, "
              f"但当前为非严格 overcommit 模式。")
        print("      该比例不预测分配失败, 故不判红。真正的约束是物理内存:")
        print("      grep -E 'MemAvailable|MemTotal' /proc/meminfo")
        print("      若宿主改为 overcommit=2, 本门禁会恢复为硬判定。")
        return 0

    if worst > MAX_COMMIT_RATIO:
        print()
        print(f"FAIL: 运行期 commit 占用 {worst:.0%} 超过阈值 {MAX_COMMIT_RATIO:.0%}")
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