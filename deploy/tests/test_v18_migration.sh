#!/usr/bin/env bash
# 验证 V18 迁移脚本的正确性 —— 在临时库执行, 绝不触碰 cloud_infra。
#
# 三个用例:
#   1. 正向_当前实数据形态 (status='0')
#   2. 正向_混合形态     ('0' 与 'UNREAD' 并存, 即 DEFAULT 遗留可能形态)
#   3. 负向_未识别值     ('GARBAGE') —— 迁移必须报错, 不能静默变成 0
#
# 退出码 0 = 全部符合预期; 非 0 = 有用例不符合。
#
# ── 本脚本自身踩过的两个坑(都属"探针坏掉却长成守护的样子") ────────────
#   1. sys_notification 有外键 -> sys_user。不先建依赖, 建表失败;
#      建表失败被 >/dev/null 吞掉 -> 表没建出来 -> 迁移报"表不存在"。
#      我第一版就是这样, 把"装置缺陷"读成了"迁移有缺陷"。
#   2. 因此每个装置步骤都必须回读确认, 且失败要说"本用例结论无效",
#      而不是让后续失败被误归因给迁移脚本。
set -uo pipefail

# ── 全局看门狗（2026-10-08 新增）──────────────────────────────────────────
# ⚠️ 起因: 本脚本于 2026-10-07 19:32:50 启动后, 到 2026-10-08 12:35 仍未退出,
#    累计 CPU 时间 24.9 小时 ≈ 墙钟 24.9 小时(100% 占用, State 始终 R),
#    且已跑完用例 1(日志里有 "PASS: 列类型=smallint")却卡在用例 2 开头。
#    单次 podman exec 已有 timeout 90, 理论上上限约 36 分钟 —— 实际远超。
#
#    机制**尚未确定**(已排除: 纯 bash 的 trap+timeout 会自旋 —— 对照实验正常;
#    无 while/递归; 无活动 postgres 会话; 测试库已不存在)。
#    但无论机制为何, "一个验证脚本可以无限期占用一个 CPU 核"本身就是缺陷:
#    它会让宿主的 %wa/load 长期畸高, 使**其他**测试结论不可信
#    (AGENTS.md 第八课: 失败时先看宿主三本账)。
#
#    因此本看门狗的作用不是"修好它", 而是**保证它一定会结束**,
#    并在超时时把状态说清楚 —— 而不是静默挂死。
#
# ⚠️ 注意 watchdog 的两个坑(AGENTS.md 第九课):
#    1. 必须用**独立进程组**启动, 否则 kill 自己所在的进程组会连带杀掉看门狗;
#    2. 超时后必须**显式报出是哪一步超时**, 否则又是一次"看起来正常的失败"。
WATCHDOG_SECS="${V18_WATCHDOG_SECS:-1800}"

# 标记文件: 记录当前正在执行的用例, 供看门狗读取
#
# ⚠️ mktemp 必须放在 `--watchdog-child` 判断**之后**。
#    看门狗子进程会重新执行本脚本; 若在这里就 mktemp, 它会先建出一个
#    无人清理的新文件, 直到子进程入口改用父进程传入的路径才被弃用
#    ⇒ 每跑一次看门狗就在临时目录留一个孤儿文件。
#    这类"创建了但没人用"的对象属于第九课说的「静默泄漏」——
#    它不影响任何断言, 所以靠功能测试发现不了, 必须有专门守护
#    (test_watchdog_child_does_not_leak_progress_file)。
PROGRESS_FILE=""
progress() { printf '%s\n' "$1" > "$PROGRESS_FILE" 2>/dev/null || true; }
new_progress_file() { PROGRESS_FILE="$(mktemp -t v18-progress.XXXXXX)"; }

