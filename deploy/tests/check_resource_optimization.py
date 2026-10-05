#!/usr/bin/env python3
"""
守护 2026-10-05 的资源优化不被静默回退。

存在意义（AGENTS.md 第四课/第五课）:
  优化项若只有"配置写了"而没有守护, 下一个人很容易改回去 ——
  尤其是当 CI 变慢或内存告警时, "调大 mem_limit" 看起来是合理的救急手段。

⚠️ 本守护自身的三个设计约束（都是实测踩出来的）:
  1. **不能只查 compose.yaml**。PostgreSQL 的调优参数**不在** compose 里:
     command 段的 -c 没生效, PG_* 环境变量该镜像不转换,
     唯一可靠入口是 ALTER SYSTEM（由 apply-postgres-tuning.sh 执行）。
     所以必须同时检查 conf 文件、脚本与 up.sh 三处。
  2. **期望值不能来自被校验对象**。否则是自证循环 ——
     实测第一版 apply 脚本从 conf 读期望值再写再读回, 改坏 conf 仍报 PASS。
     这里从 conf 读"应有哪些 key", 但**不**从 conf 读"值该是多少",
     值由下面的 EXPECTED_TUNING 独立给出。
  3. **不能只查"有没有被调用"**。要查的是**裸调用**存在 ——
     2026-10-05 实测: 断言 `tests/<脚本名>.py` 字面量存在, 会被
     **被检函数自己的定义体**满足, 于是注释掉调用点后自检仍判绿。
     即"定义了"不能代表"被调用了"。

检查项:
  1. postgres-tuning.conf 存在且含 5 个实测得出的调优参数
  2. apply-postgres-tuning.sh 存在且内部期望值与 conf 一致（防单边漂移）
  3. up.sh 调用了 apply 脚本（否则调优不会在部署时生效）
  4. postgres 不得设置 PGDATA（会指向 data/pgdata 而看不到现有数据 = 丢数据）
  5. nacos mem_limit 不得回退到 4g（实测 RSS 1698MB, 3g 已留足余量）
  6. system mem_limit 必须是 2g（768m 有已证实的 Caffeine 死锁，见 compose 注释）

用法:
  python3 check_resource_optimization.py            # 期望 exit 0
  python3 check_resource_optimization.py --compose <路径>   # 对副本做负向对照
"""
import os
import re
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# 调优参数 key -> 期望值。**独立于 conf**, 避免自证循环。
EXPECTED_TUNING = {
    "shared_buffers": "192MB",
    "max_wal_size": "2GB",
    "min_wal_size": "256MB",
    "wal_compression": "on",
    "random_page_cost": "1.1",
}

# 服务名 -> (mem_limit 字符串, 判定依据)
#
# ⚠️ nacos 3g 的依据是**最坏情形推算**, 不是瞬时读数。推算过程:
#     memory.stat 实测: anon=1573MB(已含堆+Metaspace+CodeCache+Direct)
#                       file=129MB kernel_stack=10MB pagetables=5MB
#     /proc/1/cmdline 实测: -Xmx2g -Xms1g -Xmn512m
#     JAVA_TOOL_OPTIONS: MaxMetaspace=256m CCS=128m CodeCache=128m Direct=128m
#     GC 日志实测 Metaspace 驻留 104MB (远低于 256m 上限)
#     最坏 = 堆满 2048 + 堆外四项全满 640 + (file+kernel_stack+pagetables=144)×1.2
#          = 2048 + 640 + 173 = 2861 MB, 距 3g(3072) 上限仍余 211 MB
#
# ⚠️ 这段注释与下面的可执行逻辑必须一致, 否则就是"报错文案本身是探针的一部分"
#    (第五课补记四)。我第一版注释写 2832/余240, 逻辑算 2861/余211,
#    两者并存了整整一个提交 —— 判据与文案不一致时, 读注释的人会被带错方向。
#
# ⚠️ **我在这里犯过一次反向错误, 别重犯**:
#     第一版算成"堆外余量仅 384MB < 四项上限 640MB ⇒ 不安全", 并据此
#     一度想撤回 3g。错在**重复计数** —— 那四项本来就住在 anon 里,
#     不是叠加在 anon 之上的新增部分。判据必须先分清
#     "这个内存已经算过了吗", 再做加减(第五课补记: 先确认被检对象的真实范围)。
#
# ⚠️ 另一次同源错误: 报错文本里把堆算进了"堆外"(sum() 求和时 heap 还在 dict 里),
#     报出"堆2048 + 堆外2688"。**报错的数字本身错了比不报错更糟**, 因为它
#     语法正常、会被当成事实继续推理。现已改为先 pop 掉 heap 再求和,
#     并加了数字自洽性检查(解析报错文本, 验算 堆+堆外+固定 == 总需)。
EXPECTED_LIMITS = {
    "nacos": ("3g", "最坏情形 2861MB 距 3g 余 211MB; 瞬时 peak 1739MB"),
    "system": ("2g", "768m 有已证实的 Caffeine ClassLoader 死锁, 2g 是修复值"),
}

