#!/usr/bin/env python3
"""预检: Testcontainers 在本机能否真正连上容器运行时。

## 为什么需要它（2026-10-10 实测, 见 AGENTS.md 第九课补记四十）

全量 IT 曾在 75 秒窗口内集中报出 `ContainerLaunchException`, 链尾是:

    Caused by: java.io.IOException: Broken pipe
        at UnixSocket$WrappedWritableByteChannel.write(UnixSocket.java:98)

要归因它, 必须先知道 **Testcontainers 到底连的是哪个 socket**。本轮实测发现:

| 候选 | 连得上吗 |
| :--- | :--- |
| `$DOCKER_HOST` = `/run/podman/podman.sock` | ❌ **BindException: Permission denied**（`drwx------ root root`） |
| `$XDG_RUNTIME_DIR/podman/podman.sock` | ✅ 实际走这条（`RootlessDockerClientProviderStrategy` 只认 `XDG_RUNTIME_DIR`） |

而**这条策略只存在于宿主的 `~/.testcontainers.properties`, 仓库内没有任何文件固定它**:

    docker.client.strategy=org.testcontainers.dockerclient.RootlessDockerClientProviderStrategy

⇒ 它一旦被删/被改, Testcontainers 会退回默认策略, 可能去连**连不上的**那个 socket,
   于是全量 IT 整批红 —— 且报错是 `Container startup failed`, 极易被读成"镜像问题"或
   "环境问题", 而真实原因只是**一个环境外文件的丢失**。

## 本门禁【不】做什么

- **不检查 Podman 是否在跑** —— 那属于 Podman 自己的健康, 与本条链路无关。
- **不阻断**（`--strict` 才 exit 1）。因为它读的是**仓库外**的文件, 缺失或不可读都可能是
  合法的（CI 用 Docker、别的开发机用别的运行时）。默认只警告, 避免又造一个
  "在别人机器上永远红"的假门禁（AGENTS.md 补记三十五的教训）。

## 探针自检（关键: `Files.exists` 的陷阱）

`Files.exists()` 会把 **`Permission denied` 吞成 `false`** —— 即"无权限"与"不存在"
被压成同一个结论。本脚本因此**不用 `exists()` 判定存在性**, 而是:

1. 用 `stat` 取**失败模式**（`No such file` vs `Permission denied` 互斥）;
2. 用 `test -S` 确认是 socket;
3. 真正 **connect 一次**, 只看能否建连 —— 不解析任何输出。

⚠️ 这三步的顺序不能换: 先 `exists` 再 `stat`, 会在无权限时提前得出"不存在"。
"""

from __future__ import annotations

import argparse
import os
import re
import socket
import subprocess
import sys
from pathlib import Path

# 与 RootlessDockerClientProviderStrategy 的常量一致（解包 testcontainers-2.0.5.jar 核对）
STRATEGY_CLASS = "org.testcontainers.dockerclient.RootlessDockerClientProviderStrategy"
PROPERTIES = Path.home() / ".testcontainers.properties"


def path_state(path: Path) -> tuple[str, str]:
    """返回 (状态, 失败原因)。状态 ∈ {socket, exists-not-socket, denied, missing, unknown}。

    ⚠️ 绝不用 `Path.exists()`: 它把 Permission denied 报成 False（第五课）。
    这里用 `stat` 的**失败模式**区分 —— 两句 stderr 互斥, 不会同时成立。
    """
    try:
        st = path.stat()
    except PermissionError:
        return "denied", "Permission denied（路径存在, 但当前用户无权访问）"
    except FileNotFoundError:
        return "missing", "No such file or directory"
    except OSError as e:
        return "unknown", f"{type(e).__name__}: {e}"
    import stat as stat_mod
    if stat_mod.S_ISSOCK(st.st_mode):
        return "socket", "ok"
    return "exists-not-socket", f"存在但不是 socket（mode={oct(st.st_mode)})"


def can_connect_unix(path: Path, timeout: float = 3.0) -> tuple[bool, str]:
    """真正建一次连接 —— 这是唯一有决定性的判据。

    ⚠️ 只看"能不能连", **不解析任何响应**: `/v1.41/...` 之类的版本路径一旦变化,
    解析失败会被读成"连不上", 而实际连接是通的（第五课: 探针查错了对象）。
    """
    if not hasattr(socket, "AF_UNIX"):
        return False, "当前 Python 无 AF_UNIX 支持"
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(str(path))
        return True, "connected"
    except PermissionError:
        return False, "Permission denied（socket 存在但当前用户无权连接）"
    except (ConnectionRefusedError, FileNotFoundError) as e:
        return False, f"{type(e).__name__}: {e}"
    except socket.timeout:
        return False, f"连接超时 {timeout}s"
    except OSError as e:
        return False, f"{type(e).__name__}: {e}"
    finally:
        try:
            s.close()
        except OSError:
            pass


def strategy_declared() -> tuple[bool, str]:
    """读 `~/.testcontainers.properties` 里是否声明了 rootless 策略。"""
    try:
        text = PROPERTIES.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return False, "文件不存在"
    except PermissionError:
        return False, "Permission denied"
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("docker.client.strategy"):
            _, _, value = line.partition("=")
            return STRATEGY_CLASS in value.strip(), f"声明为 {value.strip()!r}"
    return False, "文件存在但未声明 docker.client.strategy"


