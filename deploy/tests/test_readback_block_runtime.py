#!/usr/bin/env python3
"""对 deploy/up.sh 的回读块做**真实执行**验证 (不是静态分析)。

## 为什么静态守护不够

`test_config_readback_gate.py` 只看代码结构。它无法回答:
  · 那个 curl URL 到底能不能返回配置?
  · `[ -n "$content" ]` 在配置为空时是不是真的判假?
  · 重试循环是不是真的会在"先空后有"时放行?

这些是 Bash 运行时行为, 只能真跑。本测试从 up.sh **原文抽取**那段回读逻辑
(不复制粘贴 —— 复制粘贴会让它随 up.sh 漂移而无人知晓), 喂给一个桩 Nacos 服务执行。

## 对照设计

  场景 1  全部配置可读        -> 期望 CONFIG_READY=1
  场景 2  一个 dataId 返回空  -> 期望最终 exit 1 (fail-closed)
  场景 3  先空两次后有值      -> 期望重试后放行, 且不 exit
  场景 4  空 body 但 HTTP 200 -> 期望仍判为不可读 (挡不住 "存在即非空" 的误判)

场景 4 是关键: Nacos 对不存在的 dataId 可能返回 200 + 空 body。
只判 curl 退出码的实现会在这一档假绿。
"""
import http.server
import re
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

UP_SH = Path(__file__).resolve().parents[1] / "up.sh"


def extract_readback_block() -> str:
    """从 up.sh 原文抽取 3c 回读块, 去掉注释行。"""
    lines = UP_SH.read_text(encoding="utf-8").splitlines()
    try:
        start = next(i for i, l in enumerate(lines) if "3c." in l and "回读验证" in l)
    except StopIteration:
        raise AssertionError("up.sh 里找不到 3c 回读验证块")
    block = []
    for l in lines[start:]:
        block.append(l)
        if l.strip() == "fi" and any("exit 1" in x for x in block):
            break
    # 去掉纯注释行, 但保留 shebang 之外的逻辑
    body = [l for l in block if not l.strip().startswith("#")]
    return "\n".join(body)


class StubNacos(http.server.BaseHTTPRequestHandler):
    """返回可配置的 Nacos 配置响应。"""

    # dataId -> 内容; None 表示空 body
    store: dict[str, str | None] = {}
    served: list[str] = []

    def do_GET(self):
        m = re.search(r"dataId=([^&]+)", self.path)
        data_id = m.group(1) if m else ""
        type(self).served.append(data_id)
        content = type(self).store.get(data_id)
        if content is None and data_id not in type(self).store:
            body = b""
        else:
            body = (content or "").encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def run_block(block: str, port: int, configs: list[str]) -> subprocess.CompletedProcess:
    """把抽取的块放进一个临时脚本跑起来, 返回进程结果。"""
    with tempfile.TemporaryDirectory() as td:
        cfgdir = Path(td) / "config"
        cfgdir.mkdir()
        for c in configs:
            (cfgdir / c).write_text("k: v\n", encoding="utf-8")

        script = f"""#!/bin/bash
set -u
# ⚠️ 必须清空代理变量(2026-10-05 实测)。本脚本访问的是 127.0.0.1 的临时
#   端口, 而宿主常设 http_proxy=http://127.0.0.1:7890 —— curl 会把回环
#   请求也交给代理, 于是本地临时服务收到 502 Bad Gateway。
#   实测对照: 带代理 exit=1 (502), env -u http_proxy 后 exit=0 (4 场景全过)。
#   即这是**测试自身缺隔离**, 与被测的 readback 块无关。
#   ⚠️ 宿主 no_proxy 常见写法 "127.*" 对 curl 无效(它只认 127.0.0.1 或 .1),
#   不能依赖 no_proxy, 必须显式清空。
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY
export no_proxy='*' NO_PROXY='*'

NACOS_URL="http://127.0.0.1:{port}/nacos"
CONFIG_DIR="{cfgdir}"

{block}

echo "RESULT CONFIG_READY=$CONFIG_READY"
"""
        sp = Path(td) / "block.sh"
        sp.write_text(script, encoding="utf-8")
        # sleep 2s * 15 次太慢, 把 sleep 换掉
        raw = sp.read_text(encoding="utf-8").replace("sleep 2", "sleep 0.05")
        sp.write_text(raw, encoding="utf-8")
        return subprocess.run(
            ["bash", str(sp)], capture_output=True, text=True, timeout=120
        )


