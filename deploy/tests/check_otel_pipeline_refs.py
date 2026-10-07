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
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("SCANNER_BROKEN: PyYAML not available", file=sys.stderr)
    raise SystemExit(2)

CONFIG = Path("deploy/monitoring/otel-collector.yml")


def main() -> int:
    if not CONFIG.exists():
        print(f"SCANNER_BROKEN: {CONFIG} not found", file=sys.stderr)
        return 2
    try:
        doc = yaml.safe_load(CONFIG.read_text(encoding="utf-8"))
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