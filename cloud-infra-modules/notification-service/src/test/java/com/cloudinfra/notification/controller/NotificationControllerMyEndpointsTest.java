package com.cloudinfra.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.notification.service.INotificationService;
import com.cloudinfra.notification.service.NotificationQuotaService;
import com.cloudinfra.notification.service.UserNotificationPreferenceService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class NotificationControllerMyEndpointsTest {

    private static final long AUTHENTICATED_USER_ID = 42L;

    @Mock
    private INotificationService notificationService;
    @Mock
    private NotificationQuotaService quotaService;
    @Mock
    private UserNotificationPreferenceService preferenceService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        NotificationController controller =
            new NotificationController(notificationService, quotaService, preferenceService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .setValidator(new Validator() {
                @Override
                public boolean supports(Class<?> clazz) {
                    return true;
                }

                @Override
                public void validate(Object target, Errors errors) {
                    // no-op: avoid HV000151 from NotificationApi/Controller constraint mismatch in standalone setup
                }
            })
            .build();
        LoginUser loginUser = LoginUser.builder().userId(AUTHENTICATED_USER_ID).build();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void myHistory_usesAuthenticatedUser_notClientUserIdParam() throws Exception {
        given(notificationService.getNotificationHistory(eq(AUTHENTICATED_USER_ID), isNull(), isNull(), any(PageQuery.class)))
            .willReturn(PageResult.empty());

        mockMvc.perform(get("/notification/my/history").param("userId", "999").param("pageNum", "1").param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        then(notificationService).should()
            .getNotificationHistory(eq(AUTHENTICATED_USER_ID), isNull(), isNull(), any(PageQuery.class));
    }

    @Test
    void getPreferences_usesAuthenticatedUser() throws Exception {
        given(preferenceService.listByUserId(AUTHENTICATED_USER_ID)).willReturn(List.of());

        mockMvc.perform(get("/notification/preferences"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        then(preferenceService).should().listByUserId(AUTHENTICATED_USER_ID);
    }

    @Test
    void legacyHistory_rejectsCrossUser_with403_B3() throws Exception {
        // S2-B3: 旧接口禁止跨用户查询，防止任意 userId 遍历
        mockMvc.perform(get("/notification/history").param("userId", "999").param("pageNum", "1").param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(403));

        then(notificationService).shouldHaveNoInteractions();
    }

    @Test
    void legacyHistory_allowsSameUser_forCompatibility() throws Exception {
        given(notificationService.getNotificationHistory(eq(AUTHENTICATED_USER_ID), isNull(), isNull(), any(PageQuery.class)))
            .willReturn(PageResult.empty());

        mockMvc.perform(get("/notification/history").param("userId", "42").param("pageNum", "1").param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        then(notificationService).should()
            .getNotificationHistory(eq(AUTHENTICATED_USER_ID), isNull(), isNull(), any(PageQuery.class));
    }

    @Test
    void legacyLogs_rejectsCrossUser_with403_B3() throws Exception {
        mockMvc.perform(get("/notification/logs").param("userId", "999").param("pageNum", "1").param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(403));

        then(notificationService).shouldHaveNoInteractions();
    }

    @Test
    void legacyLogs_allowsSameUser_forCompatibility() throws Exception {
        given(notificationService.getLogs(eq(AUTHENTICATED_USER_ID), any(PageQuery.class)))
            .willReturn(PageResult.empty());

        mockMvc.perform(get("/notification/logs").param("userId", "42").param("pageNum", "1").param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        then(notificationService).should().getLogs(eq(AUTHENTICATED_USER_ID), any(PageQuery.class));
    }

    @Test
    void savePreferences_usesAuthenticatedUser() throws Exception {
        given(preferenceService.saveForUser(eq(AUTHENTICATED_USER_ID), any())).willReturn(List.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/notification/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"notificationType\":\"SYSTEM_ALERT\",\"channelCode\":\"email\",\"enabled\":true}]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        then(preferenceService).should().saveForUser(eq(AUTHENTICATED_USER_ID), any());
    }

    @Test
    void getQuota_usesAuthenticatedUser() throws Exception {
        given(quotaService.getQuotaStatus(AUTHENTICATED_USER_ID))
            .willReturn(new NotificationQuotaService.QuotaStatus(1, 1000, 999, 3600));

        mockMvc.perform(get("/notification/quota"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.used").value(1))
            .andExpect(jsonPath("$.data.remaining").value(999));

        then(quotaService).should().getQuotaStatus(AUTHENTICATED_USER_ID);
    }

    @Test
    void myLogs_usesAuthenticatedUser_notClientUserIdParam() throws Exception {
        given(notificationService.getLogs(eq(AUTHENTICATED_USER_ID), any(PageQuery.class)))
            .willReturn(PageResult.empty());

        mockMvc.perform(get("/notification/my/logs").param("userId", "999").param("pageNum", "1").param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        then(notificationService).should().getLogs(eq(AUTHENTICATED_USER_ID), any(PageQuery.class));
    }
}
