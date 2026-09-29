package com.cloudinfra.notification.service.channel;

/**
 * 通知渠道接口 - 定义不同发送渠道的统一原语
 */
public interface NotificationChannel {
    /**
     * 获取渠道编码 (例如: "email", "sms", "websocket")
     */
    String getChannelCode();

    /**
     * 获取渠道优先级 (数值越小优先级越高)
     */
    default int getPriority() {
        return 100;
    }

    /**
     * 是否支持 HTML/富文本
     */
    default boolean supportsHtml() {
        return false;
    }

    /**
     * 发送通知内容
     *
     * <p>投递失败时抛 {@link RuntimeException}。不使用 {@code throws Exception}:
     * 现有实现均以未受检异常表达失败, 声明受检异常只会迫使调用方写无信息量的
     * {@code catch (Exception)}, 并掩盖真正的失败类型。</p>
     *
     * @param userId 接收者ID
     * @param title 通知标题
     * @param content 通知内容
     */
    void send(Long userId, String title, String content);
}
