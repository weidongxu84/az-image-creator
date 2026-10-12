package io.weidongxu.webapp.imagecreator;

import com.azure.core.exception.HttpResponseException;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
            SavedPromptDetails saved = service.save(request);
            return ResponseEntity.status(HttpStatus.CREATED).eTag(saved.etag()).body(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (HttpResponseException e) {
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
    public ResponseEntity<?> delete(@RequestParam String name, @RequestHeader("If-Match") String etag) {
        try {
            return service.delete(name, etag) ? ResponseEntity.noContent().build()
                    : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Saved prompt not found."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (HttpResponseException e) {
            return storageFailure(e);
        }
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody SavedPrompt request, @RequestHeader("If-Match") String etag) {
        try {
            SavedPromptDetails saved = service.update(request, etag);
            return ResponseEntity.ok().eTag(saved.etag()).body(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (HttpResponseException e) {
            return storageFailure(e);
        }
    }

    @PostMapping("/rename")
    public ResponseEntity<?> rename(@RequestBody SavedPromptRenameRequest request,
            @RequestHeader("If-Match") String etag) {
        try {
            SavedPromptDetails saved = service.rename(request, etag);
            return ResponseEntity.ok().eTag(saved.etag()).body(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (HttpResponseException e) {
            return storageFailure(e);
        }
    }

    @ExceptionHandler(SavedPromptNameConflictException.class)
    public ResponseEntity<?> nameConflict(SavedPromptNameConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(SavedPromptRenameException.class)
    public ResponseEntity<?> renameFailure(SavedPromptRenameException e) {
        log.error("Saved prompt rename could not finish reliably", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<?> missingEtag(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "If-Match is required. Refresh the saved prompt and try again."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> missingName(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Name is required."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalidJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid JSON request body is required."));
    }

    private ResponseEntity<?> storageFailure(HttpResponseException e) {
        if (e instanceof BlobStorageException storageException) {
            if (storageException.getStatusCode() == 404) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Saved prompt not found. Refresh and try again."));
            }
            if (storageException.getStatusCode() == 412
                    || BlobErrorCode.CONDITION_NOT_MET.equals(storageException.getErrorCode())) {
                return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                        .body(Map.of("error", "The prompt changed in another session. Refresh before trying again."));
            }
        }
        log.error("Saved prompt storage operation failed", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Saved prompt storage is unavailable. Please try again."));
    }
}
