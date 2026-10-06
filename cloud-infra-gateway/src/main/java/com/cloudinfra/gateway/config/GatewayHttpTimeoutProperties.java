package com.cloudinfra.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 网关转发到后端服务时的 HTTP 客户端超时配置。
 *
 * <p>取值来源（后者覆盖前者）：
 * <ol>
 *   <li>本类默认值</li>
 *   <li>Nacos 的 {@code gateway.yaml} 中 {@code gateway.http-timeout.*} 键</li>
 * </ol>
 *
 * <p>为什么不直接把值写死在 {@link GatewayClientConfig} 里：
 * 文件上传是大 body + 低带宽的组合，耗时天然高于普通 JSON 请求，
 * 而一个全局读超时必须同时满足"大文件能传完"和"下游卡死时要能及时失败"。
 * 固定写死任一值都会让其中一个目标落空，所以下沉为配置项。
 *
 * <p><b>命名约束（改名会静默失效）</b>：配置键由 getter/setter 的 Java Bean 名推出，
 * 而不是由字段名推出。因此 {@code read} 字段的访问器必须叫 {@code getRead}/{@code setRead}；
 * 若叫 {@code getReadTimeout}，属性名就变成 {@code readTimeout}，
 * 此时写 {@code gateway.http-timeout.read} 会<b>不报错地</b>退回默认值 60s。
 * 本类的守护测试正是为此而存在（它首次运行时真的报出了这个错）。
 *
 * <p><b>注</b>：本类只保留真正被 {@link GatewayClientConfig} 读取的项。
 * 曾经额外加过一个 {@code idle} 键，但它没有任何消费方 —— 删掉而不是留下，
 * 因为"配置项存在却不生效"正是本类要修的那类缺陷，不该自己再犯一次。
 *
 * <p><b>历史缺陷</b>：{@code GatewayClientConfig} 曾声明 {@code READ_TIMEOUT} 常量
 * 但从未使用，实际生效的是 {@code RequestConfig} 里的字面量 {@code ofSeconds(10)}。
 * 改那个常量不会有任何效果 —— 这正是 {@code GatewayTimeoutPropertiesTest} 守护的行为。
 */
@Component
@ConfigurationProperties(prefix = "gateway.http-timeout")
public class GatewayHttpTimeoutProperties {

    /** 建立 TCP 连接的最长等待时间。 */
    private Duration connect = Duration.ofSeconds(3);

    /** 从连接池借用连接的最长等待时间。 */
    private Duration connectionRequest = Duration.ofSeconds(3);

    /** 等待下游响应数据的最长等待时间。
     *
     * <p>上传大文件时该值必须大于"服务端处理耗时 + 传输耗时"，
     * 否则网关会先于服务端放弃，表现为客户端看到 500 而服务端日志全是 200。
     *
     * <p>本项目实测：{@code file/upload} 经网关的 20MiB 上传耗时 2.6~5.2 秒；
     * 原先的 10 秒余量仅 2~4 倍，宿主负载高峰时会被击穿。
     */
    private Duration read = Duration.ofSeconds(60);

    public Duration getConnect() {
        return connect;
    }

    public void setConnect(Duration connect) {
        this.connect = connect;
    }

    public Duration getConnectionRequest() {
        return connectionRequest;
    }

    public void setConnectionRequest(Duration connectionRequest) {
        this.connectionRequest = connectionRequest;
    }

    public Duration getRead() {
        return read;
    }

    public void setRead(Duration read) {
        this.read = read;
    }
}