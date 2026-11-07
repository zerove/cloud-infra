package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.system.domain.SysDictData;
import com.cloudinfra.system.domain.SysDictType;
import com.cloudinfra.system.mapper.SysDictDataMapper;
import com.cloudinfra.system.mapper.SysDictTypeMapper;
import com.cloudinfra.system.service.ISysDictService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SysDictServiceImpl implements ISysDictService {

    private final SysDictTypeMapper dictTypeMapper;
    private final SysDictDataMapper dictDataMapper;
    private final org.springframework.data.redis.core.RedisTemplate<
        String,
        Object
    > redisTemplate;

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
    @Transactional(rollbackFor = Exception.class)
    public int insertDictType(SysDictType dictType) {
        return dictTypeMapper.insert(dictType);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateDictType(SysDictType dictType) {
        SysDictType old = dictTypeMapper.selectById(dictType.getId());
        int result = dictTypeMapper.updateById(dictType);
        if (result > 0 && old != null) {
            redisTemplate.delete(CACHE_KEY_PREFIX + old.getDictType());
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteDictTypeByIds(Long[] ids) {
        for (Long id : ids) {
            SysDictType type = dictTypeMapper.selectById(id);
            if (type != null) {
                redisTemplate.delete(CACHE_KEY_PREFIX + type.getDictType());
            }
        }
        return dictTypeMapper.deleteBatchIds(List.of(ids));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SysDictData> selectDictDataByType(String dictType) {
        String cacheKey = CACHE_KEY_PREFIX + dictType;
        Object cached = redisTemplate.opsForValue().get(cacheKey);

        if (cached instanceof List<?>) {
            return (List<SysDictData>) cached;
        }

        List<SysDictData> dictDatas = dictDataMapper.selectList(
            new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getDictType, dictType)
                .eq(SysDictData::getStatus, "0")
                .orderByAsc(SysDictData::getDictSort)
        );

        if (!dictDatas.isEmpty()) {
            redisTemplate
                .opsForValue()
                .set(cacheKey, dictDatas, java.time.Duration.ofHours(1));
        }

        return dictDatas;
    }

    @Override
    public String selectDictLabel(String dictType, String dictValue) {
        if (dictType == null || dictValue == null) {
            return null;
        }
        for (SysDictData data : selectDictDataByType(dictType)) {
            if (dictValue.equals(data.getDictValue())) {
                return data.getDictLabel();
            }
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertDictData(SysDictData dictData) {
        int result = dictDataMapper.insert(dictData);
        if (result > 0) {
            redisTemplate.delete(CACHE_KEY_PREFIX + dictData.getDictType());
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateDictData(SysDictData dictData) {
        int result = dictDataMapper.updateById(dictData);
        if (result > 0) {
            redisTemplate.delete(CACHE_KEY_PREFIX + dictData.getDictType());
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteDictDataByIds(Long[] ids) {
        for (Long id : ids) {
            SysDictData data = dictDataMapper.selectById(id);
            if (data != null) {
                redisTemplate.delete(CACHE_KEY_PREFIX + data.getDictType());
            }
        }
        return dictDataMapper.deleteBatchIds(List.of(ids));
    }

    @Override
    public void clearDictCache() {
        String pattern = CACHE_KEY_PREFIX + "*";
        Set<String> keys = new java.util.HashSet<>();
        try (var cursor = redisTemplate.scan(ScanOptions.scanOptions().match(pattern).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}