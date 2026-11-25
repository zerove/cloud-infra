#!/usr/bin/env python3
"""准备 S4/S5 运行时证据所需的本地夹具（仅本地开发栈）。

它让整套证据**一条命令可复跑**：

    python3 deploy/tests/prepare_evidence_fixture.py
    python3 deploy/tests/run_evidence_it.py --scope s4 \
        --tenant-b-token-file /tmp/opencode/evidence-fixture.json \
        --require-notification-source
    python3 deploy/tests/run_evidence_it.py --scope s5 \
        --file-id 9001 \
        --tenant-b-token-file /tmp/opencode/evidence-fixture.json

产出的夹具
----------
1. **tenant A**：种子账号 ``admin``（``sys_user.tenant_id='default'``）。
2. **tenant B 超管**：``evidence_b``（tenant ``tenant-b``，角色 SUPER_ADMIN）——
   与 tenant A 的差异**只有租户**，用于跨租户隔离断言。
3. **tenant B 审计员**：``evidence_auditor_b``（tenant ``tenant-b``，角色
   ``AUDITOR_B``，**只有** ``system:audit:list`` 一个权限）——
   用于区分"超管特权"与"端点缺少租户校验"（见 issue 36）。
4. **tenant A 归属的文件**：``file_metadata`` / ``file_version``（id 9001）。
5. **tenant A 的通知与发送日志**：``sys_notification`` + ``sys_notification_log``
   （供审计时间线的 ``source=NOTIFICATION`` 断言）。

安全
----
- 密码/token 只在进程内传递与落盘（0600），**不打印**；
- 内部签名密钥只从环境变量 / ``.env`` 读取，不输出；
- 夹具写入本地 Postgres（开发库），可重复执行（幂等）。

前提：本地栈已按 ``deploy/up.sh`` 启动且 Nacos 配置已同步（服务要先有配置再起，
否则会带着残缺配置启动失败 —— 见 ``deploy/up.sh`` 步骤 3b/3c 的注释）。
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

DEFAULT_OUT = "/tmp/opencode/evidence-fixture.json"
DEFAULT_GATEWAY = "http://localhost:9000"

TENANT_A = "default"
TENANT_B = "tenant-b"
USER_B = "evidence_b"
USER_B_AUDITOR = "evidence_auditor_b"
ROLE_B_AUDITOR_ID = 9001
PERM_B_AUDITOR_ID = 9001
FILE_ID = 9001


def read_env(path):
    d = {}
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            d[k.strip()] = v.strip().strip('"').strip("'")
    return d


def req(url, method="GET", body=None, headers=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    h = dict(headers or {})
    if data:
        h.setdefault("Content-Type", "application/json")
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"


def psql(sql, container="cloud-postgres", db="cloud_infra", user="postgres"):
    p = subprocess.run(
        ["podman", "exec", "-i", container, "psql", "-U", user, "-d", db,
         "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True, timeout=60,
    )
    return p.returncode, ((p.stdout or "") + (p.stderr or "")).strip()


def login(gateway, username, password, captcha):
    st, body = req(f"{gateway}/auth/login", "POST",
                   {"username": username, "password": password,
                    "captcha": captcha, "uuid": f"fixture-{username}"})
    try:
        payload = json.loads(body)
    except Exception:
        return None, f"http={st} body={body[:160]}"
    if not payload.get("success"):
        return None, f"code={payload.get('code')} msg={(payload.get('data') or {}).get('message')}"
    token = (payload.get("data") or {}).get("token")
    if not token:
        return None, "login ok but no token in response"
    return token, "ok"


def jwt_claim(token, name):
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload)).get(name)
    except Exception:
        return None


def main():
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap = argparse.ArgumentParser(description="准备 S4/S5 运行时证据夹具")
    ap.add_argument("--gateway", default=os.environ.get("EVIDENCE_GATEWAY_URL", DEFAULT_GATEWAY))
    ap.add_argument("--out", default=os.environ.get("EVIDENCE_FIXTURE_FILE", DEFAULT_OUT))
    ap.add_argument("--admin-username", default=os.environ.get("EVIDENCE_USERNAME", "admin"))
    ap.add_argument("--admin-password", default=os.environ.get("EVIDENCE_PASSWORD", "admin123"))
    args = ap.parse_args()

    env = read_env(os.path.join(repo, ".env"))
    captcha = env.get("AUTH_CAPTCHA_FIXED_CODE", "")
    if not captcha:
        print("FAIL .env 里没有 AUTH_CAPTCHA_FIXED_CODE；本脚本依赖本地固定验证码登录", file=sys.stderr)
        return 2

    # 1) admin 登录 (tenant A)
    token_a, msg = login(args.gateway, args.admin_username, args.admin_password, captcha)
    if not token_a:
        print("FAIL admin 登录失败:", msg, file=sys.stderr)
        return 2
    auth_a = {"Authorization": f"Bearer {token_a}"}
    print("[1/5] tenant A 登录成功")

    # 2) tenant B 的两个用户 (超管 + 仅审计权限的审计员)
    #    注意: 管理端 insertUser 会用 sys_config.sys.user.initPassword 覆盖请求里的密码,
    #    所以两个用户都用初始密码登录 (123456)。
    for username, nick, role_id in ((USER_B, "租户B超管", 1),
                                    (USER_B_AUDITOR, "租户B审计员", ROLE_B_AUDITOR_ID)):
        rc, out = psql(f"SELECT 1 FROM sys_user WHERE username='{username}';")
        exists = any(l.strip().isdigit() for l in out.splitlines() if "---" not in l and "row" not in l)
        if exists:
            print(f"[2/5] 用户 {username} 已存在, 复用")
            continue
        st, body = req(f"{args.gateway}/system/user", "POST",
                       {"username": username, "password": "123456", "nickName": nick,
                        "roleId": role_id, "deptId": 1}, headers=auth_a)
        ok = st == 200
        print(f"[2/5] 创建用户 {username}: http={st} {'' if ok else body[:120]}")

    # tenant B 专属的权限行与角色 (sys_permission / sys_role 有 tenant_id)
    for sql in [
        f"INSERT INTO sys_permission (id,name,perms,path,method,tenant_id) VALUES "
        f"({PERM_B_AUDITOR_ID},'审计查询(tenant-b)','system:audit:list','/system/audit/timeline','GET','{TENANT_B}') "
        f"ON CONFLICT (id) DO NOTHING;",
        f"INSERT INTO sys_role (id,role_name,role_key,role_order,status,data_scope,create_time,update_time,deleted,tenant_id) "
        f"VALUES ({ROLE_B_AUDITOR_ID},'租户B审计员','AUDITOR_B',9,1,'1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,'{TENANT_B}') "
        f"ON CONFLICT (id) DO NOTHING;",
        f"INSERT INTO sys_role_permission (role_id,permission_id) VALUES ({ROLE_B_AUDITOR_ID},{PERM_B_AUDITOR_ID}) "
        f"ON CONFLICT DO NOTHING;",
        f"UPDATE sys_user SET tenant_id='{TENANT_B}' WHERE username IN ('{USER_B}','{USER_B_AUDITOR}');",
        f"INSERT INTO sys_user_role (user_id,role_id) SELECT u.user_id,{ROLE_B_AUDITOR_ID} FROM sys_user u "
        f"WHERE u.username='{USER_B_AUDITOR}' AND NOT EXISTS "
        f"(SELECT 1 FROM sys_user_role r WHERE r.user_id=u.user_id AND r.role_id={ROLE_B_AUDITOR_ID});",
        f"UPDATE sys_user SET role_id={ROLE_B_AUDITOR_ID} WHERE username='{USER_B_AUDITOR}';",
    ]:
        rc, out = psql(sql)
        if rc != 0:
            print("FAIL sql:", out[:200], file=sys.stderr)
            return 2
    print("[3/5] tenant B 用户/角色/权限就绪")

    # 3) tenant A 归属的文件夹具
    for sql in [
        f"INSERT INTO file_metadata (id,file_name,file_path,file_type,file_size,uploader,create_time,current_version,tenant_id) "
        f"VALUES ({FILE_ID},'evidence-tenant-a.txt','/evidence/tenant-a.txt','text/plain',12,'1',CURRENT_TIMESTAMP,1,'{TENANT_A}') "
        f"ON CONFLICT (id) DO NOTHING;",
        f"INSERT INTO file_version (id,file_id,version_number,storage_path,create_time) "
        f"VALUES ({FILE_ID},{FILE_ID},1,'/evidence/tenant-a.txt',CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING;",
    ]:
        rc, out = psql(sql)
        if rc != 0:
            print("FAIL sql:", out[:200], file=sys.stderr)
            return 2
    print(f"[4/5] 文件夹具就绪 (file_id={FILE_ID}, tenant={TENANT_A})")

    # 4) 通知与发送日志 (供审计时间线 source=NOTIFICATION 断言)
    for sql in [
        "INSERT INTO sys_notification (user_id,type,title,content,status,create_time) "
        "SELECT 1,'SYSTEM','evidence-notification','evidence fixture for audit timeline',1,CURRENT_TIMESTAMP "
        "WHERE NOT EXISTS (SELECT 1 FROM sys_notification WHERE title='evidence-notification');",
        "INSERT INTO sys_notification_log (notification_id,channel_code,status,error_message,duration,create_time) "
        "SELECT n.id,'email','SUCCESS',NULL,12,CURRENT_TIMESTAMP FROM sys_notification n "
        "WHERE n.title='evidence-notification' AND NOT EXISTS "
        "(SELECT 1 FROM sys_notification_log l WHERE l.notification_id=n.id);",
    ]:
        rc, out = psql(sql)
        if rc != 0:
            print("FAIL sql:", out[:200], file=sys.stderr)
            return 2
    print("[5/5] 通知/发送日志夹具就绪")

    # 5) tenant B 登录 (超管 + 审计员)
    token_b, msg = login(args.gateway, USER_B, "123456", captcha)
    if not token_b:
        print("FAIL tenant B 登录失败:", msg, file=sys.stderr)
        return 2
    token_auditor, msg = login(args.gateway, USER_B_AUDITOR, "123456", captcha)
    if not token_auditor:
        print("WARN tenant B 审计员登录失败:", msg)

    rc, out = psql(f"SELECT user_id FROM sys_user WHERE username='{USER_B}';")
    user_b_id = None
    for line in out.splitlines():
        if line.strip().isdigit():
            user_b_id = int(line.strip())
            break

    payload = {
        "file_id": FILE_ID,
        "tenant_a_user_id": 1,
        "tenant_a_tenant": jwt_claim(token_a, "tenantId"),
        "tenant_b_user_id": user_b_id,
        "tenant_b_tenant": jwt_claim(token_b, "tenantId"),
        "tenant_b_token": token_b,
        "tenant_b_auditor_token": token_auditor,
        "tenant_b_auditor_tenant": jwt_claim(token_auditor, "tenantId") if token_auditor else None,
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    os.chmod(args.out, 0o600)
    print(f"\n夹具已写入 {args.out} (0600; token 不打印)")
    print(f"  tenant A={payload['tenant_a_tenant']}  tenant B={payload['tenant_b_tenant']}  "
          f"tenant B 审计员={payload['tenant_b_auditor_tenant']}  file_id={FILE_ID}")
    print("\n下一步:")
    print(f"  python3 deploy/tests/run_evidence_it.py --scope s4 --tenant-b-token-file {args.out} "
          "--require-notification-source")
    print(f"  python3 deploy/tests/run_evidence_it.py --scope s5 --file-id {FILE_ID} "
          f"--tenant-b-token-file {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
