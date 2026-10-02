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
`ThreadPoolTaskExecutor` 的**字段或构造参数**注入点, 断言其上一行有
`@Qualifier`。

跳过: 注释、import、Bean 方法签名 (那是定义不是注入)。

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

# 构造参数注入的形态: final AsyncTaskExecutor xxx,
FIELD_RE = re.compile(
    r"\b(?:private|protected|public)\s+(?:final\s+)?(" + "|".join(AMBIGUOUS_TYPES) + r")\b\s+\w+\s*;"
)
CTOR_PARAM_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(" + "|".join(AMBIGUOUS_TYPES) + r")\s+(\w+)\s*[,)]"
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


def find_violations() -> list[tuple[str, int, str]]:
    violations = []
    for f in java_files():
        try:
            lines = f.read_text(encoding="utf-8").splitlines()
        except (UnicodeDecodeError, OSError):
            continue
        rel = f.relative_to(REPO)

        for i, raw in enumerate(lines):
            line = strip_comment(raw)
            if not line.strip():
                continue
            # import 行不是注入点
            if line.strip().startswith("import "):
                continue
            # Bean 定义方法 (返回执行器) 是**生产**候选, 不是注入, 跳过。
            if re.search(r"\b(?:public|private|protected)\s+\w[\w<>\[\],\s.]*\s"
                         r"(?:" + "|".join(AMBIGUOUS_TYPES) + r")\s+\w+\s*\(", line):
                continue

            m_field = FIELD_RE.search(line)
            m_ctor = CTOR_PARAM_RE.match(line) if line.lstrip().startswith("@") is False else None

            hit = m_field or m_ctor
            if not hit:
                continue

            # 向上找最近的注解行。字段注入时 @Qualifier 通常紧贴上一行,
            # 也可能与其它注解同行。留 3 行窗口足够。
            window = lines[max(0, i - 3):i]
            has_qualifier = any("@Qualifier" in strip_comment(w) for w in window)
            if not has_qualifier:
                violations.append((str(rel), i + 1, line.strip()))
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