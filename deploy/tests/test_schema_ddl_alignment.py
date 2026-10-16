#!/usr/bin/env python3
"""守护: 同一张表不得同时被 Flyway 迁移与 sql.init 的 schema.sql 声明，且列集合必须一致。

## 背景

全服务共用同一个库 `cloud_infra`, 但 DDL 演进有两套机制:

- system-module: Flyway (`db/migration/V1..V21`)
- file-service / notification-service: `spring.sql.init.mode=always` + `classpath:schema.sql`

于是 `sys_notification`、`sys_notification_preference`、`file_metadata` 同时被两处声明。
两处都用 `CREATE TABLE IF NOT EXISTS`, 所以**当前是重复而非冲突**, 但一旦有人只改其中
一处的列, 就会静默漂移: 先执行的一方决定实际结构, 另一方被 `IF NOT EXISTS` 悄悄跳过,
且**没有任何报错** —— 与本仓库记录的「能力存在 != 能力在运行」同源。

## 判定方式

对每个同时出现在 Flyway 与 schema.sql 的表, 比对**列集合**:

- 列集合一致 → MATCH(通过, 但重复声明仍在, 属待收敛的技术债)
- 列集合不一致 → DRIFT(失败), 打印差集

## 为什么现在只判 DRIFT 而不判"重复声明即失败"

重复声明本身是**当前可用的**: sql.init 是当年为修"运行时表缺失"加的急救措施
(见 deploy/config/notification-service.yaml 与 file-service.yaml 的注释),
直接删掉会让未迁移的部署重新变成"表不存在"。把它收敛到单一所有者需要给存量库做
Flyway baseline 并解决三个服务共用一个 `flyway_schema_history` 的启动顺序问题,
属于独立迁移项目, 不适合在一次守护改动里顺手做掉。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FLYWAY_DIR = (REPO_ROOT / "cloud-infra-modules" / "system-module"
              / "src" / "main" / "resources" / "db" / "migration")
SCHEMA_SQL = {
    "file-service": REPO_ROOT / "cloud-infra-modules" / "file-service" / "src" / "main" / "resources" / "schema.sql",
    "notification-service": REPO_ROOT / "cloud-infra-modules" / "notification-service" / "src" / "main" / "resources" / "schema.sql",
}

CREATE_RE = re.compile(
    r"CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+[\"`]?(\w+)[\"`]?\s*\((.*?)\n\)",
    re.S | re.I,
)
# REFERENCES 也要排掉: 外键常跨多行, 其 REFERENCES 子句单独成行, 只过滤约束起始行
# 会把 "REFERENCES sys_user (user_id) ON DELETE CASCADE" 误当成列(实测假阳性)。
CONSTRAINT_PREFIX = re.compile(
    r"(PRIMARY|FOREIGN|UNIQUE|CONSTRAINT|CHECK|KEY|INDEX|REFERENCES)\b", re.I)
COLUMN_RE = re.compile(r"[\"`]?(\w+)[\"`]?\s+\S")
VERSION_RE = re.compile(r"V(\d+)")
# Flyway 侧必须算最终态: 建表之后的 ALTER ADD COLUMN 才是真实结构
ALTER_ADD_RE = re.compile(
    r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+|ONLY\s+)?[\"`]?(\w+)[\"`]?[^;]*?"
    r"ADD\s+COLUMN(?:\s+IF\s+NOT\s+EXISTS)?\s+[\"`]?(\w+)", re.S | re.I)


def columns_of(body: str) -> set[str]:
    cols: set[str] = set()
    for line in body.split("\n"):
        line = line.strip().rstrip(",")
        if not line or line.startswith("--") or CONSTRAINT_PREFIX.match(line):
            continue
        match = COLUMN_RE.match(line)
        if match:
            cols.add(match.group(1).lower())
    return cols


def main() -> int:
    if not FLYWAY_DIR.is_dir():
        print(f"FAIL: Flyway 目录不存在: {FLYWAY_DIR}")
        return 1

    # Flyway 侧必须算**最终态**: 建表之后的 ALTER ADD COLUMN 才是真实结构。
    # 只看 CREATE 会把 V14/V15/V17 加的 tenant_id / dispatch_* / channels 全判成"漂移"。
    flyway: dict[str, tuple[str, set[str]]] = {}
    flyway_creates: set[str] = set()
    ordered = sorted(FLYWAY_DIR.glob("*.sql"),
                     key=lambda p: (int(VERSION_RE.match(p.name).group(1))
                                    if VERSION_RE.match(p.name) else 0, p.name))
    for path in ordered:
        text = path.read_text(encoding="utf-8")
        for match in CREATE_RE.finditer(text):
            table = match.group(1).lower()
            flyway_creates.add(table)
            flyway.setdefault(table, (path.name, set()))[1].update(columns_of(match.group(2)))
        for match in ALTER_ADD_RE.finditer(text):
            table, column = match.group(1).lower(), match.group(2).lower()
            flyway.setdefault(table, (path.name, set()))[1].add(column)

    failures: list[str] = []
    overlaps: list[str] = []

    for service, path in SCHEMA_SQL.items():
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for match in CREATE_RE.finditer(text):
            table = match.group(1).lower()
            local = columns_of(match.group(2))
            # 只有"双方都建了表"才算重复声明。file_metadata 由 schema.sql 独家建表,
            # Flyway 仅 V14 对它 ADD COLUMN —— 那不是所有权冲突, 拿两边的列集合相比
            # 会得到一堆假的"仅 schema.sql 有"(实测 8 列)。
            if table not in flyway_creates:
                continue
            src, remote = flyway[table]
            overlaps.append(f"{service}:{table} (flyway={src})")
            only_local = local - remote
            only_remote = remote - local
            if only_local or only_remote:
                detail = []
                if only_local:
                    detail.append(f"仅 schema.sql 有: {sorted(only_local)}")
                if only_remote:
                    detail.append(f"仅 Flyway 有: {sorted(only_remote)}")
                failures.append(f"{table} 列定义已漂移 [{service} vs {src}] — " + "; ".join(detail))

    if failures:
        print("FAIL:")
        for failure in failures:
            print(f"  - {failure}")
        print("\n同一张表被 Flyway 与 schema.sql 同时声明时, 先执行者决定实际结构, "
              "另一方被 IF NOT EXISTS 静默跳过 —— 漂移不会报错, 只会在运行时暴露。")
        return 1

    if overlaps:
        print(f"PASS 多 DDL 所有者列对齐守护: {len(overlaps)} 张表被双方声明但列集合一致")
        for item in sorted(overlaps):
            print(f"  · {item}")
        print("\n⚠️ 上述表仍属重复声明(待收敛的技术债)。本守护只保证两者不会**漂移**;")
        print("   收敛为单一所有者需为存量库做 Flyway baseline, 属独立迁移项目。")
    else:
        print("PASS 多 DDL 所有者列对齐守护: 未发现重复声明的表")
    return 0


if __name__ == "__main__":
    sys.exit(main())