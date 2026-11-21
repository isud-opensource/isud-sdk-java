package cn.isud.sdk;

import java.util.Map;

/**
 * 异常体系 —— 错误码/HTTP 状态码到 Java 异常的映射。见 SDK-DESIGN.md §3。
 * 与 Python/TypeScript 版本同样的分类与语义。
 */
public final class SdkErrors {
    private SdkErrors() {}

    /** 每次调用都解析的响应元数据（扣点/追踪/限流），成功失败都有。 */
    public static final class ResponseMeta {
        public String requestId;
        public Integer creditsCharged;
        public Integer creditsRemaining;
        public Integer rateLimit;
        public Integer rateRemaining;
        public boolean idempotentReplay;
        public int httpStatus;

        @Override public String toString() {
            return "request_id=" + requestId + " credits_charged=" + creditsCharged
                    + " credits_remaining=" + creditsRemaining;
        }
    }

    public enum Kind {
        AuthenticationError(false), RateLimitError(true), ValidationError(false),
        CapabilityError(false), ApiError(false), NetworkError(true);

        public final boolean defaultRetryable;
        Kind(boolean retryable) { this.defaultRetryable = retryable; }
    }

    /** 所有 SDK 异常的基类。 */
    public static class SdkError extends RuntimeException {
        public final Kind kind;
        public final String code;
        public final String requestId;
        public final String suggestion;
        public final boolean retryable;
        public final ResponseMeta meta;
        public final int statusCode;
        /** 仅 RateLimitError 使用（秒） */
        public Integer retryAfter;

        public SdkError(Kind kind, String message, String code, String requestId,
                        String suggestion, Boolean retryable, ResponseMeta meta,
                        int statusCode, Integer retryAfter) {
            super(render(message, code, suggestion, requestId));
            this.kind = kind;
            this.code = code;
            this.requestId = requestId;
            this.suggestion = suggestion;
            this.retryable = retryable == null ? kind.defaultRetryable : retryable;
            this.meta = meta == null ? new ResponseMeta() : meta;
            this.statusCode = statusCode;
            this.retryAfter = retryAfter;
        }

        private static String render(String msg, String code, String suggestion, String rid) {
            StringBuilder b = new StringBuilder(msg == null ? "请求失败" : msg);
            if (code != null && !code.isEmpty()) b.append(" [code=").append(code).append(']');
            if (suggestion != null && !suggestion.isEmpty()) b.append(" 建议: ").append(suggestion);
            if (rid != null && !rid.isEmpty()) b.append(" (requestId=").append(rid).append(')');
            return b.toString();
        }

        /** 给示例/终端用的完整友好提示（不裸 stack）。 */
        public String friendly() {
            StringBuilder b = new StringBuilder();
            b.append("✗ ").append(getMessage()).append('\n');
            if (code != null && !code.isEmpty()) b.append("  错误码: ").append(code).append('\n');
            if (suggestion != null && !suggestion.isEmpty()) b.append("  建议: ").append(suggestion).append('\n');
            if (requestId != null && !requestId.isEmpty())
                b.append("  追踪ID: ").append(requestId).append("（报障时提供）\n");
            if (meta.creditsCharged != null) b.append("  本次扣点: ").append(meta.creditsCharged).append('\n');
            return b.toString().replaceAll("\\n$", "");
        }
    }

    public static final class AuthenticationError extends SdkError {
        public AuthenticationError(String m, String c, String r, String s, Boolean rt, ResponseMeta meta, int st) {
            super(Kind.AuthenticationError, m, c, r, s, rt, meta, st, null);
        }
    }
    public static final class RateLimitError extends SdkError {
        public RateLimitError(String m, String c, String r, String s, Boolean rt, ResponseMeta meta, int st, Integer ra) {
            super(Kind.RateLimitError, m, c, r, s, rt, meta, st, ra);
        }
    }
    public static final class ValidationError extends SdkError {
        public ValidationError(String message) {
            super(Kind.ValidationError, message, null, null, null, false, new ResponseMeta(), 0, null);
        }
        public ValidationError(String m, String c, String r, String s, Boolean rt, ResponseMeta meta, int st) {
            super(Kind.ValidationError, m, c, r, s, rt, meta, st, null);
        }
    }
    public static final class CapabilityError extends SdkError {
        public CapabilityError(String m, String c, String r, String s, Boolean rt, ResponseMeta meta, int st) {
            super(Kind.CapabilityError, m, c, r, s, rt, meta, st, null);
        }
    }
    public static final class ApiError extends SdkError {
        public ApiError(String m, String c, String r, String s, Boolean rt, ResponseMeta meta, int st) {
            super(Kind.ApiError, m, c, r, s, rt, meta, st, null);
        }
        public ApiError(String message) {
            super(Kind.ApiError, message, null, null, null, null, new ResponseMeta(), 0, null);
        }
    }
    public static final class NetworkError extends SdkError {
        public NetworkError(String message) {
            super(Kind.NetworkError, message, null, null, null, true, new ResponseMeta(), 0, null);
        }
        public NetworkError(String m, ResponseMeta meta, int st) {
            super(Kind.NetworkError, m, null, null, null, true, meta, st, null);
        }
    }

    private static final Map<String, Kind> CODE_TO_KIND = Map.of(
            "8889", Kind.AuthenticationError,
            "9001", Kind.ValidationError,
            "9002", Kind.RateLimitError,
            "9003", Kind.RateLimitError,
            "0002", Kind.CapabilityError,
            "0001", Kind.ApiError
    );

    /** 根据信封 + HTTP 状态码构造对应异常；成功（0000）返回 null。 */
    public static SdkError raiseForEnvelope(Map<String, Object> env, int httpStatus, ResponseMeta meta) {
        String code = Json.str(env.get("code"));
        if ("0000".equals(code)) return null;
        String msg = Json.str(env.get("msg"));
        if (msg.isEmpty()) msg = "请求失败";
        String rid = Json.str(env.get("requestId"));
        if (rid.isEmpty()) rid = meta.requestId;
        String suggestion = env.get("suggestion") == null ? null : Json.str(env.get("suggestion"));
        Boolean retryable = Json.asBool(env.get("retryable"));
        Integer retryAfter = env.get("retryAfter") == null ? null : Json.asInt(env.get("retryAfter"));

        if (httpStatus == 401 || "8889".equals(code))
            return new AuthenticationError(msg, code, rid, suggestion, retryable, meta, httpStatus);
        if (httpStatus == 429 || "9002".equals(code) || "9003".equals(code))
            return new RateLimitError(msg, code, rid, suggestion, retryable, meta, httpStatus, retryAfter);

        Kind kind = CODE_TO_KIND.getOrDefault(code, Kind.ApiError);
        switch (kind) {
            case ValidationError: return new ValidationError(msg, code, rid, suggestion, retryable, meta, httpStatus);
            case CapabilityError: return new CapabilityError(msg, code, rid, suggestion, retryable, meta, httpStatus);
            default: return new ApiError(msg, code, rid, suggestion, retryable, meta, httpStatus);
        }
    }

    public static boolean isKind(Throwable t, Kind kind) {
        return t instanceof SdkError && ((SdkError) t).kind == kind;
    }
}
