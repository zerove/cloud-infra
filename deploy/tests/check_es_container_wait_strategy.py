#!/usr/bin/env python3
r"""守护: Testcontainers 的 ES 等待策略在真实 ES 日志上确实能命中。

## 背景: 一次被证伪的诊断 (2026-10-07)

Search 的 IT 曾在全量 Reactor 里报:

    ContainerLaunchException: Timed out waiting for log output matching
      '.*("message":\\s?"started[\\s?|"].*|] started$)'

我当时给出的诊断是: 库内置正则要求 `"message": "started` (冒号后**有空格**),
而 ES 9.x 输出紧凑 JSON `"message":"started` (**无空格**), 故永不匹配 ——
于是 "配了 3 分钟超时也没用, 因为在等一个永不出现的模式"。

**这个诊断是错的。** 从 1.21.0 的 jar 常量池提取库的真实正则, 用 Java
`Pattern` 逐行匹配真实 ES 日志, 结果是**命中**:

    库内置  .*("message":\s?"started[\s?|"].*|] started\n$)   逐行 find = 1
    整块 find = true

反向对照更直接: 把 `SearchServiceIntegrationTest` **还原成没有 waitingFor
覆盖的 HEAD 版本**重跑, 结果同样是 7 tests / 0 failures / BUILD SUCCESS。
即该覆盖不是失败原因。同一份代码单跑两次都绿, 全量跑时红 ⇒ 变量是并发负载。

## 为什么仍然要有这个门禁

上面的证伪过程暴露了一个真实的**可复发**风险: ES 一旦升级、启动日志格式
变化, 等待策略会在**无人察觉**的情况下静默失配 —— 表现是 IT 超时, 而报错
文案("timed out waiting for log output")读起来像"机器慢", 极易被归为环境
问题而放过。本次若没有做反向对照, 我就会把这个错误诊断连同"3 分钟超时
形同虚设"的结论一起写进仓库注释, 变成下一处"看起来正常的错误信息"。

所以门禁守的是**可验证的事实**, 不是我的推断:

1. 从 **jar 常量池**提取库内置正则 (被检对象 = 被调用方, 不靠手抄);
2. **真跑一个 ES 容器**抓真实日志 (证据来源 = 被测系统本身);
3. 用 1 的正则去匹配 2 的日志 —— 不命中即判红。

第 3 步现在能通过, 是因为库的正则确实匹配; 它会在 ES 升级失配时变红。

## 为什么还要检查源码里的显式覆盖

本仓库有两处 ES 容器声明。若有人写了自定义 `waitingFor`, 它就是**替代**
库默认的第二来源, 必须一并验 —— 否则会出现"库默认被绕过但没人发现"。

## 退出码

| 码 | 含义 |
| :-- | :--- |
| 0 | 命中 |
| 1 | 明确失配（真阳性） |
| 2 | **未判定**: 探针自身失效、扫不到目标、或取不到真实日志 |
"""
from __future__ import annotations

import re
import subprocess
import sys
import time
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

ES_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.0.3"
ES_READY_TIMEOUT = 300  # 秒; 实测裸起 84-130 秒

# 从被测系统(ES 自己)取证用的锚点, 不是等待策略本身
ES_READY_ANCHOR = '"message":"started'

JAVA_ROOTS = [ROOT / "cloud-infra-test-support", ROOT / "cloud-infra-modules"]

WAITING_FOR = re.compile(
    r"ElasticsearchContainer[^;]*?waitingFor\(\s*Wait\.forLogMessage\(\s*"
    r'"((?:[^"\\]|\\.)*)"',
    re.S,
)


def strip_java_comments(src: str) -> str:
    """剥掉 Java 注释（保留字符串/字符字面量）。

    为什么需要: `WAITING_FOR` 用 `[^;]*?` 跨行找 `waitingFor`, 而注释里的
    分号会把它截断 —— 本次就踩到, 把已修好的文件误报成"用了默认策略"。
    替换为等量空白而非删除, 以免把两行代码粘连成一行。
    """
    out: list[str] = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
        elif c == "/" and nxt == "*":
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            for _ in range(2):
                if i < n:
                    out.append(" ")
                    i += 1
        elif c in "\"'":
            out.append(src[i])
            i += 1
            while i < n:
                if src[i] == "\\":
                    out.append(src[i])
                    i += 1
                    if i < n:
                        out.append(src[i])
                        i += 1
                    continue
                out.append(src[i])
                i += 1
                if src[i - 1] == c:
                    break
        else:
            out.append(c)
            i += 1
    return "".join(out)


