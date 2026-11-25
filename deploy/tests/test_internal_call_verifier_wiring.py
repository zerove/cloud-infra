#!/usr/bin/env python3
"""守护: 内部调用验签必须只有一份实现 (InternalCallVerifier), 禁止回归为拷贝。

## 背景（2026-10-18, 审计缺陷 D1）

同一套 HMAC 验签逻辑曾以 5 份独立拷贝分散在 5 个模块:

- framework-security `HeaderAuthenticationFilter`
- common `TenantContextFilter`
- notification `WebSocketAuthFilter`
- system `SystemSecurityConfig`
- framework-grpc `GrpcSecurityInterceptor`

任一处被放宽 (少判一个头、漏掉时间窗、密钥缺失时放行), 信任模型就静默分裂 ——
而每个站点自己的测试只会证明"自己那份"是对的, 没有任何测试在看"五份是否一致"。
收敛后判定逻辑只在 `InternalCallVerifier` 一份, 本守护防止它再次被拷贝。

## 判据

A. 全仓 main 源码中 `InternalCallCrypto.verify(` 只允许出现在 InternalCallVerifier.java
   (测试代码不受限); 判据匹配前剥离 Java 注释, 避免"注释里提到名字"造成误报/假绿;
B. 5 个历史站点必须含 `InternalCallVerifier.isTrusted` 委托;
C. InternalCallVerifier 必须保留 fail-closed 结构: 开关前置、密钥空判、NumberFormatException 捕获;
D. 5 个站点不得重新实现密码学原语 (`HmacSHA256` / `MessageDigest.isEqual`)。

扫不到对应结构一律判红（判据失效 != 通过）。

负向对照: --negative-control 在副本上逐类制造回归, 四类都必须报红。
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

VERIFIER_REL = ("cloud-infra-common/src/main/java/"
                "com/cloudinfra/common/core/crypto/InternalCallVerifier.java")

SITES = (
    "cloud-infra-framework/framework-security/src/main/java/"
    "com/cloudinfra/framework/security/filter/HeaderAuthenticationFilter.java",
    "cloud-infra-common/src/main/java/"
    "com/cloudinfra/common/core/tenant/TenantContextFilter.java",
    "cloud-infra-modules/notification-service/src/main/java/"
    "com/cloudinfra/notification/ws/WebSocketAuthFilter.java",
    "cloud-infra-modules/system-module/src/main/java/"
    "com/cloudinfra/system/config/SystemSecurityConfig.java",
    "cloud-infra-framework/framework-grpc/src/main/java/"
    "com/cloudinfra/framework/grpc/GrpcSecurityInterceptor.java",
)

FORBIDDEN_CRYPTO = ("HmacSHA256", "MessageDigest.isEqual")
FAIL_CLOSED_NEEDLES = (
    "if (!verifyEnabled)",
    "secret == null || secret.isBlank()",
    "catch (NumberFormatException",
)

_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
_LINE_COMMENT = re.compile(r"//[^\n]*")


def strip_java_comments(text: str) -> str:
    """剥离注释后再判定: 被检字面量出现在注释里不算命中, 也不算违规。

    本项目已有 7 次"被检字面量出现在注释/标签里"造成的假绿或误报
    (docs/lessons/debugging-log.md 第八课补记一), 判定必须在可执行文本上进行。
    """
    return _LINE_COMMENT.sub("", _BLOCK_COMMENT.sub("", text))


def read_code(path: Path) -> str:
    return strip_java_comments(path.read_text(encoding="utf-8"))


def audit(root: Path):
    problems: list[str] = []
    checked = 0

    # A: 唯一实现 —— main 源码中 verify( 的调用点只允许在 InternalCallVerifier
    for path in sorted(root.rglob("*.java")):
        if "target" in path.parts or "src/main/java" not in path.as_posix():
            continue
        rel = path.relative_to(root).as_posix()
        if rel == VERIFIER_REL:
            continue
        if "InternalCallCrypto.verify(" in read_code(path):
            problems.append(
                f"{rel}: 直接调用 InternalCallCrypto.verify —— 验签逻辑必须委托 "
                f"InternalCallVerifier (单一真源), 新站点请扩展它而非拷贝"
            )

    # B/D: 5 个站点必须委托, 且不得自带密码学原语
    for rel in SITES:
        path = root / rel
        if not path.exists():
            problems.append(f"{rel}: 文件不存在 —— 判据失效, 不是通过")
            continue
        code = read_code(path)
        checked += 1
        if "InternalCallVerifier.isTrusted" not in code:
            problems.append(
                f"{rel}: 缺少 InternalCallVerifier.isTrusted 委托 —— 回归为自带拷贝?"
            )
        for needle in FORBIDDEN_CRYPTO:
            if needle in code:
                problems.append(
                    f"{rel}: 出现 {needle} —— 站点不得重新实现密码学原语"
                )

    # C: 唯一实现自身必须保留 fail-closed 结构
    verifier = root / VERIFIER_REL
    if not verifier.exists():
        problems.append(f"{VERIFIER_REL}: 文件不存在 —— 判据失效, 不是通过")
    else:
        code = read_code(verifier)
        for needle in FAIL_CLOSED_NEEDLES:
            if needle not in code:
                problems.append(
                    f"{VERIFIER_REL}: fail-closed 结构缺失 ({needle}) —— "
                    f"同步修改 InternalCallVerifierTest 再动这里"
                )

    return problems, checked


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(REPO_ROOT))
    parser.add_argument("--negative-control", action="store_true",
                        help="在副本上制造 4 类回归, 断言守护必须全部报红")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    if args.negative_control:
        with tempfile.TemporaryDirectory() as tmp:
            clone = Path(tmp) / "repo"
            for rel in (VERIFIER_REL, *SITES):
                dst = clone / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(root / rel, dst)

            mutations = [
                (SITES[1],
                 "return InternalCallVerifier.isTrusted(request, true, internalCallSecret);",
                 "return InternalCallCrypto.verify(internalCallSecret, "
                 "request.getHeader(InternalCallCrypto.HEADER_SIGN), "
                 "System.currentTimeMillis(), "
                 "request.getHeader(InternalCallCrypto.HEADER_NONCE));",
                 "直接调用 InternalCallCrypto.verify"),
                (SITES[2],
                 "return InternalCallVerifier.isTrusted(request, verifyInternalSign, secret);",
                 "return true;",
                 "缺少 InternalCallVerifier.isTrusted 委托"),
                (VERIFIER_REL,
                 "if (secret == null || secret.isBlank())",
                 "if (false)",
                 "fail-closed 结构缺失"),
                (SITES[3],
                 "String sign = request.getHeader(InternalCallCrypto.HEADER_SIGN);",
                 "String sign = request.getHeader(InternalCallCrypto.HEADER_SIGN);\n"
                 "            MessageDigest.isEqual(new byte[0], new byte[0]);",
                 "重新实现密码学原语"),
            ]
            for rel, old, new, _sig in mutations:
                path = clone / rel
                text = path.read_text(encoding="utf-8")
                assert old in text, f"负向对照变异失败: {rel} 里找不到 {old!r}"
                path.write_text(text.replace(old, new, 1), encoding="utf-8")

            problems, _checked = audit(clone)
            missing = [sig for _rel, _old, _new, sig in mutations
                       if not any(sig in p for p in problems)]
            if missing:
                print("⛔ 负向对照失败: 以下回归未被报红:", missing)
                for p in problems:
                    print("   ", p)
                return 1
            print(f"✅ 负向对照通过: 4 类回归全部被报红({len(problems)} 项)")
            return 0

    problems, checked = audit(root)
    if problems:
        print(f"FAIL: {len(problems)} 项")
        for p in problems:
            print(f"  - {p}")
        return 1
    print(f"PASS: 验签唯一实现成立 —— {checked} 个站点全部委托 InternalCallVerifier, "
          "无第二份 verify 调用/密码学拷贝")
    return 0


if __name__ == "__main__":
    sys.exit(main())
