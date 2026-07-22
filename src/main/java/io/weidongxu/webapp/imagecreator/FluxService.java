package io.weidongxu.webapp.imagecreator;

import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class FluxService {

    private static final Logger log = LoggerFactory.getLogger(FluxService.class);
    private static final String API_VERSION = "preview";
    private static final int SAFETY_TOLERANCE = 2;

    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public FluxService(AppConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Whether the FLUX endpoint is configured.
     */
    public boolean isConfigured() {
        String endpoint = config.getFluxEndpoint();
        return endpoint != null && !endpoint.isBlank();
    }

    /**
     * Whether the configured FLUX deployment name looks like a FLUX model.
     */
    public boolean isFluxModel() {
        String deployment = config.getFluxDeployment();
        return deployment != null && deployment.toLowerCase().startsWith("flux");
    }

    /**
     * Generate images using the FLUX.2 API.
     */
    public List<byte[]> generateImage(String prompt, String size, String outputFormat, int n) {
        int[] dimensions = parseDimensions(size);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", prompt);
        body.put("width", dimensions[0]);
        body.put("height", dimensions[1]);
        body.put("num_images", n);
        body.put("model", config.getFluxDeployment());
        body.put("safety_tolerance", SAFETY_TOLERANCE);
        body.put("output_format", mapOutputFormat(outputFormat));

        log.info("FLUX generate: size={}x{}, n={}, format={}", dimensions[0], dimensions[1], n, outputFormat);
        return executeRequest(body);
    }

    /**
     * Edit images using the FLUX.2 API with input_image (supports multi-reference).
     */
    public List<byte[]> editImage(String prompt, String size, List<byte[]> images, String outputFormat, int n) {
        int[] dimensions = parseDimensions(size);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", prompt);
        body.put("width", dimensions[0]);
        body.put("height", dimensions[1]);
        body.put("num_images", n);
        body.put("model", config.getFluxDeployment());
        body.put("safety_tolerance", SAFETY_TOLERANCE);
        body.put("output_format", mapOutputFormat(outputFormat));

        // Multi-reference: input_image, input_image_2, input_image_3, ...
        if (images != null && !images.isEmpty()) {
            body.put("input_image", Base64.getEncoder().encodeToString(images.get(0)));
            for (int i = 1; i < images.size() && i < 8; i++) {
                body.put("input_image_" + (i + 1), Base64.getEncoder().encodeToString(images.get(i)));
            }
        }

        log.info("FLUX edit: size={}x{}, n={}, format={}, inputImages={}", dimensions[0], dimensions[1], n,
                outputFormat, images != null ? images.size() : 0);
        return executeRequest(body);
    }

    private List<byte[]> executeRequest(ObjectNode body) {
        String url = buildUrl();
        String token = acquireToken();

        // Log request body without base64 image data (too large)
        ObjectNode logBody = body.deepCopy();
        logBody.fieldNames().forEachRemaining(field -> {
            if (field.startsWith("input_image")) {
                logBody.put(field, "<base64:" + body.get(field).asText().length() + "chars>");
            }
        });
        log.info("FLUX request: url={}, body={}", url, logBody);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize FLUX request body", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            log.info("FLUX response: HTTP {}, bodyLength={}", response.statusCode(),
                    responseBody != null ? responseBody.length() : 0);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("FLUX API error: HTTP {} body={}", response.statusCode(), responseBody);
                throw new RuntimeException("FLUX API error HTTP " + response.statusCode() + ": " + responseBody);
            }

            return parseResponse(responseBody);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("FLUX API request failed: " + e.getMessage(), e);
        }
    }

    private List<byte[]> parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Log response structure (keys and types, without large data)
            log.info("FLUX response structure: keys={}", root.fieldNames().hasNext()
                    ? iteratorToString(root.fieldNames()) : "empty");

            ArrayNode dataArray = (ArrayNode) root.get("data");
            if (dataArray == null || dataArray.isEmpty()) {
                log.error("FLUX response has no data array. Full response: {}", responseBody);
                throw new RuntimeException("No data in FLUX response");
            }

            log.info("FLUX response: {} item(s) in data array", dataArray.size());

            List<byte[]> results = new ArrayList<>();
            for (int i = 0; i < dataArray.size(); i++) {
                JsonNode item = dataArray.get(i);
                JsonNode b64Node = item.get("b64_json");
                if (b64Node != null && !b64Node.asText().isEmpty()) {
                    log.info("FLUX response item[{}]: b64_json ({} chars)", i, b64Node.asText().length());
                    results.add(Base64.getDecoder().decode(b64Node.asText()));
                    continue;
                }
                JsonNode urlNode = item.get("url");
                if (urlNode != null && !urlNode.asText().isEmpty()) {
                    log.info("FLUX response item[{}]: url={}", i, urlNode.asText());
                    results.add(downloadFromUrl(urlNode.asText()));
                    continue;
                }
                log.error("FLUX response item[{}]: no b64_json or url. Keys: {}", i,
                        iteratorToString(item.fieldNames()));
                throw new RuntimeException("No b64_json or url in FLUX response item");
            }
            return results;
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse FLUX response: " + e.getMessage(), e);
        }
    }

    private String iteratorToString(java.util.Iterator<String> it) {
        var sb = new StringBuilder("[");
        while (it.hasNext()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(it.next());
        }
        return sb.append("]").toString();
    }

    private byte[] downloadFromUrl(String url) {
        try (var in = URI.create(url).toURL().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to download image from URL: " + e.getMessage(), e);
        }
    }

    private String buildUrl() {
        String endpoint = config.getFluxEndpoint();
        // Remove trailing slash
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint + "?api-version=" + API_VERSION;
    }

    private String acquireToken() {
        TokenRequestContext ctx = new TokenRequestContext()
                .addScopes("https://cognitiveservices.azure.com/.default");
        return config.getCredential().getToken(ctx).block().getToken();
    }

    private int[] parseDimensions(String size) {
        if (size != null && size.contains("x")) {
            String[] parts = size.split("x");
            try {
                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
            } catch (NumberFormatException ignored) {
            }
        }
        return new int[]{1024, 1024};
    }

    /**
     * Map output format to FLUX-supported values (png or jpeg).
     * WebP is not supported by FLUX, falls back to png.
     */
    private String mapOutputFormat(String format) {
        if ("jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format)) {
            return "jpeg";
        }
        return "png";
    }
}
