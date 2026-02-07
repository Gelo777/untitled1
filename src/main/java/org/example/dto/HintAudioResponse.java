package org.example.dto;

import java.util.List;

public record HintAudioResponse(
        String hintId,
        String question,
        String hint,
        List<String> followUps
) {}