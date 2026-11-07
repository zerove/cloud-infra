#!/usr/bin/env python3
"""守护: jacoco 覆盖率门禁必须真的在 `mvn test` 里执行并生效。

## 背景（本仓库真实发生过的两处漂移, 2026-10-18 修复）

### 漂移一: report/check 绑错阶段

7 个模块的 jacoco `report` + `coverage-check` 长期绑在 `<phase>verify</phase>`,
而 CI 覆盖率门禁步骤跑的是 `mvn test` —— mvn test 到 test 阶段就停,
根本走不到 verify, report/check 从不执行。

### 漂移二: agent 从未被挂上(阶段修好后**仍然**静默跳过)

把阶段改到 `test` 后实测**仍不生效**。`mvn -o test` 输出:

    --- jacoco:0.8.15:report (report) ---
    [INFO] Skipping JaCoCo execution due to missing execution data file.
    --- jacoco:0.8.15:check (coverage-check) ---
    [INFO] Skipping JaCoCo execution due to missing execution data file:.../target/jacoco.exec
    [INFO] BUILD SUCCESS

根因: 根 pom 给 surefire 配了**字面** `<argLine>`(byte-buddy agent), 没有
`@{argLine}` 延迟替换 → 覆盖 jacoco prepare-agent 写入的 `argLine` 属性
→ JVM 没挂 jacoco agent → 不生成 target/jacoco.exec
→ report/check "静默跳过"**并返回成功**。

这是比"阶段绑错"更隐蔽的假门禁: 阶段对了、配置看着齐全、构建成功、
日志里 report/check 都"执行"了 —— 唯独阈值从未被评估。
与 AGENTS.md 第三课同型: 机制存在 ≠ 机制在运行。

## 判据

1. 全仓 pom.xml(跳过 target/)中, 凡 jacoco goal 为 `report`/`check` 的 execution,
   `phase` 必须精确等于 `test`(缺 phase 默认绑 verify; 早于 test 会读到空数据;
   晚于 test 则 mvn test 走不到)。
2. 同 pom 内若有 report/check, 必须另有 `prepare-agent` execution(缺它则无 jacoco.exec)。
3. **agent 挂载**: 若某 pom 显式配置了 maven-surefire-plugin 的 `<argLine>`,
   其中必须含 `@{argLine}` 延迟替换标记 —— 否则覆盖 jacoco 属性, 门禁静默跳过。
   (surefire 未显式配置 argLine 时其默认值即 `${argLine}`, 能正确引回, 不报红。)
   failsafe **不在**本判据内: 覆盖率门禁(report/check)绑在 test 阶段, 只消费 surefire
   的 exec; IT 覆盖率不在门禁范围内, 故 failsafe 的 argLine 保持原样(最小改动)。
4. 若配置里引用了 `@{argLine}`, 根 pom 必须定义 `argLine` 属性默认值,
   否则未声明 jacoco 的模块会拿到字面量 `@{argLine}` 传给 JVM 而启动失败。
5. 扫描器 sanity: 全仓扫不到任何 report/check execution 一律判红(判据失效 != 通过)。

只解析 `build/plugins` 与各 `profile` 下的 `build/plugins`; `pluginManagement` 只是版本管理,
其中的 execution 不会被执行, 故跳过。

负向对照: --negative-control 在副本 pom 上制造 4 类漂移
(错误阶段 / 缺 phase / 缺 prepare-agent / surefire argLine 缺 `@{argLine}`),
四类都必须报红, 且先重解析自校验变异确实改变了被守护的 XML 事实。
"""

from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GATED_GOALS = ("report", "check")
EXPECTED_PHASE = "test"
SUREFIRE = "maven-surefire-plugin"
LATE_TOKEN = "@{argLine}"


def _localname(tag: str) -> str:
    """剥离 XML namespace 后的标签名(兼容带/不带默认命名空间的 pom)。"""
    return tag.rsplit("}", 1)[-1]


def _children(elem: ET.Element, name: str) -> list[ET.Element]:
    return [c for c in elem if _localname(c.tag) == name]


def _child(elem: ET.Element | None, name: str) -> ET.Element | None:
    if elem is None:
        return None
    for c in elem:
        if _localname(c.tag) == name:
            return c
    return None


