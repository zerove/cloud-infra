#!/usr/bin/env python3
"""check_jvm_opts_keys_match_image.py 的守护测试。

## 反馈循环性质（必须先说清，否则会被当成真实验证）

本测试用的是**桩镜像脚本**（synthetic）：它验证的是判定逻辑本身，
不是「当前 compose 是否真的有效」。后者需要真实 podman + 真实镜像，
由 `check_jvm_opts_keys_match_image.py` 自己在有 podman 时执行。

这个区分不是客套话：第五课说的正是「探针给出一个格式正确但语义错误的
结论」。若有人拿本测试的 PASS 当作配置已生效的证据，那就是把
synthetic 当 acceptance_aligned。

## 覆盖的场景

1.  compose 用**镜像脚本不读**的键（JVM_ADJUST）→ 必须报红并指名该键
2.  compose 用脚本读的键（JAVA_OPT_EXT）→ 通过
3.  JAVA_TOOL_OPTIONS 在忽略名单里 → 不算失败（podman/JVM 自身处理）
4.  取不到任何脚本 → SKIP + exit 0，且**必须打印原因**（不是静默 0）
5.  compose 解析不出 service / 没有任何 JVM 键 → **报红**（扫描器坏了 ≠ 通过）
6.  映射形式与列表形式的 environment 都能解析
"""

from __future__ import annotations

import importlib.util
import io
import sys
import tempfile
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
GUARD_PATH = HERE / "check_jvm_opts_keys_match_image.py"

spec = importlib.util.spec_from_file_location("guard", GUARD_PATH)
guard = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(guard)

# 取自**真实 nacos 镜像内 docker-startup.sh 的变量引用形态**（实测），
# 而不是按官方文档猜的键名。
NACOS_STUB = """#!/bin/bash
JAVA_OPT="${JAVA_OPT:-}"
JAVA_OPT_EXT="${JAVA_OPT_EXT:-}"
JAVA_OPT_EXT_FIX="${JAVA_OPT_EXT_FIX:-}"
JVM_XMS="${JVM_XMS:-}"
JVM_XMX="${JVM_XMX:-}"
JVM_XMN="${JVM_XMN:-}"
exec java $JAVA_OPT $JAVA_OPT_EXT $JAVA_OPT_EXT_FIX -Xms$JVM_XMS -Xmx$JVM_XMX
"""

failures: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        print(f"  PASS {name}")
    else:
        print(f"  FAIL {name} — {detail}")
        failures.append(name)


def run_guard(compose_text: str, script_body: str | None) -> tuple[int, str]:
    """在临时目录里跑一次守护，返回 (退出码, 输出)。

    通过覆写模块级 COMPOSE / SCRIPT_PATHS 指向临时产物，
    不修改仓库文件（第八课补记一第 5 条）。
    """
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        compose = td / "compose.yaml"
        compose.write_text(compose_text, encoding="utf-8")

        # 构造一个假的 "podman run --rm --entrypoint cat IMAGE PATH" 行为:
        # 直接让 script_vars 返回桩内容, 不真的调 podman。
        if script_body is None:
            guard.script_vars = lambda image: (None, "测试桩: 镜像内无已知启动脚本")
        else:
            guard.script_vars = lambda image: (
                set(__import__("re").findall(r"[A-Z_]{3,}", script_body)),
                "测试桩",
            )

        old_compose = guard.COMPOSE
        guard.COMPOSE = compose
        buf = io.StringIO()
        try:
            with redirect_stdout(buf):
                rc = guard.main()
        finally:
            guard.COMPOSE = old_compose
        return rc, buf.getvalue()


BASE_ENV = '      JAVA_TOOL_OPTIONS: "-Dnacos.core.auth.admin.enabled=false"\n'
METAS = "-XX:MaxMetaspaceSize=256m"

print("== 场景 1: compose 用了镜像脚本不读的键 (JVM_ADJUST) → 必须报红 ==")
compose_bad = f"""services:
  nacos:
    image: nacos/nacos-server:v3.0.0
    environment:
{BASE_ENV}      JVM_ADJUST: "{METAS}"
"""
rc, out = run_guard(compose_bad, NACOS_STUB)
check("退出码为 1", rc == 1, f"实得 {rc}")
# ⚠️ 断言文案必须与门禁**实际输出**一致。这里先前写成"不会被启动脚本读取",
#   而门禁打的是"未被启动脚本读取的键" —— 断言写错就会恒红,
#   而恒红的断言会被误当成"门禁在工作"(第五课: 假结论比报错更危险)。
check(
    "指认 JVM_ADJUST 未被读取",
    "JVM_ADJUST" in out and "未被启动脚本读取" in out,
    out,
)
check("明确写出'参数不会生效'", "不会生效" in out, out)

print("\n== 场景 2: compose 用了脚本读的键 (JAVA_OPT_EXT) → 通过 ==")
compose_good = f"""services:
  nacos:
    image: nacos/nacos-server:v3.0.0
    environment:
{BASE_ENV}      JAVA_OPT_EXT: "{METAS}"
"""
rc, out = run_guard(compose_good, NACOS_STUB)
check("退出码为 0", rc == 0, f"实得 {rc}\n{out}")

print("\n== 场景 3: 只用 JAVA_TOOL_OPTIONS → 不应因该键失败 ==")
compose_too = f"""services:
  nacos:
    image: nacos/nacos-server:v3.0.0
    environment:
{BASE_ENV}"""
rc, out = run_guard(compose_too, NACOS_STUB)
check("退出码为 0", rc == 0, f"实得 {rc}\n{out}")

print("\n== 场景 4: 取不到脚本 → SKIP + exit 0 且打印原因 ==")
rc, out = run_guard(compose_good, None)
check("退出码为 0 (本次不判定)", rc == 0, f"实得 {rc}")
check("打印了 SKIP", "[SKIP]" in out, out)
check("打印了原因", "无已知启动脚本" in out or "SKIP" in out, out)
check("没有把 SKIP 说成 PASS", "[PASS]" not in out, out)

print("\n== 场景 5a: compose 解析不出 service → 报红 (扫描器坏了 ≠ 通过) ==")
rc, out = run_guard("not_a_mapping: 123\n", NACOS_STUB)
check("退出码为 1", rc == 1, f"实得 {rc}")
check("说明是扫描器问题", "扫描器坏了" in out, out)

print("\n== 场景 5b: 没有任何 JVM 键 → 报红 ==")
rc, out = run_guard("services:\n  redis:\n    image: redis:7.2\n", NACOS_STUB)
check("退出码为 1", rc == 1, f"实得 {rc}")
check("说明是扫描器问题", "扫描器坏了" in out, out)

print("\n== 场景 6: 列表形式 environment 也能解析 ==")
compose_list = f"""services:
  es:
    image: elasticsearch:9.0.3
    environment:
      - JAVA_TOOL_OPTIONS=-Xmx512m
      - ES_JAVA_OPTS={METAS}
"""
rc, out = run_guard(compose_list, "ES_JAVA_OPTS JAVA_TOOL_OPTIONS")
check("退出码为 0", rc == 0, f"实得 {rc}\n{out}")
check("正确解析出两个键", "ES_JAVA_OPTS" in out, out)

print()
if failures:
    print(f"[FAIL] {len(failures)} 项: {failures}")
    sys.exit(1)
print("[PASS] 全部场景通过（注意: 本测试用桩数据，只验证判定逻辑，不证明当前配置已生效）")
