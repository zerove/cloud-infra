package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.SystemException;
import com.cloudinfra.common.core.audit.IAuditLogService;
import com.cloudinfra.system.service.ISysUserService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultActions;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 框架一致性集成测试
 * 验证 ResponseAdvice, GlobalExceptionHandler, AuditLogAspect 的联动行为
 */
@SpringBootTest
@AutoConfigureMockMvc
public class FrameworkConsistencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ISysUserService userService;

    @MockBean
    private IAuditLogService auditLogService;

    @Test
    public void testResponseWrapping_Success() throws Exception {
        // 场景：调用一个返回 POJO 的接口，验证是否被自动包装为 Result.success
        // 假设 /system/user/list 在没有任何参数时返回空列表
        mockMvc.perform(get("/system/user/list"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.code").value(200))
               .andExpect(jsonPath("$.msg").value("操作成功"));
    }

    @Test
    public void testGlobalExceptionHandler_BusinessException() throws Exception {
        // 场景：Service 抛出 BusinessException，验证是否被转化为 Result.fail(业务码)
        Mockito.when(userService.insertUser(any())).thenThrow(new BusinessException("用户已存在"));

        String json = "{\"userName\":\"test\", \"userName\":\"test\"}"; // 简写

        mockMvc.perform(post("/system/user")
               .contentType(MediaType.APPLICATION_JSON)
               .content(json))
               .andExpect(status().isOk()) // 业务异常通常返回 200，由 Result.code 区分
               .andExpect(jsonPath("$.code").value(500)) // 默认 BusinessException 码
               .andExpect(jsonPath("$.msg").value("用户已存在"));
    }

    @Test
    public void testGlobalExceptionHandler_SystemException() throws Exception {
        // 场景：Service 抛出 SystemException，验证是否返回系统级错误
        Mockito.when(userService.insertUser(any())).thenThrow(new SystemException("数据库连接超时"));

        String json = "{\"userName\":\"test\"}";

        mockMvc.perform(post("/system/user")
               .contentType(MediaType.APPLICATION_JSON)
               .content(json))
               .andExpect(status().isInternalServerError()) // SystemException 应该返回 500
               .andExpect(jsonPath("$.code").value(500))
               .andExpect(jsonPath("$.msg").value("数据库连接超时"));
    }

    @Test
    public void testAuditLog_Capture() throws Exception {
        // 场景：调用标注了 @AuditLog 的接口，验证审计记录是否被正确保存
        String json = "{\"userName\":\"test\"}";

        mockMvc.perform(post("/system/user")
               .contentType(MediaType.APPLICATION_JSON)
               .content(json))
               .andExpect(status().isOk());

        // 验证 IAuditLogService.save 被调用
        verify(auditLogService, times(1)).save(any());
    }
}
