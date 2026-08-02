package com.cloudinfra.framework.mybatis.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.Calendar;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 字段级加密 TypeHandler 单元测试
 */
class EncryptedTypeHandlerTest {

    private static final String TEST_KEY = Base64.getEncoder()
        .encodeToString("0123456789abcdef0123456789abcdef".getBytes()); // 32 字节密钥

    private EncryptedTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new EncryptedTypeHandler(TEST_KEY);
    }

    @Test
    void shouldEncryptAndDecrypt() throws Exception {
        String plaintext = "sensitive-data-12345";

        // 模拟 PreparedStatement 加密
        var ps = new MockPreparedStatement();
        handler.setNonNullParameter(ps, 1, plaintext, JdbcType.VARCHAR);

        // 加密后的值不应等于明文
        String encrypted = ps.stringValue;
        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(encrypted).isNotNull();

        // 解密应还原
        String decrypted = handler.getNullableResult(new MockResultSet(encrypted), 1);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void shouldEncryptEachTimeWithDifferentIV() throws Exception {
        String plaintext = "same-value";

        var ps1 = new MockPreparedStatement();
        handler.setNonNullParameter(ps1, 1, plaintext, JdbcType.VARCHAR);
        var ps2 = new MockPreparedStatement();
        handler.setNonNullParameter(ps2, 1, plaintext, JdbcType.VARCHAR);

        // 相同明文每次加密结果不同（随机 IV）
        assertThat(ps1.stringValue).isNotEqualTo(ps2.stringValue);

        // 但都能解密回明文
        assertThat(handler.getNullableResult(new MockResultSet(ps1.stringValue), 1))
            .isEqualTo(plaintext);
        assertThat(handler.getNullableResult(new MockResultSet(ps2.stringValue), 1))
            .isEqualTo(plaintext);
    }

    @Test
    void shouldHandleNullValues() throws Exception {
        var ps = new MockPreparedStatement();
        handler.setNonNullParameter(ps, 1, null, JdbcType.VARCHAR);
        assertThat(ps.stringValue).isNull();

        String decrypted = handler.getNullableResult(new MockResultSet(null), 1);
        assertThat(decrypted).isNull();
    }

    @Test
    void shouldRejectInvalidKey() {
        assertThatThrownBy(() -> new EncryptedTypeHandler("not-base64!"))
            .isInstanceOf(RuntimeException.class);
    }

    // ===== 最小 Mock 实现 =====

    private static class MockPreparedStatement implements PreparedStatement {
        String stringValue;
        private final java.sql.Connection connection = new MockConnection();

        @Override
        public void setString(int parameterIndex, String x) {
            this.stringValue = x;
        }

        @Override
        public void setNull(int parameterIndex, int sqlType) {
            this.stringValue = null;
        }

        // 以下方法仅为了满足接口编译，返回默认值
        @Override public ResultSet executeQuery() { return null; }
        @Override public int executeUpdate() { return 0; }
        @Override public void setBoolean(int i, boolean x) { }
        @Override public void setByte(int i, byte x) { }
        @Override public void setShort(int i, short x) { }
        @Override public void setInt(int i, int x) { }
        @Override public void setLong(int i, long x) { }
        @Override public void setFloat(int i, float x) { }
        @Override public void setDouble(int i, double x) { }
        @Override public void setBigDecimal(int i, BigDecimal x) { }
        @Override public void setBytes(int i, byte[] x) { }
        @Override public void setDate(int i, Date x) { }
        @Override public void setTime(int i, Time x) { }
        @Override public void setTimestamp(int i, Timestamp x) { }
        @Override public void setAsciiStream(int i, InputStream x, int len) { }
        @Override public void setUnicodeStream(int i, InputStream x, int len) { }
        @Override public void setBinaryStream(int i, InputStream x, int len) { }
        @Override public void clearParameters() { }
        @Override public void setObject(int i, Object x, int targetSqlType) { }
        @Override public void setObject(int i, Object x, int targetSqlType, int scaleOrLength) { }
        @Override public void setObject(int i, Object x) { }
        @Override public boolean execute() { return false; }
        @Override public void addBatch() { }
        @Override public void setCharacterStream(int i, Reader x, int len) { }
        @Override public void setRef(int i, Ref x) { }
        @Override public void setBlob(int i, Blob x) { }
        @Override public void setClob(int i, Clob x) { }
        @Override public void setArray(int i, Array x) { }
        @Override public ResultSetMetaData getMetaData() { return null; }
        @Override public void setDate(int i, Date x, Calendar cal) { }
        @Override public void setTime(int i, Time x, Calendar cal) { }
        @Override public void setTimestamp(int i, Timestamp x, Calendar cal) { }
        @Override public void setNull(int i, int sqlType, String typeName) { }
        @Override public void setURL(int i, URL x) { }
        @Override public ParameterMetaData getParameterMetaData() { return null; }
        @Override public void setRowId(int i, RowId x) { }
        @Override public void setNString(int i, String value) { }
        @Override public void setNCharacterStream(int i, Reader value, long len) { }
        @Override public void setNClob(int i, NClob value) { }
        @Override public void setClob(int i, Reader reader, long len) { }
        @Override public void setBlob(int i, InputStream inputStream, long len) { }
        @Override public void setNClob(int i, Reader reader, long len) { }
        @Override public void setSQLXML(int i, SQLXML xmlObject) { }
        @Override public void setAsciiStream(int i, InputStream x, long len) { }
        @Override public void setBinaryStream(int i, InputStream x, long len) { }
        @Override public void setCharacterStream(int i, Reader reader, long len) { }
        @Override public void setAsciiStream(int i, InputStream x) { }
        @Override public void setBinaryStream(int i, InputStream x) { }
        @Override public void setCharacterStream(int i, Reader reader) { }
        @Override public void setNCharacterStream(int i, Reader value) { }
        @Override public void setClob(int i, Reader reader) { }
        @Override public void setBlob(int i, InputStream inputStream) { }
        @Override public void setNClob(int i, Reader reader) { }
        @Override public void close() {
            try { this.connection.close(); } catch (java.sql.SQLException ignored) { }
        }
        @Override public int[] executeBatch() { return new int[0]; }
        @Override public java.sql.Connection getConnection() { try { return this.connection; } finally { /* close() 释放 */ } }
        @Override public void closeOnCompletion() { }
        @Override public boolean isCloseOnCompletion() { return false; }
        @Override public int getMaxFieldSize() { return 0; }
        @Override public void setMaxFieldSize(int max) { }
        @Override public int getMaxRows() { return 0; }
        @Override public void setMaxRows(int max) { }
        @Override public void setEscapeProcessing(boolean enable) { }
        @Override public int getQueryTimeout() { return 0; }
        @Override public void setQueryTimeout(int seconds) { }
        @Override public void cancel() { }
        @Override public SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { }
        @Override public void setCursorName(String name) { }
        @Override public boolean execute(String sql) { return false; }
        @Override public boolean execute(String sql, int autoGeneratedKeys) { return false; }
        @Override public boolean execute(String sql, int[] columnIndexes) { return false; }
        @Override public boolean execute(String sql, String[] columnNames) { return false; }
        @Override public ResultSet executeQuery(String sql) { return null; }
        @Override public int executeUpdate(String sql) { return 0; }
        @Override public int executeUpdate(String sql, int autoGeneratedKeys) { return 0; }
        @Override public int executeUpdate(String sql, int[] columnIndexes) { return 0; }
        @Override public int executeUpdate(String sql, String[] columnNames) { return 0; }
        @Override public int getResultSetHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public boolean isPoolable() { return false; }
        @Override public void setPoolable(boolean poolable) { }
        @Override public int getUpdateCount() { return 0; }
        @Override public boolean getMoreResults() { return false; }
        @Override public void setFetchDirection(int direction) { }
        @Override public int getFetchDirection() { return 0; }
        @Override public void setFetchSize(int rows) { }
        @Override public int getFetchSize() { return 0; }
        @Override public int getResultSetConcurrency() { return 0; }
        @Override public int getResultSetType() { return 0; }
        @Override public void addBatch(String sql) { }
        @Override public void clearBatch() { }
        @Override public ResultSet getResultSet() { return null; }
        @Override public boolean getMoreResults(int current) { return false; }
        @Override public ResultSet getGeneratedKeys() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static class MockConnection implements java.sql.Connection {
        private boolean closed = false;

        @Override public void close() { this.closed = true; }
        @Override public boolean isClosed() { return this.closed; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public java.sql.Statement createStatement() { return null; }
        @Override public PreparedStatement prepareStatement(String sql) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String sql) { return null; }
        @Override public String nativeSQL(String sql) { return null; }
        @Override public void setAutoCommit(boolean autoCommit) { }
        @Override public boolean getAutoCommit() { return false; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public java.sql.DatabaseMetaData getMetaData() { return null; }
        @Override public void setReadOnly(boolean readOnly) { }
        @Override public boolean isReadOnly() { return false; }
        @Override public void setCatalog(String catalog) { }
        @Override public String getCatalog() { return null; }
        @Override public void setTransactionIsolation(int level) { }
        @Override public int getTransactionIsolation() { return 0; }
        @Override public SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { }
        @Override public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) { return null; }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) { return null; }
        @Override public java.util.Map<String, Class<?>> getTypeMap() { return null; }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> map) { }
        @Override public void setHoldability(int holdability) { }
        @Override public int getHoldability() { return 0; }
        @Override public java.sql.Savepoint setSavepoint() { return null; }
        @Override public java.sql.Savepoint setSavepoint(String name) { return null; }
        @Override public void rollback(java.sql.Savepoint savepoint) { }
        @Override public void releaseSavepoint(java.sql.Savepoint savepoint) { }
        @Override public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) { return null; }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) { return null; }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) { return null; }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) { return null; }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) { return null; }
        @Override public Clob createClob() { return null; }
        @Override public Blob createBlob() { return null; }
        @Override public NClob createNClob() { return null; }
        @Override public SQLXML createSQLXML() { return null; }
        @Override public boolean isValid(int timeout) { return false; }
        @Override public void setClientInfo(String name, String value) { }
        @Override public void setClientInfo(java.util.Properties properties) { }
        @Override public String getClientInfo(String name) { return null; }
        @Override public java.util.Properties getClientInfo() { return null; }
        @Override public int getNetworkTimeout() { return 0; }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) { }
        @Override public java.sql.Struct createStruct(String typeName, Object[] attributes) { return null; }
        @Override public java.sql.Array createArrayOf(String typeName, Object[] elements) { return null; }
        @Override public void abort(java.util.concurrent.Executor executor) { }
        @Override public String getSchema() { return null; }
        @Override public void setSchema(String schema) { }
    }

    private static class MockResultSet implements ResultSet {
        private final String value;

        MockResultSet(String value) {
            this.value = value;
        }

        @Override
        public String getString(int columnIndex) {
            return value;
        }

        // 以下方法仅为了满足接口编译
        @Override public boolean next() { return false; }
        @Override public void close() { }
        @Override public boolean wasNull() { return false; }
        @Override public boolean getBoolean(int i) { return false; }
        @Override public byte getByte(int i) { return 0; }
        @Override public short getShort(int i) { return 0; }
        @Override public int getInt(int i) { return 0; }
        @Override public long getLong(int i) { return 0; }
        @Override public float getFloat(int i) { return 0; }
        @Override public double getDouble(int i) { return 0; }
        @Override public BigDecimal getBigDecimal(int i, int scale) { return null; }
        @Override public byte[] getBytes(int i) { return new byte[0]; }
        @Override public Date getDate(int i) { return null; }
        @Override public Time getTime(int i) { return null; }
        @Override public Timestamp getTimestamp(int i) { return null; }
        @Override public java.io.InputStream getAsciiStream(int i) { return null; }
        @Override public java.io.InputStream getUnicodeStream(int i) { return null; }
        @Override public java.io.InputStream getBinaryStream(int i) { return null; }
        @Override public String getString(String columnLabel) { return value; }
        @Override public boolean getBoolean(String columnLabel) { return false; }
        @Override public byte getByte(String columnLabel) { return 0; }
        @Override public short getShort(String columnLabel) { return 0; }
        @Override public int getInt(String columnLabel) { return 0; }
        @Override public long getLong(String columnLabel) { return 0; }
        @Override public float getFloat(String columnLabel) { return 0; }
        @Override public double getDouble(String columnLabel) { return 0; }
        @Override public BigDecimal getBigDecimal(String columnLabel, int scale) { return null; }
        @Override public byte[] getBytes(String columnLabel) { return new byte[0]; }
        @Override public Date getDate(String columnLabel) { return null; }
        @Override public Time getTime(String columnLabel) { return null; }
        @Override public Timestamp getTimestamp(String columnLabel) { return null; }
        @Override public java.io.InputStream getAsciiStream(String columnLabel) { return null; }
        @Override public java.io.InputStream getUnicodeStream(String columnLabel) { return null; }
        @Override public java.io.InputStream getBinaryStream(String columnLabel) { return null; }
        @Override public SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { }
        @Override public String getCursorName() { return null; }
        @Override public ResultSetMetaData getMetaData() { return null; }
        @Override public Object getObject(int i) { return null; }
        @Override public Object getObject(String columnLabel) { return null; }
        @Override public int findColumn(String columnLabel) { return 0; }
        @Override public java.io.Reader getCharacterStream(int i) { return null; }
        @Override public java.io.Reader getCharacterStream(String columnLabel) { return null; }
        @Override public BigDecimal getBigDecimal(int i) { return null; }
        @Override public BigDecimal getBigDecimal(String columnLabel) { return null; }
        @Override public boolean isBeforeFirst() { return false; }
        @Override public boolean isAfterLast() { return false; }
        @Override public boolean isFirst() { return false; }
        @Override public boolean isLast() { return false; }
        @Override public void beforeFirst() { }
        @Override public void afterLast() { }
        @Override public boolean first() { return false; }
        @Override public boolean last() { return false; }
        @Override public int getRow() { return 0; }
        @Override public boolean absolute(int row) { return false; }
        @Override public boolean relative(int rows) { return false; }
        @Override public boolean previous() { return false; }
        @Override public void setFetchDirection(int direction) { }
        @Override public int getFetchDirection() { return 0; }
        @Override public void setFetchSize(int rows) { }
        @Override public int getFetchSize() { return 0; }
        @Override public int getType() { return 0; }
        @Override public int getConcurrency() { return 0; }
        @Override public boolean rowUpdated() { return false; }
        @Override public boolean rowInserted() { return false; }
        @Override public boolean rowDeleted() { return false; }
        @Override public void updateNull(int i) { }
        @Override public void updateBoolean(int i, boolean x) { }
        @Override public void updateByte(int i, byte x) { }
        @Override public void updateShort(int i, short x) { }
        @Override public void updateInt(int i, int x) { }
        @Override public void updateLong(int i, long x) { }
        @Override public void updateFloat(int i, float x) { }
        @Override public void updateDouble(int i, double x) { }
        @Override public void updateBigDecimal(int i, BigDecimal x) { }
        @Override public void updateString(int i, String x) { }
        @Override public void updateBytes(int i, byte[] x) { }
        @Override public void updateDate(int i, Date x) { }
        @Override public void updateTime(int i, Time x) { }
        @Override public void updateTimestamp(int i, Timestamp x) { }
        @Override public void updateAsciiStream(int i, java.io.InputStream x, int len) { }
        @Override public void updateBinaryStream(int i, java.io.InputStream x, int len) { }
        @Override public void updateCharacterStream(int i, java.io.Reader x, int len) { }
        @Override public void updateObject(int i, Object x, int scaleOrLength) { }
        @Override public void updateObject(int i, Object x) { }
        @Override public void updateNull(String columnLabel) { }
        @Override public void updateBoolean(String columnLabel, boolean x) { }
        @Override public void updateByte(String columnLabel, byte x) { }
        @Override public void updateShort(String columnLabel, short x) { }
        @Override public void updateInt(String columnLabel, int x) { }
        @Override public void updateLong(String columnLabel, long x) { }
        @Override public void updateFloat(String columnLabel, float x) { }
        @Override public void updateDouble(String columnLabel, double x) { }
        @Override public void updateBigDecimal(String columnLabel, BigDecimal x) { }
        @Override public void updateString(String columnLabel, String x) { }
        @Override public void updateBytes(String columnLabel, byte[] x) { }
        @Override public void updateDate(String columnLabel, Date x) { }
        @Override public void updateTime(String columnLabel, Time x) { }
        @Override public void updateTimestamp(String columnLabel, Timestamp x) { }
        @Override public void updateAsciiStream(String columnLabel, java.io.InputStream x, int len) { }
        @Override public void updateBinaryStream(String columnLabel, java.io.InputStream x, int len) { }
        @Override public void updateCharacterStream(String columnLabel, java.io.Reader x, int len) { }
        @Override public void updateObject(String columnLabel, Object x, int scaleOrLength) { }
        @Override public void updateObject(String columnLabel, Object x) { }
        @Override public void insertRow() { }
        @Override public void updateRow() { }
        @Override public void deleteRow() { }
        @Override public void refreshRow() { }
        @Override public void cancelRowUpdates() { }
        @Override public void moveToInsertRow() { }
        @Override public void moveToCurrentRow() { }
        @Override public java.sql.Statement getStatement() { return null; }
        @Override public Object getObject(int i, java.util.Map<String, Class<?>> map) { return null; }
        @Override public Ref getRef(int i) { return null; }
        @Override public Blob getBlob(int i) { return null; }
        @Override public Clob getClob(int i) { return null; }
        @Override public Array getArray(int i) { return null; }
        @Override public Object getObject(String columnLabel, java.util.Map<String, Class<?>> map) { return null; }
        @Override public Ref getRef(String columnLabel) { return null; }
        @Override public Blob getBlob(String columnLabel) { return null; }
        @Override public Clob getClob(String columnLabel) { return null; }
        @Override public Array getArray(String columnLabel) { return null; }
        @Override public Date getDate(int i, Calendar cal) { return null; }
        @Override public Date getDate(String columnLabel, Calendar cal) { return null; }
        @Override public Time getTime(int i, Calendar cal) { return null; }
        @Override public Time getTime(String columnLabel, Calendar cal) { return null; }
        @Override public Timestamp getTimestamp(int i, Calendar cal) { return null; }
        @Override public Timestamp getTimestamp(String columnLabel, Calendar cal) { return null; }
        @Override public URL getURL(int i) { return null; }
        @Override public URL getURL(String columnLabel) { return null; }
        @Override public void updateRef(int i, Ref x) { }
        @Override public void updateRef(String columnLabel, Ref x) { }
        @Override public void updateBlob(int i, Blob x) { }
        @Override public void updateBlob(String columnLabel, Blob x) { }
        @Override public void updateClob(int i, Clob x) { }
        @Override public void updateClob(String columnLabel, Clob x) { }
        @Override public void updateArray(int i, Array x) { }
        @Override public void updateArray(String columnLabel, Array x) { }
        @Override public RowId getRowId(int i) { return null; }
        @Override public RowId getRowId(String columnLabel) { return null; }
        @Override public void updateRowId(int i, RowId x) { }
        @Override public void updateRowId(String columnLabel, RowId x) { }
        @Override public int getHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void updateNString(int i, String x) { }
        @Override public void updateNString(String columnLabel, String x) { }
        @Override public void updateNClob(int i, NClob x) { }
        @Override public void updateNClob(String columnLabel, NClob x) { }
        @Override public NClob getNClob(int i) { return null; }
        @Override public NClob getNClob(String columnLabel) { return null; }
        @Override public SQLXML getSQLXML(int i) { return null; }
        @Override public SQLXML getSQLXML(String columnLabel) { return null; }
        @Override public void updateSQLXML(int i, SQLXML x) { }
        @Override public void updateSQLXML(String columnLabel, SQLXML x) { }
        @Override public String getNString(int i) { return null; }
        @Override public String getNString(String columnLabel) { return null; }
        @Override public java.io.Reader getNCharacterStream(int i) { return null; }
        @Override public java.io.Reader getNCharacterStream(String columnLabel) { return null; }
        @Override public void updateNCharacterStream(int i, java.io.Reader x, long len) { }
        @Override public void updateNCharacterStream(String columnLabel, java.io.Reader x, long len) { }
        @Override public void updateAsciiStream(int i, java.io.InputStream x, long len) { }
        @Override public void updateBinaryStream(int i, java.io.InputStream x, long len) { }
        @Override public void updateCharacterStream(int i, java.io.Reader x, long len) { }
        @Override public void updateAsciiStream(String columnLabel, java.io.InputStream x, long len) { }
        @Override public void updateBinaryStream(String columnLabel, java.io.InputStream x, long len) { }
        @Override public void updateCharacterStream(String columnLabel, java.io.Reader x, long len) { }
        @Override public void updateBlob(int i, InputStream x, long len) { }
        @Override public void updateBlob(String columnLabel, InputStream x, long len) { }
        @Override public void updateClob(int i, Reader x, long len) { }
        @Override public void updateClob(String columnLabel, Reader x, long len) { }
        @Override public void updateNClob(int i, Reader x, long len) { }
        @Override public void updateNClob(String columnLabel, Reader x, long len) { }
        @Override public void updateNCharacterStream(int i, java.io.Reader x) { }
        @Override public void updateNCharacterStream(String columnLabel, java.io.Reader x) { }
        @Override public void updateAsciiStream(int i, java.io.InputStream x) { }
        @Override public void updateBinaryStream(int i, java.io.InputStream x) { }
        @Override public void updateCharacterStream(int i, java.io.Reader x) { }
        @Override public void updateAsciiStream(String columnLabel, java.io.InputStream x) { }
        @Override public void updateBinaryStream(String columnLabel, java.io.InputStream x) { }
        @Override public void updateCharacterStream(String columnLabel, java.io.Reader x) { }
        @Override public void updateBlob(int i, InputStream x) { }
        @Override public void updateBlob(String columnLabel, InputStream x) { }
        @Override public void updateClob(int i, Reader x) { }
        @Override public void updateClob(String columnLabel, Reader x) { }
        @Override public void updateNClob(int i, Reader x) { }
        @Override public void updateNClob(String columnLabel, Reader x) { }
        @Override public <T> T getObject(int i, Class<T> type) { return null; }
        @Override public <T> T getObject(String columnLabel, Class<T> type) { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}