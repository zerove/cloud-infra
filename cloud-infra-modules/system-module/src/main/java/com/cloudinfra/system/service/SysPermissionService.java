package com.cloudinfra.system.service;

import java.util.List;

public interface SysPermissionService {
    List<String> getPermsByUserId(Long userId);
}
