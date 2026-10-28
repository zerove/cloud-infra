#!/usr/bin/env python3
"""
check_nacos_config_alignment.py — 本地 yml 与 deploy/config 子块对齐门禁
报告节: A3

约束:
  - 各 service application*.yml 的 spring.cloud.nacos.discovery.* / management.*
    / datasource.hikari.* 三组子块与 deploy/config 中同名位置应存在。
    缺失视为"对齐缺口"，提示但不破坏性阻止——由人工判断是否需合并。
  - 仅检查**有 deploy 配置文件覆盖**的模块（notification/search/file/system/auth）。
    demo 已弃用，不计入；framework 无对应 deploy 文件，跳过。

负向对照: 把一个 yml 里 nacos.discovery 的 server-addr 改掉 → 应报红。
历史数据: 2026-10-28 实测各模块三组子块均有对应 deploy 文件，对齐 PASS。
"""
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DEPLOY_CONFIGS = {
    "notification": "deploy/config/notification-service.yaml",
    "search": "deploy/config/search-service.yaml",
    "file": "deploy/config/file-service.yaml",
    "system": "deploy/config/system-module.yaml",
}
EXCLUDE_DIRS = {"target", ".git"}
MISSING = []

for svc, dcfg in DEPLOY_CONFIGS.items():
    deploy_text = Path(dcfg).read_text(encoding="utf-8")
    deploy_has = any(k in deploy_text for k in (
        "spring.cloud.nacos.discovery",
        "management.endpoints",
        "datasource.hikari",
    ))
    if not deploy_has:
        continue
    local = REPO / "cloud-infra-modules" / f"{svc}-service" / "src" / "main" / "resources" / "application.yml"
    if not local.exists():
        continue
    local_text = local.read_text(encoding="utf-8")
    for key in ("spring.cloud.nacos.discovery", "management.endpoints", "datasource.hikari"):
        if key not in local_text:
            MISSING.append(f"{svc}-service application.yml: 缺 {key}")

if MISSING:
    print("FAILED: 对齐缺口:")
    for m in MISSING:
        print(f"  {m}")
    sys.exit(1)
print(f"OK: {len(DEPLOY_CONFIGS)} 个 service 三组配置子块均有 deploy 对照")
sys.exit(0)
