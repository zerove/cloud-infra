#!/usr/bin/env python3
"""守护: 按类型注入 AsyncTaskExecutor 时必须带 @Qualifier。

## 背景 (CI E2E run 37059177909, 2026-10-03)

notification-service 在 CI 上启动失败, 真实异常:

    Parameter 8 of constructor in com.cloudinfra.notification.service.impl.
    NotificationServiceImpl required a single bean, but 2 were found:
      - applicationTaskExecutor: ... VirtualThreadConfig
      - taskScheduler:          ... DefaultTaskSchedulerConfiguration

`AsyncTaskExecutor` 在上下文里有**两个**候选 —— 本项目的虚拟线程执行器, 以及
Spring Boot 在 `spring.task.scheduling` 配置非空时注册的 `taskScheduler`。
按类型注入即二义。

## 为什么本地长期不复现

notification-service.yaml 里有 `spring.task.scheduling.pool.size: 2`。
该配置使 `taskScheduler` 被注册, 于是二义必然出现。
而本地 `deploy/nacos/data` 长期保留旧配置残留 (且历史上那份配置没有
`spring.task.scheduling` 段), 因此 `taskScheduler` 根本没被注册 ——
只有一个候选, 注入自然成功。

即: **本地绿是因为配置残缺, 不是因为代码正确**。这类"环境差异掩盖代码缺陷"
只靠跑测试发现不了, 必须有静态守护。

## 为什么不用 ArchUnit

现有 `ArchitectureTest` 在 system-module 下, 其自身文档已记录已知限制:
sibling module (notification/file/search) 默认不在 test classpath,
规则匹配零类。因此它**扫不到**本缺陷涉及的类, 用它守护会得到假绿。

## 判定方式

扫 `src/main/java` 下所有 `.java`, 找出 `AsyncTaskExecutor` / `TaskExecutor` /
`ThreadPoolTaskExecutor` / `AsyncTaskScheduler` 的**注入点**, 断言有 `@Qualifier`。

**注入点有两类, 必须分别处理（2026-10-05 实测踩到）**：

1. **字段声明** `private final AsyncTaskExecutor x;` —— `@Qualifier` 在字段上
   （仅当类用 `@RequiredArgsConstructor` 时才有效, 见 `LombokQualifierPropagationTest`）。
2. **构造器参数** —— `@Qualifier` 必须**直接标在参数上**。

⚠️ 本守护曾**只扫第 1 类**且只在字段上方 3 行找注解, 于是对
`NotificationServiceImpl` 报假阳性: 那个类的 `AsyncTaskExecutor taskExecutor`
字段**本来就不该带** `@Qualifier`（Lombok 不复制字段注解到构造参数, 加了反而误导）,
真正的限定名在显式构造器的第 9 个参数上:

    @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {

两个守护各扫一半、互相覆盖不到, 结果是"两个都绿, 真实缺陷从中间漏过去"。
**字段带 @Qualifier 但构造参数没带**才是要抓的形态 —— 那是"看起来修过、实际无效"
（CI run 37074048182 的真因）。

## 跳过

- 注释、import 行
- Bean 定义方法签名（返回执行器）—— 那是**生产**候选, 不是注入
- **字段声明**（只作定位锚点, 真正判定看构造参数）

## 负向对照

去掉任一注入点的 `@Qualifier`, 本守护必须失败。
"""
import re
import sys
from pathlib import Path

# 仓库根: deploy/tests/ -> deploy/ -> 仓库根
REPO = Path(__file__).resolve().parents[2]

# 会被 Spring 同时注册多个候选的执行器类型。
# TaskScheduler 虽不是 Executor, 但 implements AsyncTaskExecutor, 同样会撞车。
AMBIGUOUS_TYPES = (
    "AsyncTaskExecutor",
    "TaskExecutor",
    "ThreadPoolTaskExecutor",
    "AsyncTaskScheduler",
)

# 扫描范围: 业务模块 + framework。排除测试与构建产物。
SCAN_GLOBS = (
    "cloud-infra-modules/*/src/main/java",
    "cloud-infra-framework/*/src/main/java",
    "cloud-infra-starter/src/main/java",
)

# 字段声明: 只作**定位锚点**（找到所属类与构造器）, 本身不参与判定。
FIELD_RE = re.compile(
    r"\b(?:private|protected|public)\s+(?:final\s+)?(" + "|".join(AMBIGUOUS_TYPES) + r")\b\s+(\w+)\s*;"
)


def strip_comment(line: str) -> str:
    """去掉行内 // 注释。"""
    idx = line.find("//")
    return line[:idx] if idx >= 0 else line


def java_files() -> list[Path]:
    out: list[Path] = []
    for pattern in SCAN_GLOBS:
        for d in sorted(REPO.glob(pattern)):
            if d.is_dir():
                out.extend(sorted(d.rglob("*.java")))
    return out


def is_bean_definition(line: str) -> bool:
    """Bean 定义方法（返回执行器）是**生产**候选, 不是注入。"""
    return bool(re.search(r"\b(?:public|private|protected)\s+\w[\w<>\[\],\s.]*\s"
                          r"(?:" + "|".join(AMBIGUOUS_TYPES) + r")\s+\w+\s*\(", line))


