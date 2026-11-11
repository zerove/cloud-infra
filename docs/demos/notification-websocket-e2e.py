"""
Notification WebSocket + REST 端到端测试
- 客户端直连 ws://localhost:8084/notification/ws（M7-03: 无参路径，userId 来自可信头）
- WS 握手需携带网关等效内部签名头（verify-internal-sign fail-closed 默认开启）
- 等 connected 消息
- 调 REST POST /notification/send
- 验证收到 WebSocket push
- ping/pong 心跳
"""
import asyncio
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.request
import websockets


def load_internal_call_secret():
    """读取内部调用签名密钥（不打印）。优先环境变量，其次 .env。"""
    secret = os.environ.get("INTERNAL_CALL_SECRET")
    if secret:
        return secret
    for path in (".env", os.path.join(os.path.dirname(__file__), "..", "..", ".env")):
        try:
            with open(path, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, v = line.split("=", 1)
                    if k.strip() == "INTERNAL_CALL_SECRET":
                        value = v.strip().strip('"').strip("'")
                        if value:
                            return value
        except FileNotFoundError:
            continue
    raise RuntimeError(
        "INTERNAL_CALL_SECRET 未找到：WebSocket 握手需内部签名头。"
        "请设置环境变量或在仓库根目录提供 .env（deploy/compose.yaml 要求）。"
    )


def internal_call_headers(user_id: str, secret: str):
    """生成与 InternalCallCrypto.sign/verify 一致的握手头。"""
    timestamp = str(int(time.time() * 1000))
    nonce = f"{int(time.time() * 1000):x}:{os.urandom(8).hex()}"
    message = f"{timestamp}:{nonce}".encode("utf-8")
    sign = hmac.new(secret.encode("utf-8"), message, hashlib.sha256).hexdigest()
    return {
        "X-Internal-Call": sign,
        "X-Internal-Timestamp": timestamp,
        "X-Internal-Nonce": nonce,
        "X-User-Id": str(user_id),
    }


async def get_token():
    req = urllib.request.Request("http://localhost:9000/auth/captchaImage")
    with urllib.request.urlopen(req, timeout=5) as r:
        uuid = json.loads(r.read())["data"]["uuid"]
    body = json.dumps({
        "username": "admin", "password": "admin123", "captcha": "1234", "uuid": uuid
    }).encode()
    req = urllib.request.Request("http://localhost:9000/auth/login",
                                  data=body,
                                  headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())["data"]["token"]


async def send_notification(token, user_id, title, content):
    body = json.dumps({
        "userId": user_id, "title": title, "content": content,
        "type": "SYSTEM", "channels": ["inApp"]
    }).encode()
    req = urllib.request.Request("http://localhost:9000/notification/send",
                                  data=body,
                                  headers={"Content-Type": "application/json",
                                           "Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())


async def main():
    print("=== Notification WebSocket 端到端 ===\n")
    token = await get_token()
    print(f"[1] 登录 token len={len(token)}")

    secret = load_internal_call_secret()
    user_id = 1
    uri = "ws://localhost:8084/notification/ws"
    headers = internal_call_headers(user_id, secret)
    print(f"[2] 连接 {uri}")
    async with websockets.connect(
        uri,
        additional_headers=headers,
        ping_interval=None,
        open_timeout=10,
    ) as ws:
        print("    ✓ connected")
        msg = await asyncio.wait_for(ws.recv(), timeout=5)
        print(f"    欢迎消息: {json.loads(msg)}")

        print(f"\n[3] 触发 sendNotification")
        resp = await send_notification(
            token, user_id, "WS 端到端测试",
            f"推送验证 {time.time():.0f}"
        )
        print(f"    send resp: {resp}")

        print(f"\n[4] 等待 WebSocket 推送")
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=15)
            payload = json.loads(msg)
            print(f"    ★ 收到推送:")
            print(f"      {json.dumps(payload, indent=6, ensure_ascii=False)}")
            if payload.get("type") == "notification" and "payload" in payload:
                print(f"\n    推送内容: {payload['payload']}")
        except asyncio.TimeoutError:
            print("    ✗ 15 秒内未收到推送!")
            return False

        print(f"\n[5] ping/pong 心跳测试")
        await ws.send("ping")
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=5)
            print(f"    pong: {json.loads(msg)}")
        except asyncio.TimeoutError:
            print("    ✗ 未收到 pong")
            return False

    print("\n=== ALL PASS ===")
    return True


if __name__ == "__main__":
    sys.exit(0 if asyncio.run(main()) else 1)
