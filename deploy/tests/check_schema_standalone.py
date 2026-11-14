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


def _modules_with_h2_tests() -> set[Path]:
    """测试数据源是 H2 的模块根目录。

    用 `jdbc:h2:` 作为判据而非手写模块清单: 手写清单会与配置漂移,
    而"这份 schema 会被加载进哪个库"才是本门禁的判定对象。
    """
    mods: set[Path] = set()
    for cfg in ROOT.rglob("src/test/resources/*.yml"):
        if "target" in cfg.parts:
            continue
        try:
            text = cfg.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if "jdbc:h2:" in text:
            # <module>/src/test/resources/x.yml -> parents[0]=resources,
            # [1]=test, [2]=src, [3]=<module>
            mods.add(cfg.parents[3])
    return mods


def schema_files() -> list[Path]:
    """只取会被 `spring.sql.init` 真正加载进 H2 空库的 schema*.sql。

    ⚠️ 扫描范围的历史变化（2026-11-06 修正）:

    原版只扫 `src/main/resources`, 因为当时的缺陷文件是
    `notification-service/src/main/resources/schema.sql`。
    commit `777e6842`("schema 收敛为 Flyway 单一所有者") 已**有意删除**
    该文件, schema 改由 `src/test/resources/schema.sql` 承载并由
    `spring.sql.init.mode=always` 加载到 H2 —— 原扫描范围因此恒为 0,
    门禁自身报"扫描器失效"。

    **判据仍是"实际会被加载的文件", 而不是"以前那个路径"**:

    1. 测试资源配置了 `jdbc:h2:` 的模块 —— 取其
       `src/test/resources/schema*.sql`; 这些文件会由 Spring Boot
       的 `spring.sql.init` 在空 H2 库上执行。
    2. 保留 `src/main/resources/schema*.sql` 扫描(生产 schema 若存在,
       仍应能独立初始化), 但当前仅剩 `schema-seata.sql`。
    3. 排除 `schema-seata.sql` —— 全仓零引用, Seata 已停用, 是死文件;
       纳入只会得到"MySQL 反引号语法在 H2 下报错"的无关误报。
    4. 排除 system-module 的测试 schema —— 其测试数据源是
       PostgreSQL(`application-test.yml` 为 `jdbc:postgresql://`),
       不是 H2, 也不由 `spring.sql.init` 加载; 用 H2 判它属方言误报。

    **误报的代价**: 判红一个无关文件会训练人忽略本门禁输出,
    比漏判更危险 —— 与 `run_gate_selftest` 的教训同源。
    """
    files: set[Path] = set()
    for module in _modules_with_h2_tests():
        test_res = module / "src/test/resources"
        if not test_res.is_dir():
            continue
        files.update(
            p for p in test_res.glob("schema*.sql")
            if "seata" not in p.name.lower()
        )
    files.update(
        p for p in ROOT.rglob("src/main/resources/schema*.sql")
        if "target" not in p.parts and "seata" not in p.name.lower()
    )
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


def h2_columns(jar: Path, schema_text: str, table: str) -> set[str] | None:
    """在真实 H2 空库建完 schema 后, 返回 table 的**实测列名集合**。

    ⚠️【2026-10-07 新增】原版只验"能否执行", 于是**抓不到缺列**:
       负向对照显示删掉 `channels` 列后门禁依旧 PASS —— 因为
       `CREATE TABLE ... (id, user_id, ...)` 少了列**依然是合法 SQL**。
       即它守护的正是"表缺列", 却对缺列视而不见（弱门禁）。
       故此处**读回实测列**, 与实体声明的列比对。
    """
    code = strip_comments(schema_text)
    with tempfile.TemporaryDirectory() as td:
        runner = Path(td) / "ReadColumns.java"
        runner.write_text(
            "import java.sql.*;\n"
            "import java.util.*;\n"
            "public class ReadColumns {\n"
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
            "      List<String> cols = new ArrayList<>();\n"
            "      try (ResultSet rs = c.getMetaData().getColumns(null, null, a[1], null)) {\n"
            "        while (rs.next()) cols.add(rs.getString(\"COLUMN_NAME\").toLowerCase());\n"
            "      }\n"
            "      System.out.println(\"COLS:\" + String.join(\",\", cols));\n"
            "    }\n"
            "  }\n"
            "}\n",
            encoding="utf-8",
        )
        sql_file = Path(td) / "schema.sql"
        sql_file.write_text(code, encoding="utf-8")
        run = subprocess.run(
            ["java", "-cp", str(jar), str(runner), str(sql_file), table],
            capture_output=True, text=True, timeout=180,
        )
    out = (run.stdout or "") + (run.stderr or "")
    m = re.search(r"^COLS:(.*)$", out, re.M)
    if not m:
        return None
    return {c for c in m.group(1).split(",") if c}


def find_entity_for_table(table: str) -> Path | None:
    """从**实体源码**里搜 @TableName("<table>") 定位文件。

    ⚠️【2026-10-07】第一版把路径写死为 `.../entity/Notification.java`,
    实测报 `No such file or directory` —— 真实路径是 `.../domain/Notification.java`
    (本项目实体放 domain 包)。这正是第五课记录的
    **「类名/文件名/资源名与运行时标识符之间没有必然对应关系」**,
    我按惯例猜了包名, 又一次。
    正解: 由表名反查实体, 不猜包路径 —— 表名才是稳定标识符。
    """
    for jf in ROOT.rglob("src/main/java/**/*.java"):
        if "target" in jf.parts:
            continue
        try:
            text = jf.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if f'@TableName("{table}")' in text:
            return jf
    return None


