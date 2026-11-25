#!/usr/bin/env python3
"""
check_keys_not_used.py — Redis 内存泄漏门禁
目标：全仓业务源码禁止裸 keys() 调用（替代为 SCAN 或批量接口）
作用：防止 KEYS 阻塞 Redis 主线程，造成延迟尖刺与并发放大。
调用：verify-e2e.sh（advisory）

## 2026-11-14 修复恒假绿

原判据是 `re.findall(r'\\bprivate\\s+keys\\s*\\(', content)` ——
它匹配的是 Java 源码里的字面序列 `private keys(`，而真实的 Redis 调用是
`redisTemplate.keys("...")`。前者在合法 Java 中不可能出现，因此该门禁
**从来没有命中过任何文件**（第五课补记一：没验到 != 验过了）。

现判据为 `\\.\\s*keys\\s*\\(`（对象方法调用），并跳过 `//` / `*` 注释行。

负向对照：
  · 业务源码临时加入 `redisTemplate.keys("*")` -> 必须 rc=2
  · 删除该临时文件 -> 必须 rc=0
"""
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ROOT_MODULES = ("cloud-infra-modules", "cloud-infra-gateway")
EXCLUDE_DIRS = {"target", "test", "generated"}
KEYS_CALL_RE = re.compile(r'\.\s*keys\s*\(')


def is_comment_line(line: str) -> bool:
    stripped = line.lstrip()
    return stripped.startswith(("//", "*", "/*"))


def find_keys_calls():
    hits = []
    for sub in ROOT_MODULES:
        base = os.path.join(REPO_ROOT, sub)
        if not os.path.isdir(base):
            continue
        for root, dirs, files_list in os.walk(base):
            dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
            for fname in files_list:
                if not fname.endswith(".java"):
                    continue
                fpath = os.path.join(root, fname)
                try:
                    with open(fpath, encoding="utf-8") as f:
                        lines = f.readlines()
                except OSError:
                    continue
                for line in lines:
                    if is_comment_line(line):
                        continue
                    if KEYS_CALL_RE.search(line):
                        hits.append(fpath)
                        break
    return hits


def main():
    if not any(os.path.isdir(os.path.join(REPO_ROOT, sub))
               for sub in ROOT_MODULES):
        print("FAIL: 扫描根全部不存在 —— 扫描器坏了, 不能判绿", file=sys.stderr)
        return 2
    hits = find_keys_calls()
    if not hits:
        print("OK: 全仓业务源码未发现裸 keys( 调用，Redis 操作已全部迁移到 SCAN / 批量接口")
        return 0
    print(f"FAIL: 发现 {len(hits)} 个文件含裸 keys( 调用：", file=sys.stderr)
    for h in hits[:20]:
        print(f"  {h.replace(REPO_ROOT + '/', '')}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
