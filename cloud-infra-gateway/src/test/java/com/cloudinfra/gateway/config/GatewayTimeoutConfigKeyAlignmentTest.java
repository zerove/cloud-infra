package com.cloudinfra.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@code deploy/config/gateway.yaml} 里的 {@code gateway.http-timeout.*} 键
 * 与 {@link GatewayHttpTimeoutProperties} 的 Java Bean 属性名逐字对齐。
 *
 * <p><b>为什么需要这个测试（来自真实事故）</b>：本类的字段名与访问器名一旦不一致，
 * 配置文件里的键会<b>不报任何错</b>地失效，服务退回代码默认值。
 * 本项目已有两个同类真实案例：
 * <ul>
 *   <li>开发本类时，访问器写成 {@code getReadTimeout} 而配置键写的是 {@code read} ——
 *       没有 setter {@code setRead}，配置静默退回默认 60s，
 *       当时 {@link GatewayTimeoutPropertiesTest} 真的报红才发现。</li>
 *   <li>file-service 的 {@code file.upload.dir} 无人读取，服务一直用代码默认值，
 *       见 {@code FileServiceConfigKeyAlignmentTest}。</li>
 * </ul>
 * 两者形态相同：配置"看起来配好了"，服务也正常启动，只有在配置值与默认值不同时才有后果。
 *
 * <p><b>本测试的判据来源</b>：键名从<b>类源码里的 getter/setter 正则提取</b>，
 * 而不是硬编码在测试中。理由：硬编码会出现"代码改了属性名、测试仍按旧名校验"的假绿。
 */
class GatewayTimeoutConfigKeyAlignmentTest {

    private static final String CONFIG_FILE = "deploy/config/gateway.yaml";

    @Test
    @DisplayName("deploy/config/gateway.yaml 的 http-timeout 键必须与 Java Bean 属性名逐字对齐")
    void configKeysMatchBeanPropertyNames() throws Exception {
        Path configPath = locateConfigFile();
        Map<String, Object> timeoutSection = readTimeoutSection(configPath);

        List<String> declaredKeys = new ArrayList<>();
        if (timeoutSection instanceof Map<?, ?> map) {
            map.keySet().forEach(k -> declaredKeys.add(String.valueOf(k)));
        }

        assertThat(declaredKeys)
                .as("deploy/config/gateway.yaml 应声明 gateway.http-timeout.*，实际读到 %s", declaredKeys)
                .isNotEmpty();

        List<String> beanProperties = extractBeanPropertyNames();
        assertThat(beanProperties)
                .as("应能从 GatewayHttpTimeoutProperties 提取到属性名")
                .isNotEmpty();

        assertThat(declaredKeys)
                .as("配置键 %s 必须在 GatewayHttpTimeoutProperties 的属性名 %s 中逐字存在；"
                        + "不存在的键不会报错，只会静默退回默认值", declaredKeys, beanProperties)
                .allMatch(k -> beanProperties.contains(toBeanName(k)));

        assertThat(beanProperties)
                .as("属性 %s 若不在配置中声明，则运维改 Nacos 不会生效（只有代码默认值可用）", beanProperties)
                .allMatch(p -> declaredKeys.stream().anyMatch(k -> toBeanName(k).equals(p)));
    }

    /**
     * 把 YAML 键转成 Spring 实际绑定的属性名。
     *
     * <p>Spring Boot 的宽松绑定（relaxed binding）会把 {@code connection-request}
     * 归一化为 {@code connectionRequest}，两者都能绑定上。
     *
     * <p><b>关键：判据必须区分"能被宽松绑定"与"完全认不出"</b>。
     * 若不归一化，{@code connection-request} 会被误报为不存在 —— 那是假红。
     * 但也不能因此放宽到"大概行"：本测试最初就是因为没做归一化而假红，
     * 若反过来无脑接受任意字符串，又会退回"配置看着配好了其实没生效"。
     */

    @Test
    @DisplayName("read 键必须存在: 它承载大文件上传的读超时，缺失即退回 10s 级别的余量")
    void readKeyIsPresent() throws Exception {
        Map<String, Object> timeoutSection = readTimeoutSection(locateConfigFile());
        assertThat(timeoutSection)
                .as("gateway.http-timeout 必须含 read 键")
                .containsKey("read");
    }

    @Test
    @DisplayName("配置里的 read 值必须能被 Spring 解析为 Duration")
    void readValueParsesAsDuration() throws Exception {
        Map<String, Object> timeoutSection = readTimeoutSection(locateConfigFile());
        Object read = timeoutSection.get("read");
        assertThat(read).isNotNull();

        // 用与运行时相同的解析路径验证，避免"写了个 Spring 不认的格式"。
        // detectAndParse 返回 java.time.Duration（不是 DurationStyle）。
        java.time.Duration parsed =
                org.springframework.boot.convert.DurationStyle.detectAndParse(String.valueOf(read));
        assertThat(parsed)
                .as("read=%s 应解析为正的 Duration", read)
                .isNotNull()
                .isPositive();
    }

