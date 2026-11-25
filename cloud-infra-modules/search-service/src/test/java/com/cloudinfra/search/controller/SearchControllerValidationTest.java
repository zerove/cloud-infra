package com.cloudinfra.search.controller;

import com.cloudinfra.common.core.exception.ErrorCode;
import com.cloudinfra.framework.web.exception.GlobalExceptionHandler;
import com.cloudinfra.search.service.SearchService;
import com.cloudinfra.search.service.UnifiedSearchService;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 (#15): {@link SearchController} 的请求参数约束必须真实生效。
 *
 * <p>两类约束的生效机制不同, 故用两套夹具分别覆盖:
 * <ul>
 *   <li>{@code @RequestParam} 上的 {@code @NotBlank/@Min/@Max} —— 类级 {@code @Validated}
 *       经 {@code MethodValidationPostProcessor} 的 AOP 代理校验, 抛
 *       {@link ConstraintViolationException}; 与 file-service 端到端 IT 实测一致。</li>
 *   <li>{@code @Valid SearchRequest} —— 属 MVC 数据绑定校验
 *       ({@code ModelAttributeMethodProcessor}), 不经 AOP 代理, 故用 MockMvc 覆盖。</li>
 * </ul>
 */
class SearchControllerValidationTest {

    private SearchController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        MethodValidationPostProcessor postProcessor = new MethodValidationPostProcessor();
        postProcessor.setProxyTargetClass(true);
        postProcessor.setValidator(validator);
        postProcessor.afterPropertiesSet();
        SearchController target = new SearchController(mock(SearchService.class), mock(UnifiedSearchService.class));
        controller = (SearchController) postProcessor.postProcessAfterInitialization(target, "searchController");

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SearchController(mock(SearchService.class), mock(UnifiedSearchService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("query 关键字为空 -> 违反 @NotBlank")
    void unifiedSearchBlankKeywordViolatesConstraint() {
        assertThatThrownBy(() -> controller.unifiedSearch("   ", 20))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("搜索关键字不能为空");
    }

    @Test
    @DisplayName("query limit 越界 -> 违反 @Min/@Max")
    void unifiedSearchLimitOutOfRangeViolatesConstraint() {
        assertThatThrownBy(() -> controller.unifiedSearch("abc", 999))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("limit 不能超过 100");
    }

    @Test
    @DisplayName("query 合法入参 -> 不触发校验")
    void validUnifiedSearchPassesValidation() {
        assertThatCode(() -> controller.unifiedSearch("abc", 20)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("operlog 非法分页 -> MVC 数据绑定校验返回 400")
    void operLogInvalidPageSizeIsRejectedWith400() throws Exception {
        mockMvc.perform(get("/search/operlog").param("pageSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("operlog 合法分页 -> 通过校验")
    void operLogValidPageSizePassesValidation() throws Exception {
        mockMvc.perform(get("/search/operlog").param("pageNum", "1").param("pageSize", "20"))
                .andExpect(status().isOk());
    }
}
