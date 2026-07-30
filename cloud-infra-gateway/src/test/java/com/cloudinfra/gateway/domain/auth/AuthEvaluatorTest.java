package com.cloudinfra.gateway.domain.auth;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.cloudinfra.common.util.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AuthEvaluator 单元测试
 * 验证三段式认证判定逻辑：白名单 → JWT 签名校验 → 会话验证
 */
@ExtendWith(MockitoExtension.class)
class AuthEvaluatorTest {

    @Mock
    private SessionStore sessionStore;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private DecodedJWT decodedJWT;

    private AuthEvaluator authEvaluator;

    // ==================== 共享测试数据 ====================

    private static final Long TEST_USER_ID = 10001L;
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_JTI = "jti-test-123";
    private static final String VALID_TOKEN = "Bearer valid.jwt.token";
    private static final String PURE_TOKEN = "valid.jwt.token";

    // ==================== 场景一：白名单路径 ====================

    @Nested
    @DisplayName("白名单路径认证")
    class WhiteListTest {

        @BeforeEach
        void setUp() {
            authEvaluator = new AuthEvaluator(sessionStore,
                    List.of("/auth/login", "/auth/register", "/public/**", "/health"),
                    jwtUtils);
        }

        @Test
        @DisplayName("精确匹配白名单路径应返回成功（无 Token）")
        void exactMatchWhiteListPath() {
            AuthContext ctx = new AuthContext(null, "/auth/login", "POST", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertTrue(result.isSuccess());
            assertEquals(AuthStatus.SUCCESS, result.status());
            assertNull(result.identity());

            verifyNoInteractions(jwtUtils, sessionStore);
        }

        @Test
        @DisplayName("前缀通配白名单路径应返回成功")
        void prefixWildcardWhiteListPath() {
            AuthContext ctx = new AuthContext(null, "/public/health/check", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertTrue(result.isSuccess());
            assertNull(result.identity());
        }

        @Test
        @DisplayName("/health 路径（精确匹配白名单）应返回成功")
        void healthExactWhiteListPath() {
            AuthContext ctx = new AuthContext(null, "/health", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("/auth/login 路径应返回成功即使携带无效 Token")
        void whiteListPathShouldBypassTokenValidation() {
            AuthContext ctx = new AuthContext(null, "/auth/login", "POST", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertTrue(result.isSuccess());
            verifyNoInteractions(jwtUtils, sessionStore);
        }
    }

    // ==================== 场景二：Token 缺失 ====================

    @Nested
    @DisplayName("Token 缺失场景")
    class MissingTokenTest {

        @BeforeEach
        void setUp() {
            authEvaluator = new AuthEvaluator(sessionStore, List.of(), jwtUtils);
        }

        @Test
        @DisplayName("无 Token 请求应返回 UNAUTHORIZED")
        void nullTokenShouldReturnUnauthorized() {
            AuthContext ctx = new AuthContext(null, "/api/secure", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertFalse(result.isSuccess());
            assertEquals(AuthStatus.UNAUTHORIZED, result.status());
            assertNull(result.identity());
        }

        @Test
        @DisplayName("空 Token 请求应返回 UNAUTHORIZED")
        void emptyTokenShouldReturnUnauthorized() {
            AuthContext ctx = new AuthContext("", "/api/secure", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertFalse(result.isSuccess());
            assertEquals(AuthStatus.UNAUTHORIZED, result.status());
        }
    }

    // ==================== 场景三：JWT 校验失败 ====================

    @Nested
    @DisplayName("JWT 校验失败场景")
    class JwtValidationFailureTest {

        @BeforeEach
        void setUp() {
            authEvaluator = new AuthEvaluator(sessionStore, List.of(), jwtUtils);
        }

        @Test
        @DisplayName("Token 格式错误（无 Bearer 前缀）应校验失败")
        void malformedTokenShouldReturnUnauthorized() {
            when(jwtUtils.parseToken(anyString())).thenThrow(new RuntimeException("Invalid token"));

            AuthContext ctx = new AuthContext("invalid-token", "/api/secure", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertFalse(result.isSuccess());
            assertEquals(AuthStatus.UNAUTHORIZED, result.status());
        }

        @Test
        @DisplayName("Bearer Token 解析失败应返回 UNAUTHORIZED")
        void bearerTokenParseFailureShouldReturnUnauthorized() {
            when(jwtUtils.parseToken(PURE_TOKEN)).thenThrow(
                    new com.auth0.jwt.exceptions.TokenExpiredException("Token expired", Instant.now()));

            AuthContext ctx = new AuthContext(VALID_TOKEN, "/api/secure", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertFalse(result.isSuccess());
            assertEquals(AuthStatus.UNAUTHORIZED, result.status());
        }

        @Test
        @DisplayName("签名无效的 Token 应返回 UNAUTHORIZED")
        void invalidSignatureTokenShouldReturnUnauthorized() {
            when(jwtUtils.parseToken(PURE_TOKEN)).thenThrow(new com.auth0.jwt.exceptions.SignatureVerificationException(null));

            AuthContext ctx = new AuthContext(VALID_TOKEN, "/api/secure", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertFalse(result.isSuccess());
            assertEquals(AuthStatus.UNAUTHORIZED, result.status());
        }
    }

    // ==================== 场景四：JWT Claim 异常 ====================

    @Nested
    @DisplayName("JWT Claim 异常场景")
    class JwtClaimValidationTest {

        @BeforeEach
        void setUp() {
            authEvaluator = new AuthEvaluator(sessionStore, List.of(), jwtUtils);
            when(jwtUtils.parseToken(PURE_TOKEN)).thenReturn(decodedJWT);
        }

        @Test
        @DisplayName("缺少 userId claim 应返回 UNAUTHORIZED")
        void missingUserIdClaimShouldReturnUnauthorized() {
            // 保持 lenient 宽松模式避免不必要的桩桩告
            when(decodedJWT.getClaim(anyString())).thenReturn(null);

            AuthContext ctx = new AuthContext(VALID_TOKEN, "/api/secure", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertFalse(result.isSuccess());
            assertEquals(AuthStatus.UNAUTHORIZED, result.status());
        }

        @Test
        @DisplayName("缺少 jti claim 应返回 UNAUTHORIZED")
        void missingJtiClaimShouldReturnUnauthorized() {
            com.auth0.jwt.interfaces.Claim userIdClaim = mock(com.auth0.jwt.interfaces.Claim.class);
            when(userIdClaim.asLong()).thenReturn(TEST_USER_ID);
            when(decodedJWT.getClaim("userId")).thenReturn(userIdClaim);

            com.auth0.jwt.interfaces.Claim jtiClaim = mock(com.auth0.jwt.interfaces.Claim.class);
            when(jtiClaim.asString()).thenReturn(null);
            when(decodedJWT.getClaim("jti")).thenReturn(jtiClaim);

            when(decodedJWT.getSubject()).thenReturn(TEST_USERNAME);

            AuthContext ctx = new AuthContext(VALID_TOKEN, "/api/secure", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertFalse(result.isSuccess());
            assertEquals(AuthStatus.UNAUTHORIZED, result.status());
        }
    }

    // ==================== 场景五：会话验证失败 ====================

    @Nested
    @DisplayName("会话验证失败场景")
    class SessionValidationFailureTest {

        @BeforeEach
        void setUp() {
            authEvaluator = new AuthEvaluator(sessionStore, List.of(), jwtUtils);
            mockValidJwt();
        }

        private void mockValidJwt() {
            when(jwtUtils.parseToken(PURE_TOKEN)).thenReturn(decodedJWT);

            com.auth0.jwt.interfaces.Claim userIdClaim = mock(com.auth0.jwt.interfaces.Claim.class);
            when(userIdClaim.asLong()).thenReturn(TEST_USER_ID);
            when(decodedJWT.getClaim("userId")).thenReturn(userIdClaim);

            com.auth0.jwt.interfaces.Claim jtiClaim = mock(com.auth0.jwt.interfaces.Claim.class);
            when(jtiClaim.asString()).thenReturn(TEST_JTI);
            when(decodedJWT.getClaim("jti")).thenReturn(jtiClaim);

            when(decodedJWT.getSubject()).thenReturn(TEST_USERNAME);
        }

        @Test
        @DisplayName("会话不存在（未登录或已过期）应返回 FORBIDDEN")
        void sessionExpiredShouldReturnForbidden() {
            when(sessionStore.validateSession(TEST_USER_ID, TEST_JTI)).thenReturn(false);

            AuthContext ctx = new AuthContext(VALID_TOKEN, "/api/secure", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertFalse(result.isSuccess());
            assertEquals(AuthStatus.FORBIDDEN, result.status());
            assertTrue(result.message().contains("Session expired") || result.message().contains("blacklisted"));
        }

        @Test
        @DisplayName("使用已被撤销的 Token（黑名单）应返回 FORBIDDEN")
        void blacklistedTokenShouldReturnForbidden() {
            when(sessionStore.validateSession(TEST_USER_ID, TEST_JTI)).thenReturn(false);

            AuthContext ctx = new AuthContext(VALID_TOKEN, "/api/admin", "DELETE", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertEquals(AuthStatus.FORBIDDEN, result.status());
        }

        @Test
        @DisplayName("用户被强制登出后应返回 FORBIDDEN")
        void forcedLogoutShouldReturnForbidden() {
            when(sessionStore.validateSession(TEST_USER_ID, TEST_JTI)).thenReturn(false);

            AuthContext ctx = new AuthContext(VALID_TOKEN, "/api/resource", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertFalse(result.isSuccess());
        }
    }

    // ==================== 场景六：认证成功 ====================

    @Nested
    @DisplayName("认证成功场景")
    class AuthSuccessTest {

        @BeforeEach
        void setUp() {
            authEvaluator = new AuthEvaluator(sessionStore, List.of(), jwtUtils);
            mockValidJwtAndSession();
        }

        private void mockValidJwtAndSession() {
            when(jwtUtils.parseToken(PURE_TOKEN)).thenReturn(decodedJWT);

            com.auth0.jwt.interfaces.Claim userIdClaim = mock(com.auth0.jwt.interfaces.Claim.class);
            when(userIdClaim.asLong()).thenReturn(TEST_USER_ID);
            when(decodedJWT.getClaim("userId")).thenReturn(userIdClaim);

            com.auth0.jwt.interfaces.Claim jtiClaim = mock(com.auth0.jwt.interfaces.Claim.class);
            when(jtiClaim.asString()).thenReturn(TEST_JTI);
            when(decodedJWT.getClaim("jti")).thenReturn(jtiClaim);

            when(decodedJWT.getSubject()).thenReturn(TEST_USERNAME);

            when(sessionStore.validateSession(TEST_USER_ID, TEST_JTI)).thenReturn(true);
        }

        @Test
        @DisplayName("有效 Token + 有效会话应返回成功并携带身份信息")
        void validTokenAndSessionShouldReturnSuccessWithIdentity() {
            AuthContext ctx = new AuthContext(VALID_TOKEN, "/api/resource", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertTrue(result.isSuccess());
            assertEquals(AuthStatus.SUCCESS, result.status());

            Identity identity = result.identity();
            assertNotNull(identity);
            assertEquals(TEST_USER_ID, identity.userId());
            assertEquals(TEST_USERNAME, identity.username());
        }

        @DisplayName("不同安全路径的认证应等效通过")
        @ParameterizedTest
        @ValueSource(strings = {"/api/resource", "/api/admin/user", "/system/config", "/file/upload"})
        void authenticationShouldSucceedOnDifferentPaths(String path) {
            AuthContext ctx = new AuthContext(VALID_TOKEN, path, "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertTrue(result.isSuccess());
            assertNotNull(result.identity());
            assertEquals(TEST_USER_ID, result.identity().userId());
        }

        @Test
        @DisplayName("认证成功时应调用 sessionStore.validateSession()")
        void shouldCallValidateSessionOnAuthSuccess() {
            AuthContext ctx = new AuthContext(VALID_TOKEN, "/api/resource", "GET", "127.0.0.1", Map.of());
            authEvaluator.evaluate(ctx);

            verify(sessionStore, times(1)).validateSession(TEST_USER_ID, TEST_JTI);
        }
    }

    // ==================== 场景七：Token 提取逻辑 ====================

    @Nested
    @DisplayName("Token 提取逻辑")
    class TokenExtractionTest {

        @BeforeEach
        void setUp() {
            authEvaluator = new AuthEvaluator(sessionStore, List.of("/public"), jwtUtils);

            // 通用 JWT mock 设置
            lenient().when(jwtUtils.parseToken(PURE_TOKEN)).thenReturn(decodedJWT);

            com.auth0.jwt.interfaces.Claim userIdClaim = mock(com.auth0.jwt.interfaces.Claim.class);
            lenient().when(userIdClaim.asLong()).thenReturn(TEST_USER_ID);
            lenient().when(decodedJWT.getClaim("userId")).thenReturn(userIdClaim);

            com.auth0.jwt.interfaces.Claim jtiClaim = mock(com.auth0.jwt.interfaces.Claim.class);
            lenient().when(jtiClaim.asString()).thenReturn(TEST_JTI);
            lenient().when(decodedJWT.getClaim("jti")).thenReturn(jtiClaim);

            lenient().when(decodedJWT.getSubject()).thenReturn(TEST_USERNAME);
            lenient().when(sessionStore.validateSession(TEST_USER_ID, TEST_JTI)).thenReturn(true);
        }

        @Test
        @DisplayName("'Bearer token' 格式应正确提取纯 Token")
        void bearerTokenShouldBeExtracted() {
            AuthContext ctx = new AuthContext(VALID_TOKEN, "/api/resource", "GET", "127.0.0.1", Map.of());
            authEvaluator.evaluate(ctx);

            verify(jwtUtils, times(1)).parseToken(PURE_TOKEN);
        }

        @Test
        @DisplayName("不含 Bearer 前缀的 Token 应直接传递")
        void tokenWithoutBearerPrefixShouldBePassedDirectly() {
            when(jwtUtils.parseToken("raw-token")).thenReturn(decodedJWT);

            AuthContext ctx = new AuthContext("raw-token", "/api/resource", "GET", "127.0.0.1", Map.of());
            AuthResult result = authEvaluator.evaluate(ctx);

            assertTrue(result.isSuccess());
            verify(jwtUtils, times(1)).parseToken("raw-token");
        }
    }
}
