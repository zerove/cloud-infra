#!/usr/bin/env python3
"""门禁: 所有集成测试的 Testcontainers 容器声明必须显式设置启动预算。

## 背景 —— 这条门禁来自一次真实的失败, 不是预防性想象

2026-10-08 的全量 IT (`fullit5`) 得到 97 项 / **0 failures** / **8 errors**,
8 个 error 全部是 `ContainerLaunchException`, 其中 9 次指向 `postgres:16-alpine`。
业务断言一项都没失败 —— 测试方法根本没执行, `@BeforeAll` 就崩了。

受控对照(同一台机器, 唯一变量是并发数):

| 组 | 条件 | postgres 就绪耗时 |
| :-- | :-- | :-- |
| A | n=1 单独起 | **109 秒** |
| B | n=3 并发起 | 125 秒 / 26 秒 / 5 秒(总计 173 秒) |

而未显式声明预算的容器走 **Testcontainers 默认 60 秒**。
即默认预算在本机**任何一种情况下都不够**, 与并发只是加剧而非成因。

两处交叉佐证:

* 事故当天(2026-10-08, archive/local-lineage-2026-11-08 树)同一批测试里,
  走共享常量 `InfrastructureContainers.STARTUP_TIMEOUT`(3 分钟)的
  `NotificationRecoveryMultiReplicaIT`(4 项)、`SystemIntegrationTest`(7 项)
  **全部通过**; 走 60 秒默认的则失败。
  当前树已无该常量(见下方退出码 1 的修法提示), 但同形态的在树证据仍在:
  `TxMessageFlowIntegrationTest` 第 60 行注释记录"本机负载下 RabbitMQ 冷启动
  实测需 ~65s, 默认 60s 等待会超时, 故放宽到 180s"。
* `CrossServiceFeignChainIntegrationTest` 的 postgres 硬编码 **90 秒**,
  已低于实测的 109 秒 —— 它本轮 5/5 通过属侥幸(镜像已缓存)。

## 为什么需要门禁而不是只修一次

"某个测试忘了加超时"是一个**每次新增测试都会重演**的问题, 且它的表现形式
极具误导性: 报错是 `Timed out waiting for log output matching
'.*database system is ready to accept connections.*'`, 文案读起来像"机器慢",
极易被归为环境问题而放过 —— 本次就先被这样误判过一轮。

判据取"声明数 == 预算数", 而不是"文件里出现过 withStartupTimeout":
后者会被同文件里**别的容器**的预算误判为已覆盖(本次 CrossService 三个容器
只有一个设预算, 早期盘点脚本就把它算作"已覆盖")。

⚠️ 曾经用过 `声明数 <= 预算数`, 该口径**掩盖了一处扫描器漏扫**:
SearchServiceIntegrationTest 报"1 声明 / 2 预算", 人工核对发现它其实有
2 个容器、2 个预算、完全合规 —— 是 `ElasticsearchContainer` 不在白名单里,
于是少算了一个声明。而 `1 <= 2` → PASS。

那个口径的危险方向是: 被漏扫的容器**若恰好没有预算, 门禁照样 PASS**
(即"少验了一个容器"被读成"全部覆盖")。与第五课补记一的
"删一个 .class, 剩余的验过了 → exit 0" 同型。

现在改为 `声明数 == 预算数`: 少算与多算都报出来(退出码 2),
把"扫描器是否可信"变成门禁自己的职责, 而不是靠人眼核对统计数字。

## 退出码

| 码 | 含义 |
| :-- | :-- |
| 0 | 声明与预算一一对应, 全部容器都有显式预算 |
| 1 | 存在无预算的容器声明(列出文件与缺口) |
| 2 | 扫描器不可信(未扫到任何声明 / 有扫描根未命中 / 声明数与预算数对不上) |

**退出码 2 不可省**: 扫描器坏掉时必须与"通过"区分开, 否则路径写错就会
变成恒绿的假门禁(第五课补记一)。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 只扫测试源码: 容器声明只应出现在测试里
# (cloud-infra-test-support/src/main 里有一个无人引用的 InfrastructureContainers,
#  属死代码、不参与任何测试运行, 不在本门禁范围; 若将来启用它, 其预算由使用方负责)
SCAN_GLOBS = (
    "cloud-infra-auth/src/test/**/*.java",
    "cloud-infra-gateway/src/test/**/*.java",
    "cloud-infra-modules/*/src/test/**/*.java",
    "cloud-infra-framework/*/src/test/**/*.java",
)

# 容器声明: new GenericContainer<>(... / new PostgreSQLContainer("..."
#
# ⚠️ 两处踩过的坑, 都不是"忘了写", 而是**假设与真实代码不符**:
#
# 1) 白名单漏了容器类型 (2026-10-08):
#    `ElasticsearchContainer` 不在名单里, 于是 SearchServiceIntegrationTest 的
#    ES 容器从未被数到 —— 而该文件实际有 2 容器 / 2 预算, 完全合规。
#    它之所以暴露, 是因为出现了"预算数 > 声明数"这种**自相矛盾**的统计;
#    若被漏扫的那个容器恰好没预算, 聚合判据 `d <= b` 会照样 PASS。
#
# 2) `[<(]` 之前可能夹着泛型参数:
#    `new GenericContainer<>(...)` 在 `Container` 与 `(` 之间还有 `<?>`,
#    要求紧邻会漏扫这一形式。
#
# 两条的共同教训: **白名单要靠"从被测系统里提取"来维护, 不能凭记忆列**。
# 新增测试文件或升级 Testcontainers 引入新容器类型时, 必须同步补这里,
# 否则该文件的所有容器都会静默退出守护范围。
CONTAINER_RE = re.compile(
    r"\bnew\s+(?:GenericContainer|PostgreSQLContainer|MySQLContainer|MongoDBContainer"
    r"|RabbitMQContainer|KafkaContainer|NatsContainer|Neo4jContainer"
    r"|MinIOContainer|LocalStackContainer"
    # Testcontainers 官方模块提供的容器类型 —— 漏一个就是一处静默失守。
    r"|ElasticsearchContainer|OpenSearchContainer|MSSQLServerContainer"
    r"|OracleContainer|CassandraContainer|CouchbaseContainer|InfluxDBContainer"
    r"|MariaDBContainer|SingleNodeElasticsearchContainer"
    # 任何以 Container 结尾的类型 —— 白名单逐个枚举必然漏, 改为兜底。
    #
    # ⚠️ 兜底必须**按源码里的真实命名提取**, 不能凭想象。负向对照实测:
    # 我先写了 `[A-Z][A-Za-z0-9]*TestContainer`(只覆盖 *TestContainer),
    # 注入一个 `ScopedMockContainer` 后门禁仍报 PASS —— 它不以 Test 结尾,
    # 兜底没接住。改为凡以 Container 结尾即匹配, 该用例才变红。
    # 这正是"负向对照要打在探针本身"的又一次复现:
    # 没做对照时, 这个洞会一路留到下一个真实容器类型出现。
    r"|[A-Z][A-Za-z0-9]*Container"
    r")\s*(?:<[^;\n]*?>)?\s*[<(]"
)
# 容器类型名后面允许出现任意合法的泛型参数, 直到该泛型闭合的 `>`:
# 匹配 `<>` / `<?>` / `<? extends Foo>` / `<String, Integer>` 均可。
# 上限 `[^;\n]*?` 是为了防止跨行/跨语句贪婪匹配把无关代码算成声明。
BUDGET_RE = re.compile(r"\.withStartupTimeout\s*\(")
# 注释里的容器提及不算声明。
#
# ⚠️ 这里踩过一次真实的自伤: 最初写成 `//.*$|/\*.*?\*/` 并同时启用
# `MULTILINE | DOTALL` —— DOTALL 让 `.` 匹配换行, 于是第一个分支
# `//.*$` 从文件里第一个 `//` **一路吃到文末**, 把大段真实代码当成注释删掉。
# 症状极具欺骗性: 剥离后 `withStartupTimeout` 计数从 2 变 **0**,
# 而每个文件的声明数都恰好是 1 —— 看起来像"所有文件都缺预算",
# 实际上是我自己的探针在吃代码(第五课补记一: 扫描器自己坏掉时会长成守护的样子)。
#
# 正解: 两个分支各自用**不带 DOTALL** 的正则, 组合时只保留 MULTILINE。
# 行注释 `.` 不跨行; 块注释用非贪婪 `*?` 也不会跨块。
COMMENT_LINE_RE = re.compile(r"//[^\n]*")
COMMENT_BLOCK_RE = re.compile(r"/\*.*?\*/", re.DOTALL)


def strip_comments(src: str) -> str:
    """去掉注释, 避免注释里的 new GenericContainer 被当成真声明。"""
    return COMMENT_BLOCK_RE.sub("", COMMENT_LINE_RE.sub("", src))


def scan() -> tuple[list[tuple[str, int, int]], list[str]]:
    """返回 (命中列表, 未命中的扫描根列表)。

    未命中的扫描根**必须报出来**: 少扫一个模块而门禁仍然 PASS,
    等于"没验到"被当成"验过了"。第五课补记一: 没验到 ≠ 验过了。
    """
    found: list[tuple[str, int, int]] = []
    empty_roots: list[str] = []
    seen: set[Path] = set()
    for pattern in SCAN_GLOBS:
        root_hit = 0
        for path in ROOT.glob(pattern):
            if path in seen or not path.is_file():
                continue
            seen.add(path)
            src = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
            decls = len(CONTAINER_RE.findall(src))
            if decls == 0:
                continue
            budgets = len(BUDGET_RE.findall(src))
            found.append((str(path.relative_to(ROOT)), decls, budgets))
            root_hit += 1
        if root_hit == 0:
            empty_roots.append(pattern)
    return sorted(found), empty_roots


def main() -> int:
    files, empty_roots = scan()

    # 扫描器失效的判据是"有扫描根一个都没命中", 而不是"总共扫到 0 个"。
    # 后者太宽松: 删掉整个 cloud-infra-auth 目录后其余模块仍有声明,
    # 门禁照样 PASS —— 即"少验了一个模块"被读成了"通过"(实测踩过)。
    if empty_roots:
        print("SCAN-FAIL: 以下扫描根未命中任何容器声明 —— 扫描路径失效, 不是通过", file=sys.stderr)
        for pattern in empty_roots:
            print(f"  未命中: {pattern}", file=sys.stderr)
        print(f"  扫描根: {ROOT}", file=sys.stderr)
        print("  (若某模块确实不再声明容器, 请把它的 glob 从 SCAN_GLOBS 移除)", file=sys.stderr)
        return 2

    if not files:
        print("SCAN-FAIL: 未找到任何 Testcontainers 声明 —— 扫描器失效, 不是通过", file=sys.stderr)
        return 2

    gaps = [(p, d, b) for p, d, b in files if d > b]
    # 预算 > 声明同样是**缺陷**, 不是"更安全"。
    #
    # 实测踩过(2026-10-08): SearchServiceIntegrationTest 报 "1 声明 / 2 预算",
    # 人工核对发现该文件**确实有 2 个容器、2 个预算, 完全合规** ——
    # 只是其中一个容器用了 CONTAINER_RE 没覆盖到的声明形式, 于是"少算一个声明"。
    # 而当时的判据是 `d <= b`, 聚合后 1 <= 2 → PASS, 漏扫被完全掩盖。
    #
    # 更危险的是它的**失效方向**: 若那个被漏扫的容器恰好**没有**预算,
    # 判据依然 PASS —— 即"少验了一个容器"被读成了"全部覆盖"。
    # 这与第五课补记一的"删一个 .class, 剩余的验过了 → exit 0" 同型。
    #
    # 因此: `d != b` 一律报出。声明数与预算数必须一一对应,
    # 对不上就说明要么有漏扫(要补正则), 要么有多余预算(要删),
    # 两种都不该由门禁替人猜测。
    mismatched = [(p, d, b) for p, d, b in files if d < b]

    total_decls = sum(d for _, d, _ in files)
    total_budgets = sum(b for _, _, b in files)
    print(f"扫描 {len(files)} 个含容器声明的测试文件")
    print(f"  容器声明总数: {total_decls}")
    print(f"  显式预算总数: {total_budgets}")

    if mismatched:
        print(
            f"\nSCAN-FAIL: {len(mismatched)} 个文件的预算数多于声明数 —— "
            f"扫描器可能漏扫了容器声明, 不能判通过",
            file=sys.stderr,
        )
        for path, decls, budgets in mismatched:
            print(
                f"  {path}: 声明 {decls} / 预算 {budgets} → 多 {budgets - decls}",
                file=sys.stderr,
            )
        print(
            "\n这通常意味着 CONTAINER_RE 没覆盖该文件里某种容器声明形式。\n"
            "     请人工核对该文件的容器数量, 补全正则或修正声明 —— \n"
            "     不要靠调大判据让它变绿: 漏扫在缺预算时同样会 PASS。",
            file=sys.stderr,
        )
        return 2

    if not gaps:
        print("PASS: 每个容器声明都有显式启动预算")
        return 0

    print(f"\nFAIL: {len(gaps)} 个文件存在无预算的容器声明", file=sys.stderr)
    for path, decls, budgets in gaps:
        missing = decls - budgets
        print(f"  {path}: 声明 {decls} / 预算 {budgets} → 缺 {missing}", file=sys.stderr)
    print(
        "\n修法: 给缺口容器加 .withStartupTimeout(Duration.ofMinutes(3))"
        "\n     （本仓没有 InfrastructureContainers.STARTUP_TIMEOUT 常量;"
        " 现有合规文件用 Duration.ofMinutes(3) 或 ofSeconds(180)）"
        "\n     (当前 3 分钟; 受控对照实测 postgres n=1 需 109s, n=3 并发最长 125s)",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())