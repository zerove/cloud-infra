package com.cloudinfra.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@code deploy/config/file-service.yaml} 的配置键与代码读取处逐字对齐。
 *
 * <p><b>2026-10-06 实测缺陷</b>: 配置里写的是
 * <pre>
 * file:
 *   upload:
 *     dir: /data/uploads
 * </pre>
 * 而代码读的是 <code>&#64;Value("${file.storage.local.path:...}")</code>。
 * {@code upload.dir} <b>没有任何代码读取</b> —— 于是服务一直静默使用代码默认值
 * {@code /tmp/cloud-infra/uploads}。运行时证据: 容器内 {@code /data} 目录不存在,
 * 而 {@code /tmp/cloud-infra/uploads} 存在。
 *
 * <p><b>为什么它长期没被发现</b>: 配置"看起来配好了", 服务也正常启动,
 * 小文件上传下载全部成功 —— 缺陷只在<b>配置值与默认值不同</b> 时才有可观测后果。
 * 若默认值恰好可用, 这个错键将永久静默。这正是本项目"能力存在 ≠ 能力在运行"
 * 同一类问题的配置面版本。
 *
 * <p><b>本测试的判据来源</b>: 键名从<b>代码里的 &#64;Value 表达式</b>正则提取,
 * 而不是硬编码在测试里。理由: 硬编码会出现"代码改了键名、测试仍按旧键校验"
 * 的假绿 —— 那正是本项目记录过的守护自身失效案例。
 */
class FileServiceConfigKeyAlignmentTest {

