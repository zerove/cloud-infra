package com.cloudinfra.auth.service.impl;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.api.system.dto.UserCredentialDTO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserApi userApi;

    @InjectMocks
    private UserServiceImpl userService;

    private UserCredentialDTO credential() {
        UserCredentialDTO dto = new UserCredentialDTO();
        dto.setUserId(1L);
        dto.setUsername("admin");
        dto.setDeptId(100L);
        dto.setRoleId(200L);
        dto.setPassword("hashed-password");
        dto.setPermissions(Set.of("system:user:list", "system:role:list"));
        return dto;
    }

    @Test
    void shouldMapCredentialToLoginUserOnGetUserById() {
        when(userApi.getUserCredentialById(1L)).thenReturn(Result.success(credential()));

        LoginUser user = userService.getUserById(1L);

        assertThat(user).isNotNull();
        assertThat(user.getUserId()).isEqualTo(1L);
        assertThat(user.getUsername()).isEqualTo("admin");
        assertThat(user.getDeptId()).isEqualTo(100L);
        assertThat(user.getRoleId()).isEqualTo(200L);
        assertThat(user.getPassword()).isEqualTo("hashed-password");
        assertThat(user.getPermissions()).containsExactlyInAnyOrder("system:user:list", "system:role:list");
    }

    @Test
    void shouldMapCredentialToLoginUserOnGetUserByUsername() {
        when(userApi.getUserCredentialByUsername("admin")).thenReturn(Result.success(credential()));

        LoginUser user = userService.getUserByUsername("admin");

        assertThat(user).isNotNull();
        assertThat(user.getUsername()).isEqualTo("admin");
        assertThat(user.getPermissions()).containsExactlyInAnyOrder("system:user:list", "system:role:list");
    }

    @Test
    void shouldReturnNullWhenResultIsNull() {
        when(userApi.getUserCredentialById(1L)).thenReturn(null);

        assertThat(userService.getUserById(1L)).isNull();
    }

    @Test
    void shouldReturnNullWhenResultCodeIsNotSuccess() {
        when(userApi.getUserCredentialById(1L)).thenReturn(Result.fail("user not found"));

        assertThat(userService.getUserById(1L)).isNull();
    }

    @Test
    void shouldReturnNullWhenDataIsNull() {
        when(userApi.getUserCredentialById(1L)).thenReturn(Result.success(null));

        assertThat(userService.getUserById(1L)).isNull();
    }

    @Test
    void shouldUseEmptyPermissionsWhenDtoPermissionsAreNull() {
        UserCredentialDTO dto = credential();
        dto.setPermissions(null);
        when(userApi.getUserCredentialByUsername("admin")).thenReturn(Result.success(dto));

        LoginUser user = userService.getUserByUsername("admin");

        assertThat(user.getPermissions()).isEmpty();
    }
}
