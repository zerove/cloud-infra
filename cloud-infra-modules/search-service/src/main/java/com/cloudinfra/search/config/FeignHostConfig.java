package com.cloudinfra.search.config;

import feign.Client;
import feign.Request;
import feign.Response;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feign 客户端: 拦截请求, 替换 service 名 host 为配置的 IP.
 *
 * <p>背景: 容器内 'notification-service' 等 DNS 解析到 podman 内部错误 IP 198.18.0.30,
 * 造成 Feign 连接超时/拒绝. 改用环境变量 SERVICE_HOST_MAP (格式 service:ip)
 * 显式映射, 完全绕开容器 DNS.</p>
 *
 * <p>当前由 search-service 使用 (跨服务调 notification-service).</p>
 *
 * <p>配置: deploy/config/search-service.yaml 加顶级 key:
 *   SERVICE_HOST_MAP: notification-service:10.89.0.5</p>
 */
@Configuration
public class FeignHostConfig {

    @Bean
    public Client feignClientWithHostMapping(Environment env) {
        Map<String, String> hostMap = parseMapping(env.getProperty("SERVICE_HOST_MAP", ""));
        return (request, options) -> {
            String url = request.url();
            // 解析 host 部分 (http://host:port/path)
            int protoEnd = url.indexOf("://");
            int hostStart = protoEnd >= 0 ? protoEnd + 3 : 0;
            int pathStart = url.indexOf('/', hostStart);
            int portStart = url.indexOf(':', hostStart);
            String hostPart;
            String portPart;
            if (portStart > 0 && (pathStart < 0 || portStart < pathStart)) {
                hostPart = url.substring(hostStart, portStart);
                portPart = url.substring(portStart, pathStart > 0 ? pathStart : url.length());
            } else {
                hostPart = url.substring(hostStart, pathStart > 0 ? pathStart : url.length());
                portPart = "";
            }

            String mapped = hostMap.get(hostPart);
            if (mapped == null) {
                // 兼容: host:port 形式
                for (Map.Entry<String, String> e : hostMap.entrySet()) {
                    if (hostPart.equals(e.getKey() + portPart)) {
                        mapped = e.getValue() + portPart;
                        break;
                    }
                }
            }
            if (mapped != null && !mapped.equals(hostPart + portPart)) {
                String newUrl = url.substring(0, hostStart) + mapped
                    + (pathStart > 0 ? url.substring(pathStart) : "");
                request = Request.create(request.httpMethod(), newUrl, request.headers(),
                    request.body(), request.charset(), request.requestTemplate());
            }
            return new Client.Default(null, null).execute(request, options);
        };
    }

    static Map<String, String> parseMapping(String mapStr) {
        if (mapStr == null || mapStr.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String entry : mapStr.split(",")) {
            String[] kv = entry.split(":", 2);
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }
}
