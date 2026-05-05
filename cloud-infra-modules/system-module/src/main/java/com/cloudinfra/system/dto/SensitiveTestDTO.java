package com.cloudinfra.system.dto;

import com.cloudinfra.common.core.annotation.Sensitive;
import com.cloudinfra.common.core.enums.SensitiveStrategy;
import lombok.Data;

@Data
public class SensitiveTestDTO {
    @Sensitive(strategy = SensitiveStrategy.PHONE)
    private String phone;

    @Sensitive(strategy = SensitiveStrategy.ID_CARD)
    private String idCard;
}
