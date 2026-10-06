#!/usr/bin/env python3
"""守护: 业务服务的 healthcheck start_period 必须覆盖实测启动耗时。

## 缺陷机制（2026-10-06 实测）

Docker/podman 的 healthcheck 语义:

    start_period 期间, 探测失败**不计入** retries;
    start_period 一结束, 连续失败 retries 次即锁定 `unhealthy`,
    且**不会**因为应用随后就绪而回 healthy。

于是当 `start_period + interval * retries < 实际启动耗时` 时, 会出现:

    容器一直 Up, 应用最终完全可用, 但 health 永远是 unhealthy。

2026-10-06 实测 6 个业务服务, **5 个**踩中（只有 system 配了 240s）:

    服务          实测启动      start_period   判红耗时   判定
    gateway       128.1s        20s            45s        覆盖不住
    auth          147.5s        20s            45s        覆盖不住
    file          108.5s        20s            45s        覆盖不住
    search        255.7s        20s            45s        覆盖不住
    notification  304.3s        20s            45s        覆盖不住
    system        223.3s        240s           300s       覆盖

危害不止于"状态显示难看": `verify-e2e.sh` 第 1197 行按容器名逐个判
`healthy`, 它的等待预算只有 600s —— 恰好落在最慢服务的启动区间内,
会把"服务正常但健康判定超时"报成 E2E 失败。

## 本脚本的判据

对每个业务服务, 要求:

    start_period >= 该服务的实测启动耗时 / STARTUP_MEASURED[service]

即"启动期至少要等于实测值", 不要求余量 —— 余量是运维决策, 不是正确性。

实测值来源: 运行中容器日志的 `Started ...Application in N seconds`。
**该值随宿主负载显著波动**（实测 notification 两次相差 2.3 倍: 130.9s / 304.3s）,
故常量取最坏观测值。重新测量:

    podman logs <容器> | grep -oE 'Started .* in [0-9.]+ seconds'

注意此时容器必须已就绪 —— 若它的start_period 覆盖不住实测启动,
health 会先被判 unhealthy, 但**服务仍会最终启动**, 日志仍可读。

## 为什么是白名单式的显式清单

`deploy/compose.yaml` 里还有 20+ 个容器（nacos/postgres/redis/oap...）,
它们的启动特性与业务服务不同（基础设施镜像有自己的就绪语义）。
本守护只覆盖 `BUSINESS_SERVICES` 列出的 6 个 —— 正确的一侧可枚举,
而"哪些服务启动慢"会随部署变化, 所以显式列出并在缺失时报错。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("SKIP: 需要 PyYAML 才能解析 compose.yaml")
    sys.exit(0)

#: 被检 compose 文件。允许用环境变量覆盖 —— 守护自身要能被测试驱动,
#: 而测试不该去改生产文件（AGENTS.md 第八课补记一第 5 条）。
COMPOSE = Path(os.environ.get("COMPOSE_FILE") or (Path(__file__).resolve().parent.parent / "compose.yaml"))

#: 需要守护的业务服务（容器名去掉 cloud- 前缀）
BUSINESS_SERVICES = ("gateway", "auth", "system", "file", "notification", "search")

#: 2026-10-06 从运行中容器日志读回的实测启动耗时（秒）
#:
#: ⚠️ 这是**单次采样**, 不是稳定值。实测波动很大: notification 两次重建
#:    分别为 304.3s（当时宿主 commit ~99%, load 19+）与 130.9s（负载较低）,
#:    相差 2.3 倍。因此常量取**观测到的最坏值**, 配置也按最坏值留余量——
#:    少配了会误判, 多配只是让健康判定晚一点开始, 方向上没有代价。
#:
#:    换机器 / 换 JDK / 宿主负载特征变化后应重新测量并更新这里。
STARTUP_MEASURED = {
    "gateway": 128.1,
    "auth": 147.5,
    "system": 223.3,
    "file": 108.5,
    "notification": 304.3,
    "search": 255.7,
}


def parse_duration(value: object) -> float | None:
    """把 '180s' / '3m' / 数字 解析成秒。无法解析返回 None。"""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip()
    if not text:
        return None
    units = {"s": 1.0, "m": 60.0, "h": 3600.0}
    try:
        if text[-1] in units:
            return float(text[:-1]) * units[text[-1]]
        return float(text)
    except ValueError:
        return None


def main() -> int:
    if not COMPOSE.is_file():
        print(f"FAIL: 找不到 {COMPOSE}")
        return 1

    # 先取一份健康样本: 解析器自身失效必须判红, 不能静默放行
    # （AGENTS.md 第五课补记一: 扫到 0 个目标必须判红）
    doc = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
    if not isinstance(doc, dict) or "services" not in doc:
        print("FAIL: compose.yaml 解析失败或没有 services 段 —— 探针坏了, 不等于配置通过")
        return 1

    services = doc["services"]
    missing = [s for s in BUSINESS_SERVICES if s not in services]
    if missing:
        print(f"FAIL: compose.yaml 缺少业务服务定义: {missing}")
        return 1

    failures: list[str] = []
    for name in BUSINESS_SERVICES:
        hc = services[name].get("healthcheck") or {}
        if not hc:
            failures.append(f"{name}: 没有 healthcheck 段")
            continue

        start_period = parse_duration(hc.get("start_period"))
        if start_period is None:
            failures.append(f"{name}: start_period 缺失或无法解析 (值={hc.get('start_period')!r})")
            continue

        measured = STARTUP_MEASURED[name]
        if start_period < measured:
            failures.append(
                f"{name}: start_period={start_period:.0f}s < 实测启动 {measured}s "
                f"(差 {measured - start_period:.0f}s)。"
                f"服务会在就绪前被锁定 unhealthy, 且不会自动回 healthy。"
            )
        else:
            print(f"  OK  {name:14s} start_period={start_period:.0f}s >= 实测 {measured}s")

    if failures:
        print("")
        for f in failures:
            print(f"FAIL: {f}")
        print("")
        print("修复: 把 start_period 调到 >= 实测启动耗时 (可留余量), 并在 compose 里注明依据。")
        print("重新测量: podman logs <容器> | grep -oE 'Started .* in [0-9.]+ seconds'")
        return 1

    print(f"PASS: {len(BUSINESS_SERVICES)} 个业务服务的 start_period 均覆盖实测启动耗时")
    return 0


if __name__ == "__main__":
    sys.exit(main())
