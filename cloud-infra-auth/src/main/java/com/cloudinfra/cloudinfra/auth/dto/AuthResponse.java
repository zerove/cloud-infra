package com.cloudinfra.cloudinfra.auth.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
@Data @AllArgsConstructor public class AuthResponse { private String accessToken; private UserInfo userInfo; }
