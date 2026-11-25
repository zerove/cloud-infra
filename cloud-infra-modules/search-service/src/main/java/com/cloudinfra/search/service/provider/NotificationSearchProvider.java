package com.cloudinfra.search.service.provider;

import com.cloudinfra.api.notification.dto.NotificationDTO;
import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.domain.UnifiedSearchResult;
import com.cloudinfra.search.service.SearchProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSearchProvider implements SearchProvider {

    private final com.cloudinfra.api.notification.NotificationApi notificationApi;

    @Override
    public List<UnifiedSearchResult> search(String query, int limit) {
        try {
            PageQuery pageQuery = new PageQuery();
            pageQuery.setPageSize(limit);
            Result<PageResult<NotificationDTO>> result = notificationApi.searchNotifications(query, pageQuery);
            if (result == null || result.getData() == null || result.getData().getRecords() == null) {
                return Collections.emptyList();
            }

            return result.getData().getRecords().stream()
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
            log.warn("通知搜索 provider 异常, 本 provider 返回空结果", e);
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