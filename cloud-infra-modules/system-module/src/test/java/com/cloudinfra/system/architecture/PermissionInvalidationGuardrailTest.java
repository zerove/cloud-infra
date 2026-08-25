package com.cloudinfra.system.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限失效协议护栏：禁止业务代码直接发布 auth:refresh:channel。
 *
 * 背景：失效广播曾以字符串字面量散落多个服务类，deleteRoleByIds 因漏写
 * 广播产生权限残留。现协议收口至 PermissionRefreshProtocol（常量）与
 * PermissionInvalidationService（唯一发布点），本测试防止回潮。
 */
class PermissionInvalidationGuardrailTest {

    /** 允许直接触碰频道/消息拼接的唯一发布端与契约类。 */
    private static final List<String> ALLOWED = List.of(
        "PermissionInvalidationService.java",
        "PermissionCacheRefreshListener.java",
        "PermissionRefreshProtocol.java");

    private static final Pattern CHANNEL_LITERAL =
        Pattern.compile("\"auth:refresh:channel\"");

    private static final Pattern RAW_USER_MESSAGE =
        Pattern.compile("publish\\s*\\(\\s*[^,]+\\s*,\\s*\"user:\"");

    @Test
    void onlyTheInvalidationServiceMayPublishToTheRefreshChannel() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(root).exists();
        try (Stream<Path> paths = Files.walk(root)) {
            List<String> offenders = paths
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> ALLOWED.stream().noneMatch(a -> p.endsWith(a)))
                .filter(this::mentionsProtocolDirectly)
                .map(Path::toString)
                .toList();
            assertThat(offenders)
                .as("以下文件绕过 PermissionInvalidationService 直接操作失效频道，"
                    + "请改调 invalidateUser/invalidateUsers/invalidateRoles")
                .isEmpty();
        }
    }

    private boolean mentionsProtocolDirectly(Path file) {
        try {
            String src = Files.readString(file);
            return CHANNEL_LITERAL.matcher(src).find()
                || RAW_USER_MESSAGE.matcher(src).find()
                || src.contains("AUTH_REFRESH_CHANNEL");
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 " + file, e);
        }
    }
}