    /**
     * 从模块目录向上回溯定位仓库根。
     *
     * <p><b>为什么不写死相对路径</b>: 实测存在两个不同基准 ——
     * 模块根能找到 {@code src/main/java/...}, 但 {@code ../deploy/...} 找不到;
     * 而 {@code ../../deploy/config/...} 能找到配置, 却找不到源码。
     * 即没有一个 {@code ../} 层数同时满足两者（surefire 的 user.dir
     * 与 shell 的 cwd 不同）。写死层数会在换构建方式时静默失效。
     *
     * <p><b>回溯失败必须报红</b>: 找不到仓库根时抛异常, 而不是返回 null 让后续
     * 断言以"文件不存在"的理由失败 —— 那会掩盖真正的根因（基准算错）。
     */
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
        throw new IllegalStateException(
                "从 " + Path.of("").toAbsolutePath() + " 向上 6 层未找到 deploy/config/file-service.yaml"
                        + " —— 无法定位仓库根。本守护依赖真实配置文件, 不允许猜路径或跳过。");
    }

    private static final Path REPO_ROOT = repoRoot();
    private static final Path CONFIG = REPO_ROOT.resolve("deploy/config/file-service.yaml");
    private static final Path COMPOSE = REPO_ROOT.resolve("deploy/compose.yaml");

    /** 代码侧读取点 —— 经仓库根解析, 避开 surefire user.dir 与 shell cwd 的差异 */
    private static final Path LOCAL_STRATEGY = REPO_ROOT.resolve(
            "cloud-infra-modules/file-service/src/main/java/com/cloudinfra/file/service/strategy/LocalStorageStrategy.java");
    private static final Path MINIO_CONFIG = REPO_ROOT.resolve(
            "cloud-infra-modules/file-service/src/main/java/com/cloudinfra/file/config/MinioConfig.java");
    private static final Path FILE_CONTROLLER = REPO_ROOT.resolve(
            "cloud-infra-modules/file-service/src/main/java/com/cloudinfra/file/controller/FileController.java");

    /** 提取 &#64;Value("${...}") 中的配置键, 例如 file.storage.local.path */
    private static List<String> valueKeysIn(Path javaFile) throws Exception {
        assertThat(Files.exists(javaFile))
                .as("找不到 %s —— 守护必须指向真实的读取处", javaFile)
                .isTrue();
        String src = Files.readString(javaFile);
        Matcher m = Pattern.compile("\\@Value\\(\"\\$\\{([^:}]+):").matcher(src);
        List<String> keys = new java.util.ArrayList<>();
        while (m.find()) {
            keys.add(m.group(1).trim());
        }
        return keys;
    }

    /** 提取 &#64;ConfigurationProperties(prefix = "...") 的前缀 */
    private static List<String> prefixesIn(Path javaFile) throws Exception {
        assertThat(Files.exists(javaFile))
                .as("找不到 %s", javaFile)
                .isTrue();
        String src = Files.readString(javaFile);
        Matcher m = Pattern.compile("prefix\\s*=\\s*\"([^\"]+)\"").matcher(src);
        List<String> prefixes = new java.util.ArrayList<>();
        while (m.find()) {
            prefixes.add(m.group(1).trim());
        }
        return prefixes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml() throws Exception {
        assertThat(Files.exists(CONFIG))
                .as("找不到 %s —— 守护必须指向真实下发给服务的配置", CONFIG)
                .isTrue();
        try (InputStream in = Files.newInputStream(CONFIG)) {
            return new Yaml().load(in);
        }
    }

    /** 逐段下钻取嵌套键, 路径任一段不存在返回 null（区别于"值为空"） */
    @SuppressWarnings("unchecked")
    private static Object dig(Map<String, Object> root, String dottedKey) {
        Object cur = root;
        for (String seg : dottedKey.split("\\.")) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(seg);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    @Test
    @DisplayName("配置中必须存在代码实际读取的每一个键 —— 写没人读的键等于没配")
    void everyKeyReadByCodeMustExistInConfig() throws Exception {
        Map<String, Object> root = loadYaml();

        List<String> valueKeys = valueKeysIn(LOCAL_STRATEGY);
        assertThat(valueKeys)
                .as("守护前提: 应从 LocalStorageStrategy 提取到至少一个 &#64;Value 键。"
                        + "若提取到 0 个, 说明正则失效或代码改成了别的注入方式 —— "
                        + "此时本测试必须报红, 不能因'没键可查'而静默通过")
                .isNotEmpty();

        List<String> missing = new java.util.ArrayList<>();
        for (String key : valueKeys) {
            if (dig(root, key) == null) {
                missing.add(key + " (代码用 &#64;Value 读取, 但配置里没有这个键)");
            }
        }
        assertThat(missing)
                .as("这些键被代码读取但配置中不存在 —— 服务会静默使用代码默认值。"
                        + "2026-10-06 实测: upload.dir 就是这样写了半年从未生效")
                .isEmpty();
    }

    @Test
    @DisplayName("配置中不得残留无人读取的 file.upload 节点 —— 它是失效配置的来源")
    void noOrphanUploadNode() throws Exception {
        Map<String, Object> root = loadYaml();
        Object uploadNode = dig(root, "file.upload");
        if (uploadNode == null) {
            return; // 已经清理干净
        }
        // 若还存在, 必须确认确实有代码读它, 否则就是误导性配置
        String src = Files.readString(LOCAL_STRATEGY) + Files.readString(MINIO_CONFIG);
        assertThat(src)
                .as("配置里存在 file.upload 节点, 但代码中没有任何 &#64;Value(\"${file.upload...\") "
                        + "或 prefix=\"file.upload\" 读取它。"
                        + "该节点应删除, 否则下一次读它的人会以为配置已生效(2026-10-06 实测陷阱)")
                .doesNotContain("file.upload");
    }

    @Test
    @DisplayName("MinIO 桶名必须与 FileController 签名直传硬编码的桶一致")
    void bucketMustMatchPresignedUpload() throws Exception {
        Map<String, Object> root = loadYaml();
        Object configuredBucket = dig(root, "file.storage.minio.bucket");
        assertThat(configuredBucket)
                .as("file.storage.minio.bucket 必须在配置中显式声明")
                .isNotNull();

        // 从代码里提取硬编码桶名, 而不是硬编码在测试中
        assertThat(Files.exists(FILE_CONTROLLER)).as("找不到 %s", FILE_CONTROLLER).isTrue();
        Matcher m = Pattern.compile("\\.bucket\\(\"([^\"]+)\"\\)").matcher(Files.readString(FILE_CONTROLLER));
        java.util.Set<String> codeBuckets = new java.util.LinkedHashSet<>();
        while (m.find()) {
            codeBuckets.add(m.group(1));
        }
        assertThat(codeBuckets)
                .as("守护前提: 应从 FileController 提取到硬编码桶名。"
                        + "提取到 0 个说明代码已改为读配置 —— 那时本测试的判据需重写")
                .isNotEmpty();

        // 配置值形如 ${MINIO_BUCKET:cloud-infra-files}, 取其默认值部分。
        // 不能用 lastIndexOf(':') —— 那个冒号在 "${" 内, 切开会得到
        // "cloud-infra-files}" (带结尾花括号)。2026-10-06 实测踩到过。
        String raw = String.valueOf(configuredBucket).trim();
        String effective;
        if (raw.startsWith("${") && raw.endsWith("}")) {
            String body = raw.substring(2, raw.length() - 1);
            int colon = body.indexOf(':');
            effective = (colon >= 0 ? body.substring(colon + 1) : body).trim();
        } else {
            effective = raw;
        }
        assertThat(codeBuckets)
                .as("配置默认桶名 '" + effective + "' 与代码硬编码桶名 " + codeBuckets + " 不一致。"
                        + "后果: 签名直传写进 A 桶、常规上传读 B 桶, 大文件报 NoSuchBucket, "
                        + "而小文件走本地存储故不受影响 —— 缺陷只在部分路径上暴露")
                .contains(effective);
    }

    @Test
    @DisplayName("本地存储目录必须挂持久卷, 且挂载点与配置路径一致")
    void localPathMustBeMountedToPersistentVolume() throws Exception {
        Map<String, Object> root = loadYaml();
        Object path = dig(root, "file.storage.local.path");
        assertThat(path).as("file.storage.local.path 必须在配置中显式声明").isNotNull();
        String localPath = String.valueOf(path);

        assertThat(Files.exists(COMPOSE)).as("找不到 %s", COMPOSE).isTrue();
        String compose = Files.readString(COMPOSE);

        // 取 file 服务块（container_name: cloud-file 是它的真实标识）
        int start = compose.indexOf("container_name: cloud-file");
        assertThat(start)
                .as("compose 中找不到 container_name: cloud-file —— 守护前提不成立")
                .isGreaterThan(0);
        int end = compose.indexOf("\n  notification:", start);
        if (end < 0) {
            end = compose.length();
        }
        String fileBlock = compose.substring(start, end);

        assertThat(fileBlock)
                .as("cloud-file 服务没有声明任何 volumes —— "
                        + "/data/uploads 位于容器可写层, 容器重建后文件全丢而元数据仍在, "
                        + "形成'有记录无文件'的孤儿(2026-10-06 实测)")
                .contains("volumes:");

        assertThat(fileBlock)
                .as("cloud-file 的 volumes 未挂载到 " + localPath + "。"
                        + "挂载点与配置路径不一致 = 空挂载, 比不挂更危险: 它看起来配好了")
                .contains(":/" + localPath.replaceFirst("^/", ""));
    }
}
