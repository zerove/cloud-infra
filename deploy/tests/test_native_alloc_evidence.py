#!/usr/bin/env python3
"""check_native_alloc_evidence.py 的守护测试。

核心要证明三件事:
  1. 判据**能**命中真实的崩溃字面量(否则它恒绿 = 假门禁)
  2. 探针失效**不等于**健康(CLEAN / HIT / PROBE_FAIL 三态必须分开)
  3. 判据不会因为宿主状态漂移(它不读百分比, 所以没有阈值可继承)

负向对照见 main() 末尾。
"""

from __future__ import annotations

import io
import subprocess
import sys
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_native_alloc_evidence as mod  # noqa: E402

PASSED = 0
FAILED = 0


def check(name: str, condition: bool, detail: str = "") -> bool:
    global PASSED, FAILED
    if condition:
        PASSED += 1
        print(f"PASS  {name}")
        return True
    FAILED += 1
    print(f"FAIL  {name}" + (f"  — {detail}" if detail else ""))
    return False


# 真实的崩溃字面量, 取自 AGENTS.md 记录的现场日志。
REAL_CRASH_LINES = (
    "Native memory allocation (malloc) failed to allocate 112 bytes.",
    "Error detail: AllocateHeap",
)
BENIGN_LINES = (
    "Started CloudInfraApplication in 12.3 seconds",
    "Nacos started successfully, listening on 8848",
    "gc pause 12ms, heap 256m/512m",
)
# ⚠️ 不要在"良性日志"里放 'malloc' 字样 —— 判据只看子串是否出现, 不看语义。
# 我第一版写了 "malloc arena stats: ok" 想凑一个"含 malloc 但无害"的样本,
# 结果门禁如实判红, 而失败的是**夹具**: 一个真要上生产的判据不能靠
# "这行大概是好的"来豁免。想验证误伤面, 用不含关键词的普通日志(用例 7)。


def make_proc(stdout: str, stderr: str = "", rc: int = 0):
    return subprocess.CompletedProcess(args=[], returncode=rc,
                                       stdout=stdout.encode(), stderr=stderr.encode())


def stub_run_with(payloads: dict[str, object]) -> dict:
    """把 subprocess.run 换成按容器名返回预设结果。

    ⚠️ 必须连**退出码**一起替换 —— 只替换输出的话, "podman 报错"与
    "日志里没有 malloc" 都表现为空串, 三态塌成两态(第五课)。
    """
    original = mod.subprocess.run
    calls: list[str] = []

    def fake_run(args, **kwargs):
        name = args[-1]
        calls.append(name)
        value = payloads.get(name)
        if value == "TIMEOUT":
            raise subprocess.TimeoutExpired(cmd=args, timeout=1)
        if callable(value):
            return value()
        return value

    mod.subprocess.run = fake_run
    return {"restore": lambda: setattr(mod.subprocess, "run", original), "calls": calls}


def run_check() -> tuple[int, str]:
    buf = io.StringIO()
    with redirect_stdout(buf):
        code = mod.check()
    return code, buf.getvalue()


