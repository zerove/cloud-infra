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

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MIGDIR="$ROOT/cloud-infra-modules/system-module/src/main/resources/db/migration"
MIG="$MIGDIR/V18__fix_notification_status_column_type.sql"
V13="$MIGDIR/V13__add_notification_main_table.sql"
DB="v18_migration_test"
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
x() { timeout 90 podman exec cloud-postgres psql -U postgres -d postgres -q -tA -c "$1" 2>&1; }

db_count() { x "SELECT count(*) FROM pg_database WHERE datname='$DB';"; }

drop_db() {
  local out
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

cleanup() { drop_db >/dev/null 2>&1; true; }
trap cleanup EXIT

echo "=== 前置 ==="
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
case1

echo
echo "=== 用例 2 (正向): 混合形态 —— '0' 与 'UNREAD' 并存 ==="
case2

echo
echo "=== 用例 3 (负向对照): 未识别值必须报错, 不能静默变 0 ==="
case3

echo
if [ "$FAIL" -eq 0 ]; then echo "RESULT: ALL_PASS"; exit 0; else echo "RESULT: HAS_FAILURE"; exit 1; fi