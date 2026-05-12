package com.cloudinfra.notification.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * 通知仓库类
 * 提供对通知数据的 CRUD 操作及自定义查询
 */
@Repository
@RequiredArgsConstructor
public class NotificationRepository extends ServiceImpl<NotificationMapper, Notification> {

    /**
     * 分页/列表查询通知历史
     *
     * @param userId 用户ID
     * @param status 通知状态 (null 表示忽略状态过滤)
     * @param type 通知类型 (null 表示忽略类型过滤)
     * @param page 页码 (1-based)
     * @param size 每页数量
     * @return 通知列表，按创建时间倒序排列
     */
    public List<Notification> findHistory(Long userId, NotificationStatus status, NotificationType type, Integer page, Integer size) {
        Page<Notification> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
               .eq(status != null, Notification::getStatus, status)
               .eq(type != null, Notification::getType, type)
               .orderByDesc(Notification::getCreateTime);

        return this.page(pageParam, wrapper).getRecords();
    }
}