def main() -> int:
    # ---- 1. 真实崩溃字面量必须被判红 -------------------------------------
    payloads = {name: make_proc("\n".join(REAL_CRASH_LINES) + "\n")
                for name in mod.JVM_CONTAINERS}
    stub = stub_run_with(payloads)
    try:
        code, out = run_check()
    finally:
        stub["restore"]()
    check("真实崩溃日志 -> 退出码 1", code == 1, f"got {code}")
    check("真实崩溃日志 -> 输出含 FAIL", "FAIL" in out)
    check("真实崩溃日志 -> 点名容器", all(n in out for n in mod.JVM_CONTAINERS))

    # ---- 2. 正常日志必须判绿 --------------------------------------------
    payloads = {name: make_proc("\n".join(BENIGN_LINES) + "\n")
                for name in mod.JVM_CONTAINERS}
    stub = stub_run_with(payloads)
    try:
        code, out = run_check()
    finally:
        stub["restore"]()
    check("正常日志 -> 退出码 0", code == 0, f"got {code}")
    check("正常日志 -> 输出含 PASS", "PASS" in out)
    check("正常日志 -> 9/9 已探测", "9/9" in out)

    # ---- 3. 探针失效必须与健康分开(退出码 2, 不是 0 也不是 1) ----------
    # 这一条是本门禁存在的理由: 若探针失效被判 0, "没观测到"会被读成"健康"。
    payloads = {name: make_proc("", rc=125) for name in mod.JVM_CONTAINERS}
    stub = stub_run_with(payloads)
    try:
        code, out = run_check()
    finally:
        stub["restore"]()
    check("全部探针失效 -> 退出码 2", code == 2, f"got {code}")
    check("全部探针失效 -> 输出含 PROBE_FAILED", "PROBE_FAILED" in out)
    check("全部探针失效 -> 不出现 PASS", "PASS:" not in out)
    check("全部探针失效 -> 不出现 FAIL", "FAIL:" not in out)
    check("全部探针失效 -> 明说不代表健康", "不代表系统健康" in out)

    # ---- 4. 超时也必须算探针失效 ----------------------------------------
    payloads = {name: "TIMEOUT" for name in mod.JVM_CONTAINERS}
    stub = stub_run_with(payloads)
    try:
        code, _ = run_check()
    finally:
        stub["restore"]()
    check("全部超时 -> 退出码 2", code == 2, f"got {code}")

    # ---- 5. 混合: 部分命中 + 部分失效, 命中优先判红 ---------------------
    mixed = {}
    for i, name in enumerate(mod.JVM_CONTAINERS):
        if i == 0:
            mixed[name] = make_proc("\n".join(REAL_CRASH_LINES) + "\n")
        elif i == 1:
            mixed[name] = make_proc("", rc=125)
        else:
            mixed[name] = make_proc("\n".join(BENIGN_LINES) + "\n")
    stub = stub_run_with(mixed)
    try:
        code, out = run_check()
    finally:
        stub["restore"]()
    check("混合(命中+失效) -> 退出码 1", code == 1, f"got {code}")
    check("混合 -> 仍报告探针失效", "PROBE-FAIL" in out)

    # ---- 6. 判据大小写不敏感 -------------------------------------------
    payloads = {name: make_proc("native memory allocation (MALLOC) FAILED TO ALLOCATE 1 byte")
                for name in mod.JVM_CONTAINERS}
    stub = stub_run_with(payloads)
    try:
        code, _ = run_check()
    finally:
        stub["restore"]()
    check("大写形式也命中", code == 1, f"got {code}")

    # ---- 7. 判据不会误伤无 malloc 的普通日志 ----------------------------
    noisy = make_proc("loaded 500 classes, gc pause 12ms, thread dump ok\n")
    payloads = {name: noisy for name in mod.JVM_CONTAINERS}
    stub = stub_run_with(payloads)
    try:
        code, _ = run_check()
    finally:
        stub["restore"]()
    check("含 malloc 之外噪音的日志仍判绿", code == 0, f"got {code}")

    # ---- 8. 不得含任何百分比阈值(防止后人加回来) -----------------------
    src = Path(mod.__file__).read_text(encoding="utf-8")
    code_lines = []
    in_doc = False
    for raw in src.splitlines():
        line = raw.strip()
        if line.startswith('"""') and not in_doc:
            in_doc = True
            continue
        if line.endswith('"""') and in_doc:
            in_doc = False
            continue
        if not in_doc and not line.startswith("#") and line:
            code_lines.append(raw)
    body = "\n".join(code_lines)
    has_ratio = ("RATIO" in body) or ("0." in body and "threshold" in body.lower())
    check("代码中不含百分比阈值常量", not has_ratio,
          "本门禁刻意不含阈值 —— 新增阈值必须有新实测数据支撑")

    # ---- 9. 容器清单与真实 compose 服务对齐 ------------------------------
    check("容器清单非空", len(mod.JVM_CONTAINERS) >= 6)
    check("容器名不重复", len(set(mod.JVM_CONTAINERS)) == len(mod.JVM_CONTAINERS))

    # ---- 10. 真实环境跑一次(必须有真实证据, 不能只靠桩) ----------------
    # ⚠️ 这一项让本门禁在 CI/本机都至少真跑一次 podman logs。
    # 若 podman 不可用, 退出码 2 属"探针失效", 不算失败 —— 但必须打印出来。
    real_code, real_out = run_check()
    check("真实环境探测 -> 退出码 ∈ {0,1,2}", real_code in (0, 1, 2), f"got {real_code}")
    print(f"     真实环境结果: rc={real_code}, "
          f"{'有 malloc 失败' if real_code == 1 else '无 malloc 失败' if real_code == 0 else '探针失效'}")

    print()
    print("═══ 负向对照 ═══")
    # 把判据改坏: 去掉 'failed to allocate' 这个标记。
    original_markers = mod.MALLOC_MARKERS
    mod.MALLOC_MARKERS = ("__never_matches__",)
    payloads = {name: make_proc("Native memory allocation (malloc) failed to allocate 112 bytes.\n")
                for name in mod.JVM_CONTAINERS}
    stub = stub_run_with(payloads)
    try:
        broken_code, _ = run_check()
    finally:
        stub["restore"]()
        mod.MALLOC_MARKERS = original_markers
    check("判据改坏后不再命中(证明用例 1 的红是真红)", broken_code == 0, f"got {broken_code}")

    # 判据改坏后, 探针失效三态是否还能守住?
    mod.MALLOC_MARKERS = ("__never_matches__",)
    payloads = {name: make_proc("", rc=125) for name in mod.JVM_CONTAINERS}
    stub = stub_run_with(payloads)
    try:
        still_two, _ = run_check()
    finally:
        stub["restore"]()
        mod.MALLOC_MARKERS = original_markers
    check("判据改坏后探针失效仍返回 2", still_two == 2, f"got {still_two}")

    # 恢复确认
    payloads = {name: make_proc("\n".join(REAL_CRASH_LINES) + "\n")
                for name in mod.JVM_CONTAINERS}
    stub = stub_run_with(payloads)
    try:
        restored, _ = run_check()
    finally:
        stub["restore"]()
    check("恢复后重新判红", restored == 1, f"got {restored}")

    print()
    print(f"═══ 结果: {PASSED} 通过 / {FAILED} 失败 ═══")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
