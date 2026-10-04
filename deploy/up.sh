#!/bin/bash
# Cloud-Infra 本地开发环境启动脚本
# 依赖: podman-compose, curl
# 构建: 使用项目内的 mvnw（无需预先安装 Maven）
#
# 用法:
#   ./up.sh                # 启动基础设施 + 同步配置 (不启动微服务, 用于开发)
#   ./up.sh --full         # 启动全部 (基础设施 + 微服务, 需要先构建镜像)
#   ./up.sh --check        # 检测本地配置与 Nacos 的漂移, 不执行变更
#   ./up.sh --sync         # 仅同步本地配置到 Nacos (不启动基础设施, 不重启容器)
#   ./up.sh --infra        # 仅启动基础设施 (不级联清理数据卷)
#   ./up.sh --self-test    # 仅运行兼容性自检 (不碰容器, 可在 CI 跑)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 默认仅基础设施; --full 时含微服务
COMPOSE_ARGS=()
FULL_SERVICES=()
FULL=0
CHECK_ONLY=0
SYNC_ONLY=0
INFRA_ONLY=0
SELF_TEST=0
for arg in "$@"; do
  case "$arg" in
    --full) FULL=1 ;;
    --check) CHECK_ONLY=1 ;;
    --sync) SYNC_ONLY=1 ;;
    --infra) INFRA_ONLY=1 ;;
    --self-test) SELF_TEST=1 ;;
    *) echo "未知参数: $arg (支持 --full --check --sync --infra --self-test)" >&2; exit 1 ;;
  esac
done

# compose.yaml 中 `profiles: ["full"]` 的服务名。
#
# ⚠️ 这里必须用 **compose 的服务名 (services 段下的 key)**, 不是镜像名, 也不是
# container_name。镜像名是 localhost/cloud-infra-gateway, container_name 是
# cloud-gateway, 但服务名就是 gateway。
# 548b9bc 误用了镜像名 (cloud-infra-gateway), 于是即便参数位置正确也会失败。
FULL_SERVICES=(gateway auth system file notification search)

# 探测 podman-compose 是否支持 --profile。
# CI 的 apt 包是 1.0.6 (实测其源码 grep -c profile = 0, 完全无此特性);
# 本地通常是 1.6.0。
compose_supports_profile() {
  podman-compose --help 2>&1 | grep -q -- '--profile'
}

