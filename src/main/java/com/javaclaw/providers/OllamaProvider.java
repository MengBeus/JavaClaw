package com.javaclaw.providers;

public class OllamaProvider extends OpenAiCompatibleProvider {

    public OllamaProvider(String model) {
        super("ollama", "http://localhost:11434/v1", model);
    }

    @Override
    public String id() {
        return "ollama";
    }
}
