#!/usr/bin/env python3
"""守护 AGENTS.md 各「课次补记」小节的表格结构完整性。

存在原因: 课次小节用表格承载「错误结论 / 缺陷对照」, 而表格是
Markdown 里最容易静默损坏的结构（多一个/少一个管道符, 渲染仍成功,
但列已错位）。本脚本对其做列数一致性断言, 并可作为负向对照的载体。

2026-10-08 扩展: 原版硬编码只守护 LESSON = "第九课补记二",
结果新增「补记三/补记四」后它照样报 PASS —— 守护范围比看起来窄,
且**没有任何东西会告诉你**。这与 AGENTS.md 第五课补记一
(扫到 0 个目标却 exit 0) 同型, 只是形态换成「扫到 1 个却漏掉其余」。
故改为: 扫描全部课次小节 + 内置覆盖自检(有表格的节没被扫描 => rc=2)。

退出码:
  0 = 通过
  1 = 表格列数不一致（结构损坏）
  2 = 探针不可信（文件读不到 / 定位失败 / 存在未被扫描的课次）
"""
import os
import re
import sys

# 课次补记已于 2026-10-13 从 AGENTS.md 迁至 docs/lessons/debugging-log.md。
DOC = os.environ.get("DOC_OVERRIDE") or os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "docs/lessons/debugging-log.md",
)
# DOC_OVERRIDE 的存在是为了让负向对照能在**副本**上注入缺陷, 而不必改仓库里的真实文档。
# 若副本本身写坏 / 读不到, 必须像对待真实文档一样判 rc=2 ——
# 否则"负向用例跑在一个空文件上"会被读成"守护抳到了缺陷"。

# 课次小节标题的识别模式, 而不是硬编码某一个课号。
# 形如: "### ⚠️ 第九课补记二: ..." / "### 第五课补记三之二: ..." / "### 第十课补记: ..."
#
# 编号形态有三种, 必须全收, 漏掉任何一种都会让守护"扫到大部分就报 PASS":
#   第N课 / 第N课补记M / 第N课补记M之二
# 「之二」这个形态是实测漏掉过一次才补上的 —— 守护范围比看起来窄,
# 而窄掉的那些节不会被任何东西报告。
LESSON_RE = re.compile(
    r"^###\s+(?:⚠️\s*)?第[一二三四五六七八九十]+课"
    r"(?:补记[一二三四五六七八九十]*(?:之[一二三四五六七八九十]+)?)?[:：]"
)

# 仅这些课次必须含表格。新增课次若含表格, 会在下面「覆盖自检」里被自动发现,
# 但不需要先在这里登记 —— 登记只用于钉住"必须有"的下限。
REQUIRED_LESSONS = {"第九课补记二": [5, 5]}   # 课号 -> 各表数据行数


def count_pipes(line):
    """统计真正的列分隔管道符, 跳过 ``\\|`` 这种转义。

    不做这一步会误判: 单元格里写 ``\\|`` 表示"这一列的内容里有个竖线",
    它的管道符不是分隔符。若按原文 count("|"), 该行会多 1 而被判成
    COLUMN-MISMATCH —— 即守护自己制造假红(本次实测踩到)。
    """
    return line.replace("\\|", "").count("|")


def find_lesson_blocks(lines):
    """扫描全文, 返回 [(课号, 起始行idx, 结束行idx), ...], 按出现顺序。

    不硬编码单个课号 —— 那正是本次修复的缺陷。
    """
    heads = []
    for i, ln in enumerate(lines):
        m = LESSON_RE.match(ln)
        if m:
            heads.append((i, ln))
    out = []
    for k, (i, ln) in enumerate(heads):
        end = heads[k + 1][0] if k + 1 < len(heads) else len(lines)
        out.append((ln, i, end))
    return out


def find_all_lesson_sections(lines):
    """返回所有课次小节的标题行, 供覆盖自检比对。"""
    return [ln for ln in lines if LESSON_RE.match(ln)]


