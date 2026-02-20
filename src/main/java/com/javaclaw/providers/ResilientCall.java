package com.javaclaw.providers;

import java.util.concurrent.Callable;

public class ResilientCall {

    private static final int MAX_RETRIES = 2;
    private static final long INITIAL_DELAY_MS = 500;

    public static <T> T execute(Callable<T> action) {
        Exception last = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(INITIAL_DELAY_MS << attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                }
            }
        }
        throw new RuntimeException("All retries exhausted", last);
    }
}