# 自愈: 修复被强停容器留下的 0 字节 healthcheck.log。
#
# ⚠️ 这是本项目最阴的一类环境故障(2026-10-05 实测, 当天踩了两次)。
#   podman 在容器停止时会 truncate healthcheck.log 再写新记录; 若进程在写入前
#   就被杀(容器 crash / 强停 / 本项目反复出现的 malloc 失败崩溃), 就留下一个
#   **0 字节文件**。之后:
#
#     $ podman ps
#     Error: unable to get healthcheck log for <id>: failed to unmarshal ...
#     rc=125, 且 stdout 完全为空
#
#   一个坏文件会让**所有** podman ps / podman-compose 调用失败 ——
#   实测中 podman-compose 因此直接抛 CalledProcessError 而无法执行。
#
# ⚠️ rc=125 是 podman 的错误信号, **不是**超时(124)。本次我先把它当成超时,
#   去找"为什么这么慢", 方向完全错了(第八课: 探针失效被读成别的问题)。
#
# 修法: 写入合法 JSON。正常内容形如:
#   {"Status":"reset","FailingStreak":0,"Log":null}
# 这是**回退自身故障**的修复, 不丢任何数据(空文件本来就没有数据)。
repair_healthcheck_logs() {
  local root d f fixed=0
  root="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/containers/overlay-containers"
  [ -d "$root" ] || return 0
  for d in "$root"/*/; do
    f="$d/healthcheck.log"
    # 只碰 **存在且为 0 字节** 的; 非空的一律不碰(那是有效记录)
    [ -f "$f" ] || continue
    [ -s "$f" ] && continue
    if printf '{"Status":"reset","FailingStreak":0,"Log":null}' >"$f" 2>/dev/null; then
      fixed=$((fixed+1))
    fi
  done
  [ "$fixed" -gt 0 ] && echo "  ✓ 已修复 $fixed 个空的 healthcheck.log (podman ps 本会因此全面失败)"
  return 0
}

# 逐个拉起 full-profile 业务服务, 每个之间等 $1 秒。
#
# ⚠️ 必须串行的原因(2026-10-05 实测, 非推测): 宿主机 vm.overcommit_memory=2,
# 所有进程共享同一个全局 commit 预算(MemTotal+SwapTotal≈20.63GB)。并发启动多个
# JVM 时, 每个进程都要预留虚拟内存, 瞬时冲破预算 —— 崩掉的**不是刚启动的**,
# 而是**已经在跑**的服务(malloc 失败)。即第八课的“给更多资源反而更早失败”。
#
# 这个函数是串行逻辑的**唯一来源**。不要为了分profile/非profile 两条路径
# 而复制一份 —— 复制后守护只能匹配其中一份, 另一份退回并发时它仍然全绿,
# 这正是第五课补记一说的“守护假装通过”。
#
# ⚠️ 关键: 必须用 `podman start` 而不是 `podman-compose up -d <svc>`。
#   实测(2026-10-05 第三次修正): compose 里有 11 处 depends_on, 且业务服务
#   同属 profile full —— `podman-compose up -d gateway` 仍会把依赖链乃至
#   其余 full 服务一并带起, 串行完全失效(实测重启了全部六个, 反而更糟)。
#   `podman start <容器>` 只启动已存在的容器, 不解析依赖, 才是真串行。
#   因此前提是: 容器**已由 compose 创建好**(调用方先用 compose up -d 创建,
#   但用 -1 或在基础设施阶段创建), 本函数只负责错峰拉起。
#
# ⚠️ 为什么要“等稳定”而不只是 sleep:
#   首版间隔 45s, 六个服务仍有两个崩溃。取崩溃时间线得到真实数据:
#     system       启动 02:16:23 -> 崩溃 02:18:44  = 存活 141s
#     notification 启动 02:17:24 -> 崩溃 02:19:00  = 存活  96s
#   即崩溃发生在启动后 96–141秒 —— 那时上一个 JVM 仍在加载完整 Spring 上下文,
#   它的 commit 还在增长。45s 间隔下, 下一个启动时上一个远未到稳定值。
#   故改为**等到连续稳定再继续**, 而不是等固定时长。
#
#   ⚠️ 而且“等到 running 就继续”是错的: 实测启动后 50s 采样仍显示 running,
#   再过 60s 就已 exited。探针过早采样会把延迟崩溃读成正常(与第八课补记一
#   同类)。故要求**连续稳定 settle_secs 秒**才继续。
start_full_services_serial() {
  local gap="${1:-0}" settle_secs="${2:-180}" svc deadline st stable_since
  echo "  逐个拉起业务服务 (间隔 ${gap}s, 需连续稳定 ${settle_secs}s, 避免抢全局 commit 预算)..."
  for svc in "${FULL_SERVICES[@]}"; do
    printf '    -> %-14s' "$svc"
    if ! timeout 120 podman start "cloud-$svc" >/dev/null 2>&1; then
      # 容器不存在时先创建(不带 --profile full 以免带起其余服务)。
      if ! timeout 300 podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" \
           ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"} up -d --no-deps "$svc" >/dev/null 2>&1; then
        echo "    ⚠ 启动失败(容器可能不存在), 继续下一个"
        continue
      fi
    fi
    printf '已拉起 (commit %s%%)\n' "$(commit_pct)"

    # 等本服务真正稳定再启下一个(带超时, 不无限等)。
    stable_since=0
    deadline=$(( $(date +%s) + settle_secs + 240 ))
    while :; do
      # 探针必须带超时: podman 偶发卡住时会把“取不到值”读成“已退出”(第八课补记一)。
      st=$(timeout 20 podman inspect --format '{{.State.Status}}' "cloud-$svc" 2>/dev/null)
      now=$(date +%s)
      if [ "$st" != "running" ]; then
        stable_since=0
        if [ "$now" -ge "$deadline" ]; then
          echo "    ⚠ 超时仍未稳定 (当前状态 ${st:-probe-timeout}), 继续下一个"
          break
        fi
      else
        [ "$stable_since" -eq 0 ] && stable_since=$now
        if [ $(( now - stable_since )) -ge "$settle_secs" ]; then
          echo "    ✓ 已稳定 ${settle_secs}s (commit $(commit_pct)%)"
          break
        fi
      fi
      sleep 10
    done
    # ⚠️ 这里不能用 `[ "$gap" -gt 0 ] && sleep "$gap"`:
    #   gap=0 时该表达式返回 1, 在 `set -e` 下会让整个函数以失败告终 ——
    #   实测子 shell 跑完两个服务后 rc=1。改成显式 if, 保证函数末尾恒返回 0。
    if [ "$gap" -gt 0 ]; then sleep "$gap"; fi
  done
}

# 读宿主机全局 commit 占用率。用于**启动日志**里的人工判断,
# 不作为门禁 —— 实测85–90% 这个区间既可能崩也可能稳, 静态值无法区分。
commit_pct() {
  awk '/^Committed_AS/{c=$2} /^CommitLimit/{l=$2}
       END{if(l>0)printf "%.0f", c/l*100; else print "?"}' /proc/meminfo 2>/dev/null
}

# 拼出最终命令行。兼容两条路径:
#   支持 --profile: `podman-compose --profile full up -d`
#   不支持:        `podman-compose up -d gateway auth ...`
#                   实测 1.0.6 下显式点名确实会绕过 profile 过滤
#                   (最小复现: up -d web 启动 web, up -d infra 则 web/api 均被 skip),
#                   因此回退路径语义等价。
#
# ⚠️ 服务名必须放在 `up -d` **之后**。1.0.6 的 argparse 把第一个位置参数当 command,
# 放前面会得到 "invalid choice: 'gateway'" (2026-10-02 E2E 红灯的直接原因)。
resolve_compose_args() {
  COMPOSE_ARGS=()
  if [ "$FULL" -eq 1 ] && compose_supports_profile; then
    COMPOSE_ARGS+=(--profile full)
  fi
}

# 实际拼出的 `up` 参数列表。放服务名的分支与不放服务名的一律用数组返回,
# 避免未定义数组在 set -u 下展开出错。
compose_up_args() {
  if [ "$FULL" -eq 1 ] && ! compose_supports_profile; then
    printf '%s\n' up -d "${FULL_SERVICES[@]}"
  else
    printf '%s\n' up -d
  fi
}

resolve_compose_args

# ---------------------------------------------------------------------------
# 兼容性自检 (--self-test): 不启动任何容器, 验证参数拼装与 compose.yaml 一致。
#
# ⚠️ 为什么不能"跑一次 podman-compose 看退出码"来自检:
#   ① podman-compose --dry-run 在 1.6.0 下**输出为空** (实测 stdout/stderr 均 0 行),
#      探测不到任何东西; 放在 up 之后还会直接 error: unrecognized arguments;
#   ② 本地版本与 CI 不同 (本地 1.6.0 / CI apt 包 1.0.6), 用本地二进制自检
#      等于**永远没演练过 CI 的那条路径** —— 这是 548b9bc 自检方案的根本缺陷。
#
# 因此自检改为两件真正能失败的事:
#   ① 直接解析 compose.yaml 的 services 键, 核对 FULL_SERVICES 集合;
#   ② 用一个 1.0.6 行为桩校验参数**顺序** —— 桩只复刻 argparse 的
#      "第一个位置参数即 command" 这条规则, 与真实 1.0.6 报错一致。
#
# ⚠️ ① 为什么不用 `podman-compose config --services`(2026-10-02 修正):
#   该子命令在 CI 的 1.0.6 上**不存在**, 命令整体报错。原先的 `|| true`
#   把失败吞成空串, 于是自检在 CI 上恒定报 FAIL —— 又一次"本地绿、CI 红"。
#   本地是 1.6.0(支持该子命令), 正是这个版本差让它逃过了本地验证。
#   改为解析 YAML 文本: 不依赖 podman-compose 版本, 两边行为必然一致。
#
# ⚠️ 本自检自身也是负向对照的对象: 把服务名挪回 `up` 之前, 或改回镜像名,
# ① / ② 都必须变红。
# ---------------------------------------------------------------------------
self_test() {
  local failures=0

  echo "自检 1/3: FULL_SERVICES 与 compose.yaml 的服务名一致"
  # 只取 services 块内的顶层键。compose.yaml 里 services 下缩进 2 空格。
  local svc_list svc
  svc_list="$(awk '
    /^services:[[:space:]]*$/ { in_services = 1; next }
    in_services && /^[^[:space:]#]/ { in_services = 0 }
    in_services && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ {
      line = $0
      sub(/^  /, "", line)
      sub(/:.*/, "", line)
      print line
    }
  ' "$SCRIPT_DIR/compose.yaml")"
  if [ -z "$svc_list" ]; then
    echo "  FAIL  无法从 compose.yaml 解析出 services 列表"
    failures=$((failures + 1))
  else
    for svc in "${FULL_SERVICES[@]}"; do
      if printf '%s\n' "$svc_list" | grep -qx "$svc"; then
        echo "  PASS  服务名 '$svc' 存在"
      else
        echo "  FAIL  服务名 '$svc' 在 compose.yaml 中不存在"
        failures=$((failures + 1))
      fi
    done
  fi

  echo "自检 2/3: 服务名位于 'up' 之后 (1.0.6 行为桩)"
  # 桩: 复刻 1.0.6 的 argparse —— 第一个位置参数必须是已知 command。
  # 真实 1.0.6 报错为 "argument command: invalid choice: 'gateway'"。
  local stub
  stub="$(mktemp)"
  cat > "$stub" <<'STUB'