# 看门狗: 超时后先 dump 进度, 再杀整个进程组。
#
# ⚠️ 必须由 `start_watchdog` 用 `setsid` 起在新会话里 —— 详见那里的说明:
#    看门狗若留在目标组内, `kill -TERM -- -PGID` 会**先杀死它自己**,
#    于是"报已终止"与"真的已终止"分离, 真进程永久残留。
#
# ⚠️ 不能用 `timeout` 直接包整个脚本: 那会让 timeout 成为父进程,
#    而 trap EXIT 的清理会在 timeout 已杀子进程后才跑, 顺序不可控。
watchdog() {
  # ⚠️⚠️ 2026-10-08 23:00 实测: 这里原本是**纯定时器** ——
  #    `sleep "$WATCHDOG_SECS"` 睡满即无条件清理, 期间【从不检查目标是否已死】。
  #    而 WATCHDOG_SECS 默认 1800 秒, 于是主进程早被 SIGKILL 之后,
  #    看门狗仍要空睡满 30 分钟才删那个进度文件。
  #
  #    实测后果: 一次受控对照(KILL 主进程后等 200 秒)留下 1 个泄漏文件,
  #    而 pgrep 显示看门狗还在睡(etimes=312s)。文件不是"没删",
  #    是"要等半小时才删"—— 期间每次调用都再叠一个。
  #    这与第八课补记一「谁创建谁删除」同源: 清理者的生命周期
  #    必须短于它所守护对象的存活期待, 否则等于没有清理。
  #
  #    正解: 分片等待, 每片结束检查目标组是否还在。
  #    组内已无进程(只有僵尸) ⇒ 目标已自行结束 ⇒ 立刻删文件退出,
  #    不再空等剩余时间。组仍在 ⇒ 继续等下一片。
  local waited_total=0
  local tick=5
  while [ "$waited_total" -lt "$WATCHDOG_SECS" ]; do
    sleep "$tick"
    waited_total=$((waited_total + tick))
    # ⚠️ 必须按**退出码**三态判定, 不能写成
    #    `[ "$(group_has_live_process ...)" -eq 0 ]` ——
    #    那是命令替换, 只取 stdout 而丢掉退出码, 三态被压成一个字符串。
    #    碰巧 `-eq 0` 仍能工作, 但依据是"失效时 stdout 恰为 2"这一巧合,
    #    一旦探针改用别的失败输出就静默误判(第五课: 靠巧合的判据最脆)。
    #    这里显式 case: 0=已空 ⇒ 目标已结束; 1=仍有活进程 ⇒ 继续等;
    #    2=判据失效 ⇒ **不能**当成"已结束", 否则会删掉仍在运行的用例的文件。
    group_has_live_process "$WATCHDOG_PGID"
    case $? in
      0)  # 组内已无活进程(僵尸不计) ⇒ 目标自行结束, 不必空等剩余时限
        # ⚠️ 这里**不自己 rm**, 直接返回: 进度文件的删除由看门狗入口末尾
        #    的兜底统一负责(单一清理点, 避免两条路径各删一次)。
        return 0
        ;;
      2)  # 判据失效: 不作判定, 继续等(下一片会再问一次)
        ;;
      *)  # 1 = 仍有活进程, 正常继续
        ;;
    esac
  done
  # ── 第一件事: 终止, 而不是诊断 ───────────────────────────────────────────
  # ⚠️⚠️ 2026-10-08 实测, 本项目最讽刺的一个坑: **诊断步骤会把看门狗自己挂死**。
  #
  #    我最初把"残留会话"查询(podman exec psql)放在 kill **之前**。
  #    而本机单次 `podman exec` 实测需 9~17 秒, 宿主负载高时可远超;
  #    于是看门狗在超时后先去做诊断, 卡在 podman 上 —— **永远走不到 kill**。
  #
  #    实测现象: 输出停在 `!!! 残留会话(用于定位):` 之后没有任何一行,
  #    35 秒后目标组内 `bash wd_hung.sh` + `sleep 300` 仍存活。
  #    即"看门狗已触发"是真的, "看门狗会终止"是假的。
  #
  #    ⇒ 正解: **先杀, 后诊断**; 且诊断自带独立时限, 失败只影响输出不影响终止。
  echo "" >&2
  echo "!!! 看门狗触发: 已运行超过 ${WATCHDOG_SECS}s, 强制终止" >&2
  echo "!!! 停止在: $(cat "$PROGRESS_FILE" 2>/dev/null || echo '(进度未知)')" >&2
  echo "!!! 这不是用例失败 —— 是装置挂死, 结论无效(AGENTS.md 第八课)" >&2

  # ── 终止: TERM -> 观察窗口 -> 必要时 KILL ───────────────────────────────
  # ⚠️ 只发 SIGTERM **不够**: 被测脚本有 `trap cleanup EXIT`, 而 cleanup 里
  #    会跑 drop_db —— 一次最长 90 秒的 podman exec。于是 SIGTERM 触发 cleanup,
  #    cleanup 又进入长等待, 而看门狗已退出。
  #    实测: 看门狗报"已触发", 但 20 秒后组内仍有 bash + sleep 300。
  # ⇒ TERM 后留观察窗口, 确认真的退了再退; 超时仍未死才升级 KILL。
  #
  # ⚠️ 也不能用 `kill -0` 判存活: setsid fork 出的中间进程无人 wait,
  #    `Zs` 僵尸永远挂在组里, 而 `kill -0` 对僵尸**返回成功**
  #    (已被 kill, 只是未回收) ⇒ "组内已无进程"恒为假, 于是无条件升级 KILL,
  #    而 KILL 对僵尸无效 ⇒ 只能报一条恒红的"SIGKILL 后仍有进程存活"。
  kill -TERM -- "-$WATCHDOG_PGID" 2>/dev/null || true
  local waited=0
  local grace="${WATCHDOG_KILL_GRACE:-10}"
  while [ "$waited" -lt "$grace" ]; do
    group_has_live_process "$WATCHDOG_PGID"
    case $? in
      0) break ;;                 # 已清理干净
      2) break ;;                 # 判据失效: 不再空转, 但如实报告
    esac
    sleep 1
    waited=$((waited + 1))
  done
  group_has_live_process "$WATCHDOG_PGID"
  case $? in
    0)
      echo "!!! 已清理干净 (PGID=$WATCHDOG_PGID)" >&2
      ;;
    2)
      echo "!!! 判据失效, 无法确认是否已清理 (PGID=$WATCHDOG_PGID) —— 请人工检查" >&2
      ;;
    *)
      echo "!!! SIGTERM 后 ${grace}s 仍在运行, 升级到 SIGKILL" >&2
      kill -KILL -- "-$WATCHDOG_PGID" 2>/dev/null || true
      sleep 2
      group_has_live_process "$WATCHDOG_PGID"
      case $? in
        0)
          echo "!!! 已清理干净 (PGID=$WATCHDOG_PGID)" >&2
          ;;
        2)
          echo "!!! 判据失效, 无法确认 KILL 后状态 (PGID=$WATCHDOG_PGID)" >&2
          ;;
        *)
          echo "!!! SIGKILL 后仍有**活着的**进程 —— 请人工检查 PGID=$WATCHDOG_PGID" >&2
          ps -eo pid=,ppid=,pgid=,stat=,args= 2>/dev/null \
            | awk -v g="$WATCHDOG_PGID" '$3 == g' | sed 's/^/!!!   /' >&2
          ;;
      esac
      ;;
  esac

  # ── 最后才做诊断, 且自带独立时限 ────────────────────────────────────────
  # 诊断是**尽力而为**的额外信息, 绝不能影响终止这件事本身。
  if command -v podman >/dev/null 2>&1; then
    echo "!!! 残留会话(尽力而为, 超时即跳过):" >&2
    timeout "${WATCHDOG_DIAG_TIMEOUT:-20}" podman exec cloud-postgres \
      psql -U postgres -d postgres -q -tA -c \
      "SELECT pid, state, wait_event_type, now()-query_start, left(query,60)
         FROM pg_stat_activity WHERE state<>'idle';" 2>&1 \
      | head -8 | sed 's/^/!!!   /' >&2
  fi

  # ⚠️ 进度文件的删除责任落在**这里**, 不能只放在调用点之后。
  #    调用点 (`--watchdog-child` 分支) 的 `rm` 紧跟在 `watchdog` 之后 ——
  #    而 `watchdog()` 以 `exit 124` 结束, **该行永远不可达**。
  #    这正是第八课补记一第 6 条「函数不该假定调用前的初始状态」的变体:
  #    一个"看起来做了清理"的调用点, 实际一次都没执行过。
  #
  #    为什么必须由看门狗兜底: 看门狗是**最后一个**退出的进程。主流程被
  #    SIGKILL 时(不可捕获), `trap cleanup EXIT` 根本没机会跑。
  #    `test_watchdog_is_outside_target_process_group` 就在复现这个场景。
  [ -n "${PROGRESS_FILE:-}" ] && rm -f "$PROGRESS_FILE" 2>/dev/null
  exit 124
}

