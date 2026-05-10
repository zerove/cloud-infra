package com.cloudinfra.system.event;

import com.cloudinfra.common.event.OperLogEvent;
import com.cloudinfra.system.domain.SysOperLog;
import com.cloudinfra.system.mapper.SysOperLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.util.Date;

@Component
public class OperLogListener {

    @Autowired
    private SysOperLogMapper operLogMapper;

    @Async
    @EventListener
    public void handleOperLogEvent(OperLogEvent event) {
        SysOperLog operLog = new SysOperLog();
        operLog.setTitle(event.getTitle());
        operLog.setBusinessType(event.getBusinessType());
        operLog.setOperTime(new Date());
        operLog.setJsonResult(event.getJsonResult());
        operLogMapper.insert(operLog);
    }
}
