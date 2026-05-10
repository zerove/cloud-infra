package com.cloudinfra.gateway.handler;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.enums.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;

/**
 * Sentinel 限流自定义异常处理器 (Gateway MVC)
 */
@Component
public class GatewayBlockExceptionHandler implements BlockExceptionHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, String resourceName, BlockException e) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=utf-8");

        Result<?> result = Result.error(ResultCode.BUSINESS_ERROR.getCode(), "系统繁忙，请稍后再试 (Sentinel Blocked)");

        ObjectMapper objectMapper = new ObjectMapper();
        PrintWriter out = response.getWriter();
        out.print(objectMapper.writeValueAsString(result));
        out.flush();
        out.close();
    }
}
