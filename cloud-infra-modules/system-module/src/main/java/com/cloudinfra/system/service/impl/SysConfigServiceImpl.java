package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.framework.redis.service.RedisService;
import com.cloudinfra.system.domain.SysConfig;
import com.cloudinfra.system.mapper.SysConfigMapper;
import com.cloudinfra.system.service.ISysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysConfigServiceImpl implements ISysConfigService {

    private final SysConfigMapper configMapper;
    private final RedisService redisService;

    private static final String CACHE_KEY_PREFIX = "sys:config:";

    @Override
    public IPage<SysConfig> selectConfigList(SysConfig query, long pageNum, long pageSize) {
        Page<SysConfig> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            if (query.getConfigName() != null && !query.getConfigName().isEmpty()) {
                wrapper.like(SysConfig::getConfigName, query.getConfigName());
            }
            if (query.getConfigKey() != null && !query.getConfigKey().isEmpty()) {
                wrapper.eq(SysConfig::getConfigKey, query.getConfigKey());
            }
            if (query.getConfigType() != null) {
                wrapper.eq(SysConfig::getConfigType, query.getConfigType());
            }
        }
        return configMapper.selectPage(page, wrapper);
    }

    @Override
    public SysConfig selectConfigById(Long id) {
        return configMapper.selectById(id);
    }

    @Override
    public String selectConfigByKey(String configKey) {
        String cacheKey = CACHE_KEY_PREFIX + configKey;
        String configValue = (String) redisService.get(cacheKey);
        
        if (configValue != null) {
            return configValue;
        }

        SysConfig config = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, configKey));
        
        if (config != null) {
            configValue = config.getConfigValue();
            redisService.set(cacheKey, configValue, 3600000);
        }
        
        return configValue;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertConfig(SysConfig config) {
        int result = configMapper.insert(config);
        if (result > 0) {
            redisService.set(CACHE_KEY_PREFIX + config.getConfigKey(), config.getConfigValue(), 3600000);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateConfig(SysConfig config) {
        SysConfig old = configMapper.selectById(config.getId());
        int result = configMapper.updateById(config);
        if (result > 0) {
            redisService.set(CACHE_KEY_PREFIX + old.getConfigKey(), null, 0);
            redisService.set(CACHE_KEY_PREFIX + config.getConfigKey(), config.getConfigValue(), 3600000);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteConfigByIds(Long[] ids) {
        for (Long id : ids) {
            SysConfig config = configMapper.selectById(id);
            if (config != null) {
                redisService.set(CACHE_KEY_PREFIX + config.getConfigKey(), null, 0);
            }
        }
        return configMapper.deleteBatchIds(List.of(ids));
    }
}
