package io.weidongxu.webapp.imagecreator;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.core.util.BinaryData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private final BlobServiceClient serviceClient;
    private final BlobContainerClient containerClient;
    private final PromptStorageService promptStorageService;

    public StorageService(AppConfig config, PromptStorageService promptStorageService) {
        this.serviceClient = new BlobServiceClientBuilder()
                .endpoint("https://" + config.getStorageAccountName() + ".blob.core.windows.net")
                .credential(config.getCredential())
                .buildClient();
        this.containerClient = serviceClient.getBlobContainerClient(config.getStorageContainerName());
        this.promptStorageService = promptStorageService;
    }

    public String upload(byte[] imageData, String outputFormat) {
        String ext = switch (outputFormat == null ? "png" : outputFormat.toLowerCase()) {
            case "jpeg" -> ".jpg";
            case "webp" -> ".webp";
            default -> ".png";
        };
        String contentType = switch (ext) {
            case ".jpg" -> "image/jpeg";
            case ".webp" -> "image/webp";
            default -> "image/png";
        };

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String datePrefix = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String blobName = datePrefix + "/image_"
                + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
                + ext;

        BlobParallelUploadOptions options = new BlobParallelUploadOptions(BinaryData.fromBytes(imageData))
                .setHeaders(new BlobHttpHeaders().setContentType(contentType));

        containerClient.getBlobClient(blobName).uploadWithResponse(options, null, null);
        return blobName;
    }

    public List<ImageInfo> listImages(String prefix) {
        Map<String, String> storedPrompts = loadStoredPrompts();
        ListBlobsOptions options = new ListBlobsOptions()
                .setDetails(new BlobListDetails().setRetrieveMetadata(true));
        if (prefix != null && !prefix.isBlank()) {
            options.setPrefix(prefix);
        }

        return containerClient.listBlobs(options, null).stream()
                .sorted(Comparator.comparing(
                        (BlobItem item) -> item.getProperties().getLastModified(),
                        Comparator.reverseOrder()))
                .map(item -> new ImageInfo(
                        item.getName(),
                        item.getProperties().getLastModified().toString(),
                        storedPrompts.containsKey(item.getName())
                                ? storedPrompts.get(item.getName())
                                : decodePrompt(item.getMetadata())))
                .collect(Collectors.toList());
    }

    private Map<String, String> loadStoredPrompts() {
        try {
            return promptStorageService.listPrompts();
        } catch (Exception e) {
            log.error("Prompt table unavailable; using legacy blob metadata", e);
            return Collections.emptyMap();
        }
    }

    public byte[] download(String blobName) {
        return containerClient.getBlobClient(blobName).downloadContent().toBytes();
    }

    public boolean delete(String blobName) {
        boolean blobDeleted = containerClient.getBlobClient(blobName).deleteIfExists();
        boolean promptDeleted = false;
        try {
            promptDeleted = promptStorageService.delete(blobName);
        } catch (Exception e) {
            log.error("Image {} deleted from blob storage, but prompt cleanup failed", blobName, e);
        }
        return blobDeleted || promptDeleted;
    }

    public String generateSasUrl(String blobName) {
        OffsetDateTime now = OffsetDateTime.now();
        UserDelegationKey delegationKey = serviceClient.getUserDelegationKey(
                now.minusMinutes(5), now.plusHours(1));

        BlobClient blobClient = containerClient.getBlobClient(blobName);
        BlobSasPermission permission = new BlobSasPermission().setReadPermission(true);
        BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(
                now.plusHours(1), permission);

        String sasToken = blobClient.generateUserDelegationSas(sasValues, delegationKey);
        return blobClient.getBlobUrl() + "?" + sasToken;
    }

    private String decodePrompt(Map<String, String> metadata) {
        if (metadata == null) return "";
        String raw = metadata.getOrDefault("prompt", "");
        if (raw.isEmpty()) return "";
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Already plain text (old entries before encoding was added)
            return raw;
        }
    }
}
