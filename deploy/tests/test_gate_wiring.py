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
    """拼起所有可能的调用方脚本正文（缺失不算错, 只是没有调用点）。

    ⚠️ 2026-10-06 修正 —— 必须剥离**注释行**, 否则被注释掉的调用仍算接线。
    实测: 把 `check_native_alloc_evidence || true` 注释掉后,
    本守护仍报 PASS —— 因为函数定义那一行仍然包含该名字。

    这与 workflow_texts() 2026-10-05 的修正是**同一个缺陷的两处**:
    那边修的是工作流(剥离 YAML 注释), 这边漏了调用方脚本(剥离 shell 注释)。
    只修一处 ⇒ 假阴性从工作流搬到了 shell。

    ⚠️ 剥离注释用的是「行首 #」判据, 存在已知局限: 被注释掉的**多行**片段
    中若后续行不以 # 开头(heredoc / 续行), 仍可能被误算作接线。
    本次实测覆盖的是「整行注释」这一形态, 也是本项目实际用过的形态。
    """
    parts: list[str] = []
    for rel in CALLER_SCRIPTS:
        p = REPO / rel
        if p.is_file():
            try:
                text = p.read_text(encoding="utf-8")
            except OSError:
                continue
            parts.append(_strip_shell_comments(text))
    return "\n".join(parts)


def _strip_shell_comments(text: str) -> str:
    """剥掉 shell 注释行, 只保留可执行内容。

    刻意**不**做「引号感知」的解析: 那会把 here-doc 里的 `#` 也当注释,
    反而制造新的假阴性。本守护只需要判「有没有被注释掉的调用点」,
    行首 `#` 判据对实际用法足够, 且不会误伤。
    """
    kept: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        # 注释行 / 纯注释(可能带 shebang) / 空行直接丢。
        if stripped.startswith("#"):
            continue
        kept.append(line)
    return "\n".join(kept)


def _is_invoked(name: str, caller: str) -> bool:
    """该门禁是否被**真实调用**（而非仅仅被提到）。

    ⚠️ 2026-10-06 修正 —— 「名字出现在文件里」不等于「被调用」。实测:
    把 `check_native_alloc_evidence || true` 注释掉后, 旧判据仍报 PASS,
    因为该名字还出现在两处**非调用点**:

        1011: check_native_alloc_evidence() {                     <- 函数**定义**
        1013:   out=$(python3 ".../check_native_alloc_evidence.py")  <- 函数体内的路径

    所以光剔除注释行**不够** —— 函数定义与函数体让名字必然存在。
    这与本项目已踩多次的形态同源: 判据必须指向**被检对象本身**,
    而「这个名字在文件里」指向的是「文本中存在」, 不是「被执行」。

    判据: 必须存在一行, 它**调用**这个名字且**不是**定义。
    即: 名字后面**不能**紧跟 `(`（定义形式 `name() {`）, 且该名字必须
    站在**命令位置**（行首, 或 `&&`/`||`/`;`/`|`/`$(` 之后）。

    ⚠️ 第二版反面教材（同日）: 第一版用「名字前引号计数奇偶」判断是否在引号内,
    结果**误伤了 4 个真实接线**。原因是判据取的名字带扩展名
    (`check_commit_budget.py`), 而真实调用形如:

        out=$(python3 "$SCRIPT_DIR/deploy/tests/check_commit_budget.py" 2>&1)

    名字前紧邻的是 `"`, 但那引号属于**外层 $()** 与路径字符串, 名字本身站在
    **闭引号之后** —— 引号奇偶判定在这里没有任何信息量, 纯属噪声。

    正解: 不猜引号状态, 只问「这个名字是被当作**参数**传给了某个执行器」。
    真实的调用形态统一是 `<解释器> "<路径>/<名字>"`, 即名字永远出现在
    **路径末尾**（紧邻扩展名）而前面是 `/`。

    ⚠️ 第三版反面教材（同日, 两次）:
      · 第一版用引号奇偶 → 误伤 4 个真实接线;
      · 第二版要求名字站在命令位置（行首 / `&&` 之后）→ **一个都判不出**,
        因为真实形态是 `python3 "$DIR/check_xxx.py"` —— 名字前面是 `/`。
    两次都是**把判据建在"看起来像调用"的语法特征上**, 而不是问
    「这个名字有没有被交给执行器」。语法特征会随写法变, 是否执行不会。

    故最终判据 —— **先看有没有同名 wrapper, 再决定用哪个判据**:

      若存在 wrapper (`name() {`):
        必须**该 wrapper 真被调用**。wrapper 函数体里那句
        `python3 ".../name.py"` 只是**函数体**, wrapper 没被调用时
        它根本不执行 —— 这正是本次负向对照①（注释掉调用点）曾漏过的形态。
      若不存在 wrapper:
        直接要求该门禁的**路径**出现在非注释行。
        实测 10 个 check_* 门禁里有 2 个
        (`check_jvm_native_memory_bounds.py` / `check_jvm_opts_keys_match_image.py`)
        属这一类 —— 被 `check_jvm_native_bounds` 这类**不同名**函数间接调用。

    ⚠️ 为什么不能用「A 或 B」: 两分支是**互斥择一**而非并存。
    早期版本写成 `if A: return True` 再 `return B`, 于是注释掉调用点后,
    A 分支在 wrapper 函数体里找到了那句 `python3 ".../name.py"` 而返回 True,
    守护报 PASS —— 函数体存在 ≠ 函数被调用（第三类假门禁的又一形态）。
    """
    stem = name.rsplit(".", 1)[0]
    if _has_wrapper_definition(stem, caller):
        return _called_outside_definition(stem, caller)
    return _referenced_outside_definition(name, caller)


