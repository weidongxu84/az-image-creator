package io.weidongxu.webapp.imagecreator;

import com.azure.storage.blob.models.BlobStorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
public class ImageController {

    @Autowired
    private JobStore jobStore;

    @Autowired
    private ImageGenerationService imageGenerationService;

    @Autowired
    private StorageService storageService;

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> generate(
            @RequestParam("prompt") String prompt,
            @RequestParam(name = "size", required = false, defaultValue = "2048x2048") String size,
            @RequestParam(name = "images", required = false) List<MultipartFile> images) {

        List<MultipartFile> validImages = images == null ? null
                : images.stream().filter(f -> f != null && !f.isEmpty()).collect(Collectors.toList());

        String jobId = jobStore.createJob();
        imageGenerationService.generateImage(jobId, prompt, size,
                (validImages != null && validImages.isEmpty()) ? null : validImages);

        log.info("Started job {} for prompt: {}", jobId, prompt.length() > 80
                ? prompt.substring(0, 80) + "…" : prompt);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobStatus> getJob(@PathVariable String jobId) {
        JobStatus status = jobStore.getJob(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/images")
    public List<ImageInfo> listImages() {
        return storageService.listImages();
    }

    @GetMapping("/images/{name}")
    public ResponseEntity<byte[]> downloadImage(@PathVariable String name) {
        try {
            byte[] data = storageService.download(name);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                    .body(data);
        } catch (BlobStorageException e) {
            log.warn("Image not found: {}", name);
            return ResponseEntity.notFound().build();
        }
    }

    /** Health ping, no auth required */
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.ok().build();
    }
}
