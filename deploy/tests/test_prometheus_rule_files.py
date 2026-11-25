#!/usr/bin/env python3
"""守护: deploy/monitoring/prometheus-rules/ 下的每个规则文件都必须被真正加载。

## 要防的缺陷 (2026-10-17 实测, 非推测)

`deploy/prometheus.yml` 写的是

    rule_files:
      - /etc/prometheus/rules/*.yml

而同目录下有一个 `gateway-alerts.yaml`。结果是:

  1. glob `*.yml` **不匹配** `.yaml` 后缀 → 20 条网关规则从未被 Prometheus 加载;
  2. 更隐蔽的一层: 该文件本体是 `PrometheusRule` CRD
     (`apiVersion: monitoring.coreos.com/v1`), 而本仓库**没有任何 prometheus-operator**
     消费它 —— 即便把后缀改成 .yml, CRD 外壳 (`apiVersion`/`kind`/`metadata`/`spec`)
     也不是原生规则文件格式, Prometheus 会解析失败。

两重失效叠加, 结果是 31 条告警里 20 条静默失效, 且 `podman-compose up` 全程无任何报错。

## 判定规则

对 `deploy/monitoring/prometheus-rules/` 下每个 `.yml`/`.yaml`:

  1. 必须命中 `deploy/prometheus.yml` 的 `rule_files` glob;
  2. 顶层必须是 `groups:` (即原生规则文件, 而非 PrometheusRule CRD 或其它 K8s 资源);
  3. `groups[].rules[]` 至少一条, 且每条 `alert` 有 `expr`。

任一条不满足 → rc=1 并指名文件。

## 负向对照

`--negative-control` 会在临时副本上把 `*.y*ml` 改回 `*.yml`, 断言此时**必须**报红。
守护若不会红, 说明它本身失效了。
"""
from __future__ import annotations

import argparse
import fnmatch
import pathlib
import shutil
import sys
import tempfile

import yaml

RULES_DIR = pathlib.Path("deploy/monitoring/prometheus-rules")
PROM_CONFIG = pathlib.Path("deploy/prometheus.yml")

# 容器内挂载点 (deploy/compose.yaml 把 RULES_DIR 挂到 /etc/prometheus/rules)
CONTAINER_RULES_GLOB_PREFIX = "/etc/prometheus/rules/"


def load_rule_globs(config_path: pathlib.Path) -> list[str]:
    config = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
    globs = config.get("rule_files") or []
    return [g for g in globs if g.startswith(CONTAINER_RULES_GLOB_PREFIX)]


def check_rule_file(path: pathlib.Path, globs: list[str]) -> list[str]:
    problems: list[str] = []

    if not any(fnmatch.fnmatch(f"{CONTAINER_RULES_GLOB_PREFIX}{path.name}", g) for g in globs):
        problems.append("未被任何 rule_files glob 命中 (该文件永远不会生效)")

    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        return problems + [f"YAML 解析失败: {exc}"]

    if not isinstance(doc, dict) or "groups" not in doc:
        kind = (doc or {}).get("kind", "<无 kind>") if isinstance(doc, dict) else "<非映射>"
        api = (doc or {}).get("apiVersion", "<无 apiVersion>") if isinstance(doc, dict) else "-"
        problems.append(
            f"顶层无 groups: (apiVersion={api}, kind={kind}) —— "
            f"这不是原生 Prometheus 规则文件格式; 本仓库无 prometheus-operator, 故无人能消费它"
        )
        return problems

    total = 0
    for group in doc["groups"] or []:
        rules = (group or {}).get("rules") or []
        total += len(rules)
        for rule in rules:
            if not rule.get("expr"):
                problems.append(f"告警 {rule.get('alert', '<未命名>')} 缺 expr")
    if total == 0:
        problems.append("groups 下没有任何 rule")

    return problems


def audit(root: pathlib.Path) -> tuple[int, int]:
    globs = load_rule_globs(root / PROM_CONFIG)
    rule_files = sorted(
        p for p in (root / RULES_DIR).iterdir() if p.suffix in (".yml", ".yaml")
    )
    if not rule_files:
        print(f"!! {RULES_DIR} 下没有任何规则文件")
        return 0, 1

    failed = 0
    total_rules = 0
    for path in rule_files:
        problems = check_rule_file(path, globs)
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        if isinstance(doc, dict) and doc.get("groups"):
            total_rules += sum(len(g.get("rules") or []) for g in doc["groups"])
        if problems:
            failed += 1
            print(f"⛔ {path}")
            for problem in problems:
                print(f"    · {problem}")
        else:
            print(f"✅ {path}")

    print(f"\n规则文件 {len(rule_files)} 个, 告警规则 {total_rules} 条, 不合规 {failed} 个")
    return total_rules, failed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="仓库根目录")
    parser.add_argument(
        "--negative-control",
        action="store_true",
        help="在副本上把 glob 收窄回 *.yml, 断言此时必须报红",
    )
    args = parser.parse_args()

    root = pathlib.Path(args.root).resolve()

    if args.negative_control:
        with tempfile.TemporaryDirectory() as tmp:
            clone = pathlib.Path(tmp) / "repo"
            shutil.copytree(root / "deploy", clone / "deploy")
            rules = clone / RULES_DIR

            # 变异 1 —— 复现历史缺陷: 新增一个 .yaml 后缀的规则文件, 同时把 glob 收窄回 *.yml。
            # 这正是 2026-10-17 之前 gateway-alerts.yaml 的处境。
            (rules / "gateway-alerts.yml").rename(rules / "gateway-alerts.yaml")
            config = clone / PROM_CONFIG
            config.write_text(
                config.read_text(encoding="utf-8").replace("*.y*ml", "*.yml"),
                encoding="utf-8",
            )

            # 变异 2 —— 复现第二重缺陷: 把规则整体收进 spec:, 成为**纯** CRD 文件。
            # 注意不能"CRD 外壳 + 顶层 groups"混着注入: 那种混合体 Prometheus 仍能加载
            # (它只认顶层 groups), 测不出东西。历史缺陷文件是纯 CRD, 故此处也必须是纯 CRD。
            victim = rules / "cloud-infra-alerts.yml"
            groups = "".join(
                "  " + line + "\n"
                for line in victim.read_text(encoding="utf-8").split("groups:\n", 1)[1].splitlines()
            )
            victim.write_text(
                "apiVersion: monitoring.coreos.com/v1\nkind: PrometheusRule\n"
                "metadata:\n  name: injected\nspec:\n  groups:\n" + groups,
                encoding="utf-8",
            )

            _, failed = audit(clone)
            if failed < 2:
                print(
                    f"\n⛔ 负向对照失败: 两处注入只被抓到 {failed} 处 (应为 2) —— "
                    "守护对'文件未被加载'或'格式不是原生规则文件'存在盲区。"
                )
                return 1
            print(
                f"\n✅ 负向对照通过: .yaml 后缀不被 glob 命中、CRD 外壳无法解析, "
                f"两处注入均被如期报红 ({failed} 个文件)。"
            )
            return 0

    _, failed = audit(root)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())