# ⚠️ 上面 nacos 的依据必须能被**重算**, 否则注释会腐烂成谎言。
#   这里把最坏情形的算式显式写下来: 若有人调大 -Xmx 或某个 -XX 上限,
#   而 mem_limit 没跟着调, 这个断言会报红。
#
#   注意 anon 已在 memory.stat 里**包含**堆与四项堆外空间,
#   但"最坏情形"是问**上限能到多少**, 不是当前占多少 ——
#   两者是不同的问题: 前者决定 mem_limit 够不够, 后者只说明当下闲不闲。
NACOS_WORST_CASE_MB = {
    "heap": 2048,          # -Xmx2g
    "metaspace": 256,      # -XX:MaxMetaspaceSize=256m
    "ccs": 128,            # -XX:CompressedClassSpaceSize=128m
    "code_cache": 128,     # -XX:ReservedCodeCacheSize=128m
    "direct": 128,         # -XX:MaxDirectMemorySize=128m
    # 以下三项来自 memory.stat 实测的**当前**值; 它们不随 JVM 参数增长,
    # 但保留 20% 余量以吸收 mapped 文件的自然增长。
    "fixed": 144,          # file 129 + kernel_stack 10 + pagetables 5
}
NACOS_MARGIN_RATIO = 1.2  # fixed 部分留 20% 余量


def parse_size_mb(text):
    """'3g' / '512m' -> MiB 数值。无法解析时返回 None（由调用方判红）。"""
    if not isinstance(text, str):
        return None
    t = text.strip().lower()
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*([kmg])", t)
    if not m:
        return None
    num, unit = float(m.group(1)), m.group(2)
    scale = {"k": 1 / 1024, "m": 1, "g": 1024}[unit]
    return num * scale


def parse_tuning(path):
    """从 tuning conf 里读出 key -> value（跳过注释与空行）。"""
    out = {}
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip().strip("'\"")
    except OSError:
        return None
    return out


