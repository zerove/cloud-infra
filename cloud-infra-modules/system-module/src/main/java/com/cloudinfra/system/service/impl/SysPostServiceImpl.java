package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysPost;
import com.cloudinfra.system.mapper.SysPostMapper;
import com.cloudinfra.system.service.ISysPostService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysPostServiceImpl extends ServiceImpl<SysPostMapper, SysPost> implements ISysPostService {

    @Override
    public List<SysPost> selectPostList(SysPost post) {
        LambdaQueryWrapper<SysPost> wrapper = new LambdaQueryWrapper<>();
        if (post != null) {
            if (post.getPostCode() != null) {
                wrapper.like(SysPost::getPostCode, post.getPostCode());
            }
            if (post.getPostName() != null) {
                wrapper.like(SysPost::getPostName, post.getPostName());
            }
            if (post.getStatus() != null) {
                wrapper.eq(SysPost::getStatus, post.getStatus());
            }
        }
        wrapper.orderByAsc(SysPost::getPostSort);
        return list(wrapper);
    }

    @Override
    public boolean deletePostByIds(List<Long> postIds) {
        return removeByIds(postIds);
    }
}
