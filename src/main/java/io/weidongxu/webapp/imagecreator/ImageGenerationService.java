package io.weidongxu.webapp.imagecreator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
                              List<byte[]> images, byte[] mask, String outputFormat) {
        jobStore.setRunning(jobId);
        try {
            byte[] imageData;
            if (images != null && !images.isEmpty()) {
                log.info("Job {}: editing {} image(s), size={}, format={}", jobId, images.size(), size, outputFormat);
                imageData = openAIService.editImage(prompt, size, images, mask, outputFormat);
            } else {
                log.info("Job {}: generating new image, size={}, format={}", jobId, size, outputFormat);
                imageData = openAIService.generateImage(prompt, size, outputFormat);
            }
            String blobName = storageService.upload(imageData, prompt, outputFormat);
            log.info("Job {}: completed, saved as {}", jobId, blobName);
            jobStore.setCompleted(jobId, blobName);

        } catch (OpenAIException e) {
            log.warn("Job {}: OpenAI error (HTTP {}): {}", jobId, e.getStatusCode(), e.getMessage());
            jobStore.setFailed(jobId, e.getMessage());
        } catch (Exception e) {
            log.error("Job {}: unexpected error", jobId, e);
            jobStore.setFailed(jobId, "Internal error: " + e.getMessage());
        }
    }
}
