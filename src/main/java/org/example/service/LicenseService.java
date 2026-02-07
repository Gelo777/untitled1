package org.example.service;


import org.example.config.LicenseProperties;
import org.example.dto.ApiException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
@Service
public class LicenseService {
    private final LicenseProperties props;

    public LicenseService(LicenseProperties props) {
        this.props = props;
    }

    public LicenseProperties.LicenseEntry requireValid(String licenseKey) {
        if (licenseKey == null || licenseKey.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LICENSE_MISSING",
                    "X-License-Key header is required", Map.of());
        }

        var entry = props.keys() == null ? null :
                props.keys().stream().filter(k -> licenseKey.equals(k.key())).findFirst().orElse(null);

        if (entry == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LICENSE_INVALID",
                    "License key not found", Map.of());
        }
        if (!entry.enabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LICENSE_DISABLED",
                    "License is disabled", Map.of());
        }
        if (entry.expiresAt() != null && Instant.now().isAfter(entry.expiresAt())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LICENSE_EXPIRED",
                    "License is expired", Map.of("expiresAt", entry.expiresAt().toString()));
        }
        return entry;
    }
}
