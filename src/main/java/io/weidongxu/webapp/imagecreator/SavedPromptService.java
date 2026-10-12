package io.weidongxu.webapp.imagecreator;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobRequestConditions;
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

    public SavedPrompt save(SavedPrompt request) {
        if (request == null) {
            throw new IllegalArgumentException("Name and prompt are required.");
        }
        String name = validateName(request.name());
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("Prompt is required.");
        }
        if (request.prompt().length() > 100_000) {
            throw new IllegalArgumentException("Prompt must be at most 100,000 characters.");
        }
        SavedPrompt saved = new SavedPrompt(name, request.prompt());
        container.createIfNotExists();
        BlobParallelUploadOptions options = new BlobParallelUploadOptions(BinaryData.fromObject(saved))
                .setHeaders(new BlobHttpHeaders().setContentType("application/json"))
                .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"));
        container.getBlobClient(blobName(name)).uploadWithResponse(options, null, null);
        return saved;
    }

    public List<SavedPrompt> list() {
        container.createIfNotExists();
        return container.listBlobs().stream()
                .filter(item -> item.getName().endsWith(".json"))
                .map(item -> container.getBlobClient(item.getName()).downloadContent().toObject(SavedPrompt.class))
                .sorted(Comparator.comparing(SavedPrompt::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public boolean delete(String name) {
        return container.getBlobClient(blobName(validateName(name))).deleteIfExists();
    }

    private static String validateName(String name) {
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
