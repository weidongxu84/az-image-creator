package io.weidongxu.webapp.imagecreator;

import com.azure.core.credential.TokenRequestContext;
import com.azure.core.credential.TokenCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.azure.AzureUrlPathMode;
import com.openai.azure.AzureOpenAIServiceVersion;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.credential.BearerTokenCredential;
import com.openai.errors.OpenAIServiceException;
import com.openai.core.MultipartField;
import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageEditParams.Image;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OpenAIService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAIService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMAGE_API_VERSION = "2025-04-01-preview";
    private static final long OUTPUT_COMPRESSION = 95L;
    private static final String CHAT_SYSTEM_PROMPT_PLAIN = """
            You are an expert creative image consultant and prompt engineer.

            Answer the user naturally and helpfully. If the current user turn contains an image,
            include practical critique and concrete improvement advice.

            Return this exact plain-text format (no markdown fences):

            ASSISTANT_REPLY:
            <your user-facing response>

            IMAGE_SUMMARY:
            <compact reusable summary, or NONE if no image in the current turn>

            IMPROVEMENT_ACTIONS:
            - <action 1>
            - <action 2>

            BEST_PROMPT_CANDIDATE:
            <single gpt-image-2 EDIT prompt for the uploaded image, or empty if no image>

            Additional rules:
            - If image is present, write BEST_PROMPT_CANDIDATE for image-to-image editing of the
              uploaded photo, not fresh generation.
            - Preserve original subject/composition unless user asks to change them.
            """;

    private final String deployment;
    private final String chatDeployment;
    private final String chatResponsesUrl;
    private final String configuredApiKey;
    private final TokenCredential tokenCredential;
    private final ChatResponseParser chatResponseParser;
    private final OpenAIClient managedIdentityImageClient;
    private final OpenAIClient apiKeyFallbackImageClient;
    private final HttpClient httpClient;

    public OpenAIService(AppConfig config, ChatResponseParser chatResponseParser) {
        this.deployment = config.getOpenAIDeployment();
        this.chatDeployment = config.getOpenAIChatDeployment();
        String endpoint = config.getOpenAIEndpoint();
        String trimmedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.chatResponsesUrl = trimmedEndpoint + "/openai/v1/responses";
        this.configuredApiKey = config.getOpenAIApiKey();
        this.tokenCredential = config.getCredential();
        this.chatResponseParser = chatResponseParser;
        this.httpClient = HttpClient.newBuilder().build();

        var managedImageBuilder = OpenAIOkHttpClient.builder()
                .baseUrl(endpoint)
                .azureUrlPathMode(AzureUrlPathMode.UNIFIED)
                .azureServiceVersion(AzureOpenAIServiceVersion.fromString(IMAGE_API_VERSION));

        TokenRequestContext ctx = new TokenRequestContext()
                .addScopes("https://cognitiveservices.azure.com/.default");
        managedImageBuilder.apiKey("none")
                .credential(BearerTokenCredential.create(
                        () -> config.getCredential().getToken(ctx).block().getToken()));
        this.managedIdentityImageClient = managedImageBuilder.build();

        String apiKey = config.getOpenAIApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            this.apiKeyFallbackImageClient = OpenAIOkHttpClient.builder()
                    .baseUrl(endpoint)
                    .azureUrlPathMode(AzureUrlPathMode.UNIFIED)
                    .azureServiceVersion(AzureOpenAIServiceVersion.fromString(IMAGE_API_VERSION))
                    .apiKey(apiKey)
                    .build();
        } else {
            this.apiKeyFallbackImageClient = null;
        }
    }

    public ChatResponsePayload chat(String message, List<ChatTurn> history, byte[] imageBytes, String imageFilename) {
        try {
            List<Map<String, Object>> inputItems = new ArrayList<>();

            for (ChatTurn turn : history) {
                if (turn == null || turn.role() == null || turn.content() == null || turn.content().isBlank()) {
                    continue;
                }
                String role = turn.role().trim().toLowerCase();
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }

                String combined = turn.content();
                if ("assistant".equals(role) && turn.imageSummary() != null
                        && !turn.imageSummary().isBlank() && !"NONE".equalsIgnoreCase(turn.imageSummary())) {
                    combined = combined + "\n\n[IMAGE_SUMMARY]\n" + turn.imageSummary().trim();
                }

                Map<String, Object> msg = new HashMap<>();
                msg.put("role", role);
                msg.put("content", combined);
                inputItems.add(msg);
            }

            boolean usedImage = imageBytes != null && imageBytes.length > 0;
            Map<String, Object> currentMessage = new HashMap<>();
            currentMessage.put("role", "user");
            if (usedImage) {
                String mime = contentTypeFromFilename(imageFilename);
                String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
                List<Map<String, Object>> content = new ArrayList<>();
                Map<String, Object> textPart = new HashMap<>();
                textPart.put("type", "input_text");
                textPart.put("text", message);
                content.add(textPart);
                Map<String, Object> imagePart = new HashMap<>();
                imagePart.put("type", "input_image");
                imagePart.put("image_url", dataUrl);
                content.add(imagePart);
                currentMessage.put("content", content);
            } else {
                currentMessage.put("content", message);
            }
            inputItems.add(currentMessage);

            String plainText = callChatResponsesApi(inputItems);
            log.info("Plain chat output: {}", plainText.length() > 800 ? plainText.substring(0, 800) : plainText);
            ChatOutput out = chatResponseParser.parse(plainText);

            String assistantReply = safe(out.assistant_reply);
            String imageSummary = safe(out.image_summary);
            List<String> actions = out.improvement_actions == null ? List.of()
                    : out.improvement_actions.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::trim)
                    .collect(Collectors.toList());
            String bestPrompt = safe(out.best_prompt_candidate);

            if (!usedImage) {
                imageSummary = "NONE";
                actions = List.of();
                bestPrompt = "";
            }

            return new ChatResponsePayload(assistantReply, imageSummary, actions, bestPrompt, usedImage);
        } catch (OpenAIServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Chat request failed: " + e.getMessage(), e);
        }
    }

    private String callChatResponsesApi(List<Map<String, Object>> inputItems) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", chatDeployment);
        body.put("instructions", CHAT_SYSTEM_PROMPT_PLAIN);
        body.put("input", inputItems);

        String bodyJson;
        try {
            bodyJson = JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize chat request", e);
        }

        try {
            String bearer = tokenCredential.getToken(new TokenRequestContext()
                            .addScopes("https://cognitiveservices.azure.com/.default"))
                    .block().getToken();
            return sendChatHttp(bodyJson, "Authorization", "Bearer " + bearer);
        } catch (Exception e) {
            if (configuredApiKey == null || configuredApiKey.isBlank()) {
                throw new RuntimeException("Managed identity chat auth failed and no API key fallback configured", e);
            }
            log.warn("Managed identity chat auth failed. Retrying chat with API key fallback.");
            return sendChatHttp(bodyJson, "api-key", configuredApiKey);
        }
    }

    private String sendChatHttp(String bodyJson, String authHeader, String authValue) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(chatResponsesUrl))
                    .header("Content-Type", "application/json")
                    .header(authHeader, authValue)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                String msg = response.body();
                try {
                    JsonNode err = JSON.readTree(response.body()).path("error");
                    String detail = err.path("message").asText();
                    if (detail != null && !detail.isBlank()) {
                        msg = detail;
                    }
                } catch (Exception ignored) {
                }
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + msg);
            }

            JsonNode root = JSON.readTree(response.body());
            String outputText = extractOutputTextFromResponsesJson(root);
            if (outputText.isBlank()) {
                throw new RuntimeException("No text output in chat response");
            }
            return outputText;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Chat HTTP request failed", e);
        }
    }

    private String extractOutputTextFromResponsesJson(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                if (!"message".equals(item.path("type").asText())) continue;
                JsonNode content = item.path("content");
                if (!content.isArray()) continue;
                for (JsonNode part : content) {
                    if ("output_text".equals(part.path("type").asText())) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(part.path("text").asText(""));
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    public List<byte[]> generateImage(String prompt, String size, String outputFormat, int n) {
        var params = ImageGenerateParams.builder()
                .prompt(prompt)
                .model(deployment)
                .n((long) n)
                .size(ImageGenerateParams.Size.of(size))
                .quality(ImageGenerateParams.Quality.HIGH)
                .outputFormat(ImageGenerateParams.OutputFormat.of(outputFormat));

        if (isCompressedFormat(outputFormat)) {
            params.outputCompression(OUTPUT_COMPRESSION);
        }

        return executeWithPreferredAuth(managedIdentityImageClient, apiKeyFallbackImageClient,
                c -> extractAllImageData(c.images().generate(params.build())));
    }

    public List<byte[]> editImage(String prompt, String size, List<byte[]> images,
                            List<String> filenames, byte[] mask, String outputFormat, int n) {
        try {
            var paramsBuilder = ImageEditParams.builder()
                    .prompt(prompt)
                    .model(deployment)
                    .n((long) n)
                    .size(ImageEditParams.Size.of(size))
                    .quality(ImageEditParams.Quality.HIGH)
                    .inputFidelity(ImageEditParams.InputFidelity.HIGH)
                    .outputFormat(ImageEditParams.OutputFormat.of(outputFormat));

            if (isCompressedFormat(outputFormat)) {
                paramsBuilder.outputCompression(OUTPUT_COMPRESSION);
            }

            String firstName = (filenames != null && !filenames.isEmpty()) ? filenames.get(0) : "image.jpg";
            log.info("editImage: {} image(s), first='{}' bytes={}, size={}, format={}",
                    images.size(), firstName, images.get(0).length, size, outputFormat);

            if (images.size() == 1) {
                paramsBuilder.image(MultipartField.<Image>builder()
                        .value(Image.ofInputStream(new ByteArrayInputStream(images.get(0))))
                        .contentType(contentTypeFromFilename(firstName))
                        .filename(firstName)
                        .build());
            } else {
                List<InputStream> streams = images.stream()
                        .map(b -> (InputStream) new ByteArrayInputStream(b))
                        .collect(java.util.stream.Collectors.toList());
                paramsBuilder.image(MultipartField.<Image>builder()
                        .value(Image.ofInputStreams(streams))
                        .contentType(contentTypeFromFilename(firstName))
                        .filename(firstName)
                        .build());
            }

            if (mask != null) {
                paramsBuilder.mask(MultipartField.<InputStream>builder()
                        .value(new ByteArrayInputStream(mask))
                        .contentType("image/png")
                        .filename("mask.png")
                        .build());
            }

            return executeWithPreferredAuth(managedIdentityImageClient, apiKeyFallbackImageClient,
                    c -> extractAllImageData(c.images().edit(paramsBuilder.build())));
        } catch (OpenAIServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to prepare image edit request: " + e.getMessage(), e);
        }
    }

    private String contentTypeFromFilename(String filename) {
        if (filename == null) return "image/jpeg";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private List<byte[]> extractAllImageData(ImagesResponse response) {
        List<com.openai.models.images.Image> images = response.data()
                .orElseThrow(() -> new RuntimeException("No data in OpenAI response"));
        List<byte[]> results = new ArrayList<>();
        for (com.openai.models.images.Image image : images) {
            Optional<String> b64 = image.b64Json();
            if (b64.isPresent() && !b64.get().isEmpty()) {
                results.add(Base64.getDecoder().decode(b64.get()));
                continue;
            }
            Optional<String> url = image.url();
            if (url.isPresent() && !url.get().isEmpty()) {
                results.add(downloadFromUrl(url.get()));
                continue;
            }
            throw new RuntimeException("No image data in OpenAI response for one of the images");
        }
        if (results.isEmpty()) {
            throw new RuntimeException("No image data in OpenAI response");
        }
        return results;
    }

    private boolean isCompressedFormat(String format) {
        return "jpeg".equalsIgnoreCase(format) || "webp".equalsIgnoreCase(format);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private <T> T executeWithPreferredAuth(OpenAIClient primaryClient, OpenAIClient fallbackClient,
                                           Function<OpenAIClient, T> request) {
        try {
            return request.apply(primaryClient);
        } catch (OpenAIServiceException e) {
            boolean canFallback = (e.statusCode() == 401 || e.statusCode() == 403)
                    && fallbackClient != null;
            if (!canFallback) {
                throw e;
            }
            log.warn("Managed identity auth failed (HTTP {}). Retrying with API key fallback.", e.statusCode());
            return request.apply(fallbackClient);
        }
    }

    private byte[] downloadFromUrl(String url) {
        try (InputStream in = new URI(url).toURL().openStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download image from URL", e);
        }
    }

}
