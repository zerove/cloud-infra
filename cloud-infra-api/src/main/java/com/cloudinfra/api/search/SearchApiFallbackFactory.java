package com.cloudinfra.api.search;

import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 搜索服务降级工厂
 */
@Component
public class SearchApiFallbackFactory implements FallbackFactory<SearchApi> {

    @Override
    public SearchApi create(Throwable cause) {
        return request -> Result.fail(500, "搜索服务暂时不可用，请稍后重试");
    }
}
