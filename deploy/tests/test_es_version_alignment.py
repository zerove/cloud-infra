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
  · 三处配置（compose / manifests / k8s values）不一致 -> 必须失败
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 承载 ES 镜像的服务端配置。三处都要查 —— 只查 compose 的话,
# K8s 部署仍会踩同一个坑（这正是它长期存在的原因: 没人同时看三处）。
SERVER_CONFIGS = [
    ("deploy/compose.yaml", re.compile(r"^\s*image:\s*\S*elasticsearch:(\d+)\.", re.M)),
    ("manifests/base/elasticsearch.yaml",
     re.compile(r"^\s*image:\s*\S*elasticsearch:(\d+)\.", re.M)),
    ("deploy/k8s/infra/values.yaml",
     re.compile(r"^\s*image:\s*\S*elasticsearch:(\d+)\.", re.M)),
]

def _repo_boot_version() -> str:
    """Spring Boot 版本从仓库根 pom 读 —— 不手写常量(会与 pom 漂移)。"""
    txt = (ROOT / "pom.xml").read_text(encoding="utf-8")
    m = re.search(r"<spring-boot\.version>([^<]+)<", txt)
    return m.group(1) if m else ""


def _mvn_client_major(offline: bool) -> tuple[int, str] | None:
    """跑一次 dependency:tree; 拿不到则返回 None。

    ⚠️ 必须用 `-DoutputFile`: 原实现带 `-q` 并读 stdout, 而 dependency:tree
    的输出走 Maven 日志, `-q` 会把它整段吞掉 -> 该路径**从未真正取到过版本**,
    每次都静默降级到 BOM。守护的第一来源形同虚设。
    """
    import tempfile
    fd = tempfile.NamedTemporaryFile(
        mode="r", suffix=".es-tree.txt", delete=False, encoding="utf-8"
    )
    fd.close()
    args = ["mvn", "--batch-mode"]
    if offline:
        args.append("-o")
    args += [
        "-q", "-pl", "cloud-infra-modules/search-service",
        "dependency:tree", "-Dincludes=co.elastic.clients:elasticsearch-java",
        f"-DoutputFile={fd.name}",
    ]
    try:
        subprocess.run(
            args, capture_output=True, text=True, cwd=str(ROOT), timeout=600,
        )
        tree = Path(fd.name).read_text(encoding="utf-8", errors="ignore")
    except (subprocess.SubprocessError, OSError):
        tree = ""
    finally:
        Path(fd.name).unlink(missing_ok=True)
    m = re.search(r"elasticsearch-java:jar:(\d+)\.", tree)
    if m:
        return int(m.group(1)), "mvn dependency:tree" + (" (offline)" if offline else "")
    return None


def _bom_client_major() -> tuple[int, str] | None:
    """从 spring-boot-dependencies BOM 的 `<elasticsearch-client.version>` 取版本。

    先查本地 ~/.m2; 再按需从 Maven Central 取那个 pom（CI 首次运行 ~/.m2 为空,
    但网络可用）。只取一个 pom, 比跑完整 dependency:tree 快得多。
    """
    boot = _repo_boot_version()
    if not boot:
        return None
    txt = ""
    local = (
        Path.home() / ".m2/repository/org/springframework/boot"
        / "spring-boot-dependencies" / boot / f"spring-boot-dependencies-{boot}.pom"
    )
    if local.is_file():
        txt = local.read_text(encoding="utf-8", errors="ignore")
        src = local.name
    if "<elasticsearch-client.version>" not in txt:
        import urllib.request
        url = (
            "https://repo1.maven.org/maven2/org/springframework/boot"
            f"/spring-boot-dependencies/{boot}/spring-boot-dependencies-{boot}.pom"
        )
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                txt = r.read().decode("utf-8", "ignore")
            src = f"central:{local.name}"
        except Exception:
            return None
    mm = re.search(r"<elasticsearch-client\.version>([\d.]+)<", txt)
    if mm:
        return int(mm.group(1).split(".")[0]), src
    return None


def client_major() -> tuple[int, str]:
    """ES 客户端主版本。优先 mvn dependency:tree, 失败则读 BOM pom。

    返回 (major, 来源说明)。
    """
    # 1) 权威来源: Maven 实际解析结果。
    #    先离线(本地快), 再在线 —— **不能只写 -o**: E2E 在编译前跑本守护,
    #    runner 的 ~/.m2 为空, 离线必然拿不到依赖树, 守护会误报"自身失效"。
    #    2026-11-10 CI run 42678273812 即此形态。
    for offline in (True, False):
        got = _mvn_client_major(offline)
        if got:
            return got

    # 2) 降级: 直接读 BOM (本地 ~/.m2 有就用, 没有就从 Maven Central 取该 pom)
    got = _bom_client_major()
    if got:
        return got
    return -1, ""


def server_majors() -> list[tuple[str, int]]:
    out = []
    for rel, pat in SERVER_CONFIGS:
        f = ROOT / rel
        if not f.exists():
            continue
        m = pat.search(f.read_text(encoding="utf-8"))
        if m:
            out.append((rel, int(m.group(1))))
    return out


def main() -> int:
    failures: list[str] = []

    cmajor, csrc = client_major()
    if cmajor < 0:
        print("FAIL 无法确定 ES 客户端版本 —— 守护自身失效, 不能判绿")
        return 1
    print(f"INFO ES 客户端主版本 = {cmajor}  (来源: {csrc})")

    servers = server_majors()
    if not servers:
        print("FAIL 三处配置里一处都没扫到 ES 版本 —— 扫描器坏了, 不能判绿")
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

    # 三处之间也必须一致 —— 否则换个部署方式又踩一遍
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