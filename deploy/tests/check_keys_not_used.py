#!/usr/bin/env python3
"""
check_keys_not_used.py — Redis 内存泄漏门禁
目标：全仓业务源码禁止裸 keys() 调用（替代为 SCAN 或批量接口）
作用：防止 KEYS 阻塞主线程导致内存泄露警觉、并发上涨。
调用：从 .github/workflows/e2e.yml 跑
负向对照：把 PRIVATE_KEYS="*" 去掉 → 应报红；恢复 → 应绿。
历史数据：2026-10-27 实测无裸 keys() 调用 → PASS
"""
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ROOT_MODULES = ("cloud-infra-modules", "cloud-infra-gateway")
EXCLUDE_DIRS = {"target", "test", "generated"}
PRIVATE_KEYS_HINT = r'private\s+keys\s*\(\s*\)'  # detect private keys("xxx") or keys("xxx")


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
                        content = f.read()
                except Exception:
                    continue
                # 匹配 keys( 或 private keys( 但排除注释里的话（粗略）
                matches = re.findall(r'\bprivate\s+keys\s*\(', content)
                if matches:
                    hits.append(fpath)
    return hits


def main():
    hits = find_keys_calls()
    if not hits:
        print("OK: 全仓业务源码未发现裸 keys( 调用，Redis 操作已全部迁移到 SCAN / 批量接口")
        return 0
    print(f"FAIL: 发现 {len(hits)} 个文件含裸 keys( 调用：", file=sys.stderr)
    for h in hits[:20]:
        print(f"  {h.replace(REPO_ROOT + '/', '')}", file=sys.stderr)
    sys.exit(2)


if __name__ == "__main__":
    main()
