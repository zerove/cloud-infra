package com.cloudinfra.framework.security.service;

import com.cloudinfra.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@code PermissionService} 的超级管理员旁路不会再次与库里的 role_key 脱节。
 *
 * <p><b>为什么需要这个守护</b>：2026-10-06 排查 file 下载缺陷时发现，
 * {@code PermissionService} 里硬编码的 {@code SUPER_ADMIN = "admin"}
 * 与 {@code sys_role.role_key} 的真实值 {@code 'SUPER_ADMIN'} 大小写不匹配，
 * 该旁路<b>从未生效过</b>，且全仓零测试覆盖 ——
 * 属于 AGENTS.md 第三条硬规则描述的"机制存在但从未运行"。
 *
 * <p><b>这个守护的特殊之处：真值从迁移脚本提取，不在测试里硬编码</b>。
 * 若把 {@code 'SUPER_ADMIN'} 直接写进断言，那么运维改了 role_key 之后，
 * 测试与代码会<b>一起</b>漂移到新的错误值上并双双变绿 ——
 * 这正是第五课补记一"扫描器自己坏掉时长成守护的样子"的形态。
 * 这里改为读取 {@code V1__init_schema.sql} 的真实 INSERT 值，
 * 使"库里的值"与"代码里的判断"无法各自独立地错。
 */
class PermissionServiceSuperAdminRoleKeyTest {

    /** 迁移脚本里 sys_role 的 INSERT 形态：VALUES (..., '超级管理员', 'SUPER_ADMIN', ...) */
    private static final Pattern ROLE_KEY_INSERT =
            Pattern.compile("INSERT\\s+INTO\\s+sys_role\\s*\\([^)]*role_key[^)]*\\)\\s*VALUES\\s*\\(([^)]*)\\)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private PermissionService service;

    @BeforeEach
    void setUp() {
        service = new PermissionService();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 从迁移脚本提取超级管理员的真实 role_key。
     *
     * <p>提取规则：取 {@code role_name} 为"超级管理员"的那一行 INSERT 的第 3 个字面量。
     * 刻意按<b>角色中文名</b>定位而非按行号或"第一个 INSERT"，
     * 否则新增其他角色时定位会静默偏移。
     */
    private static String realSuperAdminRoleKeyFromMigration() throws IOException {
        Path migration = locateMigrationScript();
        assertThat(migration)
                .as("必须能找到 sys_role 的迁移脚本，否则本守护无法取到真值")
                .exists();

        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        Matcher m = ROLE_KEY_INSERT.matcher(sql);
        List<String> candidates = new java.util.ArrayList<>();
        while (m.find()) {
            String values = m.group(1);
            List<String> literals = splitLiterals(values);
            // VALUES (id, role_name, role_key, role_order, ...)
            if (literals.size() >= 3 && "超级管理员".equals(unquote(literals.get(1)))) {
                candidates.add(unquote(literals.get(2)));
            }
        }

        assertThat(candidates)
                .as("迁移脚本里应恰好有一条 role_name='超级管理员' 的 INSERT；实际找到 %s 条，"
                        + "提取规则可能已失效（探针失效，不是数据变了）", candidates.size())
                .hasSize(1);
        return candidates.get(0);
    }

    private static Path locateMigrationScript() {
        // 测试运行时的模块根 = framework-security/
        Path moduleRoot = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && moduleRoot != null; up++) {
            Path candidate = moduleRoot.resolve(
                    "cloud-infra-modules/system-module/src/main/resources/db/migration/V1__init_schema.sql");
            if (Files.exists(candidate)) {
                return candidate;
            }
            moduleRoot = moduleRoot.getParent();
        }
        throw new IllegalStateException(
                "未找到 V1__init_schema.sql（已向上查找 4 层）；"
                        + "若迁移文件被改名或移动，本守护会失败而不是静默跳过");
    }

    /** 按逗号切分 VALUES 内容，忽略括号内逗号（本迁移的角色行无嵌套括号）。 */
    private static List<String> splitLiterals(String values) {
        return Stream.of(values.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String unquote(String literal) {
        String s = literal.trim();
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void authenticateAs(String... authorities) {
        LoginUser principal = LoginUser.builder()
                .userId(1L)
                .username("guard-user")
                .permissions(Set.of(authorities))
                .build();
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                java.util.Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("库里真实的超级管理员 role_key 必须命中旁路")
    void superAdminRoleKeyFromMigrationMustBypassPermissionCheck() throws IOException {
        String realRoleKey = realSuperAdminRoleKeyFromMigration();

        authenticateAs(realRoleKey);

        assertThat(service.hasPermi("some:permission:that:is:not:granted:explicitly"))
                .as("超级管理员(role_key=%s)应无条件通过任意权限检查；"
                        + "若此处失败，说明 PermissionService 的旁路判断与库里的 role_key 再次脱节", realRoleKey)
                .isTrue();
    }

    @Test
    @DisplayName("真实 role_key 的任意大小写变体都命中旁路")
    void roleKeyIsCaseInsensitive() throws IOException {
        String realRoleKey = realSuperAdminRoleKeyFromMigration();

        for (String variant : List.of(realRoleKey.toLowerCase(), realRoleKey.toUpperCase())) {
            SecurityContextHolder.clearContext();
            authenticateAs(variant);
            assertThat(service.hasPermi("x:y:z"))
                    .as("role_key 大小写变体 %s 应同样命中旁路", variant)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("非管理员角色不得命中旁路")
    void nonAdminRoleDoesNotBypass() throws IOException {
        // 取库里另一个真实角色（普通用户），确保负向对照用的是真实数据而非编造值
        String otherRoleKey = otherRoleKeyFromMigration();
        SecurityContextHolder.clearContext();

        authenticateAs(otherRoleKey);

        assertThat(service.hasPermi("x:y:z"))
                .as("普通角色 %s 不得命中超级管理员旁路", otherRoleKey)
                .isFalse();
    }

    @Test
    @DisplayName("相似但不等价的标识不得命中旁路")
    void lookalikeIdentifiersRejected() {
        // 负向对照：防止"大小写不敏感"被实现成"包含即算"或过宽的匹配。
        // 不含空串：SimpleGrantedAuthority 拒绝空白 textual representation，
        // 构造阶段就会抛 IllegalArgumentException 而到不了被测逻辑。
        for (String lookalike : List.of("SUPER_ADMINS", "SUPERADMIN", "SUPER_ADMINX", "SUPER ADMN", "ADMIN")) {
            SecurityContextHolder.clearContext();
            authenticateAs(lookalike);
            assertThat(service.hasPermi("x:y:z"))
                    .as("标识 \"%s\" 不是超级管理员，不得命中旁路", lookalike)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("未认证时一律拒绝")
    void anonymousIsRejected() {
        assertThat(service.hasPermi("x:y:z")).isFalse();
    }

    @Test
    @DisplayName("通配符权限仍然有效")
    void wildcardStillWorks() {
        authenticateAs("*:*:*");
        assertThat(service.hasPermi("x:y:z")).isTrue();
    }

    private static String otherRoleKeyFromMigration() throws IOException {
        Path migration = locateMigrationScript();
        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        Matcher m = ROLE_KEY_INSERT.matcher(sql);
        while (m.find()) {
            List<String> literals = splitLiterals(m.group(1));
            if (literals.size() >= 3 && "普通用户".equals(unquote(literals.get(1)))) {
                return unquote(literals.get(2));
            }
        }
        throw new IllegalStateException("迁移脚本里找不到 role_name='普通用户' 的 INSERT");
    }
}
