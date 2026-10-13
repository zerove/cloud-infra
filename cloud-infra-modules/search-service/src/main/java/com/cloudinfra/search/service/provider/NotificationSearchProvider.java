package com.cloudinfra.search.service.provider;

import com.cloudinfra.api.notification.dto.NotificationDTO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.domain.UnifiedSearchResult;
import com.cloudinfra.search.service.SearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class NotificationSearchProvider implements SearchProvider {

    private final com.cloudinfra.api.notification.NotificationApi notificationApi;

    @Override
    public List<UnifiedSearchResult> search(String query, int limit) {
        try {
            Result<List<NotificationDTO>> result = notificationApi.searchNotifications(query, 1, limit);
            if (result == null || result.getData() == null) {
                return Collections.emptyList();
            }

            return result.getData().stream()
                .limit(limit)
                .map(n -> new UnifiedSearchResult(
                    n.id().toString(),
                    getResourceType(),
                    n.title(),
                    n.content(),
                    1.0,
                    n.createTime(),
                    "/notification/history/" + n.id()
                ))
                .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public String getResourceType() {
        return "NOTIFICATION";
    }

    @Override
    public double getWeight() {
        return 1.0;
    }
}