def _has_wrapper_definition(stem: str, caller: str) -> bool:
    """是否存在 `name() {` 形式的 wrapper 定义。"""
    pattern = re.compile(r"^\s*" + re.escape(stem) + r"\s*\(\)\s*\{", re.M)
    return bool(pattern.search(caller))


def _referenced_outside_definition(name: str, caller: str) -> bool:
    """名字是否在非注释行里以**非函数定义**的形态出现过。

    ⚠️ 必须同时剔除**行尾注释**: 实测负向对照③里,
    往 `if ! { ...; check_commit_budget; }; then` 中间插一个 `#` 会得到

        if ! # { echo "..."; check_commit_budget; }; then

    这行**不是**注释行(行首没有 #), 但 `#` 之后全是注释 ——
    若只看行首, 名字仍被判成「在命令位置」, 守护于是报 PASS。
    即「行首 #」是**不完整**的注释判据, 与本项目已记录的
    「被检字面量出现在断言自身附近」同族。

    做法: 取第一个 `#` 之后的内容(不处理引号内的 #, 见下方说明)。
    引号内的 `#` 误伤风险已知但可接受: 真实调用行里引号内几乎不会出现
    「`#` 之后还有门禁名」这种形态, 而误伤方向是**判为未接线**(安全侧)。
    """
    for raw in caller.splitlines():
        line = _strip_trailing_comment(raw).strip()
        if not line or line.startswith("#"):
            continue
        start = 0
        while True:
            pos = line.find(name, start)
            if pos < 0:
                break
            start = pos + 1
            if line[pos + len(name):].lstrip().startswith("("):
                continue  # 函数定义形式 name() {
            return True
    return False


def _strip_trailing_comment(line: str) -> str:
    """去掉 `#` 及其之后的内容。

    ⚠️ 刻意不做引号感知: 代价是 `echo "a # b"` 会被截成 `echo "a `,
    但本判据只关心「门禁名出现在可执行部分」, 而门禁名不会被写在
    纯注释性字符串里。误伤方向为**保守**(判为未接线), 可接受。
    """
    idx = line.find("#")
    return line if idx < 0 else line[:idx]


def _called_outside_definition(stem: str, caller: str) -> bool:
    """同名 wrapper 是否真被调用（`name() {` 与 `name` 直接跟 `(` 都不算）。

    命令位置判据: 名字前要么是行首, 要么是 `;` `&&` `||` `|` `!` `(` 等分隔符。
    实测真实形态: `check_commit_budget || true`、`if ! check_commit_runtime_guard`。
    """
    marks = (";", "&&", "||", "|", "!", "(", "{", "}", "\t")
    for raw in caller.splitlines():
        line = _strip_trailing_comment(raw).strip()
        if not line or line.startswith("#"):
            continue
        start = 0
        while True:
            pos = line.find(stem, start)
            if pos < 0:
                break
            start = pos + 1
            if line[pos + len(stem):].lstrip().startswith("("):
                continue  # 定义 name() {  或  name(...)
            before = line[:pos].rstrip()
            if before == "" or before.endswith(marks):
                return True
    return False


def is_exempt(name: str) -> str | None:
    """返回豁免理由; 不豁免则返回 None。

    `check_*` 的豁免是**有条件**的: 必须真能在调用方脚本里找到调用点。
    """
    if name in EXEMPT_CATEGORIES:
        return EXEMPT_CATEGORIES[name]
    if name.startswith("check_"):
        if _is_invoked(name, _caller_text()):
            return f"判定门禁, 已被调用方脚本调用({CALLER_SCRIPTS[0]})"
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
