package com.cloudinfra.common.core.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CloudMessage<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    private String traceId;
    private T data;
}
