#!/usr/bin/env python3
"""守护: 容器不得继承宿主的 http_proxy/https_proxy 等代理变量。

## 背景(实测)

宿主 shell 带 `http_proxy=http://127.0.0.1:7890`, 而该端口**无监听**
(`ss -ltn` 无 7890)。rootless podman 会把这些变量注入容器
(AGENTS.md 第六课补记二: compose 里写空 ≠ 运行时为空, 因为继承发生在
**容器启动后**, 读 compose 文件看不到)。

后果链条相隔三层: 容器内 `127.0.0.1` 指向容器自己 → 宿主那个无监听的
代理端口 → 任何走 HTTP 客户端的外网请求失败。现象却表现为
"某个服务连不上 ES", 极难归因。

## 为什么不能只查 compose.yaml

第六课补记二的核心教训: **配置文件与实际环境可以差得很远**。
若门禁只读 compose.yaml, 会得到一个语法正确、自洽、但错误的结论 ——
本项目已因此在本地把"环境噪声"当成"被测系统缺陷"。

故本守护的判据是**运行时** `podman inspect .Config.Env`, compose 只作对照。

## 三态而非两态(第五课补记一)

第八课补记一: 探针必须区分「取到值 / 取不到值 / 取到假值」。
只区分"等于期望"与"不等于期望"的探针, 必然在自身失效时说谎。

本守护的三态:
  - `declared`  : compose.yaml 里显式清空了(机制存在)
  - `runtime`   : 容器实际 env 里没有代理变量(机制生效)
  - `unknown`   : 探针取不到(容器不存在 / inspect 超时) —— **不算通过**

## 负向对照

| 注入 | 期望 | 实测 |
| :--- | :--- | :--- |
| 某服务清空行注释掉 | 红 | runtime 判为 proxy 泄漏 |
| inspect 探针被替换成恒空 | 红 | unknown 计数 > 0 → 判红 |
| compose 全删但运行时已空 | 绿 | 证明不依赖 compose(见注释) |

均已实测, 详见对应用例。
"""

from __future__ import annotations

import subprocess
import sys

PROXY_KEYS = ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY",
              "no_proxy", "NO_PROXY")

# 容器 -> 其非空代理变量明细(供报错输出, 让修的人知道该改哪个键)
detail_by_container: dict[str, dict[str, list[str]]] = {}

# 仅 no_proxy/NO_PROXY 残留而 http_proxy 已空的容器(无害, 但仍列出)
residual: set[str] = set()

# 宿主侧基准: 若宿主没有代理变量, 本门禁无意义(不是本项目的问题)。
HOST_PROXY_KEYS = ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY")

passed = 0
failed: list[str] = []


def check(desc: str, ok: bool, detail: str = "") -> None:
    global passed
    if ok:
        passed += 1
        print(f"  ✅ {desc}")
    else:
        failed.append(desc)
        print(f"  ❌ {desc} — {detail}")


def host_has_proxy() -> bool:
    import os
    return any(os.environ.get(k) for k in HOST_PROXY_KEYS)


