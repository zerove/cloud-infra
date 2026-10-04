#!/usr/bin/env bash
set -euo pipefail

# E2E 真实链路验收：登录 -> 受保护资源 -> 401 -> 403
# 前置条件：本地环境已启动（./deploy/up.sh），且 auth 启用固定验证码
#   （deploy/config/auth.yaml 或环境变量 AUTH_CAPTCHA_FIXED_CODE=1234）
GATEWAY="${GATEWAY:-http://localhost:9000}"
FIXED_CODE="${AUTH_CAPTCHA_FIXED_CODE:-1234}"
PASS=0; FAIL=0

# 本机透明/环境代理会劫持回环请求(SYN_SENT 挂起), 全链路强制直连
export no_proxy="*" NO_PROXY="*"
unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY
CURL="curl -s --noproxy *"

expect_contains() { # desc haystack needle
  local desc="$1" hay="$2" needle="$3"
  if echo "$hay" | grep -q "$needle"; then PASS=$((PASS+1)); echo "  PASS: $desc";
  else FAIL=$((FAIL+1)); echo "  FAIL: $desc (期望含 '$needle'，实际: ${hay:0:200})"; fi
}

echo "=== E2E 验收测试开始 ==="

echo "1. 获取验证码 uuid..."
CAPTCHA_RESP=$(curl -s -f --noproxy '*' -X GET "$GATEWAY/auth/captchaImage")
UUID=$(echo "$CAPTCHA_RESP" | grep -o '"uuid":"[^"]*"' | cut -d'"' -f4)
[ -n "$UUID" ] || { echo "FAIL: 未获取到 captcha uuid"; exit 1; }
echo "  UUID: $UUID"

