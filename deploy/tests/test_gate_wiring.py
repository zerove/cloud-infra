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
#
# ⚠️⚠️ 2026-10-05 修正 —— 上一版把 `check_` **无条件**豁免, 只声明理由
#   ("由 verify-e2e.sh 调用")却从不验证该理由是否成立。
#   实测后果: check_commit_runtime_guard.py 与 check_sw_oap_config.sh
#   **全仓没有任何调用点**, 而本守护报 PASS —— 它连"未接线"都不报。
#
#   这正是本守护自己的注释所批判的形态: **用"代码在不在"代替"链路通没通"**
#   (AGENTS.md 第三条硬规则)。而它之所以藏得住, 正是因为两个脚本
#   **各自都有守护测试且全绿** —— 守护测试只证明"脚本能自我运转",
#   不证明"脚本被调用", 这与 M6/M7 那 11 项"建成无人使用"是同一个误判。
#
#   修正: 豁免**必须附验证**。`check_*` 只有在 verify-e2e.sh(或工作流)里
#   真的能定位到调用点时才豁免; 否则它就是一个"无人调用的门禁", 照常报红。
EXEMPT_CATEGORIES: dict[str, str] = {
    "classify_gh_run.sh": "诊断工具, 需 gh 登录与 run id, 不是门禁",
}

# `check_*` 的豁免前提: 调用方脚本。verify-e2e.sh 在**仓库根目录**,
# 不在 deploy/ 下 —— 第五课补记五的形态: 探针查错路径, grep 得到 0 次,
# 被读成"这个机制没被接上"。所以这里用 glob 定位而非硬编码路径。
CALLER_SCRIPTS = ("verify-e2e.sh", "deploy/verify-e2e.sh",
                  "deploy/up.sh", "verify-e2e-self-test.sh")


def _caller_text() -> str:
    """拼起所有可能的调用方脚本正文（缺失不算错, 只是没有调用点）。"""
    parts: list[str] = []
    for rel in CALLER_SCRIPTS:
        p = REPO / rel
        if p.is_file():
            try:
                parts.append(p.read_text(encoding="utf-8"))
            except OSError:
                continue
    return "\n".join(parts)


def is_exempt(name: str) -> str | None:
    """返回豁免理由; 不豁免则返回 None。

    `check_*` 的豁免是**有条件**的: 必须真能在调用方脚本里找到引用。
    """
    if name in EXEMPT_CATEGORIES:
        return EXEMPT_CATEGORIES[name]
    if name.startswith("check_"):
        if name in _caller_text():
            return f"判定门禁, 已被调用方脚本引用({CALLER_SCRIPTS[0]})"
        # 条件不成立 → 不豁免, 交由 main() 当作未接线报红。
        return None
    for prefix, reason in EXEMPT_CATEGORIES.items():
        if prefix.endswith("_") and name.startswith(prefix):
            return reason
    return None


def workflow_texts() -> str:
    """拼起所有工作流文件的**可执行内容**（剥离注释与 step 名字）。

    ⚠️⚠️ 2026-10-05 修正 —— 旧版返回**全文**, 于是注释里的字面量也会算数。
    实测: 删掉聚合步骤的真实执行体(第 183 行)后, 同一 step 的
    `- name: Gate self-tests (deploy/tests/test_*)`（第 165 行）仍在,
    `name not in blob` 恒为假 ⇒ 守护报 PASS。

    这是本项目已踩 6 次的"被检字面量出现在断言自身附近"(第八课补记一),
    这次形态是**注释里**。

    为什么必须过滤:
      · `#` 开头的行是注释, 不会被执行。
      · YAML 的 `- name:` 是**给人看的标签**, 不是执行内容。
        两者都可能出现通配符字面量, 而只有 `run:` 块里的才算真接线。
    """
    if not WORKFLOWS.is_dir():
        return ""
    kept: list[str] = []
    for p in sorted(WORKFLOWS.glob("*.yml")):
        try:
            lines = p.read_text(encoding="utf-8").splitlines()
        except OSError:
            continue
        for raw in lines:
            stripped = raw.strip()
            if not stripped or stripped.startswith("#"):
                continue
            # `- name: ...` / `name: ...` 是标签, 不是可执行语句。
            if stripped.startswith("- name:") or stripped.startswith("name:"):
                continue
            kept.append(stripped)
    return "\n".join(kept)


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
    """工作流是否用通配符批量执行**任意一类**门禁脚本（仅用于展示)。"""
    return any(prefix in blob for prefix in WILDCARD_PREFIXES)


