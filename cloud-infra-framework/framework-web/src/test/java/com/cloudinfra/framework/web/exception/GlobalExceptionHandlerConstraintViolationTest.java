package com.cloudinfra.framework.web.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.exception.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约 (#15): 方法参数约束失败必须返回 HTTP 400 + 业务码 PARAM_ERROR, 记 WARN 且不带堆栈。
 *
 * <p>机制经 file-service 端到端 IT 实测确定: Spring 7 / Boot 4.1 下 {@code @Validated}
 * 控制器的方法参数校验走 {@code MethodValidationPostProcessor} 的 AOP 路径, 抛
 * {@link ConstraintViolationException} (而非 MVC 6.1+ 的 HandlerMethodValidationException)。
 * 本测试固定该分支, 防止 #15 新增的参数约束在失败时被兜底成 500。</p>
 */
class GlobalExceptionHandlerConstraintViolationTest {

    static class ConstrainedBean {
        @Min(value = 1, message = "limit 必须大于等于 1")
        @Max(value = 10, message = "limit 不能超过 10")
        int limit;

        ConstrainedBean(int limit) {
            this.limit = limit;
        }
    }

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
    @DisplayName("约束违反 → HTTP 400 + PARAM_ERROR, 记 WARN 且无堆栈")
    void constraintViolationReturns400ParamErrorWithWarnLog() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        Set<ConstraintViolation<ConstrainedBean>> violations = validator.validate(new ConstrainedBean(999));
        assertThat(violations).isNotEmpty();

        ResponseEntity<Result<?>> response =
                handler.handleConstraintViolationException(new ConstraintViolationException(violations));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());

        List<ILoggingEvent> warns = events(Level.WARN);
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains("Constraint violation");
        assertThat(warns.get(0).getThrowableProxy()).isNull();
    }

    private List<ILoggingEvent> events(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).toList();
    }
}