#!/usr/bin/env bash
# 复刻 podman-compose 1.0.6: 第一个位置参数被当作 command, 不匹配即报错。
positional=()
while [ $# -gt 0 ]; do
  case "$1" in
    -f|--file|--env-file|-p|--podman-path|--podman-args) shift 2 ;;
    -*) shift ;;
    *) positional+=("$1"); shift ;;
  esac
done
if [ ${#positional[@]} -eq 0 ]; then
  echo "STUB: 缺少 command" >&2
  exit 1
fi
case "${positional[0]}" in
  help|version|wait|systemd|pull|push|build|up|down|ps|run|exec|start|stop|restart|logs|config|port|pause|unpause|kill) ;;
  *) echo "podman-compose: error: argument command: invalid choice: '${positional[0]}'" >&2; exit 2 ;;
esac
echo "STUB_OK command=${positional[0]}"
STUB
  chmod +x "$stub"

  # 在子 shell 里把 compose_supports_profile 打成"不支持", 以覆盖 CI 的 1.0.6 路径。
  if (
    compose_supports_profile() { return 1; }
    FULL=1
    mapfile -t up_args < <(compose_up_args)
    "$stub" "${up_args[@]}"
  ) >/dev/null 2>&1; then
    echo "  PASS  服务名位于 up 之后, 1.0.6 可解析"
  else
    echo "  FAIL  服务名位置或顺序错误, 1.0.6 无法解析 (会得到 invalid choice)"
    failures=$((failures + 1))
  fi

  # 负向对照: 若顺序被改回 "服务名 up -d", 桩必须拒绝。
  if "$stub" "${FULL_SERVICES[0]}" up -d >/dev/null 2>&1; then
    echo "  FAIL  桩未能拒绝错误顺序, 说明自检本身失效"
    failures=$((failures + 1))
  else
    echo "  PASS  桩正确拒绝错误顺序 (负向对照成立)"
  fi

  rm -f "$stub"

  # 自检 3: 业务服务必须串行间隔启动(2026-10-05 新增, 必须守护)。
  #
  # 实测依据: 并发启动多个 JVM 会撑穿宿主机**全局** commit 预算
  # (vm.overcommit_memory=2), 挤崩**已在运行**的服务(malloc 失败)。
  #
  # ⚠️ 这里**不能**只 grep 源码。早前这么做过, 守护全绿, 但把另一处
  # 重复的循环改回并发时仍然全绿 —— 断言匹配到的是"某一个"实现,
  # 不是"唯一的"实现。故改为**执行** start_full_services_serial, 用桩
  # 替换 podman-compose 来记录真实调用序列。
  echo "自检 3/3: 业务服务串行启动 (避免抢占全局 commit 预算)"

  # ① 默认间隔非 0。0 等于退回并发, 正是本次实测会崩的路径。
  if grep -qE 'START_GAP="\$\{START_GAP:-[1-9]' "$SCRIPT_DIR/up.sh"; then
    echo "  PASS  START_GAP 默认非 0 (不会退回并发启动)"
  else
    echo "  FAIL  START_GAP 默认应为非 0 的间隔秒数"
    failures=$((failures + 1))
  fi

  # ② 行为证据: 真的调用一次 start_full_services_serial, 记录调用序列。
  #    期望: N 个服务 -> N 次独立 podman-compose 调用, 每次只带 1 个服务名。
  #    并发写法是 1 次调用带 N 个服务名 —— 两者计数与形态都不同。
  #
  #    ⚠️ 用 awk 抽取函数体到临时文件后 source, **不能** source 整个脚本:
  #    顶层有`set -euo pipefail` 与参数解析, source 会连带执行它们 ——
  #    实测桩返回非零即被 set -e 中止, 且桩会被真实顶层逻辑覆盖。
  #    只 source 函数体, 被测单元与被测行为严格一致。
  local fn_src; fn_src=$(mktemp)
  local call_log; call_log=$(mktemp)
  local stub_dir; stub_dir=$(mktemp -d)
  awk '/^start_full_services_serial\(\) \{/,/^\}/' "$SCRIPT_DIR/up.sh" >"$fn_src"
  # 唯一性判据: **全文里逐服务启动的实现只能有一处, 且必须是本函数**。
  #
  # ⚠️ 本项目实测踩到第 8 次的“断言字面量污染扫描”, 四次失败版本:
  #  ① 只数函数名 -> 改名副本 _dup 绕过(假绿)。
  #  ② 数所有含该模式的函数, 不排除断言自身 -> 扫到断言里的模式字符串,
  #     正向自判红(自检把自己判成失败, 比假绿更难排查)。
  #  ③ 加 awk range 排除 -> range 未闭合把真实实现也跳过, 扫出 0 处(另一种假绿)。
  #  ④ 只跳过注释行 -> 仍扫到 awk 脚本里那行模式本身。
  #
  # 结论: **任何"在自身文件里搜索某模式"的断言都会匹配到自己**,
  # 因为模式字面量必然存在于该文件。故唯一可靠做法是: 先用**稳定锚点**
  # 算出断言宿主的行区间并整体跳过, 再在区间外搜索。
  # 锚点用 self_test 函数名 —— 与本断言同生命周期, 且不会被重构改动。
  # 模式串用变量传入而非内联, 避免连awk 源码那一行也落进扫描范围。
  lo=$(grep -n '^self_test() {' "$SCRIPT_DIR/up.sh" | head -1 | cut -d: -f1)
  hi=$(awk -v s="$lo" 'NR > s && $0 ~ /^}/ { print NR; exit }' "$SCRIPT_DIR/up.sh")
  if [ -z "$lo" ] || [ -z "$hi" ]; then
    echo "  FAIL  无法定位 self_test 区间 —— 扫描范围不可信, 本次不判定"
    failures=$((failures + 1))
  else
    needle='up -d .*svc'
    # 判据方向: **除被测函数外**还有没有另一份。
    # 早前写成"范围内有没有" —— 但被测的那一份本来就该在, 于是它自己被判成
    # "第二份"(第 69 行 = start_full_services_serial 本体), 正向恒红。
    # “该存在的被当成多余的” 与 “多余的被漏掉” 是同一个错误的两种方向。
    target_lo=$(grep -n '^start_full_services_serial() {' "$SCRIPT_DIR/up.sh" | head -1 | cut -d: -f1)
    target_hi=$(awk -v s="$target_lo" 'NR > s && $0 ~ /^}/ { print NR; exit }' "$SCRIPT_DIR/up.sh")
    second_impl=$(awk -v lo="$lo" -v hi="$hi" -v tlo="$target_lo" -v thi="$target_hi" -v pat="$needle" '
      NR >= lo && NR <= hi { next }
      NR >= tlo && NR <= thi { next }
      $0 ~ pat && $0 !~ /^[[:space:]]*#/ { c++; if (c == 1) line = NR }
      END { print (c > 0 ? line : "none") }
    ' "$SCRIPT_DIR/up.sh")
    if [ -z "$target_lo" ] || [ -z "$target_hi" ]; then
      echo "  FAIL  无法定位 start_full_services_serial 区间 —— 本次不判定"
      failures=$((failures + 1))
    elif [ "$second_impl" = "none" ]; then
      echo "  PASS  串行实现唯一 (断言区间 ${lo}-${hi} 与被测函数 ${target_lo}-${target_hi} 之外无实现)"
    else
      echo "  FAIL  第 ${second_impl} 行存在第二份逐服务启动实现 —— 守护会被绕过"
      failures=$((failures + 1))
    fi
  fi

  if [ ! -s "$fn_src" ] || ! grep -q '^start_full_services_serial()' "$fn_src"; then
    echo "  FAIL 未能从脚本提取 start_full_services_serial (抽取失败)"
    failures=$((failures + 1))
  else
    # 桩sleep 置0, 否则 6x45s 会让自检跑满 4.5 分钟。
    (
      FULL_SERVICES=(gateway auth system file notification search)
      SCRIPT_DIR=/nonexistent
      ENV_FILE=/dev/null
      COMPOSE_ARGS=()
      commit_pct() { echo "0"; }
      podman-compose() { echo "CALL: $*" >>"$JCODE_CALL_LOG"; }
      # ⚠️ podman 桩必须是 PATH 上的**真实可执行文件**, 不能是 shell 函数:
      #   被测函数写的是 `timeout 120 podman start ...`, 而 timeout 是外部
      #   命令 —— 它 fork 出的子进程**不继承 shell 函数**, 桩根本不生效。
      #   实测症状极具欺骗性: 子 shell rc=0、六个服务全部走完、输出完整,
      #   但调用日志为空 -> 判据报"未发起任何调用"。看起来像被测函数坏了,
      #   实际是桩没接上 —— 第八课补记一的又一例(探针失效被读成被测对象失败)。
      #
      #   注: 被测函数走 podman start 而非 compose, 因为 compose 的 depends_on
      #   会把其余服务一并带起, 串行就失效(实测踩过)。
      mkdir -p "$stub_dir"
      cat >"$stub_dir/podman" <<STUB
#!/usr/bin/env bash
case " \$* " in
  *" inspect "*) echo running ;;
  *" start "*)   echo "CALL: \$*" >>"\$JCODE_CALL_LOG" ;;
