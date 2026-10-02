#!/usr/bin/env python3
"""守护: Nacos 配置"上传成功"不等于"微服务能读到", 中间必须有回读确认。

## 为什么需要这个守护 (2026-10-03)

`test_config_sync_order.py` 守的是"顺序": 上传调用点早于启动调用点。
那个守护在 CI E2E run 37070818024 中**判定为通过**, 上一次也修对了顺序,
但 notification 依然起不来。真实日志:

    22:15:48 WARN  c.a.c.n.c.NacosConfigDataLoader -
               config[dataId=notification-service.yaml, group=DEFAULT_GROUP] is empty
    22:15:48 ERROR Could not resolve placeholder 'spring.datasource.url'

同一批的 gateway/auth/system/file 四个服务都 healthy —— 说明顺序对了、
配置最终也确实上传成功了。差别只在 **notification 读得早**。

Spring Cloud Alibaba 的 NacosConfigDataLoader 在启动期只拉一次:
拉到空就认为"该 dataId 不存在", 继续启动, **不会重试**。
于是它带着残缺的环境永久失败, 反复重启也读不到。

所以真正的门禁不是"顺序对了", 而是"**读得到**"。
本守护要求: 上传之后、启动之前, 必须有一次针对每个 dataId 的
非空回读验证, 且读不到时要 exit 而不是硬着头皮启动。

## 为什么这次不该再靠"顺序守护"了

上一版守护证明了顺序正确, 缺陷依然命中。这本身就是对"该守护够不够"
的负向信号: 它守的是一个**必要条件**, 但代码只满足了必要条件就宣称完成。
本守护盯的是**充分性**: 不只看有没有调上传, 而看有没有验证"真的能读到"。

## 判定方式

静态检查 up.sh 中回读验证块的存在性与语义:
  1. 存在对每个配置文件的回读 (curl + 非空判断);
  2. 回读发生在配置上传之后、微服务启动之前;
  3. 回读最终失败时 `exit` 而非继续启动 (fail-closed)。

## 负向对照

  a. 删掉整个回读验证块            -> 必须失败
  b. 回读失败时不 exit, 继续启动   -> 必须失败
  c. 回读块被移到微服务启动之后     -> 必须失败
"""
import re
import sys
from pathlib import Path

UP_SH = Path(__file__).resolve().parents[1] / "up.sh"

MICROSERVICES = ("gateway", "auth", "system", "file", "notification", "search")


