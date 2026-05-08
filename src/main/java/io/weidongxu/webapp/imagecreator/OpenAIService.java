package io.weidongxu.webapp.imagecreator;

import com.azure.core.credential.TokenRequestContext;
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
    private static final long OUTPUT_COMPRESSION = 95L;

    private final String deployment;
    private final OpenAIClient client;

    public OpenAIService(AppConfig config) {
        this.deployment = config.getOpenAIDeployment();

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

        return extractAllImageData(client.images().generate(params.build()));
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

            return extractAllImageData(client.images().edit(paramsBuilder.build()));
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

    private byte[] downloadFromUrl(String url) {
        try (InputStream in = new URI(url).toURL().openStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download image from URL", e);
        }
    }

}
