package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.framework.redis.service.RedisService;
import com.cloudinfra.system.domain.SysDictData;
import com.cloudinfra.system.domain.SysDictType;
import com.cloudinfra.system.mapper.SysDictDataMapper;
import com.cloudinfra.system.mapper.SysDictTypeMapper;
import com.cloudinfra.system.service.ISysDictService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysDictServiceImpl implements ISysDictService {

    private final SysDictTypeMapper dictTypeMapper;
    private final SysDictDataMapper dictDataMapper;
    private final RedisService redisService;

    private static final String CACHE_KEY_PREFIX = "sys:dict:";

    @Override
    public List<SysDictType> selectDictTypeList() {
        return dictTypeMapper.selectList(null);
    }

    @Override
    public SysDictType selectDictTypeById(Long id) {
        return dictTypeMapper.selectById(id);
    }

    @Override
    @Transactional
    public int insertDictType(SysDictType dictType) {
        return dictTypeMapper.insert(dictType);
    }

    @Override
    @Transactional
    public int updateDictType(SysDictType dictType) {
        // Clear cache for this type if name changed
        SysDictType old = dictTypeMapper.selectById(dictType.getId());
        int result = dictTypeMapper.updateById(dictType);
        if (result > 0) {
            redisService.deleteObject(CACHE_KEY_PREFIX + old.getDictType());
        }
        return result;
    }

    @Override
    @Transactional
    public int deleteDictTypeByIds(Long[] ids) {
        for (Long id : ids) {
            SysDictType type = dictTypeMapper.selectById(id);
            if (type != null) {
                redisService.deleteObject(CACHE_KEY_PREFIX + type.getDictType());
            }
        }
        return dictTypeMapper.deleteBatchIds(List.of(ids));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SysDictData> selectDictDataByType(String dictType) {
        String cacheKey = CACHE_KEY_PREFIX + dictType;
        List<SysDictData> dictDatas = (List<SysDictData>) redisService.get(cacheKey);
        
        if (dictDatas != null) {
            return dictDatas;
        }

        dictDatas = dictDataMapper.selectList(new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getDictType, dictType)
                .eq(SysDictData::getStatus, "0")
                .orderByAsc(SysDictData::getDictSort));
        
        if (!dictDatas.isEmpty()) {
            redisService.set(cacheKey, dictDatas, 3600000); // 1 hour
        }
        
        return dictDatas;
    }

    @Override
    @Transactional
    public int insertDictData(SysDictData dictData) {
        int result = dictDataMapper.insert(dictData);
        if (result > 0) {
            redisService.set(CACHE_KEY_PREFIX + dictData.getDictType(), null, 0);
        }
        return result;
    }

    @Override
    @Transactional
    public int updateDictData(SysDictData dictData) {
        int result = dictDataMapper.updateById(dictData);
        if (result > 0) {
            redisService.set(CACHE_KEY_PREFIX + dictData.getDictType(), null, 0);
        }
        return result;
    }

    @Override
    @Transactional
    public int deleteDictDataByIds(Long[] ids) {
        for (Long id : ids) {
            SysDictData data = dictDataMapper.selectById(id);
            if (data != null) {
                redisService.set(CACHE_KEY_PREFIX + data.getDictType(), null, 0);
            }
        }
        return dictDataMapper.deleteBatchIds(List.of(ids));
    }

    @Override
    public void clearDictCache() {
        // In a real system, we'd use scan to delete all sys:dict:* keys
        // For now, let's keep it simple
    }
}