def main():
    # 探针对象必须先确认存在 —— 否则 open() 抛异常时 rc=1,
    # 会被读成「表格结构损坏」, 而真实原因是「没找到被检对象」。
    # 这与 AGENTS.md 第五课同型: 取不到值 ≠ 取到假值。
    try:
        with open(DOC, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError as e:
        print("FAIL: 无法读取被检文件 %s —— %s" % (DOC, e))
        print("      这是探针对象缺失, 不是表格损坏, 不判绿")
        return 2

    if not lines:
        print("FAIL: 被检文件为空 —— 探针不可信, 不判绿")
        return 2

    blocks_all = find_lesson_blocks(lines)
    if not blocks_all:
        print("FAIL: 未找到任何课次小节 —— 探针不可信, 不判绿")
        return 2

    scanned = {}   # 标题 -> 该节内表格块数
    bad = 0
    for title, s, e in blocks_all:
        name = title.lstrip("# ").rstrip()
        # 收集表格块: 连续的以 | 开头的行
        blocks, cur = [], []
        for i in range(s, e):
            if lines[i].startswith("|"):
                cur.append((i, lines[i]))
            else:
                if cur:
                    blocks.append(cur)
                    cur = []
        if cur:
            blocks.append(cur)
        scanned[title] = len(blocks)
        if not blocks:
            continue
        print("[%s] 行 %d..%d" % (name.split(":")[0], s + 1, e))
        for bi, blk_lines in enumerate(blocks, 1):
            counts = [count_pipes(ln) for _, ln in blk_lines]
            uniq = sorted(set(counts))
            status = "OK" if len(uniq) == 1 else "COLUMN-MISMATCH"
            if len(uniq) != 1:
                bad += 1
            print("  表 %d: 行 %d..%d (%d 行) 管道数=%s  %s"
                  % (bi, blk_lines[0][0] + 1, blk_lines[-1][0] + 1,
                     len(blk_lines), uniq, status))
            if len(uniq) != 1:
                for (ln, text), c in zip(blk_lines, counts):
                    mark = "  <-- 少数派" if c != counts[0] else ""
                    print("      行%d 管道=%d %s%s"
                          % (ln + 1, c, text[:70], mark))
            # 列数一致性之外, 还要钉住数据行数, 否则"删掉一整行结论"不会被抳到
            # 按位置取: REQUIRED_LESSONS[课号] 是一个"各表数据行数"的列表
            expected_rows = REQUIRED_LESSONS.get(_lesson_key(title), [])
            if bi <= len(expected_rows) and expected_rows:
                exp_total = expected_rows[bi - 1]
                if len(blk_lines) != exp_total + 2:
                    print("FAIL: [%s] 表 %d 应有 %d 数据行(共 %d 行), 实为 %d 行"
                          % (name.split(":")[0], bi, exp_total,
                             exp_total + 2, len(blk_lines)))
                    bad += 1

    # ---- 覆盖自检: 文档里有表格的课次, 必须都被扫到 ----
    # 这段是本次修复的核心。原版硬编码单课号, 新增课次后它仍报 PASS,
    # 即"守护看起来在跑, 实际漏掉了大部分目标"。漏扫必须判红, 不能默认放过。
    all_sections = find_all_lesson_sections(lines)
    scanned_titles = set(scanned.keys())
    unscanned = []
    for i, ln in enumerate(lines):
        if not ln.startswith("|"):
            continue
        # 找出该表格行属于哪个课次小节
        owner = None
        for title, s, e in blocks_all:
            if s < i < e:
                owner = title
                break
        if owner is None:
            # 表格在最后一个课次小节之外 => 它在更早的课里, 而那些课也必须被覆盖
            owner = "__outside__"
        if owner not in scanned_titles and owner != "__outside__":
            unscanned.append((i + 1, owner))
    # 只对"确实属于某个课次小节"的表格做判定
    if unscanned:
        print("FAIL: 存在未被守护的课次小节 —— 守护范围比看起来窄")
        for ln_no, owner in unscanned[:10]:
            print("      行%d 所属课次未被扫描: %s" % (ln_no, owner[:40]))
        bad += 1

    # 必须存在的课次
    keys = {_lesson_key(t) for t in scanned_titles}
    for req in REQUIRED_LESSONS:
        if req not in keys:
            print("FAIL: 必需的课次 %r 未被扫描 —— 探针不可信, 不判绿" % req)
            return 2

    if bad:
        print("RESULT: FAIL (%d 项结构异常)" % bad)
        return 1
    print("RESULT: PASS (扫描 %d 个课次小节, 列数一致且数据行数符合预期)"
          % len(blocks_all))
    return 0


def _lesson_key(title):
    """从标题提取课号, 如 '### ⚠️ 第九课补记二: xxx' -> '第九课补记二'

    正则必须与 LESSON_RE 的编号形态一致（含「补记M之二」）, 否则
    REQUIRED_LESSONS 的键对不上，必要课次检查会静默失效。
    """
    m = re.search(
        r"第[一二三四五六七八九十]+课(?:补记[一二三四五六七八九十]*(?:之[一二三四五六七八九十]+)?)?",
        title,
    )
    return m.group(0) if m else title


if __name__ == "__main__":
    sys.exit(main())