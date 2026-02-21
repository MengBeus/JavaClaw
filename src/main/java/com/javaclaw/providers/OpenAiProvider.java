package com.javaclaw.providers;

public class OpenAiProvider extends OpenAiCompatibleProvider {

    public OpenAiProvider(String apiKey, String baseUrl, String model) {
        super(apiKey, baseUrl, model);
    }

    @Override
    public String id() {
        return "openai";
    }
}