esac
exit 0
STUB
      chmod +x "$stub_dir/podman"
      export PATH="$stub_dir:$PATH"
      # ⚠️ 桩要覆盖 podman / timeout / date, 但**不能桩 sleep**。
      #   实测: 桩掉 sleep 后, 等待稳定的重试循环里时间永不推进, 形成死循环
      #   —— 自检直接挂住 5 分钟(不是失败, 是卡死, 比失败更难排查)。
      #   正解: sleep 桩成 **1 秒**(不是空函数), 配合 date 桩每轮跳 1000s,
      #   于是两轮内即判定稳定, 自检约 12s。
      #   ⚠️ 实测: 不桩 sleep 则自检跑 70s(6 服务 x 2 轮 x 10s), CI 上不可接受;
      #     桩成空函数则时间永不推进 -> 死循环(挂住 5 分钟)。1 秒桩是唯一
      #     同时满足两者的取值。
      # ⚠️ 不能把 `timeout` 也定义成同名函数: 被测函数调用的是外部命令,
      #   实测同名shell 函数会遮蔽它, 导致 exit 127(command not found)。
      #   故让 podman 桩自己吞掉超时参数即可, 不需要动 timeout。
      # ⚠️ 假时钟必须存在**文件**里, 不能用 shell 变量:
      #   被测函数写的是 `now=$(date +%s)`, 命令替换在**子 shell** 中执行,
      #   子 shell 里 FAKE_NOW=$((FAKE_NOW+1000)) 的结果不会带回父进程 ——
      #   实测 now 恒为 1000, 稳定判定永远不满足, 自检挂到超时。
      fake_clock="$fn_src.clock"
      export JCODE_FAKE_CLOCK="$fake_clock"
      printf '0' >"$fake_clock"
      date() {
        local v; v=$(cat "$JCODE_FAKE_CLOCK"); v=$(( v + 1000 ))
        printf '%s' "$v" >"$JCODE_FAKE_CLOCK"
        printf '%s\n' "$v"
      }
      export JCODE_CALL_LOG="$call_log"
      # shellcheck disable=SC1090
      . "$fn_src"
      start_full_services_serial 0 1
    ) >/dev/null 2>&1
    rm -f "$fn_src.clock"

    if [ ! -s "$call_log" ]; then
      echo "  FAIL  start_full_services_serial 未发起任何调用"
      failures=$((failures + 1))
    else
      # 判据: **podman start** 恰好 6 次, 每次 1 个容器名。
      # ⚠️ 不能只数CALL: 总数 —— 若有人改成 compose 批量拉起, 总数恰好
      #   仍是 6(一个循环里), 但那是并发, 正是本守护要防的。
      #   故要求每次调用的参数里服务名只有一个。
      serial_calls=$(grep -c '^CALL: .*start ' "$call_log" || true)
      multi_arg=$(grep -c '^CALL: .*start .* .*' "$call_log" || true)
      if [ "$serial_calls" -eq "${#FULL_SERVICES[@]}" ] && [ "$multi_arg" -eq 0 ]; then
        echo "  PASS  实测行为: podman start ${serial_calls} 次, 每次 1 个容器 (无批量)"
      else
        echo "  FAIL  行为异常: podman start ${serial_calls} 次(期望 ${#FULL_SERVICES[@]}), 多参数 ${multi_arg} 次(期望 0)"
        failures=$((failures + 1))
      fi
    fi
  fi
  rm -f "$fn_src" "$call_log" "$fn_src.clock"
  [ -n "${stub_dir:-}" ] && [ -d "${stub_dir:-}" ] && rm -rf "$stub_dir"

  if [ "$failures" -eq 0 ]; then
    echo "自检通过"
    return 0
  fi
  echo "自检失败: $failures 项"
  return 1
}

