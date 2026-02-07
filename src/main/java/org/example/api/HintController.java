package org.example.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.ApiException;
import org.example.dto.OpenAiDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class HintController {

    private final org.example.service.OpenAiClient openAi;
    private final ObjectMapper om;

    public HintController(org.example.service.OpenAiClient openAi, ObjectMapper om) {
        this.openAi = openAi;
        this.om = om;
    }

    // --- HEALTH ---------------------------------------------------------------

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    // --- AUDIO HINT -----------------------------------------------------------

    public record HintResponse(
            String hintId,
            String transcript,
            String hint,
            List<String> nextSteps
    ) {}

    @PostMapping(
            value = "/hint/audio",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public HintResponse hintAudio(
            @RequestHeader("X-License-Key") String licenseKey,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            @RequestPart(value = "transcript", required = false) String transcript,
            @RequestPart(value = "meta", required = false) String metaJson
    ) throws Exception {

        requireLicense(licenseKey);

        String lang = parseLang(metaJson);

        // Если transcript уже пришёл — быстрее, STT не вызываем
        String text;
        if (transcript != null && !transcript.isBlank()) {
            text = transcript;
        } else {
            if (audio == null || audio.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_AUDIO",
                        "audio or transcript is required", Map.of());
            }
            text = openAi.transcribe(audio, lang);
        }

        OpenAiDtos.HintJson hj = openAi.hintFromTranscript(text, lang);

        return new HintResponse(
                UUID.randomUUID().toString(),
                text,
                hj.hint(),
                hj.nextSteps() == null ? List.of() : hj.nextSteps()
        );
    }

    // --- SNAPSHOT (VISION) ----------------------------------------------------

    public record SnapshotResponse(
            String snapshotId,
            String taskType,            // LIVE_CODING | CODE_REVIEW | ARCHITECTURE | DEBUG | THEORY | UNKNOWN
            String output,              // главный ответ
            String code,                // если есть готовый код/патч
            List<String> checklist,
            List<String> questions,
            List<String> nextSteps
    ) {}

    @PostMapping(
            value = "/snapshot",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public SnapshotResponse snapshot(
            @RequestHeader("X-License-Key") String licenseKey,
            @RequestPart("image") MultipartFile image,
            @RequestPart(value = "meta", required = false) String metaJson
    ) throws Exception {

        requireLicense(licenseKey);

        if (image == null || image.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_IMAGE", "image is required", Map.of());
        }

        String lang = parseLang(metaJson);
        String contentType = image.getContentType();

        OpenAiDtos.SnapshotJson sj = openAi.analyzeScreenshot(image.getBytes(), contentType, lang);

        return new SnapshotResponse(
                UUID.randomUUID().toString(),
                sj.taskType(),
                sj.output(),
                sj.code(),
                sj.checklist() == null ? List.of() : sj.checklist(),
                sj.questions() == null ? List.of() : sj.questions(),
                sj.nextSteps() == null ? List.of() : sj.nextSteps()
        );
    }

    // --- HELPERS --------------------------------------------------------------

    private void requireLicense(String licenseKey) {
        if (licenseKey == null || licenseKey.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LICENSE_MISSING",
                    "X-License-Key header is required", Map.of());
        }
    }

    private String parseLang(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) return "ru";
        try {
            JsonNode node = om.readTree(metaJson);
            JsonNode lang = node.get("lang");
            if (lang != null && lang.isTextual() && !lang.asText().isBlank()) {
                return lang.asText();
            }
            return "ru";
        } catch (Exception e) {
            // meta не обязателен, не валим запрос
            return "ru";
        }
    }
}
