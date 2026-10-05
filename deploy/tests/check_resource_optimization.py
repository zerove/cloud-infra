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

# 服务名 -> (mem_limit 字符串, 实测依据)
EXPECTED_LIMITS = {
    "nacos": ("3g", "实测 JVM RSS 1570MB; 堆下界 1g 由 Derby 建库超时钉住"),
    "system": ("2g", "768m 有已证实的 Caffeine ClassLoader 死锁, 2g 是修复值"),
}


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
