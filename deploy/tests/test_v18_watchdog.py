#!/usr/bin/env python3
"""test_v18_migration.sh 的看门狗守护测试。

为什么需要它: 看门狗本身是**保险丝**——平时不动作, 所以
"它没被调用过"和"它坏了"在正常运行中**完全一样**(第三课)。
本测试用**受控对照**证明它真的能终止挂死的脚本。

⚠️ 三条自伤陷阱, 全部是本项目踩过的(AGENTS.md 第九课):
  1. `pgrep -f wd_test.sh` 会**匹配到自己**的 `bash -c` 包装 ——
     报"进程还在", 而实际已死。判据必须用 **PGID 组内 ps**。
  2. 判据写成 `PGID == SID` 会**恒真** —— 普通 bash 也满足那个等式。
     正确判据是 `PGID == PID`。
  3. 测试**绝不能**改写仓库里的真脚本 —— 必须复制到临时目录再改。

退出码: 0=全部通过, 1=有断言失败
"""
import os
import re
import shutil
import subprocess
import sys
import glob
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
TARGET = os.path.join(REPO, "deploy", "tests", "test_v18_migration.sh")

# 与被测脚本里的三个装置函数一一对应(只改装置, 不动看门狗逻辑)
# ⚠️ 第三条锚的是 `x()`, 而它的原文**已经变过两次**:
#      timeout 90 podman exec ...            (初版)
#      timeout "${X_TIMEOUT:-90}" podman ...  (加了可覆盖时限)
#   若写死字面量, 每次重构都会让 `test_repo_script_untouched` 假红 ——
#   而那条用例的本意是"测试没把装置替换落到真脚本上", 与 x() 长什么样无关。
#   ⇒ 正解: 用**结构化**的正则锚, 匹配"函数名 + 其体内的 podman exec",
#      而不是匹配整行文本。
DEVICE_SUBSTITUTIONS = [
    (r'(?m)^x\(\)\s*\{\s*timeout\s+"\$\{X_TIMEOUT:-90\}"\s+podman exec cloud-postgres psql -U postgres -d postgres -q -tA -c "\$1" 2>&1;\s*\}',
     'x() { sleep 300 2>&1; }'),
    ('timeout 90 podman exec -i cloud-postgres psql -U postgres -d "$1" -v ON_ERROR_STOP=1 -q -tA 2>&1',
     'cat >/dev/null; sleep 300'),
    ('timeout 90 podman exec cloud-postgres psql -U postgres -d "$1" -tA -c "$2" 2>&1',
     'sleep 300'),
]


_PROGRESS_GLOB = "v18-progress.*"
# 全量泄漏守护的递归闸门(见 test_every_case_leaves_no_progress_file_behind)
_RECURSE_GUARD = "V18_LEAK_SWEEP_CHILD"


def snapshot_progress_files():
    """当前所有进度文件的快照(用于算差集)。"""
    return set(glob.glob(os.path.join(tempfile.gettempdir(), _PROGRESS_GLOB)))


def remove_new_progress_files(before):
    """删掉 `before` 之后新出现的进度文件, 返回删掉的数量。

    ⚠️ 为什么必须是**装置自己的责任**, 而不是脚本的:
    `test_watchdog_is_outside_target_process_group` 用 SIGKILL 杀主进程
    —— 而 **SIGKILL 不可捕获**, 于是脚本内的 `cleanup` 与看门狗兜底
    全都救不了。实测每次该用例稳定泄漏 1 个文件, 等 15 秒也不消失。

    ⇒ 规则: **谁创建谁删除**。装置必须自己收拾(第八课补记一第 5 条)。

    ⚠️ 必须删"差集"而不是"全部": 同一台机器上可能有别的进程在用同名文件,
    删掉别人的东西是不可逆的误伤(与"不擅自终止他人进程"同源)。
    """
    n = 0
    for f in snapshot_progress_files() - before:
        try:
            os.unlink(f)
            n += 1
        except OSError:
            pass
    return n


def _is_regex_anchor(old):
    """锚是否为正则(而非普通文本)。

    ⚠️ 判据形式本身也是探针: 用 `startswith("(?")` 去猜, 猜错就静默走错分支
    (走错时 `re.subn` 会把字面量当正则, 或 `assertIn` 永远找不到)。
    ⇒ 显式判定并要求两种锚都能被正确处理。
    """
    return bool(re.match(r"^\(\?[a-zA-Z]", old))


def make_hung_copy(tmpdir):
    """复制脚本并把装置换成 sleep —— 得到一个**必定挂死**的替身。

    ⚠️ 必须复制而不是就地改: 仓库里的真脚本若被改坏且未还原,
    下一个用例就会跑在被污染的内容上(AGENTS.md 第八课补记一第 5 条)。
    """
    dst = os.path.join(tmpdir, "wd_hung.sh")
    shutil.copy(TARGET, dst)
    with open(dst, encoding="utf-8") as f:
        src = f.read()
    for old, new in DEVICE_SUBSTITUTIONS:
        if _is_regex_anchor(old):
            src, n = re.subn(old, new, src)
            if n == 0:
                raise AssertionError(
                    "正则锚未命中真脚本 —— 装置未替换, 本用例会跑在真库上!\n锚: %s"
                    % old[:90])
        else:
            if old not in src:
                raise AssertionError(
                    "字面量锚未命中真脚本 —— 装置未替换, 本用例会跑在真库上!\n锚: %s"
                    % old[:90])
            src = src.replace(old, new)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(src)
    return dst


def run_hung(script, watchdog_secs, wait_secs):
    """以 setsid 起独立进程组运行挂死替身, 返回 (进程, 日志路径)。

    ⚠️ **不要用 Popen 的 stdout 管道**: setsid 会让子进程脱离,
    `p.stdout.read()` 在它仍存活时**立即返回空串** —— 于是
    "日志没写" 与 "读取方式错了" 产出同一个结果(第五课补记一)。
    实测: 首次运行两个用例都报 "输出为空", 而日志里其实有完整内容。
    正解: 落盘到文件, 事后按路径读 —— 读取方式与进程存活状态无关。
    """
    logpath = os.path.join(os.path.dirname(script), "hung.out")
    env = dict(os.environ)
    env["V18_WATCHDOG_SECS"] = str(watchdog_secs)
    env["V18_REPO_ROOT"] = REPO          # 关键: 否则副本在 tmp 下 ROOT 推导失败
    logf = open(logpath, "wb")
    try:
        p = subprocess.Popen(
            ["setsid", "bash", script],
            stdout=logf, stderr=subprocess.STDOUT,
            env=env, cwd=REPO,
        )
    finally:
        logf.close()
    time.sleep(wait_secs)
    return p, logpath


def read_log(logpath):
    """读日志; 分清「文件不存在」与「读到了空」。"""
    if not os.path.exists(logpath):
        return None
    with open(logpath, encoding="utf-8", errors="replace") as f:
        return f.read()