# 判据: 进程组 pgid 内是否还有**非僵尸**进程。
#
# ⚠️ 返回值约定必须区分三态 —— 读不到就是"无法判定", 不能当"没有"(第八课补记一):
#   0 = 组内无活进程(已清理干净)
#   1 = 组内仍有活进程
#   2 = 判据失效(ps 不可用/输出无法解析), 调用方必须区别于 0/1 对待
# 判据: 进程组 pgid 内是否还有**非僵尸**进程。
#
# ⚠️ 返回值是**三态**, 调用方必须区分 —— 读不到就是"无法判定", 不能当"没有":
#     0 = 组内无活进程(已清理干净)
#     1 = 组内仍有活进程
#     2 = 判据失效(命令不可用/输出无法解析)
#
# ⚠️⚠️ **必须用 `ps -eo`, 不能用 `ps -g`**(2026-10-08 实测, 本项目最隐蔽的一个坑)。
#
#   `ps -g` 的语义是"**前台**作业组/会话组", 不是"PGID 相同的全部进程"。
#   实测对照(一个组: 组长 bash + 其子 sleep 300, 组长是 setsid 起的):
#       ps -o pid=,stat= -g 2961237   -> 输出空, rc=1     ← 明明组内有 2 个活进程
#       ps -eo pgid=,stat= | grep 2961237
#                                    -> ['2961237 S', '2961237 S']  ← 真实情况
#
#   于是我第一版(以 `ps -g` 为主判据)在这个场景下得到**"组内已清理干净"**,
#   看门狗随即报告"已清理干净"并退出, 而 bash 与 sleep 300 **仍在运行**。
#
#   ⇒ 这属于 AGENTS.md 第五课: "探不到"有两个解释(对象不存在 / 存在但不可见),
#     而 `ps -g` 的空输出**同时支持两个互斥结论**。
#   ⇒ 正解: 全量列举(`ps -eo`)后按 PGID 过滤, 状态以 Z 开头视为僵尸。
#     额外用 /proc 兜底: /proc/<pgid> 目录存在即表示组长尚未被回收,
#     但它**不能**证明有活进程 —— 所以只用来区分"组不存在"与"组存在但无活进程"。
group_has_live_process() {
  local pgid="$1" out
  case "$pgid" in
    ''|*[!0-9]*)
      echo "!!! 判据失效: PGID 非法 [$pgid]" >&2
      return 2 ;;
  esac
  if ! command -v ps >/dev/null 2>&1; then
    echo "!!! 判据失效: ps 不可用, 无法判定 PGID=$pgid 是否有活进程" >&2
    return 2
  fi
  # 全量列举后按 PGID 过滤; stat 以 Z 开头(Z/Z+/Zs)视为僵尸(已死, 等回收)
  out="$(ps -eo pgid=,stat= 2>/dev/null)"
  if [ -z "$(printf '%s' "$out" | tr -d '[:space:]')" ]; then
    # ps 全量输出为空: 无法解析 → 判据失效, **不能**当成"没有活进程"
    echo "!!! 判据失效: ps -eo 输出为空, 无法判定 PGID=$pgid" >&2
    return 2
  fi
  if printf '%s\n' "$out" | awk -v g="$pgid" '$1 == g && $2 !~ /^Z/ { found = 1 } END { exit !found }'; then
    return 1                       # 组内仍有活进程
  fi
  # 没有活进程。再区分两种"没有":
  #   - 组内只剩僵尸 / 组已不存在 → 已清理干净(判 0)
  #   - ps 压根没列出这个 PGID 且 /proc/<pgid> 不存在 → 组不存在(判 0)
  #   两者对"是否已清理"是同一结论, 故不需要更细的分支。
  return 0
}

