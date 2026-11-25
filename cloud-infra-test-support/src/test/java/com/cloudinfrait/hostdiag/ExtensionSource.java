package com.cloudinfrait.hostdiag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 守护测试读取生产源码的辅助方法。
 *
 * <p><b>为什么需要独立的类</b>: 多个守护测试都要读同一份生产源码。
 * 若每个测试各自实现一遍"找路径 → 读 → 找不到就当空串", 就会出现
 * <b>探针失效被读成"源码里没有"</b> —— 那正是本项目反复踩的形态
 * (第五课: 索引名探错 ⇒ 恒假红; 补记十六: 查错对象 ⇒ 假绿)。
 *
 * <p><b>关键设计: 读不到就抛异常, 不返回空串</b>。
 * 返回空串时, {@code contains(...)} 会一致地返回 {@code false},
 * 测试<b>会红</b>但错因指向"缺陷", 而真实原因是"探针坏了" ⇒ 下一个人会去改生产代码。
 * 抛异常则让两者在错误信息上就分开(补记一: 三态区分)。
 */
final class ExtensionSource {

    private ExtensionSource() {
    }

    private static final Path ROOT = Path.of("src", "main", "java", "com", "cloudinfra",
        "test", "support", "diagnostics");

    /**
     * 读取指定生产源文件的全部内容。
     *
     * @throws UncheckedIOException 文件不存在或读不了 —— <b>刻意不返回空串</b>
     */
    static String sourceOf(String simpleName) {
        Path f = ROOT.resolve(simpleName + ".java");
        if (!Files.isRegularFile(f)) {
            throw new UncheckedIOException(new IOException(
                "探针失效: 找不到生产源文件 " + f.toAbsolutePath()
                    + " ⇒【这不是缺陷, 是探针查错了对象】"));
        }
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(new IOException("探针失效: 无法读取 " + f, e));
        }
    }

    /**
     * 返回包含指定子串的第一行; 找不到返回空串。
     *
     * <p>空串是<b>安全的失败</b>: 调用方对它做 {@code contains} 断言必然为 {@code false},
     * 即测试会红, 而不是静默通过。调用方须把空串当"探针失效"处理并给出明确断言消息。
     */
    static String lineContaining(String src, String needle) {
        for (String line : src.split("\n")) {
            if (line.contains(needle)) {
                return line.trim();
            }
        }
        return "";
    }
}