package org.example.dto;

import java.time.Instant;

public record CreateSessionResponse(String sessionId, Instant createdAt) {}
