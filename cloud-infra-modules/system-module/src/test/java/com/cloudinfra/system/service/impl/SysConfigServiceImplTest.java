package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.framework.redis.service.RedisService;
import com.cloudinfra.system.domain.SysConfig;
import com.cloudinfra.system.mapper.SysConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置服务单元测试
 * 覆盖：缓存优先读取、DB 兜底、写后缓存同步、批量删除缓存清理
 */
@ExtendWith(MockitoExtension.class)
public class SysConfigServiceImplTest {

    @Mock
    private SysConfigMapper configMapper;

    @Mock
    private RedisService redisService;

    @InjectMocks
    private SysConfigServiceImpl configService;

    @BeforeEach
    void setUp() {
        // no base stubbing; per-test lenient where needed
    }

    @Test
    void shouldReturnConfigList() {
        SysConfig c1 = new SysConfig();
        c1.setConfigKey("a");
        when(configMapper.selectList(null)).thenReturn(List.of(c1));

        List<SysConfig> result = configService.selectConfigList();

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldReadConfigFromCacheFirst() {
        when(redisService.get("sys:config:default.page.size")).thenReturn("20");

        String value = configService.selectConfigByKey("default.page.size");

        assertThat(value).isEqualTo("20");
        // 缓存命中时不查库
        verify(configMapper, never()).selectOne(any());
    }

    @Test
    void shouldFallbackToDbAndPopulateCache() {
        SysConfig config = new SysConfig();
        config.setConfigKey("default.page.size");
        config.setConfigValue("50");
        when(redisService.get("sys:config:default.page.size")).thenReturn(null);
        when(configMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(config);

        String value = configService.selectConfigByKey("default.page.size");

        assertThat(value).isEqualTo("50");
        verify(redisService).set("sys:config:default.page.size", "50", 3600000L);
    }

    @Test
    void shouldReturnNullWhenConfigNotInCacheOrDb() {
        when(redisService.get(anyString())).thenReturn(null);
        when(configMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        String value = configService.selectConfigByKey("missing.key");

        assertThat(value).isNull();
        verify(redisService, never()).set(anyString(), any(), anyLong());
    }

    @Test
    void shouldInsertAndCache() {
        SysConfig config = new SysConfig();
        config.setConfigKey("sys.title");
        config.setConfigValue("Cloud-Infra");
        when(configMapper.insert(config)).thenReturn(1);

        int result = configService.insertConfig(config);

        assertThat(result).isEqualTo(1);
        verify(redisService).set("sys:config:sys.title", "Cloud-Infra", 3600000L);
    }

    @Test
    void shouldUpdateAndRefreshCache() {
        SysConfig old = new SysConfig();
        old.setId(1L);
        old.setConfigKey("old.key");
        old.setConfigValue("old");

        SysConfig updated = new SysConfig();
        updated.setId(1L);
        updated.setConfigKey("new.key");
        updated.setConfigValue("new");

        when(configMapper.selectById(1L)).thenReturn(old);
        when(configMapper.updateById(updated)).thenReturn(1);

        int result = configService.updateConfig(updated);

        assertThat(result).isEqualTo(1);
        // 清理旧 key 缓存，写入新 key 缓存
        verify(redisService).set("sys:config:old.key", null, 0L);
        verify(redisService).set("sys:config:new.key", "new", 3600000L);
    }

    @Test
    void shouldEvictCacheBeforeBatchDelete() {
        SysConfig config = new SysConfig();
        config.setId(1L);
        config.setConfigKey("to.delete");
        when(configMapper.selectById(1L)).thenReturn(config);
        when(configMapper.deleteBatchIds(List.of(new Long[]{1L, 2L}))).thenReturn(2);

        int result = configService.deleteConfigByIds(new Long[] {1L, 2L});

        assertThat(result).isEqualTo(2);
        verify(redisService).set(eq("sys:config:to.delete"), eq(null), eq(0L));
        // 不存在的 id 不触发缓存清理
        verify(redisService, org.mockito.Mockito.times(1)).set(anyString(), eq(null), eq(0L));
    }
}