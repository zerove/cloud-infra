package com.cloudinfra.framework.security.config;

import com.cloudinfra.common.core.security.IPermissionProvider;
import com.cloudinfra.framework.security.provider.RemotePermissionProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@ConditionalOnClass(com.cloudinfra.api.system.UserApi.class)
public class FrameworkSecurityAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(FrameworkSecurityAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public IPermissionProvider remotePermissionProvider(com.cloudinfra.api.system.UserApi userApi) {
        log.info("Creating RemotePermissionProvider bean...");
        return new RemotePermissionProvider(userApi);
    }
}
