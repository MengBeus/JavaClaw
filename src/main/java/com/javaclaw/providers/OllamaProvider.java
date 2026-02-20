package com.javaclaw.providers;

import java.time.Duration;

public class OllamaProvider extends OpenAiCompatibleProvider {

    public OllamaProvider(String model) {
        super("ollama", "http://localhost:11434/v1", model, Duration.ofSeconds(120));
    }

    @Override
    public String id() {
        return "ollama";
    }
}
