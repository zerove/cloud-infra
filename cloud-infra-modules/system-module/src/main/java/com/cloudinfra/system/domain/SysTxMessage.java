package com.cloudinfra.system.domain;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;

import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_tx_message")
public class SysTxMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String topic;

    private String content;

    /**
     * 状态 (0:待发送, 1:已发送, 2:已处理, 3:处理失败)
     */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
