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
        return baseMapper.selectList(new LambdaQueryWrapper<SysPost>()
                .like(post.getPostName() != null, SysPost::getPostName, post.getPostName())
                .eq(post.getStatus() != null, SysPost::getStatus, post.getStatus())
                .orderByAsc(SysPost::getPostSort));
    }

    @Override
    public boolean insertPost(SysPost post) {
        return this.save(post);
    }

    @Override
    public boolean deletePostById(Long postId) {
        return this.removeById(postId);
    }

    @Override
    public boolean deletePostByIds(List<Long> postIds) {
        return this.removeByIds(postIds);
    }
}
