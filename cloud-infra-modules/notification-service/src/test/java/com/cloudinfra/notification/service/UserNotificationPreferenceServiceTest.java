package com.cloudinfra.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.notification.domain.UserNotificationPreference;
import com.cloudinfra.notification.domain.dto.NotificationPreferenceRequest;
import com.cloudinfra.notification.mapper.UserNotificationPreferenceMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserNotificationPreferenceServiceTest {

    @Mock
    private UserNotificationPreferenceMapper preferenceMapper;

    @InjectMocks
    private UserNotificationPreferenceService service;

    @Test
    void saveForUser_insertsWhenMissing_andReturnsList() {
        given(preferenceMapper.selectOne(any(Wrapper.class))).willReturn(null);
        given(preferenceMapper.selectList(any(Wrapper.class))).willReturn(List.of());

        NotificationPreferenceRequest req = new NotificationPreferenceRequest("SYSTEM_ALERT", "email", Boolean.TRUE);
        service.saveForUser(7L, List.of(req));

        ArgumentCaptor<UserNotificationPreference> captor = ArgumentCaptor.forClass(UserNotificationPreference.class);
        verify(preferenceMapper).insert((UserNotificationPreference) captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getNotificationType()).isEqualTo("SYSTEM_ALERT");
        assertThat(captor.getValue().getChannelCode()).isEqualTo("email");
        assertThat(captor.getValue().getEnabled()).isTrue();
    }

    @Test
    void listByUserId_requiresUserId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.listByUserId(null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void saveForUser_updatesWhenExisting() {
        UserNotificationPreference existing = new UserNotificationPreference();
        existing.setId(99L);
        existing.setUserId(7L);
        existing.setNotificationType("SYSTEM_ALERT");
        existing.setChannelCode("sms");
        existing.setEnabled(Boolean.FALSE);
        given(preferenceMapper.selectOne(any(Wrapper.class))).willReturn(existing);
        given(preferenceMapper.selectList(any(Wrapper.class))).willReturn(List.of(existing));

        NotificationPreferenceRequest req = new NotificationPreferenceRequest("SYSTEM_ALERT", "sms", Boolean.TRUE);
        service.saveForUser(7L, List.of(req));

        verify(preferenceMapper, times(0)).insert(any(UserNotificationPreference.class));
        verify(preferenceMapper).updateById(existing);
        assertThat(existing.getEnabled()).isTrue();
    }
}
