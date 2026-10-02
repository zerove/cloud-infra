#!/usr/bin/env python3
"""守护: deploy/up.sh 不得让微服务容器早于 Nacos 配置同步启动。

## 为什么需要这个守护 (2026-10-02)

CI E2E run 37050526759 中 auth/file/notification/search 四个服务全部 `Exited (1)`,
真实异常是:

    Caused by: org.springframework.util.PlaceholderResolutionException:
      Could not resolve placeholder 'spring.datasource.url'

配套日志显示 `[Nacos Config] config[... is empty]` —— 服务启动时Nacos 里
还没有它的配置。

根因是 up.sh 的执行顺序: 步骤 2 用 `podman-compose up -d` 一次性拉起
**全部**服务 (--profile full 时连 6 个微服务一起), 而配置同步被放在
**步骤 10**。CI 每次 `down` 后 Nacos 的数据目录是全新的 (deploy/nacos/data
是 gitignored 的 bind mount), 所以配置一定晚于容器启动, 竞态必然命中。

本地长期复现不了, 因为那个 bind mount 保留了上次运行的配置残留 ——
这正是"本地绿 / CI 红"的典型形态。

## 判定方式

不跑容器 (太慢且受本机负载影响), 改为静态分析 up.sh 的执行顺序:
配置上传调用点必须早于微服务容器启动调用点。只认**实际生效**的调用点:
注释行、函数定义、被 --check/--sync 提前 return 掉的分支都不算。

## 负向对照

把配置同步块移到容器启动块之后, 本守护必须失败。
"""
import re
import sys
from pathlib import Path

UP_SH = Path(__file__).resolve().parents[1] / "up.sh"

# 微服务的容器名/服务名。compose 里这 6 个属于"应用", 其余是基础设施。
MICROSERVICES = ("gateway", "auth", "system", "file", "notification", "search")