def self_test_probe() -> list[str]:
    """探针自检: 这些函数坏了, 门禁的结论就不可信。"""
    errs: list[str] = []
    # 1) 剥注释必须真生效, 且不能粘连行
    for src, forbidden in [("int a;\n// hi; x\n.waitingFor(y)\n", "hi"),
                           ("/* a; b */\nint a;\n.waitingFor(y)\n", "b")]:
        got = strip_java_comments(src)
        if forbidden in got:
            errs.append(f"strip_java_comments 未剥掉注释内容 {forbidden!r}: {got!r}")
    keep = 'String p = "a;b"; // 真注释;x\n.waitingFor(W.forLogMessage("q"))\n'
    got = strip_java_comments(keep)
    for must in ('"a;b"', '"q"'):
        if must not in got:
            errs.append(f"strip_java_comments 误伤了字面量 {must}: {got!r}")
    two = "a\n// c\n.waitingFor(y)"
    if strip_java_comments(two).count("\n") != two.count("\n"):
        errs.append("strip_java_comments 改变了行数, 可能把两行粘连")

    # 2) 跨行提取必须能处理 Java 字符串里的转义换行
    #    ⚠️【2026-10-07 自修】本用例初版写成 `E waitingFor(...)` 而**漏了
    #    ElasticsearchContainer 前缀**, 于是 WAITING_FOR 必然返回 None ->
    #    自检报红。看上去像"正则坏了", 实际是**样本坏了**。
    #    教训: 自检报红时先分清"被检对象坏了"与"探针/样本坏了"。
    #    (同类: 首次运行门禁时误报 InfrastructureContainers, 原因是注释里的
    #     分号挡住了 `[^;]*?` —— 同样是探针侧缺陷。)
    p = ('new ElasticsearchContainer(img) '
         '.waitingFor(Wait.forLogMessage("a\\nb", 1))')
    m = WAITING_FOR.search(strip_java_comments(p))
    if not m or m.group(1) != "a\\nb":
        errs.append(f"WAITING_FOR 未能跨行提取含 \\n 的模式: {m}")

    # 3) 没写 waitingFor 时必须返回 None（而不是误抓前一个容器的）
    two_decls = ('A new ElasticsearchContainer(i) '
                 '.waitingFor(Wait.forLogMessage("first", 1))\n'
                 'B new ElasticsearchContainer(x);\n')
    m2 = WAITING_FOR.search(strip_java_comments(two_decls))
    if not m2 or m2.group(1) != "first":
        errs.append(f"WAITING_FOR 抓错了容器: {m2}")
    return errs


def class_utf8_constants(data: bytes) -> list[str]:
    """按 CONSTANT_Utf8 结构解析常量池, 返回全部 UTF-8 字符串。

    ⚠️【2026-10-07 自修, 关键】初版用 `re.finditer(rb"[ -~]{20,}", data)` 扫
    可打印字符来"提取"库内置正则 —— 那是**扫到的东西不是被检对象**:

        库里真实的常量是
            .*("message":\s?"started[\s?|"].*|] started<LF>$)
        其中 <LF> 是一个**真实换行字节 0x0A**, 不是字面的反斜杠+n。
        可打印字符扫描在换行处断开, 于是提取到的是**被截断的常量**
        (`...|] started`), 末尾的 `$)` 丢失 —— 括号不闭合。

    后果链: 截断的正则 → Java 报 `PatternSyntaxException: Unclosed group`
        → 我的代码把"探针失败"当成"不匹配"
        → 门禁对**本来能匹配**的库正则报了红。
        即: 探针缺陷伪装成了被测对象缺陷, 且方向是"假红"。

    正解: 按 class 文件格式解析常量池, 长度由 u2 给出, 不依赖字符是否可打印。
    """
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return []
    out: list[str] = []
    count = struct.unpack(">H", data[8:10])[0]
    i, k = 10, 1
    try:
        while k < count:
            tag = data[i]
            if tag == 1:  # CONSTANT_Utf8
                ln = struct.unpack(">H", data[i + 1:i + 3])[0]
                out.append(data[i + 3:i + 3 + ln].decode("utf-8", "replace"))
                i += 3 + ln
            elif tag in (7, 8, 16, 19, 20):
                i += 3
            elif tag == 15:
                i += 4
            elif tag in (5, 6):  # long/double 占两个槽位
                i += 9
                k += 1
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                i += 5
            else:
                break
            k += 1
    except (struct.error, IndexError):
        return out
    return out

