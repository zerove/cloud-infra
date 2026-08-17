package com.cloudinfra.search.service.provider;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationDTO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.domain.UnifiedSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class NotificationSearchProviderTest {

    @Mock
    private NotificationApi notificationApi;

    private NotificationSearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new NotificationSearchProvider(notificationApi);
    }

    @Test
    void search_shouldMapNotificationsToResults() {
        NotificationDTO dto = new NotificationDTO(
                5L, 1L, null, "系统升级通知", "今晚 23:00 维护",
                null, LocalDateTime.of(2026, 8, 17, 9, 0), null);
        given(notificationApi.searchNotifications("升级")).willReturn(Result.success(List.of(dto)));

        List<UnifiedSearchResult> results = provider.search("升级", 10);

        assertThat(results).hasSize(1);
        UnifiedSearchResult r = results.get(0);
        assertThat(r.resourceId()).isEqualTo("5");
        assertThat(r.resourceType()).isEqualTo("NOTIFICATION");
        assertThat(r.title()).isEqualTo("系统升级通知");
        assertThat(r.snippet()).isEqualTo("今晚 23:00 维护");
        assertThat(r.timestamp()).isEqualTo(LocalDateTime.of(2026, 8, 17, 9, 0));
        assertThat(r.url()).isEqualTo("/notification/history/5");
    }

    @Test
    void search_shouldRespectLimit() {
        NotificationDTO dto1 = new NotificationDTO(
                1L, 1L, null, "t1", "c1", null, LocalDateTime.now(), null);
        NotificationDTO dto2 = new NotificationDTO(
                2L, 1L, null, "t2", "c2", null, LocalDateTime.now(), null);
        given(notificationApi.searchNotifications("x")).willReturn(Result.success(List.of(dto1, dto2)));

        List<UnifiedSearchResult> results = provider.search("x", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).resourceId()).isEqualTo("1");
    }

    @Test
    void search_whenResultNull_shouldReturnEmptyList() {
        given(notificationApi.searchNotifications("x")).willReturn(null);

        assertThat(provider.search("x", 5)).isEmpty();
    }

    @Test
    void search_whenDataNull_shouldReturnEmptyList() {
        given(notificationApi.searchNotifications("x")).willReturn(Result.success(null));

        assertThat(provider.search("x", 5)).isEmpty();
    }

    @Test
    void search_whenApiThrows_shouldReturnEmptyList() {
        given(notificationApi.searchNotifications("x")).willThrow(new RuntimeException("feign down"));

        assertThat(provider.search("x", 5)).isEmpty();
    }

    @Test
    void resourceTypeAndWeight_shouldBeNotification() {
        assertThat(provider.getResourceType()).isEqualTo("NOTIFICATION");
        assertThat(provider.getWeight()).isEqualTo(1.0);
    }
}
