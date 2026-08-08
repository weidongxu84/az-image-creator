package io.weidongxu.webapp.imagecreator;

import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    private final OpenAIService openAIService;
    private final FluxService fluxService;
    private final StorageService storageService;
    private final PromptStorageService promptStorageService;
    private final JobStore jobStore;
    private final AppConfig config;

    public ImageGenerationService(OpenAIService openAIService, FluxService fluxService,
                                  StorageService storageService, PromptStorageService promptStorageService,
                                  JobStore jobStore, AppConfig config) {
        this.openAIService = openAIService;
        this.fluxService = fluxService;
        this.storageService = storageService;
        this.promptStorageService = promptStorageService;
        this.jobStore = jobStore;
        this.config = config;
    }

    @Async
    public void generateImage(String jobId, String model, String prompt, String size,
                              List<byte[]> images, List<String> imageFilenames,
                              byte[] mask, String outputFormat, int n, String inputFidelity) {
        jobStore.setRunning(jobId);
        boolean useFlux = fluxService.isConfigured() && isFluxModel(model);
        log.info("Job {}: model={}, routed to {}, hasImages={}, size={}, format={}, n={}",
                jobId, model, useFlux ? "FluxService" : "OpenAIService",
                images != null && !images.isEmpty(), size, outputFormat, n);
        List<String> blobNames = new ArrayList<>();
        try {
            List<byte[]> imageDataList;
            if (useFlux) {
                // Route to FLUX.2 service
                if (images != null && !images.isEmpty()) {
                    log.info("Job {}: FLUX editing {} image(s), size={}, format={}, n={}", jobId, images.size(), size, outputFormat, n);
                    imageDataList = fluxService.editImage(prompt, size, images, outputFormat, n);
                } else {
                    log.info("Job {}: FLUX generating new image, size={}, format={}, n={}", jobId, size, outputFormat, n);
                    imageDataList = fluxService.generateImage(prompt, size, outputFormat, n);
                }
            } else if (images != null && !images.isEmpty()) {
                log.info("Job {}: editing {} image(s), size={}, format={}, n={}, inputFidelity={}", jobId, images.size(), size, outputFormat, n, inputFidelity);
                imageDataList = openAIService.editImage(prompt, size, images, imageFilenames, mask, outputFormat, n, inputFidelity);
            } else {
                log.info("Job {}: generating new image, size={}, format={}, n={}", jobId, size, outputFormat, n);
                imageDataList = openAIService.generateImage(prompt, size, outputFormat, n);
            }
            String uploadFormat = useFlux ? mapFluxOutputFormat(outputFormat) : outputFormat;
            boolean isEdit = images != null && !images.isEmpty();
            String provider = useFlux ? "flux"
                    : config.isUseAlternateImageEndpoint() ? "azure-openai-alternate" : "azure-openai";
            String effectiveModel = useFlux ? config.getFluxDeployment()
                    : config.isUseAlternateImageEndpoint()
                            ? config.getAlternateImageDeployment()
                            : config.getOpenAIDeployment();
            int referenceImageCount = isEdit ? images.size() : 0;
            for (byte[] imageData : imageDataList) {
                String blobName = storageService.upload(imageData, uploadFormat);
                blobNames.add(blobName);
                try {
                    promptStorageService.save(new ImagePrompt(
                            blobName, prompt, java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                            effectiveModel, provider, uploadFormat, isEdit ? "edit" : "generate",
                            isEdit && !useFlux ? inputFidelity : null, jobId, referenceImageCount));
                } catch (Exception e) {
                    log.error("Job {}: image {} saved, but prompt persistence failed", jobId, blobName, e);
                }
            }
            log.info("Job {}: completed, saved {} image(s): {}", jobId, blobNames.size(), blobNames);
            jobStore.setCompleted(jobId, blobNames);

        } catch (OpenAIServiceException e) {
            int status = safeStatusCode(e);
            String code = safeOptional(e::code);
            String type = safeOptional(e::type);
            String detail = safeMessage(e, status);
            String userMessage = classifyOpenAIError(status, code, detail);
            log.warn("Job {}: OpenAI error HTTP {} code={} type={} message={}",
                    jobId, status, code, type, detail);
            jobStore.setFailed(jobId, userMessage);
        } catch (Exception e) {
            log.error("Job {}: unexpected error", jobId, e);
            jobStore.setFailed(jobId, "Internal error: " + e.getMessage());
        }
    }

    private boolean isFluxModel(String model) {
        return model != null && model.toLowerCase().startsWith("flux");
    }

    /** FLUX only supports png and jpeg; webp falls back to png. */
    private String mapFluxOutputFormat(String format) {
        if ("jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format)) {
            return "jpeg";
        }
        return "png";
    }

    private String classifyOpenAIError(int status, String code, String detail) {
        String normalizedCode = code == null ? "" : code.toLowerCase();

        if ("moderation_blocked".equals(normalizedCode)
                || "contentfilter".equals(normalizedCode)
                || "content_filter".equals(normalizedCode)
                || detail.toLowerCase().contains("safety")) {
            return "[content_policy] " + detail;
        }
        if (status == 429 || "ratelimitreached".equals(normalizedCode)) {
            return "[rate_limit] " + detail;
        }
        return detail;
    }

    private int safeStatusCode(OpenAIServiceException e) {
        try {
            return e.statusCode();
        } catch (Exception ignored) {
            return 500;
        }
    }

    private String safeMessage(OpenAIServiceException e, int status) {
        try {
            String message = e.getMessage();
            if (message != null && !message.isBlank()) {
                return message.trim();
            }
        } catch (Exception ignored) {
        }
        return "API error " + status;
    }

    private String safeOptional(java.util.function.Supplier<java.util.Optional<String>> reader) {
        try {
            return reader.get().orElse("");
        } catch (Exception ignored) {
            return "";
        }
    }
}