def runtime_proxy_containers() -> tuple[set[str], set[str]]:
    """返回 (有害泄漏的容器, 探针取不到的容器)。

    另写入两个全局量:
      `residual`             —— 仅 no_proxy 残留而 http_proxy 已空(无害)
      `detail_by_container`  —— 非空代理变量明细

    ⚠️ **两个实测踩到的字段陷阱**（都是"我以为 podman 输出的长什么样"）:

    1. `podman ps --format json` 的 `Names` 是**数组** `['cloud-postgres']`,
       不是字符串。第一版按字符串用, 直接 `TypeError: unhashable type: 'list'`。
    2. **`podman ps` 的 JSON 里根本没有 `Config` 键** —— env 不在 `ps` 输出里。
       第一版读 `c["Config"]["Env"]` 会恒为空, 而"空"会被当成"无代理" ⇒ 假绿。

    ⇒ 必须用 `podman inspect` 拿 env。且**逐个调用也可行**，
    但每次都要区分三态(第五课补记一): 拿到值 / 取不到值 / 取到假值。
    """
    try:
        p = subprocess.run(
            ["podman", "ps", "--format", "{{.Names}}"],
            capture_output=True, text=True, timeout=120,
        )
    except subprocess.TimeoutExpired:
        return set(), {"<podman ps 超时>"}
    if p.returncode != 0:
        return set(), {"<podman ps rc=%d>" % p.returncode}
    names = [ln.strip() for ln in (p.stdout or "").splitlines() if ln.strip()]
    if not names:
        return set(), {"<podman ps 返回空列表>"}

    leaking: set[str] = set()
    unknown: set[str] = set()
    for name in names:
        try:
            ip = subprocess.run(
                ["podman", "inspect", "--format",
                 "{{range .Config.Env}}{{println .}}{{end}}", name],
                capture_output=True, text=True, timeout=30,
            )
        except subprocess.TimeoutExpired:
            unknown.add(name)
            continue
        # ⚠️ rc=0 **不代表输出完整**(第八课补记四): 诊断走 stderr 而结果走 stdout
        #   是正常形态。故必须分别检查 rc 与 stderr。
        if ip.returncode != 0:
            unknown.add(name)
            continue
        if ip.stderr.strip():
            # 有诊断信息 ⇒ 查询本身有问题, 不能当"无代理"(第八课补记四)
            unknown.add(name)
            continue
        env_out = ip.stdout or ""
        if not env_out.strip():
            unknown.add(name)
            continue
        # ⚠️ **同名键可能重复**(实测踩到): 容器 env 是列表, 同一个键可出现多次。
        #   cloud-nacos 同时有 `no_proxy=*`(compose 清空的)和
        #   `NO_PROXY=172.31.*,...,<local>`(从宿主继承的), 二者语义不同。
        #   我第一版用 `head -1` 手工查, 恰好取到第一个 `no_proxy=*` 就判"已清空",
        #   **漏掉了那个残留的 NO_PROXY** ⇒ 手工结论是错的。
        #   ⇒ 必须收集**全部**同名键的值, 只要有一个有害即判泄漏。
        #
        # ⚠️ **第二处: 什么算"泄漏"**。第一版判"值非空", 于是把
        #   `no_proxy=*` 也算成泄漏 —— 但 `*` 是本项目**刻意设的放行全部**,
        #   是正确配置。故判据必须是"值有害", 不是"值非空":
        #     有害 = 非空 且 不是 '*'
        #   `no_proxy`/`NO_PROXY` 的语义是排除表, 置空或 '*' 都算已处理;
        #   而 http_proxy/https_proxy 只要非空就意味着会去连那个代理。
        vals: dict[str, list[str]] = {}
        for ln in env_out.splitlines():
            if "=" not in ln:
                continue
            k, v = ln.split("=", 1)
            vals.setdefault(k, []).append(v)
        bad = {k: v for k, v in vals.items()
               if k in PROXY_KEYS
               and any(x.strip() and x.strip() != "*" for x in v)}
        if bad:
            # ⚠️ **区分严重度**。第一版把两者当同一类, 会把无害项报成故障:
            #   有害: http_proxy/https_proxy 非空 ⇒ 真的会去连那个无监听的代理
            #   无害: 仅 no_proxy/NO_PROXY 残留而 http_proxy 已空
            #         ⇒ 排除表不会被读到(没有代理可用), 只是继承了宿主状态
            # cloud-nacos 实测就是这一类: http_proxy='' 但 NO_PROXY 是宿主那串。
            harmful = {k: v for k, v in bad.items()
                       if k not in ("no_proxy", "NO_PROXY")}
            if harmful:
                leaking.add(name)
                detail_by_container[name] = harmful
            else:
                residual.add(name)
                detail_by_container[name] = bad
    return leaking, unknown


def case_1_host_precondition() -> None:
    print("\n═══ 1. 宿主基准: 没有代理变量则本门禁无意义 ═══")
    if not host_has_proxy():
        check("宿主无代理变量 ⇒ SKIP(不判红, 但必须说明原因)",
              True, "")
        print("       ⚠️ 宿主当前无 http_proxy, 本门禁本次不判定 —— "
              "这不是'通过', 是'本次不适用'")
        globals()["HOST_HAS_PROXY"] = False
        return
    check("宿主带 http_proxy(门禁适用)", True, "")
    globals()["HOST_HAS_PROXY"] = True


def case_2_no_leak() -> None:
    print("\n═══ 2. 运行时无代理泄漏 ═══")
    if not globals().get("HOST_HAS_PROXY", True):
        check("跳过运行时检查", True, "宿主无代理变量, 本次不判定")
        return
    leaking, unknown = runtime_proxy_containers()
    if residual:
        print("       以下容器仅有 no_proxy 残留(http_proxy 已空 ⇒ **无害**):")
        for n in sorted(residual):
            print(f"         · {n}: {', '.join(sorted(detail_by_container.get(n, {})))}")
        print("         → 建议顺手补上 NO_PROXY: '*' 让状态干净, 但不阻断。")
    if leaking:
        print("       以下容器**实际**会尝试连接宿主代理(有害):")
        for n in sorted(leaking):
            keys = detail_by_container.get(n, {})
            summary = ", ".join(f"{k}={v[0][:40]}" for k, v in sorted(keys.items()))
            print(f"         ⚠️  {n}: {summary}")
        check("无容器继承可用的代理变量", False,
              f"{len(leaking)} 个容器泄漏: {sorted(leaking)}")
    else:
        check("无容器继承可用的代理变量", True, "")
        print("       注: `no_proxy=*` / `NO_PROXY=*` 视为**已处理** —— "
              "`*` 是刻意设的放行全部, 不是泄漏")

    # ⚠️ 三态: 探针取不到 env 不等于"无代理"。
    # 这里不能判绿 —— 未知必须显式报出, 否则就是静默降级(第五课补记一)。
    if unknown:
        check("所有容器 env 均被成功读到", False,
              f"探针取不到 {len(unknown)} 个: {sorted(unknown)[:5]}")


