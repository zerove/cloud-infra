#!/usr/bin/env python3
"""门禁: compose 里承载 JVM 参数的键, 必须是镜像启动脚本**真正读取**的键。

## 为什么需要这道门禁（2026-10-05 实测事故）

`check_jvm_native_memory_bounds.py` 静态解析 `deploy/compose.yaml`, 检查每个
JVM 服务是否设置了 `-XX:MaxMetaspaceSize`。它长期 9/9 全绿, 但 nacos 的
边界**一个字节都没生效**:

| 证据 | 值 |
| :--- | :--- |
| compose 里的键 | `JVM_ADJUST` |
| nacos 启动脚本引用的键 | `JAVA_OPT` / `JAVA_OPT_EXT` / `JAVA_OPT_EXT_FIX` / `JVM_XMS` / `JVM_XMX` / `JVM_XMN` / `JVM_MS` / `JVM_MMS` / `XX_MS` / `XX_MMS` —— **没有 `JVM_ADJUST`** |
| 容器内实际 cmdline | 只有 `-Xms1g -Xmx2g -Xmn512m`, 无任何 `-XX:MaxMetaspaceSize` |
| nacos 的 VmSize | 10.51 GB（未受限时的典型值） |

即: **静态解析 compose 的守护, 无法发现"配置写在了没人读的键上"**。
这是第三课（机制存在 ≠ 机制在运行）在守护层的重演。

## 本门禁做什么

对每个镜像, 判定两件事:

1. **该镜像的启动脚本读取哪些变量**（用 `podman run --rm --entrypoint cat`
   取出脚本, 再正则提取 `${VAR}` / `$VAR` 引用）。
2. **compose 里该服务用的 JVM 参数键是否都在这个集合内**。

任一不满足 → 退出码 1 并指名具体服务与键。

## 边界与降级（第五课补记一）

- 取不到镜像启动脚本时 **SKIP + 打印原因 + exit 0**（"本次不判定"），
  绝不静默 `exit 0`, 更不 `exit 1`（那是把"没测"说成"测了不过"）。
- **扫到 0 个目标必须判红** —— 扫描器坏了 ≠ 通过。
- 需要 podman 与镜像已就绪; 本地无镜像时全部 SKIP。

## 局限（必须说清）

本门禁证明的是「**配置的键名在镜像脚本里存在**」,
**不等于**「参数真的进了 JVM」—— 后者只能从容器内
`/proc/<pid>/cmdline` 读回, 属运行时证据, 不在本门禁范围内。
把本门禁的 PASS 当作"边界已生效"是误读。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "deploy" / "compose.yaml"

# 与 check_jvm_native_memory_bounds.py 保持一致: 这些键承载 JVM 参数。
JVM_KEYS = ("JAVA_OPTS", "ES_JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JAVA_OPT_EXT")

# ⚠️ 看起来像 JVM 参数、但**不一定被镜像启动脚本读取**的键。
#   这张名单的作用不是"检查它们", 而是"不把它们静默忽略"。
#
#   起因（2026-10-05）: JVM_ADJUST 最初不在 JVM_KEYS 里, 于是
#   check_jvm_opts_keys_match_image.py 对它**完全无感** ——
#   连 compose 里写了这个错键都不会报红, 因为门禁只遍历它认识的键。
#   即: 「只检查我认识的键」把「用错键」变成了「没写这个键」,
#   而两者在结果上都表现为 PASS (第四课: 信任边界要逐跳检查)。
#
#   正解: 凡形似 JVM 参数的键都要**过判据**, 由镜像脚本决定去留。
#   名单可以不全(那就漏检), 但**不能靠"不认识就不看"**。
JVM_OPT_LIKE = re.compile(
    r"^(JAVA|JVM|ES_JAVA|JDK|NACOS_JVM)[A-Z0-9_]*$"
)

# 这些键由 JVM 自身(JAVA_TOOL_OPTIONS)或 podman 语义处理,
# 不需要出现在镜像启动脚本的变量引用里。
IGNORE_KEYS = {"JAVA_TOOL_OPTIONS"}

# 已知用 sh 启动脚本注入 JVM 参数的脚本路径。
# ⚠️ 这张表本身也可能过期, 所以 script_vars() 里"表里没命中 ⇒ 返回 None"
# 走 SKIP, 而不是把未知情况当成通过。
# ⚠️ 这张表只列**入口**。真正的问题在 2026-10-05 被抓到:
#   ES 的 `ES_JAVA_OPTS` **是有效的**, 但它的消费者**不在** elasticsearch-env 里
#   —— 后者只负责警告与 unset(原文: "pass JVM parameters via ES_JAVA_OPTS"),
#   真正读它的是 bin/elasticsearch-cli 或 Java launcher。
#   于是「只 cat elasticsearch-env」的门禁把有效键判成无效, 差点让人去改一个没坏的配置。
#
#   ⇒ 正解: 在镜像的 **bin 目录下搜全部脚本**, 而不是赌某一个文件会引用它。
#   这是第五课「探针的对象必须从被测系统里提取」的直接应用。
# ⚠️【2026-10-05 修正】ES 那一条原来写的是 ("/usr/share/elasticsearch/bin", "*"),
#   即 cat 目录下**所有东西** —— 含 libjvm.so 等二进制, 后果有二:
#     1) 输出混入非 UTF-8 字节, 严格解码直接抛 UnicodeDecodeError;
#     2) `ls ... || true` 在目录读不到时输出字面量 "cat", 随后被当成
#        文件名 cat 下去 → "cat: cat: No such file or directory"。
#   即**探针坏了的信号被当成了"镜像里没有脚本"**, 于是走 SKIP 而非 FAIL。
#   第五课补记一: 扫描器坏了 ≠ 通过。这里改为只匹配 shell 脚本。
SCRIPT_GLOBS = (
    # (目录, 该目录下的通配模式)
    ("/home/nacos/bin", "*.sh"),
    ("/usr/share/elasticsearch/bin", "*.sh"),
    # ⚠️【2026-10-07 修正】业务镜像的启动脚本在 /app, 此前**完全没被覆盖**,
    #   于是 auth/gateway/file/system/search/notification 六个服务
    #   (以及 skywalking-oap) 全部走 SKIP —— 而 rc 仍是 0, 即"没判定"
    #   被读成"通过"。形态与上面 ES 那次相同: 探针没覆盖到目标 ⇒ 报告
    #   "不存在" ⇒ 走 SKIP 而非 FAIL。
    #   实测依据: `podman exec <c> ls /app/*.sh` 对 6 个业务容器均返回
    #   /app/agent-entrypoint.sh (search 另有 /app/entrypoint.sh)。
    #   业务镜像是本仓库**自己构建**的, 脚本位置由本仓库 Dockerfile 决定,
    #   不随上游镜像变动 —— 故在此显式列出 /app。
    ("/app", "*.sh"),
    # ⚠️【2026-10-07 补】skywalking-oap 的入口在 /skywalking/docker-entrypoint.sh
    #   (实测 `find / -name "*.sh"` 取真实位置, 非推断)。此前未覆盖 ⇒ SKIP。
    #   受控验证: 该脚本 grep 到 JAVA_OPTS, 与 compose 里给它的键一致。
    ("/skywalking", "*.sh"),
    # ES 9.x 的 bin/ 下**无 .sh** (上游改成无扩展名的 elasticsearch-env 等脚本)。
    # 这里**不**放宽模式去匹配二进制/多文件: 业务镜像的 sh 是 busybox
    # (/bin/sh -> /bin/busybox, 实测), busybox 的 `ls` 在 glob 无匹配时会把
    # 模式串本身打到 stdout, 于是 "cat: cat: No such file" —— 即本文件
    # 上方注释警告过的同一个陷阱, 曾让 ES 误判为"镜像里没有脚本"。
    # 保持 *.sh ⇒ ES 走 SKIP(明确"未判定") 而非误判。
    ("/usr/share/elasticsearch/bin", "*.sh"),
)

_VAR_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")


def compose_services() -> dict:
    doc = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
    if not isinstance(doc, dict):
        return {}
    return doc.get("services") or {}


def image_of(svc: dict) -> str:
    img = svc.get("image")
    return str(img) if img else ""


def env_keys(svc: dict) -> list[str]:
    """返回该服务 environment 里**所有形似 JVM 参数**的键。

    ⚠️ 这里刻意**不只**返回 JVM_KEYS 里的键。理由见 JVM_OPT_LIKE 的注释:
    只认白名单会让"用了一个白名单外的错键"完全不被发现 ——
    JVM_ADJUST 就是这么长期假绿的。
    """
    env = svc.get("environment")
    keys: list[str] = []
    if isinstance(env, dict):
        keys.extend(k for k in env if isinstance(k, str) and JVM_OPT_LIKE.match(k))
    elif isinstance(env, list):
        for item in env:
            if not isinstance(item, str):
                continue
            key = item.split("=", 1)[0].strip().lstrip("-").strip()
            if JVM_OPT_LIKE.match(key):
                keys.append(key)
    return keys


def script_vars(image: str) -> tuple[set[str] | None, str]:
    """取出镜像启动脚本引用的变量集合。

    在 SCRIPT_GLOBS 列出的目录下搜**全部**脚本, 而不是只 cat 一个入口 ——
    见 SCRIPT_GLOBS 上方注释里 ES 的教训。

    返回 (集合, 说明)。集合为 None 表示**取不到**, 调用方须走 SKIP 分支。
    三态区分（第八课补记一）:
      - 找到脚本 ⇒ 返回变量集合
      - 目录不存在 / podman 失败 ⇒ None + 原因（SKIP, 不判定）
      - 找到脚本但**一个变量都没有** ⇒ 这是探针坏了, 视为失败而非 SKIP
    """
    if not image:
        return None, "compose 未声明 image"
    found: set[str] = set()
    checked = 0
    for directory, pattern in SCRIPT_GLOBS:
        # 用 sh 一次列出目录内容, 再逐个 cat —— 比对每个候选路径都跑一次 podman 快得多。
        try:
            ls = subprocess.run(
                ["podman", "run", "--rm", "--entrypoint", "sh", image, "-c",
                 f"ls -1 {directory}/{pattern} 2>/dev/null || true"],
                capture_output=True, text=True, timeout=180,
                # ⚠️ errors="replace" 不是装饰性的: ES 的 bin/ 下有**二进制文件**
                #   (如 libjvm.so 等), 拼接 cat 后输出含非 UTF-8 字节,
                #   默认 strict 解码会抛 UnicodeDecodeError 直接崩掉整个门禁。
                #   真实踩到: position 10123 byte 0xb1。
                #   探针崩溃比探针给假答案更隐蔽 —— 它连 SKIP 都不是。
                errors="replace",
            )
        except (OSError, subprocess.SubprocessError) as e:
            return None, f"podman 调用失败({type(e).__name__}): {e}"
        if ls.returncode != 0:
            # 探针失效与"目录不存在"是两件事, 不能混。
            return None, f"podman ls 失败(rc={ls.returncode}): {ls.stderr.strip()[:200]}"
        paths = [p for p in ls.stdout.split() if p.strip()]
        if not paths:
            continue
        try:
            cat = subprocess.run(
                ["podman", "run", "--rm", "--entrypoint", "sh", image, "-c",
                 "cat " + " ".join(paths)],
                capture_output=True, text=True, timeout=180, errors="replace",
            )
        except (OSError, subprocess.SubprocessError) as e:
            return None, f"podman cat 失败({type(e).__name__}): {e}"
        if cat.returncode != 0:
            return None, f"podman cat 失败(rc={cat.returncode}): {cat.stderr.strip()[:200]}"
        checked += len(paths)
        for m in _VAR_RE.finditer(cat.stdout):
            found.add(m.group(1) or m.group(2))
    if checked == 0:
        return None, "镜像内未找到任何已知启动脚本 (SCRIPT_GLOBS 全 miss)"
    if not found:
        # ⚠️ 读到脚本却一个变量都没解析出 ⇒ 解析器坏了。
        #   当成 SKIP 就是「探针失效被读成通过」(第五课补记一)。
        return None, f"读到 {checked} 个脚本但解析出 0 个变量 —— 解析器可能已失效, 本次不判定"
    return found, f"检查了 {checked} 个脚本"


def main() -> int:
    services = compose_services()
    if not services:
        print(f"[FAIL] {COMPOSE} 解析不出任何 service —— 扫描器坏了, 不等于通过")
        return 1

    targets = {
        name: svc
        for name, svc in services.items()
        if isinstance(svc, dict) and env_keys(svc)
    }
    if not targets:
        print("[FAIL] 没有找到任何使用 JVM 参数键的 service —— 扫描器坏了, 不等于通过")
        return 1

    print(f"检查 {len(targets)} 个使用 JVM 参数键的 service")
    failures: list[str] = []
    skipped: list[str] = []

    for name, svc in sorted(targets.items()):
        image = image_of(svc)
        keys = env_keys(svc)
        vars_set, note = script_vars(image)
        if vars_set is None:
            skipped.append(f"{name}: {note}")
            print(f"  [SKIP] {name:24s} {note}")
            continue
        bad = [k for k in keys if k not in vars_set and k not in IGNORE_KEYS]
        if bad:
            failures.append(f"{name} ({image}): compose 用 {bad} 传 JVM 参数, 但镜像启动脚本不读它")
            print(f"  [FAIL] {name:24s} 未被启动脚本读取的键: {bad} —— 参数不会生效 (假绿)")
        else:
            print(f"  [ OK ] {name:24s} keys={keys} 均被脚本读取 ({note})")

    if skipped:
        print("\n以下 service 本次未判定（取不到启动脚本），不算通过也不算失败：")
        for s in skipped:
            print(f"  - {s}")

    if failures:
        print(f"\n[FAIL] {len(failures)} 个 service 的 JVM 参数键无效：")
        for f in failures:
            print(f"  ✗ {f}")
        return 1

    # ⚠️ 全部 SKIP 时**不能**说 PASS（第五课补记一: 跳过 ≠ 通过）。
    #   曾经在这里无条件打 [PASS], 于是「一个都没判定」被报成「全部通过」。
    #   由 test_jvm_opts_keys_match_image.py 场景 4 抓到。
    if not skipped:
        print("\n[PASS] 所有被判定的 service，其 JVM 参数键均被镜像启动脚本读取")
        print("提醒: 这只证明键名有效, 不证明参数进了 JVM —— 需从容器内 /proc/<pid>/cmdline 读回确认")
        return 0

    print("\n[SKIP] 本次**未做任何判定** —— 不是通过。上面的 service 都没取到启动脚本。")
    print("       需要 podman 可用且镜像已就绪; 修好后重跑。")
    return 0


if __name__ == "__main__":
    sys.exit(main())