if [ "$SELF_TEST" -eq 1 ]; then
  ENV_FILE="$PROJECT_ROOT/.env"
  resolve_compose_args
  self_test
  exit $?
fi

if [ "$CHECK_ONLY" -eq 1 ]; then
  echo "=== 配置漂移检测模式 (仅比较, 不执行变更) ==="
fi

# 自动生成 .env（仅当不存在时，避免覆盖用户自定义值）
# compose.yaml 中基础设施凭据与微服务密钥均为 ${VAR:?必填} 强制模式
ENV_FILE="$PROJECT_ROOT/.env"
ensure_env() {
  if [ ! -f "$ENV_FILE" ]; then
    if [ -f "$PROJECT_ROOT/.env.example" ]; then
      echo "=== 未检测到 .env, 从 .env.example 生成本地开发配置 ==="
      cp "$PROJECT_ROOT/.env.example" "$ENV_FILE"
      # 随机生成 JWT 密钥避免开发环境使用已知值
      RANDOM_SECRET=$(head -c 48 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 48)
      sed -i "s|^AUTH_JWT_SECRET=.*|AUTH_JWT_SECRET=${RANDOM_SECRET}|" "$ENV_FILE"
      echo "  ✓ 已生成 $ENV_FILE (AUTH_JWT_SECRET 已随机化)"
    else
      echo "✗ 缺少 .env 且无 .env.example 模板, 请手动创建 .env" >&2
      exit 1
    fi
  else
    echo "=== 使用现有 .env (如需重置请删除后重跑) ==="
  fi
  # shellcheck disable=SC1090
  # JAVA_OPTS 含空格与引号, 直接 source 会触发 "command not found";
  # 用 process substitution 过滤掉, 仅导出其余变量
  set -a; source <(grep -v '^JAVA_OPTS=' "$ENV_FILE"); set +a
}
ensure_env

NACOS_URL="http://localhost:8848"

# 配置同步函数: 上传单个文件到 Nacos (v3 admin API)
# 返回 0=已同步, 1=需要更新
nacos_config_diff() {
  local file="$1"
  local filename
  filename=$(basename "$file")
  local remote
  remote=$(curl -sf "$NACOS_URL/nacos/v3/client/cs/config?dataId=$filename&groupName=DEFAULT_GROUP&namespaceId=public" 2>/dev/null || echo "")
  # v3 API 返回 JSON 包装 {data:{content:...}}, 提取 content 字段
  remote=$(echo "$remote" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('content',''))" 2>/dev/null || echo "$remote")
  local local_content
  local_content=$(cat "$file")
  if [ "$remote" == "$local_content" ]; then
    echo "  ✓ $filename 无漂移"
    return 0
  else
    if [ -z "$remote" ]; then
      echo "  ✗ $filename 不存在于 Nacos, 需要创建"
    else
      echo "  ✗ $filename 存在漂移"
    fi
    return 1
  fi
}

