package com.cloudinfra.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台 Starter 配置属性
 */
@ConfigurationProperties(prefix = "cloud-infra")
public class PlatformStarterProperties {

    /** 多租户配置 */
    private Tenant tenant = new Tenant();

    /** Outbox 配置 */
    private Outbox outbox = new Outbox();

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
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

    public static class Outbox {
        /** 是否启用 Outbox */
        private boolean enabled = true;
        /** 轮询间隔（毫秒） */
        private long pollIntervalMs = 1000;
        /** 批次大小 */
        private int batchSize = 100;
        /** 清理天数 */
        private int cleanupDays = 7;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getCleanupDays() {
            return cleanupDays;
        }

        public void setCleanupDays(int cleanupDays) {
            this.cleanupDays = cleanupDays;
        }
    }
}