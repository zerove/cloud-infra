package com.cloudinfra.framework.mybatis.crypto;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.springframework.beans.factory.annotation.Value;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;

/**
 * 字段级加密 TypeHandler
 * 支持 AES-GCM 加密，自动处理 IV 和认证标签
 *
 * 使用方式：
 *   @TableField(typeHandler = EncryptedTypeHandler.class)
 *   @Encrypt
 *   private String sensitiveData;
 *
 * 密钥通过 ENCRYPTION_KEY 环境变量或 cloud-infra.crypto.key 配置提供
 */
public class EncryptedTypeHandler extends BaseTypeHandler<String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;      // GCM 推荐 12 字节 IV
    private static final int TAG_LENGTH = 16;     // GCM 认证标签 16 字节
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec secretKey;

    public EncryptedTypeHandler(
        @Value("${cloud-infra.crypto.key:${ENCRYPTION_KEY:}}") String keyBase64) {
        if (keyBase64 == null || keyBase64.isEmpty()) {
            throw new IllegalStateException(
                "加密密钥未配置: 请设置 cloud-infra.crypto.key 或 ENCRYPTION_KEY 环境变量 (Base64 编码的 32 字节密钥)");
        }
        byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
        if (keyBytes.length != 32) { // AES-256 需要 32 字节密钥
            throw new IllegalStateException("加密密钥必须为 256 位 (32 字节)，当前为 " + keyBytes.length + " 字节");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
        throws SQLException {
        if (parameter == null) {
            ps.setNull(i, JdbcType.VARCHAR.TYPE_CODE);
            return;
        }

        try {
            // 生成随机 IV
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH * 8, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            // 加密数据
            byte[] plaintext = parameter.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintext);

            // 组合: IV + ciphertext + tag，Base64 编码
            ByteBuffer buffer = ByteBuffer.allocate(IV_LENGTH + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            ps.setString(i, Base64.getEncoder().encodeToString(buffer.array()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SQLException("字段加密失败", e);
        }
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decrypt(cs.getString(columnIndex));
    }

    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(encrypted);

            // 提取 IV (前 12 字节)
            if (decoded.length < IV_LENGTH + TAG_LENGTH) {
                throw new IllegalArgumentException("无效的加密数据长度");
            }

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH);

            // 剩余部分: ciphertext + tag
            int ciphertextLength = decoded.length - IV_LENGTH;
            byte[] ciphertextWithTag = new byte[ciphertextLength];
            System.arraycopy(decoded, IV_LENGTH, ciphertextWithTag, 0, ciphertextLength);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH * 8, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            byte[] plaintext = cipher.doFinal(ciphertextWithTag);
            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new RuntimeException("字段解密失败", e);
        }
    }
}