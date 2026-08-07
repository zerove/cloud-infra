#!/usr/bin/env python3
"""同步 deploy/config/*.yaml 到 Nacos（等价 up.sh 第 9 步）"""
import os
import sys
import urllib.request
import urllib.parse
import json

CONFIG_DIR = "deploy/config"
NACOS_URL = "http://localhost:8848/nacos/v1/cs/configs"

def publish(data_id, content):
    data = urllib.parse.urlencode({
        "dataId": data_id,
        "group": "DEFAULT_GROUP",
        "content": content,
    }).encode()
    req = urllib.request.Request(NACOS_URL, data=data, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read().decode()
            print(f"  OK  {data_id} -> {resp.status} {body.strip()[:80]}")
    except Exception as e:
        print(f"  ERR {data_id} -> {e}")
        return False
    return True

ok = True
for f in sorted(os.listdir(CONFIG_DIR)):
    if not f.endswith(".yaml"):
        continue
    path = os.path.join(CONFIG_DIR, f)
    with open(path, "r", encoding="utf-8") as fh:
        content = fh.read()
    if not publish(f, content):
        ok = False

sys.exit(0 if ok else 1)