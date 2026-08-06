package com.cloudinfra.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.file.domain.StorageQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StorageQuotaMapper extends BaseMapper<StorageQuota> {

    /**
     * 原子调整存储用量（消除 TOCTOU 竞态）。
     * 条件：调整后用量必须 >= 0 且 <= 配额上限，否则不更新并返回 0。
     *
     * @param userId 用户ID
     * @param delta  增量（上传为正、删除为负）
     * @return 受影响行数；0 表示配额不足或用量将变为负数
     */
    @Update("UPDATE sys_storage_quota SET used_size_bytes = used_size_bytes + #{delta}, "
        + "update_time = CURRENT_TIMESTAMP "
        + "WHERE user_id = #{userId} "
        + "AND used_size_bytes + #{delta} >= 0 "
        + "AND used_size_bytes + #{delta} <= max_size_bytes")
    int atomicUpdateUsage(@Param("userId") Long userId, @Param("delta") long delta);
}
