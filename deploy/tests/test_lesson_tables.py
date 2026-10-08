#!/usr/bin/env python3
"""第九课补记二 表格守护 —— 负向对照驱动器。

要点: 所有注入**按内容定位**, 不用行号。
行号会随文档编辑漂移, 漂移后注入落在别处(如空行) → 测试恒真绿,
这正是 AGENTS.md 第八课补记一记录过的形态。

退出码 0 = 全部符合预期; 1 = 有用例未达预期(守护无效)。
"""
import re
import subprocess
import sys
import os
import tempfile
import shutil

BASE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(BASE, "check_lesson_tables.py")
REPO = os.path.dirname(os.path.dirname(BASE))
SRC = os.path.join(REPO, "AGENTS.md")
# 变异副本写临时目录, 不落仓库 —— 否则守护自身会污染被检工作区
WORK = tempfile.mkdtemp(prefix="lesson-tables-")


def run_guard(doc):
    """对指定文档跑守护, 返回 (rc, 输出)。

    替换 DOC 时必须匹配**整个多行定义**:
    早期版本用 ``^DOC = .*$`` 只替换了第一行, 而 DOC 在check_lesson_tables.py 里
    是跨 4 行的 ``os.path.join(...)``, 于是剩余行变成孤立缩进块 →
    IndentationError → rc=1。**所有用例都“符合预期”而实际一个都没跑起来**
    (前5 项本就该 rc=1, 于是假通过)。
    """
    s = open(SCRIPT, encoding="utf-8").read()
    s = re.sub(r'^DOC = os\.path\.join\(.*?\n\)\n',
               'DOC = %r\n' % doc, s, count=1, flags=re.M | re.S)
    if "DOC = os.path.join" in s:
        raise AssertionError("DOC 定义未被替换 —— 替换正则失效")
    p = os.path.join(WORK, "_tmp_guard.py")
    open(p, "w", encoding="utf-8").write(s)
    r = subprocess.run([sys.executable, p], capture_output=True, text=True, timeout=120)
    return r.returncode, r.stdout + r.stderr


def mutate(name, fn, expect_change=True):
    """按内容做一次变异, 写盘并返回路径。

    自检注入是否生效: 变异后若与原文**逐字节相同**, 说明匹配条件没命中,
    而 Python 的列表推导不会报错 —— 那种情况下本用例会恒真绿。
    故此处直接抛错, 让"注入失效"与"守护失效"两件事可区分。
    (对应 AGENTS.md 第八课补记一规则4)
    """
    raw = open(SRC, encoding="utf-8").read()
    lines = raw.splitlines(keepends=True)
    out = fn(lines)
    if expect_change and "".join(out) == raw:
        raise AssertionError("注入未生效: 变异结果与原文完全相同 —— 匹配条件失效")
    path = os.path.join(WORK, name)
    open(path, "w", encoding="utf-8").writelines(out)
    return path


def main():
    cases = []

    # --- 正向 ---
    rc, out = run_guard(SRC)
    cases.append(("正向: 真实文档", rc, 0, out))

    # --- 负向 1: 删五轮表一数据行(按内容) ---
    def d1(ls):
        return [l for l in ls if "不接受随机端口" not in l]
    cases.append(("负向1 删五轮表数据行", run_guard(mutate("m1.md", d1))[0], 1, ""))

    # --- 负向 2: 五轮表某行多加一个管道符 ---
    # 匹配串取自真实行内容: 该行为「端口转发完全坏了」, 不含"把"字
    # 同样不能用 l + " |\n"(会新增行而非改行), 见负向4 的注释
    def d2(ls):
        out = []
        for l in ls:
            if "端口转发完全坏了" in l:
                l = l.rstrip("\n") + " |\n"
            out.append(l)
        return out
    cases.append(("负向2 五轮表多管道符", run_guard(mutate("m2.md", d2))[0], 1, ""))

    # --- 负向 3: 删整块五轮表 ---
    def d3(ls):
        return [l for l in ls if not ("端口转发完全坏了" in l or "不接受随机端口" in l
                or "类不存在" in l or "已空闲" in l or "45777" in l or "localhost ports" in l
                or "轮次 | 我的结论" in l)]
    cases.append(("负向3 删整块五轮表", run_guard(mutate("m3.md", d3))[0], 1, ""))

    # --- 负向 4: 守护缺陷表多加管道符 ---
    # 匹配串取自表2 真实行内容(「注入用行号」)
    # 注意: 不能用 l + " |\n" —— 该行已带换行符, 那样会新增一整行而非改这一行,
    # 注入位置就错了(实测: 产生了 "> |" 这样的独立行, 表格毫无变化)。
    # 正解: 去掉行尾换行再拼回去。
    def d4(ls):
        out = []
        for l in ls:
            if "| 注入用行号 |" in l:
                l = l.rstrip("\n") + " |\n"
            out.append(l)
        return out
    cases.append(("负向4 缺陷表多管道符", run_guard(mutate("m4.md", d4))[0], 1, ""))

    # --- 负向 4b: 删表2 一数据行 ---
    def d4b(ls):
        return [l for l in ls if "| 注入用行号 |" not in l]
    cases.append(("负向4b 缺陷表删数据行", run_guard(mutate("m4b.md", d4b))[0], 1, ""))

    # --- 负向 5: 课号改掉(定位失效) ---
    def d5(ls):
        return [l.replace("### ⚠️ 第九课补记二:", "### ⚠️ 第十一课:", 1) if l.startswith("### ") and "第九课补记二" in l else l for l in ls]
    cases.append(("负向5 课号改掉", run_guard(mutate("m5.md", d5))[0], 2, ""))

    # --- 负向 6: 文件不存在 ---
    cases.append(("负向6 文件不存在", run_guard(os.path.join(BASE, "nope.md"))[0], 2, ""))

    # --- 负向 7: 文件为空 ---
    empty = os.path.join(WORK, "empty2.md")
    open(empty, "w").close()
    cases.append(("负向7 文件为空", run_guard(empty)[0], 2, ""))

    # --- 负向 8(对照): 未改动的副本必须仍然通过, 证明变异是有效变量 ---
    cases.append(("对照 未改动副本", run_guard(mutate("m8.md", lambda ls: ls,
                 expect_change=False))[0], 0, ""))

    ok = bad = 0
    for name, rc, exp, out in cases:
        if rc == exp:
            ok += 1
            print("  ✓ %-28s rc=%s" % (name, rc))
        else:
            bad += 1
            print("  ✗ %-28s rc=%s (期望 %s)" % (name, rc, exp))
            if out:
                print("      " + out.strip().splitlines()[-1])
    print()
    print("  结果: 通过=%d 失败=%d ⇒ %s" % (ok, bad,
          "守护有效" if bad == 0 else "★ 守护无效"))
    return 1 if bad else 0


if __name__ == "__main__":
    try:
        rc = main()
    finally:
        shutil.rmtree(WORK, ignore_errors=True)
    sys.exit(rc)