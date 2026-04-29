package com.cloudinfra.system.service;

import com.cloudinfra.common.core.exception.GlobalException;
import com.cloudinfra.common.core.exception.SystemExceptionCode;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.service.impl.SysPostServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SysPostServiceTest {

    @Mock
    private SysUserMapper userMapper;

    @InjectMocks
    private SysPostServiceImpl postService;

    @Test
    public void testDeletePostByIds_InUse() {
        Long postId = 1L;
        when(userMapper.countByPostId(postId)).thenReturn(1L);

        assertThrows(GlobalException.class, () -> {
            postService.deletePostByIds(Collections.singletonList(postId));
        }, "岗位存在关联用户，禁止删除");
    }
}
