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
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
MODULES = ROOT / "cloud-infra-modules"
CONFIG = ROOT / "deploy" / "config"

# Flyway 管理的表集合 —— 由 system-module 的 db/migration/ 定义。
# 任何**其他**服务都不该在自己的 schema*.sql 里重建它们。
FLYWAY_OWNER = "system-module"
FLYWAY_DIR = "db/migration"


def executable_statements(sql_text: str):
    """剥掉注释, 只留下**真正会执行**的语句。

    ⚠️ 踩过的坑: 直接 grep "CREATE TABLE" 会命中注释里的说明文字。
    实测本次修复后 `grep -n sys_user schema.sql` 仍有 5 处命中 ——
    全部是我自己写的归属说明注释, 而判据据此报出"删了还在"。
    解析器同样有这个问题: 注释里的 `CREATE TABLE x` 会被当成真实建表语句。
    故先逐行去注释(行注释 --、块注释 /* */), 再匹配。
    """
    no_block = re.sub(r"/\*.*?\*/", "", sql_text, flags=re.S)
    lines = []
    for line in no_block.splitlines():
        stripped = line.strip()
        if stripped.startswith("--"):
            continue
        # 去掉行尾注释(避免 URL 里的 // 被误当注释, 故只按 -- 且不在引号内处理)
        lines.append(re.sub(r"--.*$", "", line))
    return "\n".join(lines)


