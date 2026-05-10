package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysPost;
import java.util.List;

public interface ISysPostService extends IService<SysPost> {
    List<SysPost> selectPostList(SysPost post);
    boolean deletePostByIds(List<Long> postIds);
}
