#!/usr/bin/env python3
"""
门禁: gRPC fixed port 必须显式指向占位变量, 不能写成裸值。
对应报告节: 架构 Q4 / 质量 Q5 / 性能 Q5

证据: 原 system-module 把 grpc server port 写死 9090; 若任意 service 又写 9090 会触发 BindException。
"""
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
MODULES = [str(Path(REPO) / "cloud-infra-modules")]

BUGS = []

for mod in MODULES:
    mod_p = Path(mod)
    ymls = list((mod_p / "src/main/resources").rglob("application*.yml"))
    for f in ymls:
        text = f.read_text(encoding="utf-8")
        # 正则匹配: spring ... grpc ... server ... port: 裸值
        matches = list(re.finditer(r"spring:.*?grpc:\s*server:\s*\n\s+port:\s*([A-Za-z_$][^:]*)", text, flags=re.DOTALL))
        for m in matches:
            port_val = m.group(1)
            # 检查是否在 ${} 占位形式内 (对打开括号之后再匹配到任意)
            start = max(0, m.start() - 200)
            ctx = text[start:m.end()]
            if "$" in ctx and ":${" in ctx:
                continue
            BUGS.append(f"{f}: grpc.server.port={port_val} (应写 GRPC_SERVER_PORT)")

if BUGS:
    print("FAILED: 发现 gRPC fixed port 问题:")
    for b in BUGS:
        print(f"  {b}")
    sys.exit(1)
print("OK: 所有模块 grpc server port 已显式占位")
