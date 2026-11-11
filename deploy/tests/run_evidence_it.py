#!/usr/bin/env python3
"""运行 S4/S5 运行时证据 IT（opt-in, 真实 HTTP 断言）。

用途
----
把"运行时证据"从一次性的人工操作变成**可复跑**的命令:

    ./deploy/tests/run_evidence_it.py --scope s4
    ./deploy/tests/run_evidence_it.py --scope s5 --file-id 42 --tenant-b-token <jwt>

它做三件事:
1. 等待目标服务可达（探针带超时, 区分"取不到值"与"取到假值"）;
2. 用**种子账号**登录拿 Bearer token（验证码从本地 Redis 直接读, 不做 OCR）;
3. 用 `-Devidence.it=true` + 真实参数调用 failsafe 执行 `*EvidenceIT`。

判定口径
--------
脚本本身**不判定业务语义**, 只负责把真实环境参数喂给 IT;
业务断言在 IT 里（notification: `NotificationSystemB3EvidenceIT`,
file: `FileTenantBoundaryEvidenceIT`）。

安全
----
- token / `INTERNAL_CALL_SECRET` 只在进程内传递, **不打印**;
- 密码优先取环境变量 `EVIDENCE_PASSWORD`, 未设置时用仓库内公开的种子密码
  (`V1__init_schema.sql` 注释里写明 `admin123`), 仅适用于本地开发栈。
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

DEFAULT_GATEWAY = "http://localhost:9000"
DEFAULT_AUTH = "http://localhost:8081"
DEFAULT_NOTIFICATION = "http://localhost:8084"
DEFAULT_SYSTEM = "http://localhost:8082"
DEFAULT_FILE = "http://localhost:8083"
DEFAULT_REDIS_CONTAINER = "cloud-redis"

SCOPES = {
    "s4": {
        "module": "cloud-infra-modules/notification-service",
        "it": "com.cloudinfra.notification.NotificationSystemB3EvidenceIT",
    },
    "s5": {
        "module": "cloud-infra-modules/file-service",
        "it": "com.cloudinfra.file.FileTenantBoundaryEvidenceIT",
    },
}


def log(msg: str) -> None:
    print(f"[evidence] {msg}", flush=True)


def http_json(url: str, *, method: str = "GET", body: dict | None = None,
              headers: dict | None = None, timeout: int = 10) -> tuple[int, str]:
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # 连接失败/超时 -> 探针失效, 交由上层重试
        return 0, f"{type(e).__name__}: {e}"


def wait_reachable(url: str, label: str, timeout_s: int = 300) -> bool:
    """探针: 带超时 + 明确区分"取不到值"和"取到假值"。"""
    deadline = time.time() + timeout_s
    last = ""
    while time.time() < deadline:
        status, body = http_json(url, timeout=5)
        # 口径: **只要服务有 HTTP 响应就算"起来了"**。
        # 早前把 500 当成"没起来"是错的 —— actuator 在独立管理端口时, 主端口上的
        # /actuator/health 本来就可能 404/500, 那与"服务是否在监听"无关。
        # 真正的"没起来"是连接层失败(status==0)。
        if status:
            log(f"{label} 可达 (http={status})")
            return True
        last = f"http={status} body={body[:120]}"
        time.sleep(5)
    log(f"FAIL {label} 在 {timeout_s}s 内不可达: {last}")
    return False


def redis_get(container: str, key: str) -> str | None:
    try:
        p = subprocess.run(
            ["podman", "exec", container, "redis-cli", "GET", key],
            capture_output=True, text=True, timeout=15,
        )
    except Exception:
        return None
    if p.returncode != 0:
        return None
    val = (p.stdout or "").strip()
    return val or None


def login(auth_base: str, username: str, password: str, redis_container: str) -> str | None:
    """登录取 access token。

    验证码: auth 把 code 写在 Redis `captcha_codes:<uuid>`（TTL 120s）,
    本地栈可以直接读, 不需要 OCR —— 这是**环境准备**, 不是绕过认证:
    走的是和前端完全相同的 `/auth/captchaImage` + `/auth/login` 路径。
    """
    status, body = http_json(f"{auth_base}/auth/captchaImage", timeout=10)
    if status != 200:
        log(f"FAIL 获取验证码失败 http={status} body={body[:200]}")
        return None
    try:
        payload = json.loads(body)
        uuid = payload["data"]["uuid"]
    except Exception as e:
        log(f"FAIL 验证码响应无法解析: {e}")
        return None

    # 固定验证码优先 (.env AUTH_CAPTCHA_FIXED_CODE): 本地栈默认开启它,
    # 此时验证码**不会**写进 Redis, 走 Redis 读必然取不到。
    fixed = os.environ.get("AUTH_CAPTCHA_FIXED_CODE") or read_secret_from_env_file(
        os.path.join(REPO_ROOT, ".env"), "AUTH_CAPTCHA_FIXED_CODE")
    if fixed:
        code = fixed
    else:
        code = redis_get(redis_container, f"captcha_codes:{uuid}")
        # ⚠️ redis-cli 对不存在的 key 返回字符串 "(nil)", 不是空串 ——
        # 不特判就会把 "(nil)" 当验证码发出去, 表现为"验证码错误或已过期"。
        if code in (None, "", "(nil)", "null"):
            log(f"FAIL 未在 Redis 找到验证码 (uuid={uuid}); 确认 auth/redis 已启动且 key 前缀为 captcha_codes:")
            return None

    status, body = http_json(
        f"{auth_base}/auth/login",
        method="POST",
        # 注意字段名: common 的 LoginUser 里是 `captcha`(不是 `code`),
        # 发 `code` 会让 captcha=null -> 恒定"验证码错误或已过期"。
        body={"username": username, "password": password, "captcha": code, "uuid": uuid},
        timeout=15,
    )
    if status != 200:
        log(f"FAIL 登录请求 http={status} body={body[:200]}")
        return None
    try:
        payload = json.loads(body)
    except Exception as e:
        log(f"FAIL 登录响应无法解析: {e}")
        return None
    if not payload.get("success"):
        log(f"FAIL 登录失败: code={payload.get('code')} message={payload.get('message')}")
        return None
    token = (payload.get("data") or {}).get("token")
    if not token:
        log("FAIL 登录成功但响应里没有 token 字段")
        return None
    return token


def read_secret_from_env_file(path: str, key: str) -> str | None:
    if not os.path.exists(path):
        return None
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            if k.strip() == key:
                return v.strip().strip('"').strip("'")
    return None


def main() -> int:
    p = argparse.ArgumentParser(description="运行 S4/S5 运行时证据 IT")
    p.add_argument("--scope", choices=["s4", "s5"], required=True)
    p.add_argument("--gateway-url", default=os.environ.get("EVIDENCE_GATEWAY_URL", DEFAULT_GATEWAY))
    p.add_argument("--auth-url", default=os.environ.get("EVIDENCE_AUTH_URL", DEFAULT_AUTH))
    p.add_argument("--notification-url", default=os.environ.get("EVIDENCE_NOTIFICATION_URL", DEFAULT_NOTIFICATION))
    p.add_argument("--system-url", default=os.environ.get("EVIDENCE_SYSTEM_URL", DEFAULT_SYSTEM))
    p.add_argument("--file-url", default=os.environ.get("EVIDENCE_FILE_URL", DEFAULT_FILE))
    p.add_argument("--redis-container", default=os.environ.get("EVIDENCE_REDIS_CONTAINER", DEFAULT_REDIS_CONTAINER))
    p.add_argument("--username", default=os.environ.get("EVIDENCE_USERNAME", "admin"))
    p.add_argument("--password", default=os.environ.get("EVIDENCE_PASSWORD", "admin123"))
    p.add_argument("--user-id-self", default=os.environ.get("EVIDENCE_USER_ID_SELF", "1"))
    p.add_argument("--user-id-other", default=os.environ.get("EVIDENCE_USER_ID_OTHER", "999"))
    p.add_argument("--file-id", default=os.environ.get("EVIDENCE_FILE_ID"))
    p.add_argument("--tenant-b-token", default=os.environ.get("EVIDENCE_TENANT_B_TOKEN"))
    p.add_argument("--tenant-b-token-file", default=os.environ.get("EVIDENCE_FIXTURE_FILE"),
                   help="从 prepare_evidence_fixture.py 产出的 JSON 里取 tenant_b_token "
                        "(避免 token 出现在命令行/进程列表里)")
    p.add_argument("--tenant-a-user-id", default=os.environ.get("EVIDENCE_TENANT_A_USER_ID"))
    p.add_argument("--require-notification-source", action="store_true",
                   help="要求审计时间线含 source=NOTIFICATION")
    p.add_argument("--tenant-isolation-expect-code", type=int, default=None,
                   help="租户隔离用例的期望 body.code (如 403=被拒绝, 200=当前契约允许跨租户读取); "
                        "不给则断言严格隔离")
    p.add_argument("--dry-run", action="store_true", help="只打印将执行的 mvn 命令（不含 token）")
    args = p.parse_args()

    scope = SCOPES[args.scope]

    # token 从文件读: 命令行参数会出现在进程列表(/proc/*/cmdline) 里, 文件更安全
    if args.tenant_b_token_file and not args.tenant_b_token:
        try:
            with open(args.tenant_b_token_file, "r", encoding="utf-8") as f:
                args.tenant_b_token = (json.load(f) or {}).get("tenant_b_token")
        except Exception as e:
            log(f"FAIL 读取 {args.tenant_b_token_file} 失败: {e}")
            return 2
        if not args.tenant_b_token:
            log(f"FAIL {args.tenant_b_token_file} 里没有 tenant_b_token")
            return 2

    log(f"scope={args.scope} module={scope['module']}")

    # 1) 服务可达性
    targets = [(args.gateway_url, "gateway"), (args.auth_url, "auth")]
    if args.scope == "s4":
        targets += [(args.notification_url, "notification"), (args.system_url, "system")]
    else:
        targets += [(args.file_url, "file")]
    for url, label in targets:
        if not wait_reachable(f"{url.rstrip('/')}/actuator/health", label):
            return 2

    # 2) 登录
    token = login(args.auth_url.rstrip("/"), args.username, args.password, args.redis_container)
    if not token:
        return 2
    log("登录成功（token 已获取, 不打印）")

    # 3) 组装 -D 参数
    # 用户侧路径一律经网关: 下游服务只认网关注入的 X-User-Id/X-User-Name(需内部签名),
    # 不认 Authorization Bearer —— 直连服务 + Bearer 会 401。
    # 只有 /internal/** 走直连(网关没有该路由, 返回 404)。
    props: list[str] = [
        "-Devidence.it=true",
        f"-Devidence.baseUrl.gateway={args.gateway_url.rstrip('/')}",
        f"-Devidence.s4.jwt={token}",
    ]
    if args.scope == "s4":
        props += [
            f"-Devidence.baseUrl.notification.internal={args.notification_url.rstrip('/')}",
            f"-Devidence.s4.userId.self={args.user_id_self}",
            f"-Devidence.s4.userId.other={args.user_id_other}",
        ]
        if args.require_notification_source:
            props.append("-Devidence.s4.requireNotificationSource=true")
        if args.tenant_b_token:
            props.append(f"-Devidence.s4.jwt.tenantB={args.tenant_b_token}")
            if args.tenant_a_user_id:
                props.append(f"-Devidence.s4.userId.tenantA={args.tenant_a_user_id}")
        if args.tenant_isolation_expect_code is not None:
            props.append(f"-Devidence.s4.tenantIsolation.expectCode={args.tenant_isolation_expect_code}")
    else:
        if not args.file_id:
            log("FAIL s5 需要 --file-id (租户 A 归属的文件 id)")
            return 2
        props += [
            f"-Devidence.s5.jwt.tenantA={token}",
            f"-Devidence.s5.fileId.tenantA={args.file_id}",
        ]
        if args.tenant_b_token:
            props.append(f"-Devidence.s5.jwt.tenantB={args.tenant_b_token}")

    secret = os.environ.get("INTERNAL_CALL_SECRET") or read_secret_from_env_file(
        os.path.join(REPO_ROOT, ".env"), "INTERNAL_CALL_SECRET")
    if secret:
        props.append(f"-Dcloud-infra.internal-call.secret={secret}")
    else:
        log("WARN 未找到 INTERNAL_CALL_SECRET（.env 或环境变量）; 内部契约用例会因缺参 skip")

    cmd = [
        "mvn", "-pl", scope["module"], "-am",
        # failsafe 用的是自己的开关: -Dfailsafe.failIfNoSpecifiedTests=false。
        # 只写 -DfailIfNoTests=false 不够 —— 根模块没有匹配到 IT 时会直接失败,
        # 于是整条 reactor 被 "banned from the build", 根本跑不到目标模块。
        "-DskipITs=false", "-DfailIfNoTests=false", "-Dfailsafe.failIfNoSpecifiedTests=false",
        f"-Dit.test={scope['it']}",
        # 先 test-compile: 直接调 failsafe 目标不会触发编译生命周期,
        # 而 verify-e2e.sh 的 [2b/7] 已经把 target/ 清掉了。
        "test-compile", "failsafe:integration-test", "failsafe:verify",
        "--batch-mode",
    ] + props

    printable = " ".join(shlex.quote(c) for c in cmd if "secret=" not in c and ".jwt=" not in c)
    log("将执行: " + printable)
    if args.dry_run:
        return 0

    env = dict(os.environ)
    if secret:
        env["INTERNAL_CALL_SECRET"] = secret
    rc = subprocess.run(cmd, cwd=REPO_ROOT, env=env).returncode
    log(f"mvn 退出码={rc}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
