package com.cloudinfra.system.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cloudinfra.common.core.exception.GlobalException;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.service.impl.SysPostServiceImpl;

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
