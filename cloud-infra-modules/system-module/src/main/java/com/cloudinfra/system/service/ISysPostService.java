package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysPost;
import java.util.List;

public interface ISysPostService extends IService<SysPost> {
    void deletePostByIds(List<Long> postIds);
}
