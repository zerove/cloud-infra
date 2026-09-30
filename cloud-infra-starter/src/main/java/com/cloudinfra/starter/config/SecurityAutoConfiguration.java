package com.cloudinfra.starter.config;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.framework.security.filter.HeaderAuthenticationFilter;
import com.cloudinfra.framework.security.handler.AccessDeniedHandlerImpl;
import com.cloudinfra.framework.security.handler.AuthenticationEntryPointImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 安全相关 Bean 的统一装配。
 *
 * <p><b>必须是 {@code @AutoConfiguration} 而非 {@code @Configuration}</b> (2026-09-30 修复):
 * 业务应用普遍用 {@code scanBasePackages="com.cloudinfra"} 扫描, {@code @Configuration}
 * 类会在**组件扫描阶段**就被处理 —— 那时 {@code InternalCallSecretProvider} 上的
 * {@code @Component} 可能尚未注册, 于是 {@code @ConditionalOnMissingBean} 判定为"缺失",
 * 本类先注册一份, 随后组件扫描再注册一份 →
 * {@code BeanDefinitionOverrideException: bean 'internalCallSecretProvider'}。
 * 改为 {@code @AutoConfiguration} 后本类被 {@code AutoConfigurationExcludeFilter} 排除出
 * 组件扫描, 只在自动装配阶段评估, 顺序问题消失。</p>
 *
 * <p>注意 gateway 不依赖 cloud-infra-starter, 它仍靠 {@code @Component} 获得该 Bean ——
 * 因此 {@code InternalCallSecretProvider} 上的 {@code @Component} 不能删。</p>
 */
@AutoConfiguration
public class SecurityAutoConfiguration {

    /**
     * 内部调用签名密钥提供者。
     *
     * <p>此前只靠 {@code InternalCallSecretProvider} 上的 {@code @Component} + 业务应用的
     * {@code scanBasePackages="com.cloudinfra"} 兜底。这对**只扫描自身包**的测试应用不成立 ——
     * file / notification 的 {@code *TestApplication} 因此拿不到该 Bean,
     * {@code HeaderAuthenticationFilter.secretProvider} 注入失败,
     * 表现为 {@code Failed to load ApplicationContext: Unable to start embedded Tomcat}
     * 而真正原因藏在最底层的 {@code NoSuchBeanDefinitionException} 里。
     * 2026-09-30 CI 恢复运行后才第一次暴露。</p>
     *
     * <p>与 {@code HeaderAuthenticationFilter} 同处此配置类: 谁注册过滤器, 谁就得提供它的依赖。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public InternalCallSecretProvider internalCallSecretProvider() {
        return new InternalCallSecretProvider();
    }

    @Bean
    @ConditionalOnMissingBean(HeaderAuthenticationFilter.class)
    public HeaderAuthenticationFilter headerAuthenticationFilter() {
        return new HeaderAuthenticationFilter();
    }

    @Bean
    @ConditionalOnMissingBean(AccessDeniedHandlerImpl.class)
    public AccessDeniedHandlerImpl accessDeniedHandler() {
        return new AccessDeniedHandlerImpl();
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticationEntryPointImpl.class)
    public AuthenticationEntryPointImpl authenticationEntryPoint() {
        return new AuthenticationEntryPointImpl();
    }
}
