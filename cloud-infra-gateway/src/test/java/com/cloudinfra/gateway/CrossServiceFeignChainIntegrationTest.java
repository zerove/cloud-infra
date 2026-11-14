package com.cloudinfra.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 跨服务 Feign 链路集成测试（进程隔离版）。
 *
 * <p>架构：三个真实 Spring Boot 上下文分属三个 JVM 进程——
 * <ul>
 *   <li>system-module：以 -exec.jar 独立进程启动，Flyway V1~V9 建表（含 V9 admin 角色绑定），
 *       PostgreSQL/Redis/RabbitMQ 指向共享 Testcontainers；</li>
 *   <li>cloud-auth：独立进程启动，Feign 经 Simple Discovery 直连 system 真实端口，
 *       登录链路 getUserCredentialByUsername 走真实 HTTP+HMAC 内部签名；</li>
 *   <li>gateway：本测试类所在 JVM 进程内启动（@SpringBootTest），路由转发经
 *       Simple Discovery 指向上述两个进程的端口。</li>
 * </ul>
 *
 * <p>为何进程隔离：gateway 的全包扫描（com.cloudinfra）一旦在类路径上引入
 * auth/system 模块，其配置类会被吸入网关上下文（securityFilterChain 被
 * SystemSecurityConfig 覆盖、db/migration 触发 H2 上跑 PG 迁移等），行为彻底失真。
 * 独立 JVM 是唯一不污染彼此的方式。
 *
 * <p>用例即 V9 回归保护：admin/admin123 经完整链路登录后必须拿到非空权限集
 * （V9 之前种子 admin 零权限，所有 @PreAuthorize 端点 403）。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        // 必须与下方 TEST_JWT_KEY 字面量保持一致（子进程同密钥），否则网关验签必败
        "auth.jwt.secret=test-jwt-secret-key-for-integration-testing-32-chars-minimum",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "logging.level.com.cloudinfra.gateway=DEBUG"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossServiceFeignChainIntegrationTest {

    /** 测试用 JWT 密钥（非生产凭据，仅本地 IT 容器链路使用） */
    private static final String TEST_JWT_KEY = "test-jwt-secret-key-for-integration-testing-32-chars-minimum";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 不对 4xx/5xx 抛异常的 RestTemplate：用例需要断言 401/403 状态码 */
    private static final RestTemplate HTTP = createHttp();

    private static RestTemplate createHttp() {
        RestTemplate rt = new RestTemplate();
        rt.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return rt;
    }

    /**
     * 子进程使用的内部调用签名凭据。
     *
     * <p>2026-09-30 (issue 30): {@code InternalCallCrypto} 已移除内置默认值。
     * 本测试此前硬编码的正是那个已删除的默认值 —— 继续写死等于把弱凭据留在仓库里,
     * 且与根 pom 注入的测试凭据不一致会导致签名不匹配。此处改为读取系统属性,
     * 由 surefire 的 {@code test.internal-call.secret} 注入; 保留字面量 fallback 仅供
     * 在 IDE 中不经 Maven 直接运行时使用。</p>
     */
    private static final String INTERNAL_CALL_VALUE = System.getProperty(
        "cloud-infra.internal-call.secret", "test-only-not-a-real-credential");

    /** 下游两个服务的端口（进程启动后回填） */
    static int systemPort;
    static int authPort;

    /**
     * 子进程固定端口。
     *
     * <p>见 {@link #gatewayProperties} 的说明: 用固定端口是为了消除
     * "SimpleDiscoveryProperties 绑定早于 @BeforeAll 回填端口" 这个时序缺陷。</p>
     */
    private static final int SYSTEM_PORT = 18081;
    private static final int AUTH_PORT = 18082;

    /** 各子进程日志文件，awaitPort 轮询用 */
    private static final Map<String, Path> procLogs = new HashMap<>();

    /** 存活的子进程引用，测试结束后清理 */
    private static final List<Process> liveProcesses = new ArrayList<>();

    static {
        // JVM 退出兜底：即使断言失败或超时中断也不遗留孤儿服务进程
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process proc : liveProcesses) {
                proc.destroy();
            }
        }, "cross-service-it-cleanup"));
    }

    // ==================== 基础设施容器 ====================

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_DB", "cloud_infra")
            .withEnv("POSTGRES_USER", "postgres")
            .withEnv("POSTGRES_PASSWORD", "postgres")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                    .withStartupTimeout(Duration.ofSeconds(90)));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(180)));

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        // 本上下文无 Flyway/数据源需求（system 以外部进程运行）。
        // 路由目标：Simple Discovery 实例指向真实进程端口。
        //
        // **为什么用固定端口而不是 Supplier**（2026-10-01 定位到的 flaky 根因）:
        // `SimpleDiscoveryProperties` 是 @ConfigurationProperties, 它在**bean 初始化那一刻**
        // 一次性绑定全部属性。此时机早于测试类的 @BeforeAll(startServices) ——
        // JUnit 5 里 Extension 的 BeforeAllCallback(Spring 上下文加载) **先于** @BeforeAll 方法。
        // 所以 authPort/systemPort 在绑定时通常还是 0, 网关转发到 127.0.0.1:0 →
        // ResourceAccessException → SecurityFilter 兜底成 500。
        // 写 Supplier 并不能解决: 它只在“属性被读取那一刻”求值, 而绑定只发生一次。
        //
        // 固定端口让值在编译期即为常量, 无论何时绑定都正确, 从根上消除时序依赖。
        // 端口选用高位且成对, 与常见服务(8080/8083/9090 等)不冲突。
        registry.add("spring.cloud.discovery.client.simple.instances.system-module[0].uri",
                () -> "http://127.0.0.1:" + SYSTEM_PORT);
        registry.add("spring.cloud.discovery.client.simple.instances.cloud-auth[0].uri",
                () -> "http://127.0.0.1:" + AUTH_PORT);
    }

    // ==================== 进程编排 ====================

    private static Path workDir;

    @BeforeAll
    static void startServices() throws Exception {
        workDir = Files.createTempDirectory("cross-service-it");

        String pgUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/cloud_infra";
        String redisHost = REDIS.getHost();
        int redisPort = REDIS.getFirstMappedPort();
        String mqHost = RABBITMQ.getHost();
        int mqPort = RABBITMQ.getAmqpPort();

        // ---- 1. system-module：Flyway V1~V9 建表+种子 ----
        List<String> sysArgs = commonArgs(pgUrl, redisHost, redisPort, mqHost, mqPort);
        sysArgs.addAll(List.of(
                "--spring.profiles.active=dev",          // dev profile 使 logback appender 可见
                // 固定端口而非 0(随机): 网关的 SimpleDiscoveryProperties 在 Spring 上下文
                // 初始化时一次性绑定, 早于本 @BeforeAll 回填端口。随机端口会导致
                // 绑定时拿到 0, 网关转发到 127.0.0.1:0 而失败。见 gatewayProperties 注释。
                "--server.port=" + SYSTEM_PORT,
                // 无 ES 容器：指向死地址，连接失败仅影响搜索索引同步，不阻塞启动
                // 内部调用密钥由 startProcess 以 -D 注入, 不用 --key=value:
                // 那个形式对 InternalCallCrypto.resolveSecret() 不可见 (见 startProcess 内说明)
                "--spring.elasticsearch.uris=http://127.0.0.1:1"));
        Process sysProc = startProcess("system",
                resolveExecJar("cloud-infra-modules/system-module/target/system-module-1.0.0-SNAPSHOT-exec.jar"),
                sysArgs);
        liveProcesses.add(sysProc);
        systemPort = awaitPort(sysProc, "system");

        // ---- 2. cloud-auth：Feign 经 Simple Discovery 直连 system 真实端口 ----
        List<String> authArgs = commonArgs(pgUrl, redisHost, redisPort, mqHost, mqPort);
        authArgs.addAll(List.of(
                "--spring.profiles.active=dev",
                "--server.port=" + AUTH_PORT,
                "--management.server.port=0",             // auth 主配置固定 9301，会与网关冲突
                // Simple Discovery 实例指向 system 真实端口（服务名须与 lb://system-module 一致）
                "--spring.cloud.discovery.client.simple.instances.system-module[0].uri=http://127.0.0.1:" + SYSTEM_PORT,
                "--feign.client.config.default.connectTimeout=3000",
                "--feign.client.config.default.readTimeout=5000"));
        Process authProc = startProcess("auth",
                resolveExecJar("cloud-infra-auth/target/cloud-infra-auth-1.0.0-SNAPSHOT-exec.jar"),
                authArgs);
        liveProcesses.add(authProc);
        authPort = awaitPort(authProc, "auth");
    }

    /** 子进程公共参数：数据源/Redis/MQ 指向共享容器，JWT 密钥与网关侧一致 */
    private static List<String> commonArgs(String pgUrl, String redisHost, int redisPort,
                                           String mqHost, int mqPort) {
        return new ArrayList<>(List.of(
                "--spring.datasource.url=" + pgUrl,
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=postgres",
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.data.redis.host=" + redisHost,
                "--spring.data.redis.port=" + redisPort,
                "--spring.rabbitmq.host=" + mqHost,
                "--spring.rabbitmq.port=" + mqPort,
                "--spring.rabbitmq.username=" + RABBITMQ.getAdminUsername(),
                "--spring.rabbitmq.password=" + RABBITMQ.getAdminPassword(),
                // JWT 密钥必须与 @SpringBootTest 网关侧一致，否则网关验签必败
                "--auth.jwt.secret=" + TEST_JWT_KEY,
                // Nacos 配置导入与发现均关闭，快速失败
                "--spring.cloud.nacos.config.enabled=false",
                "--spring.cloud.nacos.config.import-check.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false",
                // **必须禁用 gRPC**，否则 system/auth 子进程会各自去绑 **固定端口 9090**
                // (GrpcServerAutoConfiguration 是 matchIfMissing=true, 默认启用且端口写死 9090)。
                // CI 上该端口常被占用/残留进程持有 → BindException: Address already in use
                // → 子进程启动即失败 → 登录时连不上 system → "用户名或密码错误"。
                // 2026-10-01 由 probeSystemCredentialEndpoint 直探确认:
                // 直连 system 内部端点同样是 ResourceAccessException(连不上),
                // 从而排除了 Feign/签名/数据三类原因, 指向"子进程根本没起来"。
                // 本用例验证的是 HTTP 链路, 不需要 gRPC。
                "--cloudinfra.grpc.enabled=false"));
    }

    /** 启动子进程：stdout/stderr 合并追加写入日志文件（绕开 springProfile 日志黑洞） */
    private static Process startProcess(String name, String jar, List<String> appArgs) throws IOException {
        Path logFile = workDir.resolve(name + ".log");
        procLogs.put(name, logFile);
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        // **必须限制堆大小**。
        //
        // 本测试同时持有: 3 个 Testcontainers (PG + Redis + RabbitMQ) + 测试 JVM 本身
        // + 下面 2 个子 JVM。若不限制, 子 JVM 默认堆 = 物理内存的 1/4, 在
        // GitHub ubuntu-latest (2 核 / 7 GB) 上两者合计就会把 runner 撑爆 ——
        // 2026-10-01 实测到 JVM 直接崩掉:
        //   There is insufficient memory for the Java Runtime Environment to continue.
        //   Native memory allocation (mmap) failed
        // 在 19 GB 的开发机上同样会崩, 说明这是**峰值需求**问题而非机器配置问题。
        //
        // 附带收益: 不限堆时子进程启动要 86 秒(日志实测, 大量时间在反复 GC);
        // 限堆后启动显著加快, 也让 awaitPort 的 300s 预算不再处于边缘。
        cmd.add("-Xms64m");
        cmd.add("-Xmx512m");
        cmd.add("-XX:MaxMetaspaceSize=192m");
        // 内部调用密钥必须以 JVM 系统属性(-D)或环境变量注入, 不能用 --key=value:
        // InternalCallCrypto.resolveSecret() 只读 System.getenv("INTERNAL_CALL_SECRET")
        // 与 System.getProperty("cloud-infra.internal-call.secret"), 它刻意绕开 Spring
        // Environment (规避 jasypt 4.0.4 + Nacos 的 CachingResolver 时序冲突)。
        // 而 Spring Boot 3+ 不再把命令行参数写回 System property, 只放进自己的 Environment,
        // 因此 `--cloud-infra.internal-call.secret=...` 对 resolveSecret() 完全不可见 →
        // 抛 "内部调用签名凭据缺失" → 子进程启动即失败。
        // 2026-10-01: 本测试此前用命令行参数传密钥, 基线上稳定失败
        // (Caused by: BeanCreationException: internalCallSecretProvider init failed)。
        cmd.add("-Dcloud-infra.internal-call.secret=" + INTERNAL_CALL_VALUE);
        cmd.add("-jar");
        cmd.add(jar);
        cmd.addAll(appArgs);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("NACOS_ADDR", "localhost:1"); // 双保险：env 与 -- 参数同禁 nacos 默认值
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        return pb.start();
    }

    /**
     * 轮询子进程日志文件直到出现 "Tomcat started on port"。
     * 相比读 stdout 流：不受应用日志配置影响；进程崩溃时能带出完整尾部日志定位根因。
     */
    private static int awaitPort(Process proc, String name) throws Exception {
        Path logFile = procLogs.get(name);
        // 300 秒不够: 2026-10-02 实测 system 启动耗时 **130 秒**、auth **91 秒**（CI runner 上,
        // 叠加容器冷启动与机器负载还会更慢）。两个子进程**串行**启动, 叠加已超 220 秒,
        // 逼近原 300 秒上限 —— 机器稍慢即超时, 是 flaky 的另一个来源。
        // 现放宽到 600 秒, 让就绪判据(而非超时)成为唯一门槛。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(600);
        // 提到循环外: 超时消息里要报告"已见到端口但未见就绪标志"还是"两者都没见到",
        // 这两种情况的排查方向完全不同。
        int port = -1;
        boolean started = false;
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive()) {
                throw new IllegalStateException(name + " 进程异常退出(exit=" + proc.exitValue()
                        + ")，日志尾部:\n" + tail(logFile));
            }
            // **两阶段就绪**（2026-10-02 修正）:
            // 先从 "Tomcat started on port" 取出端口号, **再等 "Started XxxApplication"**
            // 才算就绪。原先只看到端口行就返回, 而 Spring Boot 的
            // `Tomcat started` 早于 `Started XxxApplication` 数秒 —— 实测 auth 这段间隔
            // 约 5 秒, 期间应用尚未完成初始化。请求落在窗口内会拿到
            // 500 Internal Server Error（表现为登录偶发失败, 极难归因）。
            //
            // 直接从全部日志行里取端口号 + 单独判断就绪标志, 避免两次扫描。
            for (String line : readAllLinesSafe(logFile)) {
                if (line.contains("Tomcat started on port")) {
                    int from = line.indexOf("port ") + 5;
                    String rest = line.substring(from).replaceAll("[()\\[\\]]", " ");
                    // 同一进程会打印两次: 业务端口与管理端口(server.port / management.server.port)。
                    // 业务端口先打印, 故只在尚未取到时赋值, 避免被管理端口覆盖。
                    if (port < 0) {
                        port = Integer.parseInt(rest.trim().split("\\s+")[0]);
                    }
                }
                // 日志里的类名是首字母大写的 <Name>Application, 而传入的 name 是小写
                // ("system" / "auth"), 故需转换, 否则永远等不到而超时。
                if (line.contains("Started "
                        + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                        + "Application in ")) {
                    started = true;
                }
            }
            if (port > 0 && started) {
                return port;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(name
                + " 启动超时(600s)，端口=" + port + " 应用就绪标志=" + started
                + "（需同时看到 Tomcat 端口行与 Started "
                + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                + "Application），日志尾部:\n"
                + tail(logFile));
    }

    private static List<String> readAllLinesSafe(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 测试结束后停掉两个子进程（正常路径）；异常路径由 shutdown hook 兜底 */
    @AfterAll
    static void stopServices() {
        for (Process proc : liveProcesses) {
            if (proc.isAlive()) {
                proc.destroy();
            }
        }
    }

    private static String tail(Path file) {
        List<String> lines = readAllLinesSafe(file);
        int from = Math.max(0, lines.size() - 40);
        return String.join("\n", lines.subList(from, lines.size()));
    }

private static String resolveExecJar(String relative) throws IOException {
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent() == null
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(System.getProperty("user.dir"));
        Path p = repoRoot.resolve(relative);
        if (!Files.exists(p)) {
            // surefire 工作目录是模块根，尝试从仓库根解析
            p = Path.of("..").resolve(relative).normalize();
        }
        assertThat(p.toAbsolutePath()).as("exec jar 应存在（先 mvn package）: " + p).matches(Files::exists);
        return p.toAbsolutePath().toString();
    }

    // ==================== 链路用例 ====================

    @Autowired
    private ServletWebServerApplicationContext gatewayContext;

    private String gw(String path) {
        return "http://127.0.0.1:" + gatewayContext.getWebServer().getPort() + path;
    }

    @Test
    @Order(1)
    void step1_unauthenticatedRequestToDownstreamViaGatewayIsRejected() {
        // Given: 网关已启动，下游 system/auth 为真实进程
        // When: 未携带 token 访问受保护业务路径
        ResponseEntity<String> resp = exchange(gw("/system/user/list"), HttpMethod.GET, null, null);
        // Then: SecurityFilter 缺 token 直接 401
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void step2_fullChain_loginThroughGateway_issueToken_accessProtectedEndpoint() throws Exception {
        // Given: 完整链路登录（验证码→Redis→BCrypt→JWT 签发→Redis 会话）
        String token = obtainToken();
        assertThat(token).isNotBlank();

        // When: 携带 token 访问下游受保护端点
        //   网关校验 JWT+会话 → 注入身份头 → system HeaderAuthenticationFilter
        //   → RemotePermissionProvider(Feign[HMAC] 回拉权限) → @PreAuthorize
        // 注: 原先用 /system/user/detail/1, 该端点已迁至 /internal/system/user/detail/{userId}
        //     (见 step4), 改用同样经 @PreAuthorize + RemotePermissionProvider 的用户列表端点。
        ResponseEntity<String> resp =
                exchange(gw("/system/user/list?pageNum=1&pageSize=1"), HttpMethod.GET, token, null);

        // Then: 200 + admin 身份可见（回归保护核心断言：权限回拉链路通）
        assertThat(resp.getStatusCode().value())
                .as("携带合法 token 应 200: %s", resp.getBody()).isEqualTo(200);
        JsonNode body = JSON.readTree(resp.getBody());
        assertThat(body.path("data").path("records").isArray())
                .as("用户列表应返回分页结构: %s", resp.getBody()).isTrue();
    }

    @Test
    @Order(4)
    void step4_internalContractEndpointsAreNotRoutableThroughGateway() throws Exception {
        // Given: 持有合法 token 的普通用户（admin 也代表"已认证但非服务间调用"）
        String token = obtainToken();
        assertThat(token).isNotBlank();

        // When/Then: 内部服务间契约端点没有网关路由，携带合法 token 也拿不到
        for (String path : List.of(
                "/system/user/detail/1",
                "/system/user/credential/username/admin",
                "/internal/system/user/credential/username/admin",
                "/internal/system/user/detail/1")) {
            ResponseEntity<String> resp = exchange(gw(path), HttpMethod.GET, token, null);
            assertThat(resp.getStatusCode().value())
                    .as("内部契约端点 %s 不得经网关可达（2026-09-30 P0：可读他人 BCrypt 密码哈希），实际 %s",
                            path, resp.getBody())
                    .isNotEqualTo(200);
        }
    }

    @Test
    @Order(5)
    void step5_newlyProtectedReadEndpointsAreReachableByAdmin() throws Exception {
        // Given: 2026-09-30 审计发现三个端点"认证即可读"，已补 @PreAuthorize + V16 权限种子。
        // 本步验证新权限链路真的通：V16 把权限授予 role_id=1(admin)，
        // 且 @ss.hasPermi 经 RemotePermissionProvider(Feign[HMAC]) 能取回该权限。
        // 任何一环断掉（迁移未执行 / 权限串写错 / 缓存未刷新）这里都会 403。
        String token = obtainToken();
        assertThat(token).isNotBlank();

        for (String path : List.of(
                "/system/permission/list?pageNum=1&pageSize=1",
                "/system/rbac/user/1/permissions",
                "/system/resource-mapping/effective-nodes?resourceId=r1&resourceType=asset")) {
            ResponseEntity<String> resp = exchange(gw(path), HttpMethod.GET, token, null);
            assertThat(resp.getStatusCode().value())
                    .as("admin 应能访问新加固端点 %s（V16 权限 + @PreAuthorize 链路），实际: %s",
                            path, resp.getBody())
                    .isEqualTo(200);
        }
    }

    @Test
    @Order(3)
    void step3_logoutInvalidatesSession_gatewayRejectsSubsequentRequests() throws Exception {
        String token = obtainToken();
        ResponseEntity<String> logout = exchange(gw("/auth/logout"), HttpMethod.POST, token, null);
        // 200=正常登出；401=token 校验失败（等价于已登出）。500 属服务端缺陷
        assertThat(logout.getStatusCode().value())
                .as("登出不应 5xx: %s", logout.getBody()).isIn(200, 401);

        ResponseEntity<String> after = exchange(gw("/system/user/list"), HttpMethod.GET, token, null);
        assertThat(after.getStatusCode().value())
                .as("登出后原 token 必须失效（会话删除+黑名单）").isIn(401, 403);
    }

    // ==================== HTTP 辅助 ====================

    private static ResponseEntity<String> exchange(String url, HttpMethod method,
                                                   String bearerToken, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
        return HTTP.exchange(url, method, entity, String.class);
    }

    /**
     * 完整登录流程：验证码（白名单直通 auth 进程）→ Redis 取答案 → 登录
     * （auth → Feign[HMAC] 拉 credential → BCrypt 校验 → JWT 签发）。
     * Redis 取码经 gateway 上下文的 redisTemplate（与 auth 进程共用同一 Redis 容器）。
     */
    private String obtainToken() throws Exception {
        ResponseEntity<String> captchaResp = exchange(gw("/auth/captchaImage"), HttpMethod.GET, null, null);
        assertThat(captchaResp.getStatusCode().value()).isEqualTo(200);
        JsonNode captcha = JSON.readTree(captchaResp.getBody());
        assertThat(captcha.path("success").asBoolean())
                .as("验证码响应: %s", captchaResp.getBody()).isTrue();
        String uuid = captcha.path("data").path("uuid").asText();

        Supplier<Object> codeLookup = () ->
                gatewayContext.getBeanFactory().getBean("redisTemplate", org.springframework.data.redis.core.RedisTemplate.class)
                        .opsForValue().get("captcha_codes:" + uuid);
        await().atMost(10, TimeUnit.SECONDS).until(() -> codeLookup.get() != null);
        String code = String.valueOf(codeLookup.get());

        Map<String, Object> loginBody = new HashMap<>();
        loginBody.put("username", "admin");
        loginBody.put("password", "admin123");
        loginBody.put("captcha", code);
        loginBody.put("uuid", uuid);
        ResponseEntity<String> resp = exchange(gw("/auth/login"), HttpMethod.POST, null,
                JSON.writeValueAsString(loginBody));
        JsonNode login = JSON.readTree(resp.getBody());
        assertThat(login.path("success").asBoolean())
                .as("登录失败: %s%n"
                        + "--- system 内部凭据端点直探: %s%n"
                        + "--- system 子进程日志尾部 ---%n%s%n"
                        + "--- auth 子进程日志尾部 ---%n%s",
                        resp.getBody(),
                        probeSystemCredentialEndpoint(),
                        tail(procLogs.get("system")),
                        tail(procLogs.get("auth")))
                .isTrue();
        return login.path("data").path("token").asText();
    }

    /**
     * 登录失败时**直连** system 的内部凭据端点，绕过网关与 Feign。
     *
     * <p>用途: 把“登录失败”这一个症状拆成可区分的几类。该错误码在 auth 侧被多个原因共用 ——
     * {@code UserDetailsServiceImpl} 查不到用户时抛 {@code UsernameNotFoundException}，
     * 会被 {@code DaoAuthenticationProvider} 转成 {@code BadCredentialsException}，
     * 与“密码真的错”落到同一个码。仅看响应体无法判断属于哪一类。</p>
     *
     * <p>本探测用与 Feign 相同的内部签名直连，能区分:</p>
     * <ul>
     *   <li>连接异常 → system 子进程不可达 / 端口不对</li>
     *   <li>HTTP 401/403 → 内部签名校验没过</li>
     *   <li>HTTP 200 且 body 含该用户 → 用户查询正常, 问题在密码比对或会话链路</li>
     *   <li>HTTP 200 但查无此人 → 种子数据未加载</li>
     * </ul>
     */
    private static String probeSystemCredentialEndpoint() {
        try {
            long ts = System.currentTimeMillis();
            String nonce = "probe-" + ts;
            HttpHeaders headers = new HttpHeaders();
            headers.set(InternalCallCrypto.HEADER_SIGN,
                    InternalCallCrypto.sign(INTERNAL_CALL_VALUE, ts, nonce));
            headers.set(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts));
            headers.set(InternalCallCrypto.HEADER_NONCE, nonce);

            String url = "http://127.0.0.1:" + SYSTEM_PORT
                    + "/internal/system/user/credential/username/admin";
            ResponseEntity<String> r = HTTP.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return "HTTP " + r.getStatusCode().value() + " body=" + r.getBody();
        } catch (Exception e) {
            return "探测异常 " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
