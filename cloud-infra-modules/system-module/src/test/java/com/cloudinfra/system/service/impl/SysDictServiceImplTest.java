package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.system.domain.SysDictData;
import com.cloudinfra.system.domain.SysDictType;
import com.cloudinfra.system.mapper.SysDictDataMapper;
import com.cloudinfra.system.mapper.SysDictTypeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字典服务单元测试
 * 覆盖：字典类型 CRUD、缓存清理、字典数据缓存优先读取、批量删除缓存清理
 */
@ExtendWith(MockitoExtension.class)
public class SysDictServiceImplTest {

    @Mock
    private SysDictTypeMapper dictTypeMapper;

    @Mock
    private SysDictDataMapper dictDataMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    @InjectMocks
    private SysDictServiceImpl dictService;

    @Test
    void shouldListDictTypes() {
        SysDictType type = new SysDictType();
        type.setDictType("sys_normal");
        when(dictTypeMapper.selectList(null)).thenReturn(List.of(type));

        List<SysDictType> result = dictService.selectDictTypeList();

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldUpdateDictTypeAndEvictCache() {
        SysDictType old = new SysDictType();
        old.setId(1L);
        old.setDictType("sys_old");
        SysDictType updated = new SysDictType();
        updated.setId(1L);
        updated.setDictType("sys_new");
        when(dictTypeMapper.selectById(1L)).thenReturn(old);
        when(dictTypeMapper.updateById(updated)).thenReturn(1);

        int result = dictService.updateDictType(updated);

        assertThat(result).isEqualTo(1);
        verify(redisTemplate).delete("sys:dict:sys_old");
    }

    @Test
    void shouldDeleteDictTypesAndEvictCache() {
        SysDictType type = new SysDictType();
        type.setId(1L);
        type.setDictType("sys_to_delete");
        when(dictTypeMapper.selectById(1L)).thenReturn(type);
        when(dictTypeMapper.deleteBatchIds(List.of(new Long[]{1L, 2L}))).thenReturn(2);

        int result = dictService.deleteDictTypeByIds(new Long[] {1L, 2L});

        assertThat(result).isEqualTo(2);
        verify(redisTemplate).delete("sys:dict:sys_to_delete");
    }

    @Test
    void shouldReadDictDataFromCacheFirst() {
        SysDictData d1 = new SysDictData();
        d1.setDictType("sys_status");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("sys:dict:sys_status")).thenReturn(List.of(d1));

        List<SysDictData> result = dictService.selectDictDataByType("sys_status");

        assertThat(result).hasSize(1);
        // 缓存命中不查库
        verify(dictDataMapper, never()).selectList(any());
    }

    @Test
    void shouldLoadDictDataFromDbAndCache() {
        SysDictData d1 = new SysDictData();
        d1.setDictType("sys_status");
        d1.setDictValue("0");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("sys:dict:sys_status")).thenReturn(null);
        when(dictDataMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(d1));

        List<SysDictData> result = dictService.selectDictDataByType("sys_status");

        assertThat(result).hasSize(1);
        verify(valueOps).set(eq("sys:dict:sys_status"), any(), org.mockito.ArgumentMatchers.<Duration>eq(Duration.ofHours(1)));
    }

    @Test
    void shouldNotCacheEmptyDictData() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("sys:dict:sys_empty")).thenReturn(null);
        when(dictDataMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        List<SysDictData> result = dictService.selectDictDataByType("sys_empty");

        assertThat(result).isEmpty();
        verify(valueOps, never()).set(anyString(), any(), org.mockito.ArgumentMatchers.<Duration>any());
    }

    @Test
    void shouldInsertDictDataAndEvictCache() {
        SysDictData data = new SysDictData();
        data.setDictType("sys_status");
        when(dictDataMapper.insert(data)).thenReturn(1);

        int result = dictService.insertDictData(data);

        assertThat(result).isEqualTo(1);
        verify(redisTemplate).delete("sys:dict:sys_status");
    }

    @Test
    void shouldDeleteDictDataAndEvictCache() {
        SysDictData data = new SysDictData();
        data.setId(1L);
        data.setDictType("sys_status");
        when(dictDataMapper.selectById(1L)).thenReturn(data);
        when(dictDataMapper.deleteBatchIds(List.of(new Long[]{1L}))).thenReturn(1);

        int result = dictService.deleteDictDataByIds(new Long[] {1L});

        assertThat(result).isEqualTo(1);
        verify(redisTemplate).delete("sys:dict:sys_status");
    }

    @Test
    void shouldClearAllDictCache() {
        when(redisTemplate.keys("sys:dict:*")).thenReturn(Set.of("sys:dict:a", "sys:dict:b"));

        dictService.clearDictCache();

        verify(redisTemplate).delete(Set.of("sys:dict:a", "sys:dict:b"));
    }

    @Test
    void shouldSkipDeleteWhenNoCacheKeys() {
        when(redisTemplate.keys("sys:dict:*")).thenReturn(Set.of());

        dictService.clearDictCache();

        verify(redisTemplate, never()).delete(any(Set.class));
    }
}