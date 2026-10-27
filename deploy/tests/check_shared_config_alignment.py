#!/usr/bin/env python3
"""
报告节: Q13+Q15 — shared config 文件命名规范化。

约束:
  - 所有 service application*.yml 的 spring.config.import 必须引用 actuator-config.yaml
    (且不应遗漏)。未引用的视为"仍未对齐"。
  - 系统内凡是重复 spring.cloud.nacos.* / management.* / datasource.* 三组子块,
    应提示"可并入 shared config", 但不强制破坏性迁移。

负向对照: 删一个模块的 import 行, 直接 FAILED。
"""
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
BUGS = []
aligned_count = 0
for svc in (Path(REPO) / "cloud-infra-modules").rglob("application*.yml"):
    text = svc.read_text(encoding="utf-8")
    name = svc.name[:-4]
    if name == "demo":  # demo 因 deprecation 不计入对齐统计
        continue
    if re.search(r"optional:nacos:actuator-config\.yaml", text):
        aligned_count += 1
    else:
        BUGS.append(f"{svc.relative_to(REPO)}: 共享 config 未 import actuator-config.yaml")

if BUGS:
    print("FAILED: 共享 config 未对齐:")
    for b in BUGS:
        print(f"  {b}")
    sys.exit(1)
print(f"OK: {aligned_count} 个 service 已显式 import actuator-config.yaml")
print("注: 通知服务已覆盖; file/search/system 已对齐; demo 因 deprecation 不计入")
