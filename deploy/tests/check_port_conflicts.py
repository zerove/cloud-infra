#!/usr/bin/env python3
"""前置门禁: compose 声明的宿主端口是否会被**外部**进程抢占。

存在原因: 容器**创建阶段**的失败往往与配置无关, 但报错只显示
`Couldn't listen on requested ports`, 堆栈指向 Testcontainers,
与端口占用毫无字面关联。本门禁把这类环境前提**前置**拦住。

⚠️ 2026-10-08 自更正: 初版把「compose 端口已被占用」一律判为冲突,
结果把本项目**正常运行**的端口(cloud-skywalking-ui 常年占 18080)报成故障。
真根因是「**要用的端口被别人抢了**」, 而非「端口有人在用」——
两者的区别就是**占用者是不是本项目自己的容器**, 判据里必须带上。

退出码:
  0 = 无抢占（本项目容器占用自己的端口属正常）
  1 = 有端口被**外部**进程占用 —— 容器创建会失败
  2 = 探针不可信（compose/ss/podman 读不到）—— 不判绿
"""
import json
import os
import re
import subprocess
import sys

BASE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
COMPOSE = os.path.join(BASE, "deploy", "compose.yaml")

# compose 里端口的写法:  - "8080:8080" / - 3000:3000 / - "127.0.0.1:9000:9000"
PORT_RE = re.compile(
    # 一行形如:  - "9301:9301"   # 管理端口 (ADR-008)
    # 必须容忍**行尾注释**, 否则带注释的端口全部被漏检(假阴性)。
    # ⚠️ 第一版写成 `\s*$` 结尾 —— 只匹配无注释的行, 33 行里漏掉 8 个,
    #    其中包括 18080。漏检的端口永远不会进冲突检查。
    # 也必须支持**端口范围**: "9010-9011:9000-9001"
    r'^\s*-\s*["\']?'
    r'(?:(?P<bip>[\d.]+):)?'
    r'(?P<lo>\d+)(?:-(?P<hi>\d+))?'
    r':\d+(?:-\d+)?'
    r'\s*["\']?'
    r'(?:\s+#.*)?$'
)
# 防御: 单行声明的最大端口跨度。超过则视为提取规则命中了脏数据。
MAX_PORT_SPAN = 64


def fail(msg):
    print("FAIL: %s" % msg)
    return 1


def probe_unreliable(msg):
    print("FAIL: %s" % msg)
    print("      这是探针不可信, 不是端口有问题, 不判绿")
    return 2


