package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysPost;
import com.cloudinfra.system.mapper.SysPostMapper;
import com.cloudinfra.system.service.ISysPostService;
import org.springframework.stereotype.Service;

import com.cloudinfra.common.core.exception.GlobalException;
import com.cloudinfra.common.core.exception.SystemExceptionCode;
import com.cloudinfra.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class SysPostServiceImpl extends ServiceImpl<SysPostMapper, SysPost> implements ISysPostService {

    @Autowired
    private SysUserMapper userMapper;

    @Transactional
    @Override
    public void deletePostByIds(List<Long> postIds) {
        for (Long postId : postIds) {
            if (userMapper.countByPostId(postId) > 0) {
                throw new GlobalException(SystemExceptionCode.POST_IN_USE);
            }
        }
        baseMapper.deleteBatchIds(postIds);
    }
}
