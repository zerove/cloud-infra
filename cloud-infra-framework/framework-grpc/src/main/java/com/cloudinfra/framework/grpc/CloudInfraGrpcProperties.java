package com.cloudinfra.framework.grpc;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cloud-Infra gRPC 配置属性.
 *
 * <p>前缀: {@code cloudinfra.grpc}
 * <pre>
 * cloudinfra:
 *   grpc:
 *     port: 9090
 *     in-process-server-name: cloud-infra-grpc
 *     enabled: true
 *     max-inbound-message-size: 4194304
 *     keep-alive-time: 30s
 *     keep-alive-timeout: 10s
 *     permit-keep-alive-without-calls: true
 *     max-connection-idle: 5m
 *     max-connection-age: 30m
 *     max-connection-age-grace: 5m
 *     # SSL 配置 (可选)
 *     ssl:
 *       enabled: false
 *       cert-chain-file: classpath:grpc-server.pem
 *       private-key-file: classpath:grpc-server.key
 *     # 调用安全边界 (可选, 默认关闭)
 *     security:
 *       enabled: false
 *       require-internal-sign: true
 *       require-tenant: true
 *       require-user: true
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "cloudinfra.grpc")
public class CloudInfraGrpcProperties {

    /** gRPC server 端口. 默认 9090 (与 REST 8080 分离). */
    private Integer port = 9090;

    /** 进程内 gRPC server 名称 (用于本地测试). */
    private String inProcessServerName;

    /** 是否启用 gRPC server. 默认 true. */
    private boolean enabled = true;

    /** 最大入站消息大小 (字节). 默认 4MB. */
    private Integer maxInboundMessageSize = 4 * 1024 * 1024;

    /** Keep-alive 时间间隔. 默认 30 秒. */
    private String keepAliveTime = "30s";

    /** Keep-alive 超时. 默认 10 秒. */
    private String keepAliveTimeout = "10s";

    /** 允许无调用时的 keep-alive. 默认 true. */
    private Boolean permitKeepAliveWithoutCalls = true;

    /** 最大连接空闲时间. 默认 5 分钟. */
    private String maxConnectionIdle = "5m";

    /** 最大连接存活时间. 默认 30 分钟. */
    private String maxConnectionAge = "30m";

    /** 最大连接存活宽限期. 默认 5 分钟. */
    private String maxConnectionAgeGrace = "5m";

    /** SSL 配置 */
    private Ssl ssl = new Ssl();

    /** 调用安全边界配置 (M7-02) */
    private Security security = new Security();

    @Data
    public static class Ssl {
        /** 是否启用 SSL/TLS */
        private boolean enabled = false;

        /** 证书链文件路径 */
        private String certChainFile;

        /** 私钥文件路径 */
        private String privateKeyFile;
    }

    /**
     * gRPC 调用安全边界 (M7-02).
     *
     * <p>默认全部关闭, 保证既有实验性接入零影响; 生产开启时建议
     * {@code enabled=true} + {@code require-internal-sign=true} + {@code require-tenant=true}。
     */
    @Data
    public static class Security {
        /** 是否启用安全边界拦截器. 默认 false (仅受信网络内可用). */
        private boolean enabled = false;

        /**
         * 是否要求内部调用 HMAC 签名 ({@code X-Internal-Call} 等三头).
         * 生产环境必须为 true, 防止绕过网关直连伪造身份。
         */
        private boolean requireInternalSign = true;

        /** 是否要求 {@code X-Tenant-Id} 元数据. 关闭则允许默认租户。 */
        private boolean requireTenant = true;

        /** 是否要求 {@code X-User-Id} 元数据. 关闭则允许纯服务间调用。 */
        private boolean requireUser = true;
    }

    /**
     * 解析时长字符串为毫秒.
     * 支持单位: ns, us, ms, s, m, h, d
     */
    public long parseDuration(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.endsWith("ns")) {
            return Long.parseLong(v.substring(0, v.length() - 2));
        }
        if (v.endsWith("us")) {
            return Long.parseLong(v.substring(0, v.length() - 2)) * 1000;
        }
        if (v.endsWith("ms")) {
            return Long.parseLong(v.substring(0, v.length() - 2)) * 1_000_000;
        }
        if (v.endsWith("s")) {
            return Long.parseLong(v.substring(0, v.length() - 1)) * 1_000_000_000;
        }
        if (v.endsWith("m")) {
            return Long.parseLong(v.substring(0, v.length() - 1)) * 60 * 1_000_000_000;
        }
        if (v.endsWith("h")) {
            return Long.parseLong(v.substring(0, v.length() - 1)) * 3600 * 1_000_000_000;
        }
        if (v.endsWith("d")) {
            return Long.parseLong(v.substring(0, v.length() - 1)) * 86400 * 1_000_000_000;
        }
        // 默认毫秒
        return Long.parseLong(v) * 1_000_000;
    }

    /** 获取 keepAliveTime 纳秒 */
    public long getKeepAliveTimeNanos() {
        return parseDuration(keepAliveTime);
    }

    /** 获取 keepAliveTimeout 纳秒 */
    public long getKeepAliveTimeoutNanos() {
        return parseDuration(keepAliveTimeout);
    }

    /** 获取 maxConnectionIdle 纳秒 */
    public long getMaxConnectionIdleNanos() {
        return parseDuration(maxConnectionIdle);
    }

    /** 获取 maxConnectionAge 纳秒 */
    public long getMaxConnectionAgeNanos() {
        return parseDuration(maxConnectionAge);
    }

    /** 获取 maxConnectionAgeGrace 纳秒 */
    public long getMaxConnectionAgeGraceNanos() {
        return parseDuration(maxConnectionAgeGrace);
    }
}
