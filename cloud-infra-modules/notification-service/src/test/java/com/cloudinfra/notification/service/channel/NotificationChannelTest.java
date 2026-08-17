package com.cloudinfra.notification.service.channel;

import com.cloudinfra.notification.service.channel.impl.EmailChannel;
import com.cloudinfra.notification.service.channel.impl.SmsChannel;
import com.cloudinfra.notification.service.channel.impl.WebSocketChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 通知渠道测试 - 覆盖三个内置渠道实现与接口默认方法
 */
class NotificationChannelTest {

    @Test
    void smsChannel_shouldExposeCodePriorityAndDefaults() {
        SmsChannel channel = new SmsChannel();

        assertThat(channel.getChannelCode()).isEqualTo("sms");
        assertThat(channel.getPriority()).isEqualTo(10);
        assertThat(channel.supportsHtml()).isFalse();
        assertThatCode(() -> channel.send(1L, "标题", "内容")).doesNotThrowAnyException();
    }

    @Test
    void emailChannel_shouldExposeCodePriorityAndDefaults() {
        EmailChannel channel = new EmailChannel();

        assertThat(channel.getChannelCode()).isEqualTo("email");
        assertThat(channel.getPriority()).isEqualTo(20);
        assertThat(channel.supportsHtml()).isTrue();
        assertThatCode(() -> channel.send(1L, "标题", "内容")).doesNotThrowAnyException();
    }

    @Test
    void webSocketChannel_shouldExposeCodePriorityAndDefaults() {
        WebSocketChannel channel = new WebSocketChannel();

        assertThat(channel.getChannelCode()).isEqualTo("websocket");
        assertThat(channel.getPriority()).isEqualTo(50);
        assertThat(channel.supportsHtml()).isTrue();
        assertThatCode(() -> channel.send(1L, "标题", "内容")).doesNotThrowAnyException();
    }

    @Test
    void interfaceDefaultMethods_shouldReturnDefaults() throws Exception {
        // 匿名实现不覆写默认方法，验证接口默认行为
        NotificationChannel channel = new NotificationChannel() {
            @Override
            public String getChannelCode() {
                return "custom";
            }

            @Override
            public void send(Long userId, String title, String content) {
                // no-op
            }
        };

        assertThat(channel.getPriority()).isEqualTo(100);
        assertThat(channel.supportsHtml()).isFalse();
    }
}