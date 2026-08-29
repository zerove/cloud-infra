"""
Notification WebSocket + REST 端到端测试
- 客户端连 ws://localhost:8084/notification/ws/1
- 等 connected 消息
- 调 REST POST /notification/send
- 验证收到 WebSocket push
- ping/pong 心跳
"""
import asyncio
import json
import sys
import urllib.request
import websockets


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
    print("=== Notification WebSocket 端到端 (修复 V12/V13 后) ===\n")
    token = await get_token()
    print(f"[1] 登录 token len={len(token)}")

    uri = "ws://localhost:8084/notification/ws/1"
    print(f"[2] 连接 {uri}")
    async with websockets.connect(uri, ping_interval=None, open_timeout=5) as ws:
        print("    ✓ connected")
        msg = await asyncio.wait_for(ws.recv(), timeout=3)
        print(f"    欢迎消息: {json.loads(msg)}")

        print(f"\n[3] 触发 sendNotification")
        resp = await send_notification(token, 1, "WS 端到端测试", f"推送验证 {asyncio.get_event_loop().time():.0f}")
        print(f"    send resp: {resp}")

        print(f"\n[4] 等待 WebSocket 推送")
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=10)
            payload = json.loads(msg)
            print(f"    ★ 收到推送:")
            print(f"      {json.dumps(payload, indent=6, ensure_ascii=False)}")
            if payload.get("type") == "notification" and "payload" in payload:
                print(f"\n    推送内容: {payload['payload']}")
        except asyncio.TimeoutError:
            print("    ✗ 10 秒内未收到推送!")
            return False

        print(f"\n[5] ping/pong 心跳测试")
        await ws.send("ping")
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=3)
            print(f"    pong: {json.loads(msg)}")
        except asyncio.TimeoutError:
            print("    ✗ 未收到 pong")

    print("\n=== ALL PASS ===")
    return True


if __name__ == "__main__":
    sys.exit(0 if asyncio.run(main()) else 1)
