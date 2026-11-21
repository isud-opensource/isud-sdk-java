package cn.isud.sdk;

import cn.isud.sdk.Client.Transport;
import cn.isud.sdk.SdkErrors.*;
import cn.isud.sdk.Models.TrademarkSearchResult;
import cn.isud.sdk.Models.MCPHealth;
import cn.isud.sdk.Models.OpenApiUsage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 自包含契约测试（纯 javac 可跑，不依赖 JUnit）—— 用注入 Transport mock HTTP 响应。
 * 覆盖：信封解析、错误映射、退避、幂等键、本地校验、MCP 裸响应。
 * Maven 用户也可运行 JUnit 版（ContractTest）。
 */
public final class ContractSelfTest {
    static int passed = 0, failed = 0;

    static HttpResponse<String> resp(int status, String body, Map<String, List<String>> headers) {
        HttpHeaders h = HttpHeaders.of(headers, (k, v) -> true);
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return h; }
            public String body() { return body; }
            public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return null; }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    static Transport fixed(int status, String body, Map<String, List<String>> headers) {
        return (req, timeout) -> resp(status, body, headers);
    }

    static Map<String, List<String>> hdr(String... kv) {
        Map<String, List<String>> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], List.of(kv[i + 1]));
        return m;
    }

    static String env(String code, String dataJson) {
        return "{\"code\":\"" + code + "\",\"msg\":\"test msg\",\"data\":" + dataJson + "}";
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ✓ " + name); }
        else { failed++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) throws Exception {
        // 1. 成功信封 + 扣点回读
        System.out.println("TestSuccessEnvelope:");
        Client c = Client.builder().apiKey("lg_ip_test_fake_key").maxRetries(0)
                .transport((req, t) -> {
                    check("api key header", "lg_ip_test_fake_key".equals(req.headers().firstValue("X-API-Key").orElse("")));
                    check("idempotency key", req.headers().firstValue("Idempotency-Key").isPresent());
                    return resp(200, env("0000", "{\"jurisdictionCode\":\"CN\",\"keyword\":\"优标宝\",\"total\":3,\"highRisk\":1,\"items\":[{\"markText\":\"优标宝\",\"riskLevel\":\"high\"}]}"),
                            hdr("X-Credits-Charged", "10", "X-Request-Id", "req-123"));
                }).build();
        TrademarkSearchResult r = c.searchTrademarks("CN", "优标宝");
        check("result parsed", r != null && r.total == 3 && r.items.size() == 1 && "优标宝".equals(r.items.get(0).markText));
        check("credits meta", c.lastMeta.creditsCharged != null && c.lastMeta.creditsCharged == 10);
        check("request id meta", "req-123".equals(c.lastMeta.requestId));

        // 2. 错误映射
        System.out.println("TestErrorMapping:");
        int[][] cases = {{401,8889},{200,8889},{429,9002},{200,9003},{200,9001},{200,0002},{200,0001}};
        Kind[] kinds = {Kind.AuthenticationError, Kind.AuthenticationError, Kind.RateLimitError,
                Kind.RateLimitError, Kind.ValidationError, Kind.CapabilityError, Kind.ApiError};
        for (int i = 0; i < cases.length; i++) {
            final int st = cases[i][0]; final String code = String.format("%04d", cases[i][1]);
            Client cc = Client.builder().apiKey("k").maxRetries(0)
                    .transport(fixed(st, env(code, "null"), hdr())).build();
            try {
                cc.getUsage();
                check("status=" + st + " code=" + code + " throws", false);
            } catch (SdkError e) {
                check("status=" + st + " code=" + code + " -> " + kinds[i], e.kind == kinds[i]);
            }
        }

        // 3. 本地校验（不发请求）
        System.out.println("TestLocalValidation:");
        Client vc = Client.builder().apiKey("k").transport((req, t) -> {
            check("no request for invalid params", false);
            return resp(200, env("0000", "{}"), hdr());
        }).build();
        try { vc.listTrademarks(1, 101, null, null, null); check("size=101 validation", false); }
        catch (SdkError e) { check("size=101 validation", e.kind == Kind.ValidationError); }
        try { vc.searchTrademarks("CN", null); check("empty keyword validation", false); }
        catch (SdkError e) { check("empty keyword validation", e.kind == Kind.ValidationError); }
        try { vc.queryRequestLog("x".repeat(65)); check("long requestId validation", false); }
        catch (SdkError e) { check("long requestId validation", e.kind == Kind.ValidationError); }

        // 4. MCP 裸响应
        System.out.println("TestMCPBareResponse:");
        Client mc = Client.builder().apiKey("k").transport((req, t) -> {
            check("mcp path", req.uri().toString().contains("/api/v1/mcp/health"));
            return resp(200, "{\"status\":\"ok\",\"toolCount\":13}", hdr());
        }).build();
        MCPHealth h = mc.getMcpHealth();
        check("mcp health parsed", h != null && "ok".equals(h.status) && h.toolCount == 13);

        // 5. Retry-After 解析
        System.out.println("TestParseRetryAfter:");
        check("int retry-after", Retry.parseRetryAfter("30") == 30);
        String future = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(5)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        Integer ra = Retry.parseRetryAfter(future);
        check("http-date retry-after", ra != null && ra >= 3 && ra <= 5);
        check("garbage -> null", Retry.parseRetryAfter("garbage") == null);

        // 6. 退避重试（429 两次后成功）
        System.out.println("TestRetryOn429:");
        final int[] calls = {0};
        Client rc = Client.builder().apiKey("k").maxRetries(2).transport((req, t) -> {
            calls[0]++;
            if (calls[0] < 3) return resp(429, env("9002", "null").replace("\"data\":null", "\"data\":null,\"retryAfter\":0"), hdr());
            return resp(200, env("0000", "{\"balance\":100}"), hdr());
        }).build();
        OpenApiUsage u = rc.getUsage();
        check("success after retries", u != null && u.balance == 100 && calls[0] == 3);

        System.out.println("\nPASS=" + passed + " FAIL=" + failed);
        if (failed > 0) System.exit(1);
    }
}