def _plugin_parents(root: ET.Element) -> list[ET.Element]:
    """收集会真正执行的 <plugins> 容器: build/plugins + profiles/*/build/plugins。

    刻意不取 pluginManagement/plugins —— 那里只做版本与默认配置管理。
    """
    parents: list[ET.Element] = []
    plugins = _child(_child(root, "build"), "plugins")
    if plugins is not None:
        parents.append(plugins)
    profiles = _child(root, "profiles")
    if profiles is not None:
        for profile in _children(profiles, "profile"):
            pplugins = _child(_child(profile, "build"), "plugins")
            if pplugins is not None:
                parents.append(pplugins)
    return parents


def jacoco_executions(pom_path: Path) -> list[dict]:
    """返回该 pom 里 jacoco 的 execution 列表: {id, phase, goals}。"""
    root = ET.parse(pom_path).getroot()
    found: list[dict] = []
    for plugins in _plugin_parents(root):
        for plugin in _children(plugins, "plugin"):
            artifact = _child(plugin, "artifactId")
            if artifact is None or (artifact.text or "").strip() != "jacoco-maven-plugin":
                continue
            for execution in _children(_child(plugin, "executions"), "execution"):
                goals = [(g.text or "").strip()
                         for g in _children(_child(execution, "goals"), "goal")]
                id_el = _child(execution, "id")
                phase_el = _child(execution, "phase")
                found.append({
                    "id": (id_el.text or "").strip() if id_el is not None else None,
                    "phase": (phase_el.text or "").strip()
                    if phase_el is not None and phase_el.text else None,
                    "goals": goals,
                })
    return found


def surefire_argline(pom_path: Path) -> str | None:
    """返回该 pom 为 maven-surefire-plugin **显式**配置的 <argLine> 文本。

    未显式配置则返回 None(surefire 默认值 `${argLine}` 能正确引回 jacoco 属性)。
    """
    root = ET.parse(pom_path).getroot()
    for plugins in _plugin_parents(root):
        for plugin in _children(plugins, "plugin"):
            artifact = _child(plugin, "artifactId")
            if artifact is None or (artifact.text or "").strip() != SUREFIRE:
                continue
            argline = _child(_child(plugin, "configuration"), "argLine")
            if argline is not None:
                return "".join(argline.itertext())
    return None


def argline_property_defined(pom_path: Path) -> bool:
    """该 pom 是否在 <properties> 里定义了 argLine 默认值。"""
    props = _child(ET.parse(pom_path).getroot(), "properties")
    return _child(props, "argLine") is not None


def audit(root: Path) -> tuple[list[str], int, int]:
    """返回 (problems, gated_executions, gated_poms)。"""
    problems: list[str] = []
    gated_total = 0
    gated_poms = 0
    gated_any = False
    arglines: list[tuple[Path, str]] = []
    argline_prop = False
    poms = sorted(p for p in root.rglob("pom.xml") if "target" not in p.parts)
    for pom in poms:
        try:
            executions = jacoco_executions(pom)
            argline = surefire_argline(pom)
        except ET.ParseError as exc:
            problems.append(f"{pom.relative_to(root)}: pom 解析失败: {exc}")
            continue
        if argline is not None:
            arglines.append((pom.relative_to(root), argline))
        if argline_property_defined(pom):
            argline_prop = True
        if not executions:
            continue
        goals_all = {g for e in executions for g in e["goals"]}
        gated = [e for e in executions if set(GATED_GOALS) & set(e["goals"])]
        if not gated:
            continue
        gated_any = True
        gated_poms += 1
        for execution in gated:
            for goal in sorted(set(GATED_GOALS) & set(execution["goals"])):
                gated_total += 1
                label = f"{pom.relative_to(root)}: goal={goal}"
                if execution["id"]:
                    label += f"(id={execution['id']})"
                if execution["phase"] is None:
                    problems.append(
                        f"{label} 未声明 phase(report/check 插件默认绑 verify, "
                        f"mvn test 不会执行) —— 应为 'test'")
                elif execution["phase"] != EXPECTED_PHASE:
                    problems.append(
                        f"{label} phase={execution['phase']!r} 应为 'test'"
                        f"(早于 test 会在 surefire 之前读到空数据; 晚于 test 则 mvn test 走不到)")
        if "prepare-agent" not in goals_all:
            problems.append(
                f"{pom.relative_to(root)}: 有 report/check 但缺 prepare-agent execution"
                f"(无 jacoco.exec 时报告静默跳过, 门禁不生效)")

    if not gated_any:
        problems.append(
            "全仓未扫描到任何 jacoco report/check execution —— 扫描器失效或门禁被整体移除, 不是通过")
        return problems, gated_total, gated_poms

    # 判据 3: 显式 surefire <argLine> 必须引回 jacoco 的 agent 参数
    for rel, text in arglines:
        if LATE_TOKEN not in text:
            problems.append(
                f"{rel}: surefire <argLine> 未含 {LATE_TOKEN} —— 会覆盖 jacoco prepare-agent "
                f"写入的 argLine 属性, JVM 不挂 jacoco agent, 不生成 jacoco.exec, "
                f"report/check 静默跳过(构建仍 SUCCESS, 门禁不生效)")

    # 判据 4: 引用了 @{argLine} 就必须有默认属性值
    if any(LATE_TOKEN in text for _rel, text in arglines) and not argline_prop:
        problems.append(
            f"引用了 {LATE_TOKEN} 但根 pom 未定义 <argLine> 属性默认值 —— "
            f"未声明 jacoco 的模块会把字面量 {LATE_TOKEN} 传给 JVM 而启动失败")

    return problems, gated_total, gated_poms


