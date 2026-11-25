#!/usr/bin/env python3
"""守护: 任何注入 AsyncTaskExecutor / TaskScheduler 的构造参数, 字节码上必须带 @Qualifier。

## 这个守护拦住的是什么

**同一个根因在 CI 上真实发生了三次**, 每次只暴露一个类:

| run | 类 | 参数 |
| :--- | :--- | :--- |
| 37059177909 / 37074048182 | `NotificationServiceImpl` | Parameter 8 |
| 37077332251 | `NotificationDispatchRecoveryTask` | Parameter 4 |

典型报错:

    Parameter 4 of constructor in ...NotificationDispatchRecoveryTask
    required a single bean, but 2 were found:
      - applicationTaskExecutor: ... VirtualThreadConfig
      - taskScheduler:           ... DefaultTaskSchedulerConfiguration

注意: 这不是"没写 @Qualifier" —— 类里**早就写了**, 在字段上:

    @Qualifier("applicationTaskExecutor")
    private final AsyncTaskExecutor taskExecutor;

看起来合理、编译通过、单测全绿, 但**从未生效**。
原因是类上还有 `@RequiredArgsConstructor`: Lombok 生成的构造器
**不复制字段上的 @Qualifier 到构造参数**, 而 Spring 注入看的是构造参数。

**为什么有 @Qualifier 还不够、却又必须**: `applicationTaskExecutor` 与 `taskScheduler`
都实现 `AsyncTaskExecutor`, 按类型注入必然二义。Spring 只有看到**构造参数上**的
`@Qualifier` 才会消歧。

## 为什么不逐个类改成显式构造器

第一版逐个改, 结果是 CI 立刻报出下一个同类类 —— 逐个修只是让 bug 一个一个冒出来。
现在改为根 `lombok.config` 里 `lombok.copyableAnnotations` 一次性生效,
字段上写 `@Qualifier` 就等价于写在构造参数上。

## 为什么用字节码而不是源码判定

源码里 `@Qualifier` 一定存在（字段上就有）, 任何基于 grep 的守护都会通过。
唯一能区分的判据是**字节码里构造参数上有没有它** —— 即 Spring 真正看到的。
因此本守护跑 javap。

## 扫描范围怎么来的（不许猜）

候选类从**源码里真实存在的注入点**提取: 搜 `private final <TaskExecutor|AsyncTaskExecutor|
TaskScheduler>`, 逐个映射到 target/classes 的同名 .class。
不维护手写名单 —— 手写名单就是第五课说的"假证据": 新增类不会进名单, 于是静默漏过。

## 负向对照

  · 删掉根 lombok.config 的 copyableAnnotations -> 必须失败
  · 恢复 @RequiredArgsConstructor 且去掉显式构造器 -> 必须失败
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOTS = [
    ROOT / "cloud-infra-modules",
    ROOT / "cloud-infra-framework",
    ROOT / "cloud-infra-common",
    ROOT / "cloud-infra-gateway",
    ROOT / "cloud-infra-auth",
    ROOT / "cloud-infra-starter",
]

# 需要 @Qualifier 消歧的类型: 这些在容器里都存在**第二个**同类型候选。
#   applicationTaskExecutor -> VirtualThreadConfig
#   taskScheduler           -> DefaultTaskSchedulerConfiguration
AMBIGUOUS = re.compile(
    r"private\s+final\s+"
    r"(?:AsyncTaskExecutor|TaskExecutor|AsyncTaskScheduler|TaskScheduler)\s+(\w+)\s*;"
)

LOMBOK_CONFIG = ROOT / "lombok.config"
QUALIFIER_ENTRY = (
    "lombok.copyableAnnotations += "
    "org.springframework.beans.factory.annotation.Qualifier"
)


def source_candidates() -> list[tuple[str, Path]]:
    """返回 [(类全名, 源码路径)], 从真实源码提取, 不含测试。"""
    found = []
    for root in JAVA_ROOTS:
        if not root.exists():
            continue
        for jf in root.rglob("*.java"):
            if "/src/test/" in str(jf) or jf.name.endswith("Tests.java"):
                continue
            try:
                text = jf.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            hits = AMBIGUOUS.findall(text)
            if not hits:
                continue
            # 从 package + 类名推出 FQCN —— 不靠猜, 直接读文件。
            pkg = re.search(r"^package\s+([\w.]+);", text, re.M)
            cls = re.search(r"^(?:public\s+)?(?:final\s+)?class\s+(\w+)", text, re.M)
            if not pkg or not cls:
                continue
            found.append((f"{pkg.group(1)}.{cls.group(1)}", jf))
    return sorted(set(found))


def classes_dir_for(src: Path) -> Path | None:
    """源码路径 -> 该 Maven 模块的 target/classes 根。

    ⚠️ 不能直接用 JAVA_ROOTS 里的聚合目录（如 cloud-infra-modules/）:
       它**没有**自己的 target/, 只是多个模块的父目录。
       真正的模块根是**向上最近的含 pom.xml 的目录**。
       第一次实现就是这么错的 —— 推导出 .../cloud-infra-modules/target/classes,
       于是字节码永远找不到, 守护对**全部**注入点报 SKIP,
       而 exit 0 让它看上去一切正常。这正是"假门禁"的一种形态:
       扫描器什么都没验, 却给出了通过的姿态。
    """
    src = src.resolve()
    cur = src.parent
    root: Path | None = None
    while True:
        if (cur / "pom.xml").exists():
            root = cur
            break
        if cur == ROOT or cur == cur.parent:
            return None
        cur = cur.parent
    assert root is not None
    if "src" not in src.relative_to(root).parts:
        return None
    return root / "target" / "classes"


def param_qualifier_count(classes_dir: Path, fqcn: str) -> int | None:
    """构造器**参数**上的 @Qualifier 个数。None = 取不到字节码。"""
    cf = classes_dir / Path(fqcn.replace(".", "/")).with_suffix(".class")
    if not cf.exists():
        return None
    p = subprocess.run(
        ["javap", "-v", "-p", "-cp", str(classes_dir), fqcn],
        capture_output=True, text=True,
    )
    if p.returncode != 0:
        return None
    # ⚠️ 段尾不能用"空行": 构造器体里 descriptor/flags/Code 各行都以分号结尾,
    #   正则 `.*?^\s*$` 会在 descriptor 行就停下, 永远走不到
    #   RuntimeVisibleParameterAnnotations —— 于是有效的字节码被读成 0。
    #   第一版就是这么错的: 修好后守护反而对正确代码判红。
    #   正确边界: 下一个同缩进的方法/字段签名行（两个空格缩进 + 非 descriptor）。
    lines = p.stdout.splitlines()
    start = None
    for i, ln in enumerate(lines):
        s = ln.strip()
        if s.startswith(f"public {fqcn}(") or s.startswith(f"protected {fqcn}(") \
                or s.startswith(f"{fqcn}("):
            start = i
            break
    if start is None:
        return None
    end = len(lines)
    for j in range(start + 1, len(lines)):
        ln = lines[j]
        # 新成员开始: 两个空格缩进, 且不是 javap 的属性行
        if ln.startswith("  ") and not ln.startswith("   ") and ln.strip():
            end = j
            break
    seg = "\n".join(lines[start:end])
    # 只数参数注解区里的 @Qualifier（字段/类上的不算）
    n = 0
    in_params = False
    for ln in seg.splitlines():
        if "RuntimeVisibleParameterAnnotations" in ln or \
                "RuntimeInvisibleParameterAnnotations" in ln:
            in_params = True
            continue
        if "RuntimeVisibleAnnotations" in ln or \
                "RuntimeInvisibleAnnotations" in ln or \
                "Signature:" in ln or "Exceptions:" in ln:
            in_params = False
        if in_params and re.search(
                r"org\.springframework\.beans\.factory\.annotation\.Qualifier\s*\(",
                ln):
            n += 1
    return n


def main() -> int:
    failures: list[str] = []

    # --- 配置层: 根 lombok.config 必须含 copyableAnnotations -----------
    if not LOMBOK_CONFIG.exists():
        print(f"FAIL 缺少 {LOMBOK_CONFIG.relative_to(ROOT)} —— "
              "字段级 @Qualifier 不会被 Lombok 复制到构造参数")
        return 1
    cfg = LOMBOK_CONFIG.read_text(encoding="utf-8")
    if "org.springframework.beans.factory.annotation.Qualifier" not in cfg:
        print("FAIL lombok.config 未把 @Qualifier 加入 copyableAnnotations")
        return 1
    print("PASS lombok.config 含 @Qualifier copyableAnnotations")

    cands = source_candidates()
    if not cands:
        print("FAIL 源码里一个注入点都没扫到 —— 扫描器本身坏了, 不能判绿")
        return 1
    print(f"INFO 扫描到 {len(cands)} 个需要消歧的注入点")

    # 预检: 先把所有 classes 路径算出来, 一个都定位不到就是推导坏了,
    # 而不是"未编译" —— 这两种必须分开, 否则退化成全 SKIP 的假门禁。
    located = [(fq, s, classes_dir_for(s)) for fq, s in cands]
    unresolved = [fq for fq, _, c in located if c is None]
    if unresolved and len(unresolved) == len(located):
        print(f"FAIL 所有注入点都无法定位 target/classes: {unresolved} —— "
              "模块根推导坏了, 不能判绿")
        return 1

    skipped = 0
    checked = 0
    unverified: list[str] = []
    for fqcn, src, cdir in located:
        if cdir is None:
            failures.append(f"{fqcn}: 无法定位 target/classes")
            continue
        n = param_qualifier_count(cdir, fqcn)
        if n is None:
            # ⚠️ 未编译 ≠ 注解未生效。分开表达, 不冒充判红, 也不静默放过。
            skipped += 1
            unverified.append(fqcn)
            print(f"SKIP 未编译, 本次不判定: {fqcn}")
            continue
        checked += 1
        if n < 1:
            failures.append(
                f"{fqcn}: 构造参数上 @Qualifier = {n}（应为 >=1）"
                f"\n      {src.relative_to(ROOT)}"
            )
        else:
            print(f"PASS {fqcn}: 构造参数 @Qualifier x{n}")

    print()
    if skipped and not checked:
        # ⚠️ 这里不能直接 exit 0。
        #   实测过: 把类改到编译不过 -> 字节码全没 -> 全 SKIP -> exit 0,
        #   守护对**什么都没验**的代码给出了"通过"姿态。
        #   区分两种"未编译":
        #     · target/classes 整个不存在 -> 确实还没编译, 跳过合理;
        #     · target/classes 存在但 .class 缺失 -> 编译失败或产物陈旧, 判红。
        roots = {c for _, _, c in located if c is not None}
        if roots and not any(c.exists() for c in roots):
            print(f"SKIP {skipped} 个注入点全部未编译 —— 本次不判定。"
                  "请在编译之后运行本守护。")
            return 0
        print(f"FAIL {skipped} 个注入点全部取不到字节码, 但 target/classes "
              "已存在 —— 多半是编译失败或产物陈旧。不能把'没验到'说成'验过了'。")
        return 1
    if skipped:
        # ⚠️ 部分 SKIP 也**不能**判绿。
        #   实测过: 删掉一个 .class -> 守护只报 WARN 后 exit 0,
        #   剩下那个类验过了, 删掉的那个**完全没验**。
        #   "我验到了 N 个里的 2 个, 剩下 0 个没验" 不等于 "全部通过"。
        #   本守护已被固定在编译之后（e2e.yml 里 verify-e2e.sh 之后 + if: always）,
        #   那里字节码必然存在, 所以 SKIP 只能是异常情况, 不该宽容。
        print(f"FAIL {skipped} 个注入点取不到字节码（未编译或产物缺失），"
              "本次**未全部**判定:\n" + "\n".join(f"  - {x}" for x in unverified))
        failures.append(f"{skipped} 个注入点取不到字节码，未判定")

    if failures:
        print("FAIL 以下注入点的 @Qualifier 未在构造参数上生效:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"PASS Lombok @Qualifier 传播守护: "
          f"{checked} 个注入点的字节码构造参数均含 @Qualifier")
    return 0


if __name__ == "__main__":
    sys.exit(main())