def read_compose_ports():
    """从 compose.yaml 提取宿主侧端口。返回 (端口集合, 错误信息)。"""
    if not os.path.exists(COMPOSE):
        return None, "compose 文件不存在: %s" % COMPOSE
    try:
        with open(COMPOSE, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError as e:
        return None, "无法读取 compose: %s" % e
    ports = set()
    for lineno, ln in enumerate(lines, start=1):
        m = PORT_RE.match(ln)
        if not m:
            continue
        lo = int(m.group("lo"))
        hi = int(m.group("hi") or lo)
        if hi < lo:
            # 形如 "9011-9010" —— 倒序跨度, 不解释为范围, 只取起点
            hi = lo
        if hi - lo > MAX_PORT_SPAN:
            # ⚠️ 用 enumerate 的行号, 不用 lines.index(ln) ——
            #    后者对**重复行**返回首个下标, 会把错误行号报成别的行。
            return None, ("compose 第 %d 行端口跨度异常(%d-%d), 提取规则可能已失效"
                          % (lineno, lo, hi))
        ports.update(range(lo, hi + 1))
    if not ports:
        return None, "compose 中未解析出任何端口 —— 提取规则可能已失效"
    return ports, None


def listening_ports():
    """返回 {端口: 占用者描述}。区分「取不到值」与「取到空」。"""
    # 必须保留 stderr: ss 失败时 stdout 为空, 与"没有监听"无法区分。
    # 这正是 AGENTS.md 第五课补记四那条 —— 取不到值 ≠ 取到假值。
    try:
        r = subprocess.run(["ss", "-ltnp"], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as e:
        return None, "ss 执行失败: %s" % e
    if r.returncode != 0 and not r.stdout.strip():
        return None, "ss 返回 rc=%d 且无输出: %s" % (r.returncode, r.stderr.strip()[:200])
    occupied = {}
    for ln in r.stdout.splitlines()[1:]:
        parts = ln.split()
        if len(parts) < 4:
            continue
        local = parts[3]
        port = local.rsplit(":", 1)[-1]
        if not port.isdigit():
            continue
        users = ""
        m = re.search(r'users:\(\("([^"]+)",pid=(\d+)', ln)
        if m:
            users = "%s(pid=%s)" % (m.group(1), m.group(2))
        prev = occupied.get(int(port))
        occupied[int(port)] = users or prev or "unknown"
    return occupied, None


def own_container_ports():
    """本项目**正在运行的容器**所监听的宿主端口。

    这些端口被占用是**正常**的 —— 容器本就要占自己的端口。
    判据必须排除它们, 否则每次都会误报成故障。

    ⚠️ 返回 None 表示「拿不到」而非「没有」: podman 失败时不能让判据退化成
    「全部占用都算冲突」, 那会把常态说成故障(本门禁第一版的错误)。
    此时按**保守但诚实**处理: 报 rc=2, 不判绿。
    """
    try:
        r = subprocess.run(
            ["podman", "ps", "--format", "{{.Names}}\t{{.Ports}}"],
            capture_output=True, text=True, timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as e:
        return None, "podman 执行失败: %s" % e
    if r.returncode != 0:
        return None, "podman 返回 rc=%d: %s" % (r.returncode, r.stderr.strip()[:200])
    own = set()
    containers = []
    for ln in r.stdout.splitlines():
        if not ln.strip():
            continue
        cols = ln.split("\t")
        name = cols[0]
        containers.append(name)
        own |= parse_host_ports(cols[-1] or "")
    return own, None, containers


def parse_host_ports(ports_col):
    """从 podman 的 Ports 列提取**宿主侧**端口。

    ⚠️ 必须同时处理**单端口与端口范围**两种写法（2026-10-08 实测）:
        0.0.0.0:9000->9000/tcp          → {9000}
        0.0.0.0:9010-9011->9000-9001/tcp  → {9010, 9011}
        0.0.0.0:4317-4318->4317-4318/tcp → {4317, 4318}

    我第一版只用 `:(\\d+)->`, 于是所有**范围**映射的端口都抓不到 ——
    而 cloud-minio(9010-9011) 与 cloud-otel-collector(4317-4318) 恰恰是范围的,
    结果它们**自己占着的端口被报成"被外部抢占"**。
    ⇒ 与 AGENTS.md 第五课补记二(ES 索引名)同型: 判据只覆盖了一种形态。
    """
    got = set()
    # 形如  <addr>:<ports>-><containerPorts>
    for m in re.finditer(r':(\d+(?:-\d+)?)->', ports_col):
        lo, _, hi = m.group(1).partition("-")
        lo_i, hi_i = int(lo), int(hi or lo)
        # 防御: 上限 64 个端口, 防止异常范围把集合撑爆
        if hi_i - lo_i > 64:
            hi_i = lo_i + 64
        got.update(range(lo_i, hi_i + 1))
    return got


def main():
    ports, err = read_compose_ports()
    if ports is None:
        return probe_unreliable(err)

    occupied, err = listening_ports()
    if occupied is None:
        return probe_unreliable(err)

    own, err, containers = own_container_ports()
    if own is None:
        return probe_unreliable(
            "%s —— 无法区分「没有运行容器」与「查不到容器」, 不判绿" % err)

    conflicts = []
    for p in sorted(ports):
        if p not in occupied:
            continue
        if p in own:
            continue  # 本项目自己的容器占着, 正常
        conflicts.append((p, occupied[p]))

    print("compose 声明 %d 个宿主端口" % len(ports))
    print("运行中容器 %d 个, 占用 %d 个端口(已排除, 属正常)"
          % (len(containers), len(own)))

    if not conflicts:
        print("RESULT: PASS (无抢占端口)")
        return 0

    print("发现 %d 个端口被**本项目容器之外**的进程占用:" % len(conflicts))
    for p, holder in conflicts:
        print("  ★ %d  <- %s" % (p, holder))
    print("RESULT: FAIL")
    print()
    print("这类抢占会让容器**创建阶段**失败, 报错只有")
    print("\"Couldn't listen on requested ports\", 堆栈指向 Testcontainers,")
    print("与端口被抢毫无字面关联 —— 排查成本极高。")
    print("处理: 停掉占用者, 或改 compose 里对应的宿主端口。")
    return 1


if __name__ == "__main__":
    sys.exit(main())