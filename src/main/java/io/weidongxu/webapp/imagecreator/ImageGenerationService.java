package io.weidongxu.webapp.imagecreator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
public class ImageGenerationService {

    @Autowired
    private OpenAIService openAIService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private JobStore jobStore;

    @Async
    public void generateImage(String jobId, String prompt, String size, List<MultipartFile> images) {
        jobStore.setRunning(jobId);
        try {
            byte[] imageData;
            if (images != null && !images.isEmpty()) {
                log.info("Job {}: editing {} image(s), size={}", jobId, images.size(), size);
                imageData = openAIService.editImage(prompt, size, images);
            } else {
                log.info("Job {}: generating new image, size={}", jobId, size);
                imageData = openAIService.generateImage(prompt, size);
            }
            String blobName = storageService.upload(imageData, prompt);
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
