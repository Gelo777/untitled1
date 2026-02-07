package org.example.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


import java.util.List;

public class OpenAiDtos {

    // --- STT ---
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TranscriptionResponse(String text) {}

    // --- Chat Completions ---
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionResponse(List<Choice> choices, Usage usage) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Choice(Message message) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Message(String content) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Usage(long total_tokens) {}
    }

    // то, что мы просим у модели вернуть (наш JSON)
    public record HintJson(
            String hint,
            List<String> nextSteps
    ) {}
    public record SnapshotJson(
            String taskType,
            String output,
            String code,
            List<String> checklist,
            List<String> questions,
            List<String> nextSteps
    ) {}
}
