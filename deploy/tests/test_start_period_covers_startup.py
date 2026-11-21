#!/usr/bin/env python3
"""守护测试: check_start_period_covers_startup.py 必须真的能判红。

## 为什么需要这个文件（AGENTS.md 第四课）

"我加了守护"不等于"守护有效"。本项目已出现过 11 项补扫测试全绿而缺陷仍在,
以及一个 `p.startswith("com.cloudinfra")` 写法的假守护 —— 负向对照下**没有变红**。

本文件的唯一目的, 是让 `check_start_period_covers_startup.py`
接受一次真实的负向对照: 把 start_period 改回 20s, 守护必须 exit 1。

## 为什么是直接执行的脚本, 不是 pytest 用例

CI 的 `deploy/tests/test_*.py` 聚合步骤用 `python3 <file>` 直接执行。
pytest 风格的函数在该方式下**一个都不会运行**, 却以 exit 0 结束 ——
那是"看起来在守护、实际从未执行"的假绿。故这里自带 `main()` 与断言,
每个用例独立副本, 失败时 exit 1。
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
COMPOSE = REPO_ROOT / "deploy" / "compose.yaml"
GATE = REPO_ROOT / "deploy" / "tests" / "check_start_period_covers_startup.py"

BUSINESS_SERVICES = ("gateway", "auth", "system", "file", "notification", "search")


def run_gate(compose_path: Path) -> tuple[int, str]:
    """对指定 compose 文件跑守护, 返回 (退出码, 合并输出)。"""
    import os

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


def make_copy(tmp_path: Path) -> Path:
    target = tmp_path / "compose.yaml"
    shutil.copy(COMPOSE, target)
    return target


def edit_start_period(compose_path: Path, service: str, new_value: str | None) -> None:
    """在副本上把某服务的 start_period 改成 new_value, 并读回校验。"""
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
    reread = yaml.safe_load(compose_path.read_text(encoding="utf-8"))
    actual = (reread["services"][service].get("healthcheck") or {}).get("start_period")
    assert str(actual) == str(new_value), f"注入未生效: 期望 {new_value}, 实际 {actual}"


def case_positive_current_config() -> None:
    code, out = run_gate(COMPOSE)
    assert code == 0, f"当前配置应 PASS, 实际 exit={code}\n{out}"


def case_lists_every_service() -> None:
    code, out = run_gate(COMPOSE)
    assert code == 0, out
    for svc in BUSINESS_SERVICES:
        assert svc in out, f"输出未提及 {svc}, 该服务可能没被检查"


def case_negative_notification_20s(tmp_path: Path) -> None:
    compose = make_copy(tmp_path)
    edit_start_period(compose, "notification", "20s")
    code, out = run_gate(compose)
    assert code == 1, f"改回 20s 后守护必须判红, 实际 exit={code}\n{out}"
    assert "notification" in out, "报错信息必须指名 notification"
    assert "304" in out, "报错信息必须给出实测值"


def case_negative_gateway_only(tmp_path: Path) -> None:
    compose = make_copy(tmp_path)
    edit_start_period(compose, "gateway", "20s")
    code, out = run_gate(compose)
    assert code == 1, out
    assert "gateway" in out
    assert "OK  notification" in out, f"未改动的 notification 不该报错\n{out}"


def case_negative_missing_key(tmp_path: Path) -> None:
    compose = make_copy(tmp_path)
    edit_start_period(compose, "search", None)
    code, out = run_gate(compose)
    assert code == 1, f"start_period 缺失应判红, 实际 exit={code}\n{out}"
    assert "search" in out


def case_negative_unparsable_value(tmp_path: Path) -> None:
    compose = make_copy(tmp_path)
    edit_start_period(compose, "auth", "很久")
    code, out = run_gate(compose)
    assert code == 1, f"无法解析的 start_period 应判红, 实际 exit={code}\n{out}"
    assert "auth" in out


def main() -> int:
    cases = [
        ("当前配置 PASS", case_positive_current_config, False),
        ("输出覆盖全部业务服务", case_lists_every_service, False),
        ("负向对照: notification 改回 20s", case_negative_notification_20s, True),
        ("负向对照: 只改 gateway", case_negative_gateway_only, True),
        ("负向对照: 缺少 start_period", case_negative_missing_key, True),
        ("负向对照: 无法解析的值", case_negative_unparsable_value, True),
    ]
    failures = 0
    print("== check_start_period_covers_startup 守护测试 ==")
    for name, fn, needs_tmp in cases:
        try:
            if needs_tmp:
                with tempfile.TemporaryDirectory(prefix="start-period-test-") as td:
                    fn(Path(td))
            else:
                fn()
        except AssertionError as exc:
            failures += 1
            print(f"  FAIL  {name}: {exc}")
        else:
            print(f"  PASS  {name}")
    if failures:
        print(f"\nFAIL: {failures} 项")
        return 1
    print(f"\nPASS: {len(cases)} 项")
    return 0


if __name__ == "__main__":
    sys.exit(main())