def library_pattern() -> str | None:
    """从 jar 常量池提取库内置正则 —— 被检对象必须来自被调用方。"""
    jars = sorted(Path.home().joinpath(".m2/repository/org/testcontainers/elasticsearch")
                  .glob("*/elasticsearch-*.jar"))
    jars = [j for j in jars if "sources" not in j.name and "javadoc" not in j.name]
    if not jars:
        return None
    jar = jars[-1]
    try:
        with zipfile.ZipFile(jar) as z:
            data = z.read("org/testcontainers/elasticsearch/ElasticsearchContainer.class")
    except (KeyError, OSError, zipfile.BadZipFile) as e:
        print(f"  无法读取 {jar.name}: {e}", file=sys.stderr)
        return None
    for s in class_utf8_constants(data):
        if "started" in s and "message" in s:
            print(f"  来自 {jar.parent.name}/{jar.name}")
            return s
    return None


def es_declarations() -> list[tuple[Path, str | None]]:
    out = []
    for root in JAVA_ROOTS:
        if not root.exists():
            continue
        for jf in root.rglob("*.java"):
            if "/target/" in str(jf):
                continue
            try:
                raw = jf.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if "ElasticsearchContainer" not in raw:
                continue
            text = strip_java_comments(raw)
            if "ElasticsearchContainer" not in text:
                continue  # 只在 import/注释里提到
            m = WAITING_FOR.search(text)
            out.append((jf, m.group(1) if m else None))
    return out


def real_es_log() -> tuple[str | None, str]:
    """真跑 ES 抓日志。失败返回 (None, 原因) —— 调用方须判未判定。

    `--reuse-log` 只用于**开发期快速迭代**: 复用上一轮已抓的真实日志。
    它不改变判定逻辑, 但会让门禁失去"每次都向 ES 重新取证"这一性质 ——
    因此 CI/verify-e2e 调用时**不得**传该开关。
    """
    if "--reuse-log" in sys.argv:
        cached = Path("/tmp/es_real.log")
        if cached.exists() and cached.stat().st_size > 0:
            return cached.read_text(errors="replace"), "cached"
        print("  --reuse-log 但无缓存, 回退为真跑容器", file=sys.stderr)
    name = "es_wait_gate_probe"
    subprocess.run(["podman", "rm", "-f", name], capture_output=True, timeout=120)
    run = subprocess.run(
        ["podman", "run", "--rm", "--name", name,
         "-e", "discovery.type=single-node",
         "-e", "xpack.security.enabled=false",
         "-e", "ES_JAVA_OPTS=-Xms512m -Xmx512m",
         "-d", ES_IMAGE],
        capture_output=True, text=True, timeout=240)
    if run.returncode != 0:
        return None, f"podman run rc={run.returncode}: {run.stderr.strip()[:200]}"
    logs = ""
    deadline = time.time() + ES_READY_TIMEOUT
    while time.time() < deadline:
        p = subprocess.run(["podman", "logs", name],
                           capture_output=True, text=True, timeout=60)
        logs = (p.stdout or "") + (p.stderr or "")
        if ES_READY_ANCHOR in logs:
            return logs, "ok"
        st = subprocess.run(["podman", "inspect", "--format", "{{.State.Status}}", name],
                            capture_output=True, text=True, timeout=60)
        if st.stdout.strip() == "exited":
            return None, "ES 容器在 ready 前退出"
        time.sleep(5)
    return None, f"{ES_READY_TIMEOUT}s 内未捕获锚点"


