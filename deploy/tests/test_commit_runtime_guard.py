#!/usr/bin/env python3
"""check_commit_runtime_guard.py 的守护测试。

## 为什么要单独测这个门禁

第八课: "我加了守护"不等于"守护有效", 差的那一步就是**负向对照**。
本次必须验证: 把阈值改到必然被突破的值, 门禁**确实会红**。

## 造数据的方式

不能靠真去占满宿主机 commit —— 那是不可逆且影响其他进程的操作。
改为**注入 meminfo 桩**: 把 `/proc/meminfo` 的读取替换为固定内容。
这属于合成夹具(synthetic), 因此额外做一层校验:
  判据逻辑用桩数据验证, 且**桩与真实 /proc/meminfo 的字段格式一致**
(用真实文件反推格式, 而不是凭记忆写正则 —— 第五课补记一)。
"""

from __future__ import annotations

import importlib.util
import io
import os
import subprocess
import sys
import tempfile
from contextlib import redirect_stdout
from pathlib import Path

GUARD = Path(__file__).with_name("check_commit_runtime_guard.py")

spec = importlib.util.spec_from_file_location("runtime_guard", GUARD)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

_failures = 0
_checks = 0


def check(name: str, condition: bool, detail: str = "") -> None:
    global _failures, _checks
    _checks += 1
    if condition:
        print(f"  PASS  {name}")
    else:
        _failures += 1
        print(f"  FAIL  {name}" + (f" — {detail}" if detail else ""))


def meminfo_text(committed_kb: int, limit_kb: int) -> str:
    """构造与真实 /proc/meminfo 同格式的文本。"""
    return (
        "MemTotal:       20480000 kB\n"
        "MemFree:         1000000 kB\n"
        f"Committed_AS:   {committed_kb} kB\n"
        f"CommitLimit:    {limit_kb} kB\n"
        "MemAvailable:   10000000 kB\n"
    )


def run_with_stub(committed_ratio: float, limit_kb: int = 21629724) -> tuple[int, str]:
    """在桩 meminfo 下运行门禁, 返回 (退出码, 输出)。

    ⚠️ 此处替换 `read_meminfo` 有一个**结构性局限**, 本轮实测踩到:
    第一次负向对照注入 `read_meminfo` 的单位 bug 时, 守护测试**仍然全绿** ——
    因为所有正向用例都走的是本函数提供的 `fake_read`, 它覆盖了被测实现本身,
    于是"注入真实实现的 bug"根本影响不到任何断言。

    即"替换被测对象"的桩会让真实实现失去守护。补救: 另有一组不替换任何东西的
    断言 (见 main 中"真实实现"小节), 它们直接调 `guard.read_meminfo` /
    `guard.sample_once`, 注入的真实 bug 会在那里暴露。
    """
    stub = meminfo_text(int(limit_kb * committed_ratio), limit_kb)
    original = guard.read_meminfo
    original_oc = guard.read_overcommit_mode

    def fake_read() -> dict[str, int]:
        values: dict[str, int] = {}
        for line in stub.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[0].endswith(":") and parts[1].isdigit():
                values[parts[0].rstrip(":")] = int(parts[1])
        return values

    guard.read_meminfo = fake_read
    # ⚠️ 必须一并固定为严格模式 "2"。
    # 门禁自 2026-10-06 起按 overcommit 模式分流: 比例阈值只在严格模式下
    # 才是有效约束。若只替换 meminfo, 本文件所有"应判红"用例会继承**跑测试
    # 那台机器**的 overcommit 值 —— 在 overcommit=0 的宿主上它们会假红,
    # 而测试自身看不出原因(与 test_commit_budget.py 的同类问题同源)。
    # 默认 "2" 而非真实值: 阈值相关断言要测的是严格模式这条真实防线,
    # 而不是随宿主环境漂移。
    guard.read_overcommit_mode = lambda: "2"
    buf = io.StringIO()
    try:
        with redirect_stdout(buf):
            code = guard.check(0.0)  # 0 秒 = 只采一次
    finally:
        guard.read_meminfo = original
        guard.read_overcommit_mode = original_oc
    return code, buf.getvalue()


