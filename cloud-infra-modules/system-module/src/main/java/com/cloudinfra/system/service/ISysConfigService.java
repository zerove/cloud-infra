package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.SysConfig;
import java.util.List;

public interface ISysConfigService {
    List<SysConfig> selectConfigList();
    SysConfig selectConfigById(Long id);
    String selectConfigByKey(String configKey);
    int insertConfig(SysConfig config);
    int updateConfig(SysConfig config);
    int deleteConfigByIds(Long[] ids);
}
