package com.cloudinfra.system.grpc;

import com.cloudinfra.system.service.ISysDeptService;
import com.cloudinfra.system.service.ISysUserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SystemGrpcDisabledTest {
    @Test
    void businessServicesAreAbsentByDefault() {
        new ApplicationContextRunner()
            .withBean(ISysUserService.class, () -> mock(ISysUserService.class))
            .withBean(ISysDeptService.class, () -> mock(ISysDeptService.class))
            .withUserConfiguration(SysUserGrpcService.class, SysDeptGrpcService.class, SystemGrpcConfiguration.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean(SysUserGrpcService.class);
                assertThat(context).doesNotHaveBean(SysDeptGrpcService.class);
                assertThat(context).doesNotHaveBean(SystemGrpcConfiguration.class);
                assertThat(context).doesNotHaveBean(io.grpc.BindableService.class);
            });
    }

    @Test
    void explicitFalseAlsoDisablesBusinessServices() {
        new ApplicationContextRunner()
            .withPropertyValues("cloudinfra.system.grpc.enabled=false")
            .withUserConfiguration(SysUserGrpcService.class, SysDeptGrpcService.class, SystemGrpcConfiguration.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean(io.grpc.BindableService.class);
                assertThat(context).doesNotHaveBean(SystemGrpcConfiguration.class);
            });
    }

    @Test
    void explicitTrueRegistersAdaptersWithoutStartingExternalServices() {
        new ApplicationContextRunner()
            .withPropertyValues("cloudinfra.system.grpc.enabled=true")
            .withBean(ISysUserService.class, () -> mock(ISysUserService.class))
            .withBean(ISysDeptService.class, () -> mock(ISysDeptService.class))
            .withBean(jakarta.validation.Validator.class, () -> mock(jakarta.validation.Validator.class))
            .withUserConfiguration(SysUserGrpcService.class, SysDeptGrpcService.class)
            .run(context -> {
                assertThat(context).hasSingleBean(SysUserGrpcService.class);
                assertThat(context).hasSingleBean(SysDeptGrpcService.class);
            });
    }
}
