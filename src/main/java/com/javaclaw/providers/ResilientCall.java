package com.javaclaw.providers;

import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ResilientCall {

    private static final int MAX_RETRIES = 2;
    private static final long INITIAL_DELAY_MS = 500;
    private static final long MAX_BACKOFF_MS = 10_000;
    private static final long RETRY_AFTER_CAP_MS = 30_000;
    private static final Pattern STATUS_CODE = Pattern.compile("\\b(\\d{3})\\b");
    private static final Pattern RETRY_AFTER = Pattern.compile(
            "(?i)retry[_-]after[:\\s]+([\\d.]+)");

    public static <T> T execute(Callable<T> action) {
        return execute(action, MAX_RETRIES, INITIAL_DELAY_MS);
    }

    public static <T> T execute(Callable<T> action, int maxRetries, long baseDelayMs) {
        Exception last = null;
        long delay = baseDelayMs;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                last = e;
                if (isNonRetryable(e)) break;
                if (attempt < maxRetries) {
                    long wait = isRateLimited(e)
                            ? Math.max(delay, parseRetryAfterMs(e))
                            : delay;
                    sleep(wait);
                    delay = Math.min(delay * 2, MAX_BACKOFF_MS);
                }
            }
        }
        throw new RuntimeException("All retries exhausted", last);
    }

    static boolean isNonRetryable(Exception e) {
        int code = extractStatusCode(e);
        return code >= 400 && code < 500 && code != 429 && code != 408;
    }

    static boolean isRateLimited(Exception e) {
        int code = extractStatusCode(e);
        if (code == 429) return true;
        String msg = e.getMessage();
        return msg != null && msg.contains("429")
                && (msg.contains("Too Many") || msg.contains("rate") || msg.contains("limit"));
    }

    static long parseRetryAfterMs(Exception e) {
        if (e.getMessage() == null) return 0;
        Matcher m = RETRY_AFTER.matcher(e.getMessage());
        if (m.find()) {
            try {
                double secs = Double.parseDouble(m.group(1));
                if (Double.isFinite(secs) && secs >= 0) {
                    return Math.min((long) (secs * 1000), RETRY_AFTER_CAP_MS);
                }
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static int extractStatusCode(Exception e) {
        if (e.getMessage() == null) return 0;
        Matcher m = STATUS_CODE.matcher(e.getMessage());
        while (m.find()) {
            int code = Integer.parseInt(m.group(1));
            if (code >= 100 && code < 600) return code;
        }
        return 0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during retry", ie);
        }
    }
}
