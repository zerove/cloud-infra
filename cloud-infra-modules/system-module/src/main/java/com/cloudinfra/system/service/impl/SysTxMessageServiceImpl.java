package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysTxMessage;
import com.cloudinfra.system.mapper.SysTxMessageMapper;
import com.cloudinfra.system.service.SysTxMessageService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class SysTxMessageServiceImpl extends ServiceImpl<SysTxMessageMapper, SysTxMessage> implements SysTxMessageService {

    @Override
    public void saveMessage(SysTxMessage message) {
        save(message);
    }

    @Override
    public void updateMessageStatus(Long id, Integer status) {
        SysTxMessage message = new SysTxMessage();
        message.setId(id);
        message.setStatus(status);
        updateById(message);
    }

    @Override
    public void updateRetryCount(Long id, Integer count) {
        SysTxMessage message = new SysTxMessage();
        message.setId(id);
        message.setRetryCount(count);
        updateById(message);
    }

    @Override
    public List<SysTxMessage> findPendingMessages() {
        return list(new LambdaQueryWrapper<SysTxMessage>()
                .eq(SysTxMessage::getStatus, 0)
                .or()
                .eq(SysTxMessage::getStatus, 3));
    }
}
