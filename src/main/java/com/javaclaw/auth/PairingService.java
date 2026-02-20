package com.javaclaw.auth;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PairingService {

    private final SecureRandom random = new SecureRandom();
    // code -> channel (e.g. "telegram", "discord")
    private final Map<String, String> pendingCodes = new ConcurrentHashMap<>();

    public String generateCode(String channel) {
        String code;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
        } while (pendingCodes.putIfAbsent(code, channel) != null);
        return code;
    }

    /** Consumes the code only if it was generated for the given channel. Returns true on success. */
    public boolean consumeCode(String code, String expectedChannel) {
        return pendingCodes.remove(code, expectedChannel);
    }
}