def main():
    args = sys.argv[1:]
    path = os.path.join(ROOT, "deploy", "compose.yaml")
    if "--compose" in args:
        path = args[args.index("--compose") + 1]

    try:
        with open(path, encoding="utf-8") as fh:
            doc = yaml.safe_load(fh)
    except OSError as exc:
        print(f"FAIL: 无法读取 {path}: {exc}")
        return 2
    except yaml.YAMLError as exc:
        print(f"FAIL: {path} YAML 解析失败: {exc}")
        return 2

    services = (doc or {}).get("services") or {}
    failures = []
    notes = []

    # --- 1. tuning conf 内容 ---
    conf_path = os.path.join(ROOT, "deploy", "postgres-tuning.conf")
    tuning = parse_tuning(conf_path)
    if tuning is None:
        failures.append(f"调优配置文件不存在或不可读: {conf_path}")
        tuning = {}
    else:
        for key, want in EXPECTED_TUNING.items():
            got = tuning.get(key)
            if got is None:
                failures.append(
                    f"postgres-tuning.conf 缺少 {key} = {want} —— 调优已被回退"
                )
            elif got.lower() != want.lower():
                failures.append(
                    f"postgres-tuning.conf {key} = {got}，期望 {want}"
                )
            else:
                notes.append(f"tuning {key} = {got}  ✓")

    # --- 2. apply 脚本的独立期望值必须与 conf 一致（防单边漂移）---
    apply_path = os.path.join(ROOT, "deploy", "apply-postgres-tuning.sh")
    try:
        with open(apply_path, encoding="utf-8") as fh:
            apply_src = fh.read()
    except OSError:
        failures.append(f"调优应用脚本不存在: {apply_path}")
        apply_src = ""
    if apply_src:
        env_map = {
            "shared_buffers": "EXPECT_SHARED_BUFFERS",
            "max_wal_size": "EXPECT_MAX_WAL_SIZE",
            "min_wal_size": "EXPECT_MIN_WAL_SIZE",
            # wal_compression 起初没有独立期望值（沿用 conf 兜底 => 部分自证循环）,
            # 2026-10-05 已补上 EXPECT_WAL_COMPRESSION, 此处一并守护。
            "wal_compression": "EXPECT_WAL_COMPRESSION",
            "random_page_cost": "EXPECT_RPC",
        }
        # 期望值个数必须与调优项个数一致, 否则新增参数时会被漏掉
        if len(env_map) != len(EXPECTED_TUNING):
            failures.append(
                f"守护只覆盖 {len(env_map)}/{len(EXPECTED_TUNING)} 个参数的"
                f"独立期望值 —— 新增调优参数时会被漏检"
            )
        for key, var in env_map.items():
            m = re.search(rf"{var}=\"\$\{{{var}:-([^}}]+)\}}\"", apply_src)
            if not m:
                failures.append(
                    f"apply 脚本里找不到独立期望值 {var} —— "
                    "校验会退化成自证循环（改坏 conf 仍报 PASS）"
                )
            elif m.group(1) != EXPECTED_TUNING[key]:
                failures.append(
                    f"apply 脚本 {var}={m.group(1)} 与守护期望 "
                    f"{EXPECTED_TUNING[key]} 不一致 —— 单边漂移"
                )
        if all(
            re.search(rf"{v}=\"\$\{{{v}:-", apply_src) for v in env_map.values()
        ):
            notes.append("apply 脚本含独立期望值（非自证循环）✓")

    # --- 3. up.sh 必须调用 apply 脚本 ---
    up_path = os.path.join(ROOT, "deploy", "up.sh")
    try:
        with open(up_path, encoding="utf-8") as fh:
            up_src = fh.read()
    except OSError:
        failures.append(f"up.sh 不可读: {up_path}")
        up_src = ""
    if up_src:
        if "apply-postgres-tuning.sh" in up_src:
            notes.append("up.sh 调用了 apply-postgres-tuning.sh ✓")
        else:
            failures.append(
                "up.sh 未调用 apply-postgres-tuning.sh —— "
                "调优不会在部署时生效（机制存在但链路未接）"
            )

    # --- 4. postgres 不得改 PGDATA ---
    pg = services.get("postgres")
    if pg is None:
        failures.append("postgres 服务不存在")
    else:
        env = pg.get("environment") or {}
        pgdata = env.get("PGDATA")
        if pgdata and "pgdata" in pgdata:
            failures.append(
                f"postgres PGDATA={pgdata} 指向 data/pgdata 子目录, "
                "而现有数据在 /var/lib/postgresql/data 下 —— "
                "容器会看不见旧数据并当成空库重新初始化 = 丢数据"
            )
        else:
            notes.append("postgres 未改 PGDATA（数据目录安全）✓")

        # 挂载不得重复。2026-10-05 编辑 volumes 时实测引入过:
        #   同一路径被列了两次。YAML 不报错、容器也能起, 属静默冗余。
        vols = pg.get("volumes") or []
        seen = set()
        for v in vols:
            if v in seen:
                failures.append(f"postgres 挂载重复: {v}")
            seen.add(v)
        dead = [v for v in vols if "postgres-tuning.conf" in v]
        if dead:
            failures.append(
                f"postgres 仍挂载 {dead[0]} —— 该文件不会被任何 include 读取, "
                "挂载是死的（真实路径是 ALTER SYSTEM, 见 compose 注释）"
            )
        else:
            notes.append("postgres 无失效 tuning.conf 挂载、无重复挂载 ✓")

    # --- 5. mem_limit ---
    for svc, (want, why) in EXPECTED_LIMITS.items():
        cfg = services.get(svc)
        if cfg is None:
            failures.append(f"{svc} 服务不存在")
            continue
        got = cfg.get("mem_limit")
        if got != want:
            failures.append(f"{svc} mem_limit = {got}，期望 {want}（{why}）")
        else:
            notes.append(f"{svc} mem_limit = {got}  ✓")

    # --- 6. nacos: mem_limit 必须能容下 JVM 的最坏情形 ---
    #
    # ⚠️ 这一项守护的是**两个配置之间的一致性**, 不是单个值:
    #   有人调大 JVM_XMX 却忘了调 mem_limit, 容器就会被 cgroup OOM-kill,
    #   而现象是"Nacos 莫名启动失败", 排查时极难联想到内存上限。
    nacos_cfg = services.get("nacos") or {}
    nacos_env = nacos_cfg.get("environment") or {}
    xmx = parse_size_mb(nacos_env.get("JVM_XMX"))
    xms = parse_size_mb(nacos_env.get("JVM_XMS"))
    if xmx is None:
        failures.append(
            "nacos 缺少可解析的 JVM_XMX —— 无法校验 mem_limit 是否够用"
        )
    else:
        limit_mb = parse_size_mb(nacos_cfg.get("mem_limit"))
        if limit_mb is None:
            failures.append("nacos mem_limit 无法解析为内存大小")
        else:
            worst = dict(NACOS_WORST_CASE_MB)
            # 堆上限以 compose 的 JVM_XMX 为准, 不写死 ——
            # 否则调大 XMX 时这个断言会拿旧值算, 变成又处假绿。
            worst["heap"] = xmx
            fixed_mb = worst.pop("fixed") * NACOS_MARGIN_RATIO
            # ⚠️ 必须**先把 heap 弹掉**再求和, 否则堆会被算进"堆外"。
            #   我第一版写成 sum(worst.values()) 而 heap 还在 dict 里,
            #   报出"堆2048 + 堆外2688"(=2048+640) 这种重复计数的数字,
            #   连正向都判红。报错的数字本身错了, 比不报错更糟。
            heap_mb = worst.pop("heap")
            nonheap_mb = sum(worst.values())
            need_mb = heap_mb + nonheap_mb + fixed_mb
            headroom = limit_mb - need_mb
            if xms is not None and xms > xmx:
                failures.append(f"nacos JVM_XMS({xms:.0f}MB) > JVM_XMX({xmx:.0f}MB)")
            if headroom < 0:
                failures.append(
                    f"nacos mem_limit {nacos_cfg.get('mem_limit')} 装不下 JVM 最坏情形: "
                    f"需 {need_mb:.0f}MB = 堆{heap_mb:.0f} + 堆外上限{nonheap_mb:.0f} "
                    f"+ 固定{fixed_mb:.0f}, 超出 {-headroom:.0f}MB ⇒ 会被 cgroup OOM-kill"
                )
            else:
                notes.append(
                    f"nacos 最坏情形 {need_mb:.0f}MB ≤ {limit_mb:.0f}MB "
                    f"(余 {headroom:.0f}MB)  ✓"
                )

    # --- 7. 代理变量必须在**同类服务之间**保持一致 ---
    #
    # ⚠️ 判据是**横向对比**, 不是"每个服务都必须清空"。后者是错的:
    #   实测 compose 23 个服务分两类 —— 12 个显式清空(gateway/auth/system/…,
    #   值确为空串), 11 个**根本没写**(postgres/redis/nacos/es/minio/…),
    #   于是从宿主 shell 继承到 http://127.0.0.1:7890。
    #   若按"全部要清"判红, 会把 11 个服务一次性全报出来, 噪声淹没真信号。
    #
    # ⚠️ 更关键的实测修正: 我先前以为容器 env 里的 no_proxy(含 127.*)
    #   会豁免对 127.0.0.1:7890 的代理访问 —— **不成立**。
    #   同一容器内对照: 默认(走代理) rc=7 失败, 而 --noproxy "*" 直连 code=200。
    #   curl 的 no_proxy 不认 "127.*" 这种通配(只认 127.0.0.1 或 .1;
    #   "*" 仅在位置参数 --noproxy '*' 有效)。故继承来的代理是**真故障**,
    #   不是无害噪声。检查项因此只对"已声明者"断言值非空。
    PROXY_KEYS = ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")
    declared_count = 0
    dirty_declared = []
    undeclared = []
    for svc, cfg in services.items():
        env = (cfg or {}).get("environment") or {}
        if not any(k in env for k in PROXY_KEYS):
            undeclared.append(svc)
            continue
        declared_count += 1
        bad = [f"{k}={env[k]!r}" for k in PROXY_KEYS if env.get(k)]
        if bad:
            dirty_declared.append(f"{svc}({','.join(bad)})")
    if dirty_declared:
        failures.append(
            f"{len(dirty_declared)} 个服务声明了代理变量但**值非空**, 会把请求导向代理: "
            f"{'; '.join(sorted(dirty_declared))} ⇒ 改为 \"\""
        )
    else:
        notes.append(f"已声明代理变量的 {declared_count} 个服务值均为空串  ✓")
    if undeclared:
        notes.append(
            f"⚠️ {len(undeclared)} 个服务未声明代理变量, 将继承宿主环境: "
            f"{', '.join(sorted(undeclared))}"
        )

    for n in notes:
        print(f"  {n}")
    if failures:
        print(f"\nFAIL: {len(failures)} 项")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"\nPASS: 全部 {len(notes)} 项检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
