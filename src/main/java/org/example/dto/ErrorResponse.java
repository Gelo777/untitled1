package org.example.dto;


import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ErrorResponse(ErrorBody error) {
    public record ErrorBody(String code, String message, String requestId, Map<String, Object> details) {}
}

