package com.cloudinfra.auth.security;

import com.cloudinfra.auth.service.UserService;
import java.util.List;
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
        var user = userService.getUserById(Long.valueOf(username));
        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }


        // Permissions might be in LoginUser already
        List<SimpleGrantedAuthority> authorities = user
            .getPermissions()
            .stream()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toList());

        return org.springframework.security.core.userdetails.User.withUsername(
            user.getUsername()
        )
            .password("") // Password handling needs to be addressed separately
            .authorities(authorities)
            .build();
    }
}
