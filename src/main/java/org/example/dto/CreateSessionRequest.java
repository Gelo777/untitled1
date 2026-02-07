package org.example.dto;

public record CreateSessionRequest(
        String lang,
        Profile profile,
        String instructions
) {
    public record Profile(String stack, String level) {}
}
