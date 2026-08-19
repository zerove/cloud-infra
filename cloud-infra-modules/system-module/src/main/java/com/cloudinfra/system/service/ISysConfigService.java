package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cloudinfra.system.domain.SysConfig;
import java.util.List;

public interface ISysConfigService {
    IPage<SysConfig> selectConfigList(SysConfig query, long pageNum, long pageSize);
    SysConfig selectConfigById(Long id);
    String selectConfigByKey(String configKey);
    int insertConfig(SysConfig config);
    int updateConfig(SysConfig config);
    int deleteConfigByIds(Long[] ids);
}