# ⚠️ 看门狗必须**真的被启动**, 否则上面那段只是一段没跑过的代码
#   (第三课: 机制存在 ≠ 机制在运行)。这一段的守护是 test_v18_watchdog.py。
#
# 用法: 若本脚本是**被 setsid 起了独立进程组**运行的, 则:
#   - WATCHDOG_PGID = 本进程所在的 PGID
#   - 看门狗自己也要在该组外, 否则 kill -- -PGID 会先杀掉它自己
# 若不是独立进程组, 则退化为「只报告不杀」——
#   宁可杀不掉也不能自杀(第九课: 探针自己坏掉时比没有更坏)。
start_watchdog() {
  # 记录进度, 供看门狗读
  progress "启动阶段"

  # ⚠️ 判据必须是「**PGID == PID**」(自己是进程组组长), 不是「PGID == SID」。
  #
  #    实测(2026-10-08):
  #      setsid bash -c ...  ->  pid=2715032 pgid=2715032 sid=2715032   ← 可杀
  #      普通 bash -c ...   ->  pid=2715039 pgid=2282549 sid=2282549   ← 不可杀
    #    我第一版写的是 `PGID == SID`, 而**普通 bash 也满足**(两者都是 2282549),
    #    于是判据恒真, 真的会去 `kill -- -2282549` ——
    #    那会把**当前用户的整个 shell 会话**一起杀掉(含我自己)。
  #    ⇒ 与 AGENTS.md 第五课补记一「判据要看真实值, 不能按直觉」同型。
  local my_pgid my_pid
  my_pgid="$(ps -o pgid= -p $$ 2>/dev/null | tr -d ' ')"
  my_pid="$$"
  if [ -z "$my_pgid" ] || [ "$my_pgid" != "$my_pid" ]; then
    # 不是进程组组长 —— kill -- -PGID 会连坐外部会话, 只能报告不能杀
    echo "  NOTE: 非独立进程组 (pid=$my_pid pgid=$my_pgid), 看门狗降级为「只报告」"
    ( watchdog_report_only ) &
    disown 2>/dev/null || true
    return 0
  fi
  WATCHDOG_PGID="$my_pgid"
  # ⚠️ 2026-10-08 实测坑: 看门狗**绝不能**留在目标进程组里。
  #
  #    第一版写的是 `( watchdog ) &` —— 而 job control 的子 shell **继承
  #    父进程的 PGID**, 即看门狗自己就在被它 kill 的那一组里。
  #    ⇒ `kill -TERM -- -$PGID` 执行时, **看门狗自己先被杀**,
  #    10 秒宽限窗口与随后的 SIGKILL 升级**根本没机会执行**;
  #    而 `sleep 300` 之类的真进程无人清理, **永久残留**。
  #
  #    这个缺陷极隐蔽: 头几轮对照里组内只剩 `sleep 300`(立即响应 TERM),
  #    看门狗被杀后它也被清了, 于是看起来一切正常; 直到看门狗自己拉起的
  #    `podman exec` 诊断子进程加入, 清理变慢, 假绿才暴露。
  #
  #    ⇒ 正解: 用 `setsid` 把看门狗送进**它自己的新会话**, 使其 PGID
  #    不再等于目标 PGID。执行后**回读验证** ——
  #    "以为它脱离了"不算, "以为启动了"也不算(第九课)。
  #
  #    只套**一层** setsid: 再多一层会让 $! 拿到的是中间短命进程的 pid,
  #    下面的回读就会读到错误的 pid(实测过的那类假失败)。
  #
  #    ⚠️ 不能写成 `( ... & )`: 子 shell 里 `$!` **未定义**(本脚本 `set -u`),
  #    会直接报 `$!: unbound variable` 而中断启动流程 —— 而那个错误
  #    发生在"启动看门狗"这一步, 看门狗却**仍然在跑**,
  #    于是输出里同时有"启动失败"与"看门狗触发", 像是两个矛盾的事实。
  # ⚠️ 必须把 **PROGRESS_FILE 路径**传给子进程。
  #    看门狗是**另一次脚本执行**(`setsid bash "$0" --watchdog-child`),
  #    它会重新执行到 `mktemp -t v18-progress.XXXXXX` 而得到一个**新文件**。
  #    若不显式传入, 子进程读的是那个从未被写过的空文件 ——
  #    于是"停止在:" 永远为空, 而真实进度明明写着"用例1: setup+seed"。
  #    ⇒ 输出看起来正常(有停止在: 这一行), 内容却是错的(第五课)。
  setsid bash "$0" --watchdog-child "$WATCHDOG_PGID" "$PROGRESS_FILE" >&2 &
  WATCHDOG_PID=$!
  disown 2>/dev/null || true
  # 回读: 看门狗是否真的不在目标组内。读不到 = 无法判定, 不能默认通过。
  sleep 0.3
  local wd_pgid=""
  wd_pgid="$(ps -o pgid= -p "$WATCHDOG_PID" 2>/dev/null | tr -d ' ')"
  if [ -z "$wd_pgid" ]; then
    echo "  WARN: 无法回读看门狗 PGID(pid=$WATCHDOG_PID), 无法确认它已脱离目标组"
  elif [ "$wd_pgid" = "$WATCHDOG_PGID" ]; then
    echo "  FAIL: 看门狗仍在目标进程组内 (pgid=$wd_pgid) —— 它会杀死自己"
    echo "        此时超时会**报已终止但实际残留**, 属第三类假门禁。"
    return 1
  else
    echo "  看门狗已启动 (pid=$WATCHDOG_PID, 上限 ${WATCHDOG_SECS}s, 目标 PGID=$WATCHDOG_PGID, 看门狗自身 PGID=$wd_pgid)"
  fi
  return 0
}