def _class_body(lines: list[str], field_idx: int) -> tuple[int, int]:
    """返回字段所在类的 body 区间 [start, end)（0-based 行号索引）。

    用途: 从字段声明反查它对应的构造器。Spring 注入看的是**构造参数**,
    而字段与构造参数在源码上往往相隔几十行（中间是 javadoc）,
    只在字段附近找注解必然漏 —— 这正是本守护先前的假阳性来源。
    """
    start = -1
    for k in range(field_idx, -1, -1):
        if re.search(r"\b(?:class|record|enum)\s+\w+", strip_comment(lines[k])):
            start = k
            break
    if start < 0:
        return -1, -1

    i = start
    while i < len(lines) and "{" not in strip_comment(lines[i]):
        i += 1
    if i >= len(lines):
        return -1, -1
    depth = 0
    for j in range(i, len(lines)):
        stripped = strip_comment(lines[j])
        depth += stripped.count("{") - stripped.count("}")
        if depth == 0:
            return i + 1, j
    return -1, -1


def _find_ctor_params(lines: list[str], lo: int, hi: int, type_name: str,
                      field_name: str) -> list[int]:
    """在 [lo, hi) 内找出构造器里 `type_name field_name` 这个参数所在的行号。

    ⚠️ 判据必须**只认含类型+字段名的那一行**，不能用「长得像方法调用」。
    先前用 `\b\w+\s+\w+\s*\(\s*$` 兜底，结果把两样无关的东西算成参数：
      - 构造器**头行** `public NotificationServiceImpl(`（那是形参列表的起点, 不是某个参数）
      - 方法体里任意 `throw new IllegalArgumentException(`（单行里有 `new X(` 就匹配）
    即「找参数」这件事本身的判据太宽, 会把修复变成制造新缺陷（第五课）。
    """
    hits: list[int] = []
    pattern = re.compile(r"\b" + re.escape(type_name) + r"\s+" + re.escape(field_name)
                         + r"\s*[,)]")
    for j in range(lo, hi):
        if not pattern.search(strip_comment(lines[j])):
            continue
        # @Bean 工厂方法返回执行器, 那是**生产**候选不是注入
        if is_bean_definition(strip_comment(lines[j])):
            continue
        hits.append(j)
    return hits


def _qualifier_present(lines: list[str], idx: int) -> bool:
    """参数处是否带 @Qualifier: 同行, 或紧邻的上一行（跳过空行）。"""
    if "@Qualifier" in strip_comment(lines[idx]):
        return True
    k = idx - 1
    while k >= 0 and not strip_comment(lines[k]).strip():
        k -= 1
    return k >= 0 and "@Qualifier" in strip_comment(lines[k])


def find_violations() -> list[tuple[str, int, str]]:
    violations: list[tuple[str, int, str]] = []
    for f in java_files():
        try:
            lines = f.read_text(encoding="utf-8").splitlines()
        except (UnicodeDecodeError, OSError):
            continue
        rel = f.relative_to(REPO)

        for i, raw in enumerate(lines):
            line = strip_comment(raw)
            if not line.strip() or line.strip().startswith("import "):
                continue

            m_field = FIELD_RE.search(line)
            if not m_field:
                continue
            field_name = m_field.group(2)

            lo, hi = _class_body(lines, i)
            if lo < 0:
                # 找不到所属类（异常排版）—— 不得静默跳过
                violations.append((str(rel), i + 1,
                                   line.strip() + "   [无法定位所属类, 需人工确认]"))
                continue

            params = _find_ctor_params(lines, lo, hi, m_field.group(1), field_name)
            if not params:
                # 没有显式构造器 —— 用的是 @RequiredArgsConstructor 等。
                # 此时字段上的 @Qualifier 是唯一入口, 但它对 Lombok **不生效**
                # （不复制到构造参数）, 由 LombokQualifierPropagationTest 守护。
                # 本守护不重复报, 否则就是对"已用显式构造器"的类误判。
                continue

            for j in params:
                if not _qualifier_present(lines, j):
                    violations.append((str(rel), j + 1, strip_comment(lines[j]).strip()))
    return violations


def main() -> int:
    files = java_files()
    if not files:
        print(f"FAIL: 未扫到任何 Java 源文件 (globs={SCAN_GLOBS}), "
              "目录结构可能已变, 需人工确认 —— 不能当作通过")
        return 1

    violations = find_violations()

    print("=== 执行器注入限定名检查 ===")
    print(f"  扫描文件数 : {len(files)}")
    print(f"  违规注入点 : {len(violations)}")

    if violations:
        print()
        for path, lineno, text in violations:
            print(f"FAIL: {path}:{lineno}  {text}")
            print("      AsyncTaskExecutor 有多个候选 (applicationTaskExecutor / taskScheduler),"
                  " 按类型注入必然二义。")
            print("      加 @Qualifier(\"applicationTaskExecutor\")。")
        return 1

    print("\nPASS: 所有执行器注入点均带 @Qualifier")
    return 0


if __name__ == "__main__":
    sys.exit(main())