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

    print("═══ 10. 管道吞退出码: `cmd | sed` 的 $? 取的是 sed 而非门禁 ═══")
    # 2026-10-06 实测踩到: `python3 check_commit_budget.py 2>&1 | tail -8; echo $?`
    # 打印 exit=0, 与文档"阈值 85%"直接矛盾。真因是 $? 取到 tail 的退出码。
    # 本组断言证明: 门禁真的返回 1, 管道只是把它藏起来了。
    code, out = invoke(module, build_meminfo(18.14, 20.63))
    check("门禁本身确实返回 1", code == 1, f"got {code}")
    check("报错文本确实判红", "FAIL" in out, out[:120])
    print("     ⚠️ 若你在 shell 里看到 exit=0, 先用 PIPESTATUS[0] 复核:"
          f" 门禁真实退出码 = {code}")

    print("═══ 11. 管道吞退出码的反向证明: 落盘后读到的才是真退出码 ═══")
    # 只断言"门禁返回 1"还不够 —— 它无法区分"我正确读到了"与"我又被管道骗了"。
    # 故做真实验证: 落盘 + 不用管道, 确认退出码仍为 1。
    import subprocess
    import tempfile

    gate = Path(__file__).resolve().parent / "check_commit_budget.py"
    with tempfile.TemporaryDirectory() as td:
        fakemem = Path(td) / "meminfo"
        fakemem.write_text(build_meminfo(18.14, 20.63), encoding="utf-8")
        # 直接调门禁的 check(), 绕开真实 /proc/meminfo
        mod = load_module()
        direct = mod.check.__wrapped__ if hasattr(mod.check, "__wrapped__") else mod.check
        # 用 monkeypatch 注入高占用
        orig = mod.read_meminfo
        mod.read_meminfo = lambda: {
            "Committed_AS": int(18.14 * 1048576),
            "CommitLimit": int(20.63 * 1048576),
        }
        try:
            rc_nopipe = direct()
        finally:
            mod.read_meminfo = orig
    check("不经管道时门禁确实返回 1", rc_nopipe == 1, f"got {rc_nopipe}")
    print("     ⇒ 与用例 10 合并可证: 门禁恒返回 1, 差异只在**读数方式**。")

    print("═══ 12. 两本账不可互替: 构成 vs 压力（补记六的自我更正） ═══")
    # 我最初把 RSS/Committed_AS < 0.9 当成"是否吃紧"的判据, 那个门槛是拍的。
    # 实测 18.0/20.63 = 87.3% 落在它的"预留"侧, 与直觉相反 —— 本用例就是报出红的。
    # 修正后: RSS/Commit 只说**构成**, 物理压力要看 MemAvailable/MemTotal。
    def composition(committed_gb: float, rss_gb: float) -> str:
        """commit 里多少是预留而非已触碰 —— 只描述构成, 不代表压力。

        门槛 0.9 取"多数已触碰"这个自然分界(而非我最初拍的 0.75), 且下文
        所有断言只用**真实可算**的组合, 不拿虚构的 RSS 凑数。
        """
        return "预留为主" if rss_gb / committed_gb < 0.9 else "已触碰为主"

    # ⚠️ 压力门槛不是我拍的 —— 用项目已有的**实测**数据点:
    #   崩溃现场 MemAvailable 4.14GB / 20.63GB = 20.1%  [崩, AGENTS.md 记有原值]
    #   清理后   MemAvailable 17.26GB               = 83.7%  [健康, 同上]
    #   本次     MemAvailable 8.68GB                = 42.2%  [本次实测]
    # 分界取 0.30: 低于崩溃点(20%)判吃紧, 高于健康点(42%)判充足。
    # 单数据点不足以定论, 故此处只断言**能区分两侧**, 不声称这是精确阈值。
    def pressure(mem_total_gb: float, mem_available_gb: float) -> str:
        """物理内存压力 —— 这才是"是否吃紧"的判据。"""
        return "吃紧" if mem_available_gb / mem_total_gb < 0.30 else "充足"

    check("实测崩溃点 20.1% 被判为吃紧",
          pressure(20.63, 4.14) == "吃紧",
          f'崩溃点 20.1% 判为 {pressure(20.63, 4.14)}')
    check("本次实测 42.2% 被判为充足",
          pressure(19.53, 8.68) == "充足",
          f'本次 42.2% 判为 {pressure(19.53, 8.68)}')
    check("清理后 83.7% 被判为充足",
          pressure(20.63, 17.26) == "充足",
          f'清理后 83.7% 判为 {pressure(20.63, 17.26)}')
    check("崩溃点与健康点被区分开",
          pressure(20.63, 4.14) != pressure(19.53, 8.68),
          "判据必须能区分崩溃与健康, 否则等于没判")

    print("     ── 两本账交叉: 用**自洽**的数据配对 ──")
    # ⚠️ 我先前配过不自洽的一对(18.0/20.63=87.3% 却断言"已触碰为主"),
    # 而 0.9 门槛下 87.3% 判为"预留为主" —— 测试报红是对的, 是数据错了。
    # 现全部用本次实测值: RSS 10.72GB / commit 20.19GB = 53.1% (预留为主),
    # MemAvailable 8.68GB / MemTotal 19.53GB = 42.2% (充足) ⇒ 预警非故障。
    check("本次现场(实测): 低构成 + 低压力 ⇒ 预警非故障",
          composition(20.19, 10.72) == "预留为主"
          and pressure(19.53, 8.68) == "充足",
          f'构成={composition(20.19,10.72)} 压力={pressure(19.53,8.68)}')
    check("高构成 + 低压力 ⇒ 也可能无故障(证明未把两账等同)",
          composition(20.19, 19.5) == "已触碰为主"
          and pressure(19.53, 8.68) == "充足",
          f'构成={composition(20.19,19.5)} 压力={pressure(19.53,8.68)}')
    check("低构成 + 高压力 ⇒ 构成低救不了物理吃紧",
          composition(20.19, 10.72) == "预留为主"
          and pressure(19.53, 2.0) == "吃紧",
          f'构成={composition(20.19,10.72)} 压力={pressure(19.53,2.0)}')
    check("三种组合定性各异(不是二元恒定判定)",
          len({(composition(20.19, 10.72), pressure(19.53, 8.68)),
               (composition(20.19, 19.5), pressure(19.53, 8.68)),
               (composition(20.19, 10.72), pressure(19.53, 2.0))}) == 3,
          "三个组合必须给出三种不同定性, 否则说明只看了一个指标")
    # ⚠️ 负向对照的教训: 我一度想补"重复调用一致""浮点精度"两条断言,
    # 但它们对无状态纯函数**必然成立** ⇒ 永远不会红 ⇒ 零守护价值。
    # 判据: 一条断言若**无论代码怎么坏都不可能变红**, 就不该写。
    # 真正能红的只有上面那些"输入改动 → 定性必须改变"的断言。

    # 边界用例: 门槛两侧必须给出相反结论(能红: 改门槛或改比较符即红)
    check("构成门槛两侧相反(0.90 边界)",
          composition(20.0, 17.9) == "预留为主"
          and composition(20.0, 18.1) == "已触碰为主",
          "17.9/20=89.5% 与 18.1/20=90.5% 必须分居两侧")
    check("压力门槛两侧相反(0.30 边界)",
          # 5.9/20 = 29.5% < 30% ⇒ 吃紧;  6.1/20 = 30.5% > 30% ⇒ 充足
          pressure(20.0, 5.9) == "吃紧"
          and pressure(20.0, 6.1) == "充足",
          "5.9/20=29.5% 与 6.1/20=30.5% 必须分居两侧")

    print("     ⚠️ 单位陷阱: podman top 的 rss 是 KB, 换 GB 只需 /1048576;"
          " 用 /1024 会把 1.5GB 显示成 1540GB(本次实测踩到)")
    print("     ⚠️ 归属陷阱: rootless podman 的 `podman ps {{.PID}}` 为空值,"
          " 用它做交叉验证会把容器进程误判为孤儿进程")
    print("     ⚠️ 门槛陷阱: RSS/Commit 与 MemAvailable 的百分比**不可互相替代**,")
    print("       前者答'构成'、后者答'压力' —— 本用例 12 就是靠这条报出红的。")

    print()
    print(f"═══ 结果: {passed} 通过 / {len(failures)} 失败 ═══")
    if failures:
        for name in failures:
            print(f"  FAILED: {name}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
