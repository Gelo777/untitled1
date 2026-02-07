package org.example.dto;

public record LicenseStatusResponse(
        String status,
        String plan,
        Limits limits,
        UsageToday usageToday
) {
    public record Limits(int maxHintsPerDay, int maxSnapshotsPerDay, int maxAudioSecondsPerHint) {}
    public record UsageToday(int hints, int snapshots, long llmTokens) {}
}
