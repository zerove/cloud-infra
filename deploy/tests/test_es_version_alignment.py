#!/usr/bin/env python3
"""守护: ES 服务端主版本必须与 ES 客户端主版本一致。

## 这个守护拦住的是什么（2026-10-03, CI E2E run 37077332251 / 37079433594）

search 服务启动失败, 报错形态**极具误导性**:

    Caused by: DataAccessResourceFailureException:
      node: http://cloud-elasticsearch:9200/, status: 400, [es/indices.exists]
      Expecting a response body, but none was sent

看上去像"ES 返回了 400 但没给原因", 实际服务端给的**完整**原因是:

    media_type_header_exception: Invalid media-type value on headers
    [Accept, Content-Type]

真实根因: **客户端与服务端主版本不匹配**。

| | 版本 | 来源 |
| :--- | :--- | :--- |
| ES 客户端 | 9.4.2 | Spring Boot 4.1.0 BOM 的 `<elasticsearch-client.version>` |
| ES 服务端 | 8.18.0 | `deploy/compose.yaml` 手写 |

9.x 客户端默认发送
`Accept/Content-Type: application/vnd.elasticsearch+json; compatible-with=9`,
ES 8.x 服务端不认 -> 400。

## 为什么这件事特别难查（三重误导）

1. **curl 测不出来**: curl 不发那两个头, `HEAD /sys_oper_log` 正常返回 404,
   一切看起来"ES 好好的"。只有 Java 客户端会踩到。
2. **错误信息被截断**: 客户端只报"Expecting a response body, but none was sent",
   服务端真正的 `media_type_header_exception` 被丢掉了。
3. **像是网络问题**: 报错里带 `node: http://...`, 很容易往代理/DNS/防火墙方向找 ——
   我本地就先被自己的 shell 代理带偏过一次（见 AGENTS.md 第六课）。

## 判定方式：比对"实际解析到的客户端版本"与"配置的服务端版本"

客户端版本**从 Maven BOM 里读**, 不是从文档或注释里抄:
`mvn dependency:tree` 是唯一权威来源。离线时降级为直接解析
`spring-boot-dependencies-<boot>.pom` 里的 `<elasticsearch-client.version>`。

## 负向对照

  · 把 compose 里的 ES 版本改回 8.x -> 必须失败
  · 各处配置（compose / k8s values）不一致 -> 必须失败
  · 任一声明的配置文件不存在 -> 必须失败（声明与实际不符 ≠ 通过）
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 承载 ES 镜像的服务端配置。两处都要查 —— 只查 compose 的话,
# K8s 部署仍会踩同一个坑（这正是它长期存在的原因: 没人同时看两处）。
# ⚠️ 2026-11-07 原来的第三项 `manifests/base/elasticsearch.yaml` 已随 kustomize 面删除:
#    该条目此前会被 `if not f.exists(): continue` 静默跳过 —— 也就是说,
#    从 manifests 被删那天起, 这个"三处核对"已经悄悄变成"两处核对"。
#    现改为: 声明了的文件必须真实存在且被解析到, 否则直接报红。
SERVER_CONFIGS = [
    ("deploy/compose.yaml", re.compile(r"^\s*image:\s*\S*elasticsearch:(\d+)\.", re.M)),
    ("deploy/k8s/infra/values.yaml",
     re.compile(r"^\s*image:\s*\S*elasticsearch:(\d+)\.", re.M)),
]

BOOT_VERSION = "4.1.0"


def client_major() -> tuple[int, str]:
    """ES 客户端主版本。优先 mvn dependency:tree, 失败则读 BOM pom。

    返回 (major, 来源说明)。
    """
    # 1) 权威来源: Maven 实际解析结果
    p = subprocess.run(
        ["mvn", "-o", "-q", "-pl", "cloud-infra-modules/search-service",
         "dependency:tree", "-Dincludes=co.elastic.clients:elasticsearch-java"],
        capture_output=True, text=True, cwd=str(ROOT), timeout=300,
    )
    m = re.search(r"elasticsearch-java:jar:(\d+)\.", p.stdout)
    if m:
        return int(m.group(1)), "mvn dependency:tree"

    # 2) 降级: 直接读 BOM
    import glob
    pat = str(Path.home() / ".m2/repository/org/springframework/boot"
              / "spring-boot-dependencies" / BOOT_VERSION / "*.pom")
    for pom in glob.glob(pat):
        txt = Path(pom).read_text(encoding="utf-8", errors="ignore")
        mm = re.search(r"<elasticsearch-client\.version>([\d.]+)<", txt)
        if mm:
            return int(mm.group(1).split(".")[0]), f"{Path(pom).name}"
    return -1, ""


def server_majors() -> tuple[list[tuple[str, int]], list[str]]:
    """返回 (解析到的 (路径, 主版本) 列表, 未找到/未解析到的声明路径列表)。

    声明了的配置文件必须真实存在且能解析到版本号:
    静默跳过会把"声明 N 处但实际只扫到 1 处"的假绿合法化 —— 路径写错、
    或某个部署面被移走时, 守护会像没事一样继续报 PASS。
    """
    out = []
    missing = []
    for rel, pat in SERVER_CONFIGS:
        f = ROOT / rel
        if not f.exists():
            missing.append(f"{rel} (文件不存在)")
            continue
        m = pat.search(f.read_text(encoding="utf-8"))
        if m:
            out.append((rel, int(m.group(1))))
        else:
            missing.append(f"{rel} (存在但解析不到 elasticsearch image 版本)")
    return out, missing


def main() -> int:
    failures: list[str] = []

    cmajor, csrc = client_major()
    if cmajor < 0:
        print("FAIL 无法确定 ES 客户端版本 —— 守护自身失效, 不能判绿")
        return 1
    print(f"INFO ES 客户端主版本 = {cmajor}  (来源: {csrc})")

    servers, missing = server_majors()
    if missing:
        print("FAIL: 声明的 ES 服务端配置未全部解析到位 (声明 ≠ 实际, 不能判绿):")
        for f in missing:
            print(f"  - {f}")
        return 1
    if not servers:
        print("FAIL 声明的配置里一处都没扫到 ES 版本 —— 扫描器坏了, 不能判绿")
        return 1

    for rel, major in servers:
        if major != cmajor:
            failures.append(
                f"{rel}: ES 服务端主版本 {major} != 客户端主版本 {cmajor}\n"
                f"      9.x 客户端会发 compatible-with=9 头, 8.x 服务端返回\n"
                f"      400 media_type_header_exception, 导致 search 启动失败。"
            )
        else:
            print(f"PASS {rel}: 服务端主版本 {major} 与客户端一致")

    # 各处之间也必须一致 —— 否则换个部署方式又踩一遍
    majors = {m for _, m in servers}
    if len(majors) > 1:
        detail = ", ".join(f"{r}={m}" for r, m in servers)
        failures.append(f"各处 ES 版本不一致: {detail}")

    if failures:
        print("FAIL:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"PASS ES 版本对齐守护: 客户端与全部 {len(servers)} 处服务端配置主版本一致 ({cmajor}.x)")
    return 0


if __name__ == "__main__":
    sys.exit(main())