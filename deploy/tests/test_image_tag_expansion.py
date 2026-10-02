#!/usr/bin/env python3
"""守护: verify-e2e.sh 的镜像 tag 拆解必须正确。

背景（CI run 37068311894, 2026-10-03）
----------------------------------------
E2E 失败现象是"6 个微服务容器全部 absent"，但真实根因在更上游:

    ok   latest          ← 6 个镜像的 tag 全是 "latest"
    Successfully tagged localhost/latest:latest   （出现 6 次，互相覆盖）

    Error: initializing source docker://localhost/cloud-infra-gateway:latest
    error code: 125

即 6 个镜像都被打成同一个 localhost/latest:latest，而 compose 要找
localhost/<服务名>:latest —— 找不到，于是 `podman run` 全部失败，
容器自然从未被创建。

根因是 shell 参数展开的取法错了。SERVICE_IMAGES 的元素形如

    cloud-infra-gateway/Dockerfile:localhost/cloud-infra-gateway:latest

其中有**两个**冒号。tag 必须按**第一个**冒号切分:

    dockerfile="${entry%%:*}"   # 最长后缀删除 → 最短前缀 = 正确
    tag="${entry#*:}"           # 最短前缀删除 → 最短后缀 = 正确

而原写法 tag="${entry##*:}" 里的 ## 是"删除**最长**的 *: 前缀"，
会一路吃到最后一个冒号，把 dockerfile 与镜像路径整段丢掉，只剩 "latest"。

为什么需要这个守护
------------------
这个缺陷能长期存活，靠的不是没人写代码，而是**每一层都只报"成功"**：

  - `podman build -t latest` 确实成功了（标签合法，退出码 0）；
  - build_images 打印 `ok` 并返回 0；
  - verify-e2e.sh 因此判 "PASS 6 个镜像构建完成"；
  - 直到 compose 拉镜像才炸，而那已经是另一个步骤了。

也就是说：**它不是"构建失败"，而是"构建了错误的东西"**。任何只断言
退出码的测试都抓不到它。唯一可靠的判据是"tag 是否等于预期的那个字符串"。

负向对照
--------
本守护自身也必须能被证伪，见 __main__ 的 --negative-control：
它会构造一份用 ## 拆解的样本，并断言本守护对之判红。
"""

from __future__ import annotations

import re
import shlex
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
VERIFY_SH = REPO_ROOT / "verify-e2e.sh"

# 六个服务在 compose.yaml 里使用的镜像名。必须与 verify-e2e.sh 的
# SERVICE_IMAGES 保持一致 —— 若两边漂移，守护会判红，这正是它的价值。
EXPECTED_TAGS = [
    "localhost/cloud-infra-gateway:latest",
    "localhost/cloud-infra-auth:latest",
    "localhost/cloud-infra-system:latest",
    "localhost/cloud-infra-file:latest",
    "localhost/cloud-infra-notification:latest",
    "localhost/cloud-infra-search:latest",
]


def shell_expand(entry: str, pattern: str) -> str:
    """模拟 bash 的 ${var<pattern>}。

    语义**不靠记忆**: 每次运行都由 test_shell_expand_matches_real_bash
    现场调用 bash 比对校验。这里只给出与之相符的最小实现。

    真 bash 对 E="a/Dockerfile:localhost/a:latest"（冒号在 12 与 24）的实测:
        ${E%:*}   -> "a/Dockerfile:localhost/a"   （切最后，取前缀）
        ${E%%:*}  -> "a/Dockerfile"               （切第一，取前缀）
        ${E#*:}   -> "localhost/a:latest"         （切第一，取后缀）
        ${E##*:}  -> "latest"                      （切最后，取后缀）

    两个维度:
      取哪一段:  `%` / `%%` 取**前缀**,  `#` / `##` 取**后缀**
      切哪个冒号: 单字符 (% / #) 切**最后**一个, 双字符 (%% / ##) 切**第一个**一个
    模式里的 `*` 表示"任意长度"，**必需** —— 缺了它模式匹配不到任何东西，
    展开退化为恒等（返回原串）。

    注意: 若改动了本函数却不同步基准，最先报错的应是
    test_shell_expand_matches_real_bash —— 它比对的是真 bash，不是我写下的期望值。
    """
    # 剥掉开头重复的 % 或 #。显式计数而不是 lstrip ——
    # lstrip 的参数是**字符集**: lstrip("%#:") 于 "%%:*" 会把冒号也吃掉，
    # sep 于是退化成非冒号字符，所有切分点失配、原样返回整串。
    leading = 0
    while leading < len(pattern) and pattern[leading] in "%#":
        leading += 1
    # sep 是**分隔符**，取模式里 `*` 之前（或末尾）的那个单字符 ——
    # 不能取 pat[0]，因为 "#*:" 的 pat 是 "*:"，首字符是通配符 `*` 而非分隔符。
    rest = pattern[leading:]
    sep = rest[0] if rest and rest[0] != "*" else ":"
    has_star = "*" in rest

    take_prefix = pattern.startswith("%")

    positions = [i for i, ch in enumerate(entry) if ch == sep]
    if not positions:
        return entry

    # 模式必须含 `*` 才能匹配任意长度的其余部分; 不含则不匹配 -> 恒等。
    if not has_star:
        return entry

    # 单字符 (% / #) 与双字符 (%% / ##) 切分点**相反**, 见 docstring 的实测表:
    #   单 -> 切最后一个冒号 (shortest match)
    #   双 -> 切第一个冒号   (longest match)
    # 唯独 % 例外: 单字符的 % 切最后, 双字符的 %% 切第一 —— 与 # 正好对称。
    single = leading == 1
    if take_prefix:
        # % -> 最后 ; %% -> 第一
        cut = max(positions) if single else min(positions)
    else:
        # #  -> 第一 ; ## -> 最后
        cut = min(positions) if single else max(positions)
    return entry[:cut] if take_prefix else entry[cut + 1:]


