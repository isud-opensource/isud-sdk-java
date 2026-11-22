package cn.isud.sdk;

import cn.isud.sdk.SdkErrors.*;
import cn.isud.sdk.Models.*;
// 静态方法通过 SdkErrors 类限定调用

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;

/**
 * 优标宝开放 API Java 客户端 —— 手写体验层核心。
 * 实现 SDK-DESIGN.md 定义的全部体验：鉴权、信封解析、异常映射、Retry-After 退避、
 * 幂等键、扣点回读、分页、本地校验。零第三方依赖（java.net.http + 内置 Json）。
 */
public final class Client implements AutoCloseable {
    public static final String DEFAULT_BASE_URL = "https://app.isud.cn/api/v1/open";
    public static final String MCP_BASE_URL = "https://app.isud.cn/api/v1";

    /** 可注入的传输层，便于测试（默认用真实 HttpClient）。 */
    @FunctionalInterface
    public interface Transport {
        HttpResponse<String> send(HttpRequest req, Duration timeout) throws Exception;
    }

    private final String apiKey;
    private final String baseURL;
    private final String mcpBaseURL;
    private final Duration timeout;
    private final int maxRetries;
    private final HttpClient http;
    private final Transport transport;
    private final String userAgent;

    /** 最近一次调用的响应元数据（非线程安全，示例/调试用）。 */
    public volatile ResponseMeta lastMeta = new ResponseMeta();
    /** 每次重试前回调（异常、第几次、等待毫秒），可用于日志。 */
    public TriConsumer<SdkError, Integer, Long> onRetry = null;

    @FunctionalInterface public interface TriConsumer<A, B, C> { void accept(A a, B b, C c); }

    private Client(Builder b) {
        String key = b.apiKey != null ? b.apiKey : System.getenv("ISUD_API_KEY");
        if (key == null || key.isEmpty())
            throw new ValidationError("缺少 API 密钥：请传入 apiKey 或设置环境变量 ISUD_API_KEY。"
                    + "密钥在控制台创建（注册即送调用次数）。");
        this.apiKey = key;
        this.baseURL = trimSlash(b.baseURL != null ? b.baseURL : DEFAULT_BASE_URL);
        this.mcpBaseURL = trimSlash(b.mcpBaseURL != null ? b.mcpBaseURL : MCP_BASE_URL);
        this.timeout = b.timeout != null ? b.timeout : Duration.ofSeconds(30);
        this.maxRetries = b.maxRetries;
        this.userAgent = b.userAgent != null ? b.userAgent : "isud-sdk-java/1.0.1";
        this.http = b.transport == null ? HttpClient.newBuilder().connectTimeout(timeout).build() : null;
        this.transport = b.transport;
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String apiKey, baseURL, mcpBaseURL, userAgent;
        private Duration timeout;
        private int maxRetries = 3;
        private Transport transport;
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        public Builder baseURL(String v) { this.baseURL = v; return this; }
        public Builder mcpBaseURL(String v) { this.mcpBaseURL = v; return this; }
        public Builder timeout(Duration v) { this.timeout = v; return this; }
        public Builder maxRetries(int v) { this.maxRetries = v; return this; }
        public Builder transport(Transport v) { this.transport = v; return this; }
        public Builder userAgent(String v) { this.userAgent = v; return this; }
        public Client build() { return new Client(this); }
    }

    // ---------------- 内部请求管线 ----------------
    private static final class ReqOpts {
        String method = "GET";
        String path;
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, Object> body;
        boolean idempotent;
        String idempotencyKey;
        boolean mcp;
        boolean noEnvelope; // true = 裸响应（MCP）；零值 false = 业务信封
        Integer retries;
    }

