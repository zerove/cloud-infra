#!/usr/bin/env python3
"""守护: 每个 JVM 都必须显式限制 native 内存(Metaspace / CodeCache / DirectMemory)。

## 为什么必须有这道门禁（2026-10-05 实测）

宿主机 `vm.overcommit_memory = 2`(严格模式), `CommitLimit = 20.63 GiB` 由全机共享。
6 个业务服务 + 3 个基础设施 JVM 共 **9 个** Java 进程, 而**未设上限的 JVM 默认**:

    Compressed class space mapped: reserved size 1073741824   ← 1 GB
    Metaspace    used 166045K, committed 167296K, reserved 1179648K   ← 1.15 GB
    MaxMetaspaceSize: unlimited

即每个 JVM 光是**预留**就约 2.15 GB。9 个 JVM 合计 ≈ 19.35 GB,
而全局预算只有 20.63 GB —— 顶到 99.7% 只是时间问题。

实测崩溃现场（`hs_err_pid1.log` + 容器日志）:

    Committed_AS / CommitLimit = 99.6% ~ 99.7%
    # Native memory allocation (malloc) failed to allocate 112 bytes.
    #   Error detail: AllocateHeap
    六个服务在**同一秒**(05:11:47~50)全部 exit=1, oom=false

`oom=false` 是关键: cgroup 从未 OOM, 所以**调 mem_limit 治不了**。
内核是在 commit 层拒绝的, 与容器的 mem_limit 无关。

## 判据

compose 里每一处 JVM 参数(`JAVA_OPTS` / `ES_JAVA_OPTS`)都必须含
`-XX:MaxMetaspaceSize`。只查这一个就够 —— 它是最大的无界预留项,
且其余两项(CodeCache / CompressedClassSpace)同批加入。

负向对照: 删掉任一服务的 `MaxMetaspaceSize`, 本门禁必须报红。
"""

from __future__ import annotations

import sys

import yaml

COMPOSE = "deploy/compose.yaml"

# 这两个键都可能承载 JVM 参数: 映射形式(environment:)与列表形式(- KEY=VAL)。
#
# ⚠️【2026-10-05 变量名修正】原来写的是 JVM_ADJUST, **该变量在本镜像的
#   nacos 启动脚本里根本不被读取** —— 于是本门禁一直在检查一个
#   podman/nacos 压根不看的键, 9/9 全绿却是假绿(第五课: 守护假装通过)。
#   实测证据: 容器内 `/proc/<pid>/cmdline` 只有 `-Xms1g -Xmx2g -Xmn512m`,
#   没有任何 -XX:MaxMetaspaceSize, 而 nacos 的 VmSize 实测 10.51GB。
#
#   正确变量是 **JAVA_OPT_EXT**, 由读镜像内的
#   /home/nacos/bin/docker-startup.sh 确认(该脚本引用了
#   JAVA_OPT/JAVA_OPT_EXT/JAVA_OPT_EXT_FIX/JVM_XMS/JVM_XMX/JVM_XMN/
#   JVM_MS/JVM_MMS/XX_MS/XX_MMS, 没有 JVM_ADJUST)。
#
#   教训: **变量名不能按官方文档猜, 要读镜像里那个启动脚本。**
#   本条判据本身也由 test_jvm_opts_keys_match_image.py 守护 ——
#   即"配置里的键"必须与"镜像启动脚本引用的键"一致, 否则再次悄悄失效。
JVM_KEYS = ("JAVA_OPTS", "ES_JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JAVA_OPT_EXT")
REQUIRED_FLAG = "-XX:MaxMetaspaceSize"


def collect_jvm_opts(services: dict) -> dict[str, str]:
    """返回 {服务名: 该服务所有 JVM 相关参数的合并串}。

    ⚠️ 必须**按服务合并**而不是按键分别判定: nacos 同时有
    `JAVA_TOOL_OPTIONS=-Dnacos.core.auth.admin.enabled=false`(无内存参数)
    和 `JAVA_OPT_EXT=-XX:MaxMetaspaceSize=...`(有限制)。若按键分开判,
    前者会被当成"这个 JVM 没设上限"而误报, 或让实现忽略掉真正的载体。

    ⚠️ 但"合并"不能掩盖"键写错": 若某服务只在**无人读取的键**里写了参数,
    合并后照样能通过。所以另有一道负向对照(见 test_jvm_native_memory_bounds.py):
    把承载参数的那个键换成另一个不存在的键, 守护必须报红。
    """
    found: dict[str, str] = {}
    for name, svc in services.items():
        env = svc.get("environment")
        opts: list[str] = []
        if isinstance(env, dict):
            for key in JVM_KEYS:
                if key in env:
                    opts.append(f"{key}={env[key]}")
        elif isinstance(env, list):
            for item in env:
                if not isinstance(item, str):
                    continue
                for key in JVM_KEYS:
                    if item.startswith(key + "="):
                        opts.append(item)
        if opts:
            found[name] = " ".join(opts)
    return found


def main() -> int:
    with open(COMPOSE, encoding="utf-8") as handle:
        config = yaml.safe_load(handle)
    services = config.get("services", {})
    if not isinstance(services, dict):
        print(f"FAIL: {COMPOSE} 的 services 不是映射")
        return 1

    jvm = collect_jvm_opts(services)
    if not jvm:
        print(f"FAIL: {COMPOSE} 里找不到任何 JVM 参数 —— 判据失效, 不是通过")
        return 1

    missing = []
    for name in sorted(jvm):
        opts = jvm[name]
        has = REQUIRED_FLAG in opts
        print(f"  {'OK ' if has else 'BAD'} {name:<16} {opts[:76]}")
        if not has:
            missing.append(name)

    print()
    if missing:
        print(f"FAIL: {len(missing)}/{len(jvm)} 个 JVM 未设置 {REQUIRED_FLAG}:")
        for name in missing:
            print(f"      {name}")
        print()
        print("      未设上限时 JVM 默认预留 CompressedClassSpace 1GB + Metaspace 1.15GB,")
        print("      而 vm.overcommit_memory=2 下**预留即计入全局 commit 预算**。")
        print("      实测 9 个 JVM 会把 Committed_AS 推到 99.7%, 内核随即拒绝新 commit,")
        print("      表现为 `malloc failed to allocate 112 bytes / AllocateHeap`,")
        print("      且 cgroup 的 oom 计数**全为 0** —— 所以调 mem_limit 无效。")
        return 1

    print(f"PASS: {len(jvm)} 个 JVM 全部设置了 {REQUIRED_FLAG}")
    return 0


if __name__ == "__main__":
    sys.exit(main())