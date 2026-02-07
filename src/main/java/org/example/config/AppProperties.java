package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "app")
public record AppProperties(OpenAi openai) {

    public record OpenAi(
            String apiKey,
            String baseUrl,
            String chatModel,
            String sttModel,
            int timeoutMs
    ) {}
}
