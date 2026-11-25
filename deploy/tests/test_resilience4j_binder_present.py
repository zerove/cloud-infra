#!/usr/bin/env python3
"""断言声明了 `resilience4j:` 配置的服务, 其模块 classpath 上确实有 binder。

## 为什么需要这条

`test_resilience4j_config_shape.py` 只校验 YAML **结构**。但结构正确不等于生效:
没有 `resilience4j-spring-boot4` 就没有 `@ConfigurationProperties`, 整段配置
**从不被读取**, 且不报任何错。

issue #73 即此类: `deploy/config/gateway.yaml` 有一段完整的 `resilience4j:`
(circuitbreaker / ratelimiter / retry / bulkhead), 而 gateway 的 classpath 上
只有 `resilience4j-annotations` —— 不含 binder。

## 判据

对每个含 `resilience4j:` 的配置:
  1. 定位它属于哪个服务(经 deploy/compose.yaml 的服务名 -> 模块路径);
  2. `mvn dependency:tree -Dincludes=io.github.resilience4j` 看是否有
     `resilience4j-spring-boot*`;
  3. 没有则报错。

## 退出码

0 = 全部满足; 1 = 存在"配了却无法绑定"的配置。
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BINDER = re.compile(r"resilience4j-spring-boot\d+")
RESILIENCE_BLOCK = re.compile(r"^resilience4j\s*:", re.MULTILINE)

# 服务名 -> Maven 模块路径。与 deploy/compose.yaml 中的服务保持一致。
SERVICE_MODULE = {
    "gateway": "cloud-infra-gateway",
    "system-module": "cloud-infra-modules/system-module",
    "file-service": "cloud-infra-modules/file-service",
    "notification-service": "cloud-infra-modules/notification-service",
    "auth": "cloud-infra-auth",
    "search-service": "cloud-infra-modules/search-service",
}

CONFIG_NAME = {
    "gateway": "gateway.yaml",
    "system-module": "system-module.yaml",
}


def configs_with_resilience4j():
    for name, filename in CONFIG_NAME.items():
        path = ROOT / "deploy" / "config" / filename
        if path.is_file() and RESILIENCE_BLOCK.search(path.read_text(encoding="utf-8")):
            yield name, path


def has_binder(module: str) -> bool:
    proc = subprocess.run(
        ["mvn", "-o", "dependency:tree", "-pl", module,
         "-Dincludes=io.github.resilience4j"],
        cwd=ROOT, capture_output=True, text=True, timeout=600,
    )
    return bool(BINDER.search(proc.stdout + proc.stderr))


def main() -> int:
    offenders = []
    checked = 0
    for service, path in configs_with_resilience4j():
        module = SERVICE_MODULE.get(service)
        if module is None:
            offenders.append(f"{path.relative_to(ROOT)}: 未知服务 {service}, 无法定位模块")
            continue
        checked += 1
        if not has_binder(module):
            offenders.append(
                f"{path.relative_to(ROOT)}: 声明了 resilience4j: 配置, "
                f"但 {module} 的 classpath 上没有 resilience4j-spring-boot* "
                f"(issue #73: 该配置从不被绑定, 且不报错)"
            )

    if offenders:
        print("以下配置「看起来生效、实则从未绑定」：", file=sys.stderr)
        for item in offenders:
            print(f"  ✗ {item}", file=sys.stderr)
        print(
            "\n修复二选一:\n"
            "  a) 该服务确实需要熔断/重试 -> 在对应模块 pom 引入 "
            "resilience4j-spring-boot4\n"
            "  b) 不需要 -> 删除该 yaml 里的 resilience4j 段, 避免误导运维",
            file=sys.stderr,
        )
        return 1

    print(f"✓ {checked} 份含 resilience4j 配置的服务均有 binder ({checked} checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())