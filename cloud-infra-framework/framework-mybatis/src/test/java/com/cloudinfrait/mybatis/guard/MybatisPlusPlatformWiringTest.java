package com.cloudinfrait.mybatis.guard;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 守护 {@code MybatisPlusPlatformAutoConfiguration} 真的能被装配。
 *
 * <p><b>为什么需要这个守护</b>: 2026-10-02 在 system-module 的独立子进程里
 * 查出 {@code sqlSessionFactory} 缺失, 所有 Mapper 报
 * "Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required",
 * 而启动日志里 DataSource / Flyway / Hikari <b>一个字都没有</b>。
 *
 * <p>根因是条件注解: 该配置类原先用
 * {@code @ConditionalOnSingleCandidate(DataSource.class)}。
 * Spring 的条件求值报告(ConditionEvaluationReport)显示它
 * "did not find any beans", 而<em>同一个报告里</em> {@code dataSource} bean
 * 已经存在 —— 其他用 {@code @ConditionalOnBean(DataSource)} 的自动配置
 * (DataSourcePoolMetricsAutoConfiguration、FlywayAutoConfiguration)
 * 都正常写着 "found bean 'dataSource'"。
 *
 * <p>也就是说: bean <b>定义</b>已存在, 但 {@code @ConditionalOnSingleCandidate}
 * 在自动配置求值阶段判定为"无单候选"(它需要候选 bean 完成 {@code @Primary}
 * 解析, 而此时另一个 jar 里的 DataSourceOptimizationAutoConfiguration
 * 刚注册定义、尚未实例化), 于是整支被跳过。
 *
 * <p><b>已有测试为什么没抓到</b>: framework-mybatis 原有的测试都用
 * {@code ApplicationContextRunner} 局部装配, DataSource 由测试自己提供,
 * 走不到"跨 jar 自动配置排序"这条路径 —— 与第三条硬规则里
 * "自测全绿但能力从未生效" 是同一类盲区。
 *
 * <p><b>本守护怎么做到有效</b>: 用 {@code AutoConfigurations.of(...)}
 * 显式给出<em>两个</em>自动配置的相对顺序, 并让 DataSource 由
 * {@code DataSourceOptimizationAutoConfiguration} 这类"另一个配置类"提供 ——
 * 复现真实运行时"DataSource 定义先注册、但尚未实例化"的时刻。
 */
class MybatisPlusPlatformWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TestDataSourceConfiguration.class,
                    com.cloudinfra.framework.mybatis.config.MybatisPlusPlatformAutoConfiguration.class));

    @Test
    @DisplayName("DataSource 已定义时, sqlSessionFactory 必须真的被创建")
    void sqlSessionFactoryIsCreatedWhenDataSourcePresent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // 前置: DataSource 必须存在, 否则本测试会因错误的原因通过
            assertThat(context).hasSingleBean(DataSource.class);
            // 被守护的断言: 修复前这里失败, 因为整支配置类被条件跳过
            assertThat(context).hasSingleBean(SqlSessionFactory.class);
        });
    }

    @Test
    @DisplayName("类级不得有 DataSource 条件注解 —— 跨 jar 排序下会静默跳过整支")
    void mustNotUseClassLevelDataSourceCondition() {
        // 结构性守护: 锁住本次修复的具体决策。
        //
        // 教训: 换成 @ConditionalOnBean **并不成立** —— 实测在局部
        // ApplicationContextRunner 下同样判为不满足, 属于换一种方式再踩坑。
        // 正确做法是类级不加任何 DataSource 条件, 由 sqlSessionFactory 的
        // `DataSource dataSource` 参数注入在实例化阶段兜住(缺 bean 会报清晰的
        // NoSuchBeanDefinitionException, 而非静默跳过整支)。
        var conditions = com.cloudinfra.framework.mybatis.config.MybatisPlusPlatformAutoConfiguration.class
                .getAnnotations();
        for (var a : conditions) {
            String name = a.annotationType().getSimpleName();
            boolean isDataSourceCondition = name.equals("ConditionalOnSingleCandidate")
                    || name.equals("ConditionalOnBean");
            assertThat(isDataSourceCondition && mentionsDataSource(a))
                    .as("MybatisPlusPlatformAutoConfiguration 类级不得有 DataSource 条件注解(实际: @%s): "
                            + "它在自动配置排序阶段会把已定义的 dataSource 判为不满足, "
                            + "导致 sqlSessionFactory 缺失、所有 Mapper 装配失败", name)
                    .isFalse();
        }
    }

    /**
     * 判断条件注解里是否引用了 DataSource。
     *
     * <p>不用反射读 value(会抛受检异常): 直接 instanceof 判定即可。
     * 注意两者签名不同: {@code ConditionalOnBean.value()} 是
     * {@code Class<?>[]}, 而 {@code ConditionalOnSingleCandidate.value()}
     * 是单个 {@code Class<?>}。
     */
    private static boolean mentionsDataSource(java.lang.annotation.Annotation a) {
        if (a instanceof org.springframework.boot.autoconfigure.condition.ConditionalOnBean onBean) {
            for (Class<?> c : onBean.value()) {
                if (c == DataSource.class) {
                    return true;
                }
            }
        } else if (a instanceof org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate single) {
            return single.value() == DataSource.class;
        }
        return false;
    }

    /** 提供一个 DataSource, 模拟"另一个自动配置类注册了 DataSource 定义"的场景。 */
    @Configuration(proxyBeanMethods = false)
    static class TestDataSourceConfiguration {

        @Bean
        DataSource dataSource() {
            // 不真正建连接: 本测试只关心 bean 是否被装配, 不访问数据库
            return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { DataSource.class },
                    (proxy, method, args) -> null);
        }
    }
}