#!/usr/bin/env python3
"""Check that otel-collector pipelines only reference declared components.

## Why this guard exists (real occurrence, 2026-10-07)

While removing the `otlp/jaeger` exporter from `deploy/monitoring/otel-collector.yml`,
the exporter *definition* was deleted but the pipeline still listed it:

    exporters: [otlp/jaeger, debug]     # <- referenced a now-missing exporter

The OTel collector rejects such a config at startup, so the container would
fail to start with an error that does not name the removed component
prominently. This is the same family as the project's 3rd hard rule
("mechanism exists != mechanism is running") applied to configuration:
a reference that survives its definition is invisible to every test that
only checks "is the service reachable".

It is also the exact same defect shape as
`check_compose_dangling_deps.py` (dangling depends_on), one layer down:
both ask "does every referenced thing still exist?".

## Exit codes
  0 = all pipeline references resolve
  1 = a pipeline references an undeclared receiver/processor/exporter
  2 = the scanner itself failed (SCANNER_BROKEN)
"""
from __future__ import annotations

import sys
import re
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("SCANNER_BROKEN: PyYAML not available", file=sys.stderr)
    raise SystemExit(2)

# 2026-10-13: compose 侧的 deploy/monitoring/otel-collector.yml 已随该容器移除
# (它的唯一 exporter 是 `debug`, 属死路)。守护对象因此**迁移**到 k8s 的 Helm 模板
# —— 不是消失。故本门禁改为跟随迁移, 而不是被删掉。
# 2026-10-13: 配置真源已收敛到 values.yaml —— 模板改为
# `{{- toYaml .Values.otelCollector.config | nindent 4 }}` 消费它,
# 此前模板里那份是硬编码副本且从不读 .Values(两份配置并存, 改一处另一处即失效)。
# compose 侧那份随 otel-collector 容器一同移除。
CANDIDATES = (
    Path("deploy/k8s/infra/values.yaml"),
    Path("deploy/k8s/infra/templates/otel-collector.yaml"),
)

# values.yaml 顶层是普通 YAML(可直接解析); Helm 模板里配置是字面块或 toYaml 指令。
NESTED_PREFIX = "otelCollector"


def extract_embedded(text: str) -> str | None:
    """从 Helm 模板里取出 `config.yaml: |` 的字面块并去掉公共缩进。

    只处理**整块**提取, 不做逐行替换 —— 该文件中的 Helm 指令全部位于 K8s
    manifest 段(metadata/replicas/image), collector 配置本体是纯 YAML。
    刻意不采用"遇到 {{ 就跳行"的写法: 那种做法一旦模板语法跨行,
    就会静默吃掉真实配置 (第五课补记一记录的 strip_comments 事故)。
    """
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^(\s*)config\.yaml:\s*\|\s*$", line)
        if not m:
            continue
        indent = len(m.group(1))
        block = []
        for nxt in lines[i + 1:]:
            if nxt.strip() and len(nxt) - len(nxt.lstrip()) <= indent:
                break
            block.append(nxt)
        if not block:
            return None
        pad = min(len(b) - len(b.lstrip()) for b in block if b.strip())
        return "\n".join(b[pad:] if b.strip() else "" for b in block)
    return None


class HelmDelegated(Exception):
    """模板把配置委托给了 values({{- toYaml .Values... }}), 本文件里没有配置本体。"""


def load_config(path: Path):
    text = path.read_text(encoding="utf-8")
    embedded = extract_embedded(text)
    payload = embedded if embedded is not None else text
    # 模板块里只剩 Helm 指令 => 配置真源在 values.yaml, 此处不重复判定。
    if "toYaml" in payload or "{{- if" in payload:
        raise HelmDelegated(str(path))
    doc = yaml.safe_load(payload)
    if isinstance(doc, dict) and NESTED_PREFIX in doc:
        return (doc[NESTED_PREFIX] or {}).get("config")
    return doc


def main() -> int:
    present = [c for c in CANDIDATES if c.exists()]
    if not present:
        print("SCANNER_BROKEN: no otel-collector config found in "
              + ", ".join(str(c) for c in CANDIDATES), file=sys.stderr)
        return 2
    results = []
    for CONFIG in present:
        rc = check_one(CONFIG)
        results.append(rc)
        if rc != 0:
            return rc
    return 0


def check_one(CONFIG: Path) -> int:
    try:
        doc = load_config(CONFIG)
    except HelmDelegated:
        print(f"SKIP  {CONFIG}: 配置已委托给 values, 本文件不含配置本体")
        return 0
    except yaml.YAMLError as exc:
        print(f"SCANNER_BROKEN: cannot parse {CONFIG}: {exc}", file=sys.stderr)
        return 2
    if not isinstance(doc, dict):
        print(f"SCANNER_BROKEN: top level is {type(doc)}", file=sys.stderr)
        return 2

    declared = {
        kind: set((doc.get(kind) or {}).keys())
        for kind in ("receivers", "processors", "exporters")
    }
    for kind, names in declared.items():
        if not names:
            # Scanning zero targets and calling it green is the defect recorded in
            # 5th lesson note 1. A collector with no exporters would silently drop
            # every span, so an empty section must never pass.
            print(f"SCANNER_BROKEN: {CONFIG}: '{kind}' is empty or missing", file=sys.stderr)
            return 2

    pipelines = ((doc.get("service") or {}).get("pipelines")) or {}
    if not pipelines:
        print(f"SCANNER_BROKEN: {CONFIG}: service.pipelines is empty or missing", file=sys.stderr)
        return 2

    dangling: list[str] = []
    checked = 0
    for name, pipe in pipelines.items():
        if not isinstance(pipe, dict):
            print(f"SCANNER_BROKEN: pipeline {name} is not a mapping", file=sys.stderr)
            return 2
        for kind in ("receivers", "processors", "exporters"):
            for item in pipe.get(kind, []) or []:
                checked += 1
                if item not in declared[kind]:
                    dangling.append(f"pipeline[{name}].{kind} -> {item}  (not declared in {kind})")

    if dangling:
        print("DANGLING pipeline references:")
        for d in dangling:
            print(f"  FAIL  {d}")
        print()
        print(
            "The OTel collector rejects a config whose pipeline names an undeclared\n"
            "component, so the container will fail to start. Either restore the\n"
            "component definition or remove the reference."
        )
        return 1

    print(
        f"PASS  {len(pipelines)} pipeline(s), {checked} reference(s) all resolve "
        f"(receivers={sorted(declared['receivers'])}, "
        f"processors={sorted(declared['processors'])}, "
        f"exporters={sorted(declared['exporters'])})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())