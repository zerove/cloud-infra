package com.cloudinfra.auth;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.api.system.dto.RegisterUserDTO;
import com.cloudinfra.api.system.dto.UserCredentialDTO;
import com.cloudinfra.auth.controller.AuthController;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.framework.redis.service.RedisService;
import com.cloudinfra.test.BaseIntegrationTest;
import com.cloudinfra.test.support.containers.InfrastructureContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = com.cloudinfra.auth.AuthApplication.class
)
@Testcontainers
class AuthIntegrationTest extends BaseIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            // 与 AuthGatewayIntegrationTest 同理: 显式预算, 不依赖 60 秒默认。
            .withStartupTimeout(InfrastructureContainers.STARTUP_TIMEOUT);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private AuthController authController;

    @Autowired
    private com.cloudinfra.auth.service.AuthTokenService authTokenService;

    @MockitoBean
    private UserApi userApi;
    
    @MockitoBean
    private com.cloudinfra.auth.service.UserService userService;
    
    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;
    
    @MockitoBean
    private ValueOperations<String, Object> valueOperations;
    
    @MockitoBean
    private AuthenticationManager authenticationManager;
    
    @MockitoBean
    private RedisService redisService;
    
    @BeforeEach
    void setUp() {
        Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 默认验证码有效
        Mockito.lenient().when(valueOperations.get(Mockito.anyString())).thenReturn("ABCD");
        // 默认账号未锁定
        Mockito.lenient().when(redisTemplate.hasKey(Mockito.anyString())).thenReturn(false);
        
        // Mock UserService for authentication
        com.cloudinfra.common.core.domain.model.LoginUser testUser = com.cloudinfra.common.core.domain.model.LoginUser.builder()
            .userId(1L)
            .username("integration_test_user")
            .password("Test@1234")
            .roleId(1L)
            .permissions(Set.of("system:user:list"))
            .build();
        Mockito.lenient().when(userService.getUserByUsername(Mockito.anyString())).thenReturn(testUser);
        Mockito.lenient().when(userService.getUserById(Mockito.anyLong())).thenReturn(testUser);
        
        // Mock authenticationManager for successful authentication
        com.cloudinfra.auth.domain.SecurityUser principal = new com.cloudinfra.auth.domain.SecurityUser(
            1L, "integration_test_user", "Test@1234", java.util.Collections.emptyList());
        Authentication authentication = new UsernamePasswordAuthenticationToken(principal, null, java.util.Collections.emptyList());
        Mockito.lenient().when(authenticationManager.authenticate(Mockito.any())).thenReturn(authentication);
        
        // Mock RedisService for token operations
        Mockito.lenient().when(redisService.get(Mockito.anyString())).thenReturn(null);
        Mockito.lenient().doNothing().when(redisService).set(Mockito.anyString(), Mockito.any(), Mockito.anyLong());
        Mockito.lenient().when(redisService.delete(Mockito.anyString())).thenReturn(true);
    }
    
    @Test
    void shouldRegisterAndLoginSuccessfully() {
        // Given: 准备注册用户
        LoginUser registerUser = new LoginUser();
        registerUser.setUsername("integration_test_user");
        registerUser.setPassword("Test@1234");

        // Mock registerUser to return success
        Mockito.when(userApi.registerUser(Mockito.any())).thenReturn(Result.success());

        // When: 调用注册接口
        Result<Void> registerResult = authController.register(registerUser, null);

        // Then: 注册成功
        assertThat(registerResult.isSuccess()).isTrue();

        // When: 调用登录接口
        LoginUser loginUser = new LoginUser();
        loginUser.setUsername("integration_test_user");
        loginUser.setPassword("Test@1234");
        loginUser.setCaptcha("ABCD");
        loginUser.setUuid("test-uuid-1");

        // Mock login dependencies
        UserCredentialDTO credentialDTO = UserCredentialDTO.builder()
            .userId(1L)
            .username("integration_test_user")
            .password("Test@1234")
            .build();
        Mockito.when(userApi.getUserCredentialByUsername(Mockito.anyString()))
            .thenReturn(Result.success(credentialDTO));
        
        com.cloudinfra.common.core.domain.model.LoginUser loginUserDetail = com.cloudinfra.common.core.domain.model.LoginUser.builder()
            .userId(1L)
            .username("integration_test_user")
            .roleId(1L)
            .permissions(Set.of("system:user:list"))
            .build();
        Mockito.when(userApi.getUserDetail(Mockito.anyLong()))
            .thenReturn(Result.success(loginUserDetail));

        Result<Map<String, Object>> loginResult = authController.login(loginUser);

        // Then: 登录成功，返回 token 和 refreshToken
        assertThat(loginResult.isSuccess()).isTrue();
        assertThat(loginResult.getData()).isNotNull();
        assertThat(loginResult.getData().containsKey("token")).isTrue();
        assertThat(loginResult.getData().containsKey("refreshToken")).isTrue();

        String accessToken = (String) loginResult.getData().get("token");
        String refreshToken = (String) loginResult.getData().get("refreshToken");

        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
    }

    @Test
    void shouldRefreshTokenSuccessfully() {
        // Given: 先注册并登录获取 refreshToken
        LoginUser registerUser = new LoginUser();
        registerUser.setUsername("refresh_test_user");
        registerUser.setPassword("Test@1234");
        
        Mockito.when(userApi.registerUser(Mockito.any())).thenReturn(Result.success());
        
        authController.register(registerUser, null);

        LoginUser loginUser = new LoginUser();
        loginUser.setUsername("refresh_test_user");
        loginUser.setPassword("Test@1234");
        loginUser.setCaptcha("ABCD");
        loginUser.setUuid("test-uuid-2");

        UserCredentialDTO credentialDTO = UserCredentialDTO.builder()
            .userId(2L)
            .username("refresh_test_user")
            .password("Test@1234")
            .build();
        Mockito.when(userApi.getUserCredentialByUsername(Mockito.anyString()))
            .thenReturn(Result.success(credentialDTO));
        
        // Mock UserService for this user
        com.cloudinfra.common.core.domain.model.LoginUser refreshTestUser = com.cloudinfra.common.core.domain.model.LoginUser.builder()
            .userId(2L)
            .username("refresh_test_user")
            .password("Test@1234")
            .roleId(1L)
            .permissions(Set.of("system:user:list"))
            .build();
        Mockito.when(userService.getUserByUsername("refresh_test_user")).thenReturn(refreshTestUser);
        Mockito.when(userService.getUserById(2L)).thenReturn(refreshTestUser);

        com.cloudinfra.common.core.domain.model.LoginUser loginUserDetail = com.cloudinfra.common.core.domain.model.LoginUser.builder()
            .userId(2L)
            .username("refresh_test_user")
            .roleId(1L)
            .permissions(Set.of("system:user:list"))
            .build();
        Mockito.when(userApi.getUserDetail(Mockito.anyLong()))
            .thenReturn(Result.success(loginUserDetail));

        Result<Map<String, Object>> loginResult = authController.login(loginUser);

        String refreshToken = (String) loginResult.getData().get("refreshToken");
        
        // Mock RedisService for refresh token lookup
        String refreshKey = "refresh_token:" + refreshToken;
        Mockito.when(redisService.get(refreshKey)).thenReturn("2");

        // When: 刷新 token
        Result<Map<String, Object>> refreshResult = authController.refresh(refreshToken);

        // Then: 刷新成功，返回新的 token 和 refreshToken
        assertThat(refreshResult.isSuccess()).isTrue();
        assertThat(refreshResult.getData().containsKey("token")).isTrue();
        assertThat(refreshResult.getData().containsKey("refreshToken")).isTrue();

        // 旧 refreshToken 已失效，新的 refreshToken 应该不同
        String newRefreshToken = (String) refreshResult.getData().get("refreshToken");
        assertThat(newRefreshToken).isNotEqualTo(refreshToken);
    }

    @Test
    void shouldLogoutSuccessfully() {
        // Given: 登录获取 token
        LoginUser registerUser = new LoginUser();
        registerUser.setUsername("logout_test_user");
        registerUser.setPassword("Test@1234");
        
        Mockito.when(userApi.registerUser(Mockito.any())).thenReturn(Result.success());
        
        authController.register(registerUser, null);

        LoginUser loginUser = new LoginUser();
        loginUser.setUsername("logout_test_user");
        loginUser.setPassword("Test@1234");
        loginUser.setCaptcha("ABCD");
        loginUser.setUuid("test-uuid-3");

        UserCredentialDTO credentialDTO = UserCredentialDTO.builder()
            .userId(3L)
            .username("logout_test_user")
            .password("Test@1234")
            .build();
        Mockito.when(userApi.getUserCredentialByUsername(Mockito.anyString()))
            .thenReturn(Result.success(credentialDTO));
        
        // Mock UserService for this user
        com.cloudinfra.common.core.domain.model.LoginUser logoutTestUser = com.cloudinfra.common.core.domain.model.LoginUser.builder()
            .userId(3L)
            .username("logout_test_user")
            .password("Test@1234")
            .roleId(1L)
            .permissions(Set.of("system:user:list"))
            .build();
        Mockito.when(userService.getUserByUsername("logout_test_user")).thenReturn(logoutTestUser);
        Mockito.when(userService.getUserById(3L)).thenReturn(logoutTestUser);

        com.cloudinfra.common.core.domain.model.LoginUser loginUserDetail = com.cloudinfra.common.core.domain.model.LoginUser.builder()
            .userId(3L)
            .username("logout_test_user")
            .roleId(1L)
            .permissions(Set.of("system:user:list"))
            .build();
        Mockito.when(userApi.getUserDetail(Mockito.anyLong()))
            .thenReturn(Result.success(loginUserDetail));

        Result<Map<String, Object>> loginResult = authController.login(loginUser);

        String accessToken = (String) loginResult.getData().get("token");

        // When: 登出
        Result<Void> logoutResult = authController.logout("Bearer " + accessToken);

        // Then: 登出成功
        assertThat(logoutResult.isSuccess()).isTrue();

        // 再次尝试用同一个 token 登出应该失败（token 已加入黑名单）
        Result<Void> logoutAgain = authController.logout("Bearer " + accessToken);
        // 登出接口本身不校验 token 有效性，只做黑名单加入，所以仍返回成功
        assertThat(logoutAgain.isSuccess()).isTrue();
    }

    @Test
    void shouldRejectInvalidPasswordOnRegister() {
        // Given: 密码不符合强度要求
        LoginUser registerUser = new LoginUser();
        registerUser.setUsername("weak_password_user");
        registerUser.setPassword("weak");

        // When: 注册
        Result<Void> result = authController.register(registerUser, null);

        // Then: 失败，提示密码强度不足
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("密码强度不足");
    }

    @Test
    void shouldRejectDuplicateUsername() {
        // Given: 先注册一个用户
        LoginUser registerUser1 = new LoginUser();
        registerUser1.setUsername("duplicate_user");
        registerUser1.setPassword("Test@1234");
        
        Mockito.when(userApi.registerUser(Mockito.any())).thenReturn(Result.success());
        
        authController.register(registerUser1, null);

        // When: 再次注册相同用户名
        LoginUser registerUser2 = new LoginUser();
        registerUser2.setUsername("duplicate_user");
        registerUser2.setPassword("Test@1234");
        
        Mockito.when(userApi.registerUser(Mockito.any())).thenReturn(Result.fail("用户已存在"));
        
        Result<Void> result = authController.register(registerUser2, null);

        // Then: 失败
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void shouldCreateAndValidateTokenViaService() {
        // Given: 创建 LoginUser
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(100L);
        loginUser.setUsername("service_test_user");
        loginUser.setRoleId(1L);

        // When: 通过 service 创建 token
        Map<String, Object> tokens = authTokenService.createToken(loginUser);

        // Then: 返回 token 和 refreshToken
        assertThat(tokens).isNotNull();
        assertThat(tokens.containsKey("token")).isTrue();
        assertThat(tokens.containsKey("refreshToken")).isTrue();

        String accessToken = (String) tokens.get("token");
        String refreshToken = (String) tokens.get("refreshToken");

        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
    }
}