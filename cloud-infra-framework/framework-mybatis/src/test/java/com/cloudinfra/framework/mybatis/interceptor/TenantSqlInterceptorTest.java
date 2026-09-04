package com.cloudinfra.framework.mybatis.interceptor;

import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.common.core.tenant.TenantContext;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TenantSqlInterceptor 单元测试 (M6-01)
 *
 * 覆盖核心场景:
 * - SELECT 无 WHERE → 加 WHERE tenant_id = ?
 * - SELECT 有 WHERE → 加 AND tenant_id = ?
 * - SELECT 含 GROUP BY / ORDER BY / LIMIT → 在尾部关键句前插入
 * - UPDATE / DELETE 同 SELECT 逻辑
 * - 无 @TenantFilter 注解 → 放行
 * - 缺租户上下文 + rejectWhenMissing=false → 放行
 * - 缺租户上下文 + rejectWhenMissing=true → SQL 改写为 1=0
 * - 非法 tenantId (含 SQL 注入字符) → 抛 IllegalArgumentException
 */
class TenantSqlInterceptorTest {

    private TenantSqlInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TenantSqlInterceptor();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ========== 场景 1: SELECT 无 WHERE ==========

    @Test
    @DisplayName("SELECT 无 WHERE: 追加 WHERE tenant_id = '<tenant>'")
    void shouldAddWhereForSelectWithoutWhere() throws Exception {
        TenantContext.setTenantId("tenant-A");
        StatementHandler sh = mockHandler(SampleMapper.class, "selectAll", "SELECT id, name FROM sys_post");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).isEqualTo("SELECT id, name FROM sys_post WHERE tenant_id = 'tenant-A'");
    }

    // ========== 场景 2: SELECT 有 WHERE ==========

    @Test
    @DisplayName("SELECT 有 WHERE: 追加 AND tenant_id = '<tenant>'")
    void shouldAddAndForSelectWithWhere() throws Exception {
        TenantContext.setTenantId("tenant-B");
        StatementHandler sh = mockHandler(SampleMapper.class, "selectByStatus",
            "SELECT id, name FROM sys_post WHERE status = '0'");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).isEqualTo("SELECT id, name FROM sys_post WHERE status = '0' AND tenant_id = 'tenant-B'");
    }

    // ========== 场景 3: SELECT 含 ORDER BY ==========

    @Test
    @DisplayName("SELECT 含 ORDER BY: 在 ORDER BY 前插入 AND tenant_id")
    void shouldInsertBeforeOrderBy() throws Exception {
        TenantContext.setTenantId("tenant-C");
        StatementHandler sh = mockHandler(SampleMapper.class, "selectOrdered",
            "SELECT id FROM sys_post WHERE status = '0' ORDER BY id DESC");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).isEqualTo(
            "SELECT id FROM sys_post WHERE status = '0' AND tenant_id = 'tenant-C' ORDER BY id DESC");
    }

    // ========== 场景 4: SELECT 含 LIMIT ==========

    @Test
    @DisplayName("SELECT 含 LIMIT: 在 LIMIT 前插入")
    void shouldInsertBeforeLimit() throws Exception {
        TenantContext.setTenantId("tenant-D");
        StatementHandler sh = mockHandler(SampleMapper.class, "selectPaged",
            "SELECT id FROM sys_post LIMIT 10");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).isEqualTo("SELECT id FROM sys_post WHERE tenant_id = 'tenant-D' LIMIT 10");
    }

    // ========== 场景 5: UPDATE 同 SELECT ==========

    @Test
    @DisplayName("UPDATE 有 WHERE: 追加 AND tenant_id")
    void shouldAddAndForUpdate() throws Exception {
        TenantContext.setTenantId("tenant-E");
        StatementHandler sh = mockHandler(SampleMapper.class, "updateStatus",
            "UPDATE sys_post SET status = '1' WHERE id = 100");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).isEqualTo("UPDATE sys_post SET status = '1' WHERE id = 100 AND tenant_id = 'tenant-E'");
    }

    // ========== 场景 6: DELETE 同 SELECT ==========

    @Test
    @DisplayName("DELETE 有 WHERE: 追加 AND tenant_id")
    void shouldAddAndForDelete() throws Exception {
        TenantContext.setTenantId("tenant-F");
        StatementHandler sh = mockHandler(SampleMapper.class, "deleteById",
            "DELETE FROM sys_post WHERE id = 100");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).isEqualTo("DELETE FROM sys_post WHERE id = 100 AND tenant_id = 'tenant-F'");
    }

    // ========== 场景 7: 无注解放行 ==========

    @Test
    @DisplayName("无 @TenantFilter 注解: SQL 不变")
    void shouldNotModifySqlWithoutAnnotation() throws Exception {
        TenantContext.setTenantId("tenant-G");
        StatementHandler sh = mockHandler(PlainMapper.class, "selectAll", "SELECT id FROM sys_post");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).isEqualTo("SELECT id FROM sys_post");
    }

    // ========== 场景 8: 缺租户 + rejectWhenMissing=false 放行 ==========

    @Test
    @DisplayName("缺租户上下文 + rejectWhenMissing=false: SQL 不变 (单租户模式兼容)")
    void shouldNotModifySqlWhenTenantMissingAndNotRequired() throws Exception {
        // TenantContext.clear() 已执行
        StatementHandler sh = mockHandler(SampleMapper.class, "selectAll", "SELECT id FROM sys_post");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).isEqualTo("SELECT id FROM sys_post");
    }

    // ========== 场景 9: 缺租户 + rejectWhenMissing=true 拒绝 ==========

    @Test
    @DisplayName("缺租户上下文 + rejectWhenMissing=true: SQL 改写为 1=0")
    void shouldRejectWhenTenantMissingAndRequired() throws Exception {
        // TenantContext.clear() 已执行
        StatementHandler sh = mockHandler(StrictMapper.class, "selectAll", "SELECT id FROM sys_post");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).contains("WHERE 1=0");
    }

    // ========== 场景 10: 非法 tenantId ==========

    @Test
    @DisplayName("非法 tenantId (含单引号): 抛 IllegalArgumentException")
    void shouldRejectIllegalTenantId() {
        TenantContext.setTenantId("tenant'; DROP TABLE--");
        StatementHandler sh = mockHandler(SampleMapper.class, "selectAll", "SELECT id FROM sys_post");
        assertThatThrownBy(() -> invokeInterceptor(sh))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("非法 tenantId");
    }

    // ========== 场景 11: 类级注解 ==========

    @Test
    @DisplayName("Mapper 接口类级 @TenantFilter: 所有方法生效")
    void shouldApplyClassLevelAnnotation() throws Exception {
        TenantContext.setTenantId("tenant-H");
        StatementHandler sh = mockHandler(ClassLevelMapper.class, "anyMethod", "SELECT id FROM x");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).isEqualTo("SELECT id FROM x WHERE tenant_id = 'tenant-H'");
    }

    // ========== 场景 12: 自定义列名 ==========

    @Test
    @DisplayName("@TenantFilter(column=\"org_id\"): 使用自定义列名")
    void shouldUseCustomColumn() throws Exception {
        TenantContext.setTenantId("tenant-I");
        StatementHandler sh = mockHandler(CustomColumnMapper.class, "selectAll", "SELECT id FROM x");
        invokeInterceptor(sh);

        String sql = currentSql(sh);
        assertThat(sql).isEqualTo("SELECT id FROM x WHERE org_id = 'tenant-I'");
    }

    // ========== 辅助方法 ==========

    private void invokeInterceptor(StatementHandler sh) throws Exception {
        interceptor.beforePrepare(sh, Mockito.mock(Connection.class), 30);
    }

    private String currentSql(StatementHandler sh) {
        MetaObject mo = SystemMetaObject.forObject(sh);
        BoundSql bs = (BoundSql) mo.getValue("delegate.boundSql");
        return bs.getSql();
    }

    /**
     * 测试用 POJO: StatementHandler 内部真实结构 (delegate.mappedStatement / delegate.boundSql)
     * 使用真实类而非 Mockito mock 是因为 SystemMetaObject 通过反射 setter 设值,
     * Mockito 生成的 mock 类没有 delegate 字段.
     *
     * 注意: mybatis 3.5.19 StatementHandler 接口仅有 8 个方法 (无 setExecutorWrapper 等),
     * 完整方法列表见 javap.
     */
    static class FakeStatementHandler implements StatementHandler {
        final Delegate delegate = new Delegate();

        @Override
        public java.sql.Statement prepare(Connection connection, Integer transactionTimeout) {
            return null;
        }

        @Override
        public void parameterize(Statement statement) {}

        @Override
        public void batch(Statement statement) {}

        @Override
        public int update(Statement statement) {
            return 0;
        }

        @Override
        public <E> java.util.List<E> query(Statement statement, org.apache.ibatis.session.ResultHandler resultHandler) {
            return java.util.List.of();
        }

        @Override
        public <E> org.apache.ibatis.cursor.Cursor<E> queryCursor(Statement statement) {
            return null;
        }

        @Override
        public BoundSql getBoundSql() {
            return delegate.boundSql;
        }

        @Override
        public org.apache.ibatis.executor.parameter.ParameterHandler getParameterHandler() {
            return null;
        }
    }

    static class Delegate {
        MappedStatement mappedStatement;
        BoundSql boundSql;
    }

    private StatementHandler mockHandler(Class<?> mapperClass, String methodName, String sql) {
        FakeStatementHandler sh = new FakeStatementHandler();
        // BoundSql 是具体类非 final, 继承实现可变 sql 字段
        // (反射改 final 字段在 JDK 17+ 不可行)
        sh.delegate.boundSql = new FakeBoundSql(sql);
        sh.delegate.mappedStatement = Mockito.mock(MappedStatement.class);
        Mockito.when(sh.delegate.mappedStatement.getId()).thenReturn(mapperClass.getName() + "." + methodName);
        return sh;
    }

    /**
     * 可变 BoundSql 子类: 继承 BoundSql 后覆盖 getSql() 返回可变字段.
     * TenantSqlInterceptor 通过 MetaObject.setValue("delegate.boundSql.sql", newSql)
     * 设值时会调用 setSql (若有) 或直接设 sql 字段. 这里覆盖 setSql 方法.
     */
    static class FakeBoundSql extends BoundSql {
        private String sql;

        FakeBoundSql(String sql) {
            // BoundSql(Configuration, String, List<ParameterMapping>, Object) - Configuration 不需要, 传 null
            super(new org.apache.ibatis.session.Configuration() {
                @Override public org.apache.ibatis.mapping.ParameterMap getParameterMap(String id) { return null; }
                @Override public boolean isUseGeneratedKeys() { return false; }
            }, sql, new java.util.ArrayList<>(), null);
            this.sql = sql;
        }

        @Override
        public String getSql() {
            return sql;
        }

        /**
         * MetaObject.setValue("sql", ...) 通过反射查找 setter, 这里提供 setSql 让 interceptor 可写.
         */
        @SuppressWarnings("unused")
        public void setSql(String sql) {
            this.sql = sql;
        }
    }

    // ========== 测试 Mapper 接口 ==========

    interface SampleMapper {
        @TenantFilter
        void selectAll();

        @TenantFilter
        void selectByStatus();

        @TenantFilter
        void selectOrdered();

        @TenantFilter
        void selectPaged();

        @TenantFilter
        void updateStatus();

        @TenantFilter
        void deleteById();
    }

    interface PlainMapper {
        // 无 @TenantFilter
        void selectAll();
    }

    @TenantFilter(rejectWhenMissing = true)
    interface StrictMapper {
        void selectAll();
    }

    @TenantFilter
    interface ClassLevelMapper {
        void anyMethod();
    }

    interface CustomColumnMapper {
        @TenantFilter(column = "org_id")
        void selectAll();
    }
}