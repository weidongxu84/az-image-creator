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
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StorageService {

    private final BlobServiceClient serviceClient;
    private final BlobContainerClient containerClient;

    public StorageService(AppConfig config) {
        this.serviceClient = new BlobServiceClientBuilder()
                .endpoint("https://" + config.getStorageAccountName() + ".blob.core.windows.net")
                .credential(config.getCredential())
                .buildClient();
        this.containerClient = serviceClient.getBlobContainerClient(config.getStorageContainerName());
    }

    public String upload(byte[] imageData, String prompt, String outputFormat) {
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

        LocalDateTime now = LocalDateTime.now();
        String datePrefix = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String blobName = datePrefix + "/image_"
                + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
                + ext;

        Map<String, String> metadata = new HashMap<>();
        if (prompt != null && !prompt.isBlank()) {
            String encoded = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
            // Azure blob metadata limit is 8 KiB total. If encoded prompt exceeds limit, truncate original and re-encode.
            if (encoded.length() > 7500) {
                String truncated = prompt.length() > 4000 ? prompt.substring(0, 4000) : prompt;
                encoded = URLEncoder.encode(truncated, StandardCharsets.UTF_8);
            }
            metadata.put("prompt", encoded);
        }

        BlobParallelUploadOptions options = new BlobParallelUploadOptions(BinaryData.fromBytes(imageData))
                .setHeaders(new BlobHttpHeaders().setContentType(contentType))
                .setMetadata(metadata);

        containerClient.getBlobClient(blobName).uploadWithResponse(options, null, null);
        return blobName;
    }

    public List<ImageInfo> listImages(String prefix) {
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
                        decodePrompt(item.getMetadata())))
                .collect(Collectors.toList());
    }

    public byte[] download(String blobName) {
        return containerClient.getBlobClient(blobName).downloadContent().toBytes();
    }

    public void delete(String blobName) {
        containerClient.getBlobClient(blobName).delete();
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
