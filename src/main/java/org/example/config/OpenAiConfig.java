package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class OpenAiConfig {

    @Bean
    public RestClient openAiRestClient(AppProperties props) {
        if (props == null || props.openai() == null) {
            throw new IllegalStateException("Missing config: app.openai.* in application.yml");
        }
        var p = props.openai();

        if (p.baseUrl() == null || p.baseUrl().isBlank()) {
            throw new IllegalStateException("Missing config: app.openai.baseUrl");
        }
        if (p.apiKey() == null || p.apiKey().isBlank()) {
            throw new IllegalStateException("Missing config: app.openai.apiKey");
        }

        return RestClient.builder()
                .baseUrl(p.baseUrl())
                .defaultHeader("Authorization", "Bearer " + p.apiKey())
                .build();
    }
}