def wait_for_log(logpath, needle, timeout):
    """轮询日志直到出现 needle, 返回日志内容; 超时返回当前内容。

    ⚠️ 固定 sleep 在高负载下会给出**假失败** —— 本机 load 20+/12 核时,
    看门狗可能 30 秒后才触发, 而测试在第 30 秒整就断言了。
    判据应给**可观测条件**, 不是给经验时长。
    """
    deadline = time.time() + timeout
    out = read_log(logpath)
    while time.time() < deadline:
        if out and needle in out:
            return out
        time.sleep(2)
        out = read_log(logpath)
    return out


def pgid_from_log(logpath):
    """从看门狗自己的输出里读回 PGID。

    ⚠️ **不能用** `ps -o pgid= -p <Popen.pid>`:
    `setsid` 的行为是 —— 若调用者已是组长, 它 **fork 后父进程立即退出**。
    于是 `Popen.pid` 指向的是那个**已退出的短命 setsid 进程**,
    不是实际运行的脚本。实测:
        Popen 视角 pid=2743825 (已死)  pgid=2743825   ← 垃圾值
        实际脚本 pid=2743815            pgid=2743815   ← 真的
    拿垃圾 PGID 去查存活, 结果是「碰巧命中别的进程组」, 于是永远报活着。

    正解: 看门狗启动时**自己会打印**它看到的 PGID, 从那里读回去 ——
    判据指向被检对象自己报告的值, 而不是外部重新推导。
    """
    out = read_log(logpath) or ""
    m = re.search(r"PGID=(\d+)", out)
    return m.group(1) if m else None


