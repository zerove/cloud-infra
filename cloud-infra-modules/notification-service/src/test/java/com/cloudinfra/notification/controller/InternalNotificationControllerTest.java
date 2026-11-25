package com.cloudinfra.notification.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.service.INotificationService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InternalNotificationControllerTest {

    @Mock
    private INotificationService notificationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InternalNotificationController controller = new InternalNotificationController(notificationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
    }

    @Test
    void internalLogs_passesRequestedUserId_forTrustedAuditAggregation() throws Exception {
        // S2-B3 regression guard: user-facing legacy logs reject cross-user; internal HMAC contract must not.
        NotificationLog log = new NotificationLog();
        log.setNotificationId(7L);
        log.setChannelCode("email");
        log.setStatus("SUCCESS");
        log.setErrorMessage(null);
        log.setCreateTime(LocalDateTime.of(2026, 11, 8, 12, 0));
        given(notificationService.getLogs(eq(999L))).willReturn(List.of(log));

        mockMvc.perform(get("/internal/notification/logs")
                .param("userId", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].notificationId").value(7))
            .andExpect(jsonPath("$.data[0].channelCode").value("email"));

        then(notificationService).should().getLogs(eq(999L));
    }
}
