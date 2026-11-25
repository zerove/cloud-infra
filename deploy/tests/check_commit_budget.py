#!/usr/bin/env python3
"""守护: 本地 E2E 启动前, 全局 commit 预算必须留有余量。

## 这个守护拦住的是什么（2026-10-04）

6 个业务服务反复崩溃, 报错形态极具误导性:

    # Native memory allocation (malloc) failed to allocate 112 bytes.
    # Error detail: AllocateHeap
    # pthread_create failed (EAGAIN) for attributes: stacksize: 1024k

看上去像"容器内存不够", 实际根因在**宿主机全局**:

    vm/overcommit_memory = 2      # 严格模式: 禁止超额 commit
    CommitLimit  = MemTotal + SwapTotal = 20.63 GB

严格模式下所有进程共享这一个提交预算。预算耗尽后, 内核**拒绝任何新的 commit**,
不需要实际物理内存就会失败 —— 所以宿主机明明还有 10GB available。

最隐蔽的一点: **给服务更多内存反而更早崩溃**。因为提高单容器上限只是让该容器
更快消耗全局预算, 与"够不够用"无关。普通内存不足永远表现相反, 这是判别特征。

实测元凶是一个 13 小时前遗留的孤儿进程:

    pid=1337788  VmSize=40.62 GB  RSS=0.07 GB
    cgroup=/user.slice/user-1000.slice/session-c4.scope   ← 不属于任何容器

它不属于任何容器, 因此 `podman ps` 看不到, 容器级排查永远找不到它。
`VmSize` 40.62 GB 是宿主 `CommitLimit` 的 2 倍, 单它一个就让全局预算永久超额。

## 为什么必须是"提前检查"而不是"崩溃后排查"

崩溃现场极难还原: 一次 E2E 要预热 4 分钟 + 观测 10 分钟, 崩溃往往发生在
第 25–338 秒之间, 且落点随机(`AllocateHeap` / `Chunk::new` / `nmethod` /
`committing reserved memory`), 每次都不一样。事后归因的成本远高于事前拦截。

## 判据

`Committed_AS / CommitLimit` 超过阈值即判红。阈值取 85%:

- 本次根因现场: 88% (18.14 / 20.63 GB)
- 清理后健康状态: 33% (6.74 GB)
- 6 服务满负荷运行时: 69% (14.25 GB)

85% 留出余量, 使"6 服务启动 + agent 建连"这段最耗 commit 的窗口不被中途掐断。
"""

from __future__ import annotations

import re
import sys

# Committed_AS 占 CommitLimit 的比例上限。
# 依据: 根因现场 88% 会崩; 6 服务健康运行 69%; 85% 留出启动窗口余量。
MAX_COMMIT_RATIO = 0.85


def read_meminfo() -> dict[str, int]:
    """解析 /proc/meminfo, 单位统一为 KB。"""
    values: dict[str, int] = {}
    with open("/proc/meminfo", encoding="utf-8") as handle:
        for line in handle:
            match = re.match(r"^(\w+):\s+(\d+) kB", line)
            if match:
                values[match.group(1)] = int(match.group(2))
    return values


def check() -> int:
    meminfo = read_meminfo()
    committed = meminfo.get("Committed_AS")
    limit = meminfo.get("CommitLimit")

    if committed is None or limit is None:
        print("SKIP: /proc/meminfo 缺少 Committed_AS 或 CommitLimit, 无法判定")
        print("      本次不判定 —— 不等于通过")
        return 0

    if limit <= 0:
        print("SKIP: CommitLimit <= 0, 判定无意义")
        return 0

    ratio = committed / limit
    print(f"commit 预算: {committed / 1048576:.2f} / {limit / 1048576:.2f} GB ({ratio:.0%})")

    overcommit = read_overcommit_mode()
    if overcommit == "2":
        print("vm.overcommit_memory = 2 (严格模式): 超额 commit 会被直接拒绝")
    elif overcommit in ("0", "1"):
        print(f"vm.overcommit_memory = {overcommit}: 非严格模式, 拒绝风险较低")

    if ratio > MAX_COMMIT_RATIO:
        print(f"FAIL: commit 预算占用 {ratio:.0%} 超过阈值 {MAX_COMMIT_RATIO:.0%}")
        print("      此时任何新 commit 都可能被内核拒绝, JVM 会报")
        print("      'insufficient memory' / 'failed to allocate N bytes' / pthread_create EAGAIN,")
        print("      且 cgroup OOM 计数全为 0 (memory.events oom=0) —— 极易被误判为容器内存不足。")
        print()
        print("      排查顺序 (注意: 孤儿进程不在 podman ps 里, 必须直接扫宿主):")
        print("        1. 按 VmSize 排序扫全系统, 找出不属于任何容器的进程:")
        print("           for p in /proc/[0-9]*; do awk '/^VmSize/{print $2, FILENAME}' $p/status; done | sort -rn | head")
        print("        2. 用 cgroup 区分归属, libpod- 前缀才是容器:")
        print("           awk -F: '/^0::/{print $3}' /proc/<pid>/cgroup")
        print("        3. 重点排查遗留的 java -cp / pytest / podman wait 等长驻宿主进程")
        print()
        print("      注意: 提高容器 mem_limit 或 -Xmx **不会**解决问题, 反而会更快耗尽预算。")
        return 1

    print(f"PASS: commit 预算余量充足 (剩余 {(limit - committed) / 1048576:.2f} GB)")
    return 0


def read_overcommit_mode() -> str | None:
    try:
        with open("/proc/sys/vm/overcommit_memory", encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return None


def main() -> int:
    return check()


if __name__ == "__main__":
    sys.exit(main())
