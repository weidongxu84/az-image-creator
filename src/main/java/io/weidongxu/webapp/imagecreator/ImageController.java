package io.weidongxu.webapp.imagecreator;

import com.azure.storage.blob.models.BlobStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.openai.errors.OpenAIServiceException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.io.IOException;

@RestController
@RequestMapping("/api")
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    @Autowired
    private JobStore jobStore;

    @Autowired
    private ImageGenerationService imageGenerationService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private OpenAIService openAIService;

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> generate(
            @RequestParam("prompt") String prompt,
            @RequestParam(name = "size", required = false, defaultValue = "3264x2448") String size,
            @RequestParam(name = "outputFormat", required = false, defaultValue = "jpeg") String outputFormat,
            @RequestParam(name = "n", required = false, defaultValue = "1") int n,
            @RequestParam(name = "images", required = false) List<MultipartFile> images,
            @RequestParam(name = "mask", required = false) MultipartFile mask) throws IOException {

        if (n < 1) n = 1;
        if (n > 10) n = 10;

        // Read bytes eagerly in the HTTP request thread — MultipartFile temp files are
        // deleted when the request ends, so the @Async thread must not call getBytes() itself.
        List<byte[]> imageBytes = null;
        List<String> imageFilenames = null;
        if (images != null) {
            List<MultipartFile> valid = images.stream()
                    .filter(f -> f != null && !f.isEmpty())
                    .collect(Collectors.toList());
            if (!valid.isEmpty()) {
                imageBytes = valid.stream()
                        .map(f -> { try { return f.getBytes(); } catch (IOException e) { throw new RuntimeException(e); } })
                        .collect(Collectors.toList());
                imageFilenames = valid.stream()
                        .map(f -> f.getOriginalFilename() != null ? f.getOriginalFilename() : "image.jpg")
                        .collect(Collectors.toList());
            }
        }
        byte[] maskBytes = (mask != null && !mask.isEmpty()) ? mask.getBytes() : null;

        String jobId = jobStore.createJob();
        imageGenerationService.generateImage(jobId, prompt, size, imageBytes, imageFilenames, maskBytes, outputFormat, n);

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

    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatResponsePayload> chat(
            @RequestParam("message") String message,
            @RequestPart(name = "history", required = false) List<ChatTurn> history,
            @RequestParam(name = "image", required = false) MultipartFile image) throws IOException {

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(new ChatResponsePayload(
                    "Please provide a message.", "NONE", List.of(), "", false));
        }

        if (history == null) {
            history = List.of();
        }

        if (history.size() > 40) {
            history = history.subList(history.size() - 40, history.size());
        }

        byte[] imageBytes = (image != null && !image.isEmpty()) ? image.getBytes() : null;
        String imageFilename = (image != null) ? image.getOriginalFilename() : null;

        ChatResponsePayload response = openAIService.chat(message, history, imageBytes, imageFilename);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(OpenAIServiceException.class)
    public ResponseEntity<Map<String, String>> handleOpenAI(OpenAIServiceException e) {
        int status = e.statusCode();
        String detail = e.getMessage() == null || e.getMessage().isBlank()
                ? "OpenAI API error"
                : e.getMessage();
        String code = safeOptional(e::code);
        String type = safeOptional(e::type);
        log.warn("OpenAI API error HTTP {} code={} type={} message={}",
                status, code, type, detail);

        String userMsg;
        if (status == 429) {
            userMsg = "Rate limited by model endpoint. Please retry in a moment.";
        } else if (status == 401 || status == 403) {
            userMsg = "Model access denied. Please verify managed identity or API key permissions.";
        } else if (status >= 400 && status < 500) {
            userMsg = "Request rejected by model endpoint: " + detail;
        } else {
            userMsg = "Model request failed. Please retry.";
        }
        return ResponseEntity.status(502).body(Map.of("error", userMsg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unexpected API error", e);
        return ResponseEntity.status(500).body(Map.of("error", "Internal Server Error"));
    }

    private String safeOptional(java.util.function.Supplier<java.util.Optional<String>> reader) {
        try {
            return reader.get().orElse("");
        } catch (Exception ignored) {
            return "";
        }
    }

    @GetMapping("/images")
    public PagedImageResponse listImages(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "6") int size) {
        List<ImageInfo> all = storageService.listImages();
        long total = all.size();
        int totalPages = (int) Math.ceil((double) total / size);
        int fromIndex = Math.min(page * size, all.size());
        int toIndex = Math.min(fromIndex + size, all.size());
        List<ImageInfo> pageItems = all.subList(fromIndex, toIndex);
        return new PagedImageResponse(pageItems, page, totalPages, total);
    }

    @GetMapping("/images/{name}")
    public ResponseEntity<byte[]> downloadImage(@PathVariable String name) {
        try {
            byte[] data = storageService.download(name);
            MediaType contentType;
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                contentType = MediaType.IMAGE_JPEG;
            } else if (name.endsWith(".webp")) {
                contentType = MediaType.parseMediaType("image/webp");
            } else {
                contentType = MediaType.IMAGE_PNG;
            }
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                    .body(data);
        } catch (BlobStorageException e) {
            log.warn("Image not found: {}", name);
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/images/{name}")
    public ResponseEntity<Void> deleteImage(@PathVariable String name) {
        try {
            storageService.delete(name);
            return ResponseEntity.noContent().build();
        } catch (BlobStorageException e) {
            log.warn("Image not found for delete: {}", name);
            return ResponseEntity.notFound().build();
        }
    }

    /** Health ping, no auth required */
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.ok().build();
    }
}
