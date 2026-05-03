package io.weidongxu.webapp.imagecreator;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenAIService {

    private static final String API_VERSION = "2025-04-01-preview";

    private final AppConfig config;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TokenRequestContext tokenRequestContext;

    public OpenAIService(AppConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.tokenRequestContext = new TokenRequestContext()
                .addScopes("https://cognitiveservices.azure.com/.default");

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMinutes(10));

        this.restClient = RestClient.builder()
                .baseUrl(config.getOpenAIEndpoint().replaceAll("/$", ""))
                .requestFactory(factory)
                .build();
    }

    private void setAuthHeaders(HttpHeaders headers) {
        String apiKey = config.getOpenAIApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("Api-Key", apiKey);
        } else {
            AccessToken token = config.getCredential()
                    .getToken(tokenRequestContext)
                    .block();
            headers.setBearerAuth(token.getToken());
        }
    }

    public byte[] generateImage(String prompt, String size) {
        String url = "/openai/deployments/" + config.getOpenAIDeployment()
                + "/images/generations?api-version=" + API_VERSION;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size);
        body.put("quality", "high");
        body.put("output_format", "png");

        String response = restClient.post()
                .uri(url)
                .headers(this::setAuthHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    byte[] bytes = resp.getBody().readAllBytes();
                    String errorBody = new String(bytes, StandardCharsets.UTF_8);
                    throw new OpenAIException(resp.getStatusCode().value(),
                            parseErrorMessage(errorBody, resp.getStatusCode().value()));
                })
                .body(String.class);

        return parseImageData(response);
    }

    public byte[] editImage(String prompt, String size, List<MultipartFile> images) throws IOException {
        String url = "/openai/deployments/" + config.getOpenAIDeployment()
                + "/images/edits?api-version=" + API_VERSION;

        MultiValueMap<String, Object> formData = new LinkedMultiValueMap<>();
        formData.add("prompt", prompt);
        formData.add("n", "1");
        formData.add("size", size);
        formData.add("quality", "high");

        for (MultipartFile image : images) {
            byte[] bytes = image.getBytes();
            final String filename = image.getOriginalFilename() != null
                    ? image.getOriginalFilename() : "image.png";
            formData.add("image", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
        }

        String response = restClient.post()
                .uri(url)
                .headers(this::setAuthHeaders)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(formData)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    byte[] bytes = resp.getBody().readAllBytes();
                    String errorBody = new String(bytes, StandardCharsets.UTF_8);
                    throw new OpenAIException(resp.getStatusCode().value(),
                            parseErrorMessage(errorBody, resp.getStatusCode().value()));
                })
                .body(String.class);

        return parseImageData(response);
    }

    private byte[] parseImageData(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode item = root.path("data").get(0);

            String b64Json = item.path("b64_json").asText(null);
            if (b64Json != null && !b64Json.isEmpty()) {
                return Base64.getDecoder().decode(b64Json);
            }

            // Fallback: download from SAS URL
            String imageUrl = item.path("url").asText(null);
            if (imageUrl != null && !imageUrl.isEmpty()) {
                return downloadFromUrl(imageUrl);
            }

            throw new RuntimeException("No image data in OpenAI response");
        } catch (OpenAIException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenAI response: " + e.getMessage(), e);
        }
    }

    private byte[] downloadFromUrl(String url) throws IOException {
        try (var in = new URI(url).toURL().openStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IOException("Failed to download image from URL", e);
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
