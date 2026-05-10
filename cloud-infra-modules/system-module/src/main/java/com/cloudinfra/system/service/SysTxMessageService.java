package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.SysTxMessage;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;

public interface SysTxMessageService extends IService<SysTxMessage> {
    void saveMessage(SysTxMessage message);
    void updateMessageStatus(Long id, Integer status);
    List<SysTxMessage> findPendingMessages();
}
