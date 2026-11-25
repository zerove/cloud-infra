#!/usr/bin/env python3
"""check_nacos_config_alignment.py — 本地 yml 与 deploy/config 子块对齐门禁
报告节: A3

约束:
  - 各 service 的三组子块 spring.cloud.nacos.discovery / management.endpoints /
    datasource.hikari，必须至少在**本地 application.yml 或 deploy/config**
    一处声明（嵌套语义判定）。两处都缺 => 对齐缺口，提示但不破坏性阻止。
  - 仅检查有 deploy 配置文件覆盖的业务模块（notification/search/file/system）。
    demo 已弃用；framework 无对应 deploy 文件，跳过。
    auth 不纳入（附验证，非仅声明）：`deploy/config/auth.yaml` 明确
    `exclude: DataSourceAutoConfiguration`，且 auth 全仓无 datasource/hikari 配置
    —— 三组子块中的 hikari 对其不适用，纳入只会产生假红。

## 2026-11-08 重写原因（三重假绿，全部实测）

1. `REPO = parents[1]` 指向 `deploy/` —— 本地 yml 路径
   `deploy/cloud-infra-modules/...` 不存在，全部 `continue`。
2. 用扁平字面量 `spring.cloud.nacos.discovery` 匹配**嵌套 YAML** ——
   `deploy_has` 恒 False，整段 continue（即使路径修好也永不检查）。
3. 打印的是 `len(DEPLOY_CONFIGS)`，而非**实际核对数** —— 空转也显示 "4 个 service"。

## 负向对照

  · 临时根目录里让某服务的 hikari 在本地与部署两处都缺失 -> 必须报红
  · 声明路径不存在 -> 必须 rc=2（扫描根漂移 != 通过）
"""
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SERVICES = {
    "notification": "notification-service",
    "search": "search-service",
    "file": "file-service",
    "system": "system-module",
}
BLOCKS = (
    "spring.cloud.nacos.discovery",
    "management.endpoints",
    "spring.datasource.hikari",
)


def has_block(cfg, dotted: str) -> bool:
    cur = cfg
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return False
        cur = cur[part]
    return True


def load(path: Path):
    try:
        with open(path, encoding="utf-8") as f:
            return yaml.safe_load(f) or {}
    except (OSError, yaml.YAMLError) as exc:
        raise SystemExit(f"FAIL: 无法解析 {path}: {exc}")


def main(root: Path = ROOT) -> int:
    gaps = []
    checked = 0
    for dname in SERVICES.values():
        dep_p = root / "deploy" / "config" / f"{dname}.yaml"
        loc_p = (root / "cloud-infra-modules" / dname
                 / "src" / "main" / "resources" / "application.yml")
        if not dep_p.is_file() or not loc_p.is_file():
            print(f"FAIL: 声明的配置不存在: {dep_p} 或 {loc_p}", file=sys.stderr)
            print("      声明 != 实际 —— 路径漂移时不能判绿", file=sys.stderr)
            return 2
        dep, loc = load(dep_p), load(loc_p)
        checked += 1
        for dotted in BLOCKS:
            if not (has_block(dep, dotted) or has_block(loc, dotted)):
                gaps.append(f"{dname}: 本地与部署配置都缺 {dotted}（嵌套语义判定）")
    if checked == 0:
        print("FAIL: 一个 service 都没核对 —— 扫描器坏了，不能判绿", file=sys.stderr)
        return 2
    if gaps:
        print("FAILED: 对齐缺口:")
        for gap in gaps:
            print("  " + gap)
        return 1
    print(f"OK: {checked} 个服务的三组配置子块在本地/部署配置中均有覆盖")
    return 0


if __name__ == "__main__":
    # 可选参数仅用于负向对照（指向临时 fixture 根目录）
    cli_root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT
    sys.exit(main(cli_root))
