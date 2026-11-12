#!/usr/bin/env python3
"""门禁: @AfterEach 清理系统属性时必须还原原值 (AGENTS.md §5 规则)。
对应报告节: 质量 Q10

## 2026-11-08 重写原因（逻辑自相矛盾 + 根目录错，永不判红）

1. `ROOT = parents[1]` 指向 `deploy/` —— 四个扫描根全部不存在，
   `continue` 后得到 0 违规，打印 OK。
2. 检测逻辑自我矛盾：先要求 `"System.setProperty" in fn_text` 才继续，
   随后调用 `has_bare_clear_no_before()`，而它要求
   `"System.setProperty" not in fn_text` —— 二者同时成立不可能，
   VIOLATIONS **恒空**（即使路径修好也永不判红）。

## 判据（两种安全形态）

  A) 捕获式: `String orig = System.clearProperty(KEY)`，且文件后文用 orig 还原
     （`System.setProperty(KEY, orig)` 或 `if (orig == null)`）。
  B) 条件式: `System.clearProperty(...)` 前 200 字符内最近一个 `== null`
     之后没有 `}` / `;`（即在 if-null 分支里直接清除）。

  其余 —— 裸 clear 且不还原 —— 判红。

负向对照: 用含裸 clear 的临时 fixture 根目录运行 -> 必须报红；
          用真实仓库运行 -> 0 违规。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ROOTS = (
    "cloud-infra-modules",
    "cloud-infra-gateway",
    "cloud-infra-auth",
    "cloud-infra-framework",
    "cloud-infra-common",
    "cloud-infra-test-support",
)
EXCLUDE = {"target", ".git"}

CAPTURE_RE = re.compile(
    r"String\s+(\w+)\s*=\s*System\.clearProperty\s*\(\s*([^)]+?)\s*\)")
BARE_RE = re.compile(r"System\.clearProperty\s*\(")


def strip_comments(text: str) -> str:
    """剥掉注释再判定 —— 注释里提到 clearProperty 不算调用。

    实测: SystemGrpcSecurityBoundaryTest 的 javadoc 写着
    `{@code System.clearProperty(...)}` 作为历史说明, 直接正则会把它
    当成裸调用而误报。
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def analyze(text: str):
    """返回违规描述列表（文件内相对行号）。"""
    problems = []
    captures = list(CAPTURE_RE.finditer(text))
    capture_spans = [(m.start(), m.end()) for m in captures]

    for m in captures:
        var, key = m.group(1), m.group(2).strip()
        tail = text[m.end():]
        restored = re.search(
            r"System\.setProperty\(\s*" + re.escape(key) + r"\s*,\s*"
            + re.escape(var) + r"\s*\)", tail)
        guarded = re.search(
            r"if\s*\(\s*" + re.escape(var) + r"\s*==\s*null\s*\)", tail)
        if not (restored or guarded):
            line = text.count("\n", 0, m.start()) + 1
            problems.append(f"L{line}: 捕获式 clear({key}) 未见用 {var} 还原")

    for m in BARE_RE.finditer(text):
        if any(a <= m.start() < b for a, b in capture_spans):
            continue
        window = text[max(0, m.start() - 200):m.start()]
        idx = window.rfind("== null")
        if idx != -1 and not re.search(r"[};]", window[idx + len("== null"):]):
            continue  # 条件式: 位于 if-null 分支
        line = text.count("\n", 0, m.start()) + 1
        problems.append(f"L{line}: 裸 clear 且不在 if-null 分支 —— 会抹掉原值")
    return problems


def main(root: Path = ROOT) -> int:
    scan_roots = [root / name for name in ROOTS]
    if not any(r.is_dir() for r in scan_roots):
        # 负向对照/测试允许直接指向只含 java 文件的 fixture
        scan_roots = [root]

    violations = []
    scanned = 0
    for scan_root in scan_roots:
        if not scan_root.is_dir():
            continue
        for java in sorted(scan_root.rglob("*.java")):
            rel = java.as_posix()
            if any(d in rel.split("/") for d in EXCLUDE):
                continue
            try:
                raw = java.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if "System.clearProperty" not in raw:
                continue
            scanned += 1
            text = strip_comments(raw)
            for p in analyze(text):
                violations.append(f"{java}: {p}")

    if scanned == 0:
        print("FAIL: 一个含 System.clearProperty 的测试文件都没扫到 —— "
              "扫描根漂移或探针坏了，不能判绿", file=sys.stderr)
        return 2
    if violations:
        print("FAILED: 发现 @AfterEach 系统属性清理不恢复:")
        for v in violations:
            print("  " + v)
        return 1
    print(f"OK: 扫描 {scanned} 个含 clearProperty 的测试文件, 均为条件式/捕获式还原")
    return 0


if __name__ == "__main__":
    cli_root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT
    sys.exit(main(cli_root))