def main() -> int:
    failures: list[str] = []
    block = extract_readback_block()
    assert "MISSING_CFG" in block, "抽取到的块里没有 MISSING_CFG, 抽取逻辑可能错位"

    srv = http.server.HTTPServer(("127.0.0.1", 0), StubNacos)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    configs = ["a.yaml", "b.yaml", "c.yaml"]

    # --- 场景 1: 全部可读 ----------------------------------------------------
    StubNacos.store = {c: "k: v" for c in configs}
    r = run_block(block, port, configs)
    if "CONFIG_READY=1" not in r.stdout:
        failures.append(f"场景1 全部可读应放行, 实际:\n{r.stdout}{r.stderr}")
    if r.returncode != 0:
        failures.append(f"场景1 不应非零退出, 实际 {r.returncode}")

    # --- 场景 2: 一个为空 -> fail-closed ------------------------------------
    StubNacos.store = {"a.yaml": "k: v", "b.yaml": None, "c.yaml": "k: v"}
    r = run_block(block, port, configs)
    if r.returncode == 0:
        failures.append(
            f"场景2 配置为空应 exit 1, 实际 returncode=0 (放行了)\n{r.stdout}{r.stderr}"
        )
    if "不可读" not in r.stdout and "不可读" not in r.stderr:
        failures.append("场景2 应打印含'不可读'的诊断, 实际无")

    # --- 场景 3: 先空后有 -> 重试后放行 -------------------------------------
    StubNacos.store = {"a.yaml": "k: v", "b.yaml": None, "c.yaml": "k: v"}
    hits: dict[str, int] = {}

    class LateFill(StubNacos):
        def do_GET(self):
            m = re.search(r"dataId=([^&]+)", self.path)
            did = m.group(1) if m else ""
            if did == "b.yaml":
                hits["b"] = hits.get("b", 0) + 1
                if hits["b"] >= 4:      # 前 3 次空, 第 4 次起有值
                    content = "k: late"
                else:
                    content = None
                body = (content or "").encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            StubNacos.do_GET(self)

    srv2 = http.server.HTTPServer(("127.0.0.1", 0), LateFill)
    LateFill.store = {"a.yaml": "k: v", "c.yaml": "k: v"}
    port2 = srv2.server_address[1]
    threading.Thread(target=srv2.serve_forever, daemon=True).start()
    r = run_block(block, port2, configs)
    if "CONFIG_READY=1" not in r.stdout:
        failures.append(
            f"场景3 '先空后有' 应在重试后放行, 实际:\n{r.stdout}{r.stderr}"
        )

    # --- 场景 4: 空 body + HTTP 200 ----------------------------------------
    # 已并入场景 2 (StubNacos 对未知/None 都返回 200 + 空 body),
    # 这里单独确认桩本身确实返回 200:
    import urllib.request

    # ⚠️ 必须用显式 ProxyHandler({}) 关闭代理(2026-10-05 实测)。
    #   裸 urlopen 会走 urllib 的**全局默认 opener**, 它读环境里的
    #   http_proxy —— 而本测试访问 127.0.0.1 的临时端口, 于是回环请求
    #   也被交给代理, 直接 502 Bad Gateway。
    #
    #   ⚠️ 我第一版修错了位置: 只在生成的 bash 子脚本里 unset 代理变量,
    #      而 502 实际来自**本行**的 Python 父进程请求 ⇒ 修复完全无效
    #      (实测带代理仍 exit=1)。判据必须指向 traceback 指出的真实请求点。
    #   ⚠️ 也不能依赖 no_proxy="127.*": curl/urllib 只认 127.0.0.1 或 .1。
    _opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    with _opener.open(
        f"http://127.0.0.1:{port}/nacos/v3/client/cs/config?dataId=nope.yaml"
        f"&groupName=DEFAULT_GROUP&namespaceId=public"
    ) as resp:
        if resp.status != 200 or resp.read() != b"":
            failures.append(
                f"桩行为不符预期: 期望 200+空 body, 实际 {resp.status}"
            )

    srv.shutdown()
    srv2.shutdown()

    for f in failures:
        print(f"FAIL {f}")
    if failures:
        return 1
    print("PASS 回读块真实执行验证: 4 场景 (可读/为空 fail-closed/先空后有/200 空 body)")
    return 0


if __name__ == "__main__":
    sys.exit(main())