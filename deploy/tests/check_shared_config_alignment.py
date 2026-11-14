#!/usr/bin/env python3
"""报告节: Q13+Q15 — shared config 文件命名规范化。

约束:
  - 每个服务（`cloud-infra-modules/*` + `cloud-infra-auth` + `cloud-infra-gateway`）
    的运行配置（本地 application*.yml 或 deploy/config/<svc>.yaml）必须 import
    `optional:nacos:actuator-config.yaml` —— 否则 management 端点走各自默认值，
    与共享 config 漂移。
  - demo-service 明确弃用（仅 `-Pwith-demo` 构建），不计入。

## 2026-11-08 重写原因（两处假绿，全部实测）

1. `REPO = parents[1]` 指向 `deploy/` —— `deploy/cloud-infra-modules`
   不存在，rglob 得到 **0 个文件**，却照样打印 OK。
2. demo 排除判据写成 `svc.name[:-4] == "demo"` —— `application.yml[:-4]`
   恒为 `"application"`，排除从未生效。
3. 旧实现只扫 `cloud-infra-modules`，与"所有 service"的声明不符；
   `cloud-infra-auth` / `cloud-infra-gateway` 两个入口服务从未被核对。

判据:
  - 一个服务都没扫到 => rc=2（扫描器坏了，不能判绿）。
  - 某服务本地与部署配置都未 import => rc=1。
  - system-module 的本地 yml 走 legacy `spring.cloud.nacos.config` 机制，
    其 import 声明在 deploy/config/system-module.yaml —— 属"部署侧覆盖"，
    已在下方按 `local or deploy` 判定并注明。

## 负向对照

  · 临时根目录里某服务两处都删掉 import -> 必须报红
  · 扫描根不存在 -> 必须 rc=2
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IMPORT_RE = re.compile(r"optional:nacos:actuator-config\.yaml")


def main(root: Path = ROOT) -> int:
    service_dirs = []
    modules = root / "cloud-infra-modules"
    if modules.is_dir():
        service_dirs.extend(sorted(p for p in modules.iterdir() if p.is_dir()))
    for name in ("cloud-infra-auth", "cloud-infra-gateway"):
        candidate = root / name
        if candidate.is_dir():
            service_dirs.append(candidate)

    problems, aligned = [], []
    checked = 0
    for svc_dir in service_dirs:
        if svc_dir.name == "demo-service":
            continue  # demo 明确弃用, 不计入
        res = svc_dir / "src" / "main" / "resources"
        ymls = sorted(res.glob("application*.yml")) if res.is_dir() else []
        local_ok = any(IMPORT_RE.search(
            f.read_text(encoding="utf-8", errors="replace")) for f in ymls)
        svc = svc_dir.name.removeprefix("cloud-infra-")
        dep = root / "deploy" / "config" / f"{svc}.yaml"
        dep_ok = bool(IMPORT_RE.search(
            dep.read_text(encoding="utf-8", errors="replace"))) if dep.is_file() else False
        checked += 1
        if local_ok or dep_ok:
            aligned.append((svc, "本地" if local_ok else "部署配置"))
        else:
            problems.append(
                f"{svc}: 本地与部署配置都未 import actuator-config.yaml")

    if checked == 0:
        print(f"FAIL: {root} 下 0 个 service 被核对 —— 扫描根漂移，不能判绿",
              file=sys.stderr)
        return 2
    if problems:
        print("FAILED: 共享 config 未对齐:")
        for p in problems:
            print("  " + p)
        return 1
    covered = ", ".join(f"{svc}({where})" for svc, where in aligned)
    print(f"OK: {checked} 个 service 均已 import actuator-config.yaml: {covered}")
    return 0


if __name__ == "__main__":
    # 可选参数仅用于负向对照（指向临时 fixture 根目录）
    cli_root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT
    sys.exit(main(cli_root))
