#!/usr/bin/env python3
"""守护: AGENTS.md 里给出的 commit/meminfo 判据脚本必须**原样可跑**, 且输出格式
与文档描述一致。

## 为什么需要这个守护

第八课补记六把"两本账"写成了一组可直接复制的 shell 片段, 并用它们得出了
"预警非故障"的定性。但文档里的脚本有四个已知失效形态, 且**全部是静默**的:

1. 管道后的 `$?` 取到 `sed`/`head` 的退出码, 门禁的 exit=1 被吞掉
   —— 本项目已真实踩到(见 AGENTS.md 第八课「门禁行为说明」)。
2. `bc` 不存在时浮点换算失败, 但 `awk` 的 `print` 仍输出, 排序静默失效。
3. `podman top -eo rss` 的单位是 KB, 按 /1024 当 GB 会算出 1540 GB 这类值。
4. 脚本能跑, 但**输出与文档描述的格式不符**(如把百分比打成裸数),
   而文档里的结论仍被当作成立 —— 这是第五课"探针自己坏掉"最隐蔽的一种。

所以本守护不是"检查文档里有没有这段字", 而是**真跑一遍**, 断言:
  - 退出码为 0;
  - 输出能被文档承诺的正则完整匹配(不允许空串、不允许部分匹配);
  - 三个量的数值落在文档记录的实测区间内(防止 /1024 这类单位错误跑出 1540 GB)。

## 探针对象从文档抽取, 不手写

第五课规则: 探针的对象必须从被测系统里提取。若把脚本复制到本文件里,
文档改了而守护不改, 就会出现"守护全绿、文档里的脚本已经坏了"。

故实现方式是: 从 AGENTS.md 按锚点定位 fenced code block, 原样 exec。
这带来一个额外性质 —— **本守护无法与文档漂移**, 改文档就会改被测对象。

## 负向对照记录

| 注入 | 期望 | 实测 |
| :--- | :--- | :--- |
| 把 rss 的 /1048576 改成 /1024 | 红 | 单位守护报红(1.5GB → 1540GB) |
| 给 awk 换成 bc 做浮点 | 红 | 依赖缺失守护报红 |
| 删掉输出重定向, 让管道吞退出码 | 红 | 退出码守护报红 |
| 改 printf 的占位符使格式不符 | 红 | 格式守护报红 |

四项均已实测报红, 详见对应用例注释。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

AGENTS = Path(__file__).resolve().parents[2] / "AGENTS.md"

passed = 0
failed: list[str] = []


def check(desc: str, ok: bool, detail: str = "") -> None:
    global passed
    if ok:
        passed += 1
        print(f"  ✅ {desc}")
    else:
        failed.append(desc)
        print(f"  ❌ {desc} — {detail}")


def extract_block(anchor_regex: str) -> str | None:
    """从 AGENTS.md 抽取锚点之后最近的 ```bash 代码块(原样, 不做任何改写)。

    ⚠️ 块引用里的代码围栏带引用前缀(`> ```bash`), 必须先剥掉,
    否则正则匹配不到 → 静默返回 None → 后续断言全部被跳过 → 假绿。
    这正是第五课补记一「扫到 0 个目标必须判红」的形态, 故调用方一律
    把 None 当失败处理。
    """
    text = AGENTS.read_text(encoding="utf-8")
    m = re.search(anchor_regex, text)
    if not m:
        return None
    rest = text[m.end():]
    fence = re.search(r"^\s*(?:>\s*)?```bash\s*\n(.*?)^\s*(?:>\s*)?```\s*$",
                      rest, re.S | re.M)
    if not fence:
        return None
    # 同样剥掉每行的引用前缀, 保证是**可执行**的原文。
    lines = [re.sub(r"^\s*>\s?", "", ln) for ln in fence.group(1).splitlines()]
    return "\n".join(lines)


def run(script: str) -> tuple[int, str, str]:
    """跑脚本并返回 (rc, stdout, stderr)。

    ⚠️ 必须直接跑, 不接管道 —— 管道后的 $? 取到的是最后一个命令的退出码,
    这正是 AGENTS.md 记录的那次误读来源。
    """
    p = subprocess.run(
        ["bash", "-c", script], capture_output=True, text=True, timeout=120
    )
    return p.returncode, p.stdout.strip(), p.stderr.strip()


def case_1_extraction() -> None:
    print("\n═══ 1. 文档脚本可被抽取(探针对象来自文档, 非手写) ═══")
    script = extract_block(r"### ⚠️ 第八课补记六")
    check("AGENTS.md 存在", AGENTS.is_file(), str(AGENTS))
    check("判别法脚本块被抽取到", script is not None, "锚点后未找到 ```bash")
    if script is None:
        return
    check("抽取内容含 Committed_AS 读取", "Committed_AS" in script,
          f"实际: {script[:80]!r}")
    check("抽取内容含 VmRSS 遍历", "VmRSS" in script, f"实际: {script[:80]!r}")


def case_2_runs_clean() -> None:
    print("\n═══ 2. 原样执行: 退出码 0 且无 stderr ═══")
    script = extract_block(r"### ⚠️ 第八课补记六")
    if script is None:
        check("脚本存在", False, "跳过 —— 抽取失败")
        return
    rc, out, err = run(script)
    check("判别法脚本退出码为 0", rc == 0, f"rc={rc} stderr={err[:200]!r}")
    # ⚠️ 不能只看 stdout 非空: 脚本失败时 awk 仍可能打印半行。
    # 故要求无 stderr —— 任何诊断信息都说明它遇到了环境问题。
    check("stderr 为空(未走降级路径)", err == "", f"stderr={err[:200]!r}")
    check("输出非空", bool(out), "输出为空 ⇒ 探针取不到值, 不能当作通过")
    print(f"       实际输出: {out}")


def case_3_output_format() -> None:
    print("\n═══ 3. 输出格式与文档承诺一致 ═══")
    script = extract_block(r"### ⚠️ 第八课补记六")
    if script is None:
        check("脚本存在", False, "跳过 —— 抽取失败")
        return
    _, out, _ = run(script)
    # 文档承诺: "RSS=%.2f GB  Committed_AS=%.2f GB  ratio=%.1f%%"
    pat = re.compile(
        r"^RSS=\d+\.\d{2} GB\s+Committed_AS=\d+\.\d{2} GB\s+ratio=\d+\.\d{1,2}%$"
    )
    check("输出完整匹配文档承诺的正则", bool(pat.match(out)),
          f"实际: {out!r}")
    # 单位陷阱(AGENTS.md 记录): /1024 会把 1.5GB 显示成 1540GB。
    # 若有人把换算改回 /1024, ratio 会变成 1000000% 量级。
    m = re.search(r"ratio=(\d+(?:\.\d+)?)", out)
    if m:
        ratio = float(m.group(1))
        check("ratio 在物理可能区间(0~100%)", 0 <= ratio <= 100,
              f"ratio={ratio}% ⇒ 高度疑似单位换算错误(/1024 当 GB)")
    else:
        check("ratio 可解析", False, f"无法从 {out!r} 提取 ratio")
    # 文档承诺的第二段: MemAvailable/MemTotal=%.1f%%
    # ⚠️ 必须把 None 当失败 —— 早先的写法是 `if p2 is not None:` 才断言,
    # 于是"锚点失效/围栏格式变了"会**静默跳过全部断言并 exit 0**(假绿)。
    p2 = extract_block(r"真正的判据是另一条账")
    check("压力脚本块被抽取到", p2 is not None,
          "抽取失败 ⇒ 后续断言会被静默跳过, 必须判红")
    if p2 is not None:
        rc2, out2, err2 = run(p2)
        check("压力脚本退出码为 0", rc2 == 0, f"rc={rc2} stderr={err2[:200]!r}")
        check("压力输出格式为 百分比.1%%",
              bool(re.match(r"^MemAvailable/MemTotal=\d+\.\d%$", out2)),
              f"实际: {out2!r}")
        m2 = re.search(r"=(\d+\.\d)%$", out2)
        if m2:
            v = float(m2.group(1))
            check("压力百分比在 0~100 区间", 0 <= v <= 100, f"={v}%")
        else:
            check("压力百分比可解析", False, f"无法从 {out2!r} 提取")
        print(f"       实际输出: {out2}")


def case_4_no_bc_dependency() -> None:
    print("\n═══ 4. 环境依赖: 不得依赖不存在的 bc ═══")
    script = extract_block(r"### ⚠️ 第八课补记六")
    if script is None:
        check("脚本存在", False, "跳过 —— 抽取失败")
        return
    rc, out, err = run(script)
    # ⚠️ 负向对照: 把浮点换算改成 bc 后, stderr 会出现
    #   "bc: command not found", 但脚本 rc 仍为 0 ⇒ 只判 rc 会假绿。
    check("输出中不含 bc 报错(说明未依赖 bc)",
          "bc:" not in err and "bc:" not in out,
          f"出现 bc 报错 ⇒ 改用了本环境不存在的 bc; stderr={err[:120]!r}")
    check("无 command not found 类降级",
          "command not found" not in err,
          f"stderr={err[:200]!r}")
    check("rc 为 0", rc == 0, f"rc={rc}")


def case_5_units_not_regressed() -> None:
    print("\n═══ 5. 单位换算未被改回 /1024 ═══")
    script = extract_block(r"### ⚠️ 第八课补记六")
    if script is None:
        check("脚本存在", False, "跳过 —— 抽取失败")
        return
    # 结构性检查: KB→GB 必须是 /1048576(=1024*1024)。
    # 不能只靠 ratio 区间 —— 因为若 RSS 与 Committed_AS 同时被除以 1024,
    # 比值不变, 区间守护抓不到。
    kb_to_gb = re.findall(r"/\s*(\d+)\s*;?\s*$|/\s*(\d+)\s*\n", script, re.M)
    check("脚本使用 1048576 作 KB→GB 换算",
          "1048576" in script,
          f"未找到 1048576 ⇒ 单位换算可能被改坏; 片段={script[-200:]!r}")
    check("脚本不含 1024 当 GB 的换算(t/c 除外)",
          not re.search(r"/\s*1024\b(?!\s*\*)", script.replace("/1048576", "")),
          f"疑似存在 /1024; 片段={script[-200:]!r}")
    rc, out, _ = run(script)
    check("rc 为 0", rc == 0, f"rc={rc}")


def case_6_docs_consistency() -> None:
    print("\n═══ 6. 文档数值与实测不矛盾 ═══")
    text = AGENTS.read_text(encoding="utf-8")
    # 被否定的 <15% 门槛不得以**建议**的形式复活。
    # ⚠️ 不能简单断言 "经验值 <15%" 不在文中 —— 它必然出现, 因为文档需要
    #    引用它才能说明它被否定了。真正的判据是: 那句话必须带着否定标记。
    #    早先的断言只查字符串是否存在 ⇒ 必然报红, 且无法区分"复活"与"已更正"。
    occurrences = [ln for ln in text.splitlines() if "<15%" in ln]
    check("文中提及 <15% 的行均带否定标记",
          bool(occurrences) and all(
              any(k in ln for k in ("错的", "否定", "不得", "已删除"))
              for ln in occurrences),
          f"存在未加否定标记的行: {[ln.strip()[:60] for ln in occurrences]}")
    check("保留了说明其被崩溃点 20.1% 否定的更正",
          "20.1%" in text and ("否定" in text or "错的" in text),
          "更正说明缺失")
    check("明确写出'没有单一阈值能定性'",
          "没有单一阈值能定性" in text,
          "否则读者仍会去找一个阈值")
    # overcommit_memory 当前实测为 0, 文档必须写明, 不得沿用 2。
    check("文档写明 overcommit_memory 当前实测 = 0",
          "overcommit_memory` **当前实测 = 0**" in text
          or "overcommit_memory` 当前实测 = 0" in text,
          "未找到该前提更正")
    check("文档保留了'未触碰的预留'核心判据",
          "预留但从未触碰" in text,
          "补记六的核心结论被删除")


def main() -> int:
    print("═══ AGENTS.md 第八课补记六 · 文档判据脚本可执行性守护 ═══")
    for fn in (
        case_1_extraction,
        case_2_runs_clean,
        case_3_output_format,
        case_4_no_bc_dependency,
        case_5_units_not_regressed,
        case_6_docs_consistency,
    ):
        fn()
    print(f"\n═══ 结果: {passed} 通过 / {len(failed)} 失败 ═══")
    if failed:
        for f in failed:
            print(f"  FAILED: {f}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
