package com.cloudinfra.file.service;

import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.ErrorCode;
import com.cloudinfra.file.domain.StorageQuota;
import com.cloudinfra.file.mapper.StorageQuotaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageQuotaService {

    private final StorageQuotaMapper quotaMapper;

    /**
     * 校验存储配额是否充足
     */
    public void checkQuota(Long userId, long fileSize) {
        StorageQuota quota = quotaMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StorageQuota>()
                .eq(StorageQuota::getUserId, userId)
        );

        // 如果没有定义配额，默认允许上传 (或可以定义一个全局默认值)
        if (quota == null) return;

        if (quota.getUsedSizeBytes() + fileSize > quota.getMaxSizeBytes()) {
            throw new BusinessException(ErrorCode.RESOURCE_EXHAUSTED, 
                String.format("存储空间不足。已用: %d, 上限: %d, 尝试上传: %d", 
                quota.getUsedSizeBytes(), quota.getMaxSizeBytes(), fileSize));
        }
    }

    /**
     * 更新存储用量
     * @param userId 用户ID
     * @param delta 增量 (正数为增加，负数为减少)
     */
    @Transactional
    public void updateUsage(Long userId, long delta) {
        StorageQuota quota = quotaMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StorageQuota>()
                .eq(StorageQuota::getUserId, userId)
        );

        if (quota == null) return;

        quota.setUsedSizeBytes(quota.getUsedSizeBytes() + delta);
        quotaMapper.updateById(quota);
    }
}