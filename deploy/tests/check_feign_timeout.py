#!/usr/bin/env python3
"""check_feign_timeout.py — Feign 超时覆盖门禁

目标：每个 @FeignClient 都必须**在运行期真的带超时** —— 要么注解在本文件，
要么它的消费服务的运行配置里声明了 spring.cloud.openfeign 全局超时。

## 2026-11-08 重写原因（三处假绿，全部实测）

1. `REPO_ROOT = dirname(__file__)` => `API_ROOT = deploy/tests/cloud-infra-api`
   （不存在）—— **永远扫到 0 个 @FeignClient, 永远 PASS**。
2. 把"扫到 0 个"当通过 —— 探针路径漂移与"仓库真的没有客户端"无法区分
   （第五课补记一: 没验到 != 验过了）。
3. 文案声称"或 yml 中全局声明"，但代码从未读过任何 yml。

## 判据

- `cloud-infra-api` 下 @FeignClient 数量必须 > 0，否则 rc=2（扫描器坏了）。
- 客户端文件含 connectTimeout / readTimeout => 覆盖。
- 否则：该客户端必须被某个服务真实消费（import com.cloudinfra.api.*.*Api），
  且该消费服务的 `deploy/config/<svc>.yaml` 或模块 `application*.yml`
  含 openfeign + connectTimeout + readTimeout => 覆盖。
- **无任何消费方**的客户端没有调用路径 => 无超时风险，列为 INFO 不阻断
  （2026-11-08 实测: SearchApi 全仓无真实消费方）。
- 有消费方但无生效超时 => rc=1，逐项列出。

调用方：verify-e2e.sh（阻断型）—— 缺超时的 Feign 调用会拖死网关线程。

## 负向对照

  · 删掉 deploy/config/search-service.yaml 的 readTimeout -> 必须报红
    （search-service 是 FileApi / SearchApi 的唯一消费方；而 system-module 的
     readTimeout 不是有效对照 —— NotificationApi 另有 notification-service 覆盖）
  · 把 API_ROOT 改到不存在的目录 -> 必须 rc=2（而不是 PASS）
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
API_ROOT = ROOT / "cloud-infra-api"
CONFIG_DIR = ROOT / "deploy" / "config"
CONSUMER_ROOTS = (
    ROOT / "cloud-infra-modules",
    ROOT / "cloud-infra-auth",
    ROOT / "cloud-infra-gateway",
)

IMPORT_RE = re.compile(
    r"import\s+com\.cloudinfra\.api\.[a-z0-9_]+\.[A-Za-z0-9_]*Api\s*;")
TIMEOUT_KEYS = ("connectTimeout", "readTimeout")


def service_dir_of(java: Path):
    """把消费方 java 文件映射到服务目录。"""
    for root in CONSUMER_ROOTS:
        try:
            rel = java.relative_to(root)
        except ValueError:
            continue
        if root.name == "cloud-infra-modules":
            return root / rel.parts[0]
        return root
    return None


def find_consumers():
    """@FeignClient 简单名 -> 消费服务目录集合。"""
    mapping: dict[str, set[Path]] = {}
    for root in CONSUMER_ROOTS:
        if not root.is_dir():
            continue
        for java in root.rglob("*.java"):
            if "/src/test/" in java.as_posix():
                continue
            try:
                text = java.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if "com.cloudinfra.api" not in text:
                continue
            svc = service_dir_of(java)
            if svc is None:
                continue
            for m in IMPORT_RE.finditer(text):
                client = m.group(0).rstrip(";").strip().split(".")[-1]
                mapping.setdefault(client, set()).add(svc)
    return mapping


def config_candidates(svc_dir: Path):
    names = {svc_dir.name}
    if svc_dir.name.startswith("cloud-infra-"):
        names.add(svc_dir.name[len("cloud-infra-"):])
    for name in names:
        yield CONFIG_DIR / f"{name}.yaml"
        yield CONFIG_DIR / f"{name}.yml"
    res = svc_dir / "src" / "main" / "resources"
    if res.is_dir():
        yield from sorted(res.glob("application*.yml"))


def config_declares_timeout(svc_dir: Path) -> bool:
    for cand in config_candidates(svc_dir):
        if not cand.is_file():
            continue
        text = cand.read_text(encoding="utf-8", errors="replace")
        if "openfeign" in text and all(k in text for k in TIMEOUT_KEYS):
            return True
    return False


def main() -> int:
    if not API_ROOT.is_dir():
        print(f"FAIL: {API_ROOT} 不存在 —— 扫描根漂移, 不能判绿", file=sys.stderr)
        return 2
    client_files = []
    for java in API_ROOT.rglob("*.java"):
        try:
            text = java.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if "@FeignClient" in text:
            client_files.append((java, text))
    if not client_files:
        # 扫描器坏了与"仓库真的没有客户端"必须区分(第五课补记一)
        print(f"FAIL: {API_ROOT} 下扫到 0 个 @FeignClient —— 扫描器坏了, 不能判绿",
              file=sys.stderr)
        return 2

    unannotated = [(f, t) for f, t in client_files
                   if not all(k in t for k in TIMEOUT_KEYS)]

    consumers = find_consumers()
    problems, unused = [], []
    for java, _text in unannotated:
        client = java.stem
        svcs = sorted(consumers.get(client, set()))
        if not svcs:
            # 无消费方 => 无调用路径 => 无超时风险; 只记录, 不阻断
            unused.append(java)
            continue
        missing = [s for s in svcs if not config_declares_timeout(s)]
        if missing:
            problems.append(
                f"{java}: 无注解; 消费服务缺 openfeign 超时: "
                + ", ".join(str(s.relative_to(ROOT)) for s in missing))

    if problems:
        print(f"FAIL: {len(problems)} 个 @FeignClient 无任何生效超时:", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        print("  修复: 在客户端注解 connectTimeout/readTimeout, 或在该消费服务的"
              " openfeign 配置里设 default connectTimeout/readTimeout", file=sys.stderr)
        return 1

    if unused:
        print(f"INFO: {len(unused)} 个无注解客户端当前无消费方(无调用路径, 不阻断): "
              + ", ".join(str(u.relative_to(ROOT)) for u in unused))
    covered = len(unannotated) - len(unused)
    if covered:
        print(f"OK: {covered} 个无注解 @FeignClient 均由消费服务的"
              f"全局 openfeign 超时覆盖")
    if not unannotated:
        print(f"OK: {len(client_files)} 个 @FeignClient 均带超时注解")
    return 0


if __name__ == "__main__":
    sys.exit(main())