def _wildcard_prefixes_for(name: str) -> tuple[str, ...]:
    """返回**会匹配到该脚本**的通配符前缀。

    ⚠️ 必须按脚本自己的前缀取, 不能返回全部 —— 工作流的聚合步骤是
    `for t in deploy/tests/test_*.sh`, 它**不会**执行 check_*.py。
    用全局开关会让 check_* 获得一个自己享受不到的豁免。
    """
    if name.startswith("test_"):
        return ("deploy/tests/test_*", "$SCRIPT_DIR/deploy/tests/test_*")
    if name.startswith("check_"):
        return ("deploy/tests/check_*", "$SCRIPT_DIR/deploy/tests/check_*")
    return WILDCARD_PREFIXES


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

    # check_* 是判定门禁（由调用方脚本调用）, 不要求 workflow 直接跑
    targets = [p for p in scripts
               if p.name != Path(__file__).name and is_exempt(p.name) is None]

    # ⚠️⚠️ 通配符必须**按前缀分别**判定, 不能用全局开关 —— 2026-10-05 实测:
    #   工作流的聚合步骤是 `for t in deploy/tests/test_*.sh`, **只匹配 test_\***。
    #   而旧代码只要 `via_wildcard` 为真, 就对**所有**脚本放行, 于是
    #   check_* 也被当作"已由通配符接线" —— 尽管通配符根本不匹配它。
    #
    #   负向对照实测: 删掉 verify-e2e.sh 里两个 check_* 的全部调用点后,
    #   旧守护仍报 PASS (`未被引用: 0`)。这正是本守护自己注释里写的
    #   「验到 2 个里的 1 个, 不等于通过」—— 只是它自己踩了同一句。
    #
    #   正确形态: 问"**这个特定脚本**有没有一种会执行到它的接线形式",
    #   而不是"是否存在某个通配符"。
    via_wildcard = wildcard_covered(blob)
    unreferenced: list[str] = []
    for path in targets:
        name = path.name
        if name in blob:
            continue
        # 该脚本所属类别的通配符(见 wildcard_prefixes_for)
        if any(pref in blob for pref in _wildcard_prefixes_for(name)):
            continue
        unreferenced.append(name)

    print("=== 门禁脚本接线检查 ===")
    print(f"  门禁脚本总数   : {len(scripts)}")
    print(f"  应被 CI 调用    : {len(targets)}  (test_* 由工作流跑; check_* 须真被调用方脚本引用)")
    print(f"  接线方式        : {'通配符批量执行' if via_wildcard else '逐个列名'}")
    print(f"  未被引用        : {len(unreferenced)}")

    if unreferenced:
        print()
        for name in unreferenced:
            # ⚠️ 报错文案本身也是探针的一部分（第五课补记四）:
            #   check_* 的接入方式与 test_* 不同, 沿用同一句会把人引向工作流,
            #   而正确位置是 verify-e2e.sh —— 上一版的文案就是这么错的。
            if name.startswith("check_"):
                print(f"FAIL: {name} 在工作流与调用方脚本里都找不到调用点 —— 脚本存在但从未运行")
                print(f"      接入方式: 在 verify-e2e.sh 里加一段调用它的函数并执行"
                      f"（参照 check_commit_budget / check_jvm_native_bounds）。")
            else:
                print(f"FAIL: {name} 未被 .github/workflows/*.yml 引用 —— 脚本存在但从未运行")
                print("      它是一道门禁, 却没有任何调用点 —— 这是第 3 类假门禁。")
        print()
        print("      test_* 的接入方式: e2e.yml 的 \"Gate self-tests\" 步骤用通配符执行, 无需改工作流;")
        print("      若该步骤缺失, 请新增它（见本脚本 docstring）。")
        return 1

    print("\nPASS: 所有 test_* 门禁脚本都被 CI 调用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
