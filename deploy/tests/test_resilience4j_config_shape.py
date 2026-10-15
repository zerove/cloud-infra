#!/usr/bin/env python3
"""守护: resilience4j 阈值必须写在可绑定的层级上 (configs.default / instances.<name>)。

## 这个守护拦住的是什么

`resilience4j-spring-boot3` 的属性类
`io.github.resilience4j.common.circuitbreaker.configuration.CommonCircuitBreakerConfigurationProperties`
只有三个 Map 字段 —— `instances` / `backends` / `configs`。
**它不存在任何顶层阈值字段**（无 `failureRateThreshold` / `minimumNumberOfCalls` /
`waitDurationInOpenState` ...）。

因此把阈值**平铺**写在 `resilience4j.circuitbreaker:` 之下时:

    resilience4j:
      circuitbreaker:
        minimum-number-of-calls: 10      # <-- 无法绑定, 被 Spring 静默忽略
        wait-duration-in-open-state-ms: 30000

Spring Boot 的宽松绑定对未知键默认不报错, 于是**配置看起来生效, 实际跑的是库默认值**:

| 参数 | 写的值 | 实际生效(默认值) |
| :--- | :--- | :--- |
| minimum-number-of-calls | 10 | 100 |
| wait-duration-in-open-state | 30s | 60s |
| slow-call-duration-threshold | 2s | 60s |
| slow-call-rate-threshold | 80% | 100% |

这与本仓库记录的「能力存在 != 能力在运行」是同一类缺陷, 且**没有任何报错**。
`gateway.yaml` 此前整块都是平铺写法, 即 100% 未生效。

## 判定方式

结构判定, 不依赖运行时: 平铺层下出现阈值键即失败。

正确写法只有两种, 二者都接受:
  resilience4j.<facet>.configs.default.<threshold>      # 默认值
  resilience4j.<facet>.instances.<name>.<threshold>     # 具名实例
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CONFIG_DIR = REPO_ROOT / "deploy" / "config"

# resilience4j 四个 facet 各自的阈值键 -> (yaml 键, 单位说明)
THRESHOLD_KEYS = {
    "circuitbreaker": {
        "failure-rate-threshold",
        "slow-call-rate-threshold",
        "slow-call-duration-threshold",
        "slow-call-duration-threshold-ms",
        "sliding-window-count-based",
        "sliding-window-size",
        "minimum-number-of-calls",
        "permitted-calls-in-half-open",
        "permitted-calls-in-half-open-state",
        "wait-duration-in-open-state-ms",
        "wait-duration-in-open-state",
        "record-exceptions",
    },
    "ratelimiter": {
        "limit-refresh-period-ms",
        "limit-refresh-period",
        "limit-for-period",
        "timeout-duration-ms",
        "timeout-duration",
    },
    "retry": {
        "max-attempts",
        "wait-duration-ms",
        "wait-duration",
        "exponential-backoff",
        "exponential-multiplier",
        "retry-exceptions",
        "ignore-exceptions",
    },
    "bulkhead": {
        "max-concurrent-calls",
        "max-wait-duration-ms",
        "max-wait-duration",
    },
}

# resilience4j 属性类真实支持的容器键
CONTAINER_KEYS = {"configs", "instances", "backends"}

# 这些键在任何层级都是合法的(标注是否为 facet 自身的直接属性)
FACET_OWN_KEYS = {
    "circuitbreaker": {"circuit-breaker-aspect-order"},
    "ratelimiter": set(),
    "retry": set(),
    "bulkhead": set(),
}


def _indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def scan_flat_thresholds(path: Path) -> list[str]:
    """返回该文件中"平铺在 facet 之下"的阈值键列表 (含行号)。"""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []

    findings: list[str] = []
    in_resilience = False
    resilience_indent = 0
    facet: str | None = None
    facet_indent = 0

    for lineno, raw in enumerate(lines, start=1):
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue

        indent = _indent_of(raw)

        if stripped == "resilience4j:":
            in_resilience = True
            resilience_indent = indent
            facet = None
            continue

        if not in_resilience:
            continue

        # 离开 resilience4j 子树 -> 顶层(缩进 <= resilience4j)的新键
        if indent <= resilience_indent and not stripped.startswith("-"):
            in_resilience = False
            facet = None
            continue

        # facet 层: `  circuitbreaker:`
        if indent == resilience_indent + 2 and stripped.endswith(":") and ":" in stripped:
            candidate = stripped[:-1].strip()
            if candidate in THRESHOLD_KEYS:
                facet = candidate
                facet_indent = indent
                continue

        if facet is None:
            continue

        # 直接挂在 facet 下的 `<threshold>: <value>`
        if indent == facet_indent + 2 and not stripped.startswith("-"):
            key = stripped.split(":", 1)[0].strip()
            if key in CONTAINER_KEYS:
                continue  # 合法的容器层, 其下内容不再判定
            if key in FACET_OWN_KEYS.get(facet, set()):
                continue
            if key in THRESHOLD_KEYS[facet]:
                findings.append(f"{path.relative_to(REPO_ROOT)}:{lineno}: "
                                f"resilience4j.{facet}.{key} 平铺在 facet 之下, 不会被绑定")

    return findings


def main() -> int:
    if not CONFIG_DIR.is_dir():
        print(f"FAIL: 配置目录不存在: {CONFIG_DIR}")
        return 1

    yamls = sorted(CONFIG_DIR.glob("*.yaml"))
    failures: list[str] = []
    scanned = 0

    for path in yamls:
        text = path.read_text(encoding="utf-8")
        if "resilience4j:" not in text:
            continue
        scanned += 1
        failures.extend(scan_flat_thresholds(path))

    if failures:
        print("FAIL:")
        for f in failures:
            print(f"  - {f}")
        print(f"\n共 {len(failures)} 处。阈值必须嵌在 configs.default 或 instances.<name> 之下;")
        print("平铺键不会被绑定, 会静默回落到库默认值。")
        return 1

    print(f"PASS resilience4j 配置结构守护: {scanned} 个含 resilience4j 的配置, "
          f"无平铺阈值键")
    return 0


if __name__ == "__main__":
    sys.exit(main())