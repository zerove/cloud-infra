package com.cloudinfra.system.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 (#17): sys_user 用户检索的 pg_trgm 索引既要真的建, 也不能是死索引。
 *
 * <p>两道断言:
 * <ol>
 *   <li>结构: V26 迁移启用 pg_trgm, 并对 sys_user 建 GIN(gin_trgm_ops) 索引。</li>
 *   <li>反死索引: V26 里每个 trgm 索引列, 都必须在 mapper 里存在 LIKE 调用点 ——
 *       防止"建了索引却没有任何查询会用到"的假优化。</li>
 * </ol>
 *
 * <p>运行时证据由 {@code UserSearchTrgmIndexIT} 在真实 PostgreSQL 上补齐
 * (本测试只证明"写对了", 不证明"SQL 能被数据库执行")。
 */
class UserSearchTrgmIndexGuardTest {

    private static final String MIGRATION = "db/migration/V26__user_search_trgm_index.sql";

    private static final Pattern TRGM_COLUMN = Pattern.compile(
        "USING\\s+gin\\s*\\(\\s*(\\w+)\\s+gin_trgm_ops\\s*\\)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("V26 启用 pg_trgm 并对 username 建 GIN 索引")
    void migrationEnablesPgTrgmAndCreatesIndex() throws IOException {
        String sql = readClasspath(MIGRATION);

        assertThat(sql).containsIgnoringCase("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        assertThat(sql).containsIgnoringCase("idx_sys_user_username_trgm");
        assertThat(trgmColumns(sql)).isEqualTo(Set.of("username"));
    }

    @Test
    @DisplayName("每个 trgm 索引列都必须有 LIKE 调用点 (反死索引)")
    void everyTrgmIndexedColumnHasLikeCallSite() throws IOException {
        Set<String> indexedColumns = trgmColumns(readClasspath(MIGRATION));
        assertThat(indexedColumns).isNotEmpty();

        String mappers = readAllMapperXml().toLowerCase(Locale.ROOT);
        for (String column : indexedColumns) {
            assertThat(mappers)
                .as("trgm 索引列 [%s] 必须存在 LIKE 调用点, 否则索引是死索引", column)
                .containsPattern(column.toLowerCase(Locale.ROOT) + "\\s+like");
        }
    }

    private static Set<String> trgmColumns(String sql) {
        Matcher matcher = TRGM_COLUMN.matcher(sql);
        Set<String> columns = new LinkedHashSet<>();
        while (matcher.find()) {
            columns.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private static String readClasspath(String resource) throws IOException {
        try (InputStream in = UserSearchTrgmIndexGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Path fallback = Paths.get("src/main/resources", resource);
        if (Files.exists(fallback)) {
            return Files.readString(fallback);
        }
        throw new IllegalStateException("classpath 与文件系统均找不到: " + resource);
    }

    private static String readAllMapperXml() throws IOException {
        List<Path> candidates = List.of(
            Paths.get("src/main/resources/mapper"),
            Paths.get("cloud-infra-modules/system-module/src/main/resources/mapper"));
        for (Path dir : candidates) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    return files
                        .filter(path -> path.getFileName().toString().endsWith(".xml"))
                        .map(UserSearchTrgmIndexGuardTest::readStringUnchecked)
                        .collect(Collectors.joining("\n"));
                }
            }
        }
        throw new IllegalStateException("找不到 mapper 目录, 候选: " + candidates);
    }

    private static String readStringUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + path, e);
        }
    }
}
