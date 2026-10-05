package com.cloudinfra.starter.config;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.framework.web.interceptor.FeignAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign Trace ID / 内部调用签名 自动配置.
 * <p>仅在非 auth 模块注册: auth 模块有自己的 {@code FeignConfig} 定义 feignAuthInterceptor,
 * 避免 bean 冲突。
 *
 * <p><b>2026-10-05 修复</b>: 原条件写作
 * {@code @ConditionalOnProperty(prefix="spring.application", name="name",
 * havingValue="cloud-infra-auth", matchIfMissing=false)}, 有两处错误, 叠加后导致本配置
 * <b>在任何服务里都不会生效</b>:
 * <ol>
 *   <li><b>名字不存在</b>: auth 的真实应用名是 {@code cloud-auth}
 *       (见 {@code deploy/config/auth.yaml}), 而 {@code cloud-infra-auth} 只是
 *       <b>镜像名</b>, 在 Java 代码与配置中零引用。因此该 {@code havingValue}
 *       永远匹配不到任何服务。</li>
 *   <li><b>语义反了</b>: {@code havingValue=X} 的含义是"当属性<b>等于</b> X 时条件成立",
 *       而注释要表达的是"<b>排除</b> auth"。即便名字写对, 结果也会是"只有 auth 生效,
 *       其余业务服务都不装配" —— 与意图完全相反。</li>
 * </ol>
 *
 * <p>运行时后果 (经真实链路取证): file-service 发往 system 的
 * {@code GET /internal/system/user/permissions/{id}} 不带 {@code X-Internal-Call} 签名,
 * system 判 401 → 权限拉取失败 → 降级为空权限 →
 * {@code @PreAuthorize} 报"权限不足", 尽管该用户在库里<b>确实拥有</b>该权限。
 * 这正是 AGENTS.md 第三条硬规则 "能力存在 ≠ 能力在运行" 的形态:
 * 类存在、bean 方法存在, 但<b>装配条件永不成立</b>。
 *
 * <p>守护见 {@code com.cloudinfrait.starter.FeignAuthInterceptorAutoConfigTest}。
 */
@Configuration
@ConditionalOnClass(feign.RequestInterceptor.class)
// 排除 auth 模块: 该模块自带 cloud-infra-auth/.../config/FeignConfig 定义 feignAuthInterceptor。
//
// 2026-10-05 修正: 原写法 @ConditionalOnProperty(havingValue="cloud-infra-auth",
// matchIfMissing=false) 有两处错误, 叠加后本配置在任何服务里都不生效:
//   1) cloud-infra-auth 只是【镜像名】, auth 的真实 spring.application.name 是 cloud-auth;
//   2) havingValue 表达的是"等于", 而此处需要"不等于" —— 语义相反。
// @ConditionalOnProperty 无法表达"不等于", 故改用 @ConditionalOnExpression。
@ConditionalOnExpression("!'cloud-auth'.equals('${spring.application.name:}')")
public class FeignTraceIdAutoConfiguration {

    @Autowired
    private InternalCallSecretProvider secretProvider;

    @Bean
    @ConditionalOnMissingBean(FeignAuthInterceptor.class)
    public FeignAuthInterceptor feignAuthInterceptor() {
        // jasypt 4.0.4 注入 null 问题: 走 Spring 容器内统一持有者, 绕开 Nacos PropertySource 包装
        return new FeignAuthInterceptor(secretProvider.getSecret());
    }
}
