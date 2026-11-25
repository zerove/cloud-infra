#!/usr/bin/env python3
"""check_port_conflicts.py 的独立测试。

重点不是"跑通", 而是**证明这个判据能抓住它自己的缺陷**。
本项目已两次栽在"提取规则只覆盖了一种形态"上:
  - 第五课: 探针查 `oper_log`, 真实索引名是 `sys_oper_log`
  - 第九课补记(本轮): PORT_RE 以 `\s*$` 结尾, 漏掉全部带行尾注释的端口

所以这里的核心断言是**漏检方向**: 构造带注释/带范围/带绑定地址的
compose, 断言解析器把它们**全部**抓出来。少抓一个就红。

退出码: 0=全部通过, 1=有断言失败
"""
import importlib.util
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, "check_port_conflicts.py")

spec = importlib.util.spec_from_file_location("check_port_conflicts", TARGET)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def parse_yaml_ports(text):
    """把 compose 文本写到临时文件后调用真实解析器。

    ⚠️ 必须走**真实代码路径**(落盘 + read_compose_ports), 不能直接
    拿正则去测 —— 否则测的是"我的测试里的正则", 不是"门禁里的正则"。
    与 AGENTS.md 第八课补记一"函数不该假定调用前的初始状态"同源。
    """
    import tempfile
    tmpdir = tempfile.mkdtemp(prefix="portconf-test-")
    orig_compose = mod.COMPOSE
    try:
        path = os.path.join(tmpdir, "compose.yaml")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        mod.COMPOSE = path
        return mod.read_compose_ports()
    finally:
        mod.COMPOSE = orig_compose


class TestPortExtraction(unittest.TestCase):
    """提取规则: 必须覆盖 compose 里出现的**全部**形态。"""

    def test_plain_mapping(self):
        ports, err = parse_yaml_ports('    ports:\n      - "9000:9000"\n')
        self.assertIsNone(err)
        self.assertEqual({9000}, ports)

    def test_mapping_with_trailing_comment(self):
        """带行尾注释 —— 这正是第一版漏掉全部 8 个端口的形态。"""
        ports, err = parse_yaml_ports(
            '    ports:\n'
            '      - "18080:8080"   # SkyWalking UI\n'
            '      - "9301:9301"   # 管理端口 (ADR-008)\n'
        )
        self.assertIsNone(err)
        self.assertEqual({18080, 9301}, ports)

    def test_bind_address(self):
        ports, err = parse_yaml_ports('      - "127.0.0.1:9000:9000"\n')
        self.assertIsNone(err)
        self.assertEqual({9000}, ports)

    def test_port_range(self):
        """端口范围 —— host 侧每个端口都必须进集合。"""
        ports, err = parse_yaml_ports('      - "9010-9011:9000-9001"\n')
        self.assertIsNone(err)
        self.assertEqual({9010, 9011}, ports)

    def test_range_plus_comment(self):
        ports, err = parse_yaml_ports('      - "4317-4318:4317-4318"  # otlp\n')
        self.assertIsNone(err)
        self.assertEqual({4317, 4318}, ports)

    def test_unquoted(self):
        ports, err = parse_yaml_ports("      - 3000:3000\n")
        self.assertIsNone(err)
        self.assertEqual({3000}, ports)

    def test_real_compose_is_fully_covered(self):
        """真实 compose 里声明的端口数, 必须与最宽的独立正则一致。

        这是**漏检守卫**: 两个提取方式互为对照, 少一个就红。
        """
        import re
        with open(mod.COMPOSE, encoding="utf-8") as f:
            raw = f.read()
        # 刻意用一条与 PORT_RE **结构不同**的正则, 避免自证
        loose = re.findall(r'-\s*"?[\d.]*?:(\d+)(?:-\d+)?:\d+', raw)
        expected = set()
        for s in loose:
            expected.add(int(s))
        ports, err = mod.read_compose_ports()
        self.assertIsNone(err)
        missing = expected - ports
        self.assertEqual(set(), missing,
                         "漏检端口: %s —— PORT_RE 未覆盖某种 compose 写法" % sorted(missing))

    def test_no_ports_returns_error_not_empty_pass(self):
        """扫到 0 个目标必须报错 —— 不能当成"没有冲突"。"""
        ports, err = parse_yaml_ports('services:\n  foo:\n    image: bar\n')
        self.assertIsNone(ports)
        self.assertIsNotNone(err)


class TestPodmanPortsColumn(unittest.TestCase):
    """podman Ports 列的解析: 单端口与范围都要抓。"""

    def test_single(self):
        self.assertEqual({18080}, mod.parse_host_ports("0.0.0.0:18080->8080/tcp"))

    def test_range(self):
        self.assertEqual({9010, 9011},
                         mod.parse_host_ports("0.0.0.0:9010-9011->9000-9001/tcp"))

    def test_multiple_in_one_line(self):
        got = mod.parse_host_ports("0.0.0.0:4317-4318->4317-4318/tcp, 55679/tcp")
        self.assertEqual({4317, 4318}, got)

    def test_empty(self):
        self.assertEqual(set(), mod.parse_host_ports(""))


class TestAbnormalSpan(unittest.TestCase):
    """异常跨度必须报错而不是静默产生一个巨大的集合。"""

    def test_huge_span_rejected(self):
        ports, err = parse_yaml_ports('      - "1-65535:1-65535"\n')
        self.assertIsNone(ports)
        self.assertIn("跨度异常", err)

    def test_error_reports_correct_line_number(self):
        """错误行号必须指向真正出问题的那一行。

        ⚠️ 用 lines.index(ln) 会在**重复行**时返回首个下标, 报错指错行。

        ⚠️ 第一版用例写错了: 前两行里 `1-100` 跨度 99 已超 MAX_PORT_SPAN(64),
        于是**第 1 行就先触发**, 解析器报第 1 行是**正确行为** ——
        是我的断言假设了"第 3 行先触发"。这本身就是一次
        "探针给出的红, 被读成被测对象有缺陷"(AGENTS.md 第九课补记七)。
        本版让前三行全部合法, 只让第 3 行超限, 行号唯一确定。
        """
        ports, err = parse_yaml_ports(
            '      - "1-10:1-10"\n'
            '      - "1-10:1-10"\n'      # 与第 1 行**内容完全相同**
            '      - "1-200:1-200"\n'   # 唯一超限的一行
        )
        self.assertIsNone(ports)
        self.assertIn("第 3 行", err)


if __name__ == "__main__":
    unittest.main(verbosity=2)
