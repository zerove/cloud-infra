package com.cloudinfra.starter.config;

import com.cloudinfra.framework.web.filter.TraceIdFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TraceIdAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TraceIdFilter.class)
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new TraceIdFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(-100); // 确保在最前面执行
        return registrationBean;
    }
}
