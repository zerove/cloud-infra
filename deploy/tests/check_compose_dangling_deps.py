#!/usr/bin/env python3
"""Scan compose files for depends_on entries pointing at services that do not exist.

A dangling depends_on is silent: podman-compose 1.6.0 `config` returns rc=0 with
empty stderr (measured 2026-10-07), so the defect only surfaces at `up` time with
unclear behaviour. This is a structural check, not a behavioural one.

Exit codes:
  0 = no dangling references
  1 = dangling reference found
  2 = the scanner itself failed (see SCANNER_BROKEN notes)
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("SCANNER_BROKEN: PyYAML not available", file=sys.stderr)
    raise SystemExit(2)

COMPOSE_FILES = [
    Path("deploy/compose.yaml"),
    Path("deploy/compose.obs.yaml"),
]


def declared_services(doc: dict, path: Path) -> set[str]:
    services = doc.get("services")
    if not isinstance(services, dict):
        raise ValueError(f"{path}: top-level 'services' missing or not a mapping")
    if not services:
        # Scanning zero targets and reporting green is the exact defect recorded
        # in the project's 5th lesson note 1 (module root with no target/ ->
        # every target SKIP -> exit 0). A guard that found nothing has proven
        # nothing, so this is SCANNER_BROKEN, not a pass.
        raise ValueError(f"{path}: 'services' is empty — nothing to scan, cannot pass")
    return set(services)


def collect_deps(services: dict) -> list[tuple[str, str, int]]:
    """Return (service, dependency, line_hint) for every depends_on entry.

    depends_on may be a list (short form) or a mapping (long form with
    condition). Both forms are handled because the project uses both.
    """
    found: list[tuple[str, str, int]] = []
    for svc, spec in services.items():
        if not isinstance(spec, dict):
            continue
        deps = spec.get("depends_on")
        if deps is None:
            continue
        if isinstance(deps, list):
            for dep in deps:
                found.append((svc, str(dep), 0))
        elif isinstance(deps, dict):
            for dep in deps:
                found.append((svc, str(dep), 0))
        else:
            raise ValueError(f"depends_on of {svc} is neither list nor mapping: {type(deps)}")
    return found


def line_of(path: Path, needle: str) -> int:
    """Best-effort line number for reporting. Not used for correctness."""
    try:
        for idx, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if needle in raw:
                return idx
    except OSError:
        pass
    return 0


def main() -> int:
    roots: dict[str, Path] = {}
    for path in COMPOSE_FILES:
        if not path.exists():
            continue
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            print(f"SCANNER_BROKEN: cannot parse {path}: {exc}", file=sys.stderr)
            return 2
        if not isinstance(doc, dict):
            print(f"SCANNER_BROKEN: {path} top level is {type(doc)}", file=sys.stderr)
            return 2
        try:
            declared_services(doc, path)
            collect_deps(doc.get("services") or {})
        except ValueError as exc:
            print(f"SCANNER_BROKEN: {exc}", file=sys.stderr)
            return 2
        roots[path.stem] = path

    if not roots:
        print("SCANNER_BROKEN: no compose file found at all", file=sys.stderr)
        return 2

    # Union of services across all compose files: a cross-file reference is
    # legal, so the declared set must span every file in the same project.
    universe: set[str] = set()
    per_file: dict[str, tuple[Path, set[str]]] = {}
    for stem, path in roots.items():
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        names = declared_services(doc, path)
        per_file[stem] = (path, names)
        universe |= names

    # Per-file scan against the union, reporting cross-file refs explicitly.
    dangling: list[tuple[str, str, str, int]] = []
    cross_file: list[tuple[str, str, str]] = []
    for stem, (path, names) in per_file.items():
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        for svc, dep, _ in collect_deps(doc.get("services") or {}):
            if dep in names:
                continue
            if dep in universe:
                cross_file.append((stem, svc, dep))
                continue
            dangling.append((stem, svc, dep, line_of(path, dep)))

    for stem, svc, dep in cross_file:
        print(f"CROSS-FILE  {stem}: {svc} -> {dep} (declared in another compose file)")

    if dangling:
        print()
        print("DANGLING depends_on found:")
        for stem, svc, dep, line in dangling:
            where = f"{per_file[stem][0]}:{line}" if line else str(per_file[stem][0])
            print(f"  FAIL  {stem}: {svc} -> {dep}   ({where})")
        print()
        print(
            "podman-compose does NOT reject dangling depends_on at config time "
            "(rc=0, empty stderr, measured on 1.6.0). It would only misbehave at up time."
        )
        return 1

    total = sum(len(collect_deps(yaml.safe_load(p.read_text(encoding="utf-8")).get("services") or {})) for p, _ in per_file.values())
    print(f"PASS  {len(roots)} compose file(s), {total} depends_on entries, 0 dangling")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())