def entity_columns(entity: Path) -> set[str]:
    """从实体源码提取列名 —— **驼峰转下划线**, 与 MyBatis-Plus 默认策略一致。

    ⚠️ 来源必须是**实体**而不是手写清单: 手写清单会与实体漂移而无人察觉,
       那正是第五课反复出现的"探针对象不是被检对象"。

    ⚠️【2026-10-07 第二次修正】原实现只认**带注解**的字段:
         r"@(TableId|TableField)[^\n]*\n\\s*private ..."
       实测只提取到 2/11 个字段。看了实体真实内容才明白原因 ——
       **只有 id 和 createTime 有注解, 其余 9 个字段全是裸声明**
       （`private String title;` 前面什么都没有）,
       因为 MyBatis-Plus 默认按驼峰转换映射全部字段, 不要求逐个加注解。

       而原实现**判绿了**: 12 列里"恰好"有 2 列存在, 缺失集合为空。
       即"提取器只认出 2 列"被读成"表列齐了" —— 与第五课
       「探不到有两个解释」完全同形, 且**全绿**、无任何报错信号。

       正解: 实体类里**所有非 static 的 private 字段**都是持久化列
       （@Data 类, 无 @TableField(exist=false) 之类的排除项）,
       故直接取全部 private 字段。
    """
    if not entity.exists():
        return set()
    text = entity.read_text(encoding="utf-8", errors="replace")
    cols: set[str] = set()
    # 类体起始（"class X {"）之后的所有 private 非 static 字段
    m = re.search(r"^(?:public\s+)?(?:final\s+)?class\s+\w+[^{]*\{(.*)\}", text, re.S | re.M)
    if not m:
        return cols
    body = m.group(1)
    for fm in re.finditer(
            r"^\s*private\s+(?!static)(?:final\s+)?[\w<>,\s.]+?\s+(\w+)\s*(?:=[^;]*)?;",
            body, re.M,
        ):
        cols.add(re.sub(r"(?<!^)(?=[A-Z])", "_", fm.group(1)).lower())
    return cols


def entity_field_count(entity: Path) -> int:
    """实体里 private 字段总数（含非持久化字段）—— 只用于判断提取器是否漏抓。

    不用它做列断言（log 之类不应入库）, 仅当 `entity_columns` 的结果
    远小于它时判定"提取器坏了"。阈值宽松(>60%), 因为确有非持久化字段。
    """
    text = entity.read_text(encoding="utf-8", errors="replace")
    n = len(re.findall(r"^\s*private\s+(?!static)", text, re.M))
    return n


COLUMN_CHECKED_TABLES = (
    "sys_notification",
    "sys_notification_preference",
)


def check_columns(jar: Path, files: list[Path]) -> int:
    """对每个有本地写路径的表, 比对**实体声明列**与**schema 实测列**。

    覆盖多张表的原因: 补完 sys_notification 后, IT 立刻暴露出
    `relation "sys_notification_preference" does not exist` —— 同型问题,
    只是排在后面。只守护第一张表会让下一个同型缺陷继续漏过。

    ⚠️ 不再硬编码 `src/main/resources/schema.sql`: 该文件已随
    `777e6842` 删除, 现在由 notification 的测试资源承载。从上面已判定的
    `files` 里按模块归属定位, 让"被执行的"与"被比对列的"是同一个文件。
    """
    notif = next(
        (p for p in files if "notification-service" in p.parts),
        None,
    )
    if notif is None:
        print("FAIL: 找不到 notification schema.sql —— 探针失效, 不能判绿", file=sys.stderr)
        return 2
    body = notif.read_text(encoding="utf-8", errors="replace")
    for table in COLUMN_CHECKED_TABLES:
        entity = find_entity_for_table(table)
        if entity is None:
            print(f"FAIL: 全仓找不到 @TableName(\"{table}\") 的实体 —— "
                  "探针失效, 不能判绿", file=sys.stderr)
            return 2
        expected = entity_columns(entity)
        total_fields = entity_field_count(entity)
        if total_fields and len(expected) * 10 < total_fields * 6:
            print(f"FAIL: {table} 列提取器只认出 {len(expected)}/{total_fields} 个字段 —— "
                  "提取器失效, 不能判绿（'只认出部分列'会被误读成'表列齐了'）",
                  file=sys.stderr)
            return 2
        if not expected:
            print(f"FAIL: 未从 {table} 实体提取到任何列 —— 提取器坏了, 不能判绿",
                  file=sys.stderr)
            return 2
        actual = h2_columns(jar, body, table)
        if actual is None:
            print(f"FAIL: 读不回 {table} 的实测列 —— 探针失效, 不能判绿", file=sys.stderr)
            return 2
        missing = expected - actual
        print(f"[列对齐] {table}: 实体声明 {len(expected)} 列, schema 实建 {len(actual)} 列")
        if missing:
            print(f"FAIL: {table} 缺少实体所需的列: {sorted(missing)}", file=sys.stderr)
            print("      这些列缺失时, 集成测试会报 "
                  'ERROR: column "<name>" does not exist,', file=sys.stderr)
            print("      而 schema 本身**仍能执行** —— 这正是只验'能执行'的盲区。",
                  file=sys.stderr)
            return 1
        print(f"  ok   无缺失列 (多余列 {sorted(actual - expected)} 不影响读取)")
    return 0


def main() -> int:
    files = schema_files()
    if not files:
        # 扫描器失效 ≠ 通过
        print("FAIL: 未找到任何会被 spring.sql.init 加载的 schema*.sql —— 扫描器失效",
              file=sys.stderr)
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

    rc = check_columns(jar, files)
    if rc:
        return rc
    print(f"\nPASS: {len(files)} 个 schema.sql 均可在空库独立执行, 且列与实体对齐")
    return 0


if __name__ == "__main__":
    sys.exit(main())