# 看门狗子进程入口: 由 `setsid bash "$0" --watchdog-child <PGID>` 调用。
#
# 这里**不**再自己 setsid —— 外层已经建立了新会话; 再套一层会让日志里的
# pid 与 $! 对不上, 使父进程的"回读验证"拿到错误的 pid。
if [ "${1:-}" = "--watchdog-child" ]; then
  WATCHDOG_PGID="${2:?看门狗子进程缺少目标 PGID}"
  # 父进程传入的进度文件 —— 不能用本进程 mktemp 出来的(见调用处说明)
  if [ -n "${3:-}" ]; then
    PROGRESS_FILE="$3"
  else
    new_progress_file   # 兜底: 没传入时自己建一个
  fi
  trap '' TERM        # 绝不能因为目标组的 TERM 广播而提前死掉
  watchdog
  # ⚠️ 看门狗是**最后一个**退出的进程(主流程被 TERM/KILL 后,
  #    cleanup 可能根本没跑到 rm) ⇒ 进度文件的删除必须由它兜底。
  #    实测: `test_watchdog_is_outside_target_process_group` 就在看门狗
  #    刚启动时把主进程 SIGKILL 掉 —— SIGKILL 不可捕获, 主进程没机会清理,
  #    而当时探针路径还没生效 ⇒ 每次该用例泄露 1 个文件。
  [ -n "$PROGRESS_FILE" ] && rm -f "$PROGRESS_FILE" 2>/dev/null || true
  exit 124
fi

# ── 探针入口: 只加载函数定义, 不跑主流程 ────────────────────────────────
# ⚠️ 位置要求: 必须在**所有函数定义之后**才 return, 否则探针请求的函数
#    尚未定义就返回了, 而 bash 不报错 —— 探针于是拿到"成功"却什么都没验
#    (第五课: 探针失效必须独立红灯, 不能伪装成 PASS)。
#    故探针**先只置位**, 真正的 return 放在全部定义之后(见下方 PROBE 段)。
PROBE=""
case "${1:-}" in
  --probe-only)    PROBE="probe" ;;
  --cleanup-probe) PROBE="cleanup" ;;
esac

# 只报告版本: 不杀任何进程, 仅在 stdout 标记
watchdog_report_only() {
  sleep "$WATCHDOG_SECS"
  {
    echo ""
    echo "!!! 看门狗(${WATCHDOG_SECS}s)到期, 但本进程不在独立进程组内, 不执行终止"
    echo "!!! 停止在: $(cat "$PROGRESS_FILE" 2>/dev/null || echo '(进度未知)')"
    echo "!!! 请手动检查: ps -p \$\$"
  } >&2
}

