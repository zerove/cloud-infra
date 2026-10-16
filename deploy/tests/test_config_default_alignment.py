#!/usr/bin/env python3
"""守护: 跨服务的同名配置项必须只有一个默认值。

## 拦住的是什么

同一份 Nacos 配置模板被 5 个服务共用, 但同名键的**默认值**在各文件间不一致。
这类漂移的特征是"两边各自都能跑", 只有在**环境变量没设**时才分叉:

| 键 | 曾经的分裂 |
| :--- | :--- |
| `spring.datasource.url` | auth 默认 `cloud-postgres`,file/notification/system 默认 `postgres` |
| `hikari.maximum-pool-size` | notification 写死 10 且不可覆盖,其余 `${HIKARI_MAX_POOL_SIZE:20}` |
| `openfeign...readTimeout` | 全为 60000(60s),远超请求路径可接受的阻塞时长 |

分叉时的实际后果: 漏设 `SPRING_DATASOURCE_HOST` 会让 auth 连到与另外三个
**不同的主机**, 而本地与 CI 各自自洽, 不会报错。

## 判定方式

把每个受管键的全部出现位置收集成 (文件 -> 默认值) 映射, 默认值集合 >1 即失败。
不比较键是否存在, 只比较**同一键的默认值是否一致**。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEPLOY_CONFIG = REPO_ROOT / "deploy" / "config"

# 逻辑键 -> (正则, 默认值所在的捕获组)
# 形如 ${VAR:default} 时 default 为 group(1)；形如字面量时 default 为 group(1)
MANAGED: dict[str, tuple[str, int]] = {
    "datasource-host": (r"\$\{SPRING_DATASOURCE_HOST:([^}]+)\}", 1),
    "feign-read-timeout": (r"\$\{FEIGN_READ_TIMEOUT_MS:(\d+)\}", 1),
    "feign-connect-timeout": (r"\$\{FEIGN_CONNECT_TIMEOUT_MS:(\d+)\}", 1),
    "hikari-max-pool": (r"\$\{HIKARI_MAX_POOL_SIZE:(\d+)\}", 1),
}


def collect() -> dict[str, dict[str, str]]:
    found: dict[str, dict[str, str]] = {k: {} for k in MANAGED}
    for path in sorted(DEPLOY_CONFIG.glob("*.yaml")):
        text = path.read_text(encoding="utf-8")
        for key, (pattern, group) in MANAGED.items():
            for match in re.finditer(pattern, text):
                line_no = text[: match.start()].count("\n") + 1
                found[key][f"{path.relative_to(REPO_ROOT)}:{line_no}"] = match.group(group)
    return found


def main() -> int:
    if not DEPLOY_CONFIG.is_dir():
        print(f"FAIL: 配置目录不存在: {DEPLOY_CONFIG}")
        return 1

    found = collect()
    failures: list[str] = []

    for key, sites in found.items():
        if not sites:
            continue
        distinct = set(sites.values())
        if len(distinct) > 1:
            detail = ", ".join(f"{site}={value}" for site, value in sorted(sites.items()))
            failures.append(f"{key} 默认值分裂: {detail}")

    if failures:
        print("FAIL:")
        for f in failures:
            print(f"  - {f}")
        print("\n同名配置项必须共用同一个默认值, 否则漏设环境变量时各服务行为不一致。")
        return 1

    checked = sum(len(v) for v in found.values())
    print(f"PASS 跨服务配置默认值守护: {checked} 处受管键默认值一致 "
          f"({', '.join(sorted(found))})")
    return 0


if __name__ == "__main__":
    sys.exit(main())