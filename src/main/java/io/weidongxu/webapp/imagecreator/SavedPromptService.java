package io.weidongxu.webapp.imagecreator;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobDownloadContentResponse;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class SavedPromptService {

    private final BlobContainerClient container;

    @Autowired
    public SavedPromptService(AppConfig config) {
        if (config.getStorageSavedPromptContainerName().equals(config.getStorageContainerName())) {
            throw new IllegalArgumentException("Saved prompts must use a separate container from images.");
        }
        container = StorageService.buildBlobServiceClient(config)
                .getBlobContainerClient(config.getStorageSavedPromptContainerName());
    }

    SavedPromptService(BlobContainerClient container) {
        this.container = container;
    }

    public SavedPromptDetails save(SavedPrompt request) {
        if (request == null) {
            throw new IllegalArgumentException("Name and prompt are required.");
        }
        String name = validateNewName(request.name());
        validatePrompt(request.prompt());
        SavedPrompt saved = new SavedPrompt(name, request.prompt());
        container.createIfNotExists();
        return create(container.getBlobClient(blobName(name)), saved);
    }

    public List<SavedPromptDetails> list() {
        container.createIfNotExists();
        return container.listBlobs().stream()
                .filter(item -> item.getName().endsWith(".json"))
                .map(item -> read(container.getBlobClient(item.getName()), null))
                .sorted(Comparator.comparing(SavedPromptDetails::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public SavedPromptDetails update(SavedPrompt request, String etag) {
        if (request == null) {
            throw new IllegalArgumentException("Name and prompt are required.");
        }
        String name = normalizeName(request.name());
        validatePrompt(request.prompt());
        BlobRequestConditions conditions = etagConditions(etag);
        BlobClient blob = container.getBlobClient(blobName(name));
        SavedPromptDetails current = read(blob, conditions);
        return write(blob, new SavedPrompt(current.name(), request.prompt()), conditions);
    }

    public boolean delete(String name, String etag) {
        String normalized = normalizeName(name);
        BlobRequestConditions conditions = etagConditions(etag);
        return container.getBlobClient(blobName(normalized))
                .deleteIfExistsWithResponse(null, conditions, null, null).getValue();
    }

    public SavedPromptDetails rename(SavedPromptRenameRequest request, String etag) {
        if (request == null) {
            throw new IllegalArgumentException("Current and new names are required.");
        }
        String oldName = normalizeName(request.name());
        String newName = validateNewName(request.newName());
        BlobRequestConditions conditions = etagConditions(etag);
        BlobClient source = container.getBlobClient(blobName(oldName));
        SavedPromptDetails current = read(source, conditions);
        SavedPrompt renamed = new SavedPrompt(newName, current.prompt());
        if (blobName(oldName).equals(blobName(newName))) {
            return write(source, renamed, conditions);
        }
        BlobClient destination = container.getBlobClient(blobName(newName));
        SavedPromptDetails created = create(destination, renamed);
        try {
            source.deleteWithResponse(null, conditions, null, null);
        } catch (RuntimeException e) {
            // Only roll back a definite rejection; a timed-out delete may already have succeeded.
            if (e instanceof BlobStorageException storageException
                    && (storageException.getStatusCode() == 404 || storageException.getStatusCode() == 412)) {
                try {
                    destination.deleteWithResponse(null, etagConditions(created.etag()), null, null);
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                    throw new SavedPromptRenameException(e);
                }
                throw e;
            }
            throw new SavedPromptRenameException(e);
        }
        return created;
    }

    private SavedPromptDetails read(BlobClient blob, BlobRequestConditions conditions) {
        BlobDownloadContentResponse response = blob.downloadContentWithResponse(null, conditions, null, null);
        SavedPrompt saved = response.getValue().toObject(SavedPrompt.class);
        return new SavedPromptDetails(saved.name(), saved.prompt(), response.getHeaders().getValue("ETag"));
    }

    private SavedPromptDetails write(BlobClient blob, SavedPrompt saved, BlobRequestConditions conditions) {
        BlobParallelUploadOptions options = new BlobParallelUploadOptions(BinaryData.fromObject(saved))
                .setHeaders(new BlobHttpHeaders().setContentType("application/json"))
                .setRequestConditions(conditions);
        String etag = blob.uploadWithResponse(options, null, null).getValue().getETag();
        return new SavedPromptDetails(saved.name(), saved.prompt(), etag);
    }

    private SavedPromptDetails create(BlobClient blob, SavedPrompt saved) {
        try {
            return write(blob, saved, new BlobRequestConditions().setIfNoneMatch("*"));
        } catch (BlobStorageException e) {
            if (BlobErrorCode.BLOB_ALREADY_EXISTS.equals(e.getErrorCode())
                    || BlobErrorCode.CONDITION_NOT_MET.equals(e.getErrorCode())) {
                throw new SavedPromptNameConflictException(e);
            }
            throw e;
        }
    }

    private static BlobRequestConditions etagConditions(String etag) {
        if (etag == null || etag.length() > 256 || !etag.matches("\"[^\"\\s,]+\"")) {
            throw new IllegalArgumentException("A single saved prompt ETag is required. Refresh and try again.");
        }
        return new BlobRequestConditions().setIfMatch(etag);
    }

    private static void validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt is required.");
        }
        if (prompt.length() > 100_000) {
            throw new IllegalArgumentException("Prompt must be at most 100,000 characters.");
        }
    }

    private static String validateNewName(String name) {
        String normalized = normalizeName(name);
        if (normalized.chars().anyMatch(c -> "/\\:*?\"<>|".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Name cannot contain / \\ : * ? \" < > |.");
        }
        return normalized;
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required.");
        }
        String normalized = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
        if (normalized.length() > 100 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Name must be at most 100 characters with no control characters.");
        }
        return normalized;
    }

    private static String blobName(String name) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) + ".json";
    }
}
