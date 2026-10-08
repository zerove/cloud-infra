#!/usr/bin/env python3
"""守护 AGENTS.md「第九课补记二」的表格结构完整性。

存在原因: 该课用一张 5 行表格承载「五轮错误结论」, 而表格是
Markdown 里最容易静默损坏的结构（多一个/少一个管道符, 渲染仍成功,
但列已错位）。本脚本对其做列数一致性断言, 并可作为负向对照的载体。

退出码:
  0 = 通过
  1 = 表格列数不一致（结构损坏）
  2 = 定位失败（课号找不到 / 表格块找不到）—— 探针不可信, 不判绿
"""
import os
import sys

DOC = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "AGENTS.md",
)
LESSON = "第九课补记二"


def count_pipes(line):
    """统计真正的列分隔管道符, 跳过 ``\\|`` 这种转义。

    不做这一步会误判: 单元格里写 ``\\|`` 表示"这一列的内容里有个竖线",
    它的管道符不是分隔符。若按原文 count("|"), 该行会多 1 而被判成
    COLUMN-MISMATCH —— 即守护自己制造假红(本次实测踩到)。
    """
    return line.replace("\\|", "").count("|")


def find_lesson_block(lines):
    """返回 (起始行idx, 结束行idx) —— 结束为下一个同级标题。"""
    start = None
    for i, ln in enumerate(lines):
        if ln.startswith("### ") and LESSON in ln:
            start = i
            break
    if start is None:
        return None
    for j in range(start + 1, len(lines)):
        if lines[j].startswith("### "):
            return (start, j)
    return (start, len(lines))


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

    blk = find_lesson_block(lines)
    if blk is None:
        print("FAIL: 定位失败 —— 未找到课号 %r, 探针不可信, 不判绿" % LESSON)
        return 2
    s, e = blk
    print("定位: 行 %d..%d (%d 行)" % (s + 1, e, e - s))

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

    if not blocks:
        print("FAIL: 课内未找到任何表格块")
        return 2

    bad = 0
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

    # 断言: 必须恰好2 个表格块; 表1(五轮)=5 数据行; 表2(缺陷)=5 数据行。
    # 不用硬编码绝对行数做全部断言 —— 文档正常增删行会被误报。
    # 但仍需钉住一个绝对值: 表1 必须是 5 数据行(表头+分隔+5=7),
    # 否则"有人删掉一整轮"不会被任何断言抳到。
    EXPECT_DATA = {1: 5, 2: 5}   # 每块的数据行数(不含表头与分隔)
    if len(blocks) != 2:
        print("FAIL: 应有2 个表格块(五轮表 + 守护缺陷表), 实为 %d 个" % len(blocks))
        bad += 1
    for bi, blk_lines in enumerate(blocks, 1):
        exp_total = EXPECT_DATA.get(bi)
        if exp_total is None:
            print("FAIL: 出现第 %d 个表格块, 但只预期 2 块" % bi)
            bad += 1
            continue
        # 实際总行数 = 数据行 + 表头 + 分隔
        if len(blk_lines) != exp_total + 2:
            print("FAIL: 表 %d 应有 %d 数据行(共 %d 行), 实为 %d 行"
                  % (bi, exp_total, exp_total + 2, len(blk_lines)))
            bad += 1

    if bad:
        print("RESULT: FAIL (%d 项结构异常)" % bad)
        return 1
    print("RESULT: PASS (%d 个表格块, 列数一致且数据行数符合预期 %s)"
          % (len(blocks), EXPECT_DATA))
    return 0


if __name__ == "__main__":
    sys.exit(main())