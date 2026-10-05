#!/usr/bin/env python3
"""守护: 服务里的 schema.sql 必须真的被执行, 而不是"文件存在但无人引用"。

背景 (2026-10-06 实测):
    notification-service 的 src/main/resources/schema.sql 里有 7 张表
    (含 sys_notification_schedule), 但全仓只有 system-module 配了 Flyway,
    没有任何机制执行 notification 的 schema.sql。
    后果: @Scheduled 定时扫描每轮抛
        BadSqlGrammarException: relation "sys_notification_schedule" does not exist
    (实测 39 次), 定时调度功能实际不可用 —— 而容器 healthcheck 仍报 healthy。

与本仓已有"第三条硬规则"同型: 机制存在 != 机制在运行。

判据 (每个含 schema.sql 的服务):
    要么 deploy/config/<svc>.yaml 配了 spring.sql.init.mode (非 never),
    要么该服务配置了 spring.flyway (由 Flyway 执行 migration)。
    两者皆无 => 判红。

注意 (第五课补记一的同类教训):
    - 本门禁只做**结构性**检查(配置是否声明), 不等于运行时生效。
      运行时证据是"表真的出现在数据库里", 由 test_schema_init_wiring.py 补充。
    - 扫到 0 个目标必须判红: 扫描器坏了 != 通过。
"""
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
MODULES = ROOT / "cloud-infra-modules"
CONFIG = ROOT / "deploy" / "config"

# 这些服务不连业务库, 其 schema.sql 无需被执行
NO_SCHEMA_NEEDED = {"skywalking"}


def find_schema_services():
    """返回 [(service_dir_name, schema.sql 路径), ...]"""
    out = []
    for svc_dir in sorted(MODULES.iterdir()):
        if not svc_dir.is_dir():
            continue
        schema = svc_dir / "src" / "main" / "resources" / "schema.sql"
        if schema.is_file():
            out.append((svc_dir.name, schema))
    return out


def declared_mechanism(service: str):
    """返回 (机制名, 说明) 之一; 都没配则返回 (None, 原因)。"""
    cfg_path = CONFIG / f"{service}.yaml"
    if not cfg_path.is_file():
        return None, f"deploy/config/{service}.yaml 不存在"

    try:
        doc = yaml.safe_load(cfg_path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as exc:
        return None, f"配置解析失败: {exc}"

    spring = doc.get("spring") or {}

    # 路径一: spring.sql.init
    sql_init = ((spring.get("sql") or {}).get("init")) or {}
    mode = sql_init.get("mode")
    if mode and str(mode).lower() != "never":
        return "spring.sql.init", f"mode={mode}"

    # 路径二: Flyway (执行 db/migration, 不依赖 schema.sql, 但同样能建表)
    if spring.get("flyway"):
        enabled = spring["flyway"].get("enabled")
        if enabled is not False:
            return "spring.flyway", "由 Flyway 执行 db/migration"

    return None, "既未配 spring.sql.init 也未配 spring.flyway"


def main():
    services = find_schema_services()

    # 第五课补记一: 扫到 0 个必须判红, 否则扫描器坏了会被读成"通过"
    if not services:
        print("FAIL: 未找到任何含 schema.sql 的服务 —— 扫描器失效, 不是通过")
        return 1

    failures = []
    notes = []
    for service, schema in services:
        if service in NO_SCHEMA_NEEDED:
            notes.append(f"{service}: 无需建表(已列入豁免)  ⊘")
            continue
        n_tables = schema.read_text(encoding="utf-8", errors="replace").upper().count(
            "CREATE TABLE"
        )
        mechanism, reason = declared_mechanism(service)
        if mechanism:
            notes.append(f"{service}: {n_tables} 张表 ← {mechanism} ({reason})  ✓")
        else:
            failures.append(
                f"{service}: schema.sql 有 {n_tables} 张表但 {reason} —— "
                f"该文件从未被执行, 相关功能实际不可用"
            )

    for n in notes:
        print(f"  {n}")
    if failures:
        print(f"\nFAIL: {len(failures)} 项")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"\nPASS: 全部 {len(notes)} 项检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())