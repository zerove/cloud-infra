package com.cloudinfra.framework.mybatis.interceptor;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.common.core.tenant.TenantContext;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TenantInsertIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private SqlSessionFactory sessions;
    private PGSimpleDataSource dataSource;

    @TenantFilter
    interface PostMapper {
        @Insert("INSERT INTO sys_post (post_code) VALUES (#{code})")
        int insert(@Param("code") String code);

        @Insert("INSERT INTO sys_post (post_code) VALUES (#{first}), (#{second})")
        int insertBatch(@Param("first") String first, @Param("second") String second);

        @Select("SELECT count(*) FROM sys_post")
        int count();
    }

    @BeforeEach
    void configure() throws Exception {
        TenantContext.clear();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS sys_post");
            statement.execute("CREATE TABLE sys_post (post_id BIGSERIAL PRIMARY KEY, post_code TEXT, tenant_id TEXT NOT NULL DEFAULT 'default')");
        }
        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantSqlInterceptor());
        configuration.addInterceptor(interceptor);
        configuration.addMapper(PostMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void storesTenantAndReadsOwnRowsThroughRealMapper() throws Exception {
        TenantContext.setTenantId("tenant-A");
        try (var session = sessions.openSession(true)) {
            var mapper = session.getMapper(PostMapper.class);
            assertThat(mapper.insert("A-1")).isEqualTo(1);
            assertThat(mapper.insertBatch("A-2", "A-3")).isEqualTo(2);
            assertThat(mapper.count()).isEqualTo(3);
        }
        TenantContext.setTenantId("tenant-B");
        try (var session = sessions.openSession(true)) {
            var mapper = session.getMapper(PostMapper.class);
            assertThat(mapper.count()).isZero();
            assertThat(mapper.insert("B-1")).isEqualTo(1);
            assertThat(mapper.count()).isEqualTo(1);
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM sys_post WHERE tenant_id = 'default'")) {
            result.next();
            assertThat(result.getInt(1)).isZero();
        }
    }

    @Test
    void retainsDefaultTenantInSingleTenantMode() {
        try (var session = sessions.openSession(true)) {
            assertThat(session.getMapper(PostMapper.class).insert("single")).isEqualTo(1);
        }
        TenantContext.setTenantId("default");
        try (var session = sessions.openSession(true)) {
            assertThat(session.getMapper(PostMapper.class).count()).isEqualTo(1);
        }
    }
}
