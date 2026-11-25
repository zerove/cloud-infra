package com.cloudinfra.file;

import com.cloudinfra.file.service.StorageFactory;
import com.cloudinfra.file.service.StorageStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 <b>读/删路径必须按实际存放位置解析策略</b>。
 *
 * <p><b>2026-10-06 实测缺陷</b>: {@code FileServiceImpl} 的写路径用
 * {@code getStrategy(size, type)} 按大小路由（≥10MB → MinIO），
 * 而读路径（download / downloadVersion / delete）用无参
 * {@code getStrategy()}，后者在 {@code auto} 模式下恒返回 local。
 * 后果: <b>所有 ≥10MB 的文件永远无法下载</b>，服务端报
 * “文件不存在: xxx.txt”，HTTP 403 —— 与桶名是否配对无关。
 *
 * <p><b>为什么单测不会自动发现它</b>: 既有测试用 mock 的
 * {@code StorageStrategy}，无论返回哪个策略都能"下载成功"，
 * 断言的是"调用了 download"，而不是"调用了**正确**策略的 download"。
 */
class StorageStrategyResolveForReadTest {

    /** 只关心"我这儿有这个文件吗"的假策略 */
    private static final class FakeStrategy implements StorageStrategy {
        private final String name;
        private final boolean holds;

        FakeStrategy(String name, boolean holds) {
            this.name = name;
            this.holds = holds;
        }

        @Override
        public String upload(String fileName, InputStream inputStream) {
            return "unused";
        }

        @Override
        public InputStream download(String path) throws IOException {
            if (!holds) {
                throw new IOException("文件不存在: " + path);
            }
            return InputStream.nullInputStream();
        }

        @Override
        public boolean delete(String path) {
            return holds;
        }

        @Override
        public boolean exists(String path) {
            return holds;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static StorageFactory factoryWith(boolean localHolds, boolean minioHolds) {
        Map<String, StorageStrategy> strategies = new LinkedHashMap<>();
        strategies.put("localStorageStrategy", new FakeStrategy("local", localHolds));
        strategies.put("minioStorageStrategy", new FakeStrategy("minio", minioHolds));
        StorageFactory factory = new StorageFactory(strategies);
        // 通过反射设置 activeStrategy=auto, 模拟真实部署的默认配置
        try {
            var field = StorageFactory.class.getDeclaredField("activeStrategy");
            field.setAccessible(true);
            field.set(factory, "auto");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置 activeStrategy —— 守护前提不成立", e);
        }
        return factory;
    }

    @Test
    @DisplayName("文件只在 MinIO 时必须解析到 MinIO —— 否则 ≥10MB 的文件永远下载不到")
    void resolvesToMinioWhenOnlyMinioHoldsTheFile() {
        StorageFactory factory = factoryWith(false, true);

        StorageStrategy resolved = factory.resolveForRead("eb4fcd72-36dd-4e27-909b-5f76d571bc5b.txt");

        assertThat(resolved)
                .as("文件只存在于 MinIO, 却解析到 " + resolved
                        + " —— 这正是 2026-10-06 实测的 403『文件不存在』成因")
                .isInstanceOf(FakeStrategy.class);
        assertThat(resolved.toString()).isEqualTo("minio");
    }

    @Test
    @DisplayName("文件只在本地时必须解析到本地")
    void resolvesToLocalWhenOnlyLocalHoldsTheFile() {
        StorageFactory factory = factoryWith(true, false);

        StorageStrategy resolved = factory.resolveForRead("/data/uploads/abc.txt");

        assertThat(resolved.toString()).isEqualTo("local");
    }

    @Test
    @DisplayName("两边都没有时回退默认策略 —— 由调用方抛出真实的『文件不存在』")
    void fallsBackToDefaultWhenNoStrategyHoldsIt() {
        StorageFactory factory = factoryWith(false, false);

        StorageStrategy resolved = factory.resolveForRead("ghost.txt");

        assertThat(resolved.toString())
                .as("全都不命中时应回退默认策略(而非抛异常), 让调用方给出准确错误")
                .isEqualTo("local");
    }

    @Test
    @DisplayName("默认策略命中时不做多余探测 —— 常见路径零额外开销")
    void defaultStrategyHitCostsNoExtraProbe() {
        StorageFactory factory = factoryWith(true, true);

        StorageStrategy resolved = factory.resolveForRead("/data/uploads/abc.txt");

        assertThat(resolved.toString())
                .as("默认策略已命中时应立即返回, 不再遍历其余策略")
                .isEqualTo("local");
    }

    @Test
    @DisplayName("空路径不得触发探测 —— 避免把 null 传给存储后端")
    void blankPathFallsBackWithoutProbing() {
        StorageFactory factory = factoryWith(true, true);

        assertThat(factory.resolveForRead(null).toString()).isEqualTo("local");
        assertThat(factory.resolveForRead("").toString()).isEqualTo("local");
    }

    @Test
    @DisplayName("守护源码: FileServiceImpl 不得再出现无参 getStrategy() 调用")
    void sourceMustNotUseNoArgGetStrategyForReadOrDelete() throws Exception {
        // 结构性守护: 即使 resolveForRead 逻辑被改坏, 这条仍会拦住
        // "读路径退回 getStrategy()" 这个**具体的回归方式**。
        Path root = repoRoot();
        Path impl = root.resolve(
                "cloud-infra-modules/file-service/src/main/java/com/cloudinfra/file/service/impl/FileServiceImpl.java");
        assertThat(Files.exists(impl)).as("找不到 %s", impl).isTrue();

        String src = Files.readString(impl);
        // 去掉注释后再匹配, 否则注释里的说明文字会造成误判
        String code = src.replaceAll("(?m)//.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(code)
                .as("FileServiceImpl 出现了无参 storageFactory.getStrategy() —— "
                        + "读/删路径必须用 resolveForRead(path), 否则 ≥10MB 文件永远下载不到")
                .doesNotContain("storageFactory.getStrategy()");
    }

    private static Path repoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(cur.resolve("deploy/config/file-service.yaml"))) {
                return cur;
            }
            cur = cur.getParent();
            if (cur == null) {
                break;
            }
        }
        throw new IllegalStateException("无法定位仓库根");
    }
}
