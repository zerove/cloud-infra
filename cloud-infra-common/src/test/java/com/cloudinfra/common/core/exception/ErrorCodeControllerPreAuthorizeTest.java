package com.cloudinfra.common.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 守护 {@link ErrorCodeController} 的类级鉴权注解。
 *
 * <p>背景: 该控制器位于 {@code cloud-infra-common}, 会被每个应用的
 * {@code scanBasePackages("com.cloudinfra")} 扫入 —— 于是 {@code /api/error-codes}
 * 此前在**全部服务**上无鉴权暴露完整错误码表。仓库规则要求非 {@code /internal/}
 * 端点必须带 {@code @PreAuthorize}, 但 {@code ControllerAuthorizationTest}
 * 只扫 {@code com.cloudinfra.system}, 结构上看不见 common 下的控制器。</p>
 *
 * <p>对应的权限点 {@code system:error-code:list} 由 Flyway
 * {@code V25__add_error_code_permission_seed.sql} 播种 (id=45)。**注解与种子必须成对存在**:
 * 只有种子没有注解 → 种子无消费方; 只有注解没有种子 → 所有人被拒。</p>
 */
@DisplayName("ErrorCodeController 类级 @PreAuthorize 守护")
class ErrorCodeControllerPreAuthorizeTest {

    @Test
    @DisplayName("必须带类级 @PreAuthorize 且指向 system:error-code:list")
    void errorCodeControllerMustRequirePermission() {
        PreAuthorize annotation = ErrorCodeController.class.getAnnotation(PreAuthorize.class);

        assertThat(annotation)
            .as("ErrorCodeController 缺少类级 @PreAuthorize, /api/error-codes 将在全部服务上无鉴权暴露")
            .isNotNull();
        assertThat(annotation.value())
            .as("鉴权表达式必须指向 system:error-code:list (与 V25 种子 id=45 一致)")
            .contains("system:error-code:list");
    }
}