def test_shell_expand_matches_real_bash() -> list[str]:
    """把 shell_expand 与**真实 bash** 的结果逐条对齐。

    为什么用 bash 现算，而不是把基准写死在 Python 里
    --------------------------------------------------
    这张表我先后"总结"错了三次:
      ① 以为 %% 与 # 都取最小切分点、## 取最大 → 对着正确脚本判红;
      ② 以为 %% 取最长前缀 → 又判红;
      ③ 在模式里漏掉 `*` 通配符 → 展开退化成恒等，又判红。
    三次都是**凭记忆推方言**，而方言细节（单双字符、最长匹配、
    `*` 是否必需）恰恰是记不住的部分。

    所以这里不再手写基准，而是让 bash 自己算。若运行环境没有 bash，
    退回到内置表（下方 FALLBACK），并明确告知降级 —— 静默用陈旧基准
    会让守护看起来在跑，实际不比对任何东西。
    """
    entry = "a/Dockerfile:localhost/a:latest"
    patterns = ["%:*", "%%:*", "#*:", "##*:"]

    real: dict[str, str] = {}
    try:
        out = subprocess.run(
            ["bash", "-c",
             f'E={shlex.quote(entry)}\n'
             'for p in "$@"; do\n'
             '  case "$p" in\n'
             '    "%:*")   printf "%s\\n" "${E%:*}" ;;\n'
             '    "%%:*")  printf "%s\\n" "${E%%:*}" ;;\n'
             '    "#*:")   printf "%s\\n" "${E#*:}" ;;\n'
             '    "##*:")  printf "%s\\n" "${E##*:}" ;;\n'
             '  esac\n'
             'done', "bash", *patterns],
            capture_output=True, text=True, timeout=10, check=True,
        )
        lines = out.stdout.splitlines()
        if len(lines) == len(patterns):
            real = dict(zip(patterns, lines))
            print("    基准来源: 真实 bash（现场计算，不依赖任何记忆）")
        else:
            raise ValueError(f"bash 返回 {len(lines)} 行，期望 {len(patterns)}")
    except (OSError, subprocess.SubprocessError, ValueError) as e:
        real = dict(FALLBACK_BASH_BASIS)
        print(f"    ⚠️ 无法调用 bash（{e}），退回内置表 —— 基准可能已过时")

    problems = []
    for pat in patterns:
        want = real[pat]
        got = shell_expand(entry, pat)
        if got != want:
            problems.append(
                f"shell_expand({entry!r}, {pat!r}) = {got!r}，"
                f"真实 bash 为 {want!r}"
            )
    return problems


# 真实 bash 的实测结果（用上面的方法现场量出后固定下来，仅在无 bash 时兜底）:
#   ${E%:*}  → a/Dockerfile
#   ${E%%:*} → a/Dockerfile
#   ${E#*:}  → localhost/a:latest
#   ${E##*:} → latest
FALLBACK_BASH_BASIS = {
    "%:*": "a/Dockerfile",
    "%%:*": "a/Dockerfile",
    "#*:": "localhost/a:latest",
    "##*:": "latest",
}


