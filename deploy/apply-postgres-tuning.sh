#!/usr/bin/env bash
# 把 deploy/postgres-tuning.conf 里的参数应用到运行中的 PostgreSQL。
#
# ⚠️ 为什么不直接靠 compose 的 command / PG_* 环境变量（两者都实测过, **都无效**）:
#   1. command: [postgres, -c, shared_buffers=192MB, ...]
#      → 容器实际 Cmd 变成了 ["postgres"], 5 个 -c 都没生效。
#      ⚠️ **别再把它归因成 "podman-compose 丢弃 command"**: 隔离测试证明
#        1.6.0 是支持 command 的（新起容器实测参数带上了）。现象属实,
#        但那个原因解释不成立, 真正原因尚未定位。
#   2. 环境变量 PG_SHARED_BUFFERS=192MB（官方文档说支持）
#      → 实测读回仍是 128MB, 该镜像 entrypoint 不做这个转换。
#   两者都是"配置写了但运行时没生效"的假绿（AGENTS.md 第三条硬规则）。
#
# ✅ 可靠路径: ALTER SYSTEM → 写入 $PGDATA/postgresql.auto.conf
#   - PG 官方机制, 唯一保证生效的入口
#   - auto.conf 由 postgresql.conf 末尾自动 include, 且在 shared_buffers(第130行)
#     **之后**, 故优先级高于主配置里的显式值
#   - 文件在**数据卷内** → 容器重建不丢
#
# ⚠️ 全新环境(空数据卷)的路径不同: /docker-entrypoint-initdb.d 只在数据目录为空时
#   执行一次, 所以那里也放了一份等价脚本, 由 initdb.d/10-tuning.sh 调用。
set -euo pipefail

CONF="$(dirname "$0")/postgres-tuning.conf"
CONTAINER="${PG_CONTAINER:-cloud-postgres}"

# ⚠️ 期望值必须**独立于 conf**, 否则是自证循环:
#   第一版从 conf 读"期望值"再写再读回 —— 改坏 conf 时它照样 PASS。
#   实测做过负向对照: 把 shared_buffers 改成 128MB, 脚本仍报
#   "全部参数已在运行时生效"(因为它自己写进去的, 当然读回一致)。
#   ⇒ 期望值写死在此, 与 conf 形成**双向**校验:
#      conf 被改坏 → 这里期望 192MB 但读回 128MB → 报红。
EXPECT_SHARED_BUFFERS="${EXPECT_SHARED_BUFFERS:-192MB}"
EXPECT_MAX_WAL_SIZE="${EXPECT_MAX_WAL_SIZE:-2GB}"
EXPECT_MIN_WAL_SIZE="${EXPECT_MIN_WAL_SIZE:-256MB}"
EXPECT_WAL_COMPRESSION="${EXPECT_WAL_COMPRESSION:-on}"
EXPECT_RPC="${EXPECT_RPC:-1.1}"

# 归一化 SHOW 的输出, 用于**比对**。
#
# ⚠️ 两个已实测的坑, 都在这里收口:
#   1. wal_compression 设 on 后读回的是 `pglz`(等价枚举)。
#   2. 带单位的值 PostgreSQL 会**小写化**: SHOW shared_buffers = `192mb`,
#      而 conf / 期望值写的是 `192MB`。不归一化就会把每一项都误判成
#      "待改", 于是每次部署都白重启一次 PostgreSQL —— 我第一版就这样,
#      幂等分支永远走不到(2026-10-05 实测)。
# ⚠️ 归一化**只用于比对**, ALTER SYSTEM 仍写 conf 里的原值。
normalize() {
  local v="${1,,}"
  case "$v" in
    pglz|lz4|zstd)   echo "on" ;;
    # 去掉数值与单位之间的空格, 并把 mb/gb 提到大写, 与 conf 对齐
    *mb|*gb|*tb)     echo "${v// /}" | sed -E 's/(mb|gb|tb)$/\U\1/' ;;
    *)               echo "$v" ;;
  esac
}

# conf 里的裸 key -> 独立期望值
expected_value_for() {
  case "$1" in
    shared_buffers)    echo "$EXPECT_SHARED_BUFFERS" ;;
    max_wal_size)      echo "$EXPECT_MAX_WAL_SIZE" ;;
    min_wal_size)      echo "$EXPECT_MIN_WAL_SIZE" ;;
    wal_compression)   echo "$EXPECT_WAL_COMPRESSION" ;;
    random_page_cost)  echo "$EXPECT_RPC" ;;
    *)                 echo "" ;;   # 无独立期望值: 只验证"确实被改过"
  esac
}

