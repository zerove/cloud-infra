import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import java.io.IOException;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;

/**
 * 用**生产同路径**的 Spring Data Elasticsearch 客户端探测一个 ES 端点。
 *
 * <p>为什么必须有它: {@code curl HEAD /index} 这类普通 HTTP 探针**测不出**本项目踩过的
 * 那个坑。9.x 客户端默认发送
 * <pre>Accept/Content-Type: application/vnd.elasticsearch+json; compatible-with=9</pre>
 * ES 8.x 服务端对该头返回 400 {@code media_type_header_exception}。因为 curl 不发这两个头,
 * 同一台 ES 上 curl 得到正常的 404, Java 客户端却 400 —— 两侧结论相反,
 * 只看 curl 会得出"ES 没问题"的假绿灯。
 *
 * <p>本地实测(同一探针, 仅换服务端, 2026-10-03):
 * <pre>
 *   ES 9.0.3  -&gt; IndicesTemplate.exists = false   RESULT = OK
 *   ES 8.18.0 -&gt; status: 400, [es/indices.exists]  RESULT = FAILED
 *                "Expecting a response body, but none was sent"
 * </pre>
 * 后者与 CI run 37079433594 里 search-service 的启动失败逐字相同。
 *
 * <p>本探针走 {@code ElasticsearchConfiguration -&gt; elasticsearchRest5Client -&gt;
 * elasticsearchTransport -&gt; elasticsearchClient}, 与 search-service 装配的是同一组类,
 * 因此 {@code indices().exists()} 就是启动时
 * {@code createIndexAndMappingIfNeeded -&gt; IndicesTemplate.exists} 真正执行的那次调用。
 *
 * <p>用法: {@code java -cp &lt;deps&gt; EsClientProbe &lt;host:port&gt; [index]}
 * 注意 {@code ClientConfiguration.builder().connectedTo(...)} 收的是 {@code host:port},
 * <b>不带</b> {@code http://} —— 带了会被拼成 {@code http://http//host:port},
 * 报 {@code Name or service not known}, 看起来像"连不上 ES", 实则是参数用错。
 *
 * <p>结论以 {@code RESULT = OK|FAILED} 打在标准输出上, 不靠退出码传递 ——
 * 调用方(verify-e2e.sh)按这一行判定, 因此无需终止 JVM。
 */
public final class EsClientProbe {

    private static final String TAG = "[client-probe] ";

    private EsClientProbe() {
    }

    public static void main(String[] args) {
        // 刻意不带 scheme, 见类注释。
        String hostPort = args.length > 0 ? args[0] : "127.0.0.1:9200";
        String index = args.length > 1 ? args[1] : "sys_oper_log";

        ElasticsearchConfiguration cfg = new ElasticsearchConfiguration() {
            @Override
            public ClientConfiguration clientConfiguration() {
                return ClientConfiguration.builder()
                        .connectedTo(hostPort)
                        .build();
            }
        };
        var low = cfg.elasticsearchRest5Client(cfg.clientConfiguration());
        ElasticsearchTransport transport = cfg.elasticsearchTransport(low, new JacksonJsonpMapper());
        ElasticsearchClient client = cfg.elasticsearchClient(transport);

        boolean healthy = true;

        // 客户端发 compatible-with 头后服务端才暴露版本不匹配, 所以 info() 也是有效探针。
        //
        // ⚠️ catch 的两个类型是**实测收敛**出来的, 不是照文档抄的, 且都不可省略:
        //
        //   ElasticsearchException —— 版本不匹配时 info() 抛的是它
        //     ("[es/info] failed: [media_type_header_exception] ..."), 它是 **RuntimeException**,
        //     **不是** IOException。第一版只 catch IOException 时, 这条最关键的路径直接穿透
        //     main 抛出, 探针既没打 RESULT 也没关 transport, JVM 因 HttpClient 的非守护
        //     连接池线程存活而挂住 —— 调用方只能看到"超时", 拿不到任何结论。
        //     即: 收窄捕获看起来更"干净", 实际会让最该抓的失败彻底丢失。
        //
        //   IOException —— 连接被拒/超时/读取失败(ConnectException、
        //     HttpTimeoutException、TransportException 均继承它; javac 已确认, 多 catch 会报
        //     "Alternatives ... cannot be related by subclassing")。
        //
        // 不用 catch Throwable: 那会把 OutOfMemoryError 一并吞掉, 在内存本就紧张的场景下
        // 把 JVM 级故障伪装成"ES 有问题"。
        String serverVersion;
        try {
            serverVersion = client.info().version().number();
        } catch (ElasticsearchException | IOException e) {
            serverVersion = "<失败: " + e.getClass().getSimpleName() + ": " + e.getMessage() + ">";
            healthy = false;
        }
        out("服务端 version = " + serverVersion);

        try {
            boolean exists = client.indices()
                    .exists(ExistsRequest.of(e -> e.index(index)))
                    .value();
            // 索引不存在是**正常**结果(首次启动会由 Spring Data 创建), 只有异常才是问题。
            out("IndicesTemplate.exists(" + index + ") = " + exists);
        } catch (ElasticsearchException | IOException e) {
            healthy = false;
            out("IndicesTemplate.exists(" + index + ") EXCEPTION: " + e.getClass().getName());
            out("MESSAGE : " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                out("   caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
        }

        out("RESULT = " + (healthy ? "OK" : "FAILED"));

        // ⚠️ 必须显式关掉 transport, 否则 main 返回后 JVM **不退出**:
        // 底层 Apache HttpClient 的连接池维护线程是非守护线程, 会一直存活。
        // 实测症状很隐蔽 —— 探针已经把 RESULT 打到 stdout 了, 但进程不结束,
        // 调用方的 `timeout 120` 随后把它杀掉并读到 rc=124, 于是"OK/FAILED"
        // 全被误判成"探针超时/未完成"。结论打印正确、判定却是错的,
        // 这是守护里最危险的一种形态: 有输出, 却判红。
        try {
            transport.close();
        } catch (IOException e) {
            // 关闭失败不影响已得出的结论, 但要说出来, 不能静默吞掉。
            out("WARN: 关闭 transport 失败: " + e.getMessage());
        }
    }

    // 前缀只在这里加一次: 调用方(verify-e2e.sh)会整段加行首缩进,
    // 若探针自己再加, 日志里会出现 "[client-probe] [client-probe] ..." 的双前缀。
    private static void out(String message) {
        System.out.println(TAG + message);
    }
}