def main() -> int:
    print("== check_commit_runtime_guard 守护测试 ==")

    # ---- 前置: 桩格式必须与真实 /proc/meminfo 一致 ----
    # 若不一致, 下面所有基于桩的断言都在验证一个不存在的东西。
    real = guard.read_meminfo()
    check("真实 /proc/meminfo 能解析出 Committed_AS",
          "Committed_AS" in real, f"实际键: {sorted(real)[:8]}")
    check("真实 /proc/meminfo 能解析出 CommitLimit", "CommitLimit" in real)
    check("真实解析出的 ratio 在合理区间 (0, 2]",
          real.get("CommitLimit", 0) > 0
          and 0 < real.get("Committed_AS", 0) / real["CommitLimit"] <= 2)

    # ---- 正向: 低占用应通过 ----
    code, out = run_with_stub(0.50)
    check("commit 50% 应通过", code == 0, f"退出码={code}")
    check("通过时输出含 PASS", "PASS" in out)

    code, out = run_with_stub(0.85)
    check("commit 85% 应通过 (实测稳定区间上沿)", code == 0, f"退出码={code}")

    # ---- 负向对照: 高占用必须判红 ----
    # 这一组是本文件的核心: 若把 MAX_COMMIT_RATIO 调高到 0.99, 下述断言必须失败。
    code, out = run_with_stub(0.996)
    check("commit 99.6% (实测崩溃值) 应判红", code == 1, f"退出码={code}")
    check("判红时输出含 FAIL", "FAIL" in out)

    code, out = run_with_stub(0.95)
    check("commit 95% 应判红 (超过 92% 阈值)", code == 1, f"退出码={code}")

    code, out = run_with_stub(1.50)
    check("commit 150% (超额) 应判红", code == 1, f"退出码={code}")

    # ---- 边界: 恰好等于阈值不应判红 ----
    code, out = run_with_stub(guard.MAX_COMMIT_RATIO)
    check(f"恰好等于阈值 {guard.MAX_COMMIT_RATIO:.0%} 不判红",
          code == 0, f"退出码={code}")

    # ---- 探针失效必须显式失败, 不能静默通过 ----
    def broken_read() -> dict[str, int]:
        return {}

    original = guard.read_meminfo
    guard.read_meminfo = broken_read
    buf = io.StringIO()
    try:
        with redirect_stdout(buf):
            code = guard.check(0.0)
    finally:
        guard.read_meminfo = original
    check("meminfo 缺字段时必须判红 (退出码 3), 不能当成通过",
          code == 3, f"退出码={code}")
    check("探针失效时输出含 PROBE_FAILED", "PROBE_FAILED" in buf.getvalue())

    # ---- 单位换算不得重复 ----
    # 18447032 kB = 17.59 GiB。若误算成 18000+ GB, 说明乘/除 1024 的次数不对。
    stub = meminfo_text(18447032, 21629724)
    original = guard.read_meminfo

    def one_shot() -> dict[str, int]:
        values: dict[str, int] = {}
        for line in stub.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[0].endswith(":") and parts[1].isdigit():
                values[parts[0].rstrip(":")] = int(parts[1])
        return values

    guard.read_meminfo = one_shot
    try:
        committed, limit, ratio = guard.sample_once()
    finally:
        guard.read_meminfo = original
    check("17.59 GiB 的换算不得放大成 18000 GB",
          abs(committed - 17.59) < 0.1, f"实际={committed}")
    check("ratio 约 85%", abs(ratio - 0.8528) < 0.01, f"实际={ratio}")

    # ---- 真实实现 (不替换任何东西) ----
    # 上一组全用 fake_read 覆盖了 read_meminfo, 于是对真实实现的改动无感。
    # 这里直接调真实函数, 保证"注入 read_meminfo 的 bug"必定被发现。
    # 此刻重新读一次 meminfo, 再与 sample_once 的结果比较——
    # 不能拿 main 开头那份快照比: commit 每秒都在变(实测 82%~99.6% 震荡),
    # 两次读数本来就不相等, 拿旧快照比会把**正常现象**报成缺陷。
    fresh = guard.read_meminfo()
    committed, limit, ratio = guard.sample_once()
    check("真实 sample_once 的 Committed_AS 在合理范围 (1~30 GiB)",
          1 < committed < 30, f"实际={committed}")
    check("真实 sample_once 的 CommitLimit 在合理范围 (1~64 GiB)",
          1 < limit < 64, f"实际={limit}")
    # 只断言**算法一致**而非数值相等: 两次采样之间的真实波动允许差异,
    # 但两者必须落在彼此的2 个百分点内 (实测波动速率远低于此)。
    direct = fresh["Committed_AS"] / fresh["CommitLimit"]
    check("真实 sample_once 的 ratio 与同时刻 meminfo 计算一致 (±2%)",
          abs(ratio - direct) < 0.02,
          f"sample_once={ratio:.4f} 直接算={direct:.4f}")

    # ---- 命令行入口可用 ----
    with tempfile.TemporaryDirectory() as tmp:
        result = subprocess.run(
            [sys.executable, str(GUARD), "--watch", "0"],
            capture_output=True, text=True, timeout=60,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
    check("CLI 可执行且退出码合法", result.returncode in (0, 1, 3),
          f"rc={result.returncode} out={result.stdout[-200:]}")

    print()
    if _failures:
        print(f"自检失败: {_failures} / {_checks} 项")
        return 1
    print(f"自检通过: {_checks} 项")
    return 0


if __name__ == "__main__":
    sys.exit(main())