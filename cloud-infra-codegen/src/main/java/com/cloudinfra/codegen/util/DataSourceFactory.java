package com.cloudinfra.codegen.util;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * 数据源工厂 - 创建 PostgreSQL 数据源
 *
 * <p>Cloud-Infra 平台统一使用 PostgreSQL 16 作为持久化存储。
 */
public class DataSourceFactory {

    /**
     * 创建 PostgreSQL 数据源
     */
    public static DataSource create(Properties props) {
        String url = props.getProperty("url");
        String username = props.getProperty("username");
        String password = props.getProperty("password");

        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("数据库 URL 不能为空");
        }

        if (!url.contains("postgresql")) {
            throw new IllegalArgumentException(
                "Cloud-Infra 平台仅支持 PostgreSQL，当前 URL: " + url);
        }

        return createPostgresDataSource(url, username, password);
    }

    private static DataSource createPostgresDataSource(String url, String username, String password) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setURL(url);
        ds.setUser(username);
        ds.setPassword(password);
        return ds;
    }
}
