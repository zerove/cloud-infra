#!/usr/bin/env python3
"""守护: deploy/tests 下的门禁脚本必须真的被 CI 调用。

## 背景（2026-10-05 实测缺口, 非推测）

盘 `deploy/tests/` 共 21 个脚本, 逐个反查 `.github/workflows/*.yml` 的引用,
结果 **只有 6 个被调用**, 其余 15 个从未执行:

    ⛔ test_check_sw_oap_config.sh        ⛔ test_executor_injection_qualifier.py
    ⛔ test_commit_runtime_guard.sh       ⛔ test_jvm_opts_keys_match_image.py
    ⛔ test_probe_timeout_guard.sh        ⛔ test_readback_block_runtime.py
    ⛔ test_repair_healthcheck_logs.sh    ⛔ test_commit_budget.py
    ⛔ test_commit_runtime_guard.py       ⛔ check_commit_budget.py
    ⛔ check_commit_runtime_guard.py      ⛔ check_jvm_native_memory_bounds.py
    ⛔ check_jvm_opts_keys_match_image.py ⛔ check_sw_oap_config.sh
    ⛔ classify_gh_run.sh

其中 `test_executor_injection_qualifier.py` 正是守护"执行器注入二义"那个缺陷的门禁。

## 这解释了一个长期 puzzled 现象

CI 反复以同一条报错告警:

    Parameter N of constructor in ... required a single bean, but 2 were found

而且**每次只报一个类**, 于是只能"报一次修一个"
（AGENTS.md 第五课补记二记录了三个 run 依次报出三个不同类）。

若那个守护在 CI 里跑过, **第一轮就会把全仓的注入点一次报全**。
即: 门禁不是"报了但没修好", 而是**从来没被调用过** ——
这与 AGENTS.md 第三课「能力存在 ≠ 能力在运行」同型, 只是对象是门禁本身。

## 判据: 白名单而非黑名单

黑名单（列出"已知不需要接线的几个"）无法枚举**未知的漏接**。
而"应该接线的"这一侧是可枚举的（就是 `test_*` / `check_*` 脚本本身）,
故必须用白名单方向（AGENTS.md「黑名单 vs 白名单」）。

## 为什么反查工作流文件而不是硬编码清单

硬编码一张"已接线清单"的脚本, 会在有人**删除工作流里的引用**时变成
又一张没人维护的登记表 —— 即本守护自己也会假绿。
故本脚本只做一件事: **列出脚本文件, 逐个在工作流里找引用**, 找不到就报红。
新增脚本无需改本文件, 漏接会立即暴露。

## 豁免: 哪些脚本**不应该**被 workflow 直接调用

| 类别 | 原因 |
| :--- | :--- |
| `check_*` | **判定**门禁, 由 `verify-e2e.sh` 在运行期调用。在 workflow 里跑只会读到 CI runner 自己的 `/proc/meminfo`, 对本项目无意义 |
| `classify_gh_run.sh` | 诊断工具, 需要 `gh` 登录与特定 run id; 不是门禁 |

豁免**按类别声明**（每条带理由）, 不是逐个列名 —— 逐个列名会漏掉新增的
`check_*` 脚本, 而漏掉的正是最需要被发现的那部分（AGENTS.md 第五课补记一）。

## 通配符也算接线

工作流用 `for t in deploy/tests/test_*.sh` 批量执行, 不含任何具体文件名。
若只按具体文件名找引用, 守护会把"明明会被执行"的脚本报成"从未运行" ——
**探针查错了对象**（AGENTS.md 第五课第七例）。故判据同时认这两种形式。

## 负向对照

删掉 `.github/workflows/e2e.yml` 里的 "Gate self-tests" 步骤后,
本脚本必须报出全部 `test_*` 脚本, 退出码 1。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKFLOWS = REPO / ".github" / "workflows"
TESTS_DIR = Path(__file__).resolve().parent

# 不要求被 workflow 直接调用的脚本类别及**为什么**。
#
# `check_*` 是**判定**门禁: 它们在 E2E 运行期由 verify-e2e.sh 调用,
# 对真实系统下结论（如"当前 commit 是否超限"）。若在 workflow 里跑,
# 读到的是 CI runner 自己的 /proc/meminfo, 对本项目无意义。
#
# `classify_gh_run.sh` 是**诊断**工具: 需 gh 登录与特定 run id, 不是门禁。
#
# 豁免是**按类别声明 + 每条给理由**, 不是逐个列名 —— 后者会漏新增的脚本,
# 而漏掉的正是最需要被发现的那部分（AGENTS.md 第五课补记一）。
EXEMPT_CATEGORIES: dict[str, str] = {
    "check_": "判定门禁, 由 verify-e2e.sh 在运行期调用（读真实 /proc, 对 runner 自身无意义）",
    "classify_gh_run.sh": "诊断工具, 需 gh 登录与 run id, 不是门禁",
}


def is_exempt(name: str) -> str | None:
    """返回豁免理由; 不豁免则返回 None。"""
    if name in EXEMPT_CATEGORIES:
        return EXEMPT_CATEGORIES[name]
    for prefix, reason in EXEMPT_CATEGORIES.items():
        if prefix.endswith("_") and name.startswith(prefix):
            return reason
    return None


def workflow_texts() -> str:
    """拼起所有工作流文件的正文。

    ⚠️ 必须包含 `steps` 之外的**全部**文本 —— `--self-test` 这类由
    `verify-e2e.sh --self-test` 内部再调用的情况, 在注释里也可能有痕迹。
    只搜 `run:` 的值会漏掉"被别的脚本间接调用"的情形。
    """
    if not WORKFLOWS.is_dir():
        return ""
    return "\n".join(p.read_text(encoding="utf-8") for p in sorted(WORKFLOWS.glob("*.yml")))


def gate_scripts() -> list[Path]:
    """列出所有门禁脚本（排除 __pycache__ 与本脚本自身）。"""
    out: list[Path] = []
    for pattern in ("test_*.sh", "test_*.py", "check_*.sh", "check_*.py"):
        out.extend(TESTS_DIR.glob(pattern))
    return sorted(p for p in out if p.is_file())


# 通配符接线: 工作流里写 `for t in deploy/tests/test_*.sh` 而不是逐个文件名。
#
# ⚠️ 这是本守护第一版的**假阴性来源**: 只按具体文件名找引用, 于是聚合步骤
#   明明会执行 test_*.sh, 守护却报"未被引用"。
#   即"守护查的不是被检对象本身"—— AGENTS.md 第五课第七例的又一次:
#   「这个脚本有没有被调用」要用**会执行到它的那种形式**去判,
#   而不是猜某一种写法。
#
#   判据: 工作流正文里出现该脚本**所在目录 + 模式前缀**, 且带通配符。
#   这里只认 `test_*` / `check_*` 两个前缀 —— 它们覆盖本目录全部门禁,
#   而目录内没有其它命名习惯的脚本（新增时本守护会自动要求它被覆盖）。
WILDCARD_PREFIXES = ("deploy/tests/test_*", "deploy/tests/check_*",
                     "$SCRIPT_DIR/deploy/tests/test_*", "$SCRIPT_DIR/deploy/tests/check_*")


def wildcard_covered(blob: str) -> bool:
    """工作流是否用通配符批量执行门禁脚本。"""
    return any(prefix in blob for prefix in WILDCARD_PREFIXES)


def main() -> int:
    scripts = gate_scripts()
    if not scripts:
        # 扫到 0 个 == 扫描器坏了, 不是通过（第五课补记一）
        print(f"FAIL: 未扫到任何门禁脚本 ({TESTS_DIR}) —— 目录结构可能已变, 不能当作通过")
        return 1

    blob = workflow_texts()
    if not blob:
        print(f"FAIL: {WORKFLOWS} 不存在或无 .yml —— 无法判定接线情况, 不能当作通过")
        return 1

    # check_* 是判定门禁（由 verify-e2e.sh 调用）, 不要求 workflow 直接跑
    targets = [p for p in scripts
               if p.name != Path(__file__).name and is_exempt(p.name) is None]

    via_wildcard = wildcard_covered(blob)
    unreferenced: list[str] = []
    for path in targets:
        if path.name not in blob and not via_wildcard:
            unreferenced.append(path.name)

    print("=== 门禁脚本接线检查 ===")
    print(f"  门禁脚本总数   : {len(scripts)}")
    print(f"  应被 CI 调用    : {len(targets)}  (test_* 类; check_* 是判定门禁, 由 verify-e2e.sh 调)")
    print(f"  接线方式        : {'通配符批量执行' if via_wildcard else '逐个列名'}")
    print(f"  未被引用        : {len(unreferenced)}")

    if unreferenced:
        print()
        for name in unreferenced:
            print(f"FAIL: {name} 未被 .github/workflows/*.yml 引用 —— 脚本存在但从未运行")
            print("      它是一道门禁, 却没有任何调用点 —— 这是第 3 类假门禁。")
        print()
        print("      接入方式: e2e.yml 的 \"Gate self-tests\" 步骤用通配符执行, 无需改工作流;")
        print("      若该步骤缺失, 请新增它（见本脚本 docstring）。")
        return 1

    print("\nPASS: 所有 test_* 门禁脚本都被 CI 调用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
