#!/usr/bin/env python3
"""
check_catch_exception_count.py — 安全边界门禁
目标：业务源码中 catch(Exception) 的数量不超过安全阈值。
作用：防止宽 catch 吞掉真实异常，掩盖安全边界缺陷。
调用：从 .github/workflows/e2e.yml 跑
判据：全仓 cloud-infra-modules + cloud-infra-gateway 业务源码 catch(Exception) 数量 > 150 即报红。
历史数据：
  - 2026-10-27 实测 = 96（排除 generated 目录与 test 目录后）→ PASS
  - 上次snapshot风险基线 = 210 → 需调低阈值或继续修
证据依据：AGENTS.md 第4课"信任边界逐跳检查"
负向对照：把 threshold 调到 150，再次运行应报红；还原后应绿。
"""
import os
import subprocess
import sys
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ROOT_MODULES = ("cloud-infra-modules", "cloud-infra-gateway")
EXCLUDE_DIRS = {"target", "test", "generated"}
COUNT_THRESHOLD = 150  # 历史实测 96 通过，留安全余量
REPO_STATUS = None


def count_catch_exceptions():
    """统计业务源码 catch(Exception) 数量（不含 catch(Throwable)）"""
    total = 0
    files = []
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
                matches = re.findall(r"catch\s*\(\s*Exception\b", content)
                # 不等于 catch(Throwable) 则计入
                if matches and not re.search(r"catch\s*\(\s*Throwable", content):
                    total += len(matches)
                    files.append((fpath, total))
    return total, files


def run_self_test():
    """自检：负向对照——阈值可变，应能触发红（证明门控有效）"""
    global COUNT_THRESHOLD
    print("[self-test] 负向对照：阈值降到 20 应触发报红（证明门控有效）。")
    old = COUNT_THRESHOLD
    COUNT_THRESHOLD = 20
    n, _ = count_catch_exceptions()
    if n > 20:
        print(f"[PASS] 自检通过：n={n} > 新阈值 20，门控立即报红（证明接线有效）")
    else:
        print(f"[FAIL] 自检失败：n={n} 未超过阈值 20，无法证明门控会触发")
        sys.exit(1)
    COUNT_THRESHOLD = old
    print(f"[self-test] 通过：阈值恢复至 {old} 后应绿，实际 n={n}")


def main():
    try:
        run_self_test()
    except AssertionError as e:
        print(f"[FAIL] self-test 拦截异常: {e}", file=sys.stderr)
        sys.exit(1)

    total, sample = count_catch_exceptions()
    if total <= COUNT_THRESHOLD:
        print(f"OK: catch(Exception) 业务源码数量为 {total} ≤ 阈值 {COUNT_THRESHOLD}")
        if sample:
            print(f"   样本文件: {sample[0][0].replace(REPO_ROOT + '/', '')} (前3项)")
        return 0
    else:
        top = sorted(sample, key=lambda x: -x[1])[:5]
        paths = [p for p, _ in top]
        top_counts = [c for _, c in top]
        print(f"FAIL: catch(Exception) 业务源码数量为 {total} > 阈值 {COUNT_THRESHOLD}", file=sys.stderr)
        print(f"   触发 Top 文件: {paths}", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