    private Object doOnce(ReqOpts o) {
        String root = o.mcp ? mcpBaseURL : baseURL;
        StringBuilder url = new StringBuilder(root).append(o.path);
        if (!o.query.isEmpty()) {
            url.append('?');
            StringBuilder q = new StringBuilder();
            for (Map.Entry<String, String> e : o.query.entrySet()) {
                if (q.length() > 0) q.append('&');
                q.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            }
            url.append(q);
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("X-API-Key", apiKey)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .timeout(timeout);

        String bodyStr = null;
        if (o.body != null) {
            bodyStr = Json.stringify(o.body);
            rb.header("Content-Type", "application/json");
        }
        if (o.idempotent) {
            String key = o.idempotencyKey != null && !o.idempotencyKey.isEmpty()
                    ? o.idempotencyKey : uuidHex();
            if (key.length() < 1 || key.length() > 128) throw new ValidationError("幂等键长度必须在 1~128 字符之间。");
            rb.header("Idempotency-Key", key);
        }
        switch (o.method) {
            case "POST": rb.POST(HttpRequest.BodyPublishers.ofString(bodyStr == null ? "" : bodyStr)); break;
            case "PUT": rb.PUT(HttpRequest.BodyPublishers.ofString(bodyStr == null ? "" : bodyStr)); break;
            default: rb.GET();
        }
        HttpRequest req = rb.build();

        HttpResponse<String> resp;
        try {
            resp = transport != null ? transport.send(req, timeout)
                    : http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new NetworkError("网络请求被中断: " + ie.getMessage());
        } catch (Exception e) {
            throw new NetworkError("网络请求失败: " + e.getMessage());
        }

        int status = resp.statusCode();
        String raw = resp.body() == null ? "" : resp.body();
        ResponseMeta meta = buildMeta(resp, null);

        // 裸响应端点（MCP health / JSON-RPC）
        if (o.noEnvelope) {
            lastMeta = meta;
            if (status >= 400)
                throw new NetworkError("HTTP " + status + ": " + trunc(raw, 200), meta, status);
            return parseAny(raw);
        }

        // 业务信封
        Map<String, Object> env;
        try {
            Object parsed = Json.parse(raw);
            env = parsed instanceof Map ? Json.asObj(parsed) : new LinkedHashMap<>();
        } catch (Exception e) {
            env = new LinkedHashMap<>();
            env.put("code", "0001");
            env.put("msg", "HTTP " + status + " 返回了非 JSON 内容");
        }
        meta = buildMeta(resp, env);
        lastMeta = meta;
        SdkError err = SdkErrors.raiseForEnvelope(env, status, meta);
        if (err != null) {
            if (err.kind == Kind.RateLimitError && err.retryAfter == null) {
                Integer ra = Retry.parseRetryAfter(resp.headers().firstValue("Retry-After").orElse(""));
                if (ra != null) err.retryAfter = ra;
            }
            throw err;
        }
        return env.get("data");
    }

    private Object request(ReqOpts o) {
        int attempts = o.retries != null ? o.retries : maxRetries;
        boolean safe = "GET".equals(o.method) || o.idempotent;
        int attempt = 0;
        while (true) {
            try {
                return doOnce(o);
            } catch (SdkError e) {
                if (attempt >= attempts || !e.retryable || !safe) throw e;
                double waitSec = Retry.resolveWait(e, attempt);
                long waitMs = (long) (waitSec * 1000);
                if (onRetry != null) onRetry.accept(e, attempt + 1, waitMs);
                try { Thread.sleep(waitMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                attempt++;
            }
        }
    }

    private ResponseMeta buildMeta(HttpResponse<String> resp, Map<String, Object> env) {
        ResponseMeta m = new ResponseMeta();
        m.creditsCharged = intHeader(resp, "X-Credits-Charged");
        m.creditsRemaining = intHeader(resp, "X-Credits-Remaining");
        m.rateLimit = intHeader(resp, "X-RateLimit-Limit");
        m.rateRemaining = intHeader(resp, "X-RateLimit-Remaining");
        m.idempotentReplay = resp.headers().firstValue("Idempotency-Replayed").orElse("").equalsIgnoreCase("true");
        m.httpStatus = resp.statusCode();
        if (env != null) m.requestId = Json.str(env.get("requestId"));
        if (m.requestId == null || m.requestId.isEmpty())
            m.requestId = resp.headers().firstValue("X-Request-Id").orElse("");
        return m;
    }

    private static Integer intHeader(HttpResponse<String> resp, String name) {
        String v = resp.headers().firstValue(name).orElse("");
        if (v.isEmpty()) return null;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
    }

    private static Object parseAny(String raw) {
        try { return Json.parse(raw); } catch (Exception e) { return raw; }
    }

    private static String trunc(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String uuidHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void putIf(Map<String, Object> m, String k, Object v) {
        if (v == null) return;
        if (v instanceof String && ((String) v).isEmpty()) return;
        if (v instanceof Collection && ((Collection<?>) v).isEmpty()) return;
        m.put(k, v);
    }

    private static void checkSize(int size) {
        if (size < 1 || size > 100) throw new ValidationError("size 必须在 1~100 之间（收到 " + size + "）。");
    }
    private static void checkSearchInput(String keyword, String owner) {
        if ((keyword == null || keyword.isEmpty()) && (owner == null || owner.isEmpty()))
            throw new ValidationError("keyword 与 owner 至少提供一项。");
    }

    // ---------------- 9 个端点方法（与 operationId 对齐）----------------

    /** 商标检索与冲突判定（扣 10 点，自动注入幂等键）。 */
    public TrademarkSearchResult searchTrademarks(String jurisdictionCode, String keyword,
                                                  String owner, List<String> niceClasses,
                                                  List<String> status, int current, int size,
                                                  String idempotencyKey) {
        if (size == 0) size = 50;
        checkSize(size);
        checkSearchInput(keyword, owner);
        if (current == 0) current = 1;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jurisdictionCode", jurisdictionCode);
        body.put("current", current);
        body.put("size", size);
        putIf(body, "keyword", keyword);
        putIf(body, "owner", owner);
        putIf(body, "niceClasses", niceClasses);
        putIf(body, "status", status);
        ReqOpts o = new ReqOpts();
        o.method = "POST"; o.path = "/ip/trademark/search"; o.body = body;
        o.idempotent = true; o.idempotencyKey = idempotencyKey;
        Object data = request(o);
        return data instanceof Map ? TrademarkSearchResult.from(Json.asObj(data)) : null;
    }

    /** 商标检索（简化重载）。 */
    public TrademarkSearchResult searchTrademarks(String jurisdictionCode, String keyword) {
        return searchTrademarks(jurisdictionCode, keyword, null, null, null, 1, 50, null);
    }

    /** 外观设计检索（扣 10 点；CN 法域暂未开放）。 */
    public Object searchDesigns(String jurisdictionCode, String keyword, List<String> locarnoClasses,
                                String owner, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jurisdictionCode", jurisdictionCode);
        body.put("keyword", keyword);
        putIf(body, "locarnoClasses", locarnoClasses);
        putIf(body, "owner", owner);
        ReqOpts o = new ReqOpts();
        o.method = "POST"; o.path = "/ip/design/search"; o.body = body;
        o.idempotent = true; o.idempotencyKey = idempotencyKey;
        return request(o);
    }

    /** 分页查询商标档案。 */
    public Page listTrademarks(int current, int size, String keyword, String ownerName, String riskLevel) {
        if (size == 0) size = 10;
        checkSize(size);
        if (current == 0) current = 1;
        ReqOpts o = new ReqOpts();
        o.path = "/ip/trademark/list";
        o.query.put("current", String.valueOf(current));
        o.query.put("size", String.valueOf(size));
        putQ(o.query, "keyword", keyword);
        putQ(o.query, "ownerName", ownerName);
        putQ(o.query, "riskLevel", riskLevel);
        Object data = request(o);
        return data instanceof Map ? Page.from(Json.asObj(data)) : null;
    }

    private static void putQ(Map<String, String> q, String k, String v) {
        if (v != null && !v.isEmpty()) q.put(k, v);
    }

    /** 分页查询排查报告。 */
    public Object listReports(int current, int size) {
        if (size == 0) size = 10;
        checkSize(size);
        if (current == 0) current = 1;
        ReqOpts o = new ReqOpts();
        o.path = "/ip/report/list";
        o.query.put("current", String.valueOf(current));
        o.query.put("size", String.valueOf(size));
        return request(o);
    }

    /** 工作台汇总统计。 */
    public Object getWorkbenchStats() {
        ReqOpts o = new ReqOpts(); o.path = "/ip/workbench/stats";
        return request(o);
    }

    /** 点数余额与用量查询（实时）。 */
    public OpenApiUsage getUsage() {
        ReqOpts o = new ReqOpts(); o.path = "/ip/usage";
        Object data = request(o);
        return data instanceof Map ? OpenApiUsage.from(Json.asObj(data)) : null;
    }

    /** 按 requestId 回查请求日志。 */
    public Object queryRequestLog(String requestId) {
        if (requestId == null || requestId.isEmpty() || requestId.length() > 64)
            throw new ValidationError("requestId 必须为 1~64 字符。");
        ReqOpts o = new ReqOpts(); o.path = "/ip/requests";
        o.query.put("requestId", requestId);
        return request(o);
    }

    // ---- 数据能力：图形检索 / 商标公告 / 评审文书 / 海关备案（GET，读类 1 点）----
    // 返回 data 原始对象（Map/List，结构随 mode 变化），由调用方按传入 mode 解析。

    /** 图形检索：维也纳 VCL10 / 关键词换码 / 分类树 / 以图搜图。 */
    public Object queryVienna(Map<String, ?> params) {
        ReqOpts o = new ReqOpts(); o.path = "/ip/vienna/query";
        if (params != null) params.forEach((k, v) -> { if (v != null) o.query.put(k, String.valueOf(v)); });
        return request(o);
    }

    /** 商标公告：CN 公告明细与期号 / 全球 7 局事件。 */
    public Object queryGazette(Map<String, ?> params) {
        ReqOpts o = new ReqOpts(); o.path = "/ip/gazette/query";
        if (params != null) params.forEach((k, v) -> { if (v != null) o.query.put(k, String.valueOf(v)); });
        return request(o);
    }

    /** 评审文书：CNIPA 298 万裁定书。 */
    public Object queryReview(Map<String, ?> params) {
        ReqOpts o = new ReqOpts(); o.path = "/ip/review/query";
        if (params != null) params.forEach((k, v) -> { if (v != null) o.query.put(k, String.valueOf(v)); });
        return request(o);
    }

    /** 海关备案：12 万条备案记录（可选接入）。 */
    public Object queryIpr(Map<String, ?> params) {
        ReqOpts o = new ReqOpts(); o.path = "/ip/ipr/query";
        if (params != null) params.forEach((k, v) -> { if (v != null) o.query.put(k, String.valueOf(v)); });
        return request(o);
    }

    /** MCP 服务健康检查（裸响应）。 */
    public MCPHealth getMcpHealth() {
        ReqOpts o = new ReqOpts();
        o.path = "/mcp/health"; o.mcp = true; o.noEnvelope = true; o.retries = 0;
        Object data = request(o);
        return data instanceof Map ? MCPHealth.from(Json.asObj(data)) : null;
    }

    /** MCP JSON-RPC 调用（scope: mcp）。默认不自动重试。 */
    public Object callMcp(String method, Map<String, Object> params, int id) {
        if (id == 0) id = 1;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        payload.put("id", id);
        if (params != null) payload.put("params", params);
        ReqOpts o = new ReqOpts();
        o.method = "POST"; o.path = "/mcp"; o.body = payload;
        o.mcp = true; o.noEnvelope = true; o.retries = 0;
        return request(o);
    }

    @Override public void close() {
        // java.net.http.HttpClient implements AutoCloseable only since Java 21,
        // but this project targets release 11. Its connection pool is managed
        // by the JDK; there is nothing to close explicitly on Java 11/17.
    }

    // ---------------- 便捷层 ----------------
    public static String formatSearchResult(TrademarkSearchResult r) {
        if (r == null) return "（无结果）";
        StringBuilder b = new StringBuilder();
        b.append("检索: \"").append(r.keyword).append("\"  法域=").append(r.jurisdictionCode).append('\n');
        b.append("结论: ").append(r.conclusion == null || r.conclusion.isEmpty() ? "（无）" : r.conclusion).append('\n');
        b.append("命中 ").append(r.total).append(" 条 | 高险 ").append(r.highRisk)
                .append(" / 中险 ").append(r.mediumRisk).append(" / 低险 ").append(r.lowRisk).append('\n');
        if (r.items != null) {
            int lim = Math.min(r.items.size(), 5);
            for (int i = 0; i < lim; i++) {
                TrademarkItem it = r.items.get(i);
                b.append("  [").append(it.riskLevel == null ? "?" : it.riskLevel).append("] ")
                        .append(it.markText).append("  ").append(it.applicationNumber)
                        .append("  ").append(it.ownerName).append('\n');
            }
        }
        return b.toString().replaceAll("\\n$", "");
    }
}
