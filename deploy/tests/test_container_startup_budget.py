#!/usr/bin/env python3
"""check_container_startup_budget.py 的守护测试。

## 为什么需要这个文件

新写的门禁**自带 4 个退出码**, 其中 `rc=2`(扫描器不可信)是它区别于
普通静态检查的关键 —— 上一版 `rc=0` 曾长期掩盖一处扫描器漏扫。

但门禁脚本本身没有守护: 它的判据被改回 `声明数 <= 预算数` 时,
仓库里没有任何东西会变红。本文件补上这一层。

## 守护的判据(与门禁一一对应)

| 注入的缺陷 | 期望 rc | 它证明门禁的哪个能力 |
| :-- | :-- | :-- |
| 删一个真实预算 | 1 | 能查出缺口 |
| 注入漏扫类型 + 无预算 | 1 | 兜底正则接得住新容器类型 |
| 注入漏扫类型 + 有预算 | 0 | 真合规时不误报 |
| 移走整个模块目录 | 2 | 扫描根未命中 != 通过 |
| 注释里注入假声明 | 0 | 注释不算声明 |

最后一条尤其重要: `strip_comments` 曾经因 DOTALL 从文件第一个 `//`
一路吃到文末, 把大段真实代码当注释删掉 —— 那次症状是"所有文件都缺预算",
看起来像扫描器严格, 实际是它把自己吃掉了。

## 运行

    python3 deploy/tests/test_container_startup_budget.py

每个用例注入缺陷后**立即还原**, 且断言"注入真的生效了":
门禁的扫描根 `ROOT = Path(__file__).parents[2]` 写死在脚本里、无环境变量开关,
所以守护只能改工作区文件(不像 `verify-e2e.sh` 那样能用 `COMPOSE_FILE` 覆盖)。

正因如此, "用完即还原"是硬要求 —— 漏还原一次, 后续用例就跑在被改过的内容上,
本次开发中就发生过一次: 同一批注入里前一个用例没还原,
后一个用例的结果无法解释(AGENTS.md 第八课补记一)。

另外 `edit()` 在锚点找不到时**直接抛 AssertionError** 而非静默跳过:
`sed`/`replace` 匹配不上不报错, 会让"注入没生效"被读成"守护有效"。
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
GATE = REPO / "deploy/tests/check_container_startup_budget.py"
SEARCH_TEST = (
    REPO
    / "cloud-infra-modules/search-service/src/test/java"
    / "com/cloudinfra/search/SearchServiceIntegrationTest.java"
)

PASS, FAIL, SCAN_FAIL = 0, 1, 2


def run_gate() -> tuple[int, str]:
    proc = subprocess.run(
        [sys.executable, str(GATE)], capture_output=True, text=True, cwd=REPO
    )
    return proc.returncode, proc.stdout + proc.stderr


class Worktree:
    """在副本上注入缺陷, 退出时无条件还原。

    门禁的扫描根写死在脚本里(ROOT = parents[2]), 没有环境变量开关,
    所以守护只能改工作区文件 —— 这也让"用完即还原"成为硬要求:
    漏还原一次, 后续用例就跑在被改过的内容上(第八课补记一)。
    """

    def __init__(self) -> None:
        self._saved: dict[Path, str] = {}

    def edit(self, path: Path, old: str, new: str) -> None:
        if path not in self._saved:
            self._saved[path] = path.read_text(encoding="utf-8")
        src = path.read_text(encoding="utf-8")
        if old not in src:
            raise AssertionError(f"注入前置条件缺失: {path.name} 中找不到 {old!r}")
        path.write_text(src.replace(old, new, 1), encoding="utf-8")

    def add(self, path: Path, text: str, before: str) -> None:
        if path not in self._saved:
            self._saved[path] = path.read_text(encoding="utf-8")
        src = path.read_text(encoding="utf-8")
        if before not in src:
            raise AssertionError(f"注入锚点缺失: {path.name} 中找不到 {before!r}")
        path.write_text(src.replace(before, text + before, 1), encoding="utf-8")

    def __enter__(self) -> "Worktree":
        return self

    def __exit__(self, *_exc: object) -> None:
        for path, content in self._saved.items():
            path.write_text(content, encoding="utf-8")
        self._saved.clear()


def check(name: str, actual: int, expected: int, detail: str = "") -> bool:
    ok = actual == expected
    print(f"  {'✓' if ok else '✗'} {name}: rc={actual} (期望 {expected})")
    if not ok and detail:
        print(f"      {detail}")
    return ok


def main() -> int:
    results: list[bool] = []

    print("=== 基线 ===")
    rc, out = run_gate()
    results.append(check("未注入时门禁通过", rc, PASS, out[-500:]))
    # 基线本身就自相矛盾 → 扫描器不可信, 后面的用例全部无意义
    if "声明总数" in out and "预算总数" in out:
        decls = out.split("容器声明总数:")[1].split()[0]
        buds = out.split("显式预算总数:")[1].split()[0]
        if decls != buds:
            print(f"  ✗ 基线声明数({decls}) != 预算数({buds}) —— 扫描器已不可信, 中止")
            return 1

    print("\n=== 用例 1: 删一个真实预算 → 应报缺口 ===")
    with Worktree() as w:
        w.edit(SEARCH_TEST, ".withStartupTimeout(Duration.ofMinutes(2));", "")
        rc, out = run_gate()
        results.append(check("缺口被查出", rc, FAIL, out[-500:]))

    print("\n=== 用例 2: 注入漏扫类型且无预算 → 应报缺口 ===")
    injected_no_budget = '''    @Container
    static ScopedMockContainer<?> guardExtra = new ScopedMockContainer<>("mock:1")
            .withExposedPorts(1);

'''
    with Worktree() as w:
        w.add(SEARCH_TEST, injected_no_budget, "    @DynamicPropertySource")
        rc, out = run_gate()
        results.append(check("新容器类型缺预算被查出", rc, FAIL, out[-500:]))

    print("\n=== 用例 3: 注入漏扫类型但有预算 → 真合规, 不误报 ===")
    injected_with_budget = '''    @Container
    static ScopedMockContainer<?> guardExtra = new ScopedMockContainer<>("mock:1")
            .withExposedPorts(1)
            .withStartupTimeout(Duration.ofMinutes(2));

'''
    with Worktree() as w:
        w.add(SEARCH_TEST, injected_with_budget, "    @DynamicPropertySource")
        rc, out = run_gate()
        results.append(check("真合规不误报", rc, PASS, out[-500:]))

    print("\n=== 用例 4: 注释里的容器声明不算数 → 仍通过 ===")
    fake = """    // new GenericContainer<>("comment-only:1") 无预算, 但这是注释
    // new PostgreSQLContainer("comment-only:2") 同样无预算
