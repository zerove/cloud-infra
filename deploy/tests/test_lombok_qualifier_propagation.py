#!/usr/bin/env python3
"""守护: NotificationServiceImpl 的 @Qualifier 必须在**构造参数**上生效。

## 这个守护拦住的是什么 (2026-10-03)

CI E2E run 37074048182 的真实失败:

    Parameter 8 of constructor in NotificationServiceImpl
    required a single bean, but 2 were found:
      - applicationTaskExecutor: ... VirtualThreadConfig
      - taskScheduler:           ... DefaultTaskSchedulerConfiguration

注意: 这不是"没写 @Qualifier" —— 类里**早就写了**, 在字段上:

    @Qualifier("applicationTaskExecutor")
    private final AsyncTaskExecutor taskExecutor;

看起来合理、编译通过、单测全绿, 但**从未生效**。
原因是类上还有 `@RequiredArgsConstructor`: Lombok 生成的构造器
**不复制字段上的 @Qualifier 到构造参数**, 而 Spring 注入看的是构造参数。

实测证据 (Lombok 1.18.40 + JDK 25, 本机探针):
    @RequiredArgsConstructor + 字段 @Qualifier  -> 参数级 @Qualifier 0 个
    加 lombok.config copyableAnnotations          -> 参数级 @Qualifier 1 个
    生产字节码 NotificationServiceImpl            -> 参数级 @Qualifier 0 个

这是第三课"能力存在 ≠ 能力在运行"的又一例, 但形态更隐蔽:
上一例是"注解在但没开开关", 这次是"注解写对了但写在了没人看的地方"。

## 为什么用字节码而不是源码判定

源码里 `@Qualifier` 一定存在（字段上就有）, 任何基于 grep 的守护都会通过。
唯一能区分的判据是**字节码里构造参数上有没有它** —— 即 Spring 真正看到的。

因此本守护跑 javap。这是它比其他 deploy/tests 下守护更"重"的原因,
换来的是: 它检查的正是运行时依据, 而不是意图。

## 负向对照

  · 把显式构造器改回 @RequiredArgsConstructor -> 必须失败（字节码 0 个）
  · 删掉构造参数上的 @Qualifier（只留在字段）  -> 必须失败
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "cloud-infra-modules" / "notification-service"
CLASSES = MODULE / "target" / "classes"
FQCN = "com.cloudinfra.notification.service.impl.NotificationServiceImpl"
SRC = MODULE / "src" / "main" / "java" / "com" / "cloudinfra" / "notification" / \
    "service" / "impl" / "NotificationServiceImpl.java"


def constructor_block() -> str:
    """取 javap -v 输出里该构造器的那一段。"""
    p = subprocess.run(
        ["javap", "-v", "-p", "-cp", str(CLASSES), FQCN],
        capture_output=True, text=True,
    )
    if p.returncode != 0:
        raise AssertionError(
            f"javap 失败（是否还没编译?）:\n{p.stderr.strip()[:400]}"
        )
    # 构造器签名从 "NotificationServiceImpl(第一个参数类型" 开始到空行
    m = re.search(
        rf"^\s+public\s+{re.escape(FQCN)}\(.*?$.*?^\s*$",
        p.stdout, re.S | re.M,
    )
    if not m:
        raise AssertionError("javap 输出里找不到 NotificationServiceImpl 的构造器段")
    return m.group(0)


def main() -> int:
    failures: list[str] = []

    if not SRC.exists():
        print(f"FAIL 源文件不存在: {SRC}")
        return 1

    src = SRC.read_text(encoding="utf-8")
    block = constructor_block()

    # --- 源码侧: 不得回退到 @RequiredArgsConstructor ------------------------
    #
    # ⚠️ 必须在**剥离注释后**判定: 本类的 javadoc 里就写着
    # "@RequiredArgsConstructor" 这个词（在解释为什么不用它）,
    # 直接对原文 grep 会永远命中 —— 第一版守护正是这么写的,
    # 对正确实现判红。同类错误在 test_config_readback_gate.py 里也踩过。
    code_only = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    code_only = re.sub(r"//[^\n]*", "", code_only)
    if "@RequiredArgsConstructor" in code_only:
        failures.append(
            "类上又有 @RequiredArgsConstructor 了: 它生成的构造器不传播字段上的\n"
            "  @Qualifier, 会把本次修复悄悄撤销。"
        )

    # --- 核心断言: 字节码里构造参数必须带 @Qualifier -------------------------
    if "Qualifier" not in block:
        failures.append(
            "构造器参数上**没有** @Qualifier —— Spring 注入会按类型匹配到两个候选\n"
            "  (applicationTaskExecutor / taskScheduler) 而启动失败, 即 CI run\n"
            "  37074048182 的现象。字段上有 @Qualifier 不算数: Lombok 生成的构造器\n"
            "  不会把它复制到参数上。"
        )

    # --- 构造器必须显式带 @Qualifier（不只是字节码里有）----------------------
    ctor = re.search(
        r"public\s+NotificationServiceImpl\s*\((.*?)\)\s*\{", code_only, re.S
    )
    if not ctor:
        failures.append("找不到显式构造器 —— 本类必须自己写构造器")
    else:
        params = ctor.group(1)
        if "@Qualifier" not in params:
            failures.append(
                "构造器参数列表里没有 @Qualifier, 只有字段上有 —— 这正是缺陷形态"
            )

    for f in failures:
        print(f"FAIL {f}")
    if failures:
        return 1

    print("PASS Lombok @Qualifier 传播守护: 字节码构造参数含 @Qualifier, 且未回退 Lombok")
    return 0


if __name__ == "__main__":
    sys.exit(main())