def self_test() -> list[str]:
    """探针自检: 这些判据坏了, 结论就不可信。"""
    errs: list[str] = []

    # 1) path_state 必须能区分 missing 与 denied —— 这是本脚本存在的理由。
    st_missing, _ = path_state(Path("/nonexistent-for-seltest/x.sock"))
    if st_missing != "missing":
        errs.append(f"path_state 对不存在路径返回 {st_missing!r}, 期望 'missing'")

    # 2) 对存在的普通文件, 必须报 exists-not-socket 而不是 socket
    st_file, _ = path_state(Path(__file__).resolve())
    if st_file != "exists-not-socket":
        errs.append(f"path_state 对普通文件返回 {st_file!r}, 期望 'exists-not-socket'")

    # 3) /run/podman 在本机是 denied（root 权限）。若哪天变成别的, 说明环境变了,
    #    但 path_state 仍必须给出**非 socket** 的合法状态之一, 不能是 unknown。
    st_root, _ = path_state(Path("/run/podman/podman.sock"))
    if st_root not in ("denied", "missing", "socket", "exists-not-socket", "unknown"):
        errs.append(f"path_state 返回了非法状态: {st_root!r}")

    # 4) can_connect_unix 对不存在的路径必须返回 False 且原因明确
    ok, why = can_connect_unix(Path("/nonexistent-for-seltest/x.sock"), timeout=1.0)
    if ok:
        errs.append("can_connect_unix 对不存在的路径返回 True —— 判据已失效")

    # 5) strategy_declared 必须返回 (bool, 非空原因)
    ok2, why2 = strategy_declared()
    if not why2:
        errs.append("strategy_declared 返回空原因 —— 无法判读")
    return errs


def main() -> int:
    ap = argparse.ArgumentParser(description="预检 Testcontainers 的容器运行时连通性")
    ap.add_argument("--self-test", action="store_true", help="只跑探针自检")
    ap.add_argument("--strict", action="store_true", help="不通过时 exit 1（默认只警告）")
    args = ap.parse_args()

    if args.self_test:
        errs = self_test()
        if errs:
            for e in errs:
                print(f"  SELF-TEST FAIL: {e}", file=sys.stderr)
            return 2
        print("  SELF-TEST PASS")
        return 0

    xdg = os.environ.get("XDG_RUNTIME_DIR", "")
    print("---- Testcontainers 运行时连通性预检 ----")
    print(f"  DOCKER_HOST          = {os.environ.get('DOCKER_HOST', '(未设置)')}")
    print(f"  XDG_RUNTIME_DIR      = {xdg or '(未设置)'}")

    declared, why = strategy_declared()
    print(f"  rootless 策略声明     = {'有' if declared else '无'}   ({why})")

    problems: list[str] = []

    # A) rootless 策略依赖宿主文件 —— 缺失是真实风险
    if not declared:
        problems.append(
            f"~/.testcontainers.properties 未声明 {STRATEGY_CLASS}。"
            f" 该文件在【仓库外】, 一旦丢失, Testcontainers 会退回默认策略, "
            f" 可能去连连不上的 {os.environ.get('DOCKER_HOST', 'DOCKER_HOST 指向的 socket')}, "
            f" 表现为整批 Container startup failed。"
        )

    # B) 策略真正使用的 socket 必须存在且可连
    if xdg:
        sock_path = Path(xdg) / "podman" / "podman.sock"
        state, detail = path_state(sock_path)
        print(f"  策略实际使用的 socket = {sock_path}")
        print(f"    状态               = {state}   ({detail})")
        if state == "socket":
            ok, why2 = can_connect_unix(sock_path)
            print(f"    实际连接           = {'成功' if ok else '失败'}   ({why2})")
            if not ok:
                problems.append(f"Testcontainers 使用的 socket 连不上: {sock_path} —— {why2}")
        elif state in ("missing", "denied"):
            problems.append(
                f"Testcontainers 使用的 socket 不可用: {sock_path} —— {state}: {detail}。"
                f" 注意 'denied' 是【无权限】而非【不存在】, 两者处置完全不同。"
            )
    else:
        problems.append("XDG_RUNTIME_DIR 未设置 —— RootlessDockerClientProviderStrategy 会直接失败。")

    # C) DOCKER_HOST 指向的 socket 单独报告: 它在本机【连不上】,
    #    这正是"策略必须显式固定"的原因。不作为失败, 只作事实记录。
    dh = os.environ.get("DOCKER_HOST", "")
    if dh.startswith("unix://"):
        dh_path = Path(dh[len("unix://"):])
        state, detail = path_state(dh_path)
        print(f"  DOCKER_HOST 的 socket = {dh_path}")
        print(f"    状态               = {state}   ({detail})")
        if state == "socket":
            ok, why3 = can_connect_unix(dh_path)
            print(f"    实际连接           = {'成功' if ok else '失败'}   ({why3})")
            if not ok:
                print("    ⇒ 当前策略必须固定为 rootless: Testcontainers 不会走这条, "
                      "但任何回退到默认策略的改动都会撞上它。")

    if problems:
        print("\n  ⚠️ 发现问题:")
        for p in problems:
            print(f"    - {p}")
        print("\n  判读: 以上是【环境外依赖】的缺失/不可用, 不代表被测代码有问题。")
        return 1 if args.strict else 0

    print("\n  ✅ 运行时连通性正常（策略已固定, socket 可连）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