"""
    with Worktree() as w:
        w.add(SEARCH_TEST, fake, "    @DynamicPropertySource")
        rc, out = run_gate()
        results.append(check("注释声明被正确忽略", rc, PASS, out[-500:]))

    print("\n=== 用例 5: 移走整个模块目录 → 扫描根未命中, 必须 rc=2 ===")
    moved: Path | None = None
    target = REPO / "cloud-infra-codegen"
    tmp = Path(tempfile.mkdtemp(prefix="gate-guard-"))
    try:
        moved = tmp / "cloud-infra-codegen"
        shutil.move(str(target), str(moved))
        rc, out = run_gate()
        results.append(check("扫描根未命中被判失败", rc, SCAN_FAIL, out[-500:]))
    finally:
        if moved is not None and moved.exists():
            shutil.move(str(moved), str(target))
        shutil.rmtree(tmp, ignore_errors=True)
        # 还原后必须仍是绿的 —— 否则"通过"可能只是没跑完
        rc_after, out_after = run_gate()
        results.append(check("还原后恢复通过", rc_after, PASS, out_after[-500:]))

    print(f"\n{'=' * 50}")
    passed = sum(results)
    print(f"{passed}/{len(results)} 项通过")
    if passed != len(results):
        print("守护测试失败 —— 门禁可能已失效", file=sys.stderr)
        return 1
    print("守护有效: 上述每种缺陷注入都会让门禁变红")
    return 0


if __name__ == "__main__":
    sys.exit(main())