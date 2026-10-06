#!/usr/bin/env python3
"""守护测试: check_start_period_covers_startup.py 必须真的能判红。

## 为什么需要这个文件（AGENTS.md 第四课）

"我加了守护"不等于"守护有效"。本项目已出现过 11 项补扫测试全绿而缺陷仍在,
以及一个 `p.startswith("com.cloudinfra")` 写法的假守护 —— 负向对照下**没有变红**。

本文件的唯一目的, 是让 `check_start_period_covers_startup.py`
接受一次真实的负向对照: 把 start_period 改回 20s, 守护必须 exit 1。

## 测试设计: 每个用例一份独立副本

用例之间**不共享可变状态**（AGENTS.md 第八课补记一第 5 条）。
每个用例把自己的副本写进 `tmp_path`, 通过 `COMPOSE_FILE` 环境变量
让守护去读那份副本 —— 被测脚本本身不写死路径, 测试也绝不碰生产
`deploy/compose.yaml`。若守护不接受路径覆盖, 测试就该失败, 那本身
就是缺陷信号。
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
COMPOSE = REPO_ROOT / "deploy" / "compose.yaml"
GATE = REPO_ROOT / "deploy" / "tests" / "check_start_period_covers_startup.py"

BUSINESS_SERVICES = ("gateway", "auth", "system", "file", "notification", "search")


def run_gate(compose_path: Path) -> tuple[int, str]:
    """对指定 compose 文件跑守护, 返回 (退出码, 合并输出)。

    管道后的 `$?` 不能用于判断被测命令 —— 必须先落盘或用 PIPESTATUS。
    这里用 subprocess 直接拿 returncode, 从根上避开那个陷阱。
    """
    env = dict(os.environ)
    env["COMPOSE_FILE"] = str(compose_path)
    proc = subprocess.run(
        [sys.executable, str(GATE)],
        capture_output=True,
        text=True,
        env=env,
        timeout=60,
    )
    return proc.returncode, proc.stdout + proc.stderr


def make_copy(tmp_path: Path, name: str = "compose.yaml") -> Path:
    """复制一份生产 compose, 供单个用例独占修改。"""
    target = tmp_path / name
    shutil.copy(COMPOSE, target)
    return target


def edit_start_period(compose_path: Path, service: str, new_value: str | None) -> None:
    """在副本上把某服务的 start_period 改成 new_value。

    用结构化解析 + 整段回写, 而不是 `sed` 替换字面量 ——
    AGENTS.md 第八课补记一第 4 条: 当前不存在的字面量, `sed` 会静默无操作,
    等于一个恒假绿的断言。改完必须读回校验。
    """
    doc = yaml.safe_load(compose_path.read_text(encoding="utf-8"))
    hc = doc["services"][service].get("healthcheck")
    assert hc is not None, f"{service} 没有 healthcheck 段, 测试前提不成立"
    if new_value is None:
        hc.pop("start_period", None)
    else:
        hc["start_period"] = new_value
    compose_path.write_text(
        yaml.safe_dump(doc, allow_unicode=True, sort_keys=False), encoding="utf-8"
    )

    # 注入自检: 确认改动真的落盘了（防止上面那种"改了但没生效"）
    reread = yaml.safe_load(compose_path.read_text(encoding="utf-8"))
    actual = (reread["services"][service].get("healthcheck") or {}).get("start_period")
    assert str(actual) == str(new_value), f"注入未生效: 期望 {new_value}, 实际 {actual}"


# --------------------------------------------------------------------------
# 正向
# --------------------------------------------------------------------------


def test_gate_passes_on_current_config():
    """当前仓库配置必须 PASS —— 这证明判据能认出正确配置。"""
    code, out = run_gate(COMPOSE)
    assert code == 0, f"当前配置应 PASS, 实际 exit={code}\n{out}"


def test_gate_lists_every_business_service():
    """输出必须逐个列出服务 —— 漏一个就意味着"没验到=验过了"。"""
    code, out = run_gate(COMPOSE)
    assert code == 0
    for svc in BUSINESS_SERVICES:
        assert svc in out, f"输出未提及 {svc}, 该服务可能没被检查"


# --------------------------------------------------------------------------
# 负向对照: 这一节是本文件的全部目的
# --------------------------------------------------------------------------


def test_negative_control_regression_to_20s_is_detected(tmp_path):
    """把 notification 改回 20s（原缺陷值）, 守护必须 exit 1。

    这就是 2026-10-06 实测踩中的形态:
    start_period(45s 判定窗口) < 实测启动 304s -> 永久误判 unhealthy。
    """
    compose = make_copy(tmp_path)
    edit_start_period(compose, "notification", "20s")

    code, out = run_gate(compose)
    assert code == 1, f"改回 20s 后守护必须判红, 实际 exit={code}\n{out}"
    assert "notification" in out, "报错信息必须指名 notification, 不能泛泛而谈"
    assert "304" in out, "报错信息必须给出实测值, 否则无法判断差多少"


def test_negative_control_isolated_service_only(tmp_path):
    """只改一个服务时, 守护必须只报那一个 —— 证明判据不是恒红。"""
    compose = make_copy(tmp_path)
    edit_start_period(compose, "gateway", "20s")

    code, out = run_gate(compose)
    assert code == 1, f"只改 gateway 应判红\n{out}"
    assert "gateway" in out
    # 其余服务必须仍然是 OK, 否则说明判据退化成了"全红即通过"
    assert "notification" in out and "OK  notification" in out, (
        f"未改动的 notification 不该出现在失败列表里\n{out}"
    )


def test_negative_control_missing_key_is_detected(tmp_path):
    """start_period 整个缺失 -> 判红, 不能当成"无限大所以通过"。"""
    compose = make_copy(tmp_path)
    edit_start_period(compose, "search", None)

    code, out = run_gate(compose)
    assert code == 1, f"start_period 缺失应判红\n{out}"
    assert "search" in out


def test_negative_control_unparsable_value_is_detected(tmp_path):
    """值写成人话 -> 判红, 不能静默当 0 放过。"""
    compose = make_copy(tmp_path)
    edit_start_period(compose, "auth", "很久")

    code, out = run_gate(compose)
    assert code == 1, f"无法解析的 start_period 应判红\n{out}"
    assert "auth" in out


# --------------------------------------------------------------------------
# 探针自身失效必须判红（第五课补记一）
# --------------------------------------------------------------------------


def test_missing_file_is_failure_not_skip(tmp_path):
    """compose 文件不存在 -> exit 1。

    绝不能 exit 0: "没测到" 与 "测了通过" 是两件事, SKIP 要显式且打印原因。
    """
    code, out = run_gate(tmp_path / "does-not-exist.yaml")
    assert code == 1, f"文件缺失应判红\n{out}"
    assert "找不到" in out or "FAIL" in out


def test_unparsable_yaml_is_failure(tmp_path):
    """compose 语法坏掉 -> exit 1（探针坏了不等于配置通过）。"""
    broken = tmp_path / "compose.yaml"
    broken.write_text("services: [unclosed\n", encoding="utf-8")

    code, out = run_gate(broken)
    assert code == 1, f"YAML 解析失败应判红\n{out}"


def test_missing_business_service_is_failure(tmp_path):
    """业务服务定义被删 -> exit 1, 不能"少检查一个也算过"。"""
    doc = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
    del doc["services"]["notification"]
    trimmed = tmp_path / "compose.yaml"
    trimmed.write_text(yaml.safe_dump(doc, allow_unicode=True, sort_keys=False), encoding="utf-8")

    code, out = run_gate(trimmed)
    assert code == 1, f"缺少业务服务定义应判红\n{out}"
    assert "notification" in out


# --------------------------------------------------------------------------
# 边界
# --------------------------------------------------------------------------


def _load_gate_module():
    """导入被测模块, 并保证 sys.path 事后还原。"""
    sys.path.insert(0, str(GATE.parent))
    try:
        import check_start_period_covers_startup as gate

        return gate
    finally:
        sys.path.pop(0)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("180s", 180.0), ("3m", 180.0), ("1h", 3600.0), (180, 180.0), ("180", 180.0)],
)
def test_parse_duration_units(raw, expected):
    """单位解析: 's'/'m'/'h'/裸数字都要正确 —— 解析错了判据就整体失真。"""
    assert _load_gate_module().parse_duration(raw) == expected


def test_parse_duration_rejects_garbage():
    gate = _load_gate_module()
    assert gate.parse_duration("很久") is None
    assert gate.parse_duration(None) is None
    assert gate.parse_duration("") is None


def test_gate_is_registered_in_verify_e2e():
    """守护必须真的被 verify-e2e.sh 调用 —— 第三课: 代码存在 ≠ 在运行。

    判据指向**被调用的标识**（脚本文件名）, 且排除函数定义行与注释行,
    只认真正的调用点。AGENTS.md 第八课补记一第 3 条。
    """
    script = REPO_ROOT / "verify-e2e.sh"
    text = script.read_text(encoding="utf-8")

    calls = []
    for i, line in enumerate(text.split("\n"), start=1):
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        if "check_start_period_covers_startup.py" in line:
            calls.append((i, stripped))

    assert calls, "verify-e2e.sh 里找不到对 check_start_period_covers_startup.py 的调用"
    # 至少一处不能只是 `if [ -f ... ]` 之类的存在性检查
    assert any(".py" in body and ("$" in body or "python3" in body or "python " in body)
               for _, body in calls), f"找到的疑似只是存在性检查: {calls}"
