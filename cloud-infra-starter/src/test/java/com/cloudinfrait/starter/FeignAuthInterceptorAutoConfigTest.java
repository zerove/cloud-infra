package com.cloudinfrait.starter;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.framework.web.interceptor.FeignAuthInterceptor;
import com.cloudinfra.starter.config.FeignTraceIdAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 守护内部调用签名拦截器 ({@link FeignAuthInterceptor}) 在<b>非 auth 业务服务</b>里的装配。
 *
 * <p><b>2026-10-05 运行时取证背景</b>: 本类对应的 {@code FeignTraceIdAutoConfiguration}
 * <b>从未被登记进</b> {@code AutoConfiguration.imports}, 因此 Spring 永远不会加载它,
 * {@code FeignAuthInterceptor} bean 在 file/notification/search 等服务里<b>根本不存在</b>。
 *
 * <p>后果链 (每一步都经运行时读回确认, 非推断):
 * <ol>
 *   <li>file-service 发往 system 的 {@code GET /internal/system/user/permissions/{id}}
 *       不带 {@code X-Internal-Call} 签名 → system 的 {@code TenantContextFilter} 判 401;</li>
 *   <li>{@code RemotePermissionProvider} 抛 {@code PermissionFetchException};</li>
 *   <li>{@code HeaderAuthenticationFilter} catch 后<b>降级为空权限</b>;</li>
 *   <li>{@code @PreAuthorize("@ss.hasPermi('file:file:upload')")} 判定 "权限不足"。
 *       —— 尽管该用户在库里<b>确实拥有</b> {@code file:file:upload} (权限 id=20)。</li>
 * </ol>
 *
 * <p>即: <b>库里有权限 ≠ 运行时能通过授权</b>。这是 AGENTS.md 第三条硬规则
 * "能力存在 ≠ 能力在运行" 的又一个实例, 且与 {@code IdempotentOptInTest} 记录的
 * auth 服务启动失败<b>同根因同形态</b>(自动配置类未登记进 imports)。
 *
 * <p>之所以长期未被发现: 该链路的失效表现为 <b>403 权限不足</b> 而非启动失败,
 * 单测不启动真实网络栈, 因此不会被 {@code mvn test} 捕获。
 *
 * <p>本测试不依赖容器, 每次 {@code mvn test} 执行, 分三层守护:
 * <ol>
 *   <li><b>装配层</b>: 该配置被加载时, {@code FeignAuthInterceptor} bean 真的存在;</li>
 *   <li><b>互斥层</b>: auth 服务不会被本配置重复注册(它有自己的 FeignConfig);</li>
 *   <li><b>登记层</b>: 该类被登记进 {@code AutoConfiguration.imports</code>, 且文件里
 *       每个类名都真实存在。</li>
 * </ol>
 *
 * <p><b>为什么必须分三层</b>: 第 1 层用 {@code AutoConfigurations.of(...)} 是<b>显式点名</b>加载,
 * 无论有没有登记进 {@code .imports} 都会装配 —— 它<b>抓不到</b>本次缺陷。
 * 只有第 3 层读 imports 原文才能发现"忘了登记"。反过来, 只写第 3 层也不够:
 * 文件里有名字不代表条件能在真实服务名下成立。三层合起来才闭环。
 *
 * <p><b>2026-10-05 移包</b>: 本类在 {@code com.cloudinfrait.starter} 而非
 * {@code com.cloudinfra.starter.config} —— 内嵌的 {@code @Configuration} 夹具会被业务主类
 * 的 {@code @ComponentScan(basePackages = "com.cloudinfra")} 扫到并污染其他测试的 Spring 上下文。
 * 详见 {@code IdempotentOptInTest} 的类注释与 AGENTS.md 测试夹具隔离一节。
 */
class FeignAuthInterceptorAutoConfigTest {

    /**
     * 守护: 装配层 —— 该自动配置被加载时, 拦截器 bean 真的存在。
     *
     * <p>断言 {@code hasSingleBean} 而非 {@code hasBean}: 若条件失效导致 bean 缺失,
     * 后者会静默通过(第五课补记的"探针恒绿")。
     */
    @Test
    @DisplayName("装配层: 非 auth 服务里 FeignAuthInterceptor bean 真的存在")
    void interceptorBeanIsWiredForBusinessServices() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FeignTraceIdAutoConfiguration.class))
                .withUserConfiguration(SecretHolderConfig.class)
                .withPropertyValues("spring.application.name=file-service")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FeignAuthInterceptor.class);
                });
    }

    /**
     * 守护: 互斥层 —— auth 服务<b>不</b>被本配置注册。
     *
     * <p>auth 的真实 {@code spring.application.name} 是 <b>{@code cloud-auth}</b>
     * (见 {@code deploy/config/auth.yaml}), <b>不是</b> {@code cloud-infra-auth}
     * —— 后者只是<b>镜像名</b>。本用例用真实名, 避免守护再次被"名字写错"骗过。
     *
     * <p>若这个条件失效, auth 就会同时有本配置与它自己的 {@code FeignConfig} 两个
     * {@code feignAuthInterceptor} 定义, 或用错密钥来源。
     */
    @Test
    @DisplayName("互斥层: auth 服务(cloud-auth)由自己的 FeignConfig 提供, 本配置不重复注册")
    void authServiceKeepsItsOwnInterceptorDefinition() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FeignTraceIdAutoConfiguration.class))
                .withUserConfiguration(SecretHolderConfig.class)
                .withPropertyValues("spring.application.name=cloud-auth")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FeignAuthInterceptor.class);
                });
    }

    /**
     * 守护: 装配层 —— <b>没有任何</b> {@code spring.application.name} 时也必须装配。
     *
     * <p>防的是占位符默认值被写成 {@code cloud-auth} 那类错误:
     * {@code ${spring.application.name:cloud-auth}} 会让"未设置名字"的服务被误判成 auth 而跳过。
     * 这里默认值必须是<b>空串</b>, 使未设置名字时条件成立。
     */
    @Test
    @DisplayName("装配层: 未设置 spring.application.name 时仍装配(不误判为 auth)")
    void interceptorIsWiredWhenApplicationNameMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FeignTraceIdAutoConfiguration.class))
                .withUserConfiguration(SecretHolderConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FeignAuthInterceptor.class);
                });
    }

    /**
     * 守护: 登记层 —— 类被登记进 {@code .imports}。
     *
     * <p>本断言是<b>本次缺陷的直接防线</b>: 上面两条用
     * {@code AutoConfigurations.of(...)} <b>显式</b>加载该配置, 所以无论它有没有被登记进
     * {@code .imports} 都会通过 —— 少了这一条, 整组守护就是恒绿。
     */
    @Test
    @DisplayName("登记层: FeignTraceIdAutoConfiguration 已登记进 AutoConfiguration.imports")
    void autoConfigurationIsRegisteredInImportsFile() {
        assertThat(readAutoConfigurationImports())
                .as("AutoConfiguration.imports 中应包含 %s; "
                        + "未登记会导致业务服务无签名拦截器 → 权限拉取 401 → @PreAuthorize 误判权限不足",
                        FeignTraceIdAutoConfiguration.class.getName())
                .contains(FeignTraceIdAutoConfiguration.class.getName());
    }

    /**
     * 守护: 登记层 —— imports 里登记的每个类都必须在 classpath 上真实存在。
     *
     * <p>防的是另一种形态: 登记了一个<b>被改名/被删</b>的类名。Spring 在加载 imports 时
     * 遇到不存在的类会抛 {@code IllegalArgumentException}, 而该异常发生在很早期,
     * 容易被误读成"服务启动慢"; 这里提前把它变成一条可读的失败信息。
     */
    @Test
    @DisplayName("登记层: imports 中的每个类都可被 Class.forName 加载")
    void everyRegisteredAutoConfigurationClassExists() throws Exception {
        List<String> registered = readAutoConfigurationImports();
        assertThat(registered)
                .as("imports 不应为空; 空说明探针读错了位置, 而非真的没配置")
                .isNotEmpty();
        for (String className : registered) {
            assertThat(Class.forName(className))
                    .as("imports 登记的类必须真实存在: %s", className)
                    .isNotNull();
        }
    }

    /** 读取 classpath 上的 {@code AutoConfiguration.imports} (本模块自己那份)。 */
    private List<String> readAutoConfigurationImports() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring/"
                        + "org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(in).as("imports 资源应在 classpath 上; 为 null 说明探针路径写错").isNotNull();
            List<String> names = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    names.add(trimmed);
                }
            }
            return names;
        } catch (IOException e) {
            throw new AssertionError("读取 AutoConfiguration.imports 失败", e);
        }
    }

    /** 提供 {@link InternalCallSecretProvider}, 避免 fail-fast 的密钥 bean 干扰本测试关注点。 */
    @Configuration(proxyBeanMethods = false)
    static class SecretHolderConfig {

        @Bean
        InternalCallSecretProvider internalCallSecretProvider() {
            InternalCallSecretProvider provider = mock(InternalCallSecretProvider.class);
            when(provider.getSecret()).thenReturn("unit-test-secret-not-a-real-key");
            return provider;
        }
    }
}