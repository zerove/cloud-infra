package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.BaseEntity;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.exception.BaseException;
import com.cloudinfra.common.core.exception.ErrorCode;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.vo.SysUserVO;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.test.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.RequestResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
public class SysUserDataScopeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SysUserMapper sysUserMapper;

    @Test
    @DisplayName("验证管理员角色-全量数据权限-SQL片段注入正确")
    @WithMockUser(username = "admin", roles = {"ROLE_ADMIN"})
    public void testAdminDataScope() throws Exception {
        // 1. 准备模拟数据
        SysUser userQuery = new SysUser();
        IPage<SysUser> pageResult = new Page<>();
        pageResult.setRecords(Collections.singletonList(new SysUser()));

        // 模拟 Mapper 返回结果
        when(sysUserMapper.selectUserPage(any(), any())).thenReturn(pageResult);

        // 2. 执行请求
        mockMvc.perform(get("/system/user/list")
                .param("pageNum", "1")
                .param("pageSize", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // 3. 验证 DataScopeAspect 是否注入了正确的 SQL 片段
        // 注意：这里需要捕获传递给 Mapper 的第一个参数 (Page) 或第二个参数 (SysUser)
        // 假设 DataScopeAspect 将片段注入到 SysUser 对象的 params map 中
        Mockito.verify(sysUserMapper).selectUserPage(any(), argThat(user -> {
            String dataScope = (String) user.getParams().get("dataScope");
            return dataScope != null && dataScope.contains(" OR 1=0 "); // 示例：具体取决于 Admin 的配置
            // 如果是 ALL，则可能不注入或注入空字符串，具体根据 DataScopeAspect 实现
        }));
    }

    @Test
    @DisplayName("验证普通用户-本人数据权限-SQL片段注入正确")
    @WithMockUser(username = "user1", roles = {"ROLE_USER"})
    public void testSelfDataScope() throws Exception {
        SysUser userQuery = new SysUser();
        IPage<SysUser> pageResult = new Page<>();
        pageResult.setRecords(Collections.singletonList(new SysUser()));

        when(sysUserMapper.selectUserPage(any(), any())).thenReturn(pageResult);

        mockMvc.perform(get("/system/user/list")
                .param("pageNum", "1")
                .param("pageSize", "10"))
                .andExpect(status().isOk());

        Mockito.verify(sysUserMapper).selectUserPage(any(), argThat(user -> {
            String dataScope = (String) user.getParams().get("dataScope");
            return dataScope != null && dataScope.contains("u.user_id");
        }));
    }

    @Test
    @DisplayName("验证 VO 转换为 Record 且字段正确")
    @WithMockUser(username = "admin", roles = {"ROLE_ADMIN"})
    public void testVoRecordConversion() throws Exception {
        SysUser entity = new SysUser();
        entity.setUserId(100L);
        entity.setUsername("test_user");
        entity.setEmail("test@example.com");

        IPage<SysUser> pageResult = new Page<>();
        pageResult.setRecords(Collections.singletonList(entity));

        when(sysUserMapper.selectUserPage(any(), any())).thenReturn(pageResult);

        String response = mockMvc.perform(get("/system/user/list")
                .param("pageNum", "1")
                .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 验证响应中包含 record 转换后的字段
        assertTrue(response.contains("\"username\":\"test_user\""));
        assertTrue(response.contains("\"email\":\"test@example.com\""));
    }

    // 辅助方法用于 Mockito 参数校验
    private <T> T argThat(java.util.function.Predicate<T> predicate) {
        return Mockito.argThat(predicate::test);
    }
}