def strip_noise(src: str) -> list[tuple[int, str]]:
    """返回 (行号, 代码), 去掉纯注释行与行内注释。

    ⚠️ 不要像别的守护那样把字符串字面值替换成 "" ——
    本守护要匹配的正是 URL 和 "$file" 这些字面值本身。
    上一版正是这么写的, 结果守护把自己要守的东西也擦掉了,
    对真实 up.sh 判红 (实测: 找不到上传点与回读点)。
    """
    out = []
    for lineno, raw in enumerate(src.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        line = re.sub(r"#(?![^\"']*[\"'])[^\"']*$", "", line)
        out.append((lineno, line))
    return out


def main() -> int:
    src = UP_SH.read_text(encoding="utf-8")
    code = strip_noise(src)
    failures: list[str] = []

    # --- 以主流程锚点切窗, 只在主流程里定位 -------------------------------
    #
    # ⚠️ 不能取"文件里第一个出现的位置": up.sh 里 SYNC_ONLY 分支 (行 ~71) 与
    # 辅助函数 (~293) 都有同样的调用点, 按首次出现定位会取到分支里的位置,
    # 把正确的实现判成错误 (实测: 报"上传(行293) < 回读(行239)"。
    anchor = next(
        (n for n, l in code if "3b. 同步配置到 Nacos" in l), None
    )
    if anchor is None:
        print("FAIL 未找到主流程锚点 (3b. 同步配置到 Nacos)")
        return 1
    code = [(n, l) for n, l in code if n >= anchor]

    upload_at = None       # 配置上传调用点
    readback_at = None     # 回读验证位置
    startup_at = None      # 微服务启动调用点

    for lineno, line in code:
        if upload_at is None and re.search(r"nacos_config_upload\s+\"\$file\"", line):
            upload_at = lineno
        if startup_at is None and re.search(r"compose_up_args", line):
            startup_at = lineno
        if readback_at is None and re.search(r"/nacos/v3/client/cs/config\?", line):
            if re.search(r"curl", line):
                readback_at = lineno

    if upload_at is None:
        failures.append("未找到配置上传调用点 (nacos_config_upload \"$file\")")
    if startup_at is None:
        failures.append("未找到微服务启动调用点 (compose_up_args)")
    if readback_at is None:
        failures.append(
            "未找到配置回读验证: 缺少针对 /nacos/v3/client/cs/config? 的 curl 回读。"
            "上传成功≠服务能读到, CI run 37070818024 的 notification 就是这么挂的。"
        )
    else:
        # --- 顺序: 上传 < 回读 < 启动 ----------------------------------------
        if not (upload_at < readback_at < startup_at):
            failures.append(
                f"顺序错误: 上传(行{upload_at}) < 回读(行{readback_at}) < 启动(行{startup_at}) 不成立"
            )

        # --- 回读必须校验"非空", 不能只看 HTTP 状态 -------------------------
        # ⚠️ 这里必须用窗口索引, 不能用 readback_at - 1 当下标 ——
        # code 已按主流程锚点切窗, 下标与源文件行号不再对齐,
        # 上一版正是这么写的, 取到的片段完全不含目标行。
        readback_idx = next(
            i for i, (n, _) in enumerate(code) if n == readback_at
        )
        tail = "\n".join(l for _, l in code[readback_idx:])
        # ⚠️ 正则里 $ 前面的反斜杠数量很敏感: r'"\$content"' 匹配正常,
        # 而 r'"\\$content"' 要求字面反斜杠, 不匹配。写错会静默地永远 False。
        if not re.search(r'\[ -n "\$content" \]', tail):
            failures.append(
                "回读没有校验内容非空: 缺少 [ -n \"$content\" ]。"
                "只判 HTTP 2xx 挡不住 dataId 不存在时返回空 body 的情况。"
            )

        # --- 回读失败必须 fail-closed ---------------------------------------
        #
        # ⚠️ 不能简单地在附近窗口里搜 "exit 1": up.sh 前面已有 SYNC_ONLY
        # 分支与 nacos_config_upload() 两个无关的 exit 1, 搜关键字会撞上它们,
        # 把"删掉了 fail-closed"的实现也判成通过 (实测过, 假绿)。
        #
        # 真正的判据是**结构**: CONFIG_READY 判真之后, 必须紧跟一个
        # else 分支, 该分支里含有 exit。少了 else 就是放行, 那正是回归。
        after = code[readback_idx:]
        window = "\n".join(l for _, l in after[:60])
        if not re.search(r"CONFIG_READY\s*=\s*1", window):
            failures.append("回读块缺少 CONFIG_READY 判定")
        # ⚠️ 必须锚定在 if 判定那行 `if [ "$CONFIG_READY" -eq 1 ]`,
        # 不能取"首个 CONFIG_READY=1": 循环体内也有赋值 (CONFIG_READY=0/1),
        # 取首个会落到循环里, 后面的窗口根本不含 else/exit, 于是永远假绿。
        cond = re.compile(r"""if\s+\[\s*"?\$\{?CONFIG_READY\}?"?\s+-eq\s+1\s+\]""")
        ready_pos = next(
            (i for i, (n, l) in enumerate(after) if cond.search(l)), None
        )
        if ready_pos is None:
            failures.append(
                "回读块缺少 CONFIG_READY 的 if 判定 (找不到 if [ \"$CONFIG_READY\" -eq 1 ])"
            )
        else:
                # ⚠️ 窗口必须止于匹配的 fi, 不能用固定行数。
                # 实测: 取 +30 行会跨过本块的 fi, 抓到后面微服务启动段
                # 自己的 exit 1, 于是"删掉 fail-closed"也判成通过 (假绿)。
                depth = 0
                else_body: list[str] = []
                for _, l in after[ready_pos + 1:]:
                    if l == "else":
                        depth = 1
                        continue
                    if depth:
                        if l == "fi":
                            break
                        else_body.append(l)
                if not else_body:
                    failures.append("回读判定的 else 分支为空")
                elif not any(re.match(r"exit 1", l) for l in else_body):
                    failures.append(
                        "回读失败的 else 分支里没有 exit 1: 带着空配置启动服务。"
                    )

    # --- 反向: 不得出现"回读失败也照样启动"的兜底 ---------------------------
    # (已由上面的结构化 else/exit 检查覆盖, 此处不再重复判定)

    for f in failures:
        print(f"FAIL {f}")
    if failures:
        return 1

    print(
        f"PASS 配置可读性回读守护: 上传(行{upload_at}) < 回读(行{readback_at}) "
        f"< 启动(行{startup_at}), 且回读失败会 exit"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())