package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppProperties;
import org.example.dto.ApiException;
import org.example.dto.OpenAiDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final RestClient rc;
    private final AppProperties props;
    private final ObjectMapper om;

    public OpenAiClient(RestClient openAiRestClient, AppProperties props, ObjectMapper om) {
        this.rc = openAiRestClient;
        this.props = props;
        this.om = om;
    }

    private void ensureApiKey() {
        if (props.openai() == null || props.openai().apiKey() == null || props.openai().apiKey().isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OPENAI_KEY_MISSING",
                    "OPENAI_API_KEY is not set on server", Map.of());
        }
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    /**
     * Speech-to-text через /audio/transcriptions.
     */
    public String transcribe(MultipartFile audio, String lang) {
        ensureApiKey();

        try {
            byte[] bytes = audio.getBytes();
            String filename = (audio.getOriginalFilename() == null || audio.getOriginalFilename().isBlank())
                    ? "audio.wav"
                    : audio.getOriginalFilename();

            var body = new LinkedMultiValueMap<String, Object>();
            body.add("model", props.openai().sttModel());
            if (lang != null && !lang.isBlank()) body.add("language", lang);

            body.add("file", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });

            var resp = rc.post()
                    .uri("/audio/transcriptions")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(OpenAiDtos.TranscriptionResponse.class);

            if (resp == null || resp.text() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_STT_EMPTY", "Empty STT response", Map.of());
            }
            return resp.text();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_STT_ERROR", "STT call failed", Map.of("err", e.getMessage()));
        }
    }

    /**
     * Подсказка по тексту (транскрипту) через Chat Completions.
     * <p>
     * MVP: возвращаем строго JSON вида {"hint":"...","nextSteps":[...]}.
     * Важно: здесь НЕ используем schema для snapshot, чтобы не мешать.
     */
    public OpenAiDtos.HintJson hintFromTranscript(String transcript, String lang) {
        ensureApiKey();

        String system = """
                Ты ассистент на собеседовании.
                Дай максимально конкретный ответ по тексту вопроса.
                
                Верни строго JSON:
                {"hint":"...","nextSteps":["...","..."]}
                
                Без markdown. Без лишних полей.
                """;

        var req = Map.of(
                "model", props.openai().chatModel(),
                "reasoning_effort", "minimal",
                "max_completion_tokens", 900,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", "Текст вопроса/контекст:\n" + transcript)
                )
        );

        try {
            String raw = rc.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_RAW_EMPTY", "Empty raw response from OpenAI", Map.of());
            }

            log.info("OpenAI hint raw response: {}", trunc(raw, 2000));

            JsonNode root = om.readTree(raw);
            String finish = root.path("choices").path(0).path("finish_reason").asText(null);
            String content = extractAssistantContent(root);

            if ("length".equals(finish) && (content == null || content.isBlank())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_CHAT_TRUNCATED",
                        "Model hit token limit before producing visible output. Increase max_completion_tokens and/or reduce reasoning_effort.",
                        Map.of("finish_reason", finish));
            }

            if (content == null || content.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_CHAT_EMPTY", "Empty chat response", Map.of("raw", trunc(raw, 2000)));
            }

            // content должен быть JSON
            JsonNode node = om.readTree(content);
            if (!node.hasNonNull("hint")) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_CHAT_BAD_JSON",
                        "Model returned JSON without expected fields", Map.of("content", trunc(content, 1200)));
            }

            return om.treeToValue(node, OpenAiDtos.HintJson.class);

        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.error("OpenAI hint HTTP error status={} body={}", e.getRawStatusCode(), trunc(body, 2000));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_CHAT_HTTP",
                    "HTTP " + e.getRawStatusCode() + " from OpenAI", Map.of("body", trunc(body, 2000)));

        } catch (ResourceAccessException e) {
            log.error("OpenAI hint network/timeout error: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_CHAT_NETWORK",
                    "Network/timeout: " + e.getMessage(), Map.of());

        } catch (ApiException e) {
            throw e;

        } catch (Exception e) {
            log.error("OpenAI hint unexpected error", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_CHAT_ERROR", "Chat call failed", Map.of("err", e.getMessage()));
        }
    }

    /**
     * Анализ скриншота (vision) через Chat Completions + Structured Outputs (json_schema).
     * Здесь обязательно:
     * - reasoning_effort=minimal (иначе съедает все токены на reasoning и content пустой)
     * - max_completion_tokens с запасом
     */
    public OpenAiDtos.SnapshotJson analyzeScreenshot(byte[] imageBytes, String contentType, String lang, String question) {
        ensureApiKey();

        if (imageBytes == null || imageBytes.length == 0) {
            throw new ApiException("BAD_IMAGE", "Image is empty");
        }
        // На время отладки: чтобы точно не упереться в лимиты payload
        if (imageBytes.length > 3_000_000) {
            throw new ApiException("IMAGE_TOO_LARGE", "Image too large for debug (max 3MB). bytes=" + imageBytes.length);
        }

        String mime = (contentType == null || contentType.isBlank()) ? "image/png" : contentType;
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:" + mime + ";base64," + b64;

        String system = """
                Ты ассистент на собеседовании. На входе: вопрос пользователя + один скриншот.
                
                                           Сначала определи taskType:
                                           LIVE_CODING | CODE_REVIEW | ARCHITECTURE | DEBUG | THEORY | UNKNOWN.
                
                                           Правила:
                                           - Если по скриншоту и вопросу НЕТ явной задачи (нет формулировки, нет кода/логов/диаграммы, или контекст обрывочный) — ставь UNKNOWN.
                                           - Для НЕ-UNKNOWN: отвечай уверенно и конкретно, без "кажется/возможно", без вопросов к пользователю.
                                           - Для UNKNOWN: НЕ придумывай детали. Дай:
                                             1) кратко: что видно и чего не хватает,
                                             2) 1–3 уточняющих вопроса (самые важные),
                                             3) что можно сделать прямо сейчас (общий лучший совет по теме).
                
                                           Формат ответа: строго JSON по схеме.
                """;

        var userContent = List.of(
                Map.of("type", "text", "text", "Вопрос:\n" + question + "\n\nДай ответ, используя скриншот как контекст."),
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl, "detail", "high"))
        );

        var schema = Map.of(
                "name", "universal_snapshot_response",
                "schema", Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "taskType", Map.of(
                                        "type", "string",
                                        "enum", List.of("LIVE_CODING", "CODE_REVIEW", "ARCHITECTURE", "DEBUG", "THEORY", "UNKNOWN")
                                ),
                                "output", Map.of("type", "string"),
                                "code", Map.of("type", "string"),
                                "checklist", Map.of("type", "array", "items", Map.of("type", "string")),
                                "questions", Map.of("type", "array", "items", Map.of("type", "string")),
                                "nextSteps", Map.of("type", "array", "items", Map.of("type", "string"))
                        ),
                        "required", List.of("taskType", "output", "code", "checklist", "questions", "nextSteps")
                )
        );

        var req = Map.of(
                "model", props.openai().chatModel(),
                "reasoning_effort", "minimal",
                "max_completion_tokens", 1800,
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", schema
                ),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", userContent)
                )
        );

        try {
            String raw = rc.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_RAW_EMPTY", "Empty raw response from OpenAI", Map.of());
            }

            log.info("OpenAI raw response: {}", trunc(raw, 2000));

            JsonNode root = om.readTree(raw);
            String finish = root.path("choices").path(0).path("finish_reason").asText(null);
            String content = extractAssistantContent(root);

            if ("length".equals(finish) && (content == null || content.isBlank())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_VISION_TRUNCATED",
                        "Model hit token limit before producing visible output. Increase max_completion_tokens and/or reduce reasoning_effort.",
                        Map.of("finish_reason", finish));
            }

            if (content == null || content.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_VISION_EMPTY",
                        "Empty content from OpenAI (parsed)", Map.of("raw", trunc(raw, 2000)));
            }

            log.info("OpenAI vision content: {}", trunc(content, 1200));

            // content должен быть JSON (мы же просим json_schema)
            JsonNode node = om.readTree(content);

            if (!node.hasNonNull("taskType") || !node.hasNonNull("output") || !node.has("nextSteps")) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_VISION_BAD_JSON",
                        "Model returned JSON without expected fields",
                        Map.of("content", trunc(content, 1200)));
            }

            return om.treeToValue(node, OpenAiDtos.SnapshotJson.class);

        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.error("OpenAI vision HTTP error status={} body={}", e.getRawStatusCode(), trunc(body, 2000));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_VISION_HTTP",
                    "HTTP " + e.getRawStatusCode() + " from OpenAI", Map.of("body", trunc(body, 2000)));

        } catch (ResourceAccessException e) {
            log.error("OpenAI vision network/timeout error: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_VISION_NETWORK",
                    "Network/timeout: " + e.getMessage(), Map.of());

        } catch (ApiException e) {
            throw e;

        } catch (Exception e) {
            log.error("OpenAI vision unexpected error", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_VISION_ERROR",
                    "Unexpected: " + e.getMessage(), Map.of("err", e.getMessage()));
        }
    }

    /**
     * Достаёт текст ассистента из chat.completion ответа.
     * Поддерживает:
     * - content как строка
     * - content как массив частей [{text:"..."}, ...]
     */
    private String extractAssistantContent(JsonNode root) {
        JsonNode msg = root.path("choices").path(0).path("message");
        JsonNode content = msg.path("content");

        if (content.isTextual()) {
            return content.asText();
        }

        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode t = part.get("text");
                if (t != null && t.isTextual()) sb.append(t.asText());
            }
            String joined = sb.toString().trim();
            if (!joined.isBlank()) return joined;
        }

        JsonNode refusal = msg.get("refusal");
        if (refusal != null && refusal.isTextual()) {
            return ""; // считаем пустым, можно прокинуть отдельно при желании
        }

        return null;
    }
}
