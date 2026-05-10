package com.cloudinfra.search.mq;

import com.cloudinfra.common.core.domain.entity.SysOperLog;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.repository.OperLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * 搜索消息消费者
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SearchConsumer {

    private final OperLogRepository operLogRepository;
    private final ObjectMapper objectMapper;

    @Bean
    public Consumer<SysOperLog> operLogConsumer() {
        return operLog -> {
            log.info("接收到操作日志同步消息: {}", operLog.getOperId());
            try {
                // 转换为 ES 文档对象
                OperLogDocument document = new OperLogDocument();
                document.setOperId(operLog.getOperId());
                document.setTitle(operLog.getTitle());
                document.setBusinessType(operLog.getBusinessType());
                document.setMethod(operLog.getMethod());
                document.setOperName(operLog.getOperName());
                document.setOperUrl(operLog.getOperUrl());
                document.setOperIp(operLog.getOperIp());
                document.setOperParam(operLog.getOperParam());
                document.setJsonResult(operLog.getJsonResult());
                document.setStatus(operLog.getStatus());
                document.setErrorMsg(operLog.getErrorMsg());
                document.setOperTime(operLog.getOperTime());
                
                operLogRepository.save(document);
                log.info("操作日志已成功同步至 ES: {}", operLog.getOperId());
            } catch (Exception e) {
                log.error("同步操作日志至 ES 失败: {}", operLog.getOperId(), e);
            }
        };
    }
}
