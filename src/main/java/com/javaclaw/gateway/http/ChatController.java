package com.javaclaw.gateway.http;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ChatController {
    @PostMapping("/v1/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        return Map.of("reply", body.getOrDefault("message", ""));
    }
}
