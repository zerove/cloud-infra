package com.cloudinfra.gateway.handler;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.enums.ResultCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class GatewayBlockExceptionHandler implements BlockRequestHandler {
    @Override
    public Mono<ServerResponse> handleRequest(ServerWebExchange exchange, Throwable ex) {
        return ServerResponse.status(200)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Result.error(ResultCode.BUSINESS_ERROR.getCode(), "系统繁忙，请稍后再试"));
    }
}