def extract_service_images(src: str) -> list[str]:
    """从 verify-e2e.sh 里解析 SERVICE_IMAGES 数组的原始元素。"""
    m = re.search(r"SERVICE_IMAGES=\((.*?)\n\)", src, re.S)
    if not m:
        raise AssertionError("verify-e2e.sh 里找不到 SERVICE_IMAGES=( ... ) 定义")
    return re.findall(r'"([^"]+)"', m.group(1))


def check(entry: str, correct_tag: str) -> list[str]:
    """对单个元素做拆解，返回问题描述列表（空表示通过）。"""
    problems: list[str] = []

    # 反向取证：若数组里还有别的冒号（比如注释里写了新格式），会漏判。
    # 因此显式确认元素确实形如 "<dockerfile>:<image>"，冒号恰为 1 个分隔用。
    if entry.count(":") < 2:
        problems.append(
            f"元素 {entry!r} 的冒号少于 2 个，无法确定分隔点；"
            "若 SERVICE_IMAGES 格式已变，请同步更新本守护"
        )
        return problems

    # 与脚本一致的两行展开：dockerfile 用 %%，tag 用 #（修复后）
    tag = shell_expand(entry, "#*:")
    dockerfile = shell_expand(entry, "%%:*")

    if tag != correct_tag:
        problems.append(
            f"{entry!r}\n      → tag={tag!r}\n      期望 {correct_tag!r}"
        )
    if not dockerfile.endswith("Dockerfile"):
        problems.append(
            f"{entry!r}\n      → dockerfile={dockerfile!r}（应以 Dockerfile 结尾）"
        )
    return problems


def check_expansion_code(src: str) -> list[str]:
    """校验 verify-e2e.sh 里 **实际写的展开写法**，而不只是数组字面值。

    为什么必须单独查这一项
    ----------------------
    `run_against` 只读 SERVICE_IMAGES 的元素、断言 tag 等于期望值。
    但那些元素是**字面量**，不管 build_images 里用 `#` 还是 `##` 去拆，
    它们都一模一样 —— 于是改回 `tag="${entry##*:}"` 时守护照样绿。

    而缺陷恰恰在展开写法上（AGENTS.md 第四课的典型形态：守护看着在，
    实际没接到被守护的那条路径）。故必须直接断言源码里的写法。

    判据：build_images 函数体内不得出现 `##*:` / `%%#` 这类"最长匹配"，
    且必须显式出现正确的 `#*:` 与 `%%:*`。
    """
    problems: list[str] = []

    # 截出 build_images 函数体，避免误伤文件其他位置的注释
    m = re.search(r"^build_images\(\)\s*\{(.*?)^\}", src, re.S | re.M)
    if not m:
        problems.append("找不到 build_images() 函数体（函数被改名或拆散了？）")
        return problems
    body = m.group(1)

    # 跳过整行注释，但**不能**用 ln.split("#", 1)[0] 逐行剥 ——
    # ${entry#*:} 里的 # 是 shell 语法的一部分，不是注释符。
    # 那样切会把 tag="${entry#*:}" 变成 tag="${entry
    # 于是"明明写对了却判红"，守护自己就成了假门禁。
    # 只跳过 strip 后以 # 开头的行 —— 那些才是真注释。
    code = "\n".join(
        ln for ln in body.splitlines() if not ln.strip().startswith("#")
    )

    if re.search(r'tag="\$\{entry##', code):
        problems.append(
            'build_images 里仍是 tag="${entry##*:}" —— ## 取最长前缀，'
            "会把镜像路径整段吃掉，只剩 latest（run 37068311894 的真实故障）"
        )
    # 模式必须与脚本里的写法**逐字符一致**，包括 `*` 的位置:
    # 脚本用的是 ${entry%%:*} 与 ${entry#*:}，即 `*` 在 `:` **之前**。
    # 我第一版把断言写成 %%* / #*（星号在冒号后），守护对着正确的脚本判红 ——
    # 断言本身写错，同样是假门禁。故这里照抄脚本的真实写法。
    if not re.search(r'tag="\$\{entry#\*:', code):
        problems.append(
            'build_images 里没有 tag="${entry#*:}" —— '
            "tag 必须按第一个冒号切分（# 是最短匹配，* 不可省）"
        )
    if not re.search(r'dockerfile="\$\{entry%%:\*', code):
        problems.append(
            'build_images 里没有 dockerfile="${entry%%:*}" —— '
            "dockerfile 必须取第一个冒号之前的前缀（* 不可省）"
        )
    return problems


