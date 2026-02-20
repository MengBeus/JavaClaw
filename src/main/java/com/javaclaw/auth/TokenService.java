package com.javaclaw.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenService {

    private final SecureRandom random = new SecureRandom();
    // token -> userId
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    public String generate(String userId) {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, userId);
        return token;
    }

    /** Returns userId if token is valid, null otherwise. */
    public String verify(String token) {
        return tokens.get(token);
    }
}