def _clone_poms(root: Path, dest: Path) -> None:
    for pom in root.rglob("pom.xml"):
        if "target" in pom.parts:
            continue
        target = dest / pom.relative_to(root)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(pom, target)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(REPO_ROOT))
    parser.add_argument("--negative-control", action="store_true",
                        help="在副本 pom 上制造 4 类漂移, 断言守护必须全部报红")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    if args.negative_control:
        with tempfile.TemporaryDirectory() as tmp:
            clone = Path(tmp) / "repo"
            _clone_poms(root, clone)
            mutations = [
                ("cloud-infra-gateway/pom.xml",
                 "<phase>test</phase>", "<phase>verify</phase>",
                 "应为 'test'"),
                ("cloud-infra-auth/pom.xml",
                 "<phase>test</phase>", "",
                 "未声明 phase"),
                ("cloud-infra-modules/file-service/pom.xml",
                 "<goal>prepare-agent</goal>", "",
                 "缺 prepare-agent"),
                # 漂移二(本次实查到的假门禁): 阶段对了, 但 surefire argLine 覆盖 jacoco 属性
                ("pom.xml",
                 "@{argLine}\n", "",
                 f"未含 {LATE_TOKEN}"),
            ]
            for rel, old, new, _sig in mutations:
                path = clone / rel
                text = path.read_text(encoding="utf-8")
                assert old in text, f"负向对照变异失败: {rel} 里找不到 {old!r}"
                path.write_text(text.replace(old, new), encoding="utf-8")

            # 自校验: 变异确实改变了 XML 事实(而非字符串巧合)
            gw = jacoco_executions(clone / "cloud-infra-gateway/pom.xml")
            assert any(e["phase"] == "verify" for e in gw), "变异未生效: gateway phase 非 verify"
            au = jacoco_executions(clone / "cloud-infra-auth/pom.xml")
            assert any(e["phase"] is None for e in au), "变异未生效: auth 仍带 phase"
            fs = jacoco_executions(clone / "cloud-infra-modules/file-service/pom.xml")
            assert "prepare-agent" not in {g for e in fs for g in e["goals"]}, \
                "变异未生效: file-service 仍含 prepare-agent"
            root_argline = surefire_argline(clone / "pom.xml") or ""
            assert LATE_TOKEN not in root_argline, \
                f"变异未生效: 根 pom surefire argLine 仍含 {LATE_TOKEN}"

            problems, _gated, _poms = audit(clone)
            missing = [sig for _rel, _old, _new, sig in mutations
                       if not any(sig in p for p in problems)]
            if missing:
                print("⛔ 负向对照失败: 以下漂移未被报红:", missing)
                for p in problems:
                    print("   ", p)
                return 1
            print(f"✅ 负向对照通过: 4 类漂移全部被报红({len(problems)} 项)")
            return 0

    problems, gated_total, gated_poms = audit(root)
    if problems:
        print(f"FAIL: {len(problems)} 项")
        for p in problems:
            print(f"  - {p}")
        return 1
    print(f"PASS: {gated_poms} 个 pom 的 jacoco report/check 均绑 test 阶段"
          f"且具备 prepare-agent, surefire argLine 已引回 {LATE_TOKEN}"
          f"(共 {gated_total} 个门禁 execution)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
