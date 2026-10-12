package io.weidongxu.webapp.imagecreator;

import com.azure.core.exception.HttpResponseException;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/saved-prompts")
public class SavedPromptController {

    private static final Logger log = LoggerFactory.getLogger(SavedPromptController.class);
    private final SavedPromptService service;

    public SavedPromptController(SavedPromptService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody SavedPrompt request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.save(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (HttpResponseException e) {
            if (e instanceof BlobStorageException storageException
                    && (BlobErrorCode.BLOB_ALREADY_EXISTS.equals(storageException.getErrorCode())
                    || BlobErrorCode.CONDITION_NOT_MET.equals(storageException.getErrorCode()))) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "That name already exists. Choose another name."));
            }
            return storageFailure(e);
        }
    }

    @GetMapping
    public ResponseEntity<?> list() {
        try {
            return ResponseEntity.ok(service.list());
        } catch (HttpResponseException e) {
            return storageFailure(e);
        }
    }

    @DeleteMapping
    public ResponseEntity<?> delete(@RequestParam String name) {
        try {
            return service.delete(name) ? ResponseEntity.noContent().build()
                    : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Saved prompt not found."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (HttpResponseException e) {
            return storageFailure(e);
        }
    }

    private ResponseEntity<?> storageFailure(HttpResponseException e) {
        log.error("Saved prompt storage operation failed", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Saved prompt storage is unavailable. Please try again."));
    }
}