# ⚠️ ROOT 必须用**绝对路径**传入或推导, 否则本脚本被复制到别处
#    (如 /tmp 做对照实验) 时 ROOT 会变成空串, 后续报
#    "FAIL: 缺少 //cloud-infra-modules/..." —— 看着像迁移文件缺失, 实际是路径错。
#    与 AGENTS.md 第九课「探针给出的红不是被测对象有缺陷」同型。
ROOT="${V18_REPO_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}"
[ -n "$ROOT" ] || { echo "FAIL: 无法确定仓库根目录 (V18_REPO_ROOT 未设且推导失败)"; exit 2; }
# 主流程到此才真正开始 —— 此时才创建进度文件。
# ⚠️ 绝不能提前到文件头: 看门狗子进程会重新执行本脚本, 提前创建就会
#    留下一个无人清理的孤儿文件(见文件头 new_progress_file 的说明)。
new_progress_file
MIGDIR="$ROOT/cloud-infra-modules/system-module/src/main/resources/db/migration"
MIG="$MIGDIR/V18__fix_notification_status_column_type.sql"
V13="$MIGDIR/V13__add_notification_main_table.sql"
DB="${V18_TEST_DB:-v18_migration_test}"
# 探针必须用自己的库名: 它会在不走主流程的路径上执行 drop_db。
# ⚠️ 此赋值必须放在默认值**之后**。此前它写在 PROBE 段(默认值之前),
#    随后被这里的默认值覆盖 ⇒ 死赋值, 探针实际操作的是主测试库。
#    容忍并行运行时, 用 V18_TEST_DB 给每轮一个唯一库名。
[ -n "$PROBE" ] && DB="v18_cleanup_probe"
FAIL=0

# ── podman exec 的真实开销决定了本脚本的设计 ──────────────────────────
# 实测单次往返 9~17 秒(宿主机负载高时)。这不是推测, 是连续三次测量的结果。
# 两个由此产生的硬约束:
#   1. 往返次数要尽量少 —— 每多一次往返, 整轮时间成倍增长。
#   2. timeout 不能给太小。第一版给 30s, 在高负载下会静默超时,
#      而超时返回空串 + rc=124, 被下游读成"DROP 失败"——
#      即"探针超时"被读成"被测对象失败"(第五课补记一)。
#
# DROP DATABASE 另有 PostgreSQL 硬规则: 不能在事务块内执行,
# 因此不能写成 "SELECT ...; DROP ...; SELECT ...;" 这种多语句形式
# (实测报 ERROR:  DROP DATABASE cannot run inside a transaction block)。
# 必须一次 exec 一条语句, 并用前后两次单查确认结果。

# 单次 exec, 一条语句; 输出原始 stderr 以便区分"成功"与"超时"
x() { timeout "${X_TIMEOUT:-90}" podman exec cloud-postgres psql -U postgres -d postgres -q -tA -c "$1" 2>&1; }

db_count() { x "SELECT count(*) FROM pg_database WHERE datname='$DB';"; }

drop_db() {
  local out
  # ⚠️ 时限必须在**入口**就生效, 且用局部变量。
  #    曾经写成"先 db_count 再 X_TIMEOUT=..." —— 于是 cleanup 的**第一次**
  #    查询仍走默认 90 秒, 短时限形同虚设。而 cleanup 恰恰是看门狗
  #    触发后最需要快速退出的那条路径(每次 pg 往返都 9~17 秒)。
  # ⇒ 这里设局部 X_TIMEOUT, 全函数(含首次 db_count)一致生效,
  #   且不污染调用方(测试主体阶段应继续用默认 90 秒)。
  local X_TIMEOUT="${1:-${X_TIMEOUT:-90}}"
  [ "$(db_count)" = "0" ] && return 0
  out=$(x "DROP DATABASE IF EXISTS $DB WITH (FORCE);")
  # NOTICE/DROP 是成功输出; 真实错误不含这两行
  if echo "$out" | grep -qE '^(ERROR|FATAL)'; then
    echo "  ERROR 装置缺陷: drop_db 报错: $(echo "$out" | head -2 | tr '\n' ' ')"
    FAIL=1; return 1
  fi
  out=$(db_count)
  if [ "$out" != "0" ]; then
    echo "  ERROR 装置缺陷: drop_db 后回读 count=[$out] (期望 0)"
    FAIL=1; return 1
  fi
  return 0
}

# 在目标库执行 DDL/DML; -tA 单值输出便于比较, 保留 stderr
psql_do()  { timeout 90 podman exec -i cloud-postgres psql -U postgres -d "$1" -v ON_ERROR_STOP=1 -q -tA 2>&1; }
q()        { timeout 90 podman exec cloud-postgres psql -U postgres -d "$1" -tA -c "$2" 2>&1; }

# 装置失败即判 FAIL 并让调用方跳过该用例 —— 绝不产出"结论"却其实没测到
device_error() { echo "  ERROR 装置缺陷: $1"; echo "        本用例结论无效(不等于迁移失败)"; FAIL=1; }

