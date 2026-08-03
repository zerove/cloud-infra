package com.cloudinfra.auth.security;

import com.cloudinfra.auth.domain.SecurityUser;
import com.cloudinfra.auth.service.UserService;
import com.cloudinfra.common.core.domain.model.LoginUser;
import java.util.Collections;
import java.util.stream.Collectors;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserService userService;

    public UserDetailsServiceImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String username)
        throws UsernameNotFoundException {
        LoginUser user = userService.getUserByUsername(username);
        if (user == null || user.getUserId() == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        return new SecurityUser(
            user.getUserId(),
            user.getUsername(),
            user.getPassword() == null ? "" : user.getPassword(),
            user.getPermissions() == null
                ? Collections.emptyList()
                : user.getPermissions()
                    .stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList())
        );
    }
}
