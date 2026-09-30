package com.cloudinfra.auth.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginUser implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long userId;
    private String username;
    private String terminal; // PC, APP, MINI
    private String token;
}
