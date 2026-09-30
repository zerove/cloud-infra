package com.cloudinfra.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台 Starter 配置属性
 */
@ConfigurationProperties(prefix = "cloud-infra")
public class PlatformStarterProperties {

    /** 多租户配置 */
    private Tenant tenant = new Tenant();

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public static class Tenant {
        /** 是否启用多租户 */
        private boolean enabled = false;
        /** 是否强制要求租户头 */
        private boolean requireTenant = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRequireTenant() {
            return requireTenant;
        }

        public void setRequireTenant(boolean requireTenant) {
            this.requireTenant = requireTenant;
        }
    }

}
