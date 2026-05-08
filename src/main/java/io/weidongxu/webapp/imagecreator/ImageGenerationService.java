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
            String userMessage = classifyOpenAIError(e);
            log.warn("Job {}: OpenAI error HTTP {} code={} type={} message={}",
                    jobId, e.statusCode(), e.code().orElse(""), e.type().orElse(""), e.getMessage());
            jobStore.setFailed(jobId, userMessage);
        } catch (Exception e) {
            log.error("Job {}: unexpected error", jobId, e);
            jobStore.setFailed(jobId, "Internal error: " + e.getMessage());
        }
    }

    private String classifyOpenAIError(OpenAIServiceException e) {
        String code = e.code().orElse("").toLowerCase();
        int status = e.statusCode();
        String message = e.getMessage();
        String detail = (message != null && !message.isBlank()) ? message.trim()
                : "API error " + status;

        if ("moderation_blocked".equals(code)
                || "contentfilter".equals(code)
                || "content_filter".equals(code)
                || detail.toLowerCase().contains("safety")) {
            return "[content_policy] " + detail;
        }
        if (status == 429 || "ratelimitreached".equals(code)) {
            return "[rate_limit] " + detail;
        }
        return detail;
    }
}
