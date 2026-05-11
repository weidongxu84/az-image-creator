package io.weidongxu.webapp.imagecreator;

import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.azure.AzureOpenAIServiceVersion;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.credential.BearerTokenCredential;
import com.openai.errors.OpenAIServiceException;
import com.openai.core.MultipartField;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageEditParams.Image;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OpenAIService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAIService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_VERSION = "2025-04-01-preview";
    private static final long OUTPUT_COMPRESSION = 95L;
    private static final String CHAT_SYSTEM_PROMPT = """
            You are an expert creative image consultant and prompt engineer.
            
            Answer the user naturally and helpfully. If the current user turn contains an image,
            include practical critique and concrete improvement advice.
            
            You MUST output valid JSON with this exact schema:
            {
              "assistant_reply": "string",
              "image_summary": "string",
              "improvement_actions": ["string", "..."],
              "best_prompt_candidate": "string"
            }
            
            Rules:
            - Always return all fields.
            - If no image is provided in the current turn, set image_summary to "NONE",
              improvement_actions to an empty array, and best_prompt_candidate to "".
            - Keep image_summary compact and reusable for later turns.
            - Keep improvement_actions short and actionable.
            - Put user-facing explanation and recommendations in assistant_reply.
            - Do not wrap JSON in markdown fences.
            """;

    private final String deployment;
    private final String chatDeployment;
    private final OpenAIClient managedIdentityClient;
    private final OpenAIClient apiKeyFallbackClient;

    public OpenAIService(AppConfig config) {
        this.deployment = config.getOpenAIDeployment();
        this.chatDeployment = config.getOpenAIChatDeployment();

        var managedBuilder = OpenAIOkHttpClient.builder()
                .baseUrl(config.getOpenAIEndpoint())
                .azureServiceVersion(AzureOpenAIServiceVersion.fromString(API_VERSION));

        TokenRequestContext ctx = new TokenRequestContext()
                .addScopes("https://cognitiveservices.azure.com/.default");
        managedBuilder.apiKey("none")
                .credential(BearerTokenCredential.create(
                        () -> config.getCredential().getToken(ctx).block().getToken()));
        this.managedIdentityClient = managedBuilder.build();

        String apiKey = config.getOpenAIApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            this.apiKeyFallbackClient = OpenAIOkHttpClient.builder()
                    .baseUrl(config.getOpenAIEndpoint())
                    .azureServiceVersion(AzureOpenAIServiceVersion.fromString(API_VERSION))
                    .apiKey(apiKey)
                    .build();
        } else {
            this.apiKeyFallbackClient = null;
        }
    }

    public ChatResponsePayload chat(String message, List<ChatTurn> history, byte[] imageBytes, String imageFilename) {
        try {
            List<ResponseInputItem> inputItems = new ArrayList<>();

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

                inputItems.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                        .role("assistant".equals(role) ? EasyInputMessage.Role.ASSISTANT : EasyInputMessage.Role.USER)
                        .content(combined)
                        .build()));
            }

            var currentMessage = ResponseInputItem.Message.builder()
                    .role(ResponseInputItem.Message.Role.USER)
                    .addInputTextContent(message);

            boolean usedImage = imageBytes != null && imageBytes.length > 0;
            if (usedImage) {
                String mime = contentTypeFromFilename(imageFilename);
                String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
                currentMessage.addContent(ResponseInputImage.builder()
                        .detail(ResponseInputImage.Detail.AUTO)
                        .imageUrl(dataUrl)
                        .build());
            }

            inputItems.add(ResponseInputItem.ofMessage(currentMessage.build()));

            ChatOutput out;
            try {
                StructuredResponseCreateParams<ChatOutput> params = ResponseCreateParams.builder()
                        .model(chatDeployment)
                        .instructions(CHAT_SYSTEM_PROMPT)
                        .inputOfResponse(inputItems)
                        .text(ChatOutput.class)
                        .build();
                var response = executeWithPreferredAuth(c -> c.responses().create(params));
                List<ChatOutput> outputs = response.output().stream()
                        .flatMap(item -> item.message().stream())
                        .flatMap(messageItem -> messageItem.content().stream())
                        .flatMap(content -> content.outputText().stream())
                        .collect(Collectors.toList());
                if (outputs.isEmpty()) {
                    throw new RuntimeException("No chat output returned from model");
                }
                out = outputs.get(outputs.size() - 1);
            } catch (OpenAIServiceException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Structured chat parse failed; falling back to plain text parse: {}", e.getMessage());
                var plainParams = ResponseCreateParams.builder()
                        .model(chatDeployment)
                        .instructions(CHAT_SYSTEM_PROMPT)
                        .inputOfResponse(inputItems)
                        .build();
                var plainResponse = executeWithPreferredAuth(c -> c.responses().create(plainParams));
                out = parseChatOutputFromText(extractOutputText(plainResponse));
            }

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

        return executeWithPreferredAuth(c -> extractAllImageData(c.images().generate(params.build())));
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

            return executeWithPreferredAuth(c -> extractAllImageData(c.images().edit(paramsBuilder.build())));
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

    private ChatOutput parseChatOutputFromText(String text) {
        ChatOutput fallback = new ChatOutput();
        fallback.assistant_reply = safe(text);
        fallback.image_summary = "NONE";
        fallback.improvement_actions = List.of();
        fallback.best_prompt_candidate = "";

        String candidate = extractJsonCandidate(text);
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        try {
            ChatOutput parsed = JSON.readValue(candidate, ChatOutput.class);
            if (parsed == null) {
                return fallback;
            }
            if (parsed.assistant_reply == null || parsed.assistant_reply.isBlank()) {
                parsed.assistant_reply = fallback.assistant_reply;
            }
            if (parsed.image_summary == null || parsed.image_summary.isBlank()) {
                parsed.image_summary = "NONE";
            }
            if (parsed.improvement_actions == null) {
                parsed.improvement_actions = List.of();
            }
            if (parsed.best_prompt_candidate == null) {
                parsed.best_prompt_candidate = "";
            }
            return parsed;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String extractOutputText(com.openai.models.responses.Response response) {
        String text = response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"))
                .trim();
        if (!text.isBlank()) {
            return text;
        }
        return "Sorry, I could not parse the model output.";
    }

    private String extractJsonCandidate(String text) {
        String trimmed = safe(text);
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0 && trimmed.length() > firstNewline + 4) {
                trimmed = trimmed.substring(firstNewline + 1, trimmed.length() - 3).trim();
            }
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    private <T> T executeWithPreferredAuth(Function<OpenAIClient, T> request) {
        try {
            return request.apply(managedIdentityClient);
        } catch (OpenAIServiceException e) {
            boolean canFallback = (e.statusCode() == 401 || e.statusCode() == 403)
                    && apiKeyFallbackClient != null;
            if (!canFallback) {
                throw e;
            }
            log.warn("Managed identity auth failed (HTTP {}). Retrying with API key fallback.", e.statusCode());
            return request.apply(apiKeyFallbackClient);
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
