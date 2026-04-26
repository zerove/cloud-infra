package com.cloudinfra.framework.security.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.Collection;

@Service("ss")
public class PermissionService {

    public boolean hasPermission(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        
        Collection<?> authorities = auth.getAuthorities();
        return authorities.stream()
                .anyMatch(a -> a.toString().equals(permission));
    }
}