cleanup() {
  progress "清理: drop_db"
  # ⚠️ 删除进度文件必须在 drop_db **之前**, 不能放在它后面。
  #    实测: 装置在 psql_do 处挂死后被 TERM, cleanup 进入 drop_db
  #    (已被替换成 sleep 300), 于是后面的 rm 永远到不了 ——
  #    每次挂死都在临时目录留下一个孤儿文件。
  #    即"清理函数"里最该保证的那件事(不留垃圾)被放在了最不可靠的位置。
  rm -f "$PROGRESS_FILE" 2>/dev/null || true
  # ⚠️ 超时被看门狗杀掉时, cleanup 里的 drop_db 是**最长 90 秒**的
  #    podman exec —— 这会让"已终止"迟迟不成立(见 watchdog 里的说明)。
  #    因此清理本身加一个短上限: 清理失败不影响"已终止"这个事实。
  drop_db "${DROP_DB_TIMEOUT:-15}" >/dev/null 2>&1 || true
  #    ⚠️ 不能写成 `timeout 15 drop_db` —— 见 x() 的说明:
  #    外部 `timeout` 执行不了 shell 函数(rc=127), 而 `|| true` 会把它
  #    静默吞掉, 于是**清理被跳过**而日志无任何异常(第五课)。
  #    正解: 把时限作为**参数**传给 drop_db, 由它自己限制内部往返。
}

# ── 探针真正返回: 全部函数已定义 ─────────────────────────────────────────
# --probe-only: 让守护测试直接请求 group_has_live_process 的判定
#   (判据有两份实现 —— 本文件的 bash 版与测试里的 Python 版 —— 端到端
#    用例只能观察到 Python 探针看到的东西, 实测删掉 bash 侧僵尸过滤
#    端到端仍全绿 ⇒ 必须有一条直接打在本实现上的守护)
# --cleanup-probe: 执行**一次**带时限的 drop_db, 用于运行时验证时限生效
if [ -n "$PROBE" ]; then
  if [ "$PROBE" = "cleanup" ]; then
    # ⚠️ 用 subshell 避免 drop_db 的局部变量与后续逻辑互相影响
    ( drop_db "${DROP_DB_TIMEOUT:-15}" >/dev/null 2>&1 || true )
  fi
  # ⚠️ 探针路径必须自己删进度文件: 它不走主流程, 也就不会装上
  #    `trap cleanup EXIT` —— 而 `new_progress_file` 已经建了文件。
  #    实测: 3 个走探针路径的用例会各泄露 1 个(逐用例比对定位)。
  [ -n "$PROGRESS_FILE" ] && rm -f "$PROGRESS_FILE" 2>/dev/null || true
  return 0 2>/dev/null || exit 0
fi

trap cleanup EXIT

echo "=== 前置 ==="
start_watchdog
for f in "$MIG" "$V13"; do
  [ -f "$f" ] || { echo "FAIL: 缺少 $f"; exit 2; }
done
echo "  V13/V18 均就位"

# 建库后一次回读确认类型查询能取到值 ——
# 第一版此处没有校验, 于是出现了"值查到了但列类型为空"的自相矛盾输出,
# 根因是 podman exec 超时返回空串(探针失效), 而非迁移没改类型。
# 装置自检必须覆盖查询本身可用, 否则区分不了"没改"与"没查到"。
selftest() {
  local v
  v=$(x "SELECT 1;")
  [ "$v" = "1" ] || { echo "  ERROR 装置缺陷: 连通性自检失败 [${v}]"; FAIL=1; return 1; }
  return 0
}

# 从被测文件自身提取建表语句 —— 不手写表结构, 避免把"迁移写的"与"测试造的"混为一谈
# ── setup 的每一步失败都必须带原因 ──────────────────────────────────────
# ⚠️ 本脚本第一版的 setup 用的是 `psql_do ... >/dev/null || return 1` ——
#    静默 return。用例 2/3 因此只打印标题、不打印任何结论,
#    而我第一反应是"控制流有 bug", 实际是"建库/建依赖表失败"。
#    两者都表现为"没输出", 但修法完全不同 —— 所以每步必须回显真实报错。
setup() {
  local out

  drop_db || return 1
  selftest || return 1

  out=$(psql_do postgres <<SQL
CREATE DATABASE $DB;
SQL
)
  if [ -n "$out" ]; then device_error "建库失败: $out"; return 1; fi

  out=$(psql_do "$DB" <<'SQL'
CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY);
SQL
)
  if [ -n "$out" ]; then device_error "建 sys_user 失败(外键依赖): $out"; return 1; fi

  out=$(sed -n '/CREATE TABLE IF NOT EXISTS sys_notification/,/^);/p' "$V13" | psql_do "$DB")
  if [ -n "$out" ]; then device_error "建 sys_notification 失败: $out"; return 1; fi

  local exists
  exists=$(q "$DB" "SELECT count(*) FROM information_schema.tables WHERE table_name='sys_notification';")
  if [ "$exists" != "1" ]; then
    device_error "表未建出 (回读 count=$exists) —— V13 提取可能已失效"; return 1
  fi

  out=$(psql_do "$DB" <<'SQL'
INSERT INTO sys_user (user_id) VALUES (1);
SQL
)
  if [ -n "$out" ]; then device_error "sys_user 依赖行插入失败: $out"; return 1; fi
  return 0
}

