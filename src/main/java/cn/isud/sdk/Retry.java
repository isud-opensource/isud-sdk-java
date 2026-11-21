package cn.isud.sdk;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;

/** Retry-After 退避策略 —— 与 Python/TypeScript/Go 行为对齐，见 SDK-DESIGN.md §4。 */
public final class Retry {
    private Retry() {}

    public static final int MAX_WAIT_SECONDS = 60;
    private static final double BASE_BACKOFF = 1.0;
    private static final double MAX_BACKOFF = 30.0;

    /** 解析 Retry-After 头：整数秒 或 HTTP-date。无法解析返回 null。 */
    public static Integer parseRetryAfter(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String v = value.trim();
        try {
            int n = Integer.parseInt(v);
            return n >= 0 ? n : null;
        } catch (NumberFormatException ignored) {}
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            long secs = zdt.toInstant().getEpochSecond() - Instant.now().getEpochSecond();
            return (int) Math.max(0, secs);
        } catch (DateTimeParseException ignored) {}
        return null;
    }

    /** 第 attempt 次（从 0 起）指数退避秒数，带抖动。 */
    public static double exponentialBackoff(int attempt) {
        double raw = Math.min(BASE_BACKOFF * Math.pow(2, attempt), MAX_BACKOFF);
        return raw * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5);
    }

    /** 决定本次重试等待秒数：信封 retryAfter > RateLimitError.retryAfter > 指数退避。 */
    public static double resolveWait(SdkErrors.SdkError err, int attempt) {
        double wait;
        if (err.kind == SdkErrors.Kind.RateLimitError && err.retryAfter != null) {
            wait = err.retryAfter;
        } else {
            wait = exponentialBackoff(attempt);
        }
        return Math.max(0, Math.min(wait, MAX_WAIT_SECONDS));
    }
}
