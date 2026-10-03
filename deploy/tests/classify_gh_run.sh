#!/usr/bin/env bash
# 判定一次 GitHub Actions run 是「代码失败」还是「**根本没启动**」。
#
# 为什么需要这个脚本
# ------------------
# 本仓库的 E2E job 有相当长一段时间在 GitHub 侧的账号计费/消费额度问题上红灯。
# 那条红灯与代码无关: job 一个 step 都没执行, 容器、编译、测试全部没发生。
#
# 但它在 `gh run list` 里与真正的代码失败**长得一模一样** —— 都是 red。
# 于是历史上多次出现把"计费阻塞"读成"我的改动有问题", 以及反过来
# 把"代码失败"读成"又是计费"的情况(AGENTS.md 记录的第 6 类假门禁)。
#
# 判定方法(可重复, 不依赖人眼)
# ----------------------------
# 1. steps == 0  -> job 从未开始执行, 任何代码都没跑过。
# 2. run 文本含 billing / payments / spending -> GitHub 明确说了是账号额度问题。
# 两者同时成立即为 BILLING_BLOCKED, 否则才是真实的代码/配置失败。
#
# 用法
# ----
#   ./deploy/tests/classify_gh_run.sh 37089939861
#   ./deploy/tests/classify_gh_run.sh --workflow e2e.yml --limit 1   # 查最新一次
#
# 退出码
# ------
#   0  RUN_OK       run 成功
#   1  CODE_FAILURE  run 失败, 且**确实执行过** step -> 是真的代码/配置问题
#   2  BILLING_BLOCKED  job 未启动, GitHub 账号计费/额度问题 -> **不是代码问题**
#   3  UNKNOWN       无法判定(如 gh 未登录、run 不存在、网络不通)
set -u

BLOCKED_MARKERS='recent account payments have failed|spending limit needs to be increased|Billing & plans'

classify_one() {
  local run_id="$1"
  local raw steps conclusion

  raw=$(gh run view "$run_id" 2>&1) || {
    echo "UNKNOWN  无法读取 run $run_id (gh 未登录 / run 不存在 / 网络不通)"
    echo "--- gh 输出 ---"
    echo "$raw" | head -5
    return 3
  }

  # ⚠️ 必须读**每个** job 的 steps, 不能只看顶层 conclusion。
  #   顶层 conclusion 是所有 job 的聚合, 而"未启动"这件事只体现在
  #   具体 job 的 steps 长度上 —— 聚合层看起来和普通失败一模一样。
  steps=$(gh run view "$run_id" --json jobs \
            --jq '[.jobs[].steps | length] | add // 0' 2>/dev/null)
  conclusion=$(gh run view "$run_id" --json conclusion --jq '.conclusion' 2>/dev/null)

  if [ -z "$steps" ] || [ "$steps" = "null" ]; then
    echo "UNKNOWN  run $run_id 拿不到 steps 数 (gh 返回异常)"
    return 3
  fi

  if echo "$raw" | grep -qE "$BLOCKED_MARKERS"; then
    echo "BILLING_BLOCKED  run $run_id"
    echo "  GitHub 原文: $(echo "$raw" | grep -oE "$BLOCKED_MARKERS" | head -1)"
    echo "  steps 执行数 = $steps  (0 表示代码从未运行)"
    echo "  => 需人工在 GitHub Settings -> Billing & plans 处理。改代码无效。"
    return 2
  fi

  if [ "$steps" -eq 0 ]; then
    # 没匹配到计费字样却也没执行任何 step —— 不能默认当成计费。
    # 也可能是 runner 排队失败、被并发上限拒绝、或 workflow 语法错误。
    # 归为 UNKNOWN 而不是 BILLING, 避免把别的原因误标成计费。
    echo "UNKNOWN  run $run_id steps=0 但日志里没有 GitHub 计费字样"
    echo "  => 不是已知的计费模式。可能是 runner 排队/并发上限/workflow 语法问题, 请人工查。"
    echo "--- run 摘要 ---"
    echo "$raw" | head -8
    return 3
  fi

  if [ "$conclusion" = "success" ]; then
    echo "RUN_OK     run $run_id (conclusion=success, steps=$steps)"
    return 0
  fi

  echo "CODE_FAILURE  run $run_id (conclusion=$conclusion, steps=$steps)"
  echo "  => job 确实执行过, 这是真实的代码/配置失败。"
  echo "--- 失败步骤 ---"
  gh run view "$run_id" --json jobs \
    --jq '.jobs[] | .steps[] | select(.conclusion=="failure") | .name' 2>/dev/null | head -5
  return 1
}

main() {
  if [ $# -eq 0 ]; then
    echo "用法: $0 <run-id> | $0 --workflow <name> [--limit N]" >&2
    return 3
  fi
  if [ "$1" = "--workflow" ]; then
    local wf="${2:?缺少 workflow 名}"
    local limit="${4:-1}"
    local rid
    rid=$(gh run list --workflow="$wf" --limit "$limit" --json databaseId \
            --jq '.[0].databaseId' 2>/dev/null)
    if [ -z "$rid" ]; then
      echo "UNKNOWN  找不到 workflow=$wf 的 run"
      return 3
    fi
    echo "(使用 workflow=$wf 的最新 run: $rid)"
    classify_one "$rid"
    return $?
  fi
  classify_one "$1"
}

main "$@"
