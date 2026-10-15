#!/usr/bin/env python3
"""守护: 不得把真实密钥硬编码进 git 跟踪的配置文件。

## 背景（含一次自我更正）

初次审计时报出「deploy/k8s/infra/config-secrets.yaml 提交了真实密钥」并定为 P0,
**该结论是错的**: 该文件已被 .gitignore 排除(`git ls-files` 无命中), 只是本地工作区产物。
K8s 侧密钥治理实际健全 —— 各 chart 走 `existingSecret`/`secretKeyRef`,
`values.yaml` 的 `secret:` 块是空占位, `deploy/environments/*/values-secrets.yaml` 是 Helm 模板。
真正残留的是**弱默认兜底值**(postgres `password`、rabbitmq `guest`), 在 Secret 缺失时会被真的使用。

## 判定范围的取舍

只检查 **git 跟踪**文件, 且只检查配置文件后缀(不含 `.java`)。
`JwtUtils` 的弱密钥黑名单里确实含一个真实 dev JWT 串, 但那是**防御性拒绝列表** ——
保留它正是为了拒绝该值; 把它当泄漏报出来会诱导下一个人删掉防御。

## 两条踩过的坑

1. **不能用"高熵串"做启发式**。首版用 `[A-Za-z0-9+/_-]{40,}`, 结果把包名、
   镜像坐标、长标识符全部误报(实测 20+ 处), 守护等于不可用。
   密钥的可靠信号不是长度, 而是**它被赋给了密钥类的键**。
2. **安全值白名单里不能有能匹配空串的模式**。首版含 `(?i)^(\\"|[<$@]?\\{?)`,
   其第二分支在位置 0 即匹配空串, 于是**任何值都被判为安全**, 守护恒绿 ——
   这正是本仓库反复记录的那类假门禁: 判据存在且 rc=0, 但从未真正拒绝过任何东西。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

CONFIG_SUFFIXES = {".yaml", ".yml", ".xml", ".properties", ".env", ".conf"}
SKIP_DIRS = {".git", "target", "node_modules"}
TEMPLATE_MARKERS = ("example", "template", "sample")

# 判定范围的策略边界: 测试夹具里的密钥**必须**是字面量(否则无法离线运行),
# dev 环境值也不构成"泄漏到生产"。把它们排除, 否则守护会因存量问题恒红,
# 而恒红的门禁等于没有门禁 —— 那正是本仓库反复踩的坑。
EXCLUDED_PREFIXES = (
    "cloud-infra-*/src/test/resources/*",   # 测试夹具
    "deploy/environments/dev/*",           # 仅开发环境
)

SECRET_KEY_RE = re.compile(
    r"(?i)^[\s#-]*([A-Za-z0-9_.-]*"
    r"(?:secret|password|passwd|token|api[_-]?key|access[_-]?key|private[_-]?key|credential)"
    r"[A-Za-z0-9_.-]*)"
    r"\s*[:=]\s*(.+?)\s*$"
)

# 每一条都必须"至少消耗一个字符", 否则会把所有值判为安全(见模块 docstring 第 2 条)
SAFE_VALUE_PATTERNS = (
    re.compile(r"\$\{"),                                  # ${VAR} / ${VAR:default} / ${VAR:?强制}
    re.compile(r"\{\{"),                                  # Helm 模板
    re.compile(r"^\s*$"),                                  # 空
    re.compile(r"(?i)^(null|none|true|false|\*|changeme|placeholder|redacted|todo)$"),
    re.compile(r"(?i)secretKeyRef|configMapKeyRef|existingSecret"),
    re.compile(r"(?i)^(kubectl|kubeseal|echo|docker|podman|mvn|java|bash|sh|\./)"),
    re.compile(r"^\$\("),                                 # 命令替换
)

WEAK_EXACT = {"password", "guest", "admin", "secret", "changeme", "123456"}


def tracked_files() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=REPO_ROOT,
                         capture_output=True, text=True, check=True)
    return out.stdout.splitlines()


def is_scannable(rel: str) -> bool:
    path = Path(rel)
    if any(part in SKIP_DIRS for part in path.parts):
        return False
    import fnmatch
    if any(fnmatch.fnmatch(rel, pattern) for pattern in EXCLUDED_PREFIXES):
        return False
    if path.suffix.lower() not in CONFIG_SUFFIXES:
        return False
    lowered = rel.lower()
    return not any(marker in lowered for marker in TEMPLATE_MARKERS)


def is_violation(key: str, raw: str) -> str | None:
    raw = raw.strip().strip('"').strip("'")
    if any(pattern.search(raw) for pattern in SAFE_VALUE_PATTERNS):
        return None
    if raw.lower() in WEAK_EXACT:
        return f"弱默认口令 {key}={raw!r}"
    if len(raw) >= 24 and re.fullmatch(r"[A-Za-z0-9+/=_-]+", raw):
        return f"疑似硬编码密钥 {key} (长度 {len(raw)}, 前缀 {raw[:6]}…)"
    return None


def main() -> int:
    try:
        files = tracked_files()
    except subprocess.CalledProcessError:
        print("FAIL: git ls-files 执行失败, 无法判定")
        return 1

    failures: list[str] = []
    scanned = 0

    for rel in files:
        if not is_scannable(rel):
            continue
        try:
            text = (REPO_ROOT / rel).read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if not text.strip():
            continue
        scanned += 1
        for line_no, line in enumerate(text.splitlines(), start=1):
            if line.lstrip().startswith("#"):
                continue
            match = SECRET_KEY_RE.match(line)
            if not match:
                continue
            violation = is_violation(match.group(1), match.group(2))
            if violation:
                failures.append(f"{rel}:{line_no}: {violation}")

    if failures:
        print("FAIL:")
        for failure in failures:
            print(f"  - {failure}")
        print("\n真实密钥必须经 Secret / 环境变量注入; 弱默认口令不得作为兜底值。")
        return 1

    print(f"PASS 密钥入库守护: 扫描 {scanned} 个跟踪配置文件, 未发现硬编码真实密钥")
    return 0


if __name__ == "__main__":
    sys.exit(main())