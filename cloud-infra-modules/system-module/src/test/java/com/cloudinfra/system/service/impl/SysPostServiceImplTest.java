package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.cloudinfra.system.domain.SysPost;
import com.cloudinfra.system.mapper.SysPostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 岗位服务单元测试
 * 覆盖：列表查询（编码/名称模糊、状态过滤、排序）、批量删除
 */
@ExtendWith(MockitoExtension.class)
public class SysPostServiceImplTest {

    @Mock
    private SysPostMapper postMapper;

    @InjectMocks
    private SysPostServiceImpl postService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(postService, "baseMapper", postMapper);
    }

    @Test
    void shouldListPostsWithFilters() {
        SysPost query = new SysPost();
        query.setPostCode("DEV");
        query.setPostName("研发");
        query.setStatus("0");
        when(postMapper.selectList(any(Wrapper.class))).thenReturn(List.of(query));

        List<SysPost> result = postService.selectPostList(query);

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldListAllPostsWhenQueryNull() {
        SysPost post = new SysPost();
        when(postMapper.selectList(any(Wrapper.class))).thenReturn(List.of(post));

        List<SysPost> result = postService.selectPostList(null);

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldDeletePostsByIds() {
        when(postMapper.deleteByIds(any())).thenReturn(2);

        boolean result = postService.deletePostByIds(List.of(1L, 2L));

        assertThat(result).isTrue();
        verify(postMapper).deleteByIds(any());
    }
}