def java_match(pattern: str, log_path: str) -> tuple[bool, str]:
    """用 Java Pattern 的语义判定 —— LogMessageWaitStrategy 是逐行 find()。

    必须用 Java 而不是 Python: Python 的 re 与 java.util.regex 在
    反斜杠转义 / 逐行语义上有差异, 用错引擎就是在验一个不存在的对象。

    ⚠️【2026-10-07 自修】初版把 pattern 当**命令行参数**传给 java, 结果
    正则末尾的 `\n` 在 shell + Java 参数两层转义里被吃掉, 变成一个孤立
    换行, Java 报 `PatternSyntaxException: Unclosed group near index 44`
    —— 而我的代码把 `rc != 0` 直接当成"不匹配", 于是门禁对**能匹配**的
    库内置正则报了红。这正是「探针自己坏掉时, 会把被测系统判成失败」。
    正解: 通过**文件**传递, 不经命令行; 并把探针失败与"不匹配"分开返回。
    """
    tmp = Path("/tmp/__es_gate_match")
    tmp.mkdir(exist_ok=True)
    (tmp / "pattern.txt").write_text(pattern)
    src = (
        "import java.nio.file.*;import java.util.regex.*;"
        "public class __M{public static void main(String[] a)throws Exception{"
        "String log=Files.readString(Path.of(a[0]));"
        "String p=Files.readString(Path.of(a[1]));"
        "for(String l:log.split(\"\\n\",-1))"
        "if(Pattern.compile(p).matcher(l).find()){System.out.println(\"HIT\");return;}"
        "System.out.println(\"MISS\");}}"
    )
    (tmp / "__M.java").write_text(src)
    r = subprocess.run(["java", str(tmp / "__M.java"), log_path,
                        str(tmp / "pattern.txt")],
                       capture_output=True, text=True, timeout=300)
    if r.returncode != 0:
        # ⚠️ 探针失败 ≠ 不匹配。必须区分, 否则就是用坏探针下结论。
        return False, f"UNDETERMINED(java 探针失败: {r.stderr.strip()[:200]})"
    return "HIT" in r.stdout, r.stdout.strip()


def main() -> int:
    print("[自检] 探针自身")
    errs = self_test_probe()
    if errs:
        for e in errs:
            print(f"  FAIL {e}", file=sys.stderr)
        print("探针自检未过 ⇒ 后续结论不可信, 判未判定。", file=sys.stderr)
        return 2
    print("  ok")

    lib = library_pattern()
    if lib is None:
        print("FAIL: 无法从 jar 提取库内置正则 —— 被检对象缺失, 不能判绿",
              file=sys.stderr)
        return 2
    print(f"\n[被检对象 1/3] 库内置正则（来自 jar 常量池）\n  {lib!r}")

    decls = es_declarations()
    if not decls:
        print("FAIL: 未扫到任何 ElasticsearchContainer 声明 —— 扫描器失效",
              file=sys.stderr)
        return 2
    print(f"\n[被检对象 2/3] 源码里的 ES 容器声明: {len(decls)} 处")
    for jf, pat in decls:
        rel = jf.relative_to(ROOT)
        print(f"  {'显式' if pat else '默认'} {rel}" + (f"\n         {pat}" if pat else ""))

    print(f"\n[证据来源] 启动真实 ES 容器抓日志（{ES_IMAGE}）")
    logs, why = real_es_log()
    if logs is None:
        print(f"FAIL: 取不到真实日志 —— {why}\n"
              "      等待策略是否匹配**未被判定**，不是通过。", file=sys.stderr)
        return 2
    Path("/tmp/es_real.log").write_text(logs, errors="replace")
    print(f"  已抓到 {len(logs)} 字节；锚点 {ES_READY_ANCHOR!r} "
          f"{'在' if ES_READY_ANCHOR in logs else '**不在**'}日志中")
    if ES_READY_ANCHOR not in logs:
        print("FAIL: 锚点本身不在真实日志里 —— 探针基准失效, 不能判绿",
              file=sys.stderr)
        return 2

    rc = 0
    print("\n[判定]")
    ok, detail = java_match(lib, "/tmp/es_real.log")
    print(f"  {'ok  ' if ok else 'FAIL'} 库内置正则命中真实日志  ({detail})")
    if "UNDETERMINED" in detail:
        print("       ⇒ 探针失效, 本项**未判定**, 不能作为失配结论。",
              file=sys.stderr)
        return 2
    if not ok:
        print("       ⇒ ES 升级后内置策略已失配: IT 会永远以\n"
              "         'Timed out waiting for log output matching' 失败,\n"
              "         且**加大超时无效**（在等一个永不出现的模式）。", file=sys.stderr)
        rc = 1

    for jf, pat in decls:
        if pat is None:
            print(f"  ok   {jf.relative_to(ROOT)} 用库默认（已单独验过）")
            continue
        ok, det = java_match(pat.replace(r'\"', '"'), "/tmp/es_real.log")
        if "UNDETERMINED" in det:
            print(f"  ??   {jf.relative_to(ROOT)} 探针失效, 未判定", file=sys.stderr)
            return 2
        print(f"  {'ok  ' if ok else 'FAIL'} {jf.relative_to(ROOT)} 的显式 waitingFor")
        if not ok:
            print(f"       {pat} 在真实日志中匹配不到。", file=sys.stderr)
            rc = 1

    print("\nPASS: 所有 ES 等待策略在真实 ES 日志上均命中" if rc == 0
          else "\nFAIL: 见上")
    return rc


if __name__ == "__main__":
    sys.exit(main())