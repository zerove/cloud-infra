import java.lang.reflect.Method;

/**
 * 以反射调用包私有的 ElasticsearchContainerProbe.readShardState()。
 *
 * <p>存在的意义: 该探针只读宿主 /proc 与 podman 输出, 没有任何外部依赖,
 * 因此可以在【真实容器环境】下直接跑一次, 拿到 L3 级证据 ——
 * 证明它在 ES 真的在跑时能取到读数, 而不是只返回"未取证"。
 *
 * <p>用法: java -cp cloud-infra-test-support/target/classes:. ProbeRun.java
 */
public class ProbeRun {

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName(
                "com.cloudinfra.test.support.diagnostics.ElasticsearchContainerProbe");
        Method m = c.getDeclaredMethod("readShardState");
        m.setAccessible(true);

        String out = (String) m.invoke(null);
        System.out.println("====== 探针输出开始 ======");
        System.out.println(out);
        System.out.println("====== 探针输出结束 ======");

        // 自检: 输出必须非空且已标注形态, 否则本 harness 自身失效
        if (out == null || out.isBlank()) {
            System.out.println("!!! 探针返回空 —— 本 harness 无效, 结果不可用");
            System.exit(2);
        }
        if (out.contains("未取证")) {
            System.out.println("(本次为未取证形态: " + out.lines().findFirst().orElse("") + ")");
        }
    }
}