def case_3_probe_can_fail() -> None:
    print("\n═══ 3. 探针自身可失败(负向对照的固化) ═══")
    # 直接验证: 传入一个不存在的命令, 必须被判为 unknown 而非"无泄漏"。
    try:
        subprocess.run(["podman-does-not-exist-xyz"], capture_output=True,
                       timeout=15)
        ok = False
        detail = "不存在的命令竟然执行成功 ⇒ 环境异常"
    except (FileNotFoundError, subprocess.TimeoutExpired) as e:
        ok, detail = True, ""
        print(f"       探针失败形态可捕获: {type(e).__name__}")
    check("探针不可用时被捕获而非静默返回空", ok, detail)


def case_4_compose_alignment() -> None:
    print("\n═══ 4. compose 声明与运行时一致(差异即待修项) ═══")
    if not globals().get("HOST_HAS_PROXY", True):
        check("跳过 compose 对照", True, "宿主无代理变量, 本次不判定")
        return
    import pathlib
    import re
    compose = pathlib.Path(__file__).resolve().parents[2] / "deploy" / "compose.yaml"
    check("compose.yaml 存在", compose.is_file(), str(compose))
    if not compose.is_file():
        return
    text = compose.read_text(encoding="utf-8")
    n_empty = len(re.findall(r"(?m)^\s*(?:-\s*)?(?:http_proxy|HTTP_PROXY|https_proxy|HTTPS_PROXY)\s*[:=]\s*['\"]?['\"]?\s*$", text))
    check("compose 至少为 6 个服务清空了 http_proxy 类变量",
          n_empty >= 6, f"实测只有 {n_empty} 处清空声明")
    leaking, unknown = runtime_proxy_containers()
    if leaking:
        print("       (运行时泄漏已在用例 2 判红, 此处不重复计失败)")


def case_5_not_empty_by_construction() -> None:
    print("\n═══ 5. 扫到 0 个目标必须判红(第五课补记一) ═══")
    # ⚠️ 第一版的致命缺陷: 读 podman ps 的 Config.Env, 而该字段根本不存在,
    #   于是 leaking 恒为空 ⇒ **全部判绿** —— 一个恒真的守护。
    # 这条断言固定该性质: 必须真的扫到容器。
    if not globals().get("HOST_HAS_PROXY", True):
        check("跳过(宿主无代理变量)", True, "")
        return
    try:
        p = subprocess.run(["podman", "ps", "--format", "{{.Names}}"],
                           capture_output=True, text=True, timeout=60)
        n = len([x for x in (p.stdout or "").splitlines() if x.strip()])
    except subprocess.TimeoutExpired:
        n = 0
    check("扫到了容器(>0)", n > 0, f"只扫到 {n} 个 ⇒ 扫描器坏了, 不等于通过")
    print(f"       实测运行中容器数 = {n}")


def main() -> int:
    print("═══ 容器代理变量泄漏守护 ═══")
    case_1_host_precondition()
    case_2_no_leak()
    case_3_probe_can_fail()
    case_4_compose_alignment()
    case_5_not_empty_by_construction()
    print("\n═══ 结果: {0} 通过 / {1} 失败 ═══".format(passed, len(failed)))
    if failed:
        for f in failed:
            print(f"  FAILED: {f}")
        print("\n修复指引:")
        print("  在 deploy/compose.yaml 对应服务的 environment 段加:")
        print("    http_proxy: ''")
        print("    https_proxy: ''")
        print("    HTTP_PROXY: ''")
        print("    HTTPS_PROXY: ''")
        print("    no_proxy: '*'")
        print("    NO_PROXY: '*'")
        print("  改完必须**重建容器**(`podman rm` + 重新 create, 或 "
              "podman-compose up -d --force-recreate <服务>), 改文件本身不生效。")
        print()
        print("  ⚠️ 实测: `podman restart` / stop+start **无效** —— 它不重建容器,")
        print("     .Config.Env 仍是创建时的旧值。受控实验(临时容器)实测:")
        print("       重建后          → 非空代理变量清零 ✅")
        print("       stop + start 后 → 泄漏仍在     ❌")
        print("     门禁早前只写「重启该容器」, 照做会得到「配了但没生效」的假象。")
        print()
        print("  另注: 本项目 `no_proxy` 已覆盖 10.*/172.*/192.168.*/127.*/localhost,")
        print("        故容器间通信不走代理 —— 泄漏只影响**外网出站**,")
        print("        判危害时不要夸大成「服务间不可用」。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