def pgid_alive(pgid):
    """该进程组内是否还有任何**存活**进程。

    ⚠️ **不要用** `pgrep -f <脚本名>` —— 它会匹配到调用方自己的
    `bash -c ...wd_hung.sh...` 包装, 于是**永远报活着**(假阳性)。
    必须按 PGID 精确查。

    ⚠️ 必须**忽略僵尸**(Z 状态): `setsid` fork 出真脚本后自己退出且
    无人 wait, 于是一个 `Zs` 永远挂在目标组里。僵尸**已经死了**,
    只是等父进程回收; 若把它算作"存活", 就会得到与被测逻辑完全同形
    的假失败 —— 第一次就是这么被骗的: 判红说"仍有进程存活",
    而 `ps` 快照显示组内**只有** `[bash] <defunct>`。
    ⇒ 探针与被测对象必须用**同一条**存活判据, 否则判的是探针(第五课)。
    """
    if not pgid or not pgid.isdigit():
        return None  # 拿不到 -> 交给调用方区分
    r = subprocess.run(["ps", "-eo", "pid,pgid,stat"],
                       capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        return None  # 探针失效, 不能当成"没有活进程"
    for line in r.stdout.splitlines()[1:]:
        parts = line.split(None, 2)
        if len(parts) >= 3 and parts[1].strip() == pgid:
            if not parts[2].strip().startswith("Z"):
                return True
    return False


class TestWatchdog(unittest.TestCase):

    def test_watchdog_kills_hung_script(self):
        """核心对照: 挂死的脚本必须被看门狗终止, 且**不残留**。

        这是唯一能证明"看门狗真的在跑"的实验 —— 正向跑一遍不算。

        ⚠️ 必须**轮询**而不是固定 sleep: 本机 load 常在 20+/12 核,
        进程调度延迟很大。固定 `sleep 30` 曾在看门狗还在 10 秒宽限期内
        就断言, 得到假失败(实测: 单独跑 30s 时组内已空, 测试里却报存活)。
        ⇒ 判据要给**可观测条件**, 而不是给一个经验时长。
        """
        with tempfile.TemporaryDirectory(prefix="v18wd-test-") as tmp:
            script = make_hung_copy(tmp)
            p, logpath = run_hung(script, watchdog_secs=8, wait_secs=0)

            out = wait_for_log(logpath, "看门狗触发", timeout=90)
            self.assertIsNotNone(out, "日志文件不存在 —— 探针失效, 非被测失败")
            self.assertTrue(out, "看门狗没有任何输出 —— 它可能压根没启动")
            self.assertIn("看门狗已启动", out,
                          "未见启动声明; 输出:\n%s" % out)
            # 超时报告必须指出**停在哪一步**, 否则又是一次"看起来正常的失败"
            self.assertIn("停止在:", out, "超时报告未说明卡在哪一步:\n%s" % out)

            pgid = pgid_from_log(logpath)
            self.assertIsNotNone(pgid, "日志里读不到 PGID, 无法判定残留:\n%s" % out)

            # 等到**真的**没有进程为止, 上限 90s
            deadline = time.time() + 90
            alive = None
            while time.time() < deadline:
                alive = pgid_alive(pgid)
                if alive is False:
                    break
                time.sleep(3)
            self.assertIsNotNone(alive, "拿不到 PGID, 无法判定残留")
            self.assertFalse(alive,
                             "看门狗报告已触发, 但 90s 后进程组 %s 内仍有进程存活" % pgid)
            if p.poll() is None:
                p.kill()

    def _run_case_killing_main_with_sigkill(self, kill_watchdog=True):
        """复现"主进程被 SIGKILL, 看门狗独自收尾"这条真实故障路径。

        ⚠️ 装置必须自己收掉看门狗: 它是 setsid 起在另一会话里的**孤儿**
        (PPID=1), `p.kill()` 只杀目标组组长。若留着它:
          - 它继续跑自己的倒计时;
          - 而 `with` 一退出 TemporaryDirectory 就删了脚本, 它随后报错,
            **永远走不到兜底 rm**。
        实测: 不这么做, 每次稳定泄漏 1 个进度文件, 等 15 秒也不消失。
        ⇒ 顺序不能反: 先杀看门狗(用**日志里它自己报告的** PGID), 再退出 with。

        ⚠️ 用它报告的 PGID 而不是目标 PGID: `kill -- -<目标PGID>` 恰好
        **不含**看门狗(这正是被守护的性质), 用错会让"清理"静默变成空操作。

        ⚠️ `kill_watchdog=False` 用于"守护看门狗自己会清理"的用例:
        若连看门狗一起 SIGKILL, 那被守护的兜底 `rm` 也跑不到 ——
        **前提自相矛盾**, 判据必然红, 且红得毫无信息量。
        实测: 两种变体对照(杀=红 / 不杀=绿)正是定位到这一点的手段。
        """
        with tempfile.TemporaryDirectory(prefix="v18wd-killmain-") as tmp:
            script = make_hung_copy(tmp)
            p, logpath = run_hung(script, watchdog_secs=8, wait_secs=0)
            out = wait_for_log(logpath, "看门狗已启动", timeout=60)
            self.assertTrue(out and "看门狗已启动" in out,
                            "看门狗未报告启动:\n%s" % out)
            target = pgid_from_log(logpath)
            self.assertIsNotNone(target, "读不到目标 PGID:\n%s" % out)
            m = re.search(r"看门狗自身 PGID=(\d+)", out)
            self.assertIsNotNone(
                m, "日志未报告看门狗自身 PGID(无法判定是否脱离):\n%s" % out)
            self.assertNotEqual(
                m.group(1), target,
                "看门狗 PGID == 目标 PGID (%s) —— 它会杀死自己" % target)
            wd_pgid = m.group(1)
            if p.poll() is None:
                p.kill()          # SIGKILL: 主进程的 trap cleanup EXIT 跑不到
            if kill_watchdog:
                subprocess.run(["kill", "-KILL", "--", "-%s" % wd_pgid],
                               capture_output=True)
            self._wait_watchdogs_gone(logpath)
            return out

    def test_watchdog_is_outside_target_process_group(self):
        """负向对照: 看门狗必须**不**在目标进程组内。

        ⚠️ 这是本次修复的核心判据。第一版写的是 `( watchdog ) &` ——
        job control 的子 shell 继承父进程 PGID, 于是 `kill -- -PGID`
        **先杀死看门狗自己**, 宽限窗口与 SIGKILL 升级永不执行,
        而真进程永久残留 —— 而看门狗已经打印了"看门狗触发"。

        ⇒ 即"看门狗报告已终止"与"真的已终止"是两件事(第九课)。
        """
        before = snapshot_progress_files()
        try:
            self._run_case_killing_main_with_sigkill()
        finally:
            # ⚠️ 必须 try/finally: 本用例 SIGKILL 主进程(不可捕获) ⇒
            # 脚本内的 cleanup 与看门狗兜底都救不了。谁创建谁删除 ——
            # 装置必须自己收拾(第八课补记一第 5 条)。
            # 之前这行在 `with` 之外且无 finally: 断言一失败就跳过清理,
            # **失败的那一次反而泄漏** —— 即守护只在它不需要时生效。
            remove_new_progress_files(before)

    def test_sigterm_ignoring_process_is_killed(self):
        """负向对照: 无视 SIGTERM 的进程必须被升级到 SIGKILL 清掉。

        为什么需要: 只测"普通挂死进程被清掉"无法证明升级路径存在 ——
        默认 TERM 就够用的话, 删掉 KILL 分支测试仍会绿(第九课)。
        """
        with tempfile.TemporaryDirectory(prefix="v18wd-kill-") as tmp:
            script = make_hung_copy(tmp)
            # 把替身改成**忽略 TERM**, 迫使看门狗必须升级 KILL
            with open(script, "a") as f:
                f.write("\ntrap '' TERM\nsleep 300\n")
            # ⚠️⚠️ 追加在**末尾**的 trap 根本不生效 —— 装置在
            #    `progress "用例1: setup+seed"` 之后的 psql_do 处就挂死,
            #    永远走不到文件末尾, 于是这条用例一直在测"**普通**挂死被清掉",
            #    而它的名字宣称测的是"忽略 TERM 被 SIGKILL 清掉"。
            #    即测试名通过 ≠ 机制被验证(第九课)。
            # ⇒ 正解: 在**第一个挂死点之前**装 trap —— 插到 progress 那一行前。
            self._install_sigterm_ignore_before_hang(script)
            p, logpath = run_hung(script, watchdog_secs=8, wait_secs=0)

            # ⚠️ 必须等"SIGKILL"字样本身, 不能等"看门狗触发" ——
            #    后者在 TERM 发出瞬间就打印, 而升级到 KILL 是**之后**才发生。
            #    实测: 等"看门狗触发"后立刻断言, 会拿到尚无 KILL 行的日志,
            #    于是**正向**假红(第一次跑就这样)。
            # ⇒ 判据必须指向**被断言的那件事**, 而不是它的前因(第五课)。
            out = wait_for_log(logpath, "升级到 SIGKILL", timeout=120)
            self.assertTrue(out and "看门狗触发" in out, "看门狗未触发:\n%s" % out)

            # ⚠️ 这条断言是本用例**唯一**能证明"SIGKILL 分支被走到"的证据。
            #    少了它, 本用例测的其实是"普通挂死被清掉"——
            #    因为装置早就在 psql_do 处挂死, 追加到文件末尾的 trap 永不执行。
            #    实测对照: 旧写法下本用例 19 秒就绿, 但看门狗**从未发过 SIGKILL**;
            #    插到挂死点之前后, 输出出现
            #    `!!! SIGTERM 后 10s 仍在运行, 升级到 SIGKILL`(第九课)。
            self.assertIn(
                "升级到 SIGKILL", out,
                "看门狗未走 SIGKILL 升级路径 —— 顽固装置未被真正安装, "
                "本用例名不副实:\n%s" % out)
            pgid = pgid_from_log(logpath)
            self.assertIsNotNone(pgid, "读不到 PGID:\n%s" % out)

            deadline = time.time() + 90
            alive = None
            while time.time() < deadline:
                alive = pgid_alive(pgid)
                if alive is False:
                    break
                time.sleep(3)
            self.assertIsNotNone(alive, "拿不到 PGID, 无法判定残留")
            self.assertFalse(alive,
                             "忽略 SIGTERM 的进程未被 SIGKILL 清除, 组 %s 仍存活" % pgid)
            if p.poll() is None:
                p.kill()

    def test_zombie_does_not_count_as_alive(self):
        """负向对照: 僵尸**不得**被算作存活进程。

        ⚠️ 上一条用例(`test_sigterm_ignoring_process_is_killed`)其实**不足以**
        守护僵尸过滤: 它无论判据如何都会变绿 —— 因为脚本 trap EXIT 时会
        连坐清掉自己的子进程。实测: 把僵尸过滤删掉后, 7 项仍全绿,
        而看门狗**从未发过 SIGKILL** —— 守护是无效的(第九课)。

        ⇒ 正解: 直接对判据本身做单元级断言, 并**造出真僵尸**:
        fork 一个子进程立刻退出而父进程不 wait, 让它停在 Zs 状态。

        ⚠️ 子进程必须先 `setpgid(0,0)` 成为**自己组的组长**。
        否则它继承父进程 PGID, 于是
          - 僵尸的 PGID = 父的 PGID ≠ 它的 pid
          - 按 `pgid=<僵尸pid>` 查会落到一个**很大的**组里, 命中别人
        实测: 未设 setpgid 时僵尸确实被判"存活" —— 但那是因为查错了组,
        不是判据漏了过滤(第五课第七例: 探针查错对象)。
        """
        pid = os.fork()
        if pid == 0:
            try:
                os.setpgid(0, 0)   # 自己当组长, PGID == pid
            except OSError:
                pass
            os._exit(0)
        # 轮询到它真的变成僵尸。全程**不得**调用 waitpid。
        stat = ""
        pgid = ""
        deadline = time.time() + 10
        while time.time() < deadline:
            out = subprocess.run(["ps", "-o", "pgid=,stat=", "-p", str(pid)],
                                 capture_output=True, text=True, timeout=20)
            fields = out.stdout.split()
            if len(fields) >= 2:
                pgid, stat = fields[0], fields[1]
            if stat.startswith("Z"):
                break
            time.sleep(0.2)
        self.assertTrue(stat.startswith("Z"),
                        "未能造出僵尸进程 (stat=%r) —— 装置失效, 无法判定" % stat)
        self.assertEqual(pgid, str(pid),
                         "僵尸未成为自己组的组长 (pgid=%s pid=%s) —— 装置失效"
                         % (pgid, pid))
        # ⚠️ 断言必须是 `assertFalse`, 不能是 `assertIsNone`。
        #    判据的三态契约是: False=无活进程 / True=有活进程 / None=探针失效。
        #    写成 assertIsNone 会把"探针失效"与"确实没有活进程"当成同一件事 ——
        #    即用一条断言同时接受两种互斥结论, 于是守护形同虚设(第五课)。
        got = pgid_alive(str(pid))
        self.assertNotEqual(got, None,
                            "探针失效(None), 无法判定僵尸 —— 这是装置问题, 不是判据问题")
        self.assertFalse(got,
                         "僵尸 pid=%s 被判为存活 —— 存活判据漏了僵尸过滤" % pid)
        # 收尾: 回收它, 否则会真的留下一个僵尸
        try:
            os.waitpid(pid, 0)
        except ChildProcessError:
            pass

    def test_bash_live_process_probe_classifies_zombie(self):
        """负向对照: bash 侧的 `group_has_live_process` 必须忽略僵尸。

        ⚠️ 这条不可省略: 上一条只守护 Python 探针 `pgid_alive`。
        而**被测脚本**用的是 bash 侧的同名判据 —— 两处是**两份实现**。
        实测: 删掉 bash 侧的僵尸过滤后, 8 项全绿, 因为 Python 探针独立。
        ⇒ “已加了守护”不等于“守护有效”, 差的那一步是**对照打在正确的地方**。

        直接向脚本请求一个**真实僵尸**的判定结果, 不经进程组整体。
        """
        r, w = os.pipe()
        zombie = os.fork()
        if zombie == 0:
            os.close(r)
            try:
                os.setpgid(0, 0)
            except OSError:
                pass
            os._exit(0)
        os.close(w)
        # 等它真的变僵尸, 但**不** wait
        stat = ""
        deadline = time.time() + 10
        while time.time() < deadline:
            out = subprocess.run(["ps", "-o", "stat=", "-p", str(zombie)],
                                 capture_output=True, text=True, timeout=20)
            stat = out.stdout.strip()
            if stat.startswith("Z"):
                break
            time.sleep(0.2)
        try:
            self.assertTrue(stat.startswith("Z"),
                            "未能造出僵尸 (stat=%r) —— 装置失效" % stat)
            # 让脚本自己去判。--watchdog-child 会在判完后杀掉该组,
            # 所以这里用一个独立 PGID(僵尸自己的组), 不会碰到测试进程。
            p = subprocess.run(
                ["bash", "-c",
                 'source "%s" --probe-only 2>/dev/null; '
                 'group_has_live_process %s; echo "rc=$?"' % (TARGET, zombie)],
                capture_output=True, text=True, timeout=60)
            self.assertIn("rc=", p.stdout,
                          "脚本未执行判据(输出=%r) —— 装置失效" % p.stdout[:200])
            rc = int(p.stdout.strip().split("rc=")[-1])
            self.assertNotEqual(rc, 2,
                                "判据自认失效(rc=2) —— 无法判定, 不是'无活进程'")
            self.assertEqual(rc, 0,
                             "僵尸组 %d 被判为仍有活进程(rc=%d) —— "
                             "bash 侧漏了僵尸过滤" % (zombie, rc))
        finally:
            try:
                os.waitpid(zombie, 0)
            except ChildProcessError:
                pass
            os.close(r)

    def test_no_timeout_wrapping_shell_function(self):
        """负向对照: 不得出现 `timeout N <本脚本定义的函数名>`。

        ⚠️ `timeout` 是外部命令, 只能执行可执行文件。受控对照:
              f() { echo hi; }
              timeout 2 f   ->  "failed to run command 'f': No such file
                                 or directory",  rc=127
          而原代码尾部有 `|| true`, 于是 127 被吞掉, **清理静默跳过**,
          测试库残留而日志与退出码都看不出异常。

        ⇒ 同时**正向验证** drop_db 确实可被限时调用(改完真的能用)。
        """
        with open(TARGET, encoding="utf-8") as f:
            src = f.read()
        # ⚠️ 必须先**剥掉注释**再扫。污染源包括:
        #   - 解释这个反模式的注释本身(本项目已实测: 注释里的
        #     `timeout 15 drop_db` 被扫成真代码, 使正向对照假红)
        #   - 行尾注释
        # 这是 AGENTS.md 第五课补记一记录的"判据字面量出现在断言附近"同型,
        # 且实测过 6 次 —— 这里用“按行剥离”而不是正则全文件删, 因为
        # 正则无法区分"字符串里的 #"与"注释的 #"。
        code_lines = []
        for line in src.splitlines():
            stripped = line.lstrip()
            if stripped.startswith("#"):
                continue           # 整行注释
            # 去掉行尾注释: 只在 # 不处于引号内时截断
            out, in_s, in_d, i = [], False, False, 0
            while i < len(line):
                ch = line[i]
                if ch == "'" and not in_d:
                    in_s = not in_s
                elif ch == '"' and not in_s:
                    in_d = not in_d
                elif ch == "#" and not in_s and not in_d:
                    break
                out.append(ch)
                i += 1
            code_lines.append("".join(out))
        code = "\n".join(code_lines)
        # 取出本脚本定义的函数名, 再找有没有被 timeout 包住
        defined = set(re.findall(r"^([a-z_][a-z0-9_]*)\(\)\s*\{", code, re.M))
        self.assertTrue(defined, "未能解析出函数定义 —— 探针失效")
        bad = re.findall(r"\btimeout\s+\d+\s+([a-z_][a-z0-9_]*)", code)
        offenders = sorted(set(b for b in bad if b in defined))
        self.assertEqual(offenders, [],
                         "这些 shell 函数被外部 timeout 包裹(rc=127, 清理静默跳过): %s"
                         % offenders)
        # 正向: drop_db 必须接受时限参数且真的限制内部往返
        self.assertIn('X_TIMEOUT', src, "x() 未支持可覆盖时限")
        self.assertRegex(src, r'drop_db\s+"\$\{DROP_DB_TIMEOUT',
                         "cleanup 未把时限传给 drop_db")

    def test_progress_marker_names_the_case(self):
        """进度标记必须能区分是**哪个用例**卡住。

        只报"挂死了"而不报"卡在哪"的话, 下一个人还得从头查一遍 ——
        而这次的教训恰恰是"卡在用例 2"这个信息最值钱。
        """
        with tempfile.TemporaryDirectory(prefix="v18wd-test-") as tmp:
            script = make_hung_copy(tmp)
            p, logpath = run_hung(script, watchdog_secs=8, wait_secs=0)
            out = wait_for_log(logpath, "看门狗触发", timeout=90) or ""
            self.assertIn("用例1", out, "进度标记未指出用例:\n%s" % out)
            if p.poll() is None:
                p.kill()

    def test_repo_root_env_is_honoured(self):
        """V18_REPO_ROOT 必须真的被采用。

        ⚠️ 这条来自一次**实测失败**: 复制到 /tmp 后 ROOT 推导成空串,
        报 "FAIL: 缺少 //cloud-infra-modules/..." —— 看着像迁移文件缺失,
        实际是路径错(第九课: 探针给出的红不是被测对象有缺陷)。
        """
        self.assertIn("V18_REPO_ROOT", open(TARGET, encoding="utf-8").read())


    def test_watchdog_diagnosis_is_time_bounded_and_after_kill(self):
        """看门狗的诊断步骤不得位于 kill **之前**, 且必须有独立时限。

        本项目最讽刺的一个坑, 且**此前无任何守护覆盖**:

        看门狗原本先做"残留会话"查询(podman exec psql)再 kill。
        而本机单次 podman exec 实测需 9~17 秒, 宿主负载高时远超 ——
        于是看门狗超时后先卡在 podman 上, **永远走不到 kill**。

        实测形态极具误导性: 输出里「看门狗已触发」照常打印,
        35 秒后目标组内 bash + sleep 300 仍存活。即
        「看门狗已触发」为真, 「看门狗会终止」为假。

        两条断言都要:
          1. kill 必须出现在诊断(podman exec)之前 —— 顺序即安全性;
          2. 诊断必须被 timeout 包裹 —— 有了超时, 位置错了也只是慢, 不会死。

        ⚠️ 只断言其一不够: 去掉 timeout 后 kill 仍在诊断之前, 仍会挂死。
        """
        src = open(TARGET, encoding="utf-8").read()
        body = src[src.index("watchdog() {"):]
        body = body[:body.index("\n}\n")]
        lines = [ln.strip() for ln in body.splitlines() if ln.strip()]
        kill_idx = [i for i, ln in enumerate(lines)
                    if re.match(r'kill\s+-(TERM|KILL)\s+--\s+"-?\$', ln)]
        diag_idx = [i for i, ln in enumerate(lines)
                    if "podman exec" in ln and not ln.startswith("#")]
        self.assertTrue(kill_idx, "看门狗里找不到 kill 调用")
        self.assertTrue(diag_idx, "看门狗里找不到诊断用的 podman exec")
        self.assertLess(
            max(kill_idx), min(diag_idx),
            "诊断在 kill 之前 —— 看门狗会卡在诊断上, 永远不执行终止")
        for ln in lines:
            if "podman exec" in ln and not ln.startswith("#"):
                self.assertTrue(
                    ln.startswith("timeout ") or ln.rstrip(" \\").endswith("\\"),
                    "看门狗里的 podman exec 没有时限(可悬挂): %s" % ln)
        self.assertIn("尽力而为", body, "诊断未标注尽力而为")

    def test_drop_db_timeout_applies_to_first_query(self):
        """cleanup 的**第一次**查询也必须用短时限。

        ⚠️ 实测缺陷: 时限曾写成"先 db_count 再 X_TIMEOUT=15",
        于是第一次查询仍走默认 90 秒 —— 而 cleanup 正是看门狗触发后
        最需要快速退出的那条路径(每次 pg 往返 9~17 秒)。
        即参数**看起来已传入**, 实际对首次调用无效。

        ⇒ 断言 `X_TIMEOUT` 的赋值必须出现在 `db_count` **之前**。
        """
        src = open(TARGET, encoding="utf-8").read()
        body = src[src.index("drop_db() {"):]
        body = body[:body.index("\n}\n")]
        m = re.search(r'local X_TIMEOUT="\$\{1:-', body)
        self.assertIsNotNone(
            m, "drop_db 入口未用局部 X_TIMEOUT —— 参数可能对首次查询无效")
        # ⚠️ 判据必须匹配**调用形态**, 不能用 `body.index("db_count")` ——
        #    那样会先命中注释里的那个词(位置 69), 于是真实现实有效的
        #    写法被判成"赋值在调用之后"。这正是第五课第七例:
        #    探针查错了对象, 结论与事实相反。
        first_call = re.search(r'^\s*\[?\s*"\$\(db_count\)"', body, re.M)
        self.assertIsNotNone(
            first_call, "未找到 drop_db 内的 db_count 调用 —— 判据可能已失效")
        self.assertLess(
            m.start(), first_call.start(),
            "X_TIMEOUT 赋值在首次 db_count 调用之后 —— 首次查询仍走默认时限")
        self.assertNotIn(
            'if [ -n "${1:-}" ]; then X_TIMEOUT', body,
            "仍是「事后才设 X_TIMEOUT」的老写法")



    @staticmethod
    def _install_sigterm_ignore_before_hang(script):
        """把装置换成**自身忽略 SIGTERM** 的顽固进程。

        ⚠️⚠️ 这个装置被重构过两次, 每次都**测不到它声称要测的东西**:

        第 1 版: 在文件**末尾**追加 `trap '' TERM; sleep 300`。
          装置早就在 psql_do 处挂死, 永远走不到末尾 ⇒ trap 未安装。
          决定性证据: 该写法下输出为 `已清理干净`(走 TERM 路径),
          而**从未**出现 `升级到 SIGKILL`。

        第 2 版: 把 `trap '' TERM` 插到第一个挂死点**之前**。
          看似修好了, 但实测**不装 trap 也一样**出现 `升级到 SIGKILL` ——
          因为 `sleep 300` 是**独立子进程**: bash 被 TERM 后退出,
          组内仍有活着的 sleep, 于是看门狗必然升级。
          ⇒ 那个断言**没有区分力**, 而差异只在 10 秒观察窗口内不可见。

        正解: 让**装置的 bash 自身**忽略 TERM, 且装置里不留可被 TERM 杀掉的
        其他进程 —— 这样"是否升级到 KILL"就只取决于 trap 是否安装。

        ⚠️ trap 必须在**第一个挂死点之前**安装, 否则永远走不到(第 1 版的老坑)。
        ⚠️ 插入后必须自检生效, 否则静默无效。
        """
        with open(script, encoding="utf-8") as f:
            src = f.read()
        anchor = 'progress "用例1: setup+seed"'
        assert anchor in src, "锚点未命中真脚本, 装置未替换"
        # 用一个**显式忽略 TERM 的子 shell** 替换挂死装置:
        # 它继承装置的 PGID, 但自身 trap 掉 TERM ⇒ 只有 SIGKILL 能收它。
        src = src.replace("x() { sleep 300 2>&1; }",
                          "x() { trap '' TERM; while true; do sleep 1; done; }", 1)
        if "trap '' TERM; while true" not in src:
            raise AssertionError("顽固装置替换未生效 —— 本用例会跑在普通装置上")
        open(script, "w", encoding="utf-8").write(src)

    def test_watchdog_child_does_not_leak_progress_file(self):
        """看门狗子进程不得创建无人清理的进度文件。

        ⚠️ 泄漏形态: 子进程会**重新执行本脚本**。若 mktemp 在文件头,
        子进程会先建出一个新文件, 直到入口改用父进程传入的路径才把它弃用
        —— 那个文件此后无人删除 ⇒ 每跑一次看门狗就在 /tmp 留一个孤儿。

        为什么必须有这条守护: 泄漏**不影响任何断言**, 所以靠功能测试
        永远发现不了(第九课: 没有守护的"正确"最容易被重构悄悄推翻)。

        ⇒ 实测前后对比 /tmp 下的文件数, 而不是检查源码。
           源码检查只能证明"写法对", 证明不了"没有泄漏"。
        """
        before = snapshot_progress_files()
        with tempfile.TemporaryDirectory(prefix="v18wd-leak-") as tmp:
            script = make_hung_copy(tmp)
            p, logpath = run_hung(script, watchdog_secs=6, wait_secs=0)
            wait_for_log(logpath, "看门狗触发", timeout=90)
            pgid = pgid_from_log(logpath)
            # ⚠️⚠️ 必须等进程组**真正死透**再采样。
            #    实测: 进度文件在 t=2~4s 存在、t=6s 自行消失 ——
            #    那是主流程正常运行时的正常生命周期。若在"看门狗触发"
            #    那一刻就采样, 会把这个**正常存在的**文件当成泄漏,
            #    于是正向假红(实测)。
            #    ⇒ 判据的采样时机必须落在"该发生的清理已完成"之后,
            #    而不是因为"日志里出现了某个词"就动手(第五课)。
            # ⚠️⚠️ 必须等**所有**残留进程退出, 不只是目标进程组。
            #    实测: `test_watchdog_is_outside_target_process_group` 在看门狗
            #    刚启动时就把主进程 SIGKILL 掉, 然后立刻返回 —— 此时看门狗
            #    还在跑(上限 8s), 它才是最后删除进度文件的那个进程。
            #    只等目标组 ⇒ 在看门狗删之前就采样 ⇒ **正向假红**。
            #    ⇒ 判据的采样条件必须覆盖"负责清理的那个进程也已退出"。
            # 等"负责清理的那个进程"也退出 —— 但**不**连带等目标组:
            # 目标组是本用例**故意**杀掉的, 等它等于等一个永远不会发生的状态。
            # 共用实现见 _wait_watchdogs_gone(探针三态在那里统一处理)。
            self._wait_watchdogs_gone(logpath)
        leaked = sorted(snapshot_progress_files() - before)
        self.assertEqual(
            leaked, [],
            "看门狗子进程泄漏了进度文件: %s\n"
            "⇒ mktemp 必须推迟到主流程, 不能在文件头(见 new_progress_file)"
            % leaked)


    def test_drop_db_respects_timeout_at_runtime(self):
        """运行时验证: drop_db 的时限**真的**在限制内部往返(非仅源码看起来对)。

        为什么必须运行时验证: 现有的
        `test_no_timeout_wrapping_shell_function` 与
        `test_drop_db_timeout_applies_to_first_query` 都是**静态源码检查** ——
        能证明写法正确, 证明不了"时限真的生效"。而本项目反复出现的
        正是这一形态: 参数看起来已传入, 实际对首次调用无效。

        装置: 把 x() 里的 podman 换成 `timeout "${X_TIMEOUT:-90}" sleep ...`。
        替身**无论时限如何都会返回**, 所以判据只能用**耗时** ——
        用返回值判会得到"两种写法完全一样"的假绿(实测)。

        ⚠️ 替身必须让 db_count 返回**非 0**, 否则 drop_db 第一行就
        `[ "$(db_count)" = "0" ] && return 0` 提前返回, sleep 根本不执行,
        于是"时限生效"与"时限没生效"耗时完全相同 —— 判据无区分力。
        ⇒ 用一个记住调用次数的替身: 第 1 次返回 1, 之后返回 0。
        """
        with tempfile.TemporaryDirectory(prefix="v18wd-drop-") as tmp:
            # ⚠️ **不能**用 make_hung_copy: 它会把 x() 整个替换成 `sleep 300`,
            #    于是找不到原形态 —— 探针查错了对象(第五课第七例)。
            script = os.path.join(tmp, "drop_probe.sh")
            shutil.copy(TARGET, script)
            with open(script, encoding="utf-8") as f:
                src = f.read()
            # ⚠️ 正则必须吃掉**整行**。此前只匹配到 `podman` 就停,
            #    替换后残留 `cloud-postgres psql ...` 成为语法错误 ——
            #    而 bash 没有 set -e, 语法错误**不会**中断脚本,
            #    于是探针"成功"返回, 判据读到 0.02s 便以为自己通过。
            #    这正是第五课: 探针自身坏掉时伪装成 PASS。
            # ⇒ 替换后必须自检: x() 那一行要以 `}` 结尾且不含 podman 实参。
            x_orig = (r'(?m)^x\(\)\s*\{\s*timeout\s+"\$\{X_TIMEOUT:-90\}"'
                      r'\s+podman exec.*\}$')
            # 第 1 次 db_count 返回 1(库"存在")=> 继续走 drop_db 的真实路径
            src, n = re.subn(
                x_orig,
                'x() { echo x > "$DROPPROBE_COUNT"; timeout "${X_TIMEOUT:-90}" '
                'sleep "${X_TIMEOUT:-90}"; '
                'if [ -s "$DROPPROBE_SEEN" ]; then echo 0; else '
                ': > "$DROPPROBE_SEEN"; echo 1; fi; }',
                src)
            self.assertEqual(n, 1, "x() 装置替换未生效")
            x_line = src.split("x() {", 1)[1].split("\n", 1)[0]
            self.assertNotIn(
                "cloud-postgres", x_line,
                "x() 装置替换后仍残留 podman 实参 —— 探针会静默失效: %s" % x_line)
            with open(script, "w", encoding="utf-8") as f:
                f.write(src)
            # ⚠️ 语法自检: bash -n 不通过就直接判红, 不给它跑到"假成功"的机会
            chk = subprocess.run(["bash", "-n", script],
                                 capture_output=True, text=True)
            self.assertEqual(
                chk.returncode, 0,
                "装置替换后的脚本语法错误(探针会静默失效): %s"
                % (chk.stderr or "").strip()[:200])

            counter = os.path.join(tmp, "count")
            seen = os.path.join(tmp, "seen")
            env = dict(os.environ)
            env["V18_REPO_ROOT"] = REPO
            env["DROP_DB_TIMEOUT"] = "3"
            env["DROPPROBE_COUNT"] = counter
            env["DROPPROBE_SEEN"] = seen
            t0 = time.time()
            p = subprocess.Popen(["bash", script, "--cleanup-probe"],
                                 stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, env=env, cwd=REPO)
            try:
                p.communicate(timeout=180)
            except subprocess.TimeoutExpired:
                p.kill()
                self.fail("drop_db 未受 DROP_DB_TIMEOUT 约束 —— 180s 仍未返回")
            elapsed = time.time() - t0
            # 装置真的被调用过吗? 没有它就谈不上"时限生效"
            self.assertTrue(
                os.path.exists(counter),
                "x() 替身从未被调用 —— drop_db 提前返回了, 时限未被检验")
            self.assertLess(
                elapsed, 60,
                "drop_db 耗时 %.1fs: 时限未生效(默认 90s x 多次往返)" % elapsed)

    def _wait_watchdogs_gone(self, logpath, timeout=120):
        """等本次测试起的看门狗**与**目标组都退出。

        ⚠️ 为什么要等两者: 判据若只等目标组, 就会在"负责删进度文件的那个
        进程(看门狗)还在跑"时提前采样 ⇒ **正向假红**。
        ⚠️ 为什么不能只等看门狗: 看门狗退出后才轮到主进程真正结束。
        ⇒ 两者的交集才是"一切收干净"。
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            alive = self._residual_watchdogs(logpath)
            if alive is None:
                # ⚠️ 探针失效**不消耗**等待预算, 但也**不能**当成"已退出"。
                #    判据失效 ⇒ 这次等待的结论 UNDETERMINED ⇒ 由调用方判红。
                raise AssertionError(
                    "探针(ps)不可用 ⇒ 无法判定看门狗是否已退出。"
                    "必须记为 UNDETERMINED, 不能当'已清理'。"
                    "(第八课补记一: 探针把'取不到值'当成'取到假值')")
            if alive:
                time.sleep(2)
                continue
            time.sleep(3)   # 给文件系统删除留出可见性延迟
            again = self._residual_watchdogs(logpath)
            if again is None:
                raise AssertionError("探针(ps)在复核时不可用 ⇒ UNDETERMINED")
            if not again:
                return True
            time.sleep(2)
        return False

    @staticmethod
    def _residual_watchdogs(logpath):
        """是否还有本测试起的看门狗子进程存活。

        ⚠️ 判据形态: 看门狗以 `--watchdog-child` 重新执行脚本,
        它的进程组组长是**自己**(setsid)。而 `pgid_alive` 读的是
        日志里的"目标 PGID", 那不含看门狗 ⇒ 只等它会漏。
        ⚠️ 也不能用 `pgrep -f`: 它会**匹配到自己**的调用包装,
         报"还在"而实际已死(本文件开头列的三条自伤陷阱之一)。
        ⇒ 只认"命令行里含 --watchdog-child"且**不在**本次测试 PID 下的进程。

        ⚠️⚠️ 探针失效必须**红灯**, 不能返回 False。
        旧实现 `except: return False`(探针坏了 → 当成"已退出"),
        于是探针超时会让判据**假绿** —— 正是第八课补记一那条:
        "探针把'取不到值'当成'取到假值'"。
        这里改为 `None`(第三态), 由调用方显式判红。
        """
        try:
            res = subprocess.run(
                ["ps", "-eo", "pid=,args="],
                capture_output=True, text=True, timeout=30)
        except (subprocess.TimeoutExpired, OSError):
            return None          # ← 探针不可用(第三态, 不等于"已退出")
        if res.returncode != 0:
            return None          # ps 自身失败 ⇒ 同样不可用
        out = res.stdout or ""
        if not out.strip():
            return None          # 空输出可疑(与 2026-10-05 那次同形)
        me = os.getpid()
        for line in out.splitlines():
            line = line.strip()
            if not line or "--watchdog-child" not in line:
                continue
            parts = line.split(None, 1)
            try:
                pid = int(parts[0])
            except (ValueError, IndexError):
                continue
            if pid == me:
                continue
            if "ps -eo" in line:
                continue
            return True
        return False


    def test_every_case_leaves_no_progress_file_behind(self):
        """全量用例跑完后**一个**进度文件都不该新增。

        ⚠️ 为什么需要这条: 现有的泄漏守护只跑**一个**用例
        (test_watchdog_kills_hung_script), 于是它对**其他**用例的泄漏是盲的。
        实测: 逐用例比对后发现 3 个用例各泄漏 1 个 ——
        其中两个走探针路径(不装 trap cleanup EXIT), 第三个用 SIGKILL
        杀主进程(SIGKILL 不可捕获, 脚本内清理救不了)。
        而那次守护**全绿**, 因为它只测了一个没问题的用例。

        ⇒ 这正是第九课补记八形态 3 的升级: 泄漏守护本身也会**因为覆盖面太窄**
        而失效, 且失效形态同样是全绿。

        ⚠️ 判据必须只删「本次新增的差集」, 不能删全部 ——
        同机可能有别的进程在用同名文件, 误删是不可逆的。
        """
        # ⚠️⚠️ 必须有递归闸门: 本用例会**再跑一次全量**, 若不加限制,
        #    子进程里又包含本用例 ⇒ 无限递归。
        #    闸门用环境变量而非命令行参数 —— 参数会被 `python3 file.py <arg>`
        #    解释成测试选择器, 而环境变量不会。
        if os.environ.get(_RECURSE_GUARD):
            self.skipTest("已在全量子进程中运行过(递归闸门)")
        env = dict(os.environ)
        env[_RECURSE_GUARD] = "1"
        before = snapshot_progress_files()
        try:
            p = subprocess.Popen(
                [sys.executable, os.path.abspath(__file__)],
                stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
                env=env)
            try:
                p.communicate(timeout=900)
            except subprocess.TimeoutExpired:
                p.kill()
                self.fail("全量守护测试 900s 未结束 —— 可能有用例挂死")
            leaked = sorted(snapshot_progress_files() - before)
            self.assertEqual(
                leaked, [],
                "这些用例留下了进度文件(装置必须自己收拾): %s\n"
                "⇒ SIGKILL 不可捕获, 不能指望脚本内的 cleanup; "
                "见 remove_new_progress_files" % leaked)
        finally:
            remove_new_progress_files(before)


    def test_watchdog_removes_progress_file_before_exiting(self):
        """看门狗必须在 `exit 124` **之前**删掉进度文件。

        ⚠️ 为什么不能用"源码里出现 rm"来判定: 之前那版兜底 `rm` 就写在
        `--watchdog-child` 分支的 `watchdog` 调用之后, 而 `watchdog()` 以
        `exit 124` 结束 —— **该行永远不可达**, 源码里却明明写着 `rm -f`。
        静态匹配会全绿, 而每次该用例仍泄漏 1 个文件(实测)。

        ⇒ 判据必须问"这行**跑不跑得到**", 不是"这行**写没写**"。
        这里用同一条真实故障路径(SIGKILL 主进程)观测文件系统差集。

        ⚠️ 但**不能**连看门狗一起杀: SIGKILL 不可捕获, 杀它等于让被守护的
        兜底 `rm` 必然跑不到 ⇒ 前提自相矛盾。实测两种变体: 杀=稳定红 /
        不杀=绿 —— 这个 A/B 对照正是分辨"产品缺陷"与"探针自伤"的手段,
        而不是靠猜。
        """
        before = snapshot_progress_files()
        try:
            # 主进程被 SIGKILL ⇒ trap cleanup EXIT 跑不到 ⇒ 只剩看门狗兜底。
            self._run_case_killing_main_with_sigkill(kill_watchdog=False)
            leaked = sorted(snapshot_progress_files() - before)
            self.assertEqual(
                leaked, [],
                "SIGKILL 主进程后进度文件仍在 ⇒ 看门狗的兜底 rm 不可达。"
                "典型成因: 兜底写在 watchdog() 调用之后, 而它以 exit 结束。")
        finally:
            remove_new_progress_files(before)


    def _assert_no_leak(self, argv, label):
        """跑一次入口, 断言它**不新增**进度文件。"""
        before = snapshot_progress_files()
        try:
            subprocess.run(argv, capture_output=True, timeout=300)
            leaked = sorted(snapshot_progress_files() - before)
            self.assertEqual(
                leaked, [], "%s 入口新增了进度文件(装置需自己收拾): %s"
                % (label, leaked))
        finally:
            remove_new_progress_files(before)

    def test_probe_entry_leaves_no_progress_file(self):
        """守护: `--probe-only` 入口不得新增进度文件。

        ⚠️ 这条入口**装 trap cleanup EXIT**(它 source 脚本后 return),
        但历史上它仍稳定泄漏 1 个 —— 因为清理时机在
        `new_progress_file` 之后, 而入口在**它之前**就 return 了。
        正是"读源码看得到 rm"却"跑不到"的形态。
        """
        # ⚠️ `--probe-only` 是给 **source** 用的(装完函数就 return),
        #    不是独立的命令行入口 —— 当参数传给 `bash` 会走不到那条分支。
        self._assert_no_leak(["bash", "-c", 'source "%s" --probe-only' % TARGET],
                             "--probe-only")

    def test_cleanup_probe_entry_leaves_no_progress_file(self):
        """守护: `--cleanup-probe` 入口不得新增进度文件。

        ⚠️ 它会真的去连库, 因此给足时限; 失败也应记为"入口自身泄漏",
        而不是当作"通过" —— probe 失效不许静音。
        """
        self._assert_no_leak(["bash", TARGET, "--cleanup-probe"],
                             "--cleanup-probe")


    def test_main_process_sigkill_still_leaves_no_file(self):
        """守护: 主进程被 SIGKILL 时(不可捕获), 进度文件仍要被清掉。

        ⚠️ 与 `test_watchdog_removes_progress_file_before_exiting` 的差别:
        那条断言的是**看门狗**负责兜底; 这条断言的是**主进程正常退出**那条
        路径(trap cleanup EXIT)在被杀时确实失效, 且看门狗补上了。
        两条都必要: 前者防"兜底写在 exit 之后", 后者防"根本没人兜底"。

        ⚠️ 同样**不能**连看门狗一起杀(SIGKILL 不可捕获 ⇒ 前提自相矛盾)。
        """
        before = snapshot_progress_files()
        try:
            with tempfile.TemporaryDirectory(prefix="v18wd-sigkill-") as tmp:
                script = make_hung_copy(tmp)
                p, logpath = run_hung(script, watchdog_secs=8, wait_secs=0)
                out = wait_for_log(logpath, "看门狗已启动", timeout=60)
                self.assertTrue(out and "看门狗已启动" in out,
                                "看门狗未报告启动:\n%s" % out)
                if p.poll() is None:
                    p.kill()          # 主进程 SIGKILL: trap cleanup EXIT 跑不到
                self._wait_watchdogs_gone(logpath)
            leaked = sorted(snapshot_progress_files() - before)
            self.assertEqual(
                leaked, [],
                "主进程 SIGKILL 后进度文件仍在: %s\n"
                "⇒ 没有任何进程兜底清理" % leaked)
        finally:
            remove_new_progress_files(before)


class TestProcessGroupGuard(unittest.TestCase):
    """判据本身的守护 —— 否则它会退化成"杀整个用户会话"。"""

    def test_guard_uses_pgid_eq_pid(self):
        """判据必须是 PGID == PID, 不能是 PGID == SID。

        ⚠️ 我第一版写的是 PGID == SID, 而**普通 bash 也满足**
        (实测两者都是外部 shell 的 pgid/sid), 于是判据恒真,
        会真的去 `kill -- -<外部pgid>` —— 把当前用户的整个 shell 会话杀掉。
        """
        src = open(TARGET, encoding="utf-8").read()
        self.assertIn('"$my_pgid" != "$my_pid"', src,
                      "未使用 PGID==PID 判据")
        self.assertNotIn('"$my_pgid" = "$(ps -o sid=', src,
                         "仍在用 PGID==SID 判据(会误杀外部会话)")

    def test_repo_script_untouched(self):
        """装置替换**绝不能**落到仓库真脚本上。

        ⚠️ 第一版断言是 `assertNotIn("sleep 300", src)` —— 结果**误报**:
        `sleep 300` 作为**注释文字**确实出现在真脚本里(我在写
        "TERM 后仍存活: bash h.sh + sleep 300"这段说明)。
        报错的形态是「一条断言红了, 而被测对象没问题」——
        即 AGENTS.md 第九课那一条。

        正解: 断言必须指向**被替换的那几行真实装置代码**,
        而不是它们可能出现在注释里的字面量。
        """
        src = open(TARGET, encoding="utf-8").read()
        for old, _new in DEVICE_SUBSTITUTIONS:
            # ⚠️ 正则锚不能用 assertIn —— 它把锚当**普通文本**找, 必然找不到,
            #    于是每次重构装置都会假红。实测: x() 从 `timeout 90 ...` 改成
            #    `timeout "${X_TIMEOUT:-90}" ...` 后, 本用例报的真脚本内容
            #    里明明有完整的 podman exec 行。
            #    正解: 让正则真的去 search, 命中必须存在(第九课)。
            if _is_regex_anchor(old):
                self.assertIsNotNone(
                    re.search(old, src),
                    "真脚本里已找不到原有装置函数 —— 可能已被测试改坏")
            else:
                self.assertIn(old, src,
                              "真脚本里已找不到原有装置函数 —— 可能已被测试改坏")
        # 反向: 替换后的形态不得出现在**非注释**行里
        for line in src.splitlines():
            stripped = line.strip()
            if stripped.startswith("#"):
                continue
            self.assertNotIn("sleep 300", stripped,
                             "仓库真脚本的**可执行代码**里出现 sleep 300: %s" % stripped)


if __name__ == "__main__":
    unittest.main(verbosity=2)
