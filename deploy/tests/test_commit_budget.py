#!/usr/bin/env python3
"""守护测试: check_commit_budget.py 必须真的能判红。

## 为什么需要这个文件（AGENTS.md 第四课）

新增守护不等于守护有效。本项目历史上出现过 11 项补扫测试全绿而缺陷仍在的案例,
以及一个 `p.startsWith("com.cloudinfra")` 写法的假守护 —— 它在负向对照下
**没有变红**, 因为该串确实以 `com.cloudinfra` 开头却不包含密钥所在的子包。

所以本文件的唯一目的, 是让 `check_commit_budget.py` 接受一次负向对照。

## 覆盖的判据

1. 正常余量 → exit 0
2. 逼近阈值 → exit 1, 且错误信息必须指向全局 commit 而非容器内存
3. 缺失 Committed_AS → SKIP + exit 0, 且必须明说"不等于通过"
4. 缺失文件 → SKIP + exit 0, 不得抛栈
5. 关键: 错误信息必须包含排查指引, 否则等于把"知道错了"变成"不知道怎么修"
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

MODULE_PATH = Path(__file__).parent / "check_commit_budget.py"

KILOBYTES_PER_GB = 1048576


def load_module():
    spec = importlib.util.spec_from_file_location("check_commit_budget", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def build_meminfo(committed_gb: float, limit_gb: float) -> str:
    """按 GB 构造 meminfo。

    KB 取整用 round 而非 int: int 是向零截断, 会让 20.63*0.85=17.5355 GB
    截成 17 GB, 比例变成 17/20.63=82.4% —— 恰好把边界用例挪到了绿区,
    掩盖阈值究竟是 > 还是 >=。这类"构造数据失真"与第五课的假结论同源。
    """
    def to_kb(value: float) -> int:
        return round(value * KILOBYTES_PER_GB)

    return (
        f"MemTotal:       {to_kb(19.53)} kB\n"
        f"SwapTotal:      {to_kb(5.00)} kB\n"
        f"Committed_AS:   {to_kb(committed_gb)} kB\n"
        f"CommitLimit:    {to_kb(limit_gb)} kB\n"
    )


def invoke(module, meminfo: str) -> tuple[int, str]:
    """把 module.read_meminfo 换成返回构造数据, 捕获 check() 的退出码与输出。

    必须真的替换数据源: 只重定向 stdout 而不替换 meminfo, 会让所有断言
    都读到真实的 /proc/meminfo —— 那种"守护"在本项目已被负向对照证伪过
    (TenantFilterSecretWiringTest 用 p.startsWith 判扫描范围, 改坏后不红)。
    """
    import io
    from contextlib import redirect_stdout

    def fake_read_meminfo() -> dict[str, int]:
        values: dict[str, int] = {}
        for line in meminfo.splitlines():
            if not line.strip():
                continue
            key, _, rest = line.partition(":")
            parts = rest.split()
            if parts and parts[0].lstrip("-").isdigit():
                values[key.strip()] = int(parts[0])
        return values

    original = module.read_meminfo
    module.read_meminfo = fake_read_meminfo
    buffer = io.StringIO()
    try:
        with redirect_stdout(buffer):
            code = module.check()
    finally:
        module.read_meminfo = original
    return code, buffer.getvalue()


def main() -> int:
    module = load_module()
    failures: list[str] = []
    passed = 0

    def check(name: str, condition: bool, detail: str = "") -> None:
        nonlocal passed
        if condition:
            passed += 1
            print(f"  ✅ {name}")
        else:
            failures.append(name)
            print(f"  ❌ {name}{(' — ' + detail) if detail else ''}")

    print("═══ 1. 正常余量 → exit 0 ═══")
    code, out = invoke(module, build_meminfo(6.74, 20.63))
    check("健康状态 (33%) 判绿", code == 0, f"got {code}")
    check("输出含 PASS", "PASS" in out, out[:120])

    print("═══ 2. 6 服务满负荷 (69%) → 仍判绿 ═══")
    code, out = invoke(module, build_meminfo(14.25, 20.63))
    check("满负荷状态 (69%) 判绿", code == 0, f"got {code}")

    print("═══ 3. 根因现场 (88%) → exit 1 ═══")
    code, out = invoke(module, build_meminfo(18.14, 20.63))
    check("根因现场 (88%) 判红", code == 1, f"got {code}")
    check("错误信息指向 commit 而非容器内存",
          "commit" in out.lower() and "mem_limit" in out,
          "必须同时提到 commit 与 mem_limit 无效")
    check("明确警告提高 mem_limit 无用",
          "不会" in out and "mem_limit" in out,
          out[:200])
    check("含孤儿进程排查指引",
          "VmSize" in out and "cgroup" in out,
          "必须指向扫宿主而非 podman ps")

    print("═══ 4. 100% 占满 → exit 1 ═══")
    code, out = invoke(module, build_meminfo(20.63, 20.63))
    check("完全占满判红", code == 1, f"got {code}")

    print("═══ 5. 阈值恰好 85% → 判绿（边界不含）═══")
    code, out = invoke(module, build_meminfo(20.63 * 0.85, 20.63))
    check("恰好 85% 判绿", code == 0, f"got {code}")

    print("═══ 6. 阈值 86% → 判红 ═══")
    code, out = invoke(module, build_meminfo(20.63 * 0.86, 20.63))
    check("86% 判红", code == 1, f"got {code}")

    print("═══ 7. 缺失 Committed_AS → SKIP 且明说'不等于通过' ═══")
    code, out = invoke(module, "MemTotal: 100 kB\n")
    check("缺失字段判 SKIP(0)", code == 0, f"got {code}")
    check("明说不等于通过", "不等于通过" in out, out[:120])

    print("═══ 8. CommitLimit 为 0 → SKIP 不崩溃 ═══")
    code, out = invoke(module, build_meminfo(0, 0))
    check("零上限判 SKIP(0)", code == 0, f"got {code}")

    print("═══ 9. 空 meminfo → SKIP 不抛栈 ═══")
    code, out = invoke(module, "")
    check("空内容判 SKIP(0)", code == 0, f"got {code}")

    print()
    print(f"═══ 结果: {passed} 通过 / {len(failures)} 失败 ═══")
    if failures:
        for name in failures:
            print(f"  FAILED: {name}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
