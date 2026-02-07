package org.example.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.ApiException;
import org.example.dto.OpenAiDtos;
import org.example.service.LicenseService;
import org.example.service.OpenAiClient;
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

    private final OpenAiClient openAi;
    private final ObjectMapper om;
    private final LicenseService licenseService;

    public HintController(OpenAiClient openAi, ObjectMapper om, LicenseService licenseService) {
        this.openAi = openAi;
        this.om = om;
        this.licenseService = licenseService;
    }

    public record HintResponse(
            String hintId,
            String taskType,     // TEXT | VISION
            String question,
            String output,       // главный ответ
            String code,         // если вернулось
            List<String> checklist,
            List<String> questions,
            List<String> nextSteps
    ) {}

    @PostMapping(
            value = "/hint",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public HintResponse hint(
            @RequestHeader("X-License-Key") String licenseKey,
            @RequestPart("question") String question,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestPart(value = "meta", required = false) String metaJson
    ) throws Exception {

        licenseService.requireValid(licenseKey);

        if (question == null || question.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_QUESTION", "question is required", Map.of());
        }

        String lang = parseLang(metaJson);

        // если есть картинка — используем vision-ответ, иначе обычный hint
        if (image != null && !image.isEmpty()) {
            String ct = image.getContentType();
            OpenAiDtos.SnapshotJson sj = openAi.analyzeScreenshot(image.getBytes(), ct, lang, question);

            return new HintResponse(
                    UUID.randomUUID().toString(),
                    "VISION",
                    question,
                    sj.output(),
                    sj.code(),
                    sj.checklist() == null ? List.of() : sj.checklist(),
                    sj.questions() == null ? List.of() : sj.questions(),
                    sj.nextSteps() == null ? List.of() : sj.nextSteps()
            );
        } else {
            OpenAiDtos.HintJson hj = openAi.hintFromTranscript(question, lang);

            return new HintResponse(
                    UUID.randomUUID().toString(),
                    "TEXT",
                    question,
                    hj.hint(),
                    "",
                    List.of(),
                    List.of(),
                    hj.nextSteps() == null ? List.of() : hj.nextSteps()
            );
        }
    }

    private String parseLang(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) return "ru";
        try {
            JsonNode node = om.readTree(metaJson);
            JsonNode lang = node.get("lang");
            if (lang != null && lang.isTextual() && !lang.asText().isBlank()) return lang.asText();
            return "ru";
        } catch (Exception e) {
            return "ru";
        }
    }
}