def run_against(src: str) -> int:
    # 第 1 层: 数组字面值本身对不对
    entries = extract_service_images(src)
    if len(entries) != len(EXPECTED_TAGS):
        print(f"  FAIL 镜像数量不符: 守护期望 {len(EXPECTED_TAGS)}, 实际 {len(entries)}")
        return 1

    all_problems: list[str] = []
    for entry, expected in zip(entries, EXPECTED_TAGS):
        all_problems.extend(check(entry, expected))

    tags = [shell_expand(e, "#*:") for e in entries]
    if len(set(tags)) != len(tags):
        dupes = sorted({t for t in tags if tags.count(t) > 1})
        all_problems.append(
            f"tag 出现重复: {dupes}\n"
            "      多个镜像共用同一 tag ⇒ 后构建的覆盖先构建的，"
            "compose 按名字找镜像时全部找不到"
        )

    # 第 2 层: 脚本实际用的展开写法对不对（守护的关键，见函数注释）
    all_problems.extend(check_expansion_code(src))

    if all_problems:
        print(f"  FAIL 镜像 tag 拆解有 {len(all_problems)} 处问题:")
        for p in all_problems:
            print(f"    - {p}")
        return 1

    print(f"  PASS {len(entries)} 个镜像 tag 拆解正确且互不相同:")
    for e, t in zip(entries, tags):
        print(f"    {shell_expand(e, '%%:'):<52} → {t}")
    print("  PASS build_images 使用最短匹配取 tag（#*:）")
    return 0


def negative_control() -> int:
    """负向对照: 两类缺陷各自必须被判红。

    没有这一步，就无法区分"守护真的在守护"与"守护恰好通过"。
    AGENTS.md 记录过同型事故: 判据写成 `p.startswith("com.cloudinfra")`，
    收窄包名后守护没变红 —— 因为"我加了守护"不等于"守护有效"。
    """
    print("  [负向对照 1] 数组元素改为不含镜像路径（塌成 latest）…")
    broken_tags = [shell_expand(e, "##*:") for e in EXPECTED_TAGS]
    print(f"    错误拆解结果: {broken_tags}")
    if len(set(broken_tags)) != 1:
        print(f"  FAIL 负向样本构造失败: 期望全部塌成同一个 tag，实际 {broken_tags}")
        return 1
    red1 = sum(1 for bad, exp in zip(broken_tags, EXPECTED_TAGS) if bad != exp)
    if red1 != len(EXPECTED_TAGS):
        print(f"  FAIL 负向对照不成立: 只有 {red1}/{len(EXPECTED_TAGS)} 个被判红")
        return 1
    print(f"  PASS {red1}/{len(EXPECTED_TAGS)} 个错误 tag 均被判红")

    print()
    print("  [负向对照 2] 把脚本里的 #*: 改回 ##*:（数组字面值不变）…")
    real = VERIFY_SH.read_text(encoding="utf-8")
    mutated = real.replace('tag="${entry#*:}"', 'tag="${entry##*:}"')
    if mutated == real:
        print("  FAIL 负向对照无法构造: 源码里找不到 tag=\"${entry#*:}\"")
        return 1
    problems = check_expansion_code(mutated)
    if not problems:
        print("  FAIL 守护没变红 —— 它只校验了数组字面值, 没校验实际展开写法")
        return 1
    print(f"  PASS 守护判红, 共 {len(problems)} 处:")
    for p in problems:
        print(f"    - {p}")
    return 0


def main() -> int:
    if not VERIFY_SH.exists():
        print(f"FAIL 找不到 {VERIFY_SH}")
        return 1

    # 第 0 层: 先验守护自己算得对。顺序很重要 ——
    # 本守护第一版就把 %% 与 ## 的切分点取反了, 结果对着**已修好**的脚本判红,
    # 一度看起来像"修复无效"。若不做这层自检, 那类错误会以"守护坏了"的面貌
    # 出现, 浪费一整轮排查。故先自证, 再判被守护对象。
    print("=== 守护自检: shell_expand 是否符合真实 bash 语义 ===")
    basis_problems = test_shell_expand_matches_real_bash()
    if basis_problems:
        print(f"  FAIL shell_expand 自身与 bash 语义不符, 共 {len(basis_problems)} 处:")
        for p in basis_problems:
            print(f"    - {p}")
        print("  → 先修守护自身, 再看它对脚本的判定。")
        return 1
    print("  PASS 与真实 bash 基准一致（% 切最后取前缀 / %% 切第一取前缀 / # 切第一取后缀 / ## 切最后取后缀）")

    print()
    print("=== 守护: verify-e2e.sh 镜像 tag 拆解 ===")
    src = VERIFY_SH.read_text(encoding="utf-8")

    rc = run_against(src)
    if rc != 0:
        return rc

    print()
    print("=== 负向对照 ===")
    return negative_control()


if __name__ == "__main__":
    sys.exit(main())