package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.util.List;

@ConfigurationProperties(prefix = "licenses")
public record LicenseProperties(List<LicenseEntry> keys) {
    public record LicenseEntry(String key, boolean enabled, Instant expiresAt, String plan) {}
}

