package com.cloudinfra.gateway.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关认证白名单配置属性。
 *
 * <p>替代 AuthEvaluator 构造期逐索引轮询 Environment 的做法：
 * ConfigurationProperties 走标准绑定器，支持 YAML 列表与逗号分隔两种形态，
 * 且在 Nacos 配置变更 + RefreshEvent 时自动重绑定，无需重启即可
 * 经 {@code reloadFrom()} 应用新值。</p>
 */
@ConfigurationProperties(prefix = "auth")
public class AuthWhiteListProperties {

    /** 白名单路径列表：精确路径或以 /* 结尾的前缀路径 */
    private List<String> whiteList = new ArrayList<>();

    public List<String> getWhiteList() {
        return whiteList;
    }

    public void setWhiteList(List<String> whiteList) {
        this.whiteList = whiteList;
    }
}
