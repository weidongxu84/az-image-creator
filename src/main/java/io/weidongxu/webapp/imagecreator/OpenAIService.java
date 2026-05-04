package io.weidongxu.webapp.imagecreator;

import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class OpenAIService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAIService.class);
    private static final String API_VERSION = "2025-04-01-preview";
    private static final long OUTPUT_COMPRESSION = 90L;

    private final String deployment;
    private final OpenAIClient client;
    private final ObjectMapper objectMapper;

    public OpenAIService(AppConfig config, ObjectMapper objectMapper) {
        this.deployment = config.getOpenAIDeployment();
        this.objectMapper = objectMapper;

        var builder = OpenAIOkHttpClient.builder()
                .baseUrl(config.getOpenAIEndpoint())
                .azureServiceVersion(AzureOpenAIServiceVersion.fromString(API_VERSION));

        String apiKey = config.getOpenAIApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        } else {
            TokenRequestContext ctx = new TokenRequestContext()
                    .addScopes("https://cognitiveservices.azure.com/.default");
            builder.apiKey("none")
                    .credential(BearerTokenCredential.create(
                            () -> config.getCredential().getToken(ctx).block().getToken()));
        }

        this.client = builder.build();
    }

    public byte[] generateImage(String prompt, String size, String outputFormat) {
        var params = ImageGenerateParams.builder()
                .prompt(prompt)
                .model(deployment)
                .n(1L)
                .size(ImageGenerateParams.Size.of(size))
                .quality(ImageGenerateParams.Quality.HIGH)
                .outputFormat(ImageGenerateParams.OutputFormat.of(outputFormat));

        if (isCompressedFormat(outputFormat)) {
            params.outputCompression(OUTPUT_COMPRESSION);
        }

        try {
            return extractImageData(client.images().generate(params.build()));
        } catch (OpenAIServiceException e) {
            throw new OpenAIException(e.statusCode(),
                    parseErrorMessage(e.body().toString(), e.statusCode()));
        }
    }

    public byte[] editImage(String prompt, String size, List<byte[]> images,
                            List<String> filenames, byte[] mask, String outputFormat) {
        try {
            var paramsBuilder = ImageEditParams.builder()
                    .prompt(prompt)
                    .model(deployment)
                    .n(1L)
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

            try {
                return extractImageData(client.images().edit(paramsBuilder.build()));
            } catch (OpenAIServiceException e) {
                log.warn("editImage: HTTP {} raw body: {}", e.statusCode(), e.body());
                throw new OpenAIException(e.statusCode(),
                        parseErrorMessage(e.body().toString(), e.statusCode()));
            }
        } catch (OpenAIException e) {
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

    private byte[] extractImageData(ImagesResponse response) {
        com.openai.models.images.Image image = response.data()
                .orElseThrow(() -> new RuntimeException("No data in OpenAI response"))
                .get(0);

        Optional<String> b64 = image.b64Json();
        if (b64.isPresent() && !b64.get().isEmpty()) {
            return Base64.getDecoder().decode(b64.get());
        }

        Optional<String> url = image.url();
        if (url.isPresent() && !url.get().isEmpty()) {
            return downloadFromUrl(url.get());
        }

        throw new RuntimeException("No image data in OpenAI response");
    }

    private boolean isCompressedFormat(String format) {
        return "jpeg".equalsIgnoreCase(format) || "webp".equalsIgnoreCase(format);
    }

    private byte[] downloadFromUrl(String url) {
        try (InputStream in = new URI(url).toURL().openStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download image from URL", e);
        }
    }

    /**
     * Parses the OpenAI error response body and returns a human-readable message
     * prefixed with a category tag consumed by the UI for styled display.
     */
    private String parseErrorMessage(String errorBody, int statusCode) {
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                String code = errorNode.path("code").asText("");
                String message = errorNode.path("message").asText("").trim();
                String innermostMessage = errorNode.path("innererror")
                        .path("message").asText("").trim();
                String detail = innermostMessage.isEmpty() ? message : innermostMessage;

                if ("contentFilter".equalsIgnoreCase(code)
                        || "content_filter".equalsIgnoreCase(code)
                        || detail.toLowerCase().contains("content filter")
                        || detail.toLowerCase().contains("safety")) {
                    return "[content_policy] " + (detail.isEmpty()
                            ? "Your request was rejected by the content safety system. Try rephrasing your prompt."
                            : detail);
                }
                if (statusCode == 429
                        || "429".equals(code)
                        || "RateLimitReached".equalsIgnoreCase(code)) {
                    return "[rate_limit] " + (detail.isEmpty()
                            ? "Rate limit reached. Please wait a moment and try again."
                            : detail);
                }
                if (!detail.isEmpty()) {
                    return detail;
                }
            }
        } catch (Exception ignored) {
            // fall through to status-based defaults
        }

        return switch (statusCode) {
            case 400 -> "Bad request — check your prompt text and image inputs.";
            case 401 -> "Authentication failed — API credentials may be misconfigured.";
            case 403 -> "Access denied — the model or feature is not enabled for this endpoint.";
            case 429 -> "[rate_limit] Rate limit reached. Please wait a moment and try again.";
            case 500, 503 -> "The AI service returned a server error. Please try again shortly.";
            default -> "API error " + statusCode + ": "
                    + (errorBody.length() > 300 ? errorBody.substring(0, 300) + "…" : errorBody);
        };
    }
}
