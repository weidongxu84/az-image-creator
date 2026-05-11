package io.weidongxu.webapp.imagecreator;

import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    @Autowired
    private OpenAIService openAIService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private JobStore jobStore;

    @Async
    public void generateImage(String jobId, String prompt, String size,
                              List<byte[]> images, List<String> imageFilenames,
                              byte[] mask, String outputFormat, int n) {
        jobStore.setRunning(jobId);
        try {
            List<byte[]> imageDataList;
            if (images != null && !images.isEmpty()) {
                log.info("Job {}: editing {} image(s), size={}, format={}, n={}", jobId, images.size(), size, outputFormat, n);
                imageDataList = openAIService.editImage(prompt, size, images, imageFilenames, mask, outputFormat, n);
            } else {
                log.info("Job {}: generating new image, size={}, format={}, n={}", jobId, size, outputFormat, n);
                imageDataList = openAIService.generateImage(prompt, size, outputFormat, n);
            }
            List<String> blobNames = new ArrayList<>();
            for (byte[] imageData : imageDataList) {
                String blobName = storageService.upload(imageData, prompt, outputFormat);
                blobNames.add(blobName);
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