# 从 conf 取某个 key 的**原始字面值**（未经归一化）。
#
# ⚠️ 为什么要区分"原始字面值"与"归一化后的期望值":
#   ALTER SYSTEM 要写 PG 能接受的值(`on`), 而 SHOW wal_compression 读回的是
#   `pglz` —— 归一化只用于**比对**, 不能拿去写。
#   第一版没有这个区分, 直接把归一化值写回去, 属第五课那类"改了一个东西
#   却以为是另一个"。
conf_value_of() {
  local want_key="$1" conf_file="$2" k v
  while IFS= read -r line; do
    [[ -z "${line// }" || "$line" == \#* ]] && continue
    k="$(echo "${line%%=*}" | tr -d '[:space:]')"
    [[ "$k" == "$want_key" ]] || continue
    v="$(echo "${line#*=}" | tr -d "[:space:]'\"")"
    echo "$v"
    return 0
  done < "$conf_file"
  return 1
}

# 读运行时实际值（归一化后）
runtime_value() {
  local raw
  raw="$(podman exec "$CONTAINER" psql -U postgres -tAc "SHOW $1;" 2>/dev/null \
        | head -1 | tr -d '[:space:]')" || raw=""
  [[ -z "$raw" ]] && { echo "<读不到>"; return; }
  normalize "$raw"
}

if [[ ! -f "$CONF" ]]; then
  echo "FAIL: 找不到 $CONF" >&2
  exit 1
fi

if ! podman inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "FAIL: 容器 $CONTAINER 不存在" >&2
  exit 1
fi

echo "==> 应用 $CONF 到 $CONTAINER"

# ---- 阶段 1: 读当前运行时值, 找出真正需要改的项 ------------------------
#
# ⚠️ 幂等是必需的, 不是优化: 重启 PostgreSQL 会断开所有连接, 且在宿主机
#   I/O 紧张时(本项目常态)容器重启后要几十秒才恢复健康。参数已正确时
#   仍去 ALTER + restart, 会把一次无害的部署变成一次真实的服务抖动。
declare -a NEED=()
declare -A WANT=()
total=0
while IFS= read -r line; do
  [[ -z "${line// }" || "$line" == \#* ]] && continue
  key="$(echo "${line%%=*}" | tr -d '[:space:]')"
  [[ -z "$key" ]] && continue
  total=$((total + 1))
  conf_val="$(echo "${line#*=}" | tr -d "[:space:]'\"")"
  want="$(expected_value_for "$key")"
  # 无独立期望值时, 退化为"conf 值本身"作为目标
  [[ -z "$want" ]] && want="$conf_val"
  # ⚠️ 两侧必须走**同一个** normalize, 否则就是拿 `192MB` 比 `192mb` ——
  #   实测这会让幂等分支永远走不到, 每次部署都白重启一次数据库。
  WANT["$key"]="$(normalize "$want")"
  got="$(runtime_value "$key")"
  # ⚠️ 比较的两侧都必须是归一化后的值, 否则形如 `192MB` vs `192mb`
  #   会被判成"待改" —— 幂等分支因此永远走不到。
  if [[ "$got" != "${WANT[$key]}" ]]; then
    NEED+=("$key")
    echo "  待改: ${key} = ${got}  →  ${WANT[$key]}"
  fi
done < "$CONF"

# ---- 阶段 2: 只改真正需要改的项 ---------------------------------------
if [[ ${#NEED[@]} -eq 0 ]]; then
  echo "==> 全部 $total 项参数已在运行时生效, 跳过 ALTER SYSTEM 与重启（幂等）"
else
  for key in "${NEED[@]}"; do
    raw_val="$(conf_value_of "$key" "$CONF")" || {
      echo "FAIL: conf 里找不到 $key" >&2
      exit 1
    }
    out="$(podman exec "$CONTAINER" psql -U postgres -tAc \
          "ALTER SYSTEM SET ${key} = '${raw_val}';" 2>&1)" || {
      echo "FAIL: ALTER SYSTEM SET ${key} 失败: $out" >&2
      exit 1
    }
    echo "  ${key} = ${raw_val}"
  done

  echo "==> 已写入 ${#NEED[@]} 项, 需重启容器让 shared_buffers 生效"
  podman restart "$CONTAINER" >/dev/null

  for _ in $(seq 1 30); do
    # ⚠️ 探针要区分"取不到值"与"取到假值"(第五课补记四):
    #   inspect 失败时 $state 为空串, 不能当成"不是 healthy"。
    state="$(timeout 15 podman inspect --format '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null)" || state=""
    [[ -z "$state" ]] && state="probe-failed"
    [[ "$state" == "healthy" ]] && break
    sleep 2
  done
fi

# ---- 阶段 3: 校验运行时实际生效值（期望值来自本脚本, 与 conf 独立）----
echo "==> 校验运行时实际生效值（期望值来自本脚本, 与 conf 相互独立）"
rc=0
checked=0
for key in "${!WANT[@]}"; do
  got="$(runtime_value "$key")"
  want="${WANT[$key]}"
  if [[ "$got" == "$want" ]]; then
    echo "  ${key} = ${got}  ✓"
  else
    echo "  ${key} = ${got}  独立期望 ${want}  ✗" >&2
    rc=1
  fi
  checked=$((checked + 1))
done

if [[ $rc -eq 0 ]]; then
  echo "PASS: ${checked} 项参数已在运行时生效"
else
  echo "FAIL: 有参数未生效" >&2
fi
exit $rc
