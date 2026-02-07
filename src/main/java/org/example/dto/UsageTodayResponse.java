package org.example.dto;

import java.util.Map;

public record UsageTodayResponse(
        String date,
        Map<String, Object> usage,
        Map<String, Object> limits
) {}
