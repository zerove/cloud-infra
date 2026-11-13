#!/usr/bin/env python3
"""守护: Dockerfile 的 ENV 赋值语法合法, 且 JVM 参数不指向不存在的文件。

## 要防的缺陷 (2026-10-17 实测, 非推测)

删除失效的 CDS 配置时用了正则 `-XX:SharedArchiveFile=\\S+`。`\\S+` 是贪婪的,
**把行尾的右双引号一起吃掉了**, 于是 7 个 Dockerfile 的

    ENV JAVA_OPTS="... -XX:+UseContainerSupport"

全部缺右引号 → `podman build` 在解析该行时报

    unexpected end of statement while looking for matching double-quote

而这类缺陷**没有任何既有门禁能发现**:

- `mvn test` 不构建镜像 (516 项全绿)
- `verify-dockerfiles.sh` 只校验 COPY 的 jar 匹配, 不看 ENV 语法
- 真正暴露它的是手工 `podman build`, 成本高且容易被跳过

即"删掉一行死配置"这种看起来安全的改动, 实际打断了全部 7 个服务的镜像构建。

## 判定规则

对每个 Dockerfile / Dockerfile.template:

1. 每个 `ENV <NAME>="` 开头的行, 双引号数量必须为偶数 (否则语法已断);
2. 不得出现 `-XX:SharedArchiveFile=` (仓库不生成任何 .cds, 该 flag 必为悬空);
3. `COPY ... *.cds` / `RUN cp ... *.cds` 一律不允许 (生成物已删除)。

## 负向对照

`--negative-control` 会在副本上删掉某行 JAVA_OPTS 的右引号, 断言此时**必须**报红。
守护若不会红, 说明它本身失效了。
"""
from __future__ import annotations

import argparse
import pathlib
import re
import shutil
import sys
import tempfile

DOCKERFILES = "Dockerfile*"
FORBIDDEN = (
    "-XX:SharedArchiveFile=",
    "*.cds",
)


def dockerfiles(root: pathlib.Path) -> list[pathlib.Path]:
    found = []
    for pattern in ("Dockerfile", "Dockerfile.*", "*/Dockerfile", "*/*/Dockerfile",
                    "*/Dockerfile.*", "deploy/*/Dockerfile.*"):
        found.extend(root.glob(pattern))
    return sorted({p for p in found if p.is_file()})


def check(path: pathlib.Path) -> list[str]:
    problems: list[str] = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        if stripped.startswith("ENV ") and '"' in stripped:
            if stripped.count('"') % 2 != 0:
                problems.append(f"L{lineno} ENV 值的双引号不成对: {stripped[:90]}")
        for token in FORBIDDEN:
            if token in line:
                problems.append(
                    f"L{lineno} 出现 `{token}` —— 仓库已不再生成 .cds 存档, "
                    f"JVM 若读到该 flag 会在每次启动时报 [error][cds]"
                )
    return problems


def audit(root: pathlib.Path) -> int:
    files = dockerfiles(root)
    if not files:
        print("!! 未找到任何 Dockerfile")
        return 1
    failed = 0
    for path in files:
        problems = check(path)
        if problems:
            failed += 1
            print(f"⛔ {path.relative_to(root)}")
            for problem in problems:
                print(f"    · {problem}")
        else:
            print(f"✅ {path.relative_to(root)}")
    print(f"\nDockerfile {len(files)} 个, 不合规 {failed} 个")
    return failed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--negative-control", action="store_true",
                        help="在副本上制造引号不配对, 断言守护必须报红")
    args = parser.parse_args()
    root = pathlib.Path(args.root).resolve()

    if args.negative_control:
        with tempfile.TemporaryDirectory() as tmp:
            clone = pathlib.Path(tmp) / "repo"
            shutil.copytree(root, clone, dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns('.git', 'target', '.scratch'))
            victim = clone / "cloud-infra-gateway" / "Dockerfile"
            text = victim.read_text(encoding="utf-8")
            mutated = re.sub(r'^(ENV JAVA_OPTS=".*)"\s*$', r'\1', text, flags=re.M)
            assert mutated != text, "负向对照变异失败: 未找到可破坏的 ENV JAVA_OPTS 行"
            victim.write_text(mutated, encoding="utf-8")

            failed = audit(clone)
            if failed == 0:
                print("\n⛔ 负向对照失败: 删掉右引号后守护仍全绿 —— 该守护无效。")
                return 1
            print("\n✅ 负向对照通过: 引号不配对被如期报红。")
            return 0

    return 1 if audit(root) else 0


if __name__ == "__main__":
    sys.exit(main())