package com.cloudinfra.framework.mybatis.interceptor;

import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.common.core.tenant.TenantContext;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantInsertInterceptorTest {
    @TenantFilter interface PostMapper { void insert(); }
    @TenantFilter(rejectWhenMissing = true) interface StrictMapper { void insert(); }
    @TenantFilter(column = "org_id") interface CustomMapper { void insert(); }
    interface PlainMapper { void insert(); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void insertsCurrentTenantWithoutAddingWhere() {
        TenantContext.setTenantId("tenant-A");
        assertThat(rewrite(PostMapper.class, "INSERT INTO sys_post (post_code) VALUES (?)"))
            .isEqualTo("INSERT INTO sys_post (post_code, tenant_id) VALUES (?, 'tenant-A')");
    }

    @Test
    void insertsTenantForEveryBatchRowAndKeepsReturning() {
        TenantContext.setTenantId("tenant-A");
        assertThat(rewrite(PostMapper.class, "INSERT INTO sys_post (post_code) VALUES (?), (?) RETURNING post_id"))
            .isEqualTo("INSERT INTO sys_post (post_code, tenant_id) VALUES (?, 'tenant-A'), (?, 'tenant-A') RETURNING post_id");
    }

    @Test
    void supportsCustomTenantColumn() {
        TenantContext.setTenantId("org-A");
        assertThat(rewrite(CustomMapper.class, "INSERT INTO sys_post (post_code) VALUES (?)"))
            .contains("(post_code, org_id)", "(?, 'org-A')");
    }

    @Test
    void preservesSingleTenantCompatibility() {
        String sql = "INSERT INTO sys_post (post_code) VALUES (?)";
        assertThat(rewrite(PostMapper.class, sql)).isEqualTo(sql);
    }

    @Test
    void leavesUnannotatedMapperUntouched() {
        TenantContext.setTenantId("tenant-A");
        String sql = "INSERT INTO sys_post (post_code) VALUES (?)";
        assertThat(rewrite(PlainMapper.class, sql)).isEqualTo(sql);
    }

    @Test
    void rejectsMissingRequiredTenantBeforePreparingInsert() {
        assertThatThrownBy(() -> rewrite(StrictMapper.class, "INSERT INTO sys_post (post_code) VALUES (?)"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("租户");
    }

    @Test
    void rejectsExplicitTenantColumnRatherThanTrustingCaller() {
        TenantContext.setTenantId("tenant-A");
        assertThatThrownBy(() -> rewrite(PostMapper.class, "INSERT INTO sys_post (post_code, tenant_id) VALUES (?, ?)"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("租户列");
    }

    @Test
    void rejectsUnsupportedInsertFormsRatherThanSilentlySkippingTenant() {
        TenantContext.setTenantId("tenant-A");
        for (String sql : new String[] {
            "INSERT INTO sys_post (post_code) SELECT post_code FROM archived_post",
            "INSERT INTO sys_post DEFAULT VALUES",
            "INSERT INTO sys_post (post_code) VALUES (?) ON CONFLICT (post_code) DO UPDATE SET post_code = excluded.post_code"
        }) {
            assertThatThrownBy(() -> rewrite(PostMapper.class, sql))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private String rewrite(Class<?> mapper, String sql) {
        Configuration configuration = new Configuration();
        MappedStatement statement = new MappedStatement.Builder(configuration,
            mapper.getName() + ".insert", new StaticSqlSource(configuration, sql), SqlCommandType.INSERT).build();
        StatementHandler handler = configuration.newStatementHandler(null, statement, null, RowBounds.DEFAULT, null, null);
        new TenantSqlInterceptor().beforePrepare(handler, null, 30);
        return handler.getBoundSql().getSql();
    }
}