seed() {
  local out
  out=$(psql_do "$DB")
  if [ -n "$out" ]; then device_error "数据插入失败: $out"; return 1; fi
  return 0
}

# 注意: heredoc 接在 && 后时, $? 不可靠(取决于后续命令), 故每用例封装为函数,
# 用显式 if/else 控制流。这是第五课"探针自己坏掉"的又一形态: 判据读到了错误的退出码。
case1() {
  if setup; then seed <<'SQL'
INSERT INTO sys_notification (user_id, type, title, content, status)
VALUES (1,'SYSTEM','t1','c1','0'), (1,'SYSTEM','t2','c2','1'), (1,'SYSTEM','t3','c3','2');
SQL
  else return 1; fi
  if OUT=$(psql_do "$DB" < "$MIG"); then
    RES=$(q "$DB" "SELECT data_type FROM information_schema.columns WHERE table_name='sys_notification' AND column_name='status';")
    VALS=$(q "$DB" "SELECT coalesce(string_agg(status::text, ',' ORDER BY id), 'NO_ROWS') FROM sys_notification;")
    if [ "$RES" = "smallint" ] && [ "$VALS" = "0,1,2" ]; then
      echo "  PASS: 列类型=smallint, 值=0,1,2"
    else
      echo "  FAIL: 列类型=[$RES] 值=[$VALS] (期望 smallint / 0,1,2)"; FAIL=1
    fi
  else
    echo "  FAIL: 迁移执行报错:"; echo "$OUT" | head -5 | sed 's/^/    /'; FAIL=1
  fi
}

case2() {
  if setup; then seed <<'SQL'
INSERT INTO sys_notification (user_id, type, title, content, status)
VALUES (1,'SYSTEM','a','c','0'), (1,'SYSTEM','b','c','UNREAD'),
       (1,'SYSTEM','c','c','SENT'), (1,'SYSTEM','d','c','READ');
SQL
  else return 1; fi
  if OUT=$(psql_do "$DB" < "$MIG"); then
    VALS=$(q "$DB" "SELECT coalesce(string_agg(status::text, ',' ORDER BY title), 'NO_ROWS') FROM sys_notification;")
    if [ "$VALS" = "0,0,1,2" ]; then
      echo "  PASS: 混合形态正确归一 -> 0,0,1,2"
    else
      echo "  FAIL: 期望 0,0,1,2, 实际 [$VALS]"; FAIL=1
    fi
  else
    echo "  FAIL: 迁移执行报错:"; echo "$OUT" | head -5 | sed 's/^/    /'; FAIL=1
  fi
}

case3() {
  if setup; then seed <<'SQL'
INSERT INTO sys_notification (user_id, type, title, content, status)
VALUES (1,'SYSTEM','x','c','GARBAGE');
SQL
  else return 1; fi
  if OUT=$(psql_do "$DB" < "$MIG"); then
    echo "  FAIL: 迁移居然成功了 —— 未识别值被静默转换, 会让'已读'看起来像'未读'"
    echo "        表中现值: $(q "$DB" 'SELECT status::text FROM sys_notification;')"
    FAIL=1
  else
    echo "$OUT" | grep -q '无法识别的值' \
      && echo "  PASS: 迁移按设计报错并说明原因" \
      || { echo "  FAIL: 报错原因不符(可能是别的错误, 非本用例要验证的路径)"; echo "$OUT" | head -3 | sed 's/^/    /'; FAIL=1; }
    STILL=$(q "$DB" "SELECT data_type FROM information_schema.columns WHERE table_name='sys_notification' AND column_name='status';")
    [ "$STILL" = "character varying" ] \
      && echo "  PASS: 类型未被改动 (事务已回滚, 无半迁移残留)" \
      || { echo "  FAIL: 类型已改为 [$STILL] 但迁移失败 -> 留下半迁移状态"; FAIL=1; }
  fi
}

echo
echo "=== 用例 1 (正向): 生产当前形态 —— status 为数字字符串 ==="
progress "用例1: setup+seed"
case1

echo
echo "=== 用例 2 (正向): 混合形态 —— '0' 与 'UNREAD' 并存 ==="
progress "用例2: setup+seed"
case2

echo
echo "=== 用例 3 (负向对照): 未识别值必须报错, 不能静默变 0 ==="
progress "用例3: setup+seed"
case3

echo
progress "收尾: 判定结果"
if [ "$FAIL" -eq 0 ]; then echo "RESULT: ALL_PASS"; exit 0; else echo "RESULT: HAS_FAILURE"; exit 1; fi