    /**
     * 从源码提取 {@code getXxx/setXxx} 对应的 Java Bean 属性名。
     * <p>注意：必须保留 {@code Timeout} 等后缀原样去掉首字母小写的结果，
     * 即 {@code getReadTimeout} -> {@code readTimeout}。这正是本测试要抓的错配。
     */
    private static List<String> extractBeanPropertyNames() throws Exception {
        Path source = locateSourceFile();
        String text = Files.readString(source);

        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\\b(?:get|set)([A-Z][A-Za-z0-9]*)\\s*\\(").matcher(text);
        while (m.find()) {
            String raw = m.group(1);
            if ("Class".equals(raw)) {
                continue;
            }
            names.add(Character.toLowerCase(raw.charAt(0)) + raw.substring(1));
        }
        return names;
    }

    /**
     * 把 YAML 键转成 Spring 实际绑定的属性名。
     *
     * <p>对应 Spring Boot 的 {@code ConfigurationPropertyName} 规则：
     * 分隔符（{@code -} / {@code _} / {@code .}）被移除，<b>且分隔符后一个字母大写</b>。
     * 因此 {@code connection-request} → {@code connectionRequest}，
     * 而非 {@code connectionrequest}。
     *
     * <p><b>关键：判据必须区分"能被宽松绑定"与"完全认不出"</b>。
     * 若不做归一化，{@code connection-request} 会被误报为不存在 —— 那是假红；
     * 首版就踩了这个坑。而归一化若只删分隔符不还原大写，同样会假红。
     */
    private static String toBeanName(String yamlKey) {
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < yamlKey.length(); i++) {
            char c = yamlKey.charAt(i);
            if (c == '-' || c == '_' || c == '.') {
                upperNext = true;
                continue;
            }
            if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else if (sb.length() == 0) {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readTimeoutSection(Path configPath) throws Exception {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(configPath)) {
            root = new Yaml().load(in);
        }
        assertThat(root).as("%s 应能解析为 YAML 映射", configPath).isInstanceOf(Map.class);

        Object gateway = ((Map<String, Object>) root).get("gateway");
        assertThat(gateway)
                .as("%s 应含顶层 gateway 键", configPath)
                .isInstanceOf(Map.class);

        Object httpTimeout = ((Map<String, Object>) gateway).get("http-timeout");
        assertThat(httpTimeout)
                .as("%s 的 gateway 下应含 http-timeout 键", configPath)
                .isInstanceOf(Map.class);

        return (Map<String, Object>) httpTimeout;
    }

    /** 反射确认属性可读写 —— 键能绑定的必要条件。 */
    @Test
    @DisplayName("配置涉及的每个属性都必须有 public setter, 否则 Spring 静默忽略该键")
    void everyPropertyHasPublicSetter() throws Exception {
        for (String name : extractBeanPropertyNames()) {
            String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Field field = findField(GatewayHttpTimeoutProperties.class, name);
            assertThat(field)
                    .as("属性 %s 应有对应字段", name)
                    .isNotNull();
            assertThat(GatewayHttpTimeoutProperties.class.getMethod(setter, field.getType()))
                    .as("属性 %s 缺 public setter %s —— Spring 会静默忽略该配置键", name, setter)
                    .isNotNull();
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            }
        }
        return null;
    }

    private static Path locateConfigFile() {
        return findUpwards(CONFIG_FILE);
    }

    private static Path locateSourceFile() {
        return findUpwards("cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/config/GatewayHttpTimeoutProperties.java");
    }

    /**
     * 从当前目录逐级向上找指定相对路径。
     *
     * <p>找不到就明确失败 —— “扫描器坏了”不等于“通过”，这与本项目记录过的假门禁教训同源。
     *
     * <p>用 {@code resolve} 逐层试，而不是列目录再匹配：列目录只能看到<b>直接子项</b>，
     * 匹配不到 {@code deploy/config/gateway.yaml} 这种嵌套路径
     * —— 首版就是这么写的，4 项全部报红才发现（不是被守护对象失败，是装置坏了）。
     */
    private static Path findUpwards(String relativePath) {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && cur != null; i++, cur = cur.getParent()) {
            Path candidate = cur.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError(
                "从 " + Path.of("").toAbsolutePath() + " 向上 8 层未找到 " + relativePath);
    }
}