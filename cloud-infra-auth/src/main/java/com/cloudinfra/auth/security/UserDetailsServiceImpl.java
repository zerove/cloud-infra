package com.cloudinfra.auth.security;

import com.cloudinfra.auth.service.UserService;
import com.cloudinfra.system.domain.SysPermission;
import com.cloudinfra.system.service.ISysPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserService userService;
    private final ISysPermissionService permissionService;

import com.cloudinfra.auth.domain.SecurityUser;
// ... (其他导入保持不变)

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var user = userService.getByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        List<SysPermission> permissions = permissionService.getPermissionsByRoleId(user.getRoleId());
        
        List<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(p -> new SimpleGrantedAuthority(p.getPerms()))
                .collect(Collectors.toList());

        return new SecurityUser(user.getId(), user.getUsername(), user.getPassword(), authorities);
    }
}
