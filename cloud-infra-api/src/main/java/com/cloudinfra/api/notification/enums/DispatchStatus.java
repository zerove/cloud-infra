package com.cloudinfra.api.notification.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通知分发状态 (M7 通知可靠性改造)
 *
 * <p>与 {@link NotificationStatus} 正交: 后者描述"用户是否已读", 本枚举描述
 * "通知是否已投递到渠道"。二者需要同时看才能判断通知的真实状态。</p>
 *
 * <p>存在本枚举的原因是: 渠道分发原先在事务提交后由内存线程池执行,
 * 进程崩溃即永久丢失且无痕迹; 且无法区分"从未分发"与"已分发但未读"。
 * 本枚举让 {@code sys_notification} 行自身充当发件箱记录, 支撑补扫重投。</p>
 */
@Getter
@RequiredArgsConstructor
public enum DispatchStatus {
    /**
     * 待分发 (刚写入事务, 尚未投递)
     */
    PENDING(0, "待分发"),

    /**
     * 分发中 (已被消费者或补扫任务抢占, 正在投递渠道)
     */
    DISPATCHING(1, "分发中"),

    /**
     * 已分发 (至少一个渠道投递成功, 或已确认无需重试)
     */
    DISPATCHED(2, "已分发"),

    /**
     * 分发失败且重试耗尽 (已进 DLQ 或补扫达到最大次数)
     */
    FAILED(3, "分发失败");

    @EnumValue
    private final Integer code;

    private final String description;
}
