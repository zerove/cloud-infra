package com.cloudinfra.framework.web.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.exception.ErrorCode;
import com.cloudinfra.common.core.exception.SystemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} 业务异常日志分级回归测试。
 *
 * <p>契约 (#13): 4xx 建议状态 (客户端可预期的语义失败) 记 WARN 且不带堆栈;
 * 5xx 记 ERROR 且保留堆栈。以真实 Logback 捕获断言, 防止回退成"一律 ERROR"。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLogCapture() {
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("4xx 业务异常: WARN 且无 ERROR、无堆栈")
    void clientErrorShouldLogWarnOnly() {
        com.cloudinfra.common.core.exception.AccessDeniedException e =
                new com.cloudinfra.common.core.exception.AccessDeniedException(ErrorCode.FORBIDDEN);

        ResponseEntity<Result<?>> response = handler.handleBaseException(e);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());

        List<ILoggingEvent> warns = events(Level.WARN);
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains("Business exception occurred");
        assertThat(warns.get(0).getThrowableProxy()).isNull();
        assertThat(events(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("5xx 业务异常: ERROR 且保留堆栈")
    void serverErrorShouldLogErrorWithStack() {
        SystemException e = new SystemException("内部依赖不可用");

        ResponseEntity<Result<?>> response = handler.handleBaseException(e);

        assertThat(response.getStatusCode().value()).isEqualTo(500);

        List<ILoggingEvent> errors = events(Level.ERROR);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getThrowableProxy()).isNotNull();
        assertThat(events(Level.WARN)).isEmpty();
    }

    private List<ILoggingEvent> events(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).toList();
    }
}
