#!/usr/bin/env python3
"""守护: K8s 探针路径必须是真实可达的那个, 而不是看起来合理的那个。

## 这个守护拦住的是什么（本仓库真实发生过的）

`management.endpoints.web.base-path: /management` 会**取代** `/actuator`, 因此
actuator 健康端点的真实路径是 `/management/health/*`, 而**不是**
`/actuator/health/*`。用一次性 Spring Boot 4.1 应用实测:

    /actuator/health/liveness            -> 404
    /management/actuator/health/liveness -> 404
    /management/health/liveness          -> 200 {"status":"UP"}

而 `deploy/k8s/*/values.yaml`(6 个 chart, 18 处)与 `gateway/canary-rollout.yaml`
写的全是 `/actuator/health/*` —— 即**全部探针恒 404**, 每个 Pod 都会被判定不健康。

第二个坑: **Spring Boot 没有 `startup` 健康分组**。实测
`/management/health/startup` -> 404。因此 startupProbe 也必须指向 readiness。

## 独立佐证（不是推断）

`deploy/compose.yaml` 里 6 个服务的 healthcheck 用的就是
`http://localhost:9301/management/health/readiness`, 且**长期可用**。
即该路径是生产中已被验证可达的那个, 而 `/actuator/health/*` 从未生效过。
本守护同时校验 compose healthcheck, 使三个部署面(manifests / Helm / compose)
不会各自漂移。

## 判定方式

扫描 manifests/ 与 deploy/k8s/ 下所有 `*Probe.httpGet.path`:
1. 不得以 `/actuator` 开头(base-path 已取代它)
2. 指向 health 分组时必须是 `/management/health/{liveness,readiness}`
3. 探针端口必须是管理端口 9301
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
# 只扫 cloud-infra 自己的 Spring 服务。deploy/k8s/infra/ 是第三方组件 chart
# (Elasticsearch / Nacos / Zipkin / OTel collector), 它们用自己的原生探针路径与端口
# (如 /_cluster/health:9200), 不受 Spring 的 base-path 约束 —— 纳入扫描会全部误报。
SPRING_SERVICES = ("auth", "system", "file", "notification", "search", "gateway")
SCAN_ROOTS = [REPO_ROOT / "manifests"]
SCAN_ROOTS += [REPO_ROOT / "deploy" / "k8s" / name for name in SPRING_SERVICES]

COMPOSE_FILE = REPO_ROOT / "deploy" / "compose.yaml"

MANAGEMENT_PORT = 9301
VALID_GROUPS = {"liveness", "readiness"}
# 非 Spring 服务自身的健康端点, 不受本守护约束
FOREIGN_PREFIXES = ("/minio/", "/metrics")

PROBE_NAMES = ("startupProbe", "livenessProbe", "readinessProbe")


def probe_blocks(lines):
    """逐行扫描产出 (probe 名, 起始行号, 块内行)。

    不用正则匹配缩进块: Python re 不支持反向引用(Perl 的 \\k<name> 语法),
    且 YAML 缩进块用正则表达本就不稳。
    """
    for idx, line in enumerate(lines):
        stripped = line.strip()
        if not stripped.endswith(":") or stripped.count(":") != 1:
            continue
        name = stripped[:-1]
        if name not in PROBE_NAMES:
            continue
        indent = len(line) - len(line.lstrip())
        body = []
        for follow in lines[idx + 1:]:
            if follow.strip() and (len(follow) - len(follow.lstrip())) <= indent:
                break
            body.append(follow)
        yield name, idx + 1, body


PATH_RE = re.compile(r"^\s*path:\s*(\S+)\s*$", re.M)
PORT_RE = re.compile(r"^\s*port:\s*(\d+)\s*$", re.M)


def iter_yaml_files():
    for root in SCAN_ROOTS:
        if not root.is_dir():
            continue
        for pattern in ("**/*.yaml", "**/*.yml"):
            yield from root.glob(pattern)


def main() -> int:
    if not any(r.is_dir() for r in SCAN_ROOTS):
        print(f"FAIL: 未找到扫描目录: {[str(r) for r in SCAN_ROOTS]}")
        return 1

    failures: list[str] = []
    checked = 0

    for path in sorted(iter_yaml_files()):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        rel = path.relative_to(REPO_ROOT)
        lines = text.splitlines()

        for probe, line_no, body in probe_blocks(lines):
            # 必须用 \n 连接: splitlines() 已丢掉换行, 直接 join 会把多行粘成一行,
            # 使 re.M 的 ^/$ 永远匹配不到(实测扫描到 0 处探针的假绿)。
            body_text = "\n".join(body)
            path_match = PATH_RE.search(body_text)
            if not path_match:
                continue
            probe_path = path_match.group(1).strip('"').strip("'")
            if probe_path.startswith(FOREIGN_PREFIXES):
                continue

            checked += 1

            if probe_path.startswith("/actuator"):
                failures.append(
                    f"{rel}:{line_no}: {probe} 路径 {probe_path} —— base-path=/management "
                    f"已取代 /actuator, 该路径恒 404(实测)")
                continue

            if "/health" in probe_path:
                parts = [p for p in probe_path.split("/") if p]
                # 期望形如 management / health / <group>
                if len(parts) < 3 or parts[0] != "management" or parts[1] != "health":
                    failures.append(
                        f"{rel}:{line_no}: {probe} 路径 {probe_path} 不符合 "
                        f"/management/health/<group>")
                elif parts[2] not in VALID_GROUPS:
                    failures.append(
                        f"{rel}:{line_no}: {probe} 指向健康分组 '{parts[2]}', "
                        f"但 Spring Boot 只有 {sorted(VALID_GROUPS)}(实测 "
                        f"/management/health/startup -> 404)")

            port_match = PORT_RE.search(body_text)
            if port_match and int(port_match.group(1)) != MANAGEMENT_PORT:
                failures.append(
                    f"{rel}:{line_no}: {probe} 端口 {port_match.group(1)} "
                    f"应为管理端口 {MANAGEMENT_PORT}")

    # compose healthcheck 也必须指向真实可达的路径。
    # 只取真正的 URL 行: compose 里大量注释会提到 /actuator/health 作为说明,
    # 首版未排除注释行, 导致 6 处假阳性。
    if COMPOSE_FILE.is_file():
        url_re = re.compile(r"https?://[^\s\"']+")
        for line_no, line in enumerate(
                COMPOSE_FILE.read_text(encoding="utf-8").splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("#"):
                continue
            for url in url_re.findall(stripped):
                if "/_cluster/health" in url or url.rstrip("/").endswith(":8848"):
                    continue      # 第三方组件原生探针
                if "/management/health/" in url:
                    group = url.split("/management/health/")[1].split("/")[0]
                    if group not in VALID_GROUPS:
                        failures.append(
                            f"deploy/compose.yaml:{line_no}: healthcheck 指向不存在的"
                            f"分组 '{group}'(URL {url})")
                elif "actuator/health" in url:
                    failures.append(
                        f"deploy/compose.yaml:{line_no}: healthcheck 使用 {url} —— "
                        f"base-path=/management, 该路径恒 404(实测)")

    if failures:
        print("FAIL:")
        for failure in failures:
            print(f"  - {failure}")
        print(f"\n探针路径 404 => Pod 恒不健康。真实路径由 base-path=/management 决定。")
        return 1

    print(f"PASS K8s 探针路径守护: 扫描 {checked} 处探针, 路径与端口均正确")
    return 0


if __name__ == "__main__":
    sys.exit(main())