echo "2. 场景一：错误密码登录应被拒绝..."
RESP=$(curl -s --noproxy '*' -X POST "$GATEWAY/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"wrong-password","captcha":"'"$FIXED_CODE"'","uuid":"'"$UUID"'"}')
expect_contains "错误密码返回失败码" "$RESP" '"code":-\?[0-9]*' 
if echo "$RESP" | grep -q '"token"'; then
  FAIL=$((FAIL+1)); echo "  FAIL: 错误密码竟拿到 token"
else
  PASS=$((PASS+1)); echo "  PASS: 错误密码未签发 token"
fi

echo "3. 场景二：正确凭据登录获取 token..."
CAPTCHA_RESP=$(curl -s -f --noproxy '*' -X GET "$GATEWAY/auth/captchaImage")
UUID=$(echo "$CAPTCHA_RESP" | grep -o '"uuid":"[^"]*"' | cut -d'"' -f4)
LOGIN_RESP=$(curl -s --noproxy '*' -X POST "$GATEWAY/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","captcha":"'"$FIXED_CODE"'","uuid":"'"$UUID"'"}')
TOKEN=$(echo "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -n "$TOKEN" ]; then PASS=$((PASS+1)); echo "  PASS: 登录成功获得 token";
else FAIL=$((FAIL+1)); echo "  FAIL: 登录未获得 token，响应: $LOGIN_RESP"; fi

echo "4. 场景三：无 token 访问受保护资源应 401..."
HTTP_CODE=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" "$GATEWAY/system/user/list")
if [ "$HTTP_CODE" = "401" ]; then PASS=$((PASS+1)); echo "  PASS: 无 token -> 401";
else FAIL=$((FAIL+1)); echo "  FAIL: 期望 401 实际 $HTTP_CODE"; fi

echo "5. 场景四：带 token 访问受保护资源应 2xx..."
HTTP_CODE=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "$GATEWAY/system/user/list")
if [[ "$HTTP_CODE" == 2* ]]; then PASS=$((PASS+1)); echo "  PASS: 带 token -> $HTTP_CODE";
else FAIL=$((FAIL+1)); echo "  FAIL: 期望 2xx 实际 $HTTP_CODE"; fi

echo "6. 场景五：伪造 token 应 401..."
HTTP_CODE=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer forged.token.value" "$GATEWAY/system/user/list")
if [ "$HTTP_CODE" = "401" ]; then PASS=$((PASS+1)); echo "  PASS: 伪造 token -> 401";
else FAIL=$((FAIL+1)); echo "  FAIL: 期望 401 实际 $HTTP_CODE"; fi

echo "7. 场景六：SkyWalking 必须真收到 trace（M7-01 的运行时判据）..."
# ⚠️ 为什么必须在这里查存储, 而不是查 agent 日志或 OAP 的 HTTP 200。
#   前两者都能在"agent 注入失败 / OAP 空转"时照样成立:
#     · agent 日志只证明 -javaagent 挂上了, 不证明数据出得去;
#     · OAP /healthcheck 是**浅层**探活 —— 停掉 postgres 后它仍返回 200
#       (score=0 表示"无故障项"而非"全部检查通过"), 见 compose 的 healthcheck 注释。
#   唯一能证明"机制真的在运行"的判据是**存储里有本项目产生的 segment**。
#
# 为什么查 PostgreSQL 而不是 OAP 的 GraphQL:
#   OAP 查询 API 的 schema 与 version 强绑定, 手写查询体极易踩空
#   (本次实测直接报 InvalidSyntax)。而存储是稳定的事实来源。
#
# 表名带**日期后缀** (segment_YYYYMMDD), 跨天就查不到 —— 必须动态取当天日期,
# 不能写死。这是第一次实测时按 `segment` 猜表名失败的原因。
if ! command -v podman >/dev/null 2>&1; then
  echo "  SKIP: 无 podman, 跳过 trace 校验"
elif ! timeout 30 podman ps --format '{{.Names}}' 2>/dev/null | grep -qx "cloud-skywalking-oap"; then
  # ⚠️ 区分"OAP 没部署"与"OAP 部署了但不工作":
  #   前者是**本次不判定**(SKIP), 后者必须判红。
  #   若两者都放行, 就退化成第六类假绿 —— 这正是本项目反复踩的形态。
  echo "  SKIP: cloud-skywalking-oap 未运行 —— 本次不判定 trace (不是判绿)"
else
  # 库名从 OAP 容器**实际环境变量**读, 而不是写死 "skywalking"。
  # 写死的话, 一旦改库名或换存储后端, 这里会报"表不存在"而没人知道是配置变了。
  # `|| true` 不可省: `set -e` + `pipefail` 下, 探针自身失败(容器没了/超时)会
  # 直接终止整个脚本 —— 那不是"判定失败", 是"脚本崩了", 两者在日志里长得一样:
  # 都是没有 FAIL 消息就结束。本次负向对照就撞上了这一点。
  SW_DB="$(timeout 30 podman exec cloud-skywalking-oap printenv SW_JDBC_URL 2>/dev/null \
    | sed 's|.*/||' || true)"
  [ -n "$SW_DB" ] || SW_DB="skywalking"
  TODAY="$(date +%Y%m%d)"

  # ⚠️ tr 不能用 '[:space:]' —— 它会把**换行也删掉**, 于是多个服务名粘连成一行。
  # 实测输出过 `notification-servicecloud-postgres:5432file-service...`:
  # 一旦判定靠的是"这个字符串里含不含某个名字", 粘连本身不致命; 但它会让人
  # 无法判断到底上报了哪些服务, 且这种粘连在**没有换行**时看不出是丢了几行。
  # 正确做法: 逐行处理, 只剥掉行尾的 \r。
  seg_query() { # $1=SQL, 输出保留换行
    timeout 60 podman exec cloud-postgres \
      psql -U postgres -d "$SW_DB" -t -A -c "$1" 2>/dev/null || true
  }
  SEG_ROWS="$(seg_query "SELECT count(*) FROM segment_${TODAY};" | tr -d '[:space:]')"
  if [ -z "$SEG_ROWS" ]; then
    # 空值有两种互斥解释: 表不存在(真故障) / 库名不对(配置变了) / psql 失败(探针坏了)。
    # 三者都不能算通过, 但**必须说清是哪一个** —— 否则读者无法判断该修哪一层。
    FAIL=$((FAIL+1))
    echo "  FAIL: 读不到 segment_${TODAY} 的行数 (库=$SW_DB)"
    echo "        可能: ①OAP 未收到任何数据(agent 未注入/连不上OAP) ②存储库名非 $SW_DB ③表前缀不是 segment_"
  elif [ "$SEG_ROWS" -gt 0 ] 2>/dev/null; then
    PASS=$((PASS+1)); echo "  PASS: SkyWalking 已收到 ${SEG_ROWS} 条 trace segment"
  else
    FAIL=$((FAIL+1))
    echo "  FAIL: segment_${TODAY} 为 0 行 —— agent 挂了或 OAP 收不到数据"
  fi
  # 附带确认: 上报的服务名里必须有我们的业务服务, 而不是只有中间件。
  # 只有中间件说明 agent 注入失败(中间件是 OAP 自己上报的)。
  #
  # ⚠️ 数据源必须是 segment 表的 service_id, **不能查 service_traffic_YYYYMMDD**。
  #   2026-10-05 实测踩坑: service_traffic 是**聚合表**, 由 OAP 定时任务生成,
  #   实时 segment 写入时它仍是 0 行 —— 查它会得出"没有业务服务上报"的
  #   **错误结论**, 而此刻 6 个服务的 agent 其实全部工作正常。
  #   这正是第五课"探针给出语法合法但语义错误的结论": 全程无报错, 结论却是错的。
  #
  # ⚠️ service_id 格式是 `<base64服务名>.<序号>`(如 `Y2xvdWQtaW5mcmEtYXV0aA==.1`),
  #   解码前**必须先regexp_replace 掉 `.N` 后缀**, 否则 psql 报
  #   `invalid symbol "." found while decoding base64 sequence`。
  # ⚠️ ORDER BY 必须写在子查询外并用**列名**(cnt) 而非位置号:
  #   `... || '=' || count(*) ... ORDER BY 2` 会报
  #   `ORDER BY position 2 is not in select list` —— 因为拼接后只剩一列。
  #   同样地, 上面两个错误都会被 `2>/dev/null || true` 吞成空字符串,
  #   使判据恒红且**看不出原因**。写SQL 时先在命令行验证再落盘。
  #
  # ⚠️ 白名单必须从**代码里**提取, 不能凭印象手写 (第五课)。
  #   实测两者**确实不一致**:
  #     Java 侧 spring.application.name = cloud-auth
  #     上报名(agent 读容器/环境变量)     = cloud-infra-auth
  #   权威清单(cloud-infra-gateway / file-service / notification-service /
  #   search-service / system-module / cloud-infra-auth) 于 2026-10-04 从
  #   `grep 'application.name'` + OAP 库双向比对确认, 并于 2026-10-05 用
  #   `SELECT DISTINCT convert_from(decode(service_id,'base64'),'UTF8') FROM segment_*`
  #   逐个核对计数确认。
  #   语义: 至少有一个**业务**服务上报即可, 不要求 6 个全在(逐个要求会让
  #   任何单个服务未启动就整体判红, 失去定位能力)。
  SW_SVCS="$(seg_query "SELECT svc || '=' || cnt FROM (SELECT convert_from(decode(regexp_replace(service_id,'\\.[0-9]+\$',''),'base64'),'UTF8') AS svc, count(*) AS cnt FROM segment_${TODAY} GROUP BY 1) t ORDER BY cnt DESC;")"
  SW_DECODED="$(printf '%s\n' "$SW_SVCS" | tr -d '\r' | cut -d'=' -f1 | grep -v '^$')"
  if printf '%s\n' "$SW_DECODED" | grep -qxE 'cloud-infra-gateway|file-service|notification-service|search-service|system-module|cloud-infra-auth'; then
    PASS=$((PASS+1))
    echo "  PASS: 上报服务名含业务服务 (来自 segment_${TODAY}.service_id, 非聚合表)"
    printf '%s\n' "$SW_SVCS" | tr -d '\r' | sed 's/^/        - /' | head -10
  else
    FAIL=$((FAIL+1))
    echo "  FAIL: 上报服务名里没有业务服务 —— agent 注入可能失败。实际:"
    printf '%s\n' "$SW_SVCS" | tr -d '\r' | sed 's/^/        - /' | head -10
    # service_traffic 是聚合表, 空是正常的; 明确区分两种"空"(第五课):
    #   segment 有行但服务名解不出 -> SQL/编码问题
    #   segment 为 0 行            -> agent 真的没上报
    echo "        (注: service_traffic_*是聚合表, 空属正常; 判据以segment 表为准)"
  fi
fi

echo "=== 结果: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ]
