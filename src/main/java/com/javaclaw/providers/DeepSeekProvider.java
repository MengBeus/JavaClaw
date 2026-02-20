package com.javaclaw.providers;

public class DeepSeekProvider extends OpenAiCompatibleProvider {

    public DeepSeekProvider(String apiKey) {
        super(apiKey, "https://api.deepseek.com/v1", "deepseek-chat");
    }

    @Override
    public String id() {
        return "deepseek-v3";
    }
}