def strip_noise(src: str) -> list[tuple[int, str]]:
    """返回 (行号, 代码), 去掉纯注释行和行内注释。"""
    out = []
    for i, raw in enumerate(src.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        # 行内注释: 去掉不严谨, shell 里 # 在引号外即注释, 这里只处理明显整段注释
        line = re.sub(r"\s+#.*$", "", line).rstrip()
        if not line:
            continue
        out.append((i, line))
    return out


def find_config_upload_line(lines: list[tuple[int, str]]) -> int | None:
    """nacos_config_upload 的**调用**行 (排除函数定义本身)。

    注意不能只取 min: up.sh 有三处分支, --sync 与 --check 都会提前 exit,
    真正的 --full 路径是最后一处。
    """
    calls = [
        i
        for i, line in lines
        if "nacos_config_upload" in line and "nacos_config_upload()" not in line
    ]
    return max(calls) if calls else None


def join_continuations(lines: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """把shell 的续行 (`\\` 结尾) 拼成一条逻辑行。

    必须做: 真正的启动命令是
        podman-compose -f ... --env-file ... \
          ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"} "${UP_ARGS[@]}"
    命令词在第 1 行, `-d` 在续行里。不合并就会漏掉启动点 —— 这正是
    第一版守护误报 "找不到启动点" 的原因, 也差点让假门禁溜过去。
    """
    merged: list[tuple[int, str]] = []
    buf_line_no: int | None = None
    buf: list[str] = []
    for i, line in lines:
        if buf_line_no is None:
            buf_line_no = i
        buf.append(line)
        if line.endswith("\\"):
            buf[-1] = line[:-1].rstrip()
            continue
        merged.append((buf_line_no, " ".join(buf).strip()))
        buf_line_no = None
        buf = []
    if buf:
        merged.append((buf_line_no or 0, " ".join(buf).strip()))
    return merged


def resolve_up_args(lines: list[tuple[int, str]]) -> tuple[int | None, list[str]]:
    """静态求值 `compose_up_args` 的返回值。

    ## 为什么必须做真求值而不是文本匹配

    实际启动命令是:

        podman-compose -f ... --env-file ... "${UP_ARGS[@]}"

    `up -d` 并不在这行文本里, 它在 `compose_up_args` 函数的返回值中。
    只对启动行做正则就会得到 "找不到启动点" —— 第一次写守护时正是这样,
    结果是判据失效、报了一个与竞态无关的错 (即一个假门禁)。

    所以这里直接解释该函数: 它是纯 printf 分支, 无外部依赖, 求值可靠。
    """
    src = "\n".join(line for _, line in lines)
    m = re.search(r"compose_up_args\s*\(\)\s*\{(.*?)\n\}", src, re.S)
    if not m:
        return None, []
    body = m.group(1)
    by_name = re.search(r"printf\s+'%s\\n'\s+up\s+-d\s+\"\$\{FULL_SERVICES\[@\]\}\"", body)
    if by_name:
        return 1, ["up", "-d", "${FULL_SERVICES[@]}"]
    plain = re.search(r"printf\s+'%s\\n'\s+up\s+-d\s*$", body, re.M)
    if plain:
        return 2, ["up", "-d"]
    return None, []


def find_microservice_start_line(lines: list[tuple[int, str]]) -> int | None:
    """**微服务**容器启动的行号 (不是任意 up -d)。

    ## 为什么不能把所有 up -d 都当启动点

    修复后的 up.sh 有两次 up -d:
      1. `podman-compose ... up -d`                              (只起基础设施, 安全)
      2. `podman-compose ... --profile full ... "${UP_ARGS[@]}"` (起微服务, 危险)

    第一版守护把两者都算作启动点, 结果修复后仍报红 —— 即一个会因为"已经修好"
    而误报的守护。第一版报红时理由也是错的 (它报的是找不到启动点, 不是竞态)。
    假门禁比没有门禁更糟: 它要么被忽略, 要么逼人改坏代码去迎合它。

    所以判据必须是**这条命令会不会拉起微服务**:
      - 带 `--profile full`  => 会 (profile 里含 6 个微服务)
      - 展开 `"${UP_ARGS[@]}"` => 会 (值来自 compose_up_args)
      - 光秃秃的 `up -d`   => 不会 (默认 profile 只有基础设施)
    """
    up_args_kind, _ = resolve_up_args(lines)
    if up_args_kind is None:
        return None

    candidates = []
    for i, line in lines:
        if "podman-compose" not in line:
            continue
        if " down" in f" {line}" or "dry-run" in line or " stop" in f" {line}":
            continue
        if "--help" in line:
            continue

        starts_microservices = "--profile full" in line or "UP_ARGS" in line
        if not starts_microservices:
            continue

        # 防御: 确认这确实是一条 up 命令而不是别的东西。
        if not (re.search(r"(^|\s)up(\s+-\w+)*\s+-d(\s|$)", line) or "UP_ARGS" in line):
            continue
        candidates.append(i)
    if not candidates:
        return None
    return min(candidates)


def find_infra_start_line(lines: list[tuple[int, str]]) -> int | None:
    """基础设施启动行号 —— 仅用于诊断输出, 不参与判定。

    它本身是安全的 (不拉微服务), 但如果它携带 --profile full 就同样是竞态,
    所以还是记下来看一眼。
    """
    for i, line in lines:
        if "podman-compose" not in line:
            continue
        if " down" in f" {line}" or "--help" in line or "dry-run" in line:
            continue
        if "--profile full" in line or "UP_ARGS" in line:
            continue
        if re.search(r"(^|\s)up(\s+-\w+)*\s+-d(\s|$)", line):
            return i
    return None


def find_profile_up_args_line(src: str) -> int | None:
    """`compose_up_args` 里 `printf '%s\\n' up -d` (无服务名) 的行号。

    这条路径会连带启动微服务, 所以它也是启动点。
    """
    lines = src.splitlines()
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if "printf" in s and re.search(r"up\s+-d\s*$", s):
            return i
    return None


def main() -> int:
    if not UP_SH.exists():
        print(f"FAIL: 找不到 {UP_SH}")
        return 1

    src = UP_SH.read_text(encoding="utf-8")
    lines = join_continuations(strip_noise(src))

    upload_line = find_config_upload_line(lines)
    start_line = find_microservice_start_line(lines)
    infra_line = find_infra_start_line(lines)
    up_args_kind, up_args = resolve_up_args(lines)

    print("=== up.sh 执行顺序分析 ===")
    print(f"  基础设施启动点 : {infra_line}")
    print(f"  配置上传调用点 : {upload_line}")
    print(f"  微服务启动点   : {start_line}")
    print(f"  compose_up_args: {' '.join(up_args) or '(未解析)'} (kind={up_args_kind})")

    failures = []

    if upload_line is None:
        failures.append("找不到 nacos_config_upload 调用点, up.sh 结构可能已变, 需人工确认")
    if start_line is None:
        failures.append(
            "找不到会拉起微服务的 podman-compose 命令 (带 --profile full 或展开 "
            "UP_ARGS), up.sh 结构可能已变, 需人工确认"
        )

    if upload_line is not None and start_line is not None:
        if upload_line > start_line:
            failures.append(
                f"竞态: 微服务启动在行 {start_line}, 但配置上传在行 {upload_line} —— "
                "微服务会比配置先启动, 首次部署必然读不到配置"
            )
        else:
            print(
                f"\nOK: 配置同步 (行 {upload_line}) 早于微服务启动 (行 {start_line})"
                + (f", 基础设施启动 (行 {infra_line}) 在前且不拉微服务" if infra_line else "")
            )

    if up_args_kind == 2:
        print(
            "\n注意: 走 --profile full 时 `up -d` 不带服务名, 会连带拉起微服务 —— "
            "这也是为什么配置同步必须在该命令之前"
        )

    if failures:
        print()
        for f in failures:
            print(f"FAIL: {f}")
        return 1

    print("\nPASS: 配置同步早于微服务容器启动")
    return 0


if __name__ == "__main__":
    sys.exit(main())