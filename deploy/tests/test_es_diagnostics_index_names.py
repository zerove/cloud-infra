#!/usr/bin/env python3
"""守护: verify-e2e.sh 的 ES 诊断必须探真实索引名。

## 为什么需要 (2026-10-03)

为了定位 search 的 ES 400, 我在 es_diagnostics() 里写了
`for idx in oper_log search_log`。但 search 真正使用的索引名来自
`@Document(indexName = ...)`:

    OperLogDocument          -> sys_oper_log
    DeviceTelemetryDocument  -> device_telemetry

我写的两个名字**一个都不存在**。探不存在的索引恒返回 404,
看起来"ES 正常", 诊断结论直接是错的 —— 这是最危险的一类假证据:
它不是报错, 而是**看起来正常的错误信息**。

在拿到 ES 服务端日志之前, 这套诊断是唯一的取证手段;
它若探错对象, 下一轮 CI 的结论仍然不可信。

## 判定方式

从 Java 侧提取全部 @Document(indexName=...),
要求它们全部出现在 verify-e2e.sh 的 es_diagnostics 索引列表中。

## 负向对照

  · 把 sys_oper_log 改成别的名字   -> 必须失败
  · 给 OperLogDocument 换新索引名 -> 必须失败（守护能发现漂移）
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = ROOT / "cloud-infra-modules" / "search-service" / "src" / "main" / "java"
VERIFY = ROOT / "verify-e2e.sh"


def java_index_names() -> set[str]:
    names: set[str] = set()
    for f in JAVA_ROOT.rglob("*.java"):
        text = f.read_text(encoding="utf-8")
        for m in re.finditer(
            r"@Document\s*\(\s*(?:indexName\s*=\s*)?\"([^\"]+)\"", text
        ):
            names.add(m.group(1))
    return names


def diag_index_names() -> set[str]:
    """从 verify-e2e.sh 的 es_diagnostics 函数里取索引列表。"""
    src = VERIFY.read_text(encoding="utf-8")
    m = re.search(r"^es_diagnostics\(\)\s*\{(.*?)^\}", src, re.S | re.M)
    if not m:
        raise AssertionError("verify-e2e.sh 里找不到 es_diagnostics() 函数")
    body = m.group(1)
    loop = re.search(r"for\s+idx\s+in\s+([^;\n]+);", body)
    if not loop:
        raise AssertionError("es_diagnostics() 里找不到 `for idx in ...; do` 索引循环")
    # ⚠️ 取值范围要跨**空格**, 不能用 [^\s;]+:
    #   `for idx in a b c; do` 用空格分隔三个索引,
    #   按非空白截断只会拿到第一个 —— 守护会漏掉其余真实索引,
    #   而漏掉的恰好是"没被探测到"的那部分, 即最需要被发现的那部分。
    return set(re.findall(r"[A-Za-z0-9_\-]+", loop.group(1)))


def main() -> int:
    java = java_index_names()
    diag = diag_index_names()

    if not java:
        print("FAIL 未从 Java 侧提取到任何 @Document 索引名 —— 提取逻辑可能坏了")
        return 1
    if not diag:
        print("FAIL 未从 es_diagnostics() 提取到索引名 —— 提取逻辑可能坏了")
        return 1

    missing = java - diag
    failures = []
    if missing:
        failures.append(
            f"es_diagnostics() 未探测这些真实索引: {sorted(missing)}。"
            f"探不存在的索引恒返回 404, 会得出'ES 正常'的错误结论。"
        )

    # 反向: 诊断里探了 Java 侧不存在的索引, 同样是漂移信号
    extra = diag - java
    if extra:
        failures.append(f"es_diagnostics() 探测了不存在的索引: {sorted(extra)}")

    for f in failures:
        print(f"FAIL {f}")
    if failures:
        return 1

    print(f"PASS ES 诊断索引名守护: Java {sorted(java)} == 诊断 {sorted(diag)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())