# 配置同步函数: 上传单个文件到 Nacos (v3 admin API, form-encoded)
# 使用 curl 的 --data-urlencode "content@$file" 让 curl 读文件原文, 保留多行/中文/转义
nacos_config_upload() {
  local file="$1"
  local filename
  filename=$(basename "$file")
  local resp
  resp=$(curl -sf -X POST "$NACOS_URL/nacos/v3/admin/cs/config" \
    --data-urlencode "dataId=$filename" \
    --data-urlencode "groupName=DEFAULT_GROUP" \
    --data-urlencode "namespaceId=public" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@${file}" 2>/dev/null) || { echo "  ⚠️  导入 $filename 失败 (curl error)"; return 1; }
  # v3 API 返回 {"code":0,"message":"success","data":true} 表示成功
  if echo "$resp" | grep -q '"code":0'; then
    echo "  ✓ 同步 $filename"
    return 0
  else
    echo "  ⚠️  同步 $filename 失败: $resp"
    return 1
  fi
}

# 若仅同步, 直接推 Nacos 后退出 (不起动基础设施, 不重启容器)
if [ "$SYNC_ONLY" -eq 1 ]; then
  NACOS_URL="http://localhost:8848"
  echo "=== Nacos 配置同步模式 (仅推送, 不启动基础设施) ==="
  # 先确认 Nacos 在跑
  if ! curl -sf "$NACOS_URL/nacos/v3/client/cs/config?dataId=health&groupName=DEFAULT_GROUP&namespaceId=public" > /dev/null 2>&1; then
    echo "✗ Nacos 未就绪 ($NACOS_URL), 请先 ./deploy/up.sh 启动基础设施" >&2
    exit 1
  fi
  CONFIG_DIR="$SCRIPT_DIR/config"
  SYNC_COUNT=0
  for file in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.json; do
    [ -e "$file" ] || continue
    nacos_config_upload "$file" && SYNC_COUNT=$((SYNC_COUNT+1))
  done
  echo "=== 同步完成: $SYNC_COUNT 个配置 ==="
  exit 0
fi

# 若仅检测, 直接比较配置 (跳过环境操作)
if [ "$CHECK_ONLY" -eq 1 ]; then
  NACOS_URL="http://localhost:8848"
  echo "=== Nacos 配置漂移检测 ==="
  CONFIG_DIR="$SCRIPT_DIR/config"
  DRIFT_COUNT=0
  for file in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.json; do
    [ -e "$file" ] || continue
    nacos_config_diff "$file" || DRIFT_COUNT=$((DRIFT_COUNT+1))
  done
  if [ "$DRIFT_COUNT" -eq 0 ]; then
    echo "✓ 所有配置无漂移"
    exit 0
  else
    echo "✗ 存在 $DRIFT_COUNT 个漂移配置, 可运行 ./up.sh --sync 同步"
    exit 2
  fi
fi

# ⚠️ 必须放在**任何 podman 调用之前**（包括下面的 down）。
#   空 healthcheck.log 会让 podman ps / podman-compose 全面报错(rc=125),
#   而 down 内部就要调 podman —— 不先修, 连清理都做不了。
repair_healthcheck_logs

echo "=== 1. 停止并清理旧环境 ==="
podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" down 2>/dev/null || true
echo "  已停止旧环境 (保留数据卷)"

# ⚠️ 两阶段启动的原因 (2026-10-02, CI E2E run 37050526759 的根因):
#   原来只有一次 `podman-compose up -d`, 把基础设施和 6 个微服务一起拉起,
#   而配置同步放在最后 (原步骤 10)。CI 每次 down 后 deploy/nacos/data 是
#   全新空目录, 于是微服务必然先于配置存在而启动, 报
#   "Could not resolve placeholder 'spring.datasource.url'" 并 Exited (1)。
#   本地不复现是因为那个 bind mount 保留了上次运行的配置残留。
#   修法: 基础设施先起 -> 等 Nacos 就绪 -> 同步配置 -> 再起微服务。
echo "=== 2a. 启动基础设施 (Nacos, PostgreSQL, Redis, RabbitMQ, ES, 监控) ==="
if [ "$CHECK_ONLY" -ne 1 ]; then
  # 只起基础设施: 不带 --profile full, 微服务因此不会被连带拉起。
  podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" up -d
fi

# 等待 Nacos 就绪
echo "=== 3. 等待 Nacos 就绪 ==="
NACOS_URL="http://localhost:8848"
for i in $(seq 1 30); do
    # Nacos 3.2+ 移除 v1 HTTP API, 使用 v3 open API 探测
    if curl -sf "$NACOS_URL/nacos/v3/client/cs/config?dataId=health&groupName=DEFAULT_GROUP&namespaceId=public" > /dev/null 2>&1; then
        echo "Nacos 已就绪"
        break
    fi
    echo "等待 Nacos... ($i/30)"
    sleep 2
done

# 同步配置到 Nacos —— 必须在微服务容器启动之前 (见步骤 1 处的说明)。
# 用 v3 admin API, --data-urlencode content@$file 保证原文多行/中文/转义正确。
echo "=== 3b. 同步配置到 Nacos (微服务启动前) ==="
CONFIG_DIR="$SCRIPT_DIR/config"
SYNC_COUNT=0
for file in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.json; do
    [ -e "$file" ] || continue
    nacos_config_upload "$file" && SYNC_COUNT=$((SYNC_COUNT+1))
done
echo "=== 同步完成: $SYNC_COUNT 个配置 ==="

# ⚠️ 上传返回成功 ≠ 微服务能读到（CI run 37070818024, 2026-10-03）。
#
#   那次 6 个微服务容器全部创建成功, gateway/auth/system/file 四个都 healthy,
#   只有 notification 与 search 卡住。notification 的真实报错是:
#     WARN  NacosConfigDataLoader - config[dataId=notification-service.yaml] is empty
#     ERROR Could not resolve placeholder 'spring.datasource.url'
#
#   即它在 **22:15:48** 去拉配置时 Nacos 还是空的, 而配置同步在其后才完成。
#   Spring Cloud Nacos 的 ConfigDataLoader 在启动期只拉一次:
#   读到空就当"该 dataId 不存在"直接继续, **不会重试**。
#   于是它带着一个残缺的环境永久启动失败 —— 反复重启也读不到,
#   因为启动上下文里那份空结果已被固化。
#
#   为什么之前没被发现:
#     · 本地 deploy/nacos/data 有残留配置, 配置早就在 Nacos 里, 读多少次都非空;
#     · CI 每次都是全新 Nacos 目录, 才必然撞上。
#   也就是说"配置同步早于微服务启动"这个顺序要求**还不够** ——
#   顺序对了, 但中间缺了"同步确已落地"的确认。
#
# 这里显式回读每个 dataId, 非空才算通过。宁可多等几秒,
# 也不要让服务带着残缺配置反复重启 —— 那种失败在日志里伪装成
# "业务代码有 bug", 排查成本高一个量级。
echo "=== 3c. 回读验证配置已落地 (非空) ==="
CONFIG_READY=0
MISSING_CFG=()
for attempt in $(seq 1 15); do
  MISSING_CFG=()
  for file in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.json; do
    [ -e "$file" ] || continue
    filename=$(basename "$file")
    content=$(curl -sf "$NACOS_URL/nacos/v3/client/cs/config?dataId=$filename&groupName=DEFAULT_GROUP&namespaceId=public" 2>/dev/null)
    [ -n "$content" ] || MISSING_CFG+=("$filename")
  done
  if [ "${#MISSING_CFG[@]}" -eq 0 ]; then
    CONFIG_READY=1
    break
  fi
  echo "  配置尚未可读 ($attempt/15), 缺: ${MISSING_CFG[*]}"
  sleep 2
done
if [ "$CONFIG_READY" -eq 1 ]; then
  echo "  ✓ 全部配置可读, 微服务可以安全启动"
else
  # 这里必须 exit: 带着空配置启动服务, 失败会以"业务 bug"的面貌出现在
  # 服务日志里, 而不是以"配置没同步好"的面貌出现在这里。
  echo "❌ 配置在 Nacos 中不可读, 放弃启动微服务: ${MISSING_CFG[*]}" >&2
  echo "   否则服务会报 Could not resolve placeholder, 误导为业务缺陷。" >&2
  exit 1
fi

# 配置已在 Nacos 里, 此时才允许拉起微服务。
if [ "$FULL" -eq 1 ] && [ "$CHECK_ONLY" -ne 1 ]; then
  echo "=== 2b. 启动微服务 (配置已就绪) ==="
  mapfile -t UP_ARGS < <(compose_up_args)

  # ⚠️ 业务服务必须**逐个启动且间隔** START_GAP, 不能一次性并发(2026-10-05 实测)。
  #   宿主机 vm.overcommit_memory=2, 全局 commit 预算(MemTotal+SwapTotal=20.63GB)
  #   由所有进程共享。并发启动多个 JVM 时, 每个都要预留虚拟内存, 瞬时冲破预算 ——
  #   而**已经在运行**的服务会因 malloc 失败而崩溃(不是新启的失败)。
  #   即“给更多资源反而更早失败”。
  #
  #   实测对照(同一份compose 配置, 只改启动方式):
  #     逐个启动(间隔 45s) : commit 73–84%->  6/6 存活
  #     并发启动 2 个       : commit 85–93%   ->  已运行的 auth/file 崩溃
  #     稳定期 8 轮采样     : commit 84–90%   ->  全程 6/6 存活
  #   注意第三行: 同一 commit 值(85–90%)在两种场景下一崩一活,
  #   所以静态阈值拦不住它 —— 只能靠降低启动并发。
  #
  #   START_GAP 设为 0 可关闭间隔(退回一次性并发, 仅用于无此问题的环境); 默认 45s。
  START_GAP="${START_GAP:-45}"
  # 连续稳定秒数。实测崩溃发生在启动后 96–141s, 故默认 180s 覆盖该窗口。
  SETTLE_SECS="${SETTLE_SECS:-180}"
  if [ "$START_GAP" -eq 0 ]; then
    echo "  START_GAP=0: 并发启动 (不推荐, 见上方注释)"
    podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" \
      ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"} "${UP_ARGS[@]}"
  else
    # 串行拉起的前提: 六个容器**已存在**。但**不能用 `podman-compose create`** ——
    # 本机 podman-compose 1.0.6 的合法子命令里**没有** create
    # (只有 help/version/wait/systemd/pull/push/build/up/down/ps/run/exec/
    #  start/stop/restart/logs/config/port/pause/unpause/kill/stats/images),
    # 它会报 `invalid choice: 'create'` 并 exit 2。
    #
    # ⚠️ 这里原先写的是 `create "$svc" >/dev/null 2>&1 || true` ——
    #   那个 `|| true` 把上面这个错误**静默吞掉**了, 于是容器压根没被创建,
    #   而脚本继续往下走, 看起来一切正常。2026-10-05 做 Metaspace 受控实验时
    #   才暴露: 连续五档"启动失败"其实全是**容器不存在**,
    #   差点据此得出错误的调参结论 (探针失效被读成被测对象失败)。
    #
    # 正确写法: `up --no-start <svc>` —— 创建容器但不起进程, 不触发 JVM 抢 commit。
    # (若在这里直接 `up -d`, 就回到了并发, 整个串行改动失去意义。)
    #
    # 关键: 创建后必须**断言容器真的存在**, 而不是只看 podman-compose 的退出码。
    # 退出码 0 与"容器已创建"不是一回事 (见下方验证)。
    echo "  先创建业务服务容器 (只创建不启动, 不占 commit)..."
    # 注: 这段在 main 流程里而非函数内, 不能用 local (bash 会报
    #   "local: can only be used in a function" 并中断整个脚本)。
    create_failed=""
    for svc in "${FULL_SERVICES[@]}"; do
      timeout 180 podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" \
        ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"} up --no-start "$svc" >/dev/null 2>&1 || true
      if ! timeout 30 podman inspect "cloud-$svc" >/dev/null 2>&1; then
        create_failed="$create_failed $svc"
      fi
    done
    if [ -n "$create_failed" ]; then
      echo "  ✗ 以下业务服务容器创建失败:$create_failed" >&2
      echo "    (串行拉起依赖容器已存在, 继续下去必然在 podman start 阶段报 'no such container')" >&2
      return 1
    fi

    # 基础设施(含 SkyWalking OAP/UI 等)此时才启动。
    # 不能靠"只点名业务服务"隐式排他 —— 依赖关系仍会把基础设施一起带起。
    if compose_supports_profile; then
      echo "  启动基础设施 (不带 --profile)..."
      podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" \
        "${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"}" up -d
    fi
    # ⚠️ 串行逻辑**只在 start_full_services_serial 这一个函数里**。
    #   早前写成两条并列的 for 循环(profile / 非 profile 各一份), 结果守护
    #   只匹配到其中一份 —— 把另一份改回并发时守护仍然全绿(负向对照失败)。
    #   重复实现让"守护哪一份"变成无法回答的问题, 故此处必须单一来源。
    start_full_services_serial "$START_GAP" "$SETTLE_SECS"
  fi

  # ⚠️ podman-compose 返回 0 **不等于**微服务起来了（2026-10-03, CI run 37064502008）。
  #   那次 up.sh 报 PASS、verify-e2e.sh 也据此判 PASS, 而 6 个微服务容器
  #   全部 absent —— 压根没被创建。compose 1.0.6 在 profile 过滤等情形下
  #   会"安静地什么都不做"却仍然 exit 0。
  #
  #   不加断言的后果: 这是一条**假门禁** —— 上游报成功、下游才发现没起来,
  #   而两者之间的日志已被重定向丢弃, 排障只能靠猜。
  #   故这里必须验证"容器确实存在", 而不能只信退出码。
  #
  # 只判存在不判 healthy: 健康是另一回事, 由后续就绪等待与 E2E 门禁负责。
  # 也不打印 podman-compose 的输出(刷屏且无信息量), 有缺失才报。
  sleep 1
  MISSING_SVCS=()
  for svc in "${FULL_SERVICES[@]}"; do
    podman container exists "cloud-$svc" 2>/dev/null || MISSING_SVCS+=("$svc")
  done
  if [ "${#MISSING_SVCS[@]}" -gt 0 ]; then
    echo "❌ 微服务容器未创建: ${MISSING_SVCS[*]}" >&2
    echo "   compose 已 exit 0 但这些容器不存在 ⇒ 启动参数被静默忽略。" >&2
    echo "   排查: FULL=$FULL, compose_supports_profile=$(compose_supports_profile && echo yes || echo no)" >&2
    echo "   实际 up 参数: ${UP_ARGS[*]}" >&2
    podman-compose -f "$SCRIPT_DIR/compose.yaml" --env-file "$ENV_FILE" \
      ps -a >&2 2>&1 || true
    exit 1
  fi
  echo "  ✓ ${#FULL_SERVICES[@]} 个微服务容器已创建"
fi

# 等待 PostgreSQL 就绪
echo "=== 4. 等待 PostgreSQL 就绪 ==="
for i in $(seq 1 15); do
    if pg_isready -h localhost -p 5432 -U postgres > /dev/null 2>&1; then
        echo "PostgreSQL 已就绪"
        break
    fi
    echo "等待 PostgreSQL... ($i/15)"
    sleep 2
done

# 等待 Redis 就绪
echo "=== 5. 等待 Redis 就绪 ==="
for i in $(seq 1 10); do
    if podman exec cloud-redis redis-cli ping > /dev/null 2>&1; then
        echo "Redis 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "❌ Redis 启动超时"; exit 1; }
    echo "等待 Redis... ($i/10)"
    sleep 2
done

# 等待 RabbitMQ 就绪
echo "=== 6. 等待 RabbitMQ 就绪 ==="
for i in $(seq 1 10); do
    if curl -sf http://localhost:15672 > /dev/null 2>&1; then
        echo "RabbitMQ 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "❌ RabbitMQ 启动超时"; exit 1; }
    echo "等待 RabbitMQ... ($i/10)"
    sleep 2
done

# 等待 Elasticsearch 就绪
echo "=== 7. 等待 Elasticsearch 就绪 ==="
# ⚠️ 预算从 15 次(30s) 提到 90 次(180s)。2026-10-02 实测: 干净环境下 ES 8.18.0
# 冷启动到 / 接受请求需 **48 秒** (mem_limit=1g, ES_JAVA_OPTS=-Xms512m -Xmx512m,
# oom=false)。原先 30s 预算必然超时 —— 这正是 E2E run 37023488729 / 37027922148
# 连续两次红灯的真因, 而失败消息又被 'compose 启动失败' 的措辞掩盖成另一个问题。
# 180s 是首启预算; 之后重启因数据卷已存在会快得多。
for i in $(seq 1 90); do
    if curl -sf http://localhost:9200 > /dev/null 2>&1; then
        echo "Elasticsearch 已就绪 (等待 $((i*2))s)"
        break
    fi
    # ES 首启阶段会反复 OOM 重启, 提前把真实原因打出来, 不要让它静默耗尽预算
    if [ $((i % 15)) -eq 0 ]; then
        ES_STATE=$(podman inspect -f '{{.State.Status}} oom={{.State.OOMKilled}} exit={{.State.ExitCode}}' cloud-elasticsearch 2>/dev/null || echo "容器不存在")
        echo "  等待 Elasticsearch... ($i/90, ${i}s) [$ES_STATE]"
    fi
    [ $i -eq 90 ] && { echo "❌ Elasticsearch 启动超时 (180s) [$ES_STATE]"; podman logs --tail 20 cloud-elasticsearch 2>&1 || true; exit 1; }
    sleep 2
done

# 7b. 调低 ES 磁盘水位阈值
#
# 2026-10-03: CI E2E run 37059177909 里 cloud-search 启动失败, 根因不在业务代码:
#   cloud-elasticsearch 日志:
#     flood stage disk watermark [95%] exceeded ...
#     free: 1.3gb[1.8%], all indices on this node will be marked read-only
#   search-service 侧:
#     DataAccessResourceFailureException: status: 400, [es/indices.exists]
#
# 关键不只在百分比, 更在 ES 的 max_headroom 语义:
# 实际触发线 = max(百分比, 总容量 - max_headroom)。默认
# flood_stage.max_headroom=100GB / high.max_headroom=150GB,
# 而 runner 磁盘总共才十几 GB —— 对 CI 环境而言这套官方默认值
# 相当于"几乎立刻判定磁盘已满"。那是为大规模集群设计的, 用在小磁盘
# 测试环境必然误判。
#
# ⚠️ 为什么用 API 而非环境变量或 elasticsearch.yml（两者都实测无效）:
#   ① 环境变量 `cluster.routing.*` —— 实测容器内确实存在该变量, 但
#      _cluster/settings 读回仍是 95%/100GB, 即 ES 完全不认;
#   ② `elasticsearch.yml` 挂载 —— 文件确实进了容器, 集群也能正常起,
#      但把 flood_stage 设为 0% 时 ES 报错说的是 "high disk watermark
#      [90%]" 而非 yml 里写的 98%, 证明 yml 根本没被读取;
#   ③ `command: elasticsearch -E ...` —— ES 8.18 的 server-cli 会丢弃 -E,
#      容器内 ps 可见 -E 没进入 java 进程;
#   ④ `command: -E ...` —— 直接启动失败:
#      "exec: -E: invalid option"。
#   唯一实测有效的是 PUT _cluster/settings: 返回 acknowledged:true,
#   读回值与写入完全一致。
#   用 persistent 而非 transient —— transient 语义重启即失。
echo "=== 7b. 调整 ES 磁盘水位阈值 (小磁盘环境) ==="
ES_SETTINGS_PAYLOAD='{"persistent":{
  "cluster.routing.allocation.disk.watermark.low":"97%",
  "cluster.routing.allocation.disk.watermark.high":"98%",
  "cluster.routing.allocation.disk.watermark.flood_stage":"99%",
  "cluster.routing.allocation.disk.watermark.low.max_headroom":"1GB",
  "cluster.routing.allocation.disk.watermark.high.max_headroom":"1GB",
  "cluster.routing.allocation.disk.watermark.flood_stage.max_headroom":"1GB",
  "cluster.routing.allocation.disk.watermark.flood_stage.frozen":"99%",
  "cluster.routing.allocation.disk.watermark.flood_stage.frozen.max_headroom":"1GB"
}}'
if ES_RESP=$(curl -sf -X PUT http://localhost:9200/_cluster/settings \
      -H 'Content-Type: application/json' -d "$ES_SETTINGS_PAYLOAD" 2>/dev/null) \
   && echo "$ES_RESP" | grep -q '"acknowledged":true'; then
  echo "  ✓ ES 磁盘水位阈值已下调 (flood_stage=99%, max_headroom=1GB)"
