#!/usr/bin/env python3
"""test_gate_semantics_selftest.py — 门禁「语义 × 接线」自检（阻断清单一致性守护）

背景（2026-11-08 第三批假门禁收口后）:
    本仓已有两层守护:
      - verify-e2e.sh 内嵌 self-test: 只认"阻断门禁必须以 || exit 1 / if ! 接线"
        的形态, 清单 14 项 (check_sw_oap_config ... check_feign_timeout)。
      - deploy/tests/test_gate_wiring.py: 只守"脚本是否被引用"。
    两层都覆盖不到"** advisory 门禁将来被误升为阻断/反之**"这一类漂移 —
    即: 一个本该告警的门禁, 在调用处被改成 `|| exit 1`;或一个本该阻断的门禁,
    又被降回 `|| true`。前者会让 E2E 在非关键缺陷上红灯失控, 后者会重新制造
    "假阻断"。

本守护补上中间层: **对 verify-e2e.sh 中每个 check_* 调用, 校验其声明语义
(阻断/告警)与实际接线形态一致**, 并把双方固化为同一份期望清单。

判据:
  - BLOCKING_GATES (14): 必须以 `check_xxx || exit 1` 接线。
  - ADVISORY_GATES (11): 必须以 `check_xxx || true` 降级。
  - 任一不一致 → rc=1（负向对照可证）;当前真实仓库状态 → rc=0。

调用: python3 deploy/tests/test_gate_semantics_selftest.py
"""
import re
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "verify-e2e.sh"
REPO = SCRIPT.parents[0]

# 本轮（2026-11-08 收口）确定的阻断门禁清单 ——
# verify-e2e.sh 内嵌 self-test 的 14 项, 两侧必须一致。
BLOCKING_GATES = (
    "check_sw_oap_config",
    "check_schema_standalone",
    "check_schema_init_wiring",
    "check_container_startup_budget_gate",
    "check_es_wait_strategy",
    "check_compose_dangling_deps",
    "check_otel_pipeline_refs",
    "check_port_conflicts",
    "check_v18_watchdog",
    "check_commit_runtime_guard",
    "check_native_alloc_evidence",
    "check_grpc_port_placeholder",
    "check_start_period_covers_startup",
    "check_feign_timeout",
)

# 本轮（2026-11-08）判定为 advisory 的门禁 ——
# 在 verify-e2e.sh 中以 `|| true` 降级, 语义为"提示/指标, 不阻断流程"。
ADVISORY_GATES = (
    "check_catch_exception_count",
    "check_keys_not_used",
    "check_nacos_config_alignment",
    "check_shared_config_alignment",
    "check_proxy_env",
    "check_resource_optimization",
    "check_docs_probe_scripts",
    "check_lesson_tables",
    "check_jvm_native_bounds",
    "check_jvm_opts_keys",
    "check_testcontainers_runtime",
)


def strip_comments(text: str) -> str:
    """剥掉 # 注释行, 再做调用形态匹配（与 verify-e2e awk 判据同源）。"""
    out = []
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        out.append(line)
    return "\n".join(out)


def find_blocking_calls(text: str) -> set:
    """收集 `check_xxx || exit 1` 形态的阻断调用。"""
    return set(re.findall(r"\b(check_[A-Za-z0-9_]*)\s*\|\|\s*exit\s+1\b", text))


def is_blocking(text: str, gate: str) -> bool:
    """阻断形态二: `if ! { …; check_xxx; }; then` (verify-e2e.sh 第 1287 行即此)。"""
    pattern = r"if\s*!\s*\{(?:[^}]*?)" + re.escape(gate) + r"(?:[^}]*?)\}\s*;\s*then"
    return bool(re.search(pattern, text))


def find_advisory_calls(text: str) -> set:
    return set(re.findall(r"\b(check_[A-Za-z0-9_]*)\s*\|\|\s*true\b", text))


def main() -> int:
    if not SCRIPT.is_file():
        print(f"FAIL: {SCRIPT} 不存在", file=sys.stderr)
        return 2
    text = strip_comments(SCRIPT.read_text(encoding="utf-8"))
    blocking_now = find_blocking_calls(text)
    advisory_now = find_advisory_calls(text)

    problems = []
    for g in BLOCKING_GATES:
        if g not in blocking_now and not is_blocking(text, g):
            problems.append(f"阻断门禁 {g} 未以阻断形态接线（|| exit 1 / if ! 接线）")
    for g in ADVISORY_GATES:
        if g in blocking_now:
            problems.append(f"advisory 门禁 {g} 却被升为阻断 (`|| exit 1`)")
        if g not in advisory_now:
            problems.append(f"advisory 门禁 {g} 未以 `|| true` 降级")

    unexpected_advisory = sorted(advisory_now - set(ADVISORY_GATES))
    if unexpected_advisory:
        problems.append(
            "存在未声明语义的 `|| true` 门禁: " + ", ".join(unexpected_advisory))

    if problems:
        print("FAILED: 门禁语义接线自检发现不一致:")
        for p in problems:
            print("  - " + p)
        print("        期望阻断形态: " + ", ".join(BLOCKING_GATES))
        print("        期望 advisory 降级形态: " + ", ".join(ADVISORY_GATES))
        return 1
    print(f"PASS: 门禁语义接线自检通过 — {len(BLOCKING_GATES)} 阻断 / "
          f"{len(ADVISORY_GATES)} advisory, 调用形态一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
