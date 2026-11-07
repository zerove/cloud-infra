package com.cloudinfra.gateway.filter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模块断言"由注释声称存在、实际并不存在"的守护。
 *
 * <p>历史：{@code SecurityFilter} 的 {@code TENANT_HEADER} 注释曾写着
 * "由 {@code TenantHeaderNameConsistencyTest} 做跨模块一致性断言"，
 * 但该测试<b>从未存在</b>。这与本仓库记录的「门禁自己从未被调用」同源：
 * 注释把一个未兑现的承诺当既成事实写下，下一个人便不会再补。
 * 2026-10-18 修复后网关不再复制租户头字面量，改为直接引用 common 的
 * {@code TenantContextFilter.TENANT_HEADER}；本测试守护"保持引用而非拷贝"这一结构。
 *
 * <p>trace 头曾出现同类缺陷且已实际发生：
 * 网关注入 {@code traceId}，而下游 {@code TraceIdFilter} /
 * {@code FeignTraceIdInterceptor} 读 {@code X-Trace-Id}，
 * 于是网关 ID 在服务边界被丢弃、每个服务各自重新生成。
 * 本测试把该不变量固定下来。
 */
class HeaderNameConsistencyTest {

    /** surefire 的工作目录是模块 basedir, 向上找到含 deploy/config 的目录即仓库根。
     *  不能用"pom.xml 含 artifactId cloud-infra"来判定: 每个模块的 pom 都在
     *  &lt;parent&gt; 里写了该 artifactId, 会把模块自身误判成根。 */
    private static final Path REPO_ROOT = findRepoRoot();

    private static Path findRepoRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("deploy").resolve("config"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("未找到仓库根目录");
    }

    private static final String SECURITY_FILTER =
            "cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/filter/SecurityFilter.java";
    private static final String TENANT_CONTEXT_FILTER =
            "cloud-infra-common/src/main/java/com/cloudinfra/common/core/tenant/TenantContextFilter.java";
    private static final String TRACE_ID_FILTER =
            "cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/filter/TraceIdFilter.java";

    @Test
    void tenantHeaderNameIsSharedNotCopied() {
        // 值相等可以靠巧合, 引用同一常量才是结构性保证: 改 common 一处, 网关自动跟随。
        // 若有人退回"复制字面量"的写法, 即便值暂时相同, 也在这里报红。
        assertThat(read(SECURITY_FILTER))
                .as("网关应引用 common 的租户头常量, 而非再复制一份字面量")
                .contains("String TENANT_HEADER = TenantContextFilter.TENANT_HEADER;");
        assertThat(constantOf(TENANT_CONTEXT_FILTER, "TENANT_HEADER"))
                .as("租户头的唯一字面量在 TenantContextFilter, 且线上协议名锁定为 X-Tenant-Id")
                .isEqualTo("X-Tenant-Id");
    }

    @Test
    void traceHeaderNameIsIdenticalInGatewayAndFrameworkWeb() {
        assertThat(constantOf(SECURITY_FILTER, "TRACE_ID"))
                .as("网关注入的 trace 头名必须与 TraceIdFilter 读取的一致, 否则 trace 在服务边界断裂")
                .isEqualTo(constantOf(TRACE_ID_FILTER, "TRACE_ID_HEADER"));
    }

    /**
     * MDC 键刻意不同于 HTTP 头名：logback 的 pattern 用 {@code %X{traceId}}。
     * 两者并排时 {@code traceId} 极易被误当成笔误"修好"，从而静默抽掉全部日志的 traceId。
     */
    @Test
    void mdcKeyStaysLowercaseTraceId() {
        assertThat(constantOf(SECURITY_FILTER, "TRACE_ID_MDC_KEY"))
                .as("MDC 键被 logback %X{traceId} 依赖, 不得改为 X-Trace-Id")
                .isEqualTo("traceId");
        assertThat(constantOf(TRACE_ID_FILTER, "MDC_TRACE_ID"))
                .as("网关与下游必须共用同一个 MDC 键")
                .isEqualTo("traceId");
    }

    /** 网关未引入 framework-web，缺少其白名单校验，故必须自带一份同规则的校验。 */
    @Test
    void gatewayValidatesInboundTraceIdAgainstAllowList() {
        String source = read(SECURITY_FILTER);
        assertThat(source)
                .as("外部可控的 trace 头必须校验, 否则可向网关日志注入任意内容")
                .contains("SAFE_TRACE_ID");
        assertThat(source).contains("!SAFE_TRACE_ID.matcher(tempTraceId).matches()");
    }

    @Test
    void gatewayTraceHeaderIsNotTheOldBrokenName() {
        assertThat(constantOf(SECURITY_FILTER, "TRACE_ID"))
                .as("旧实现用的是裸 traceId, 已确认导致下游丢弃网关 ID")
                .isNotEqualTo("traceId");
    }

    private static String constantOf(String relativePath, String constantName) {
        Matcher m = Pattern.compile(
                        "static\\s+final\\s+String\\s+" + Pattern.quote(constantName) + "\\s*=\\s*\"([^\"]*)\"")
                .matcher(read(relativePath));
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        assertThat(found).as("%s 中应恰好定义一个 %s 常量", relativePath, constantName).hasSize(1);
        return found.get(0);
    }

    private static String read(String relativePath) {
        Path path = REPO_ROOT.resolve(relativePath);
        assertThat(Files.exists(path)).as("源文件不存在: %s", path).isTrue();
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + path, e);
        }
    }
}