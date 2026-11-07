package com.cloudinfra.system.migration;

import com.cloudinfra.test.support.containers.InfrastructureContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时证据 (#17): 在真实 PostgreSQL 上跑完整 {@code db/migration} 迁移链,
 * 断言 pg_trgm GIN 索引确实被创建。
 *
 * <p>结构守护 ({@code UserSearchTrgmIndexGuardTest}) 只能证明"迁移文件写对了";
 * 只有真正把 SQL 交给数据库执行, 才能证明它可执行且索引存在。
 */
@Testcontainers
class UserSearchTrgmIndexIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("cloud_infra")
        .withUsername("postgres")
        .withPassword("password")
        .withStartupTimeout(InfrastructureContainers.STARTUP_TIMEOUT);

    @Test
    @DisplayName("迁移链执行后 sys_user 上存在 GIN(gin_trgm_ops) 索引")
    void migrationCreatesGinTrgmIndex() throws Exception {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT indexdef FROM pg_indexes "
                     + "WHERE tablename = 'sys_user' AND indexname = 'idx_sys_user_username_trgm'")) {
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("迁移后应存在 idx_sys_user_username_trgm").isTrue();
                assertThat(rs.getString(1))
                    .containsIgnoringCase("gin")
                    .containsIgnoringCase("gin_trgm_ops")
                    .containsIgnoringCase("username");
            }
        }
    }
}
