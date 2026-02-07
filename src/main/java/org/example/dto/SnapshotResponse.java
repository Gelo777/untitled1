package org.example.dto;

import java.util.List;

public record SnapshotResponse(
        String snapshotId,
        String summary,
        String hint,
        List<String> nextSteps
) {}
