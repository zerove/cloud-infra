package com.cloudinfra.framework.security.filter;

import com.cloudinfra.common.util.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired(required = false)
    private UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            String jwt = token.substring(7);
            if (JwtUtils.validateToken(jwt)) {
                String username = JwtUtils.getUsernameFromToken(jwt);
                
                UserDetails userDetails = null;
                if (userDetailsService != null) {
                    userDetails = userDetailsService.loadUserByUsername(username);
                } else {
                    // If no UserDetailsService, create a minimal UserDetails from JWT info
                    userDetails = org.springframework.security.core.userdetails.User.withUsername(username)
                            .password("")
                            .authorities(new java.util.ArrayList<>())
                            .build();
                }
                
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }
}