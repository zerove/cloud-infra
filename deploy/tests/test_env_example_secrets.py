#!/usr/bin/env python3
"""
门禁: 公开弱密钥不得进入部署模板, 且 up.sh 必须真的把它换掉。

背景
----
`INTERNAL_CALL_SECRET` 与 `AUTH_JWT_SECRET` 都是"公开即失效"的凭据:
一旦模板里留着固定示例值, 所有按模板部署的环境共享同一个签名密钥,
`verify-internal-sign` 与 JWT 验签都形同虚设。服务侧已有 fail-fast 守护
(`InternalCallCrypto` 拒绝公开弱值 / `JwtUtils` 拒绝公开弱 JWT 密钥),
但那是**最后一道**防线 —— 部署模板若回退, 用户看到的是"服务起不来",
而不是"模板有问题"。

本门禁判定两件事
----------------
1. `.env.example` 中 `AUTH_JWT_SECRET` / `INTERNAL_CALL_SECRET` 必须留空。
   写明示例值等于把该值公开给所有部署。
2. `deploy/up.sh` 的 `ensure_env()` 必须**真的**把它随机化。
   ⚠️ 判据不是"文件里出现 INTERNAL_CALL_SECRET 字样"——本项目已 8 次栽在
   "名字出现过就说它在运行"上。这里直接从 up.sh 抽出 `ensure_env` 函数体,
   在临时目录里用真实 .env / .env.example 跑一遍, 断言产物值。

覆盖三种真实形态:
  A. 没有 .env (首次部署, 从模板生成)
  B. 已有 .env 且值 = 公开示例值 (存量环境, 最容易漏)
  C. 已有 .env 但没有该键 (早期版本生成的 .env)

负向对照: 把 up.sh 的随机化分支删掉 → 用例 A/B 必须失败。
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
UP_SH = REPO / "deploy" / "up.sh"
ENV_EXAMPLE = REPO / ".env.example"
PUBLIC_INTERNAL_SECRET = "cloud-infra-internal-dev-secret"
# 模板里曾用的 JWT 占位值: 49 字符, 能通过长度校验, 也不在弱密钥黑名单里 ——
# 正因如此它必须被"留空 + 随机化"处理, 而不能靠服务端黑名单兜底。
PUBLIC_AUTH_PLACEHOLDER = "your_very_secure_jwt_secret_key_at_least_32_chars"

FAILURES = []


def fail(msg: str) -> None:
    FAILURES.append(msg)


def parse_env(text: str) -> dict:
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


# ---------- 1. 模板不得写死凭据 ----------
if not ENV_EXAMPLE.is_file():
    fail(f"{ENV_EXAMPLE} 不存在")
else:
    tmpl = parse_env(ENV_EXAMPLE.read_text(encoding="utf-8"))
    for key in ("AUTH_JWT_SECRET", "INTERNAL_CALL_SECRET"):
        if key not in tmpl:
            fail(f".env.example 缺少 {key} 键")
        elif tmpl[key]:
            fail(f".env.example 的 {key} 必须留空, 当前写死了 {len(tmpl[key])} 个字符的值"
                 " (写明示例值 = 向所有部署公开该凭据)")

# ---------- 2. 抽取真实 ensure_env 并执行 ----------
if not UP_SH.is_file():
    fail(f"{UP_SH} 不存在")
    print("FAILED:")
    for f in FAILURES:
        print(f"  {f}")
    sys.exit(1)

up_src = UP_SH.read_text(encoding="utf-8")
m = re.search(r"^ensure_env\(\) \{.*?^\}$", up_src, re.MULTILINE | re.DOTALL)
if m is None:
    fail("deploy/up.sh 中找不到 ensure_env() 函数定义 —— 无法验证密钥随机化")
    print("FAILED:")
    for f in FAILURES:
        print(f"  {f}")
    sys.exit(1)

ENSURE_ENV_FN = m.group(0)

# 只把真正的随机化逻辑抽出来跑: ensure_env 结尾会 source 整个 .env,
# 这里保留原样即可 (临时 .env 内容可控)。
HARNESS = """set -euo pipefail
PROJECT_ROOT="$1"
ENV_FILE="$PROJECT_ROOT/.env"
{fn}
ensure_env >/dev/null 2>&1
"""


def run_ensure_env(files: dict) -> dict:
    """在临时目录铺设 files, 执行真实 ensure_env, 返回生成后的 .env 键值。"""
    tmp = Path(tempfile.mkdtemp(prefix="env-example-gate-"))
    try:
        (tmp / ".env.example").write_text(
            # 用真实模板的骨架, 但保证密钥为空 —— 模板本身由上面第 1 步判定。
            "AUTH_JWT_SECRET=\nINTERNAL_CALL_SECRET=\nNACOS_AUTH_TOKEN=\n", encoding="utf-8")
        for name, content in files.items():
            (tmp / name).write_text(content, encoding="utf-8")
        script = tmp / "_harness.sh"
        script.write_text(HARNESS.format(fn=ENSURE_ENV_FN), encoding="utf-8")
        proc = subprocess.run(["bash", str(script), str(tmp)],
                              capture_output=True, text=True, timeout=120)
        if proc.returncode != 0:
            fail(f"ensure_env 执行失败 (rc={proc.returncode}): {proc.stderr.strip()[:400]}")
            return {}
        env_file = tmp / ".env"
        if not env_file.is_file():
            fail("ensure_env 未生成 .env")
            return {}
        return parse_env(env_file.read_text(encoding="utf-8"))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def assert_secret_rotated(case: str, env: dict) -> None:
    for key in ("AUTH_JWT_SECRET", "INTERNAL_CALL_SECRET"):
        val = env.get(key, "")
        if not val:
            fail(f"[{case}] {key} 仍为空 —— up.sh 未随机化")
            continue
        if val in (PUBLIC_INTERNAL_SECRET, PUBLIC_AUTH_PLACEHOLDER):
            fail(f"[{case}] {key} 仍是模板里的公开占位值 —— 等于没有任何随机化")
        if len(val) < 32:
            fail(f"[{case}] {key} 长度 {len(val)} < 32, 强度不足")


# A. 首次部署
assert_secret_rotated("A 无 .env", run_ensure_env({}))

# B. 存量 .env 里就是模板公开占位值 (最容易漏的一类: 手工 cp 或升级前生成)
assert_secret_rotated("B 存量=公开占位值", run_ensure_env({
    ".env": f"AUTH_JWT_SECRET={PUBLIC_AUTH_PLACEHOLDER}\n"
            f"INTERNAL_CALL_SECRET={PUBLIC_INTERNAL_SECRET}\n"
            f"NACOS_AUTH_TOKEN=\n",
}))

# C. 存量 .env 完全缺少 INTERNAL_CALL_SECRET 行 (早期版本生成的 .env)
env_c = run_ensure_env({
    ".env": f"AUTH_JWT_SECRET={PUBLIC_AUTH_PLACEHOLDER}\nNACOS_AUTH_TOKEN=\n",
})
if "INTERNAL_CALL_SECRET" not in env_c:
    fail("[C 缺失该键] INTERNAL_CALL_SECRET 未被追加 —— compose 的 ${VAR:?} 会直接拒绝启动")
else:
    assert_secret_rotated("C 缺失该键", env_c)

if FAILURES:
    print("FAILED: 部署模板/密钥随机化缺陷:")
    for f in FAILURES:
        print(f"  {f}")
    sys.exit(1)

print("OK: .env.example 未写死凭据, ensure_env 在 A/B/C 三种形态下均随机化了密钥")
