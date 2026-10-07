#!/usr/bin/env python3
"""守护: schema*.sql 必须能**独立初始化一个空库**。

## 抓出的是真实缺陷 (2026-10-07)

`notification-service/src/main/resources/schema.sql` 曾有两行:

    line 22: -- ⚠️ sys_notification 由 system-module 的 Flyway 管理, 不在此创建。
    line 30: CREATE INDEX IF NOT EXISTS idx_notification_user_status ON sys_notification(user_id, status);
    line 33: CREATE INDEX IF NOT EXISTS idx_notification_dispatch ON sys_notification(dispatch_status, create_time);

该服务集成测试的数据源是 **H2 内存空库**(`jdbc:h2:mem:notification_db`,
见 `src/test/resources/application-test.yml`), 且测试**不运行 Flyway**。
于是执行到这两行必然报 `ERROR: relation "sys_notification" does not exist`
→ `dataSourceScriptDatabaseInitializer` 失败
→ 整个 Spring 上下文加载失败 → 12 项全红。

**稳定可独立复现**: 单模块 `mvn verify` 亦 30 项 12 错, 与并发无关。
(对照: system-module 同样 `mode: always`, 但数据源是 PostgreSQL 已初始化库,
`IF NOT EXISTS` 幂等无害 —— 故它 13 项全绿。**同一份配置, 数据源不同结论相反**。)

## 判据: 用真实 H2 执行, 而不是静态扫描

我第一版门禁是纯静态扫描(判断"引用的表有没有归属"), **负向对照直接假绿**
—— 因为 `sys_notification` 确实在 Flyway 白名单里, 被判为"有归属",
而真实失败是"本文件执行时那张表并不存在"。

**判据抓错了对象**: 归属是静态属性, 自包含是动态属性。
本门禁改为**真在 H2 里跑一遍 schema.sql** —— 空库能跑完才是真绿。
静态扫描降级为辅助信息(仅在无 h2 时提示)。

**用真实执行而非推断, 才能区分"能跑"与"看起来该能跑"。**

## 与既有门禁的分工

- `check_schema_init_wiring.py`: 不得**重建** Flyway 管理的表(防双主)。
- 本门禁: 本文件**能否独立初始化空库**(防依赖外部前提)。

两者方向不同, 都必要。
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# H2 PostgreSQL 兼容模式, 与 notification 的 application-test.yml 保持一致。
H2_URL = "jdbc:h2:mem:schema_gate_check;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"


def schema_files() -> list[Path]:
    """只取会被 `spring.sql.init` 真正加载的 schema*.sql。

    ⚠️ 范围三处都是**实测**划定的, 不是推断:

    1. 只取 `src/main/resources` —— `src/test/resources/schema.sql` 是测试
       自己准备的数据集, 由测试决定建什么, 不受本门禁约束。
    2. 排除 `target/` —— 编译产物, 与源文件重复。
    3. 排除 `schema-seata.sql` —— **全仓零引用**(grep 全仓无任何
       yml/java/properties 提及它), 且 Seata 在本项目已停用
       (AGENTS.md: "Seata disabled")。它是死文件, 不会被
       `spring.sql.init` 加载; 纳入扫描只会得到"MySQL 反引号语法在 H2 下报错"
       这类与被测目标无关的误报。

    **误报的代价**: 判红一个无关文件会训练人忽略本门禁输出,
    比漏判更危险 —— 与 `run_gate_selftest` 的教训同源。
    """
    files = [
        p
        for p in ROOT.rglob("src/main/resources/schema*.sql")
        if "target" not in p.parts and "seata" not in p.name.lower()
    ]
    return sorted(files)


def find_h2_jar() -> Path | None:
    """在本地 Maven 仓库找 H2 jar —— 用真实引擎执行, 不做静态推断。"""
    repo = Path.home() / ".m2" / "repository" / "com" / "h2database" / "h2"
    if not repo.is_dir():
        return None
    jars = sorted(repo.rglob("h2-*.jar"), key=lambda p: p.stat().st_mtime, reverse=True)
    for jar in jars:
        if jar.is_file() and jar.stat().st_size > 0:
            return jar
    return None


def strip_comments(sql_text: str) -> str:
    """剥离注释 —— 注释里的 SQL 不是语句, 否则会误判。"""
    text = re.sub(r"/\*.*?\*/", " ", sql_text, flags=re.S)
    return "\n".join(
        line for line in text.splitlines() if not line.strip().startswith("--")
    )


def execute_on_h2(sql_text: str, jar: Path) -> tuple[bool, str]:
    """在**真实 H2 空库**上执行 schema.sql, 返回 (是否成功, 首个错误)。"""
    code = strip_comments(sql_text)
    with tempfile.TemporaryDirectory() as td:
        runner = Path(td) / "RunSchema.java"
        runner.write_text(
            "import java.sql.*;\n"
            "public class RunSchema {\n"
            "  public static void main(String[] a) throws Exception {\n"
            "    String sql = new String(java.nio.file.Files.readAllBytes("
            "java.nio.file.Path.of(a[0])), java.nio.charset.StandardCharsets.UTF_8);\n"
            "    try (Connection c = DriverManager.getConnection(\"" + H2_URL + "\", \"sa\", \"\")) {\n"
            "      for (String stmt : sql.split(\";\")) {\n"
            "        if (stmt.trim().isEmpty()) continue;\n"
            "        try (Statement s = c.createStatement()) { s.execute(stmt); }\n"
            "        catch (SQLException e) { System.out.println(\"FIRST_ERROR: \" + e.getMessage());"
            " System.exit(1); }\n"
            "      }\n"
            "      System.out.println(\"SCHEMA_OK\");\n"
            "    }\n"
            "  }\n"
            "}\n",
            encoding="utf-8",
        )
        sql_file = Path(td) / "schema.sql"
        sql_file.write_text(code, encoding="utf-8")
        # ⚠️ java 单文件源码模式要求文件名为 <类名>.java, 故 runner 必须叫 RunSchema.java,
        #    且 main 的参数是**类名**在前 —— 顺序反了会静默拿到 args=null。
        run = subprocess.run(
            ["java", "-cp", str(jar), str(runner), str(sql_file)],
            capture_output=True,
            text=True,
            timeout=180,
        )
    out = (run.stdout or "") + (run.stderr or "")
    return ("SCHEMA_OK" in out), out.strip()


def main() -> int:
    files = schema_files()
    if not files:
        # 扫描器失效 ≠ 通过
        print("FAIL: 未找到任何 src/main/resources/schema*.sql —— 扫描器失效", file=sys.stderr)
        return 2

    jar = find_h2_jar()
    if jar is None:
        print(
            "SKIP: 本地 Maven 仓库无 H2 jar, 无法真实执行验证。\n"
            "      这不是通过 —— 空库自包含性**未被判定**。\n"
            "      (通知类 schema.sql 的实际验证由对应服务的集成测试完成)",
            file=sys.stderr,
        )
        return 3

    print(f"[空库自包含] 用真实 H2 执行 {len(files)} 个 schema.sql")
    print(f"  引擎: {jar.name}")
    failures = 0

    for f in files:
        rel = f.relative_to(ROOT)
        ok, out = execute_on_h2(f.read_text(encoding="utf-8", errors="replace"), jar)
        if ok:
            print(f"  ok   {rel}")
        else:
            failures += 1
            first = next(
                (l for l in out.splitlines() if "FIRST_ERROR" in l),
                out.splitlines()[-1] if out.splitlines() else "(无输出)",
            )
            print(f"  FAIL {rel}")
            print(f"       {first[:160]}")
            print(
                "       ↑ 该 schema.sql 无法独立初始化空库。若它引用的表由别处"
                "(如 Flyway)创建, 则本服务用内存库测试时必然失败。",
                file=sys.stderr,
            )

    if failures:
        print(f"\nFAIL: {failures}/{len(files)} 个 schema.sql 不能独立初始化空库", file=sys.stderr)
        return 1
    print(f"\nPASS: {len(files)} 个 schema.sql 均可在空库独立执行")
    return 0


if __name__ == "__main__":
    sys.exit(main())