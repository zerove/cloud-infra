package com.cloudinfra.notification.controller;

import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.ErrorCode;
import com.cloudinfra.notification.service.INotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * markAsRead HTTP 契约 (issue 12): userId 只认安全上下文, 语义失败映射进 body code。
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerMarkAsReadTest {

    private static final long AUTHENTICATED_USER_ID = 7L;

    @Mock
    private INotificationService notificationService;

    private NotificationController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new NotificationController(notificationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        LoginUser loginUser = LoginUser.builder().userId(AUTHENTICATED_USER_ID).build();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void markAsRead_shouldUseAuthenticatedUserIdNotClientInput() throws Exception {
        // when: 伪造的头传 999 也不该被采信 —— 身份只能来自验签后的安全上下文
        mockMvc.perform(put("/notification/read/42").header("X-User-Id", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value(200));

        // then
        then(notificationService).should().markAsRead(42L, AUTHENTICATED_USER_ID);
    }

    @Test
    void markAsRead_whenNotificationNotFound_shouldReturn404Body() throws Exception {
        // given
        willThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "通知不存在"))
            .given(notificationService).markAsRead(99L, AUTHENTICATED_USER_ID);

        // when / then: HTTP 200 + body code 404 (语义失败不伪装成服务故障, FileController 同规)
        MvcResult result = mockMvc.perform(put("/notification/read/99"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(404))
            .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .contains("通知不存在");
    }

    @Test
    void markAsRead_whenNotOwner_shouldReturn403Body() throws Exception {
        // given
        willThrow(new BusinessException(ErrorCode.NO_PERMISSION, "无权操作该通知"))
            .given(notificationService).markAsRead(5L, AUTHENTICATED_USER_ID);

        // when / then
        MvcResult result = mockMvc.perform(put("/notification/read/5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(403))
            .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .contains("无权操作该通知");
    }

    @Test
    void markAsRead_whenUnexpectedBusinessCode_shouldPropagateNotSwallow() {
        // given: 未预期错误码 (非 1005/1006) 不在显式映射内, 必须原样上抛给全局处理器
        willThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "boom"))
            .given(notificationService).markAsRead(77L, AUTHENTICATED_USER_ID);

        // when / then
        assertThatThrownBy(() -> controller.markAsRead(77L))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("code", ErrorCode.SYSTEM_ERROR.getCode());
    }
}
