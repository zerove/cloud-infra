package com.cloudinfra.system.domain;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;

import com.cloudinfra.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字典类型表 sys_dict_type
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_type")
public class SysDictType extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dictName;
    private String dictType;
    private String status;
    private String remark;
}
