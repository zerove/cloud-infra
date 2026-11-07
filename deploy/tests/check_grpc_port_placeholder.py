#!/usr/bin/env python3
"""
门禁: gRPC 配置必须落在真正生效的前缀上, 且端口必须可被环境覆盖。

为什么用 YAML 对象图而不是正则
------------------------------
本门禁的前身是正则实现, 而且**从未扫描到任何文件**: `REPO` 取成 `deploy/` 而不是
仓库根, 扫描路径又少了模块那一层, 于是它一直打印 "OK" —— 典型假绿。
正则本身也不可靠: 它会因为 `spring:` 与 `grpc:` 之间的任意文本而误判,
也会把注释和标签当成配置(本项目已 7 次栽在"被检字面量出现在注释/标签里")。
现在直接解析 YAML, 按**实际命中的键路径**判定。

判定依据(以代码为准, 不以文档为准)
----------------------------------
`CloudInfraGrpcProperties` 的 `@ConfigurationProperties(prefix = "cloudinfra.grpc")`
只声明了扁平键: `port` / `in-process-server-name` / `enabled` / ... ,
类中**没有** `Server` 子类。因此:
  * `spring.grpc.*`  —— 死键。本仓库未引入 spring-grpc starter, 无人读取。
  * `cloudinfra.grpc.server.*` —— 同样是死键(会静默忽略, 端口回落到默认 9090)。
  * `cloudinfra.grpc.port` 才是生效的键。

缺陷形态
--------
1. 出现 `spring.grpc`             → 死配置, 报红。
2. 出现 `cloudinfra.grpc.server`  → 死配置, 报红。
3. 有 `cloudinfra.grpc.port` 但不是 `${...}` 占位 → 无法在多实例/CI 上避端口冲突, 报红。
   (未配置 gRPC 的模块不强制要求该项。)

负向对照
--------
  A. `cloudinfra.grpc.port` 改成裸值 9090      → rc=1
  B. 改回 `spring.grpc.server.port` 死键       → rc=1
  C. 改回 `cloudinfra.grpc.server.port` 死键   → rc=1
  恢复后                                        → rc=0
"""
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
MODULES_DIR = REPO / "cloud-infra-modules"

BUGS = []


def walk(node, path):
    """深度遍历, 产出 (键路径元组, 值)。"""
    if isinstance(node, dict):
        for k, v in node.items():
            yield path + (str(k),), v
            yield from walk(v, path + (str(k),))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk(v, path + (str(i),))


def check_file(path: Path) -> None:
    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        BUGS.append(f"{path}: YAML 解析失败: {exc}")
        return
    if not isinstance(doc, dict):
        return

    for keys, value in walk(doc, ()):
        # 1/2. 死前缀
        if keys[:2] == ("spring", "grpc"):
            BUGS.append(f"{path}: {'/'.join(keys)} 是死配置 —— "
                        "本仓库未引入 spring-grpc starter; 应改为 cloudinfra.grpc.port")
        if keys[:3] == ("cloudinfra", "grpc", "server"):
            BUGS.append(f"{path}: {'/'.join(keys)} 是死配置 —— "
                        "CloudInfraGrpcProperties 没有 server 子类; 应改为 cloudinfra.grpc.port")

    grpc = doc.get("cloudinfra", {}).get("grpc") if isinstance(doc.get("cloudinfra"), dict) else None
    if isinstance(grpc, dict) and "port" in grpc:
        val = grpc["port"]
        if not (isinstance(val, str) and val.startswith("${")):
            BUGS.append(f"{path}: cloudinfra.grpc.port 未用占位变量 (值={val!r}), "
                        "无法在多实例/CI 上避开端口冲突")


ymls = sorted(MODULES_DIR.glob("*/src/main/resources/application*.yml"))
for f in ymls:
    check_file(f)

if BUGS:
    print("FAILED: gRPC 配置缺陷:")
    for b in BUGS:
        print(f"  {b}")
    sys.exit(1)

print(f"OK: 已解析 {len(ymls)} 个模块配置, gRPC 配置均落在生效前缀且端口可覆盖")
