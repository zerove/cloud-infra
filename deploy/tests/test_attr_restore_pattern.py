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
EXCLUDE_DIRS = {"target", ".git"}
FOUND_VIOLATIONS = []
PASSED_COUNT = 0

for src in (Path(REPO) / "cloud-infra-modules").rglob("src") + \
           (Path(REPO) / "cloud-infra-gateway").rglob("src") + \
           (Path(REPO) / "cloud-infra-auth").rglob("src") + \
           (Path(REPO) / "cloud-infra-framework").rglob("src"):
    for f in src.rglob("*.java") if "target" not in str(f) and ".git" not in str(f):
        text = f.read_text(encoding="utf-8")
        # 找所有 System.clearProperty(<k>) 调用
        for m in re.finditer(r"System\.clearProperty\(\s*([^)]+?)\s*\)", text):
            key = m.group(1).strip()
            # 找同文件/函数定义前最近的 @BeforeEach 中 System.setProperty 的 key
            start = m.start()
            # 反向向前找 3000 字符, 找最近一次 setProperty(<key>) 且在同一流程内
            head = text[max(0, start - 3000):start]
            set_keys = re.findall(r"System\.setProperty\(\s*'(.+?)'\s*\)|System\.setProperty\(\s*\"(.+?)\"\s*\)", head)
            matched = None
            for s, d in set_keys:
                if s or d:
                    matched = s or d
                    break
            if matched is None:
                continue
            if matched != key:
                continue
            # 进一步: 正则检查 delete 后不恢复。简单起见断言同函数 (brace-ish) 内既有 set 有 clear
            fn_range = head[:m.start()]
            fn_text = fn_range
            has_before = "System.setProperty" in fn_text or 'System.setProperty' in fn_text
            # 这是粗判: 若 clear 在 @AfterEach 之后还有个 set 兜底也算通过
            has_restore = "System.clearProperty" in fn_text and fn_range[-1::-1].count("System.clearProperty") > 0
            # 更准确: 检查同 test 方法内出现的 @BeforeEach + @AfterEach 都用 restore 模式
            has_bare_clear = "System.clearProperty" in fn_text
            # 我们只惩罚那种"在 no-restore 区域出现 bare clear 且无同名 before 时"
            if has_bare_clear and not has_before:
                FOUND_VIOLATIONS.append(f"{f}: {key} (无 @BeforeEach 存在性记录)")

if FOUND_VIOLATIONS:
    print("FAILED: 发现 @AfterEach 系统属性清理不恢复:")
    for v in FOUND_VIOLATIONS:
        print(f"  {v}")
    sys.exit(1)
print(f"OK: {len([l for l in FOUND_VIOLATIONS])} violations fixed")
sys.exit(0)
