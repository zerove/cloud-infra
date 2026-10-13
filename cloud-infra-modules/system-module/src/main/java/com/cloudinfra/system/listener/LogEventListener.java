package com.cloudinfra.system.listener;

import com.cloudinfra.common.core.event.LogEvent;
import com.cloudinfra.common.core.event.LoginLogEvent;
import com.cloudinfra.system.mapper.SysLoginLogMapper;
import com.cloudinfra.system.mapper.SysOperLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LogEventListener {

    private final SysOperLogMapper operLogMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final StreamBridge streamBridge;

    @Async("auditLogTaskExecutor")
    @EventListener
    public void saveLog(LogEvent event) {
        operLogMapper.insert(event.getOperLog());
        // 发送同步消息到搜索服务
        streamBridge.send("search-operlog-out-0", event.getOperLog());
    }

    @Async("auditLogTaskExecutor")
    @EventListener
    public void saveLoginLog(LoginLogEvent event) {
        loginLogMapper.insert(event.getLoginLog());
    }
}
