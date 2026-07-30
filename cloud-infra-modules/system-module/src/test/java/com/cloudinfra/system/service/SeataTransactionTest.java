package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.service.impl.SysUserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/**
 * Seata 分布式事务验证测试
 *
 * 验证 {@code @GlobalTransactional} 注解在异常场景下的回滚行为。
 * 真实 Seata TC Server 集成测试需要 docker-compose 环境（参见 deploy/compose.yaml），
 * 本测试通过 Mockito 模拟 Mapper 层验证业务逻辑与注解协同的正确性。
 */
@ExtendWith(MockitoExtension.class)
class SeataTransactionTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ISysConfigService configService;

    @Captor
    private ArgumentCaptor<SysUser> userCaptor;

    private SysUserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new SysUserServiceImpl(userMapper, passwordEncoder, configService);
    }

    @Test
    void verifySeataTransaction_shouldThrowExceptionAfterUpdate() {
        // given
        Long userId = 1L;
        given(userMapper.updateById(any(SysUser.class))).willReturn(1);

        // when / then: 方法抛出模拟异常
        assertThatThrownBy(() -> userService.verifySeataTransaction(userId, "UpdatedName"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Seata Verification Failure");

        // then: 验证 updateById 被执行过一次
        then(userMapper).should(times(1)).updateById(userCaptor.capture());

        SysUser captured = userCaptor.getValue();
        assertThat(captured.getUserId()).isEqualTo(userId);
        assertThat(captured.getNickName()).isEqualTo("UpdatedName");
    }

    @Test
    void verifySeataTransaction_withNonExistentUser_shouldStillThrow() {
        // given
        Long userId = 999L;
        // 即使 updateById 影响 0 行（用户不存在），方法仍应抛出异常
        given(userMapper.updateById(any(SysUser.class))).willReturn(0);

        // when / then
        assertThatThrownBy(() -> userService.verifySeataTransaction(userId, "NewName"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Seata Verification Failure");
    }
}
