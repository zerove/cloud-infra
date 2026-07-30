package com.cloudinfra.gateway.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.*;

public class IdentityRequestWrapper extends HttpServletRequestWrapper {
    private final Map<String, String> customHeaders;

    public IdentityRequestWrapper(HttpServletRequest request, Map<String, String> headers) {
        super(request);
        this.customHeaders = new HashMap<>(headers);
    }

    @Override
    public String getHeader(String name) {
        String value = customHeaders.get(name);
        return (value != null) ? value : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
        names.addAll(customHeaders.keySet());
        return Collections.enumeration(names);
    }
}