else
  # 不阻断: 这是兜底优化, 设不上会退回原默认值并再次触发 flood stage,
  # 所以必须显式告警 —— 静默失败等于把同一个坑留给下一个人。
  echo "  ⚠️  ES 磁盘水位阈值设置失败: ${ES_RESP:-无响应}" >&2
  echo "      小磁盘环境 (如 CI runner) 下 ES 可能再次进入 flood stage," >&2
  echo "      表现为 search-service 报 indices.exists status 400。" >&2
fi

# 等待 Prometheus 就绪
echo "=== 8. 等待 Prometheus 就绪 ==="
for i in $(seq 1 10); do
    if curl -sf http://localhost:9090/-/healthy > /dev/null 2>&1; then
        echo "Prometheus 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "⚠️ Prometheus 启动超时，请手动检查监控服务"; }
    echo "等待 Prometheus... ($i/10)"
    sleep 2
done

# 等待 Sentinel Dashboard 就绪
echo "=== 9. 等待 Sentinel Dashboard 就绪 ==="
for i in $(seq 1 10); do
    if curl -sf http://localhost:8858 > /dev/null 2>&1; then
        echo "Sentinel Dashboard 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "⚠️ Sentinel Dashboard 启动超时，请手动检查"; }
    echo "等待 Sentinel Dashboard... ($i/10)"
    sleep 2
done

echo ""
echo "=== 本地开发环境已就绪 ==="
echo "  Nacos:      控制台 http://localhost:8080 (API: $NACOS_URL)"
echo "  PostgreSQL: localhost:5432/cloud_infra"
echo "  Redis:      localhost:6379"
echo "  RabbitMQ:   localhost:15672 (管理界面)"
echo "  ES:         http://localhost:9200"
echo "  Prometheus: http://localhost:9090"
echo "  Alertmanager: http://localhost:9093"
echo "  Grafana:    http://localhost:3000 (admin/admin)"
echo "  Loki:       http://localhost:3100"
echo "  Jaeger:     http://localhost:16686"
echo "  Sentinel:   http://localhost:8858 (sentinel/sentinel)"
echo ""
echo "下一步: 在 IDE 中启动各微服务，或运行 mvn spring-boot:run"
