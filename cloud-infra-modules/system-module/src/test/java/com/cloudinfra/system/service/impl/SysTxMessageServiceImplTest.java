package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cloudinfra.system.domain.SysTxMessage;
import com.cloudinfra.system.mapper.SysTxMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事务消息服务单元测试
 * 覆盖：消息保存、状态更新、重试次数更新、待处理消息查询（status 0/3）
 */
@ExtendWith(MockitoExtension.class)
public class SysTxMessageServiceImplTest {

    @Mock
    private SysTxMessageMapper txMessageMapper;

    @InjectMocks
    private SysTxMessageServiceImpl txMessageService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(txMessageService, "baseMapper", txMessageMapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysTxMessage.class);
    }

    @Test
    void shouldSaveMessage() {
        SysTxMessage message = new SysTxMessage();
        message.setId(1L);
        message.setTopic("CREATE_USER");
        when(txMessageMapper.insert(any(SysTxMessage.class))).thenReturn(1);

        txMessageService.saveMessage(message);

        verify(txMessageMapper).insert(any(SysTxMessage.class));
    }

    @Test
    void shouldUpdateMessageStatus() {
        when(txMessageMapper.updateById(any(SysTxMessage.class))).thenReturn(1);

        txMessageService.updateMessageStatus(5L, 2);

        verify(txMessageMapper).updateById(any(SysTxMessage.class));
    }

    @Test
    void shouldUpdateRetryCount() {
        when(txMessageMapper.updateById(any(SysTxMessage.class))).thenReturn(1);

        txMessageService.updateRetryCount(5L, 3);

        verify(txMessageMapper).updateById(any(SysTxMessage.class));
    }

    @Test
    void shouldFindPendingMessages() {
        SysTxMessage m1 = new SysTxMessage();
        m1.setId(1L);
        m1.setStatus(0);
        when(txMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(m1));

        List<SysTxMessage> result = txMessageService.findPendingMessages();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(0);
    }

    @Test
    void shouldFindNoPendingWhenMapperEmpty() {
        when(txMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<SysTxMessage> result = txMessageService.findPendingMessages();

        assertThat(result).isEmpty();
    }
}