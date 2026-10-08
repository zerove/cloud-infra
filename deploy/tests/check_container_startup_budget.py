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

* 同一批测试里, 走 `InfrastructureContainers.STARTUP_TIMEOUT`(3 分钟)的
  `NotificationRecoveryMultiReplicaIT`(4 项)、`SystemIntegrationTest`(7 项)
  **全部通过**; 走 60 秒默认的则失败。
* `CrossServiceFeignChainIntegrationTest` 的 postgres 硬编码 **90 秒**,
  已低于实测的 109 秒 —— 它本轮 5/5 通过属侥幸(镜像已缓存)。

## 为什么需要门禁而不是只修一次

"某个测试忘了加超时"是一个**每次新增测试都会重演**的问题, 且它的表现形式
极具误导性: 报错是 `Timed out waiting for log output matching
'.*database system is ready to accept connections.*'`, 文案读起来像"机器慢",
极易被归为环境问题而放过 —— 本次就先被这样误判过一轮。

判据取"声明数 <= 预算数", 而不是"文件里出现过 withStartupTimeout":
后者会被同文件里**别的容器**的预算误判为已覆盖(本次 CrossService 三个容器
只有一个设预算, 早期盘点脚本就把它算作"已覆盖")。

## 退出码

| 码 | 含义 |
| :-- | :-- |
| 0 | 全部容器声明都有显式预算 |
| 1 | 存在无预算的容器声明(列出文件与缺口) |
| 2 | 扫描器失效(未找到任何容器声明 / 无可扫描目录) |

**退出码 2 不可省**: 扫描器坏掉时必须与"通过"区分开, 否则路径写错就会
变成恒绿的假门禁(第五课补记一)。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 只扫测试源码: 容器声明只应出现在测试里
SCAN_GLOBS = (
    "cloud-infra-auth/src/test/**/*.java",
    "cloud-infra-gateway/src/test/**/*.java",
    "cloud-infra-modules/*/src/test/**/*.java",
    "cloud-infra-framework/*/src/test/**/*.java",
    "cloud-infra-codegen/*/src/test/**/*.java",
)

# 容器声明: new GenericContainer<>(... / new PostgreSQLContainer("..."
CONTAINER_RE = re.compile(
    r"\bnew\s+(?:GenericContainer|PostgreSQLContainer|MySQLContainer|MongoDBContainer"
    r"|RabbitMQContainer|KafkaContainer|NatsContainer|Neo4jContainer"
    r"|MinIOContainer|LocalStackContainer)\s*[<(]"
)
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

    total_decls = sum(d for _, d, _ in files)
    total_budgets = sum(b for _, _, b in files)
    print(f"扫描 {len(files)} 个含容器声明的测试文件")
    print(f"  容器声明总数: {total_decls}")
    print(f"  显式预算总数: {total_budgets}")

    if not gaps:
        print("PASS: 每个容器声明都有显式启动预算")
        return 0

    print(f"\nFAIL: {len(gaps)} 个文件存在无预算的容器声明", file=sys.stderr)
    for path, decls, budgets in gaps:
        missing = decls - budgets
        print(f"  {path}: 声明 {decls} / 预算 {budgets} → 缺 {missing}", file=sys.stderr)
    print(
        "\n修法: 给缺口容器加 .withStartupTimeout(InfrastructureContainers.STARTUP_TIMEOUT)"
        "\n     (当前 3 分钟; 受控对照实测 postgres n=1 需 109s, n=3 并发最长 125s)",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())