def created_tables(sql_text: str):
    """返回该文件**实际会创建**的表名集合(已剔除注释)。"""
    body = executable_statements(sql_text)
    return {
        m.lower()
        for m in re.findall(
            r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[\"`]?(\w+)",
            body,
            flags=re.I,
        )
    }


def flyway_owned_tables():
    """Flyway migration 会创建的表集合 —— 这些表不应被其他服务重复定义。"""
    out = set()
    mdir = MODULES / FLYWAY_OWNER / "src" / "main" / "resources" / FLYWAY_DIR
    if not mdir.is_dir():
        return out
    for f in mdir.glob("*.sql"):
        out |= created_tables(f.read_text(encoding="utf-8", errors="replace"))
    return out

# 按**服务**声明豁免（不连业务库, 其 schema.sql 无需被执行）
NO_SCHEMA_NEEDED = {"skywalking"}

# 按**文件**声明豁免 —— 每条必须写明理由, 因为"豁免"本身也是判据,
# 无理由的豁免等于把检查关掉(AGENTS.md「黑名单 vs 白名单」: 正确一侧要可枚举)。
#
# ⚠️ 不要把"当前没执行"直接写成豁免: 那会让任何漏执行都变得可接受。
# 这里的豁免依据是"**对应的功能已被显式移除**", 可被独立核验。
EXEMPT_FILES: dict[str, str] = {
    "system-module/schema-seata.sql": (
        "Seata 已在 system-module 配置中显式排除"
        "(`spring.autoconfigure.exclude: ...SeataAutoConfiguration`,"
        "注释写明「无 Seata Server 部署」), compose 无 seata 服务, "
        "运行时容器数 0, undo_log 表不存在且业务正常。"
        "该文件属已废弃功能的遗留物, 建表无意义。"
    ),
}


def find_schema_services():
    """返回 [(service_dir_name, sql 文件列表), ...] —— 含 schema*.sql 全部变体。

    ⚠️ 2026-10-06 修正: 原实现只找 `schema.sql`, 于是 `schema-seata.sql`
    这类**变体文件完全不在扫描范围内** —— 即"扫到 0 个坏"不等于"没有坏的"。
    (第五课补记一: 没验到 ≠ 验过了。)
    """
    out = []
    for svc_dir in sorted(MODULES.iterdir()):
        if not svc_dir.is_dir():
            continue
        res = svc_dir / "src" / "main" / "resources"
        if not res.is_dir():
            continue
        sqls = sorted(p for p in res.glob("*.sql"))
        if sqls:
            out.append((svc_dir.name, sqls))
    return out


def declared_mechanism(service: str, sql_file: str):
    """返回 (机制名, 说明) 之一; 都没配则返回 (None, 原因)。

    ⚠️ 关键: **Flyway 只执行 `db/migration/` 下的文件**, 它不会执行
    资源根目录的 `schema*.sql`。早期版本把"该服务配了 Flyway"当作
    "该服务的每个 sql 文件都会被执行", 对 `schema-seata.sql` 给出假绿 ——
    第三课那条"能力存在 ≠ 能力在运行", 在守护自身的实现里重演了一次。
    故这里必须**按文件路径**判定, 不能按服务粗判。
    """
    cfg_path = CONFIG / f"{service}.yaml"
    if not cfg_path.is_file():
        return None, f"deploy/config/{service}.yaml 不存在"

    try:
        doc = yaml.safe_load(cfg_path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as exc:
        return None, f"配置解析失败: {exc}"

    spring = doc.get("spring") or {}

    # 路径一: spring.sql.init —— 需覆盖到**这个具体文件**
    sql_init = ((spring.get("sql") or {}).get("init")) or {}
    mode = sql_init.get("mode")
    if mode and str(mode).lower() != "never":
        locations = sql_init.get("schema-locations")
        if locations:
            # 显式收窄时, 只有落在列表里的文件才算被覆盖
            names = {loc.strip() for loc in str(locations).split(",")}
            wanted = f"classpath:{sql_file}"
            # 支持 classpath*: 前缀与通配
            covered = any(
                wanted == n
                or n.rstrip("*") == "classpath:"
                or sql_file in n
                for n in names
            )
            if covered:
                return "spring.sql.init", f"mode={mode}, schema-locations 覆盖本文件"
        else:
            # 未收窄 => 用 Spring 默认位置, 根目录 schema*.sql 会被执行
            return "spring.sql.init", f"mode={mode}（默认位置, 覆盖根目录 schema*.sql）"

    # 路径二: Flyway —— 仅当文件位于 db/migration/ 下才算
    if spring.get("flyway"):
        enabled = spring["flyway"].get("enabled")
        if enabled is not False and sql_file.startswith("db/migration/"):
            loc = (spring["flyway"] or {}).get("locations") or "classpath:db/migration"
            if "db/migration" in str(loc):
                return "spring.flyway", f"由 Flyway 执行（{loc}）"

    return None, (
        "既无覆盖本文件的 spring.sql.init, 也不在 Flyway 的 locations 范围内"
        if spring.get("flyway")
        else "既未配 spring.sql.init 也未配 spring.flyway"
    )


def main():
    services = find_schema_services()

    # 第五课补记一: 扫到 0 个必须判红, 否则扫描器坏了会被读成"通过"
    if not services:
        print("FAIL: 未找到任何含 schema.sql 的服务 —— 扫描器失效, 不是通过")
        return 1

    failures = []
    notes = []

    # 表归属检查: 除 Flyway 管理者自身外, 任何服务都不应在 schema*.sql 里
    # 重建 Flyway 管理的表。
    #
    # 缺陷背景(2026-10-05 实测): notification 的 schema.sql 带着 sys_user /
    # sys_notification / sys_notification_preference 三张表的过时副本
    # (sys_user 只 7 列, Flyway 已 18 列)。因写的是 CREATE TABLE IF NOT EXISTS,
    # 表不存在时会被副本抢先建出, 随后 Flyway V1 报
    # `column "user_id" of relation "sys_user" does not exist`,
    # 连锁导致其后 10 个 migration 全部失败(实测 11/17 失败)。
    # 生产未触发纯属 FULL_SERVICES 的顺序巧合, 非设计保证。
    flyway_tables = flyway_owned_tables()
    # 扫描器坏了 != 通过: 扫到 0 张 Flyway 表说明路径或解析失效, 须判红。
    if not flyway_tables:
        print(
            f"FAIL: 未从 {FLYWAY_OWNER}/{FLYWAY_DIR} 解析出任何 CREATE TABLE —— "
            "表归属检查的扫描器失效, 不能当作通过"
        )
        return 1
    notes.append(
        f"[表归属] Flyway 管理 {len(flyway_tables)} 张表, "
        "其余服务不得在自己的 schema*.sql 中重建"
    )

    for service, sql_files in services:
        for sql_path in sql_files:
            rel = str(sql_path.relative_to(MODULES / service / "src" / "main" / "resources"))
            key = f"{service}/{rel}"
            if key in EXEMPT_FILES:
                notes.append(f"{key}: 已豁免 —— {EXEMPT_FILES[key]}  ⊘")
                continue
            if rel.startswith("db/migration/") and service in NO_SCHEMA_NEEDED:
                continue

            text = sql_path.read_text(encoding="utf-8", errors="replace")
            tables = created_tables(text)
            n_tables = len(tables)

            # 表归属重叠检查 (仅对非 Flyway 管理者自身)
            if service != FLYWAY_OWNER:
                overlap = sorted(tables & flyway_tables)
                if overlap:
                    failures.append(
                        f"{key}: 重复定义了 Flyway 管理的表 {overlap} —— "
                        "两份定义会竞争建表权, 先执行的一方留下过时结构, "
                        "导致另一方的 migration 校验失败"
                    )

            mechanism, reason = declared_mechanism(service, rel)
            if mechanism:
                notes.append(f"{key}: {n_tables} 张表 ← {mechanism} ({reason})  ✓")
            else:
                failures.append(
                    f"{key}: 含 {n_tables} 张表但 {reason} —— "
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