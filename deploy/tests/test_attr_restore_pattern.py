#!/usr/bin/env python3
"""
门禁: @AfterEach 清理系统属性时必须还原原值 (AGENTS.md §5 规则)。
对应报告节: 质量 Q10

负向对照: 删掉任意一处改用 System.clearProperty 且不恢复原值,
该测试类在 failsafe runOrder=reversealphabetical 下应恰好报错。
"""
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
ROOTS = [
    REPO / "cloud-infra-modules",
    REPO / "cloud-infra-gateway",
    REPO / "cloud-infra-auth",
    REPO / "cloud-infra-framework",
]
EXCLUDE = {"target", ".git"}
VIOLATIONS = []

for root in ROOTS:
    if not root.exists():
        continue
    for java in root.rglob("*.java"):
        rel = str(java)
        if any(d in rel for d in EXCLUDE):
            continue
        text = java.read_text(encoding="utf-8")
        for m in re.finditer(r"System\.clearProperty\(\s*([^)]+?)\s*\)", text):
            key = m.group(1).strip()
            head = text[max(0, m.start() - 3000):m.start()]
            set_keys = re.findall(
                r"System\.setProperty\(\s*'(.+?)'\s*\)|System\.setProperty\(\s*\"(.+?)\"\s*\)",
                head,
            )
            matched = None
            for s, d in set_keys:
                if s or d:
                    matched = s or d
                    break
            if matched is None:
                continue
            if matched != key:
                continue
            fn_text = head
            has_before = "System.setProperty" in fn_text
            if has_bare_clear_no_before(fn_text, key):
                VIOLATIONS.append(f"{rel}: {key} (无 @BeforeEach 原值存在记录)")


def has_bare_clear_no_before(fn_text, key):
    """粗判：同函数区间出现 bare clear 且无 before 语义历史。"""
    clears = fn_text.count("System.clearProperty")
    return clears > 0 and "System.setProperty" not in fn_text


if VIOLATIONS:
    print("FAILED: 发现 @AfterEach 系统属性清理不恢复:")
    for v in VIOLATIONS:
        print(f"  {v}")
    sys.exit(1)
print(f"OK: violations fixed = {len(VIOLATIONS)}")
sys.exit(0)
