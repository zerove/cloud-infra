package com.cloudinfra.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 <b>跑起来的容器必须真的包含当前源码里的修复</b>。
 *
 * <p><b>2026-10-06 实测事故</b>: 修复已提交（{@code 767e7d91}，为
 * {@code StorageFactory.resolveForRead}），单测全绿，但运行中的容器
 * 仍在跑<b>早 5.5 小时构建的旧镜像</b>。于是下载 10MB 文件稳定返回
 * HTTP 403 "文件不存在" —— 症状与"修复无效"完全一致，
 * 排查方向被完整地带偏（先怀疑桶名、网络、凭据，全部有"证据"支撑）。
 *
 * <p><b>为什么其他守护都拦不住</b>: 单元测试验的是源码，
 * 部署验的是产物，两者之间<b>没有任何断言</b>。这类失效
 * （第五课：看起来正常的错误信息 / 第六课：能力存在 ≠ 能力在运行）
 * 只有把"产物 ← 源码"这一步显式钉住才拦得住。
 *
 * <p><b>本守护的判定对象从源码提取，不硬编码字符串</b>:
 * 方法名读自 {@code StorageFactory.java}，因此重命名方法会让守护变红，
 * 提醒同步更新本测试 —— 而不是安静地继续验一个不存在的方法
 * （第五课补记一：扫描器扫到 0 个目标必须判红）。
 */
class ContainerArtifactMatchesSourceTest {

    /** 被守护的修复点：读/删路径的策略解析方法，存在于 StorageFactory */
    private static final String GUARDED_METHOD = "resolveForRead";

    /** 修复点所在的源文件（相对仓库根） */
    private static final String SOURCE_FILE = "cloud-infra-modules/file-service/src/main/java/com/cloudinfra/file/service/StorageFactory.java";

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.exists(dir.resolve(SOURCE_FILE))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "未找到仓库根（向上 6 层都没见到 " + SOURCE_FILE + "）—— 守护无法判定，属探针失效");
    }

    @Test
    @DisplayName("守卫方法名必须真的存在于源码中 —— 防止守护验一个不存在的方法")
    void guardedMethodExistsInSource() throws IOException {
        Path source = repoRoot().resolve(SOURCE_FILE);
        String text = Files.readString(source, StandardCharsets.UTF_8);

        assertThat(text)
                .as("StorageFactory 必须仍声明 `%s`；若已重命名，请同步更新本守护的 GUARDED_METHOD", GUARDED_METHOD)
                .contains(GUARDED_METHOD);
    }

    @Test
    @DisplayName("构建产物若存在, 其字节码必须含该方法 —— 拦下'代码改了但没重建镜像'")
    void builtArtifactCarriesTheFixWhenPresent() throws IOException {
        Path classes = repoRoot()
                .resolve("cloud-infra-modules/file-service/target/classes/com/cloudinfra/file/service/StorageFactory.class");

        if (!Files.exists(classes)) {
            // 没构建过 => 本次不判定, 但必须说清原因, 不能静默通过
            // 也不该 exit 1: "没测"不等于"测了不过"（第五课补记三）
            System.out.println("SKIP: 尚无 " + classes
                    + "（未构建），本次不判定产物是否含修复");
            return;
        }

        String bytecode = new String(Files.readAllBytes(classes), StandardCharsets.ISO_8859_1);

        assertThat(bytecode)
                .as("StorageFactory.class 的字节码里没有 `%s` —— target/classes 落后于源码，"
                        + "需重新构建再部署", GUARDED_METHOD)
                .contains(GUARDED_METHOD);
    }

    @Test
    @DisplayName("Spring Boot 可执行 jar 若存在, 也必须含该方法 —— 覆盖实际被打进镜像的产物")
    void executableJarCarriesTheFixWhenPresent() throws IOException {
        Path targetDir = repoRoot().resolve("cloud-infra-modules/file-service/target");
        if (!Files.isDirectory(targetDir)) {
            System.out.println("SKIP: 尚无 " + targetDir + "（未构建），本次不判定 jar");
            return;
        }

        List<Path> execJars = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(targetDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith("-exec.jar"))
                    .forEach(execJars::add);
        }

        if (execJars.isEmpty()) {
            System.out.println("SKIP: " + targetDir + " 下无 *-exec.jar，本次不判定 jar");
            return;
        }

        for (Path jar : execJars) {
            boolean found = false;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(
                        "BOOT-INF/classes/com/cloudinfra/file/service/StorageFactory.class");
                if (entry != null) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        String bytecode = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                        found = bytecode.contains(GUARDED_METHOD);
                    }
                }
            }

            assertThat(found)
                    .as("jar %s 里的 StorageFactory.class 不含 `%s` —— 该 jar 早于修复，"
                            + "用它构建镜像会得到'修复无效'的假象", jar.getFileName(), GUARDED_METHOD)
                    .isTrue();
        }
    }
}
