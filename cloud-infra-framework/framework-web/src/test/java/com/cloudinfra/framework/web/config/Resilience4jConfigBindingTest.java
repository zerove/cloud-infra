package com.cloudinfra.framework.web.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判定题：{@code resilience4j.*} 的阈值最终<b>由哪条路径</b>进入注册表。
 *
 * <p>本仓库存在两条互相竞争的绑定路径：
 * <ol>
 *   <li>官方 {@code CircuitBreakerAutoConfiguration}，读 {@code instances} / {@code configs}；</li>
 *   <li>本项目自有的 {@link Resilience4jProperties}（扁平 POJO 字段）+
 *       {@link Resilience4jAutoConfiguration#circuitBreakerConfig()}（{@code @ConditionalOnMissingBean}）。</li>
 * </ol>
 *
 * <p>历史上 {@code deploy/config/*.yaml} 把阈值<b>平铺</b>在 {@code circuitbreaker:} 之下。
 * 若只有路径 1 生效，则平铺键不可绑定、静默回落库默认值；
 * 但路径 2 的 POJO 恰好定义了扁平字段，因此<b>不能靠读代码断定</b>——必须实测。
 *
 * <p>本测试刻意同时给出两套配置并断言最终生效值，从而把"谁赢"固化为可执行事实。
 */
@SpringBootTest(classes = Resilience4jConfigBindingTest.TestConfig.class, properties = {
        // 路径 2：自定义扁平 POJO 的键
        "resilience4j.circuitbreaker.minimum-number-of-calls=7",
        "resilience4j.circuitbreaker.sliding-window-size=7",
        // 路径 1：官方 instances 键（与 Feign 注解 name 对应）
        "resilience4j.circuitbreaker.instances.fileService.minimum-number-of-calls=11",
        "resilience4j.circuitbreaker.instances.fileService.sliding-window-size=11",
        "resilience4j.circuitbreaker.instances.fileService.wait-duration-in-open-state=13s",
        "resilience4j.circuitbreaker.instances.fileService.slow-call-duration-threshold=17s",
        "resilience4j.ratelimiter.instances.fileService.limit-for-period=17",
        "resilience4j.retry.instances.fileService.max-attempts=19"
})
class Resilience4jConfigBindingTest {

    @SpringBootConfiguration
    @Import({AopAutoConfiguration.class, Resilience4jAutoConfiguration.class})
    static class TestConfig {
    }

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    /**
     * 官方 {@code instances.<name>} 路径必须真的生效。
     * 这是 {@code @CircuitBreaker(name = "fileService")} 唯一能被调优的入口——
     * 若它不生效，下游服务熔断阈值就只能靠库默认值，无法按服务区分。
     */
    @Test
    void namedInstanceFromOfficialInstancesBlockBinds() {
        CircuitBreakerConfig cfg = circuitBreakerRegistry.circuitBreaker("fileService").getCircuitBreakerConfig();

        assertThat(cfg.getMinimumNumberOfCalls())
                .as("instances.fileService.minimum-number-of-calls 必须覆盖库默认值 100")
                .isEqualTo(11);
        assertThat(cfg.getSlidingWindowSize()).isEqualTo(11);
        assertThat(cfg.getSlowCallDurationThreshold())
                .as("instances.fileService.slow-call-duration-threshold 必须覆盖库默认值 60s")
                .isEqualTo(Duration.ofSeconds(17));
    }

    @Test
    void namedRateLimiterAndRetryInstancesBind() {
        RateLimiterConfig rl = rateLimiterRegistry.rateLimiter("fileService").getRateLimiterConfig();
        assertThat(rl.getLimitForPeriod()).isEqualTo(17);

        RetryConfig retry = retryRegistry.retry("fileService").getRetryConfig();
        assertThat(retry.getMaxAttempts()).isEqualTo(19);
    }

    /**
     * 固化"平铺键不生效"这一机制，作为 {@code deploy/config} 必须用 {@code instances/configs}
     * 结构的<b>运行时证据</b>。
     *
     * <p>官方 {@code CircuitBreakerAutoConfiguration} 先注册 {@code CircuitBreakerConfig}，
     * 导致本项目 {@link Resilience4jAutoConfiguration#circuitBreakerConfig()} 上的
     * {@code @ConditionalOnMissingBean} 退让，于是其
     * {@link Resilience4jProperties} 的扁平字段<b>永远到不了注册表</b>。
     *
     * <p>因此下方断言刻意写成"等于库默认值 100"而非 7：一旦哪天官方自动配置不再优先、
     * 扁平键开始生效，这条断言会失败并提醒同步更新
     * {@code deploy/tests/test_resilience4j_config_shape.py} 的前提。
     */
    @Test
    void flatKeysDoNotReachRegistryOfficialAutoConfigWins() {
        CircuitBreakerConfig cfg = circuitBreakerRegistry.circuitBreaker("someUnnamed").getCircuitBreakerConfig();

        assertThat(cfg.getMinimumNumberOfCalls())
                .as("平铺键不得生效; 生效即说明配置来源已改变, 需同步复核 yaml 结构")
                .isEqualTo(100);
        assertThat(cfg.getSlidingWindowSize()).isEqualTo(100);
    }
}