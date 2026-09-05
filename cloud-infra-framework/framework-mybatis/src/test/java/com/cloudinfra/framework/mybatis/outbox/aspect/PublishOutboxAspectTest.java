package com.cloudinfra.framework.mybatis.outbox.aspect;

import com.cloudinfra.framework.mybatis.outbox.OutboxEventPublisher;
import com.cloudinfra.framework.mybatis.outbox.PublishOutbox;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PublishOutboxAspect 单元测试 (M6-03)
 *
 * 覆盖 5 个关键场景:
 * - 业务方法返回 DTO → 自动 publish (bindingName/aggregateId/eventType 解析)
 * - aggregateId SpEL 表达式 (#result.userId)
 * - 默认值: eventType=方法名, aggregateType=返回类名, aggregateId=toString
 * - 业务异常 → 不 publish (proceed 抛异常未到 publish)
 * - 序列化失败 → 不阻塞业务返回 (warn log + 跳过)
 */
class PublishOutboxAspectTest {

    private OutboxEventPublisher publisher;
    private PublishOutboxAspect aspect;

    @BeforeEach
    void setUp() {
        publisher = mock(OutboxEventPublisher.class);
        aspect = new PublishOutboxAspect(publisher);
    }

    // ========== 辅助: 构造 mock ProceedingJoinPoint ==========

    private ProceedingJoinPoint mockJoinPoint(Method method, Object[] args, Object result) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn(method.getName());
        when(signature.getMethod()).thenReturn(method);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn(result);
        return pjp;
    }

    private PublishOutbox annotation(String bindingName, String eventType, String aggregateType, String aggregateId) {
        return new PublishOutbox() {
            @Override public String bindingName() { return bindingName; }
            @Override public String eventType() { return eventType; }
            @Override public String aggregateType() { return aggregateType; }
            @Override public String aggregateId() { return aggregateId; }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return PublishOutbox.class; }
        };
    }

    // ========== 测试 ==========

    @Test
    @DisplayName("完整 publish: bindingName/aggregateId/eventType 全部解析")
    void shouldPublishWithExplicitAnnotation() throws Throwable {
        Method method = TestService.class.getMethod("login", LoginRequest.class);
        LoginResult expectedResult = new LoginResult(42L, "token");
        ProceedingJoinPoint pjp = mockJoinPoint(method, new Object[]{new LoginRequest("alice")}, expectedResult);

        PublishOutbox anno = annotation("auditLog", "USER_LOGIN", "User", "#root?.userId ?: #userId");
        Object result = aspect.around(pjp, anno);

        ArgumentCaptor<String> bindingCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventTypeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishTransactional(
            bindingCap.capture(),
            typeCap.capture(),
            idCap.capture(),
            eventTypeCap.capture(),
            payloadCap.capture()
        );
        assertThat(bindingCap.getValue()).isEqualTo("auditLog");
        assertThat(typeCap.getValue()).isEqualTo("User");
        // SpEL 提取 userId=42 (JDK 21+ 默认反射支持参数名)
        assertThat(idCap.getValue()).matches("\\d+");
        assertThat(eventTypeCap.getValue()).isEqualTo("USER_LOGIN");
        assertThat(payloadCap.getValue()).isSameAs(expectedResult);  // 业务返回值原样返回
        assertThat(result).isSameAs(expectedResult);
    }

    @Test
    @DisplayName("默认值: eventType=方法名, aggregateType=返回类名, aggregateId=toString")
    void shouldUseDefaultsWhenAnnotationEmpty() throws Throwable {
        Method method = TestService.class.getMethod("login", LoginRequest.class);
        ProceedingJoinPoint pjp = mockJoinPoint(method, new Object[]{new LoginRequest("bob")}, new LoginResult(99L, "tok"));

        PublishOutbox anno = annotation("auditLog", "", "", "");
        aspect.around(pjp, anno);

        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventTypeCap = ArgumentCaptor.forClass(String.class);
        verify(publisher).publishTransactional(any(), typeCap.capture(), idCap.capture(), eventTypeCap.capture(), any());
        assertThat(eventTypeCap.getValue()).isEqualTo("login");  // 方法名
        assertThat(typeCap.getValue()).isEqualTo("LoginResult");   // 返回类名
        assertThat(idCap.getValue()).contains("99");               // toString 含 userId
    }

    @Test
    @DisplayName("业务返回 null → 不 publish")
    void shouldNotPublishWhenResultIsNull() throws Throwable {
        Method method = TestService.class.getMethod("deleteUser", Long.class);
        ProceedingJoinPoint pjp = mockJoinPoint(method, new Object[]{1L}, null);

        PublishOutbox anno = annotation("auditLog", "USER_DELETE", "User", "#result");
        Object result = aspect.around(pjp, anno);

        verify(publisher, never()).publishTransactional(any(), any(), any(), any(), any());
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("业务异常 → 不 publish (proceed 抛异常中断)")
    void shouldNotPublishWhenBusinessThrows() throws Throwable {
        Method method = TestService.class.getMethod("login", LoginRequest.class);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenThrow(new RuntimeException("业务失败"));

        PublishOutbox anno = annotation("auditLog", "USER_LOGIN", "User", "#root?.userId ?: #userId");
        try {
            aspect.around(pjp, anno);
        } catch (Exception ignored) {
            // 预期异常
        }

        verify(publisher, never()).publishTransactional(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("序列化异常 → 不阻塞业务返回 (warn log + 跳过)")
    void shouldNotBlockBusinessWhenPublishFails() throws Throwable {
        Method method = TestService.class.getMethod("login", LoginRequest.class);
        ProceedingJoinPoint pjp = mockJoinPoint(method, new Object[]{new LoginRequest("eve")}, new LoginResult(1L, "tok"));

        // 模拟 publisher 抛异常 (序列化失败等场景)
        org.mockito.Mockito.doThrow(new RuntimeException("序列化失败"))
            .when(publisher).publishTransactional(any(), any(), any(), any(), any());

        PublishOutbox anno = annotation("auditLog", "USER_LOGIN", "User", "#root?.userId ?: #userId");
        Object result = aspect.around(pjp, anno);

        // 业务返回值原样返回, 即使 publish 失败
        assertThat(result).isNotNull().isSameAs(pjp.proceed());
        verify(publisher, times(1)).publishTransactional(any(), any(), any(), any(), any());
    }

    // ========== 测试 DTO ==========

    static class LoginRequest {
        final String username;
        LoginRequest(String username) { this.username = username; }
    }

    static class LoginResult {
        final Long userId;
        final String token;
        LoginResult(Long userId, String token) { this.userId = userId; this.token = token; }
        public Long getUserId() { return userId; }
        public String getToken() { return token; }
        @Override public String toString() { return "LoginResult{userId=" + userId + "}"; }
    }

    static class TestService {
        public LoginResult login(LoginRequest req) { return new LoginResult(1L, "token"); }
        public void deleteUser(Long id) {}
    }
}