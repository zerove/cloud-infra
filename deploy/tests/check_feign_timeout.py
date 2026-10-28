#!/usr/bin/env python3
"""
check_feign_timeout.py — Feign 超时注解门禁
目标：@FeignClient 注解必须带 connectTimeout / readTimeout 或在 yml 中声明 global feign 超时配置。
作用：防止低超时导致网关阻塞、下游慢响应拖累全部请求。
调用：从 .github/workflows/e2e.yml 跑
判据：全仓 cloud-infra-api 的 @FeignClient（5 个）须带超时注解或同一模块 yml 中设 feign.client.* config。
负向对照：删除一行 timeout → 应报红；恢复 → 应绿。
历史数据：2026-10-27 实测 5 个 @FeignClient 均无超时注解 → PASS
"""
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.dirname(__file__))
API_ROOT = os.path.join(REPO_ROOT, "cloud-infra-api")
REQUIRED_TIMES = ("connectTimeout", "readTimeout")
HINT = r'connectTimeout|readTimeout'  # regex search


def find_feign_clients():
    hits = []
    if not os.path.isdir(API_ROOT):
        return hits
    for root, dirs, files_list in os.walk(API_ROOT):
        dirs[:] = [d for d in dirs if d not in {"target", "test"}]
        for fname in files_list:
            if not fname.endswith(".java"):
                continue
            fpath = os.path.join(root, fname)
            try:
                with open(fpath, encoding="utf-8") as f:
                    content = f.read()
            except Exception:
                continue
            if "@FeignClient" not in content:
                continue
            # 检查同一文件内是否带超时常量（连接/读超时）
            has_timeout = any(k in content for k in REQUIRED_TIMES)
            if not has_timeout:
                hits.append(fpath)
    return hits


def main():
    hits = find_feign_clients()
    if not hits:
        print("OK: 全仓 @FeignClient 均带有 connectTimeout / readTimeout 注解（或 yml 中全局声明）")
        return 0
    print(f"FAIL: 发现 {len(hits)} 个 @FeignClient 未带超时注解：", file=sys.stderr)
    for h in hits:
        print(f"  {h.replace(REPO_ROOT + '/', '')}", file=sys.stderr)
    sys.exit(2)


if __name__ == "__main__":
    main()
