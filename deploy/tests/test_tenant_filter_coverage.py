#!/usr/bin/env python3
"""守护: 带 tenant_id 的表, 其<b>每一个</b> Mapper 都必须标注 @TenantFilter。

## 这个守护拦住的是什么

`TenantSqlInterceptor` 的契约是"无 `@TenantFilter` 注解即放行" —— fail-open。
这本身是为兼容早期无租户表而设计的, 但它把**跨租户越权**的风险留给了"新加表时忘加注解"。

实测当前状态: 32 个 Mapper 中仅 10 个带注解, 其余 22 个对应的表**确实没有**
tenant_id(字典 / 配置 / 各类连接表, 属平台级数据)。**因此当前没有活跃越权**,
本守护防的是**前向风险**: 有人新增一张带 tenant_id 的表却忘了加注解。

## 为什么用文本扫描而不是运行时断言

Mapper 覆盖范围必须包含 XML 里写 SQL 的那批(它们没有实体类可映射),
而"表 -> Mapper"的运行时关系还要受 `@TableName` / 动态 SQL 影响。
按表名在 Mapper 源码里做文本反查, 覆盖最全且确定性强。

## 判定方式

1. 从 Flyway 迁移里解析出所有带 tenant_id 的表(CREATE TABLE 内含, 或 ALTER TABLE ADD COLUMN)。
2. 对每个这样的表, 反查所有提到该表名的 Mapper 源文件(.java 及其同目录 XML)。
3. 命中但缺 `@TenantFilter` 即失败, 并打印具体文件。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATION_DIR = REPO_ROOT / "cloud-infra-modules" / "system-module" / "src" / "main" / "resources" / "db" / "migration"
MODULES_ROOT = REPO_ROOT / "cloud-infra-modules"
# 实体也可能落在 cloud-infra-common(如 SysRoleMapper 引用的 SysRole),
# 只扫业务模块会漏判, 表现为"表未被任何 Mapper 覆盖"的假阳性。
ENTITY_ROOTS = (MODULES_ROOT, REPO_ROOT / "cloud-infra-common")


def tenant_bearing_tables() -> set[str]:
    """最终态的带 tenant_id 表集合(按版本序应用增删)。

    必须以**最终态**判定: 历史迁移 V2 建过 outbox_event, 而 V21 已将其删除。
    若只看"哪些迁移提到过", 会被已删除的表永久卡住。
    """
    tables: set[str] = set()
    for sql in sorted(MIGRATION_DIR.glob("*.sql"), key=lambda p: migration_version(p)):
        text = sql.read_text(encoding="utf-8")
        for match in re.finditer(r"drop\s+table\s+(?:if\s+exists\s+)?[\"`]?(\w+)", text, re.I):
            tables.discard(match.group(1).lower())
        for match in re.finditer(
                r"create\s+table\s+(?:if\s+not\s+exists\s+)?[\"`]?(\w+)[\"`]?\s*\((.*?)\n\)",
                text, re.S | re.I):
            if "tenant_id" in match.group(2).lower():
                tables.add(match.group(1).lower())
        for match in re.finditer(
                r"alter\s+table\s+(?:if\s+exists\s+|only\s+)?[\"`]?(\w+)[\"`]?.*?add\s+column.*?tenant_id",
                text, re.S | re.I):
            tables.add(match.group(1).lower())
    return tables


def migration_version(path: Path) -> tuple[int, str]:
    match = re.match(r"V(\d+)", path.name)
    return (int(match.group(1)) if match else 0, path.name)


def mapper_sources() -> list[Path]:
    out: list[Path] = []
    for module in sorted(MODULES_ROOT.iterdir()):
        if not module.is_dir():
            continue
        main = module / "src" / "main" / "java"
        if not main.is_dir():
            continue
        for path in main.rglob("*.java"):
            if "/mapper/" in path.as_posix() and path.name.endswith("Mapper.java"):
                out.append(path)
        resources = module / "src" / "main" / "resources"
        if resources.is_dir():
            for path in resources.rglob("*.xml"):
                if "/mapper" in path.as_posix():
                    out.append(path)
    return out


def _owner_of(path: Path) -> Path:
    """XML 不可能携带注解(注解在 Mapper 接口上), 故以其同名接口为准。

    XML 位于 src/main/resources/mapper/, 接口位于 src/main/java/<pkg>/mapper/,
    两者不在同一目录, 不能用 with_suffix 直接推导。
    """
    if path.suffix == ".xml":
        java_root = REPO_ROOT / "cloud-infra-modules"
        for candidate in java_root.rglob(path.stem + ".java"):
            if "/mapper/" in candidate.as_posix() and "/src/main/" in candidate.as_posix():
                return candidate
    return path


def _entities_by_table() -> dict[str, set[str]]:
    """表名 -> 实体类简单名集合(来自 @TableName)。"""
    mapping: dict[str, set[str]] = {}
    for root in ENTITY_ROOTS:
        if not root.is_dir():
            continue
        for path in root.rglob("*.java"):
            if "/src/main/" not in path.as_posix() or path.name.endswith("Mapper.java"):
                continue
            text = path.read_text(encoding="utf-8", errors="ignore")
            match = re.search(r'@TableName\(\s*"([^"]+)"\s*\)', text)
            if match:
                mapping.setdefault(match.group(1).lower(), set()).add(path.stem)
    return mapping


def main() -> int:
    if not MIGRATION_DIR.is_dir():
        print(f"FAIL: 迁移目录不存在: {MIGRATION_DIR}")
        return 1

    tables = tenant_bearing_tables()
    if not tables:
        print("FAIL: 未从迁移中解析出任何带 tenant_id 的表, 解析逻辑可能已失效")
        return 1

    sources = mapper_sources()
    entities = _entities_by_table()
    failures: list[str] = []
    covered: set[str] = set()

    for table in sorted(tables):
        table_re = re.compile(rf"\b{re.escape(table)}\b", re.I)
        entity_names = entities.get(table, set())
        entity_re = [re.compile(rf"\b{re.escape(n)}\b") for n in entity_names]

        for path in sources:
            owner = _owner_of(path)
            text = owner.read_text(encoding="utf-8", errors="ignore")
            # 命中条件: 直接写了表名( XML / 注解 SQL ), 或引用了该表对应的实体
            if not table_re.search(text) and not any(r.search(text) for r in entity_re):
                continue
            if "@TenantFilter" in text:
                covered.add(table)
            else:
                failures.append(
                    f"表 {table} 带 tenant_id, 但引用它的 Mapper 缺少 @TenantFilter: "
                    f"{owner.relative_to(REPO_ROOT)}")

    uncovered = sorted(tables - covered)
    if failures or uncovered:
        print("FAIL:")
        for f in sorted(set(failures)):
            print(f"  - {f}")
        for table in uncovered:
            print(f"  - 表 {table} 带 tenant_id, 但未找到任何带 @TenantFilter 的 Mapper")
        print("\nTenantSqlInterceptor 对无注解 Mapper 是放行(fail-open)。"
              "带 tenant_id 的表必须显式标注 @TenantFilter, "
              "否则该表的所有查询会跨租户返回数据。")
        return 1

    print(f"PASS 多租户覆盖守护: {len(tables)} 张带 tenant_id 的表全部被带 @TenantFilter 的 Mapper 覆盖")
    return 0


if __name__ == "__main__":
    sys.exit(main())