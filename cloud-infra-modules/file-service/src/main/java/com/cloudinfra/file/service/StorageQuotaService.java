package com.cloudinfra.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
     * 预检查配额是否充足（仅用于友好提示，不保证原子性）。
     * 真正的强校验与扣减由 {@link #tryReserve} 原子完成。
     */
    public void checkQuota(Long userId, long fileSize) {
        StorageQuota quota = getQuota(userId);
        if (quota == null) return;
        if (quota.getUsedSizeBytes() + fileSize > quota.getMaxSizeBytes()) {
            throw new BusinessException(ErrorCode.RESOURCE_EXHAUSTED,
                String.format("存储空间不足。已用: %d, 上限: %d, 尝试上传: %d",
                    quota.getUsedSizeBytes(), quota.getMaxSizeBytes(), fileSize));
        }
    }

    /**
     * 原子预留存储空间：用量增加与配额上限校验在同一条 UPDATE 中完成，
     * 消除 check-then-act 竞态窗口。
     *
     * @throws BusinessException 配额不足时抛出 RESOURCE_EXHAUSTED
     */
    public void tryReserve(Long userId, long size) {
        if (userId == null || size <= 0) return;
        int rows = quotaMapper.atomicUpdateUsage(userId, size);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_EXHAUSTED,
                "存储空间不足或配额未配置，无法完成上传");
        }
    }

    /**
     * 释放存储空间（删除文件时扣减用量）。
     * 对未配置配额（无记录）的用户静默跳过。
     */
    public void release(Long userId, long size) {
        if (userId == null || size <= 0) return;
        StorageQuota quota = getQuota(userId);
        if (quota == null) return;
        quotaMapper.atomicUpdateUsage(userId, -size);
    }

    private StorageQuota getQuota(Long userId) {
        return quotaMapper.selectOne(
            new LambdaQueryWrapper<StorageQuota>()
                .eq(StorageQuota::getUserId, userId)
        );
    }
}
