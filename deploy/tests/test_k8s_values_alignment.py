#!/usr/bin/env python3
"""守护: deploy/k8s/*/values.yaml 必须与 compose 的运行时已验证值对齐。

## 背景（本仓库真实发生过的漂移, 2026-10-18 修复）

compose 侧是唯一有运行时证据的一侧（启动、内存、探针都经 E2E 实测）;
6 个 Helm charts 长期与之在五处漂移, 且每一处的外表都"看起来没问题":

1. JAVA_OPTS 缺 native 内存四项上限 —— 无上限时 JVM 默认预留约 2.15GB,
   而 check_jvm_native_memory_bounds.py 只扫 compose, 对 charts 不可见;
2. limits.memory 低于 compose mem_limit —— system 640Mi vs compose 2g,
   Caffeine ClassLoader 死锁的修复范围被吃掉; 其余四个服务 512Mi
   装不下"堆 256m + 堆外上限 448m";
3. prometheus.io 注解指向业务端口 + /actuator/prometheus（恒 404）;
4. startupProbe 窗口短于 compose start_period —— notification 310s < 360s、
   search 310s < 330s（就绪前就会被 kubelet 判定失败）;
5. configMap 暴露面宽于 Nacos 真源（env/beans/conditions 或 "*"）。

## 判据（对 6 个业务 chart）

A. JAVA_OPTS 含全部四项 -XX:*Size 上限;
B. limits.memory >= compose 同名服务 mem_limit（字节级折算）;
C. prometheus.io/port == managementService.port 且 path == /management/prometheus;
D. initialDelaySeconds + periodSeconds × failureThreshold >= compose start_period;
E. configMap 内 application.yml 的 exposure.include 不含 env/beans/conditions/"*"
   且含 prometheus。

扫不到对应结构一律判红（判据失效 != 通过）。

负向对照: --negative-control 在副本上逐类制造漂移, 五类都必须报红。
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
CHARTS = ("gateway", "auth", "system", "file", "notification", "search")
JVM_KEYS = ("JAVA_OPTS", "JAVA_TOOL_OPTIONS")
REQUIRED_CAPS = (
    "-XX:MaxMetaspaceSize",
    "-XX:CompressedClassSpaceSize",
    "-XX:ReservedCodeCacheSize",
    "-XX:MaxDirectMemorySize",
)
FORBIDDEN_EXPOSURE = ("env", "beans", "conditions", "*")

K8S_UNITS = {"Ki": 1 << 10, "Mi": 1 << 20, "Gi": 1 << 30, "Ti": 1 << 40}
# compose 的 m/g 按本仓库既有脚本的约定折算(见 check_resource_optimization.parse_size_mb)
COMPOSE_UNITS = {"k": 1 << 10, "m": 1 << 20, "g": 1 << 30}


def parse_k8s_bytes(value: object) -> int | None:
    m = re.fullmatch(r"(\d+)(Ki|Mi|Gi|Ti)?", str(value).strip())
    if not m:
        return None
    return int(m.group(1)) * K8S_UNITS.get(m.group(2) or "", 1)


def parse_compose_bytes(value: object) -> int | None:
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*([kmg])", str(value).strip().lower())
    if not m:
        return None
    return int(float(m.group(1)) * COMPOSE_UNITS[m.group(2)])


def parse_compose_seconds(value: object) -> float | None:
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip()
    if not text:
        return None
    unit = {"s": 1.0, "m": 60.0, "h": 3600.0}.get(text[-1])
    try:
        return float(text[:-1]) * unit if unit else float(text)
    except ValueError:
        return None


def jvm_opts(values: dict) -> str:
    env = values.get("env")
    opts: list[str] = []
    if isinstance(env, list):
        for item in env:
            if (isinstance(item, dict)
                    and item.get("name") in JVM_KEYS
                    and item.get("value")):
                opts.append(str(item["value"]))
    elif isinstance(env, dict):
        for key in JVM_KEYS:
            if env.get(key):
                opts.append(str(env[key]))
    return " ".join(opts)


def check_chart(name: str, values: dict, compose_service: dict,
                problems: list[str]) -> None:
    opts = jvm_opts(values)
    if not opts:
        problems.append(f"{name}: values.yaml 里找不到 JAVA_OPTS —— 判据失效, 不是通过")
    else:
        for cap in REQUIRED_CAPS:
            if cap not in opts:
                problems.append(
                    f"{name}: JAVA_OPTS 缺 {cap}"
                    f"(无上限时 JVM 默认预留约 2.15GB native 内存)")

    limit = ((values.get("resources") or {}).get("limits") or {}).get("memory")
    want = parse_compose_bytes(compose_service.get("mem_limit"))
    got = parse_k8s_bytes(limit)
    if want is None:
        problems.append(
            f"{name}: compose 侧 mem_limit 无法解析(值={compose_service.get('mem_limit')!r})")
    elif got is None:
        problems.append(f"{name}: limits.memory 无法解析(值={limit!r})")
    elif got < want:
        problems.append(
            f"{name}: limits.memory={limit} < compose mem_limit="
            f"{compose_service.get('mem_limit')} —— cgroup OOM-kill 风险")

    ann = values.get("podAnnotations") or {}
    port = (values.get("managementService") or {}).get("port")
    ann_port = ann.get("prometheus.io/port")
    ann_path = ann.get("prometheus.io/path")
    if port is None:
        problems.append(f"{name}: managementService.port 缺失")
    elif ann_port is None or str(ann_port) != str(port):
        problems.append(
            f"{name}: prometheus.io/port={ann_port!r} != managementService.port={port}"
            f"(注解抓取会指向业务端口, 恒 404)")
    if ann_path != "/management/prometheus":
        problems.append(
            f"{name}: prometheus.io/path={ann_path!r} 应为 /management/prometheus"
            f"(base-path 已取代 /actuator)")

    probe = values.get("startupProbe") or {}
    window = (probe.get("initialDelaySeconds", 0)
              + probe.get("periodSeconds", 10) * probe.get("failureThreshold", 0))
    sp = parse_compose_seconds((compose_service.get("healthcheck") or {}).get("start_period"))
    if sp is None:
        problems.append(f"{name}: compose healthcheck.start_period 缺失/不可解析")
    elif not probe or window < sp:
        problems.append(
            f"{name}: startupProbe 窗口 {window}s < compose start_period {sp:.0f}s"
            f"(就绪前就会被判定失败)")

    cm = ((values.get("configMap") or {}).get("data") or {}).get("application.yml")
    if not cm:
        problems.append(f"{name}: configMap.data.application.yml 缺失 —— 判据失效")
        return
    try:
        inner = yaml.safe_load(cm)
        include = inner["management"]["endpoints"]["web"]["exposure"]["include"]
    except Exception:  # noqa: BLE001 - 任何结构偏差都应判红而非崩溃
        problems.append(f"{name}: 无法从 configMap 解析 exposure.include")
        return
    tokens = {t.strip() for t in str(include).split(",")}
    bad = tokens & set(FORBIDDEN_EXPOSURE)
    if bad:
        problems.append(
            f"{name}: exposure.include 暴露 {sorted(bad)}(Nacos 真源不含这些端点)")
    if "prometheus" not in tokens:
        problems.append(f"{name}: exposure.include 未含 prometheus")


def audit(root: Path) -> tuple[list[str], int]:
    problems: list[str] = []
    compose_path = root / "deploy" / "compose.yaml"
    try:
        compose = yaml.safe_load(compose_path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        return [f"无法读取/解析 {compose_path}: {exc}"], 0
    services = (compose or {}).get("services") or {}

    checked = 0
    for name in CHARTS:
        values_path = root / "deploy" / "k8s" / name / "values.yaml"
        if not values_path.is_file():
            problems.append(f"{name}: 找不到 {values_path.relative_to(root)}")
            continue
        if name not in services:
            problems.append(f"{name}: compose.yaml 里没有同名服务 —— 两侧对不上")
            continue
        try:
            values = yaml.safe_load(values_path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            problems.append(f"{name}: values.yaml YAML 解析失败: {exc}")
            continue
        if not isinstance(values, dict):
            problems.append(f"{name}: values.yaml 顶层不是映射")
            continue
        checked += 1
        check_chart(name, values, services[name], problems)

    if checked == 0:
        problems.append("未检查到任何 chart —— 判据失效, 不是通过")
    return problems, checked


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(REPO_ROOT))
    parser.add_argument("--negative-control", action="store_true",
                        help="在副本上制造 5 类漂移, 断言守护必须全部报红")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    if args.negative_control:
        with tempfile.TemporaryDirectory() as tmp:
            clone = Path(tmp) / "repo"
            shutil.copytree(root / "deploy", clone / "deploy",
                            ignore=shutil.ignore_patterns("__pycache__"))
            mutations = [
                ("deploy/k8s/file/values.yaml",
                 " -XX:MaxMetaspaceSize=256m", "",
                 "缺 -XX:MaxMetaspaceSize"),
                ("deploy/k8s/gateway/values.yaml",
                 "memory: 640Mi", "memory: 256Mi",
                 "limits.memory=256Mi < compose mem_limit"),
                ("deploy/k8s/search/values.yaml",
                 "prometheus.io/port: '9301'", "prometheus.io/port: '8085'",
                 "prometheus.io/port='8085'"),
                ("deploy/k8s/notification/values.yaml",
                 "failureThreshold: 40", "failureThreshold: 30",
                 "startupProbe 窗口"),
                ("deploy/k8s/system/values.yaml",
                 "include: health,info,prometheus,metrics,refresh",
                 "include: env,health",
                 "暴露"),
            ]
            for rel, old, new, _sig in mutations:
                path = clone / rel
                text = path.read_text(encoding="utf-8")
                assert old in text, f"负向对照变异失败: {rel} 里找不到 {old!r}"
                path.write_text(text.replace(old, new), encoding="utf-8")

            problems, _checked = audit(clone)
            missing = [sig for _rel, _old, _new, sig in mutations
                       if not any(sig in p for p in problems)]
            if missing:
                print("⛔ 负向对照失败: 以下漂移未被报红:", missing)
                for p in problems:
                    print("   ", p)
                return 1
            print(f"✅ 负向对照通过: 5 类漂移全部被报红({len(problems)} 项)")
            return 0

    problems, checked = audit(root)
    if problems:
        print(f"FAIL: {len(problems)} 项")
        for p in problems:
            print(f"  - {p}")
        return 1
    print(f"PASS: {checked} 个 chart 的 JAVA_OPTS/内存/注解/探针/暴露面均与 compose 对齐")
    return 0


if __name__ == "__main__":
    sys.exit(main())
