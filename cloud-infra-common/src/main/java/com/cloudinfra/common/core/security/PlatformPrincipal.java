package com.cloudinfra.common.core.security;

import java.util.Set;

/**
 * 平台身份模型
 * 统一的用户身份表示，所有服务通过 PlatformSecurityContext 获取
 */
public record PlatformPrincipal(
    Long userId,
    String username,
    String tenantId,
    Long deptId,
    Long roleId,
    Set<String> permissions,
    java.util.Map<String, String> attributes
) {
    public static Builder builder() {
        return new Builder();
    }

    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }

    public static class Builder {
        private Long userId;
        private String username;
        private String tenantId;
        private Long deptId;
        private Long roleId;
        private Set<String> permissions = Set.of();
        private java.util.Map<String, String> attributes = java.util.Map.of();

        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder deptId(Long deptId) { this.deptId = deptId; return this; }
        public Builder roleId(Long roleId) { this.roleId = roleId; return this; }
        public Builder permissions(Set<String> permissions) { this.permissions = permissions; return this; }
        public Builder attributes(java.util.Map<String, String> attributes) { this.attributes = attributes; return this; }

        public PlatformPrincipal build() {
            return new PlatformPrincipal(userId, username, tenantId, deptId, roleId, permissions, attributes);
        }
    }
}