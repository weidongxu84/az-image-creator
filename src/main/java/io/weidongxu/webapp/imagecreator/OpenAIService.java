package io.weidongxu.webapp.imagecreator;

import com.azure.core.credential.TokenRequestContext;
import com.openai.azure.AzureOpenAIServiceVersion;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.credential.BearerTokenCredential;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import com.openai.core.MultipartField;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
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
    private static final String IMAGE_API_VERSION = "2025-04-01-preview";
    private static final long OUTPUT_COMPRESSION = 95L;
    private static final String CHAT_SYSTEM_PROMPT_JSON = """
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
            - If an image is provided, best_prompt_candidate must be a single prompt for gpt-image-2
              to EDIT the uploaded image (image-to-image), not to generate a brand new image.
            - Preserve original subject/composition unless user asks to change them.
            - Do not wrap JSON in markdown fences.
            """;

    private static final String CHAT_SYSTEM_PROMPT_FALLBACK = """
            You are an expert creative image consultant and prompt engineer.

            Answer the user naturally and helpfully.
            If the current user turn contains an image, include practical critique
            and concrete improvement advice.
            """;
    private static final String ORIENTATION_VALIDATION_PROMPT = """
            Determine the canvas orientation explicitly intended by an image-generation prompt.

            You MUST output valid JSON with this exact schema:
            {
              "intended_orientation": "landscape|portrait|square|unspecified",
              "selected_orientation": "landscape|portrait|square",
              "matches": true,
              "confidence": "high|medium|low",
              "reason": "short explanation"
            }

            Rules:
            - Use "high" confidence only when the prompt explicitly states an orientation, aspect
              ratio, dimensions, or clearly equivalent composition such as vertical/full-body
              portrait or wide/panoramic landscape.
            - Use "unspecified" when the prompt does not clearly imply a canvas orientation.
            - Copy the supplied selected orientation into selected_orientation.
            - Set matches according to whether the intended and selected orientations agree.
            - Do not infer portrait merely because a person is the subject.
            - Do not wrap JSON in markdown fences.
            """;

    private final String deployment;
    private final String chatDeployment;
    private final ChatResponseMapper chatResponseMapper;
    private final OpenAIClient managedIdentityImageClient;
    private final OpenAIClient managedIdentityChatClient;
    private final OpenAIClient apiKeyFallbackImageClient;
    private final OpenAIClient apiKeyFallbackChatClient;

    public OpenAIService(AppConfig config, ChatResponseMapper chatResponseMapper) {
        this.deployment = config.getOpenAIDeployment();
        this.chatDeployment = config.getOpenAIChatDeployment();
        String endpoint = config.getOpenAIEndpoint();
        String trimmedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.chatResponseMapper = chatResponseMapper;
        String chatBaseUrl = trimmedEndpoint + "/openai/v1";

        var managedImageBuilder = OpenAIOkHttpClient.builder()
                .baseUrl(endpoint)
                .azureServiceVersion(AzureOpenAIServiceVersion.fromString(IMAGE_API_VERSION));

        var managedChatBuilder = OpenAIOkHttpClient.builder()
                .baseUrl(chatBaseUrl);

        TokenRequestContext ctx = new TokenRequestContext()
                .addScopes("https://cognitiveservices.azure.com/.default");
        managedImageBuilder.apiKey("none")
                .credential(BearerTokenCredential.create(
                        () -> config.getCredential().getToken(ctx).block().getToken()));
        this.managedIdentityImageClient = managedImageBuilder.build();

        managedChatBuilder.apiKey("none")
                .credential(BearerTokenCredential.create(
                        () -> config.getCredential().getToken(ctx).block().getToken()));
        this.managedIdentityChatClient = managedChatBuilder.build();

        String apiKey = config.getOpenAIApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            this.apiKeyFallbackImageClient = OpenAIOkHttpClient.builder()
                    .baseUrl(endpoint)
                    .azureServiceVersion(AzureOpenAIServiceVersion.fromString(IMAGE_API_VERSION))
                    .apiKey(apiKey)
                    .build();
            this.apiKeyFallbackChatClient = OpenAIOkHttpClient.builder()
                    .baseUrl(chatBaseUrl)
                    .apiKey(apiKey)
                    .build();
        } else {
            this.apiKeyFallbackImageClient = null;
            this.apiKeyFallbackChatClient = null;
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

            boolean usedImage = imageBytes != null && imageBytes.length > 0;
            var currentMessage = ResponseInputItem.Message.builder()
                    .role(ResponseInputItem.Message.Role.USER)
                    .addInputTextContent(message);

            if (usedImage) {
                String mime = contentTypeFromFilename(imageFilename);
                String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
                currentMessage.addContent(ResponseInputImage.builder()
                        .detail(ResponseInputImage.Detail.AUTO)
                        .imageUrl(dataUrl)
                        .build());
            }
            inputItems.add(ResponseInputItem.ofMessage(currentMessage.build()));

            ChatOutput out = callStructuredChat(inputItems, usedImage);
            return chatResponseMapper.toPayload(out, usedImage);
        } catch (OpenAIServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Chat request failed: " + e.getMessage(), e);
        }
    }

    public ImageOrientationValidation validateImageOrientation(String prompt, String size) {
        String selectedOrientation = ImageOrientationValidation.selectedOrientation(size);
        String input = """
                Selected size: %s
                Selected orientation: %s

                Image prompt:
                %s
                """.formatted(size, selectedOrientation, prompt);

        try {
            StructuredResponseCreateParams<ImageOrientationValidation> params = ResponseCreateParams.builder()
                    .model(chatDeployment)
                    .instructions(ORIENTATION_VALIDATION_PROMPT)
                    .input(input)
                    .text(ImageOrientationValidation.class)
                    .build();
            var response = executeWithPreferredAuth(managedIdentityChatClient, apiKeyFallbackChatClient,
                    c -> c.responses().create(params));

            ImageOrientationValidation result = response.output().stream()
                    .flatMap(item -> item.message().stream())
                    .flatMap(message -> message.content().stream())
                    .flatMap(content -> content.outputText().stream())
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new IllegalStateException(
                            "Orientation validation returned no structured output"));
            ImageOrientationValidation validation =
                    ImageOrientationValidation.enforcePolicy(result, size);
            log.info("Orientation validation: intended={}, selected={}, confidence={}, matches={}, reason={}",
                    validation.intended_orientation, validation.selected_orientation,
                    validation.confidence, validation.matches, validation.reason);
            return validation;
        } catch (OpenAIServiceException | OpenAIInvalidDataException | IllegalStateException e) {
            log.warn("Orientation validation unavailable; allowing image request: {}", e.getMessage());
            return ImageOrientationValidation.allowWhenUnavailable(size);
        }
    }

    public List<byte[]> generateImage(String prompt, String size, String outputFormat, int n) {
        var params = ImageGenerateParams.builder()
                .prompt(prompt)
                .model(deployment)
                .n((long) n)
                .size(ImageGenerateParams.Size.of(size))
                .quality(ImageGenerateParams.Quality.HIGH)
                .moderation(ImageGenerateParams.Moderation.LOW)
                .outputFormat(ImageGenerateParams.OutputFormat.of(outputFormat));

        if (isCompressedFormat(outputFormat)) {
            params.outputCompression(OUTPUT_COMPRESSION);
        }

        return executeWithPreferredAuth(managedIdentityImageClient, apiKeyFallbackImageClient,
                c -> extractAllImageData(c.images().generate(params.build())));
    }

    public List<byte[]> editImage(String prompt, String size, List<byte[]> images,
                            List<String> filenames, byte[] mask, String outputFormat, int n, String inputFidelity) {
        try {
            var paramsBuilder = ImageEditParams.builder()
                    .prompt(prompt)
                    .model(deployment)
                    .n((long) n)
                    .size(ImageEditParams.Size.of(size))
                    .quality(ImageEditParams.Quality.HIGH)
                    .inputFidelity(parseInputFidelity(inputFidelity))
                    .outputFormat(ImageEditParams.OutputFormat.of(outputFormat));

            if (isCompressedFormat(outputFormat)) {
                paramsBuilder.outputCompression(OUTPUT_COMPRESSION);
            }

            String firstName = (filenames != null && !filenames.isEmpty()) ? filenames.get(0) : "image.jpg";
            log.info("editImage: {} image(s), first='{}' bytes={}, size={}, format={}, inputFidelity={}",
                    images.size(), firstName, images.get(0).length, size, outputFormat, inputFidelity);

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

    private ImageEditParams.InputFidelity parseInputFidelity(String value) {
        if ("high".equalsIgnoreCase(value)) {
            return ImageEditParams.InputFidelity.HIGH;
        }
        return ImageEditParams.InputFidelity.LOW;
    }

    private ChatOutput callStructuredChat(List<ResponseInputItem> inputItems, boolean usedImage) {
        try {
            StructuredResponseCreateParams<ChatOutput> params = ResponseCreateParams.builder()
                    .model(chatDeployment)
                    .instructions(CHAT_SYSTEM_PROMPT_JSON)
                    .inputOfResponse(inputItems)
                    .text(ChatOutput.class)
                    .build();
            var response = executeWithPreferredAuth(managedIdentityChatClient, apiKeyFallbackChatClient,
                    c -> c.responses().create(params));

            List<ChatOutput> outputs = response.output().stream()
                    .flatMap(item -> item.message().stream())
                    .flatMap(message -> message.content().stream())
                    .flatMap(content -> content.outputText().stream())
                    .collect(Collectors.toList());
            if (!outputs.isEmpty()) {
                return outputs.get(outputs.size() - 1);
            }

            log.warn("Structured response missing parsed output; using text fallback.");
            return callRawTextFallback(inputItems, usedImage);
        } catch (OpenAIServiceException e) {
            if (!shouldFallbackToRawText(e)) {
                throw e;
            }
            log.warn("Structured chat unsupported/rejected; using text fallback. HTTP {}", e.statusCode());
            return callRawTextFallback(inputItems, usedImage);
        } catch (Exception e) {
            log.warn("Structured chat parse failed; using text fallback: {}", e.getMessage());
            return callRawTextFallback(inputItems, usedImage);
        }
    }

    private ChatOutput callRawTextFallback(List<ResponseInputItem> inputItems, boolean usedImage) {
        var params = ResponseCreateParams.builder()
                .model(chatDeployment)
                .instructions(CHAT_SYSTEM_PROMPT_FALLBACK)
                .inputOfResponse(inputItems)
                .build();
        var response = executeWithPreferredAuth(managedIdentityChatClient, apiKeyFallbackChatClient,
                c -> c.responses().create(params));
        String text = extractOutputText(response);
        return chatResponseMapper.fromRawTextFallback(text, usedImage);
    }

    private boolean shouldFallbackToRawText(OpenAIServiceException e) {
        int status = e.statusCode();
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (status == 400 || status == 404 || status == 422) {
            return msg.contains("response_format")
                    || msg.contains("json schema")
                    || msg.contains("structured")
                    || msg.contains("text.format")
                    || msg.contains("invalid type for 'text'")
                    || msg.contains("unsupported");
        }
        return false;
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
